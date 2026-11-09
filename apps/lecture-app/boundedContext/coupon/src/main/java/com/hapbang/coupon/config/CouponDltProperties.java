package com.hapbang.coupon.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * DLT 관리자 처리 설정 ({@code hapbang.coupon.*}). 값을 주지 않으면 코드 기본값이며 측정·합의 전 값이다.
 *
 * @param dltReplayLease 재발행 선점(REPLAYING)의 lease. 선점한 프로세스가 전송 결과를 남기지 못하고 멈췄을 때, 이 시간이 지나면 관리자
 *                       재발행·영구 거절이 그 기록을 다시 선점할 수 있다. 정상 재발행은 ACK 대기({@code republish.ack-timeout})
 *                       안에 끝나므로 그보다 길어야 한다. 기본 1분
 */
@ConfigurationProperties("hapbang.coupon")
public record CouponDltProperties(Duration dltReplayLease) {

    public CouponDltProperties {
        dltReplayLease = dltReplayLease == null ? Duration.ofMinutes(1) : dltReplayLease;
        if (dltReplayLease.isNegative() || dltReplayLease.isZero()) {
            throw new IllegalArgumentException("hapbang.coupon.dlt-replay-lease는 0보다 커야 한다");
        }
    }

    /** 모두 기본값 */
    public static CouponDltProperties defaults() {
        return new CouponDltProperties(null);
    }
}
