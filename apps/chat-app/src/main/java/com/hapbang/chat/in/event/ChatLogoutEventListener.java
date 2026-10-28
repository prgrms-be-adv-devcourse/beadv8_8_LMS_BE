package com.hapbang.chat.in.event;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.hapbang.chat.in.websocket.ChatWebSocketSessionRegistry;
import com.hapbang.shared.member.event.UserLoggedOutEvent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 로그아웃하면 그 HTTP 세션으로 맺은 채팅 웹소켓 연결을 닫는다 (#242). 같은 회원의 다른 기기 연결은 유지한다.
 * <p>
 * 로그아웃은 트랜잭션 밖에서 발행되므로 {@code @ApplicationModuleListener}(커밋 후 실행)가 아니라 {@code @EventListener}로
 * 바로 받는다. 연결 닫기는 다시 처리할 필요가 없어 발행 기록도 남기지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatLogoutEventListener {

    private final ChatWebSocketSessionRegistry chatWebSocketSessionRegistry;

    @EventListener
    public void on(UserLoggedOutEvent event) {
        int closed = chatWebSocketSessionRegistry.closeByHttpSessionId(event.sessionId(),
                ChatWebSocketSessionRegistry.LOGGED_OUT);
        log.info("로그아웃한 세션의 채팅 웹소켓 연결 닫음 userId={}, closed={}", event.userId(), closed);
    }
}
