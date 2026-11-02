package com.hapbang.shared.billing.event;

/**
 * 지갑 이벤트 Kafka 토픽 (#339, #497). 지갑이 내보내는 HP 이동 결과 이벤트 타입을 이 토픽 하나에 섞는다.
 * 토픽·key·헤더 규칙은 docs/architecture/infrastructure/kafka.md를 따른다.
 * key는 대상 ID에 종류 접두어를 붙인다: point-charge, settlement, point-refund-request, exchange.
 * 같은 key의 메시지도 Modulith 전송 순서가 바뀔 수 있다(예: 환전 송금 실패 뒤의 완료가 먼저 도착).
 */
public final class BillingEventTopic {

    public static final String NAME = "billing.events.v1";

    private BillingEventTopic() {
    }
}
