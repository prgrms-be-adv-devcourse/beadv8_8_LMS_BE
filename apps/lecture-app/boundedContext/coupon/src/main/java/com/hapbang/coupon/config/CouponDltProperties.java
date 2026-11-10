package com.hapbang.coupon.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * DLT 관리자 처리 설정 ({@code hapbang.coupon.*}). 값을 주지 않으면 코드 기본값이며 측정·합의 전 값이다.
 * <p>
 * 재처리 제한 시간의 두 배가 선점 lease보다 짧아야 한다. 아니면 앱이 뜨지 않는다(설정을 묶을 때 검사한다). 제한 시간은 트랜잭션마다
 * 걸리고, 소비 멱등 충돌 뒤 같은 제한 시간으로 한 번 더 읽으므로 재처리 한 번은 최악 두 배까지 걸린다. 재처리가 lease를 넘겨 진행되면
 * 다른 관리자가 같은 기록을 다시 선점해, 늦게 끝난 재처리와 겹칠 수 있다.
 *
 * @param dltReplayLease   재발행 선점(REPLAYING)의 lease. 선점한 프로세스가 전송 결과를 남기지 못하고 멈췄을 때, 이 시간이 지나면 관리자
 *                         재발행·영구 거절이 그 기록을 다시 선점할 수 있다. 정상 재발행은 ACK 대기({@code republish.ack-timeout})
 *                         안에 끝나므로 그보다 길어야 한다. 기본 1분
 * @param dltReplayTimeout 주문 이벤트 DLT 재처리의 소비 트랜잭션 제한 시간(초 단위로 올림, 트랜잭션마다). 같은 주문 항목의 행 잠금을 오래
 *                         기다리면 이 시간에 끊고 기록을 PENDING으로 되돌린다(다시 요청하면 된다). Kafka 소비 경로에는 걸지 않는다.
 *                         두 배가 {@code dltReplayLease}보다 짧아야 한다. 기본 25초
 */
@ConfigurationProperties("hapbang.coupon")
public record CouponDltProperties(Duration dltReplayLease, Duration dltReplayTimeout) {

    public CouponDltProperties {
        dltReplayLease = dltReplayLease == null ? Duration.ofMinutes(1) : dltReplayLease;
        if (dltReplayLease.isNegative() || dltReplayLease.isZero()) {
            throw new IllegalArgumentException("hapbang.coupon.dlt-replay-lease는 0보다 커야 한다");
        }
        dltReplayTimeout = dltReplayTimeout == null ? Duration.ofSeconds(25) : dltReplayTimeout;
        if (dltReplayTimeout.compareTo(Duration.ofSeconds(1)) < 0) {
            throw new IllegalArgumentException("hapbang.coupon.dlt-replay-timeout은 1초 이상이어야 한다(트랜잭션 제한 시간은 초 단위)");
        }
        // 실제로 걸리는 값(초 단위로 올림)의 두 배(멱등 충돌 뒤 한 번 더 도는 트랜잭션까지)로 비교한다
        if (Duration.ofSeconds(2L * timeoutSeconds(dltReplayTimeout)).compareTo(dltReplayLease) >= 0) {
            throw new IllegalArgumentException(
                    "hapbang.coupon.dlt-replay-timeout의 두 배는 hapbang.coupon.dlt-replay-lease보다 짧아야 한다");
        }
    }

    /** 모두 기본값 */
    public static CouponDltProperties defaults() {
        return new CouponDltProperties(null, null);
    }

    /** 재처리 트랜잭션 제한 시간(초). Spring 트랜잭션 제한 시간은 초 단위라 올린다 */
    public int dltReplayTimeoutSeconds() {
        return timeoutSeconds(dltReplayTimeout);
    }

    private static int timeoutSeconds(Duration timeout) {
        long seconds = timeout.toSeconds() + (timeout.toNanosPart() > 0 ? 1 : 0);
        return Math.toIntExact(seconds);
    }
}
