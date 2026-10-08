package com.hapbang.chat.domain;

import lombok.Getter;

@Getter
public class ChatException extends RuntimeException {

    private final ChatErrorCode errorCode;

    public ChatException(ChatErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }
}
