package com.hapbang.chat.in.websocket;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;
import org.springframework.web.socket.server.support.HttpSessionHandshakeInterceptor;

import lombok.RequiredArgsConstructor;

/**
 * STOMP over WebSocket. 서버 1대라 내장(simple) 브로커를 쓴다.
 * <ul>
 *     <li>연결: /api/v1/chats/ws (로그인 세션 쿠키로 핸드셰이크). nginx가 /api/ 아래만 앱으로 넘기고 웹소켓 업그레이드도 그 경로에 설정돼 있다.</li>
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
    private final ChatWebSocketSessionRegistry chatWebSocketSessionRegistry;

    /**
     * 웹소켓 핸드셰이크를 허용할 프론트 출처 (프로필별 {@code hapbang.chat.websocket.allowed-origins}).
     * 세션 쿠키 인증이라 모든 출처를 허용하면 다른 사이트가 로그인한 사용자의 연결을 열 수 있다(#235).
     * 비어 있으면 같은 출처와 Origin 헤더가 없는 요청(테스트 클라이언트)만 허용한다.
     */
    @Value("${hapbang.chat.websocket.allowed-origins:}")
    private String[] allowedOrigins;

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // HTTP 세션 ID를 웹소켓 세션 속성에 남긴다. 로그아웃하면 그 세션으로 맺은 연결만 닫는다(#242)
        HttpSessionHandshakeInterceptor httpSessionInterceptor = new HttpSessionHandshakeInterceptor();
        httpSessionInterceptor.setCopyAllAttributes(false);
        registry.addEndpoint(ENDPOINT).setAllowedOrigins(allowedOrigins).addInterceptors(httpSessionInterceptor);
    }

    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        registration.addDecoratorFactory(chatWebSocketSessionRegistry);
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
