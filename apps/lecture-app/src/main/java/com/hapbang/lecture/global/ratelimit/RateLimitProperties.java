package com.hapbang.lecture.global.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 회원 × API마다 분당 요청 한도 (D80). 배포 뒤 거절 지표를 보고 코드 변경 없이 조정한다.
 *
 * @param write 주문·구매 취소 (기본 10, 지갑 신청·취소와 같음)
 */
@ConfigurationProperties("lecture.rate-limit")
public record RateLimitProperties(@DefaultValue("10") int write) {

    public RateLimitProperties {
        if (write < 1) {
            throw new IllegalArgumentException("요청 한도는 1 이상이어야 합니다.");
        }
    }
}
