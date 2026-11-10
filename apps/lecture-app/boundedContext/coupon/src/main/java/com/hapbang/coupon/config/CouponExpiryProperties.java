package com.hapbang.coupon.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 발급 쿠폰 만료 배치 설정 ({@code hapbang.coupon.expiry.*}). 값을 주지 않으면 코드 기본값이다. 모두 측정·합의 전 값이라 확정이 아니다.
 *
 * @param interval  만료 배치의 실행 간격(앞 회차가 끝난 뒤부터). 배치의 {@code @Scheduled}가 같은 key·같은 기본값(10분)을 읽는다. 사용
 *                  경로는 {@code expiresAt > now}로 이미 막으므로 표시용 상태를 늦게 바꿔도 정합성 문제는 없다
 * @param batchSize 한 묶음(한 트랜잭션)에서 고르고 바꾸는 발급 쿠폰 수. 기본 500
 */
@ConfigurationProperties("hapbang.coupon.expiry")
public record CouponExpiryProperties(Duration interval, Integer batchSize) {

    public CouponExpiryProperties {
        interval = interval == null ? Duration.ofMinutes(10) : interval;
        batchSize = batchSize == null ? 500 : batchSize;
        if (interval.isNegative() || interval.isZero()) {
            throw new IllegalArgumentException("hapbang.coupon.expiry.interval은 0보다 커야 한다");
        }
        if (batchSize < 1) {
            throw new IllegalArgumentException("hapbang.coupon.expiry.batch-size는 1 이상이어야 한다");
        }
    }

    /** 모두 기본값 */
    public static CouponExpiryProperties defaults() {
        return new CouponExpiryProperties(null, null);
    }
}
