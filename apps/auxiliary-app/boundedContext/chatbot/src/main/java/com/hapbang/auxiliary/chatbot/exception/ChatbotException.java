package com.hapbang.auxiliary.chatbot.exception;

import com.hapbang.auxiliary.global.exception.BusinessException;

/** 챗봇 모듈의 예상된 업무 실패. 공통 핸들러가 오류 코드의 상태·{@code code}로 응답한다 */
public class ChatbotException extends BusinessException {

    public ChatbotException(ChatbotErrorCode errorCode) {
        super(errorCode);
    }

    public ChatbotException(ChatbotErrorCode errorCode, Throwable cause) {
        super(errorCode, cause);
    }
}
