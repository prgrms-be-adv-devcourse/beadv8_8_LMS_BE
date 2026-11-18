package com.hapbang.chat.app.dto;

/** 방 topic 구독에 쓰는 메시지가 아닌 프레임의 종류. 프레임 헤더 {@code chat-frame-type}과 본문 {@code type}의 값이다 */
public enum ChatSubscriptionSignal {
    SUBSCRIPTION_REJECTED,
    SUBSCRIPTION_REVOKED
}
