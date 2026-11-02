package com.hapbang.chat.in.websocket;

import org.jspecify.annotations.Nullable;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.web.socket.messaging.StompSubProtocolErrorHandler;

/**
 * 클라이언트 프레임을 거절할 때 보내는 STOMP ERROR 프레임을 만든다. Spring 기본 처리({@code message} 헤더 = 거절 사유)에
 * 인증 실패({@link ChatStompAuthenticationException})면 {@code code} 헤더를 더한다(resync-frame.md "연결 종료 사유").
 * ERROR를 보낸 뒤 연결을 닫는 것은 Spring이 그대로 한다(PROTOCOL_ERROR).
 */
public class ChatStompErrorHandler extends StompSubProtocolErrorHandler {

    /** ERROR 프레임의 오류 코드 헤더 */
    public static final String CODE_HEADER = "code";

    @Override
    protected Message<byte[]> handleInternal(StompHeaderAccessor errorHeaderAccessor, byte[] errorPayload,
            @Nullable Throwable cause, @Nullable StompHeaderAccessor clientHeaderAccessor) {
        if (authenticationFailure(cause)) {
            errorHeaderAccessor.setNativeHeader(CODE_HEADER, ChatStompAuthenticationException.CODE);
        }
        return super.handleInternal(errorHeaderAccessor, errorPayload, cause, clientHeaderAccessor);
    }

    private static boolean authenticationFailure(Throwable cause) {
        for (Throwable current = cause; current != null; current = current.getCause()) {
            if (current instanceof ChatStompAuthenticationException) {
                return true;
            }
        }
        return false;
    }
}
