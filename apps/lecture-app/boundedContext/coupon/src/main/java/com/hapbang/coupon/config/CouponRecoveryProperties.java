package com.hapbang.coupon.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Redis 유실 복구 설정 ({@code hapbang.coupon.recovery.*}). 값을 주지 않으면 코드 기본값이다. 모두 측정·합의 전 값이라 확정이 아니다.
 *
 * @param republishAckTimeout  복구 경계 재발행 한 건의 Kafka ACK 대기. 기본 3초
 * @param kafkaTimeout         복구 경계(토픽 끝 offset·소비 그룹 커밋 offset) 조회 한 번의 제한 시간. 기본 5초
 * @param processCheckInterval Redis 프로세스 변경 점검 배치의 실행 간격(앞 회차가 끝난 뒤부터). 점검 배치의 {@code @Scheduled}가 같은 key·같은
 *                             기본값(1분)을 읽는다
 * @param unknownWindow        접수 기록 유실이 의심된 복구를 재개한 뒤, 그 쿠폰에서 Redis·DB 어디에도 없는 요청을 UNKNOWN으로 답하는 기간.
 *                             지나면 NOT_FOUND다. 기본 24시간
 * @param boundaryGrace        경계 확정 대기의 여유. 접수 차단 시각 + 생산자 전송 최대 시간
 *                             ({@link CouponIssueDeliveryProperties#maxSendLifetime}) + 이 값이 지나야 경계를 확정한다(시계 차·스레드 지연 몫).
 *                             기본 2초
 * @param claimLease           재구축·재개 선점의 유효 시간. 선점한 요청이 이 시간 안에 끝나지 않으면 버려진 선점으로 보고 같은 단계의 작업이
 *                             다시 선점한다. Redis 재구축·활성화 한 번(그 앞의 DB 갱신 포함)보다 충분히 길어야 한다. 기본 2분
 */
@ConfigurationProperties("hapbang.coupon.recovery")
public record CouponRecoveryProperties(Duration republishAckTimeout, Duration kafkaTimeout,
                                       Duration processCheckInterval, Duration unknownWindow, Duration boundaryGrace,
                                       Duration claimLease) {

    public CouponRecoveryProperties {
        republishAckTimeout = positive(republishAckTimeout, Duration.ofSeconds(3), "republish-ack-timeout");
        kafkaTimeout = positive(kafkaTimeout, Duration.ofSeconds(5), "kafka-timeout");
        processCheckInterval = positive(processCheckInterval, Duration.ofMinutes(1), "process-check-interval");
        unknownWindow = positive(unknownWindow, Duration.ofHours(24), "unknown-window");
        boundaryGrace = boundaryGrace == null ? Duration.ofSeconds(2) : boundaryGrace;
        if (boundaryGrace.isNegative()) {
            throw new IllegalArgumentException("hapbang.coupon.recovery.boundary-grace는 음수일 수 없다");
        }
        claimLease = positive(claimLease, Duration.ofMinutes(2), "claim-lease");
    }

    /** 모두 기본값 */
    public static CouponRecoveryProperties defaults() {
        return new CouponRecoveryProperties(null, null, null, null, null, null);
    }

    private static Duration positive(Duration value, Duration defaultValue, String name) {
        Duration resolved = value == null ? defaultValue : value;
        if (resolved.isNegative() || resolved.isZero()) {
            throw new IllegalArgumentException("hapbang.coupon.recovery." + name + "는 0보다 커야 한다");
        }
        return resolved;
    }
}
