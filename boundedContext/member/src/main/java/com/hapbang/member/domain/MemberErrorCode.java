package com.hapbang.member.domain;

import org.springframework.http.HttpStatus;

import com.hapbang.global.exception.BaseErrorCode;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** 회원 모듈 오류 코드. 응답의 code 값으로 그대로 나간다 */
@Getter
@RequiredArgsConstructor
public enum MemberErrorCode implements BaseErrorCode {
    MEMBER_NOT_FOUND(HttpStatus.NOT_FOUND, "회원을 찾을 수 없습니다."),
    INVALID_NICKNAME_LENGTH(HttpStatus.BAD_REQUEST, "닉네임은 2~12자로 입력해 주세요."),
    INVALID_NICKNAME_CHARACTER(HttpStatus.BAD_REQUEST, "닉네임은 한글·영문·숫자·공백·밑줄만 쓸 수 있습니다."),
    INVALID_NICKNAME_SPACE(HttpStatus.BAD_REQUEST, "닉네임에 공백을 연속으로 쓸 수 없습니다."),
    CHANNEL_APPROVAL_NOT_ALLOWED(HttpStatus.CONFLICT, "등록만 됐거나 반려된 채널만 승인 요청할 수 있습니다."),
    CHANNEL_ALREADY_REGISTERED(HttpStatus.CONFLICT, "이미 등록된 채널입니다."),
    INVALID_CHZZK_STATE(HttpStatus.BAD_REQUEST, "치지직 연동 요청이 올바르지 않습니다. 처음부터 다시 시도해 주세요."),
    CHZZK_API_FAILED(HttpStatus.BAD_GATEWAY, "치지직 연동 중 문제가 생겼습니다. 잠시 후 다시 시도해 주세요.");

    private final HttpStatus status;
    private final String message;
}
