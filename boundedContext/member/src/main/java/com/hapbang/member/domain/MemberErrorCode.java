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
    /** 로컬 로그인: 해당 역할·번호의 테스트 계정이 없음 */
    TEST_ACCOUNT_NOT_FOUND(HttpStatus.NOT_FOUND, "테스트 계정이 없습니다."),
    INVALID_NICKNAME_LENGTH(HttpStatus.BAD_REQUEST, "닉네임은 2~12자로 입력해 주세요."),
    INVALID_NICKNAME_CHARACTER(HttpStatus.BAD_REQUEST, "닉네임은 한글·영문·숫자·공백·밑줄만 쓸 수 있습니다."),
    INVALID_NICKNAME_SPACE(HttpStatus.BAD_REQUEST, "닉네임에 공백을 연속으로 쓸 수 없습니다."),
    CHANNEL_APPROVAL_NOT_ALLOWED(HttpStatus.CONFLICT, "등록만 됐거나 반려된 채널만 승인 요청할 수 있습니다."),
    CHANNEL_NOT_FOUND(HttpStatus.NOT_FOUND, "채널을 찾을 수 없습니다."),
    CHANNEL_ALREADY_REGISTERED(HttpStatus.CONFLICT, "이미 등록된 채널입니다."),
    INVALID_CHZZK_STATE(HttpStatus.BAD_REQUEST, "치지직 연동 요청이 올바르지 않습니다. 처음부터 다시 시도해 주세요."),
    /** 치지직이 요청을 거절함(4xx, 키 오류 제외: code 만료 등) 또는 code 없이 돌아옴. 처음부터 다시 연동하면 된다 */
    CHZZK_REQUEST_REJECTED(HttpStatus.BAD_REQUEST, "치지직 연동이 완료되지 않았습니다. 처음부터 다시 시도해 주세요."),
    /** 등록된 채널이 치지직 조회 결과에 없음 (삭제·정지 등) */
    CHZZK_CHANNEL_NOT_FOUND(HttpStatus.NOT_FOUND, "치지직에서 채널을 찾을 수 없습니다.");

    private final HttpStatus status;
    private final String message;
}
