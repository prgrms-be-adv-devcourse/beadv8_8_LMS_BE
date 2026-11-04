package com.hapbang.shared.customersupport.event;

/**
 * 고객센터 이벤트 Kafka 토픽 (#279, ADR 0001). 고객센터 앱이 내보내는 이벤트 타입을 이 토픽 하나에 섞는다.
 * key는 {@code inquiry:<문의 ID>}다. 토픽·key·헤더 규칙은 docs/architecture/infrastructure/kafka.md를 따른다.
 */
public final class CustomerSupportEventTopic {

    public static final String NAME = "customersupport.events.v1";

    private CustomerSupportEventTopic() {
    }
}
