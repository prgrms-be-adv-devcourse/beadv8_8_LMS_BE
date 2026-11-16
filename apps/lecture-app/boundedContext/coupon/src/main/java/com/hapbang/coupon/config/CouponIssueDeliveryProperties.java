package com.hapbang.coupon.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * 발급 접수 뒤 전달(접수 직후 Kafka 전송, 재발행 배치)과 매진 메모리 캐시 설정 ({@code hapbang.coupon.*}). 값을 주지 않으면 코드 기본값이다.
 * 모두 측정·합의 전 값이라 확정이 아니다.
 *
 * @param publishAckTimeout 쿠폰 producer의 {@code max.block.ms}: 전송 준비(메타데이터 조회·버퍼 공간)를 기다리는 최대 시간. 접수 직후 전송은
 *                          ACK를 기다리지 않으므로 요청 스레드가 전송 때문에 더 기다리는 시간은 이것뿐이다(broker가 정상이면 거의 0).
 *                          이름은 ACK 대기였던 때의 것을 설정 호환으로 그대로 둔다. 기본 1초
 * @param republishAfter    접수 뒤 이 시간이 지나도 DB에 결과가 없으면 재발행한다. 접수 직후 전송·소비 중인 요청과 겹치지 않게 하는 대기다.
 *                          기본 30초
 * @param soldOutCacheTtl   매진 표시를 메모리에 재사용하는 시간. 0이면 매진 캐시를 쓰지 않는다(sold_out 표시도 읽지 않고 매진 판정은 발급 Lua에 맡긴다). 기본 1초
 * @param republish         재발행 배치
 * @param producerDeliveryTimeout 쿠폰 producer의 {@code delivery.timeout.ms}: {@code send}가 돌아온 레코드가 ACK·실패로 끝나기까지의 최대
 *                          시간(재시도 포함). 접수 직후 전송은 응답 뒤에도, 재발행의 ACK 대기가 시간 초과로 돌아온 뒤에도 전송이 이 시간까지
 *                          계속될 수 있다. Kafka 제약
 *                          ({@code delivery.timeout.ms ≥ linger.ms + request.timeout.ms})을 지키려고 producer가 {@code request.timeout.ms}를
 *                          이 값 − {@code linger.ms} 이하로 줄인다. 복구 경계 확정 대기({@link #maxSendLifetime})의 기준이다. 기본 5초
 */
@ConfigurationProperties("hapbang.coupon")
public record CouponIssueDeliveryProperties(Duration publishAckTimeout, Duration republishAfter,
                                            Duration soldOutCacheTtl, Republish republish,
                                            Duration producerDeliveryTimeout) {

    /** 설정 바인딩은 이 생성자로 한다(아래 4인자 생성자는 기존 호출 호환용) */
    @ConstructorBinding
    public CouponIssueDeliveryProperties {
        publishAckTimeout = positive(publishAckTimeout, Duration.ofSeconds(1), "publish-ack-timeout");
        producerDeliveryTimeout = positive(producerDeliveryTimeout, Duration.ofSeconds(5), "producer-delivery-timeout");
        republishAfter = positive(republishAfter, Duration.ofSeconds(30), "republish-after");
        soldOutCacheTtl = soldOutCacheTtl == null ? Duration.ofSeconds(1) : soldOutCacheTtl;
        if (soldOutCacheTtl.isNegative()) {
            throw new IllegalArgumentException("hapbang.coupon.sold-out-cache-ttl은 음수일 수 없다");
        }
        republish = republish == null ? new Republish(null, null, null, null) : republish;
    }

    /** 생산자 전송 수명은 기본값 */
    public CouponIssueDeliveryProperties(Duration publishAckTimeout, Duration republishAfter, Duration soldOutCacheTtl,
                                         Republish republish) {
        this(publishAckTimeout, republishAfter, soldOutCacheTtl, republish, null);
    }

    /** 모두 기본값 */
    public static CouponIssueDeliveryProperties defaults() {
        return new CouponIssueDeliveryProperties(null, null, null, null, null);
    }

    /**
     * 쿠폰 producer로 시작한 전송 하나가 끝나기까지의 최대 시간: 전송 준비 대기({@code max.block.ms} = {@link #publishAckTimeout}) +
     * max(전송 수명 {@link #producerDeliveryTimeout}, 재발행 ACK 대기 {@code republish.ack-timeout}). 레코드 수명은 전송 수명이 끊지만,
     * 재발행 ACK 대기가 더 길게 설정되면 그만큼 기다린다(DLT 재발행·재발행 배치가 그 대기로 보낸다). 복구는 접수 차단 뒤 이 시간과 여유가 지나야
     * Kafka 끝 offset을 경계로 잡는다.
     */
    public Duration maxSendLifetime() {
        Duration delivery = producerDeliveryTimeout.compareTo(republish.ackTimeout()) >= 0
                ? producerDeliveryTimeout : republish.ackTimeout();
        return publishAckTimeout.plus(delivery);
    }

    /**
     * 재발행 배치 ({@code hapbang.coupon.republish.*}). 측정·합의 전 값이다.
     *
     * @param interval   배치 실행 간격(앞 회차가 끝난 뒤부터). 배치 클래스의 {@code @Scheduled}가 같은 key·같은 기본값(1분)을 읽는다
     * @param scanCount  requests를 HSCAN으로 한 번에 가져오는 개수이자 DB 확인 한 번의 묶음 크기. 기본 200
     * @param ackTimeout 재발행 한 건의 ACK 대기. 기본 3초
     * @param maxConsecutiveFailures 한 쿠폰에서 한 회차에 재발행이 연속으로 이만큼 실패(FAILED·TIMED_OUT)하면 그 쿠폰의 남은 전송을 멈추고
     *                   다음 쿠폰으로 간다(broker 장애 때 후보마다 ACK 대기로 회차가 끝나지 않는 것을 막는다). ACK가 한 번 끼면 다시 센다.
     *                   기본 3
     */
    public record Republish(Duration interval, Integer scanCount, Duration ackTimeout,
                            Integer maxConsecutiveFailures) {

        public Republish {
            interval = positive(interval, Duration.ofMinutes(1), "republish.interval");
            scanCount = scanCount == null ? 200 : scanCount;
            if (scanCount < 1) {
                throw new IllegalArgumentException("hapbang.coupon.republish.scan-count는 1 이상이어야 한다");
            }
            ackTimeout = positive(ackTimeout, Duration.ofSeconds(3), "republish.ack-timeout");
            maxConsecutiveFailures = maxConsecutiveFailures == null ? 3 : maxConsecutiveFailures;
            if (maxConsecutiveFailures < 1) {
                throw new IllegalArgumentException("hapbang.coupon.republish.max-consecutive-failures는 1 이상이어야 한다");
            }
        }
    }

    private static Duration positive(Duration value, Duration defaultValue, String name) {
        Duration resolved = value == null ? defaultValue : value;
        if (resolved.isNegative() || resolved.isZero()) {
            throw new IllegalArgumentException("hapbang.coupon." + name + "는 0보다 커야 한다");
        }
        return resolved;
    }
}
