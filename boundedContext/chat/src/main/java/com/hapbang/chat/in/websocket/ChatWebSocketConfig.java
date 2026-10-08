package com.hapbang.chat.in.websocket;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

import lombok.RequiredArgsConstructor;

/**
 * STOMP over WebSocket. 서버 1대라 내장(simple) 브로커를 쓴다.
 * <ul>
 *     <li>연결: /api/v1/chats/ws (CONNECT 헤더에 X-User-Id). nginx가 /api/ 아래만 앱으로 넘기고 웹소켓 업그레이드도 그 경로에 설정돼 있다.</li>
 *     <li>보내기: /pub/chats/{chatRoomId}/messages</li>
 *     <li>받기(회원별): 메시지 /user/queue/chats/messages, 새 메시지 알림 /user/queue/chats/notifications,
 *     오류 /user/queue/chats/errors</li>
 * </ul>
 * 방 단위 topic은 두지 않는다. 메시지는 서버가 확인한 현재 참여자에게만 회원별로 보낸다.
 * /pub, /queue, /user는 앱 전체에 하나뿐인 브로커 설정이라 채팅 목적지에는 chats를 붙여 다른 모듈과 구분한다.
 */
@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class ChatWebSocketConfig implements WebSocketMessageBrokerConfigurer {

    public static final String ENDPOINT = "/api/v1/chats/ws";
    public static final String APP_PREFIX = "/pub";
    public static final String USER_PREFIX = "/user";
    public static final String QUEUE_PREFIX = "/queue";
    public static final String CHAT_QUEUE_PREFIX = QUEUE_PREFIX + "/chats";
    public static final String MESSAGE_QUEUE = CHAT_QUEUE_PREFIX + "/messages";
    public static final String NOTIFICATION_QUEUE = CHAT_QUEUE_PREFIX + "/notifications";
    public static final String ERROR_QUEUE = CHAT_QUEUE_PREFIX + "/errors";

    private final ChatStompInterceptor chatStompInterceptor;

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // TODO(security): CORS·허용 출처 정책이 정해지면 프론트 도메인만 허용한다. 지금은 모든 출처를 허용한다.
        registry.addEndpoint(ENDPOINT).setAllowedOriginPatterns("*");
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.setApplicationDestinationPrefixes(APP_PREFIX);
        registry.enableSimpleBroker(QUEUE_PREFIX);
        registry.setUserDestinationPrefix(USER_PREFIX);
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(chatStompInterceptor);
    }
}
