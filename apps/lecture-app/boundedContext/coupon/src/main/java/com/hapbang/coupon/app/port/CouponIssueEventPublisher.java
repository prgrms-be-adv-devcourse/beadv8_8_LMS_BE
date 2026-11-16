package com.hapbang.coupon.app.port;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import com.hapbang.shared.coupon.event.CouponIssueRequested;

/**
 * 발급 요청 이벤트({@link CouponIssueRequested})를 Kafka 토픽 {@code coupon.events.v1}(key = couponId)으로 보낸다.
 * 접수 직후 전송, 재발행 배치, DLT 재발행, 복구 경계 재발행이 모두 이 포트를 쓴다. 같은 요청은 같은 ID·같은 값으로 보낸다
 * (중복 전달은 소비자 멱등이 흡수한다).
 * <p>
 * 예외를 던지지 않는다: 실패·시간 초과는 결과로 돌려준다. ACK 성공도 DB 완료가 아니다.
 */
public interface CouponIssueEventPublisher {

    /**
     * 보내고 broker ACK를 {@code ackTimeout}까지 기다린다. 기다림에는 전송 준비(메타데이터 조회)까지 들어간다.
     * 시간이 지나 {@link PublishResult#TIMED_OUT}을 돌려준 뒤에도 메시지는 나중에 도착할 수 있다.
     */
    PublishResult publish(CouponIssueRequested event, Duration ackTimeout);

    /**
     * 보내고 ACK를 기다리지 않는다. 호출자를 붙잡는 것은 전송 준비(메타데이터 조회·버퍼 공간, {@code max.block.ms})뿐이다.
     * 돌려준 future는 실패로 끝나지 않고 결과로 끝난다. 전송 수명({@code delivery.timeout.ms})이 지나면 {@link PublishResult#TIMED_OUT}이다.
     * 완료 콜백은 producer I/O 스레드에서 돌 수 있으므로 오래 걸리는 일을 붙이지 않는다.
     */
    CompletableFuture<PublishResult> send(CouponIssueRequested event);

    /** 전송 결과 */
    enum PublishResult {
        /** broker가 ACK했다 */
        ACKED,
        /** 전송이 실패했다(직렬화·broker 오류 등) */
        FAILED,
        /** ACK를 기다리다 시간이 지났다. {@code send}는 전송 준비나 전송 수명이 지났다 */
        TIMED_OUT;

        public boolean acked() {
            return this == ACKED;
        }
    }
}
