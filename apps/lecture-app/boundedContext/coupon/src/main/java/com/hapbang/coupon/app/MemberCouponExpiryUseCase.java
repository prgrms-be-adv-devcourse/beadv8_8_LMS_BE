package com.hapbang.coupon.app;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.hapbang.coupon.config.CouponClockConfig;
import com.hapbang.coupon.config.CouponExpiryProperties;
import com.hapbang.coupon.out.MemberCouponExpiryTarget;
import com.hapbang.coupon.out.MemberCouponRepository;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import lombok.extern.slf4j.Slf4j;

/**
 * 발급 쿠폰 만료(배치): 기한이 지난({@code expiresAt <= now}) AVAILABLE 쿠폰의 발급 쿠폰을 EXPIRED로 바꾼다. 경계는
 * {@link com.hapbang.coupon.domain.MemberCoupon#isExpiredAt}과 같다. 사용 경로는 이미 {@code expiresAt > now}로 막으므로 이 배치는
 * 쿠폰함 표시용 상태를 맞추고, 플랫폼 쿠폰이면 예산 해제 작업도 함께 남긴다.
 * <ul>
 *     <li>회차 시작 시각 하나로 판정한다. 만료 시각 → ID 커서로 {@code hapbang.coupon.expiry.batch-size}개씩 고른다.
 *     묶음 갱신·작업 등록이 실패하면 트랜잭션을 되돌리고 ID마다 별도 트랜잭션으로 격리한다. 실패한 ID도 커서가 지나가 다음 대상을
 *     처리하고, AVAILABLE로 남은 실패 건은 다음 회차에 다시 고른다. DB 연결 장애는 회차를 중단한다.</li>
 *     <li>RESERVED·USED는 건드리지 않는다(할인은 예약 성공 시점 기준으로 인정하고, 돌려받을 때 기한을 본다).</li>
 *     <li>플랫폼 쿠폰은 만료와 한 장의 최대 부담 M 해제 작업을 같은 트랜잭션에 남긴다. 지갑 호출은 예산 작업 배치가 커밋 뒤 실행한다.</li>
 *     <li>generation은 바꾸지 않는다.</li>
 * </ul>
 * lecture-app replica 1을 전제한다(다른 쿠폰 배치와 같다). 여럿이 돌아도 조건부 갱신이라 같은 쿠폰을 두 번 바꾸지 않는다.
 */
@Slf4j
@Service
public class MemberCouponExpiryUseCase {

    /** 만료한 발급 쿠폰 수 */
    static final String EXPIRED_METRIC = "coupon.expiry.expired";

    private final CouponBudgetReleaseRecorder budgetReleaseRecorder;
    private final MemberCouponRepository memberCouponRepository;
    private final CouponExpiryProperties properties;
    private final MeterRegistry meterRegistry;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public MemberCouponExpiryUseCase(CouponBudgetReleaseRecorder budgetReleaseRecorder,
                                     MemberCouponRepository memberCouponRepository, CouponExpiryProperties properties,
                                     ObjectProvider<MeterRegistry> meterRegistryProvider,
                                     PlatformTransactionManager transactionManager,
                                     @Qualifier(CouponClockConfig.CLOCK) Clock clock) {
        this.budgetReleaseRecorder = budgetReleaseRecorder;
        this.memberCouponRepository = memberCouponRepository;
        this.properties = properties;
        this.meterRegistry = meterRegistryProvider.getIfAvailable(SimpleMeterRegistry::new);
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /** 한 회차. 회차 시작 시각까지 기한이 지난 대상을 묶음으로 끝까지 만료한다 */
    public ExpiryRun expireDue() {
        Instant now = clock.instant();
        int batchSize = properties.batchSize();
        long expired = 0;
        int batches = 0;
        MemberCouponExpiryTarget cursor = null;
        while (true) {
            MemberCouponExpiryTarget after = cursor;
            List<MemberCouponExpiryTarget> targets = transaction.execute(status ->
                    memberCouponRepository.findExpiredCouponTargets(now, after == null ? null : after.expiresAt(),
                            after == null ? 0 : after.id(), Limit.of(batchSize)));
            batches++;
            if (targets.isEmpty()) {
                break;
            }
            expired += expireTargets(targets.stream().map(MemberCouponExpiryTarget::id).toList(), now);
            cursor = targets.getLast();
            if (targets.size() < batchSize) {
                break;
            }
        }
        if (expired > 0) {
            meterRegistry.counter(EXPIRED_METRIC).increment(expired);
        }
        ExpiryRun run = new ExpiryRun(now, expired, batches);
        log.info("발급 쿠폰 만료 배치를 마쳤다 now={}, expired={}, batches={}", now, expired, batches);
        return run;
    }

    private int expireTargets(List<Long> ids, Instant now) {
        try {
            return transaction.execute(status -> expireBatch(ids, now));
        } catch (RuntimeException batchFailure) {
            requireRowFailure(batchFailure);
            if (ids.size() == 1) {
                logItemFailure(ids.getFirst(), batchFailure);
                return 0;
            }
        }
        int expired = 0;
        for (Long id : ids) {
            try {
                expired += transaction.execute(status -> expireBatch(List.of(id), now));
            } catch (RuntimeException itemFailure) {
                requireRowFailure(itemFailure);
                logItemFailure(id, itemFailure);
            }
        }
        return expired;
    }

    private int expireBatch(List<Long> ids, Instant now) {
        int expired = memberCouponRepository.expire(ids, now);
        for (Long id : ids) {
            budgetReleaseRecorder.recordExpired(id, now);
        }
        return expired;
    }

    private static void requireRowFailure(RuntimeException failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof DataAccessResourceFailureException
                    || cause instanceof TransientDataAccessResourceException
                    || cause instanceof CannotCreateTransactionException) {
                throw failure;
            }
        }
    }

    private static void logItemFailure(long id, RuntimeException failure) {
        log.error("[COUPON_EXPIRY_ITEM_FAILED] 발급 쿠폰 만료를 되돌리고 다음 회차로 넘긴다 memberCouponId={}", id, failure);
    }

    /**
     * 회차 결과.
     *
     * @param now     판정 기준 시각(회차 시작)
     * @param expired 이번 회차에 EXPIRED로 바꾼 발급 쿠폰 수
     * @param batches 조회한 대상 묶음 수(실패 시 ID별 갱신 트랜잭션은 별도)
     */
    public record ExpiryRun(Instant now, long expired, int batches) {
    }
}
