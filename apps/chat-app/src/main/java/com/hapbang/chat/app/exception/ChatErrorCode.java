package com.hapbang.chat.app.exception;

import org.springframework.http.HttpStatus;

import com.hapbang.chat.global.exception.BaseErrorCode;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** 채팅 오류 코드. 응답의 code는 enum 이름이다. global의 공통 핸들러가 ProblemDetail로 응답한다 */
@Getter
@RequiredArgsConstructor
public enum ChatErrorCode implements BaseErrorCode {
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "요청 값이 올바르지 않습니다."),
    CHAT_USER_NOT_FOUND(HttpStatus.NOT_FOUND, "채팅 회원 정보를 찾을 수 없습니다."),
    CHAT_NOT_ALLOWED(HttpStatus.FORBIDDEN, "인플루언서 이상 또는 관리자만 채팅을 사용할 수 있습니다."),
    CANNOT_CHAT_WITH_SELF(HttpStatus.BAD_REQUEST, "자기 자신과는 채팅할 수 없습니다."),
    CHAT_ROOM_NOT_FOUND(HttpStatus.NOT_FOUND, "채팅방을 찾을 수 없습니다."),
    NOT_CHAT_ROOM_MEMBER(HttpStatus.FORBIDDEN, "채팅방 참여자가 아닙니다."),
    EMPTY_MESSAGE(HttpStatus.BAD_REQUEST, "메시지 내용이 비어 있습니다."),
    CHAT_PARTNER_LEFT(HttpStatus.CONFLICT, "상대가 나간 채팅방에는 메시지를 보낼 수 없습니다. 채팅을 다시 시작해 주세요."),
    CHAT_ROOM_BUSY(HttpStatus.CONFLICT, "같은 채팅방을 동시에 처리하고 있습니다. 잠시 후 다시 시도해 주세요."),
    ADMIN_ONLY(HttpStatus.FORBIDDEN, "관리자만 조회할 수 있습니다."),
    MEMBER_SOURCE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "지금은 회원 정보를 조회할 수 없습니다. 잠시 후 다시 시도해 주세요."),
    MEMBER_SOURCE_REJECTED(HttpStatus.SERVICE_UNAVAILABLE, "지금은 회원 정보를 조회할 수 없습니다."),
    CHAT_USER_BACKFILL_ALREADY_RUNNING(HttpStatus.CONFLICT, "채팅 회원 백필이 이미 실행 중입니다."),
    CHAT_ROOM_NOT_READY(HttpStatus.CONFLICT, "채팅방의 전달 대상이 준비되지 않았습니다. 채팅을 다시 시작해 주세요."),
    DELIVERY_JOB_NOT_FOUND(HttpStatus.NOT_FOUND, "재처리할 전달 작업의 원본을 찾을 수 없습니다."),
    DELIVERY_JOB_NOT_ISOLATED(HttpStatus.CONFLICT, "격리된 전달 작업만 재처리할 수 있습니다."),
    STALE_PARTICIPATION(HttpStatus.CONFLICT, "현재 참여 세대의 요청이 아닙니다. 채팅방 목록과 이력을 다시 불러와 주세요."),
    RECEIVED_SEQUENCE_OUT_OF_RANGE(HttpStatus.BAD_REQUEST, "수신 확인 순번이 볼 수 있는 메시지 범위를 벗어났습니다."),
    TOO_MANY_TICKET_REQUESTS(HttpStatus.TOO_MANY_REQUESTS, "웹소켓 연결 티켓을 너무 자주 요청했습니다. 잠시 후 다시 시도해 주세요."),
    CONNECTION_TICKET_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "지금은 웹소켓 연결 티켓을 발급할 수 없습니다. 잠시 후 다시 시도해 주세요."),
    RECRUIT_NOT_FOUND(HttpStatus.NOT_FOUND, "모집글을 찾을 수 없습니다."),
    NOT_RECRUIT_HOST(HttpStatus.FORBIDDEN, "모집글의 호스트만 단체방을 만들 수 있습니다."),
    RECRUIT_NOT_OPEN(HttpStatus.CONFLICT, "모집 중인 모집글에만 단체방을 만들 수 있습니다."),
    APPEARANCE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "지금은 모집글 정보를 확인할 수 없습니다. 잠시 후 다시 시도해 주세요."),
    NOT_GROUP_HOST(HttpStatus.FORBIDDEN, "단체방 호스트만 할 수 있습니다."),
    GROUP_ROOM_NOT_ACTIVE(HttpStatus.CONFLICT, "사용 중인 단체방에서만 할 수 있습니다."),
    HOST_CANNOT_LEAVE(HttpStatus.CONFLICT, "호스트는 단체방을 나가는 대신 폐쇄합니다. 폐쇄한 뒤에 나갈 수 있습니다."),
    CANNOT_KICK_HOST(HttpStatus.BAD_REQUEST, "호스트는 내보낼 수 없습니다."),
    KICK_TARGET_NOT_MEMBER(HttpStatus.NOT_FOUND, "내보낼 회원이 채팅방 참여자가 아닙니다.");

    private final HttpStatus status;
    private final String message;
}
