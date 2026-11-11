package com.hapbang.coupon.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 예산 작업 재시도 설정. 기본값은 측정·합의 전이며 운영 설정으로 조정한다. */
@ConfigurationProperties("hapbang.coupon.budget-work")
public record CouponBudgetWorkProperties(Duration interval, Duration lease, Integer batchSize) {
    public CouponBudgetWorkProperties {
        interval = interval == null ? Duration.ofMinutes(1) : interval;
        lease = lease == null ? Duration.ofMinutes(1) : lease;
        batchSize = batchSize == null ? 100 : batchSize;
        if (interval.isNegative() || interval.isZero() || lease.isNegative() || lease.isZero() || batchSize < 1) {
            throw new IllegalArgumentException("예산 작업 주기·lease·묶음 크기는 양수여야 합니다.");
        }
    }
}
