package com.hapbang.chat.domain;

import org.springframework.http.HttpStatus;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ChatErrorCode {
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "요청 값이 올바르지 않습니다."),
    CHAT_USER_NOT_FOUND(HttpStatus.NOT_FOUND, "채팅 회원 정보를 찾을 수 없습니다."),
    CHAT_NOT_ALLOWED(HttpStatus.FORBIDDEN, "인플루언서 이상 또는 관리자만 채팅을 사용할 수 있습니다."),
    CANNOT_CHAT_WITH_SELF(HttpStatus.BAD_REQUEST, "자기 자신과는 채팅할 수 없습니다."),
    CHAT_ROOM_NOT_FOUND(HttpStatus.NOT_FOUND, "채팅방을 찾을 수 없습니다."),
    NOT_CHAT_ROOM_MEMBER(HttpStatus.FORBIDDEN, "채팅방 참여자가 아닙니다."),
    EMPTY_MESSAGE(HttpStatus.BAD_REQUEST, "메시지 내용이 비어 있습니다."),
    CHAT_PARTNER_LEFT(HttpStatus.CONFLICT, "상대가 나간 채팅방에는 메시지를 보낼 수 없습니다. 채팅을 다시 시작해 주세요."),
    CHAT_ROOM_BUSY(HttpStatus.CONFLICT, "같은 채팅방을 동시에 처리하고 있습니다. 잠시 후 다시 시도해 주세요."),
    ADMIN_ONLY(HttpStatus.FORBIDDEN, "관리자만 조회할 수 있습니다.");

    private final HttpStatus status;
    private final String message;
}
