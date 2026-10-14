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
    INVALID_PROFILE_IMAGE_TYPE(HttpStatus.BAD_REQUEST, "프로필 사진은 jpeg·png·webp만 올릴 수 있습니다."),
    INVALID_PROFILE_IMAGE_SIZE(HttpStatus.BAD_REQUEST, "프로필 사진은 5MB 이하로 올려 주세요."),
    /** 주소의 회원 ID가 내 ID가 아님 (회원 ID는 프로필에서 공개라 404로 숨기지 않는다) */
    PROFILE_IMAGE_FORBIDDEN(HttpStatus.FORBIDDEN, "본인 프로필 사진만 올릴 수 있습니다."),
    INVALID_NICKNAME_LENGTH(HttpStatus.BAD_REQUEST, "닉네임은 2~12자로 입력해 주세요."),
    INVALID_NICKNAME_CHARACTER(HttpStatus.BAD_REQUEST, "닉네임은 한글·영문·숫자·공백·밑줄만 쓸 수 있습니다."),
    INVALID_NICKNAME_SPACE(HttpStatus.BAD_REQUEST, "닉네임에 공백을 연속으로 쓸 수 없습니다."),
    CHANNEL_APPROVAL_NOT_ALLOWED(HttpStatus.CONFLICT, "등록만 됐거나 반려된 채널만 승인 요청할 수 있습니다."),
    /** 심사 대기가 아닌 채널 (이미 처리됨 포함) */
    CHANNEL_REVIEW_NOT_ALLOWED(HttpStatus.CONFLICT, "심사 대기 중인 채널만 승인·반려할 수 있습니다."),
    /** 채널 주인이 탈퇴 등으로 정상 회원이 아님 (목록을 거치지 않은 호출·처리 직전 탈퇴) */
    CHANNEL_OWNER_INACTIVE(HttpStatus.CONFLICT, "탈퇴했거나 이용할 수 없는 회원의 채널입니다."),
    REJECTION_REASON_REQUIRED(HttpStatus.BAD_REQUEST, "반려 사유를 입력해 주세요."),
    INVALID_REJECTION_REASON_LENGTH(HttpStatus.BAD_REQUEST, "반려 사유는 500자 이하로 입력해 주세요."),
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
