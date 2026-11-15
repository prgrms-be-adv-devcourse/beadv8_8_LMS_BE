package com.hapbang.coupon.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 발급 저장소(Redis) 수량·용량 상한과 적체 차단 설정 ({@code hapbang.coupon.limits.*}). 값을 주지 않으면 코드 기본값이다.
 * <p>
 * <b>모두 측정·합의 전 값(플레이스홀더)이다.</b> 공유 Redis(maxmemory 48mb, noeviction)의 쿠폰 기록 한 건당 크기와 여유 메모리를 대표 데이터로 측정한 뒤
 * 정하고, 그 전에는 운영 발급을 열지 않는다(운영 문서 "용량과 캐시"). 기본값은 측정 전이라 일부러 작게 잡았다.
 *
 * @param maxQuantityPerCoupon 쿠폰 한 개의 최대 수량. 발행과 발급 열기에서 검사한다. 기본 10,000장
 * @param maxTotalQuantity     Redis 기록이 남은 모든 쿠폰(발급 종료됐어도 정리 전이면 포함)의 수량 합 상한. 발급을 열 때 쿠폰 수량만큼 예약한다.
 *                             기본 50,000장
 * @param backlogThreshold     쿠폰 하나의 재발행 대기 건수가 이 값을 넘으면 그 쿠폰의 신규 접수를 보류한다(hold=BACKLOG). 기본 500건
 * @param backlogReleaseRatio  적체 보류를 푸는 비율. 대기 건수가 {@code 임계치 × 비율} 이하로 줄어야 푼다(임계치 근처에서 보류·해제가
 *                             반복되지 않게). 0 이상 1 이하. 기본 0.8
 * @param orphanReservationGrace 발급 열기가 중간에 실패해 남은 용량 예약(쿠폰이 열기 전 상태이고 Redis key가 하나도 없다)을 대사 배치가
 *                             고아로 보고 풀기까지 기다리는 시간. 예약(또는 열기 재시도의 예약 재사용) 뒤 이 시간이 지나야 푼다. 진행 중인 열기의
 *                             예약을 풀지 않기 위한 값이다. 기본 10분
 */
@ConfigurationProperties("hapbang.coupon.limits")
public record CouponLimitsProperties(Integer maxQuantityPerCoupon, Long maxTotalQuantity, Integer backlogThreshold,
                                     Double backlogReleaseRatio, Duration orphanReservationGrace) {

    public CouponLimitsProperties {
        maxQuantityPerCoupon = maxQuantityPerCoupon == null ? 10_000 : maxQuantityPerCoupon;
        maxTotalQuantity = maxTotalQuantity == null ? 50_000L : maxTotalQuantity;
        backlogThreshold = backlogThreshold == null ? 500 : backlogThreshold;
        backlogReleaseRatio = backlogReleaseRatio == null ? 0.8 : backlogReleaseRatio;
        orphanReservationGrace = orphanReservationGrace == null ? Duration.ofMinutes(10) : orphanReservationGrace;
        if (maxQuantityPerCoupon < 1) {
            throw new IllegalArgumentException("hapbang.coupon.limits.max-quantity-per-coupon은 1 이상이어야 한다");
        }
        if (maxTotalQuantity < maxQuantityPerCoupon) {
            throw new IllegalArgumentException(
                    "hapbang.coupon.limits.max-total-quantity는 max-quantity-per-coupon 이상이어야 한다");
        }
        if (backlogThreshold < 0) {
            throw new IllegalArgumentException("hapbang.coupon.limits.backlog-threshold는 음수일 수 없다");
        }
        if (!(backlogReleaseRatio >= 0 && backlogReleaseRatio <= 1)) {
            throw new IllegalArgumentException("hapbang.coupon.limits.backlog-release-ratio는 0 이상 1 이하여야 한다");
        }
        if (orphanReservationGrace.isNegative()) {
            throw new IllegalArgumentException("hapbang.coupon.limits.orphan-reservation-grace는 음수일 수 없다");
        }
    }

    /** 적체 보류를 푸는 대기 건수 상한({@code 임계치 × 해제 비율}, 내림). 대기 건수가 이 값 이하일 때만 푼다 */
    public long backlogReleaseAt() {
        return (long) Math.floor(backlogThreshold * backlogReleaseRatio);
    }

    /** 모두 기본값 */
    public static CouponLimitsProperties defaults() {
        return new CouponLimitsProperties(null, null, null, null, null);
    }
}
