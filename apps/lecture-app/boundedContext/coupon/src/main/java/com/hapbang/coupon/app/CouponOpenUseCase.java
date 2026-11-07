package com.hapbang.coupon.app;

import java.time.Clock;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.hapbang.coupon.app.dto.CouponView;
import com.hapbang.coupon.app.port.CouponIssueStore;
import com.hapbang.coupon.app.port.CouponIssueStore.IssueControl;
import com.hapbang.coupon.app.port.PromotionBudgetPort;
import com.hapbang.coupon.app.port.PromotionBudgetPort.BudgetResult;
import com.hapbang.coupon.config.CouponClockConfig;
import com.hapbang.coupon.domain.BudgetWork;
import com.hapbang.coupon.domain.BudgetWorkStatus;
import com.hapbang.coupon.domain.Coupon;
import com.hapbang.coupon.domain.CouponStatus;
import com.hapbang.coupon.domain.CouponTargetType;
import com.hapbang.coupon.domain.CouponType;
import com.hapbang.coupon.domain.LectureSaleRule;
import com.hapbang.coupon.global.exception.CouponErrorCode;
import com.hapbang.coupon.global.exception.CouponException;
import com.hapbang.coupon.out.BudgetWorkRepository;
import com.hapbang.coupon.out.redis.CouponRedisProperties;

import lombok.extern.slf4j.Slf4j;

/**
 * 발급 열기. 판매자 쿠폰은 승인(REVIEW → OPEN), 플랫폼 쿠폰은 발행(DRAFT → OPEN)이다.
 * 예산(billing)·Redis 호출은 DB 트랜잭션 밖에서 하고, 트랜잭션은 단계마다 짧게 나눈다. Redis 접수는 DB OPEN 커밋 뒤에만 연다.
 * <ol>
 *     <li>특정 강의 대상이면 강의 조회 내부 API로 강의가 판매 중인지 본다. 아니면 409이고 아무것도 하지 않는다. 모든 강의 대상은 보지 않는다.</li>
 *     <li>(플랫폼만) 예산 예약 작업을 PENDING으로 저장(이미 있으면 그 작업) → billing 예약 → 결과를 작업·쿠폰에 기록.
 *     가장 최근 시도의 작업이 DONE이면 billing을 다시 부르지 않고, PENDING이면 같은 작업 ID로 다시 부른다(billing 멱등).
 *     거절(FAILED)됐으면 그 작업은 기록으로 남기고 시도 번호를 올린 새 작업 ID로 다시 예약한다.</li>
 *     <li>Redis 발급을 준비한다(stock·epoch·기간·fence, status는 접수 불가). 같은 값으로 다시 준비해도 남은 stock을 덮지 않는다.</li>
 *     <li>쿠폰을 다시 읽어 아직 열 대상 상태면 Redis 준비를 기록하고 OPEN으로 바꿔 커밋한다(version을 올린다).
 *     그 사이 다른 요청이 먼저 열었으면 version만 올린다. 그 밖의 상태로 바뀌었으면 409이고 Redis는 접수 불가로 남는다.</li>
 *     <li>커밋된 version을 fence로 Redis 접수를 연다({@link CouponSupport#allowIssue}).</li>
 * </ol>
 * 4단계 전에 실패하면 쿠폰은 열기 전 상태(DRAFT·REVIEW)로 남고 Redis는 접수 불가다. 같은 요청을 다시 보내면 남은 단계부터 이어 간다.
 * 이미 OPEN인 쿠폰을 다시 열면(유형이 맞을 때) 강의 판매 상태 확인·예산·준비 없이 version만 올려 커밋하고 Redis 접수를 다시 연다.
 * 5단계 응답을 잃었거나 실패한 차단이 Redis를 막아 둔 경우 이것으로 푼다(이미 연 발급의 복구라 판매 상태를 다시 보지 않는다).
 */
@Slf4j
@Service
public class CouponOpenUseCase {

