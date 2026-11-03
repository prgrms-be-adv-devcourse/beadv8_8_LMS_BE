package com.hapbang.billing.global.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 회원 × API마다 분당 요청 한도 (#627, BD-140). 배포 뒤 거절 지표를 보고 코드 변경 없이 조정한다.
 *
 * @param write   신청·취소 (기본 10)
 * @param read    일반 조회 (기본 60)
 * @param earning 수익 조회 (기본 20)
 */
@ConfigurationProperties("hapbang.billing.rate-limit")
public record RateLimitProperties(@DefaultValue("10") int write, @DefaultValue("60") int read,
        @DefaultValue("20") int earning) {

    public RateLimitProperties {
        if (write < 1 || read < 1 || earning < 1) {
            throw new IllegalArgumentException("rate limit 한도는 1 이상이어야 합니다.");
        }
    }

    /** 한도 종류의 분당 한도 */
    public int limitOf(RateLimitedApi.Group group) {
        return switch (group) {
            case WRITE -> write;
            case READ -> read;
            case EARNING -> earning;
        };
    }
}
