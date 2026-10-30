package com.hapbang.chat.in.websocket;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.util.unit.DataSize;
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
 *     오류 /user/queue/chats/errors, 재동기화 신호 /user/queue/chats/sync</li>
 * </ul>
 * 연결마다 송신 버퍼·송신 시간에 상한을 둔다(설계 4.6). 느린 수신자가 상한을 넘으면 Spring이 그 연결을 닫고,
 * FE는 backoff·jitter로 다시 연결해 이력을 재동기화한다(resync-frame.md의 "그 밖의 종료").
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
    public static final String SYNC_QUEUE = CHAT_QUEUE_PREFIX + "/sync";
    /** 방 topic 경로의 앞부분. 브로커에 등록하지 않고 chat-app의 방 구독 목록이 세션별로 쓴다(설계 4.2) */
    public static final String ROOM_TOPIC_PREFIX = "/topic/chats/rooms/";
    /** 방 topic SUBSCRIBE에 붙이는 참여 세대 헤더 */
    public static final String PARTICIPATION_VERSION_HEADER = "participation-version";

    private final ChatStompInterceptor chatStompInterceptor;
    private final ChatWebSocketSessionRegistry chatWebSocketSessionRegistry;
    private final ChatOutboundSendBudget chatOutboundSendBudget;
    private final ChatInboundReceiveBudget chatInboundReceiveBudget;

    /**
     * 웹소켓 핸드셰이크를 허용할 프론트 출처 (프로필별 {@code hapbang.chat.websocket.allowed-origins}).
     * 세션 쿠키 인증이라 모든 출처를 허용하면 다른 사이트가 로그인한 사용자의 연결을 열 수 있다(#235).
     * 비어 있으면 같은 출처와 Origin 헤더가 없는 요청(테스트 클라이언트)만 허용한다.
     */
    @Value("${hapbang.chat.websocket.allowed-origins:}")
    private String[] allowedOrigins;

    /** 한 연결에 프레임 하나를 보내는 데 허용하는 최대 시간. 넘으면 연결을 닫는다. 기본 10s는 미확정 초기값(Q12, 코드 기본값) */
    @Value("${hapbang.chat.realtime.websocket.send-time-limit:10s}")
    private Duration sendTimeLimit;

    /** 한 연결이 보내지 못하고 쌓아 둘 수 있는 프레임의 최대 크기. 넘으면 연결을 닫는다. 기본 512KB는 미확정 초기값(Q12, 코드 기본값) */
    @Value("${hapbang.chat.realtime.websocket.send-buffer-size-limit:512KB}")
    private DataSize sendBufferSizeLimit;

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
        registration.setSendTimeLimit(Math.toIntExact(sendTimeLimit.toMillis()));
        registration.setSendBufferSizeLimit(Math.toIntExact(sendBufferSizeLimit.toBytes()));
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.setApplicationDestinationPrefixes(APP_PREFIX);
        registry.enableSimpleBroker(QUEUE_PREFIX);
        registry.setUserDestinationPrefix(USER_PREFIX);
    }

    /**
     * 앱 전체 송신 예산(설계 4.6). 상한이 있는 실행기({@link ChatOutboundSendBudget})를 직접 넣는다.
     * {@code taskExecutor()} 설정은 Boot의 configurer(order 0)가 넣는 {@code executor(...)}에 가려지므로 쓰지 않는다.
     * 이 configurer는 순서가 없어 Boot 다음에 불리므로 이 실행기가 쓰인다.
     */
    @Override
    public void configureClientOutboundChannel(ChannelRegistration registration) {
        registration.executor(chatOutboundSendBudget.channelExecutor());
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        // 앱 전체 수신 예산(설계 4.6). 출력 채널과 같은 이유로 taskExecutor()가 아니라 executor(...)로 직접 넣는다.
        // 연결마다 받은 순서대로 처리하고 연결별 몫을 지키는 실행기다(Spring의 setPreserveReceiveOrder는 쓰지 않는다)
        registration.executor(chatInboundReceiveBudget.channelExecutor());
        registration.interceptors(chatStompInterceptor);
    }
}