    private final CouponSupport couponSupport;
    private final BudgetWorkRepository budgetWorkRepository;
    private final CouponIssueStore couponIssueStore;
    private final ObjectProvider<PromotionBudgetPort> budgetPortProvider;
    private final CouponRedisProperties redisProperties;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public CouponOpenUseCase(CouponSupport couponSupport, BudgetWorkRepository budgetWorkRepository,
                             CouponIssueStore couponIssueStore, ObjectProvider<PromotionBudgetPort> budgetPortProvider,
                             CouponRedisProperties redisProperties, PlatformTransactionManager transactionManager,
                             @Qualifier(CouponClockConfig.CLOCK) Clock clock) {
        this.couponSupport = couponSupport;
        this.budgetWorkRepository = budgetWorkRepository;
        this.couponIssueStore = couponIssueStore;
        this.budgetPortProvider = budgetPortProvider;
        this.redisProperties = redisProperties;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /** 판매자 쿠폰 승인. 예산 단계가 없다 */
    public CouponView approve(long adminId, long couponId) {
        Coupon coupon = couponSupport.findCoupon(couponId);
        requireOpenable(coupon, CouponType.SELLER);
        if (coupon.getStatus() != CouponStatus.OPEN) {
            requireLectureOnSale(coupon);
        }
        CouponView view = coupon.getStatus() == CouponStatus.OPEN ? commitOpenAndAllowIssue(couponId) : openIssue(coupon);
        log.info("판매자 쿠폰을 승인해 발급을 열었다 couponId={}, adminId={}", couponId, adminId);
        return view;
    }

    /** 플랫폼 쿠폰 발행. 예산 연동(billing)이 없으면 열지 않는다 */
    public CouponView open(long adminId, long couponId) {
        PromotionBudgetPort budgetPort = budgetPortProvider.getIfAvailable();
        if (budgetPort == null) {
            throw new CouponException(CouponErrorCode.PROMOTION_BUDGET_UNAVAILABLE,
                    "프로모션 예산 연동이 없어 플랫폼 쿠폰을 열 수 없습니다.");
        }
        Coupon current = couponSupport.findCoupon(couponId);
        requireOpenable(current, CouponType.PLATFORM);
        if (current.getStatus() != CouponStatus.OPEN) {
            requireLectureOnSale(current);
        }
        Preparation preparation = prepareBudgetWorkInTransaction(couponId);
        Coupon coupon = preparation.coupon();
        if (coupon.getStatus() == CouponStatus.OPEN) {
            CouponView view = commitOpenAndAllowIssue(couponId);
            log.info("열린 플랫폼 쿠폰의 Redis 발급 접수를 다시 열었다 couponId={}, adminId={}", couponId, adminId);
            return view;
        }
        BudgetWork work = preparation.work();
        switch (work.getStatus()) {
            // 준비 단계가 거절된 작업 대신 새 시도 작업을 돌려주므로 오지 않는다
            case FAILED -> throw new CouponException(CouponErrorCode.PROMOTION_BUDGET_REJECTED);
            case PENDING -> reserveBudget(budgetPort, coupon, work);
            case DONE -> {
                // 작업 결과와 쿠폰 기록은 한 트랜잭션에 쓰므로 보통 함께 있다. 없으면 같은 값으로 다시 기록한다(반복 무시)
                if (coupon.getBudgetReservationId() == null) {
                    recordReserved(couponId, work.getWorkId(), work.getResult());
                }
            }
        }
        CouponView view = openIssue(coupon);
        log.info("플랫폼 쿠폰 발급을 열었다 couponId={}, adminId={}", couponId, adminId);
        return view;
    }

    /**
     * {@link #prepareBudgetWork}를 짧은 트랜잭션으로 실행한다. 같은 쿠폰을 동시에 연 다른 요청이 같은 새 작업 ID를 먼저 저장해
     * PK가 충돌하면 409(동시 변경)이다. 다시 보내면 그 작업을 찾아 이어 간다.
     */
    private Preparation prepareBudgetWorkInTransaction(long couponId) {
        try {
            return transaction.execute(status -> prepareBudgetWork(couponId));
        } catch (DataIntegrityViolationException exception) {
            throw new CouponException(CouponErrorCode.COUPON_CONCURRENT_UPDATE, exception);
        }
    }

    /**
     * 열기 전 상태 확인과 예산 예약 작업(PENDING) 저장. 이미 OPEN이면 작업 없이 쿠폰만 돌려준다.
     * 이 쿠폰·정책 버전의 가장 최근 시도 작업을 찾아, 없으면 첫 시도를 만들고, 거절(FAILED)됐으면 다음 시도 번호로 새 작업을 만든다.
     * 그 밖(PENDING·DONE)이면 그 작업을 돌려준다.
     */
    private Preparation prepareBudgetWork(long couponId) {
        Coupon coupon = couponSupport.findCoupon(couponId);
        requireOpenable(coupon, CouponType.PLATFORM);
        if (coupon.getStatus() == CouponStatus.OPEN) {
            return new Preparation(coupon, null);
        }
        int attempt = BudgetWork.FIRST_ATTEMPT;
        BudgetWork work = budgetWorkRepository.findById(coupon.budgetReserveWorkId(attempt)).orElse(null);
        // 시도 번호는 앞 시도가 거절됐을 때만 올리므로, 거절된 작업 다음 번호를 차례로 보면 가장 최근 시도에 닿는다
        while (work != null && work.getStatus() == BudgetWorkStatus.FAILED) {
            attempt++;
            work = budgetWorkRepository.findById(coupon.budgetReserveWorkId(attempt)).orElse(null);
        }
        if (work == null) {
            work = budgetWorkRepository.save(BudgetWork.reserve(coupon, attempt, clock.instant()));
            if (attempt > BudgetWork.FIRST_ATTEMPT) {
                log.info("거절된 프로모션 예산 예약을 새 작업으로 다시 시도한다 couponId={}, workId={}, attempt={}",
                        couponId, work.getWorkId(), attempt);
            }
        }
        return new Preparation(coupon, work);
    }

    /**
     * billing 예산 예약(트랜잭션 밖). 결과를 모르거나(UNKNOWN·호출 예외·예약 ID 없음) 하면 작업을 PENDING으로 두고 503이다.
     */
    private void reserveBudget(PromotionBudgetPort budgetPort, Coupon coupon, BudgetWork work) {
        long couponId = coupon.getId();
        BudgetResult result;
        try {
            result = budgetPort.reserve(work.getWorkId(), couponId, coupon.getPolicyVersion(), work.getAmount());
        } catch (RuntimeException exception) {
            throw new CouponException(CouponErrorCode.PROMOTION_BUDGET_UNAVAILABLE, exception);
        }
        PromotionBudgetPort.Outcome outcome = result == null ? PromotionBudgetPort.Outcome.UNKNOWN : result.outcome();
        if (outcome == PromotionBudgetPort.Outcome.SUCCEEDED
                && (result.reservationId() == null || result.reservationId().isBlank())) {
            outcome = PromotionBudgetPort.Outcome.UNKNOWN;
        }
        switch (outcome) {
            case SUCCEEDED -> {
                recordReserved(couponId, work.getWorkId(), result.reservationId());
                log.info("프로모션 예산을 예약했다 couponId={}, workId={}, amount={}, reservationId={}", couponId,
                        work.getWorkId(), work.getAmount(), result.reservationId());
            }
            case REJECTED -> {
                transaction.executeWithoutResult(status -> budgetWorkRepository.findById(work.getWorkId()).orElseThrow()
                        .markFailed(result.reason(), clock.instant()));
                log.info("프로모션 예산 예약이 거절돼 발급을 열지 않았다 couponId={}, workId={}, amount={}, reason={}",
                        couponId, work.getWorkId(), work.getAmount(), result.reason());
                throw new CouponException(CouponErrorCode.PROMOTION_BUDGET_REJECTED);
            }
            default -> throw new CouponException(CouponErrorCode.PROMOTION_BUDGET_UNAVAILABLE);
        }
    }

    /** 예약 성공을 작업(DONE)과 쿠폰(예약 ID)에 함께 기록한다. 같은 값이면 둘 다 바꾸지 않는다 */
    private void recordReserved(long couponId, String workId, String reservationId) {
        transaction.executeWithoutResult(status -> {
            budgetWorkRepository.findById(workId).orElseThrow().markDone(reservationId, clock.instant());
            couponSupport.findCoupon(couponId).recordBudgetReserved(reservationId, clock.instant());
        });
    }

    /** Redis 발급을 준비한 뒤(트랜잭션 밖) 쿠폰을 OPEN으로 커밋하고 Redis 접수를 연다 */
    private CouponView openIssue(Coupon coupon) {
        long couponId = coupon.getId();
        IssueControl control = new IssueControl(couponId, coupon.getEpoch(), coupon.remainingQuantity(),
                coupon.getIssueStartAt(), coupon.getIssueEndAt(), redisProperties.keysExpireAt(coupon.getIssueEndAt()),
                coupon.getVersion());
        try {
            couponIssueStore.prepare(control);
        } catch (IllegalStateException exception) {
            // 같은 epoch가 Redis에서 이미 종료됐다
            throw new CouponException(CouponErrorCode.INVALID_COUPON_STATUS, "이미 종료된 발급은 다시 열 수 없습니다.",
                    exception);
        }
        return commitOpenAndAllowIssue(couponId);
    }

    /** 쿠폰을 OPEN으로 커밋하고(이미 OPEN이면 version만 올린다) 커밋된 version으로 Redis 접수를 연다 */
    private CouponView commitOpenAndAllowIssue(long couponId) {
        Coupon opened = transaction.execute(status -> markOpen(couponId));
        couponSupport.allowIssue(couponId, opened.getVersion());
        return CouponView.from(opened);
    }

    /** 커밋 뒤 version을 읽어야 하므로 엔티티를 돌려준다 */
    private Coupon markOpen(long couponId) {
        Coupon coupon = couponSupport.findCouponIncrementingVersion(couponId);
        if (coupon.getStatus() == CouponStatus.OPEN) {
            // 같은 쿠폰을 연 다른 요청이 먼저 끝났거나 다시 부른 요청이다. version만 올려 Redis 접수를 다시 연다
            return coupon;
        }
        if (coupon.getStatus() != statusBeforeOpen(coupon.getType())) {
            throw new CouponException(CouponErrorCode.INVALID_COUPON_STATUS, "발급을 여는 사이 쿠폰 상태가 바뀌었습니다.");
        }
        coupon.markRedisReady(clock.instant());
        coupon.open(clock.instant());
        return coupon;
    }

    /**
     * 특정 강의 대상이면 강의가 판매 중인지 본다(트랜잭션 밖). 아니면 409, 강의가 없으면 404, 강의 앱에 닿지 못하면 503이다.
     * 모든 강의 대상은 강의를 조회하지 않는다.
     */
    private void requireLectureOnSale(Coupon coupon) {
        if (coupon.getTargetType() != CouponTargetType.LECTURE) {
            return;
        }
        LectureSaleRule.requireOnSale(couponSupport.findLecture(coupon.getLectureId()).saleStatus());
    }

    /** 유형이 맞고 열기 전 상태이거나 이미 OPEN(다시 열기)인가. 아니면 409 */
    private void requireOpenable(Coupon coupon, CouponType type) {
        if (coupon.getType() != type
                || (coupon.getStatus() != statusBeforeOpen(type) && coupon.getStatus() != CouponStatus.OPEN)) {
            throw new CouponException(CouponErrorCode.INVALID_COUPON_STATUS);
        }
    }

    private static CouponStatus statusBeforeOpen(CouponType type) {
        return type == CouponType.SELLER ? CouponStatus.REVIEW : CouponStatus.DRAFT;
    }

    private record Preparation(Coupon coupon, BudgetWork work) {
    }
}
