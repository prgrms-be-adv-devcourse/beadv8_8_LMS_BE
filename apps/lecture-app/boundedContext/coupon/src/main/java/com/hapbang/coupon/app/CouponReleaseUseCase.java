package com.hapbang.coupon.app;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.hapbang.coupon.config.CouponClockConfig;
import com.hapbang.coupon.domain.CouponUsage;
import com.hapbang.coupon.domain.CouponUsageStatus;
import com.hapbang.coupon.domain.MemberCouponStatus;
import com.hapbang.coupon.domain.ProcessedOperation;
import com.hapbang.coupon.domain.usage.UsageContentKey;
import com.hapbang.coupon.global.exception.CouponErrorCode;
import com.hapbang.coupon.global.exception.CouponUsageException;
import com.hapbang.coupon.out.CouponUsageRepository;
import com.hapbang.coupon.out.MemberCouponRepository;
import com.hapbang.coupon.out.MemberCouponState;
import com.hapbang.coupon.out.ProcessedOperationRepository;
import com.hapbang.shared.coupon.api.CouponReleaseRequest;
import com.hapbang.shared.coupon.api.CouponReleaseResponse;

import lombok.extern.slf4j.Slf4j;

/**
 * 쿠폰 해제(강의 → 쿠폰 내부 API). 나열한 RESERVED 사용 시도를 한 트랜잭션에서 모두 해제하거나 하나도 해제하지 않는다.
 * <ul>
 *     <li>사용 시도는 (발급 쿠폰, generation)으로 찾고, 그 주문의 것이어야 한다. 없거나 다른 주문·USED·이미 해제됨이면 409
 *     {@code COUPON_RELEASE_NOT_ALLOWED}다.</li>
 *     <li>발급 쿠폰 ID 오름차순으로 generation 조건부 갱신을 한다. 만료 전이면 AVAILABLE, 만료 뒤면 EXPIRED로 끝난다. 0행이면 해제 불가다.</li>
 *     <li>같은 작업 ID·같은 내용이면 처음 결과를 다시 주고, 다른 내용이면 409 {@code COUPON_WORK_CONFLICT}다. 내용 키의 회원은 사용 시도의
 *     회원이다(요청에 회원이 없다).</li>
 *     <li>보상 완료 근거(compensationRef)는 기록만 하고 검증하지 않는다.</li>
 * </ul>
 * 사용 경로라 Redis를 쓰지 않는다.
 */
@Slf4j
@Service
public class CouponReleaseUseCase {

