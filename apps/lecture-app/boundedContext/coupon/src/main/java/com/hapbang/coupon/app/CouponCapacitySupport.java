package com.hapbang.coupon.app;

import java.time.Clock;
import java.time.Instant;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.hapbang.coupon.config.CouponClockConfig;
import com.hapbang.coupon.config.CouponLimitsProperties;
import com.hapbang.coupon.domain.Coupon;
import com.hapbang.coupon.domain.IssueCapacityReservation;
import com.hapbang.coupon.domain.IssueCapacityRule;
import com.hapbang.coupon.global.exception.CouponErrorCode;
import com.hapbang.coupon.global.exception.CouponException;
import com.hapbang.coupon.out.IssueCapacityRepository;
import com.hapbang.coupon.out.IssueCapacityReservationRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * 발급 저장소(Redis) 수량·용량 상한. 상한 값은 설정({@link CouponLimitsProperties}, 측정 전 플레이스홀더)이다.
 * <ul>
 *     <li>쿠폰별 상한: 발행과 발급 열기에서 쿠폰 수량을 검사한다.</li>
 *     <li>합산 상한: 발급을 열 때(Redis 준비 전) 쿠폰 수량만큼 용량을 예약한다. 예약 행 INSERT와 합계 조건부 UPSERT를 한 트랜잭션에서 하므로
 *     동시에 여러 쿠폰을 열어도 합계가 상한을 넘지 않는다. 쿠폰마다 한 번만 예약한다.</li>
 *     <li>해제({@link #release}): 대사 배치가 다시 열 수 없는 쿠폰(CLOSED·REJECTED)의 Redis 기록을 모두 정리한 뒤, 그리고 심사 거절(REJECTED)
 *     커밋 직후. 거절되는 판매자 쿠폰은 접수를 연 적이 없어 정리할 요청 기록이 없다.</li>
 *     <li>고아 해제({@link #releaseOrphan}): 발급 열기가 중간에 실패해 열기 전 상태(DRAFT·REVIEW)로 남았고 Redis key도 없는 쿠폰의 예약.
 *     예약 시각이 기준 시각 이전일 때만 지워 진행 중인 열기의 예약을 풀지 않는다.</li>
 * </ul>
 */
@Slf4j
@Component
public class CouponCapacitySupport {

    private final IssueCapacityRepository capacityRepository;
    private final IssueCapacityReservationRepository reservationRepository;
    private final CouponLimitsProperties limits;
    private final CouponReconciliationMetrics metrics;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public CouponCapacitySupport(IssueCapacityRepository capacityRepository,
                                 IssueCapacityReservationRepository reservationRepository,
                                 CouponLimitsProperties limits, CouponReconciliationMetrics metrics,
                                 PlatformTransactionManager transactionManager,
                                 @Qualifier(CouponClockConfig.CLOCK) Clock clock) {
        this.capacityRepository = capacityRepository;
        this.reservationRepository = reservationRepository;
        this.limits = limits;
        this.metrics = metrics;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /** 쿠폰 수량이 쿠폰별 상한 이하인가. 아니면 400({@code INVALID_COUPON_POLICY}) */
    public void requireWithinPerCoupon(int quantity) {
        requireWithinPerCoupon(quantity, limits);
    }

    /** {@link #requireWithinPerCoupon(int)}를 설정값으로 바로 검사한다(발행처럼 용량 예약이 필요 없는 곳) */
    static void requireWithinPerCoupon(int quantity, CouponLimitsProperties limits) {
        if (!IssueCapacityRule.withinPerCoupon(quantity, limits.maxQuantityPerCoupon())) {
            throw new CouponException(CouponErrorCode.INVALID_COUPON_POLICY,
                    "쿠폰 수량은 " + limits.maxQuantityPerCoupon() + "장 이하여야 합니다.");
        }
    }

    /**
     * 쿠폰 수량만큼 용량을 예약한다(트랜잭션을 직접 연다. 호출자 트랜잭션 밖에서 부른다). 이미 예약했으면(앞 열기 시도가 예약 뒤 실패) 다시
     * 예약하지 않고 그 예약의 시각만 지금으로 바꾼다(대사 배치가 이 열기의 예약을 고아로 풀지 않게). 그 사이 고아로 풀렸으면 새로 예약한다.
     * 합계가 합산 상한을 넘으면 예약 행도 남기지 않고 409({@code COUPON_CAPACITY_EXCEEDED})다.
     */
    public void reserve(Coupon coupon) {
        long couponId = coupon.getId();
        int quantity = coupon.getQuantity();
        Boolean reserved = transaction.execute(status -> {
            // 두 번째 시도는 예약을 다시 쓰려던 사이 고아 해제가 먼저 지운 경우다
            for (int attempt = 0; attempt < 2; attempt++) {
                if (reservationRepository.insertIfAbsent(couponId, quantity, clock.instant()) == 1) {
                    if (capacityRepository.reserve(quantity, limits.maxTotalQuantity(), clock.instant()) == 0) {
                        // 예외로 트랜잭션을 롤백해 방금 만든 예약 행도 지운다
                        throw new CouponException(CouponErrorCode.COUPON_CAPACITY_EXCEEDED);
                    }
                    return true;
                }
                if (reservationRepository.touch(couponId, clock.instant()) == 1) {
                    return false;
                }
            }
            throw new IllegalStateException("용량 예약을 만들지도 다시 쓰지도 못했다 couponId=" + couponId);
        });
        refreshMetric();
        if (Boolean.TRUE.equals(reserved)) {
            log.info("발급 저장소 용량을 예약했다 couponId={}, quantity={}", couponId, quantity);
        }
    }

    /**
     * 쿠폰의 용량 예약을 푼다(트랜잭션을 직접 연다). 쿠폰의 Redis 기록을 모두 정리한 뒤에만 부른다. 예약이 없으면(이미 풀었다) 아무것도 하지 않는다.
     *
     * @return 이번에 풀었는가
     * @throws IllegalStateException 합계가 예약 수량보다 작다(예약 기록이 어긋났다). 예약 행도 지우지 않는다
     */
    public boolean release(long couponId) {
        return release(couponId, null);
    }

    /**
     * 고아 예약을 푼다(트랜잭션을 직접 연다). 예약 시각이 {@code cutoff} 이전일 때만 푼다. 그 사이 열기 재시도가 예약을 다시 썼으면 풀지 않는다.
     *
     * @return 이번에 풀었는가
     * @throws IllegalStateException 합계가 예약 수량보다 작다(예약 기록이 어긋났다). 예약 행도 지우지 않는다
     */
    public boolean releaseOrphan(long couponId, Instant cutoff) {
        return release(couponId, cutoff);
    }

    private boolean release(long couponId, Instant cutoff) {
        Boolean released = transaction.execute(status -> {
            IssueCapacityReservation reservation = reservationRepository.findById(couponId).orElse(null);
            if (reservation == null) {
                return false;
            }
            int deleted = cutoff == null ? reservationRepository.deleteByCouponId(couponId)
                    : reservationRepository.deleteByCouponIdReservedAtOrBefore(couponId, cutoff);
            if (deleted == 0) {
                return false;
            }
            if (capacityRepository.release(reservation.getQuantity(), clock.instant()) == 0) {
                throw new IllegalStateException("용량 예약 합계가 쿠폰 예약 수량보다 작다 couponId=" + couponId
                        + ", quantity=" + reservation.getQuantity());
            }
            return true;
        });
        refreshMetric();
        return Boolean.TRUE.equals(released);
    }

    /** 지금 예약 합계를 읽어 지표에 반영하고 돌려준다 */
    public long refreshMetric() {
        long total = capacityRepository.reservedTotal();
        metrics.updateCapacityReserved(total);
        return total;
    }
}
