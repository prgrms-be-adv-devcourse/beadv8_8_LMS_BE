package com.hapbang.chat.in.websocket;

import org.springframework.messaging.MessageDeliveryException;

/**
 * STOMP 인증 실패로 프레임을 거절한다(설계 문서 6장). {@link ChatStompErrorHandler}가 ERROR 프레임에 {@code code} 헤더
 * ({@value #CODE})를 붙이고, Spring이 연결을 닫는다. 메시지에는 사유를 나누지 않는다.
 */
public class ChatStompAuthenticationException extends MessageDeliveryException {

    /** ERROR 프레임의 {@code code} 헤더 값. FE는 refresh 뒤 새 연결 티켓으로 다시 연결한다(resync-frame.md) */
    public static final String CODE = "UNAUTHENTICATED";

    public ChatStompAuthenticationException() {
        super("인증이 필요합니다.");
    }
}
