package com.hapbang.coupon.app.port;

import java.time.Duration;

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

    /** 전송 결과 */
    enum PublishResult {
        /** broker가 ACK했다 */
        ACKED,
        /** 전송이 실패했다(직렬화·broker 오류 등) */
        FAILED,
        /** ACK를 기다리다 시간이 지났다 */
        TIMED_OUT;

        public boolean acked() {
            return this == ACKED;
        }
    }
}
