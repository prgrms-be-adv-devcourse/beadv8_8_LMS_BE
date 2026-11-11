package com.hapbang.coupon.app;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.hapbang.coupon.app.port.PromotionBudget;
import com.hapbang.coupon.config.CouponBudgetWorkProperties;
import com.hapbang.coupon.config.CouponClockConfig;
import com.hapbang.coupon.domain.BudgetWork;
import com.hapbang.coupon.domain.BudgetWorkKind;
import com.hapbang.coupon.domain.BudgetWorkStatus;
import com.hapbang.coupon.domain.Coupon;
import com.hapbang.coupon.out.BudgetWorkRepository;
import com.hapbang.coupon.out.CouponRepository;
import com.hapbang.coupon.out.MemberCouponRepository;

import lombok.extern.slf4j.Slf4j;

/** 영속 예산 작업 재시도. 선점·결과는 짧은 트랜잭션, HTTP는 그 밖에서 같은 작업 ID로 수행한다. */
@Slf4j
@Service
public class CouponBudgetWorkUseCase {
    private final BudgetWorkRepository budgetWorkRepository;
    private final CouponRepository couponRepository;
    private final MemberCouponRepository memberCouponRepository;
    private final CouponBudgetReleaseRecorder releaseRecorder;
    private final ObjectProvider<PromotionBudget> budgetProvider;
    private final CouponBudgetWorkProperties properties;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public CouponBudgetWorkUseCase(BudgetWorkRepository budgetWorkRepository, CouponRepository couponRepository,
            MemberCouponRepository memberCouponRepository, CouponBudgetReleaseRecorder releaseRecorder,
            ObjectProvider<PromotionBudget> budgetProvider, CouponBudgetWorkProperties properties,
            PlatformTransactionManager transactionManager, @Qualifier(CouponClockConfig.CLOCK) Clock clock) {
        this.budgetWorkRepository = budgetWorkRepository;
        this.couponRepository = couponRepository;
        this.memberCouponRepository = memberCouponRepository;
        this.releaseRecorder = releaseRecorder;
        this.budgetProvider = budgetProvider;
        this.properties = properties;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    public void runDue() {
        registerExpiredRemainders();
        PromotionBudget budget = budgetProvider.getIfAvailable();
        if (budget == null) {
            return;
        }
        List<String> ids = transaction.execute(status -> budgetWorkRepository.findDueIds(clock.instant(),
                Limit.of(properties.batchSize())));
        for (String id : ids) {
            try {
                retry(id, budget);
            } catch (RuntimeException exception) {
                // 결과 불명·기록 실패도 같은 작업 ID를 다음 lease 뒤에 다시 확인한다.
                log.warn("프로모션 예산 작업을 완료하지 못해 다음 회차로 넘긴다 workId={}", id, exception);
            }
        }
    }

    private void registerExpiredRemainders() {
        Instant now = clock.instant();
        long afterId = 0;
        while (true) {
            long cursor = afterId;
            List<Long> ids = transaction.execute(status -> memberCouponRepository.findExpiredPlatformBudgetIds(now,
                    cursor, Limit.of(properties.batchSize())));
            for (Long id : ids) {
                try {
                    releaseRecorder.recordDue(id, now);
                } catch (RuntimeException exception) {
                    log.warn("만료된 플랫폼 쿠폰 예산 해제를 등록하지 못해 다음 회차로 넘긴다 memberCouponId={}", id, exception);
                }
            }
            if (ids.size() < properties.batchSize()) {
                return;
            }
            afterId = ids.getLast();
        }
    }

    private void retry(String id, PromotionBudget budget) {
        BudgetWork work = transaction.execute(status -> {
            Instant now = clock.instant();
            if (budgetWorkRepository.claim(id, now, now.plus(properties.lease())) == 0) {
                return null;
            }
            return budgetWorkRepository.findById(id).orElseThrow();
        });
        if (work == null) {
            return;
        }
        PromotionBudget.BudgetResult result = budget.status(id);
        if (result == null || result.outcome() == PromotionBudget.Outcome.UNKNOWN) {
            if (work.getKind() == BudgetWorkKind.RESERVE) {
                Coupon coupon = transaction.execute(status -> couponRepository.findById(work.getCouponId()).orElseThrow());
                result = budget.reserve(id, work.getCouponId(), coupon.getPolicyVersion(), work.getAmount());
            } else {
                result = budget.release(id, work.getReservationId(), work.getAmount(), work.getTarget(), work.getReason());
            }
        }
        if (result == null || result.outcome() == PromotionBudget.Outcome.UNKNOWN
                || (result.outcome() == PromotionBudget.Outcome.SUCCEEDED && work.getKind() == BudgetWorkKind.RESERVE
                    && (result.reservationId() == null || result.reservationId().isBlank()))) {
            return;
        }
        PromotionBudget.BudgetResult confirmed = result;
        transaction.executeWithoutResult(status -> {
            BudgetWork current = budgetWorkRepository.findForUpdateById(id).orElseThrow();
            if (current.getStatus() != BudgetWorkStatus.PENDING || current.getAttempt() != work.getAttempt()) {
                return;
            }
            if (confirmed.outcome() == PromotionBudget.Outcome.SUCCEEDED) {
                String reservationId = work.getKind() == BudgetWorkKind.RESERVE
                        ? confirmed.reservationId() : work.getReservationId();
                current.markDone(reservationId, clock.instant());
                if (work.getKind() == BudgetWorkKind.RESERVE) {
                    couponRepository.findById(work.getCouponId()).orElseThrow()
                            .recordBudgetReserved(reservationId, clock.instant());
                }
            } else {
                current.markFailed(confirmed.reason(), clock.instant());
                log.error("프로모션 예산 작업이 확정 거절됐다 workId={}, couponId={}, reason={}", id,
                        work.getCouponId(), confirmed.reason());
            }
        });
    }
}
