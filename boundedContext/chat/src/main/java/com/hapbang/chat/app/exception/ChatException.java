package com.hapbang.chat.app.exception;

import com.hapbang.global.exception.BusinessException;

/**
 * 채팅 업무 예외. 서비스에서만 던진다(도메인은 {@code Assert.state()}로 불변식만 지킨다).
 * REST는 global의 공통 핸들러가, STOMP는 {@code ChatMessageStompController}가 응답한다.
 */
public class ChatException extends BusinessException {

    public ChatException(ChatErrorCode errorCode) {
        super(errorCode);
    }

    public ChatException(ChatErrorCode errorCode, String detail) {
        super(errorCode, detail);
    }
}
