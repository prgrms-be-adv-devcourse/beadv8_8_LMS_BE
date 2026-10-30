package com.hapbang.chat.app;

import com.hapbang.chat.out.redis.ChatRedisKeys;

/**
 * 전달 작업 큐의 종류. 메시지 전달과 연결 제어는 Stream·필수 consumer group·worker 예산을 따로 쓴다.
 * 그룹 이름은 발행 체크포인트의 {@code consumer_group}에도 같은 값으로 저장한다.
 */
public enum ChatJobStream {

    /** 메시지·시스템 안내 전달 (outbox {@code chat_outbox}) */
    DELIVERY(ChatRedisKeys.DELIVERY_GROUP),

    /** 연결 제어 (outbox {@code chat_control_outbox}) */
    CONTROL(ChatRedisKeys.CONTROL_GROUP);

    private final String consumerGroup;

    ChatJobStream(String consumerGroup) {
        this.consumerGroup = consumerGroup;
    }

    /** 이 큐를 반드시 소비해야 하는 consumer group */
    public String consumerGroup() {
        return consumerGroup;
    }
}
