package com.hapbang.shared.chat.event;

/**
 * 채팅 이벤트 Kafka 토픽 (ADR 0001). 채팅 앱이 내보내는 이벤트 타입을 이 토픽 하나에 섞는다.
 * key는 단체방 ID(접두어 없음)다. 이 토픽의 ID 종류가 방 하나뿐이라 kafka.md "key는" 규칙대로 접두어를 붙이지 않는다.
 * 토픽·key·헤더 규칙은 docs/architecture/infrastructure/kafka.md를 따른다.
 */
public final class ChatEventTopic {

    public static final String NAME = "chat.events.v1";

    private ChatEventTopic() {
    }
}