    private final CouponUsageSupport usageSupport;
    private final MemberCouponRepository memberCouponRepository;
    private final CouponUsageRepository couponUsageRepository;
    private final ProcessedOperationRepository processedOperationRepository;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public CouponReleaseUseCase(CouponUsageSupport usageSupport, MemberCouponRepository memberCouponRepository,
                                CouponUsageRepository couponUsageRepository,
                                ProcessedOperationRepository processedOperationRepository,
                                PlatformTransactionManager transactionManager,
                                @Qualifier(CouponClockConfig.CLOCK) Clock clock) {
        this.usageSupport = usageSupport;
        this.memberCouponRepository = memberCouponRepository;
        this.couponUsageRepository = couponUsageRepository;
        this.processedOperationRepository = processedOperationRepository;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    public CouponReleaseResponse release(CouponReleaseRequest request) {
        String operationId = CouponUsageSupport.RELEASE_OPERATION_PREFIX + request.workId();
        try {
            return transaction.execute(status -> releaseInTransaction(request, operationId));
        } catch (CouponUsageException exception) {
            if (exception.getErrorCode() != CouponErrorCode.COUPON_RELEASE_NOT_ALLOWED) {
                throw exception;
            }
            // 같은 작업 ID의 같은 요청이 동시에 먼저 끝났으면 그 결과를 다시 준다
            return replayIfDone(request, operationId).orElseThrow(() -> exception);
        } catch (DataIntegrityViolationException exception) {
            if (!CouponUsageSupport.isOperationIdViolation(exception)) {
                throw exception;
            }
            return replayIfDone(request, operationId).orElseThrow(() -> exception);
        }
    }

    private CouponReleaseResponse releaseInTransaction(CouponReleaseRequest request, String operationId) {
        Optional<ProcessedOperation> recorded = usageSupport.findOperation(operationId);
        List<CouponUsage> usages;
        try {
            usages = findUsages(request);
        } catch (CouponUsageException exception) {
            if (recorded.isPresent()) {
                // 같은 작업 ID가 다른 사용 시도로 쓰였다
                throw new CouponUsageException(CouponErrorCode.COUPON_WORK_CONFLICT);
            }
            throw exception;
        }
        String contentKey = contentKey(request, usages.getFirst().getMemberId());
        if (recorded.isPresent()) {
            return replay(recorded.get(), request, contentKey);
        }

        Instant now = clock.instant();
        List<CouponReleaseResponse.Usage> released = new ArrayList<>();
        for (CouponUsage usage : usages) {
            if (usage.getStatus() != CouponUsageStatus.RESERVED) {
                throw notAllowed(usage.getMemberCouponId(), "예약 중인 사용 시도가 아닙니다: " + usage.getStatus());
            }
            MemberCouponStatus releasedTo = casRelease(usage, now);
            usage.release(request.workId(), request.reason(), request.compensationRef(), releasedTo, now);
            released.add(new CouponReleaseResponse.Usage(usage.getMemberCouponId(), usage.getGeneration(),
                    releasedTo.name()));
        }
        processedOperationRepository.saveAndFlush(ProcessedOperation.ofWork(operationId, contentKey,
                CouponUsageSupport.RESULT_RELEASED, null, now));
        log.info("쿠폰 예약을 해제했다 workId={}, orderId={}, usages={}, reason={}", request.workId(),
                request.orderId(), released, request.reason());
        return new CouponReleaseResponse(request.workId(), released);
    }

    /** 요청한 사용 시도를 발급 쿠폰 ID 오름차순으로 찾는다. 없거나 다른 주문이면 해제 불가 */
    private List<CouponUsage> findUsages(CouponReleaseRequest request) {
        if (request.usages().isEmpty()) {
            throw notAllowed(null, "해제할 사용 시도가 없습니다.");
        }
        Set<Long> seen = new HashSet<>();
        List<CouponUsage> usages = new ArrayList<>();
        for (CouponReleaseRequest.Usage requested : request.usages().stream()
                .sorted(Comparator.comparingLong(CouponReleaseRequest.Usage::memberCouponId))
                .toList()) {
            if (!seen.add(requested.memberCouponId())) {
                throw notAllowed(requested.memberCouponId(), "같은 발급 쿠폰이 두 번 들어 있습니다.");
            }
            CouponUsage usage = couponUsageRepository
                    .findByMemberCouponIdAndGeneration(requested.memberCouponId(), requested.generation())
                    .filter(found -> found.belongsTo(request.orderId()))
                    .orElseThrow(() -> notAllowed(requested.memberCouponId(), "이 주문의 사용 시도가 아닙니다."));
            usages.add(usage);
        }
        return usages;
    }

    /** 해제 조건부 갱신. 1행이면 갱신 뒤 상태(AVAILABLE·EXPIRED)를 돌려준다. 0행이면 해제 불가(USED·다른 generation 등) */
    private MemberCouponStatus casRelease(CouponUsage usage, Instant now) {
        int updated = memberCouponRepository.release(usage.getMemberCouponId(), usage.getGeneration(), now);
        Optional<MemberCouponState> state = memberCouponRepository.findStateById(usage.getMemberCouponId());
        if (updated != 1) {
            throw notAllowed(usage.getMemberCouponId(), "발급 쿠폰이 이 사용 시도로 예약된 상태가 아닙니다: "
                    + state.map(found -> found.status() + "/generation=" + found.generation()).orElse("없음"));
        }
        return state.map(MemberCouponState::status)
                .orElseThrow(() -> new IllegalStateException("해제한 발급 쿠폰을 다시 읽지 못했습니다."));
    }

    private Optional<CouponReleaseResponse> replayIfDone(CouponReleaseRequest request, String operationId) {
        return usageSupport.findOperation(operationId)
                .map(recorded -> transaction.execute(status -> {
                    List<CouponUsage> usages;
                    try {
                        usages = findUsages(request);
                    } catch (CouponUsageException exception) {
                        throw new CouponUsageException(CouponErrorCode.COUPON_WORK_CONFLICT);
                    }
                    return replay(recorded, request, contentKey(request, usages.getFirst().getMemberId()));
                }));
    }

    private CouponReleaseResponse replay(ProcessedOperation recorded, CouponReleaseRequest request,
                                         String contentKey) {
        if (!recorded.sameContent(contentKey)) {
            throw new CouponUsageException(CouponErrorCode.COUPON_WORK_CONFLICT);
        }
        List<CouponReleaseResponse.Usage> usages = couponUsageRepository
                .findByOrderIdAndReleaseWorkIdOrderByMemberCouponIdAsc(request.orderId(), request.workId()).stream()
                .map(usage -> new CouponReleaseResponse.Usage(usage.getMemberCouponId(), usage.getGeneration(),
                        usage.getReleasedTo().name()))
                .toList();
        return new CouponReleaseResponse(request.workId(), usages);
    }

    private static CouponUsageException notAllowed(Long memberCouponId, String detail) {
        return new CouponUsageException(CouponErrorCode.COUPON_RELEASE_NOT_ALLOWED, detail, memberCouponId);
    }

    /** 요청 내용 키. 사용 시도의 순서와 무관하다. 보상 완료 근거도 내용에 넣는다 */
    static String contentKey(CouponReleaseRequest request, long memberId) {
        List<String> parts = new ArrayList<>();
        for (CouponReleaseRequest.Usage usage : request.usages()) {
            parts.add("u" + usage.memberCouponId() + "|g" + usage.generation());
        }
        parts.add("r" + request.reason());
        parts.add("x" + (request.compensationRef() == null ? "" : request.compensationRef()));
        return UsageContentKey.of(memberId, request.orderId(), parts);
    }
}
