package com.hapbang.coupon.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import com.hapbang.coupon.domain.Coupon;

/**
 * 쿠폰 발행 정책 설정 ({@code hapbang.coupon.*}). 값을 주지 않으면 기본값이다.
 *
 * @param maxExpiryDays 만료 상한 일수. 접수 기준 만료(RELATIVE)는 만료 일수가, 고정 만료(FIXED)는 발급 종료부터 고정 만료 시각까지가
 *                      이 일수 이하여야 한다. 기본 3650일({@link Coupon#DB_MAX_EXPIRY_DAYS})이며 측정·합의 전 값이다.
 *                      DB CHECK({@code ck_coupon_expiry})도 3650일로 막으므로 3650보다 큰 값은 시작할 때 거절한다.
 *                      늘리려면 마이그레이션으로 CHECK와 {@link Coupon#DB_MAX_EXPIRY_DAYS}를 함께 바꾼다
 */
@ConfigurationProperties("hapbang.coupon")
public record CouponPolicyProperties(Integer maxExpiryDays) {

    public CouponPolicyProperties {
        maxExpiryDays = maxExpiryDays == null ? Coupon.DB_MAX_EXPIRY_DAYS : maxExpiryDays;
        if (maxExpiryDays < 1) {
            throw new IllegalArgumentException("hapbang.coupon.max-expiry-days는 1 이상이어야 한다");
        }
        // DB CHECK보다 크면 도메인은 받고 저장에서 500이 나므로 시작할 때 막는다
        if (maxExpiryDays > Coupon.DB_MAX_EXPIRY_DAYS) {
            throw new IllegalArgumentException(
                    "hapbang.coupon.max-expiry-days는 DB 상한 " + Coupon.DB_MAX_EXPIRY_DAYS + "일 이하여야 한다");
        }
    }
}
