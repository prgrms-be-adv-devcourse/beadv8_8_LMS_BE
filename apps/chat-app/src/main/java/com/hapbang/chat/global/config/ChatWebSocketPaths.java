package com.hapbang.chat.global.config;

/**
 * 웹소켓 핸드셰이크 경로. 보안 설정(핸드셰이크 허용)과 STOMP 진입 설정이 같은 값을 쓴다.
 */
public final class ChatWebSocketPaths {

    public static final String ENDPOINT = "/api/v1/chats/ws";

    private ChatWebSocketPaths() {
    }
}
