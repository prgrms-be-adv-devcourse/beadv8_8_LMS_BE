package com.hapbang.member.domain;

import org.springframework.http.HttpStatus;

import com.hapbang.member.global.exception.BaseErrorCode;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** 회원 모듈 오류 코드. 응답의 code 값으로 그대로 나간다 */
@Getter
@RequiredArgsConstructor
public enum MemberErrorCode implements BaseErrorCode {
    MEMBER_NOT_FOUND(HttpStatus.NOT_FOUND, "회원을 찾을 수 없습니다."),
    TEST_ACCOUNT_REQUIRED(HttpStatus.FORBIDDEN, "부하 시험용 이메일 형식의 회원만 승급할 수 있습니다."),
    TEST_ACCOUNT_ROLE_NOT_ALLOWED(HttpStatus.CONFLICT, "테스트 일반 회원만 인플루언서로 승급할 수 있습니다."),
    /** 같은 회원을 동시에 수정해 늦게 저장한 쪽이 실패 (관리자 역할 변경과 본인 수정이 겹칠 때 등). 다시 시도하면 된다 */
    MEMBER_CONCURRENT_UPDATE(HttpStatus.CONFLICT, "다른 변경과 겹쳤어요. 다시 시도해 주세요."),
    /**
     * 재발급 실패: refresh token이 없거나 만료·폐기됨, 재사용(탈취 의심), 이용할 수 없는 회원 (#370).
     * 어느 경우인지는 응답에 드러내지 않고 서버 로그에만 남긴다. 프론트는 이 코드면 다시 로그인시킨다
     */
    INVALID_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED, "로그인이 만료됐어요. 다시 로그인해 주세요."),
    INVALID_PROFILE_IMAGE_TYPE(HttpStatus.BAD_REQUEST, "프로필 사진은 jpeg·png·webp만 올릴 수 있습니다."),
    /** 헤더(Content-Type)는 허용 형식인데 파일 앞부분(매직 넘버)이 그 형식이 아님: 헤더만 바꾼 다른 파일·깨진 파일 */
    PROFILE_IMAGE_CONTENT_MISMATCH(HttpStatus.BAD_REQUEST, "파일 내용이 이미지 형식과 맞지 않습니다. jpeg·png·webp 이미지를 올려 주세요."),
    INVALID_PROFILE_IMAGE_SIZE(HttpStatus.BAD_REQUEST, "프로필 사진은 5MB 이하로 올려 주세요."),
    /** 주소의 회원 ID가 내 ID가 아님 (회원 ID는 프로필에서 공개라 404로 숨기지 않는다) */
    PROFILE_IMAGE_FORBIDDEN(HttpStatus.FORBIDDEN, "본인 프로필 사진만 올릴 수 있습니다."),
    INVALID_NICKNAME_LENGTH(HttpStatus.BAD_REQUEST, "닉네임은 2~12자로 입력해 주세요."),
    INVALID_NICKNAME_CHARACTER(HttpStatus.BAD_REQUEST, "닉네임은 한글·영문·숫자·공백·밑줄만 쓸 수 있습니다."),
    INVALID_NICKNAME_SPACE(HttpStatus.BAD_REQUEST, "닉네임에 공백을 연속으로 쓸 수 없습니다."),
    /** 닉네임 중복 (#646). 대소문자만 달라도 같은 닉네임이고, 탈퇴 회원 닉네임도 쓸 수 없다 */
    DUPLICATE_NICKNAME(HttpStatus.CONFLICT, "이미 쓰고 있는 닉네임이에요."),
    INVALID_PASSWORD_LENGTH(HttpStatus.BAD_REQUEST, "비밀번호는 8~64자로 입력해 주세요."),
    INVALID_PASSWORD_CHARACTER(HttpStatus.BAD_REQUEST, "비밀번호는 영문·숫자·특수문자만 쓸 수 있습니다. 공백과 한글은 쓸 수 없습니다."),
    PASSWORD_LETTER_AND_DIGIT_REQUIRED(HttpStatus.BAD_REQUEST, "비밀번호에 영문과 숫자를 하나 이상씩 넣어 주세요."),
    DUPLICATE_EMAIL(HttpStatus.CONFLICT, "이미 가입된 이메일입니다."),
    /** 가입 이메일 인증 (#557): 같은 이메일은 1분에 한 번만 코드를 받는다 */
    EMAIL_CODE_RESEND_TOO_SOON(HttpStatus.TOO_MANY_REQUESTS, "인증 코드는 1분에 한 번 받을 수 있어요. 잠시 뒤 다시 시도해 주세요."),
    /** 가입 이메일 인증 (#557): 한 IP에서 1시간에 보낼 수 있는 코드 수를 넘음 */
    EMAIL_CODE_TOO_MANY_REQUESTS(HttpStatus.TOO_MANY_REQUESTS, "인증 코드 요청이 너무 많아요. 1시간 뒤에 다시 시도해 주세요."),
    /** 가입 이메일 인증 (#557): 코드가 틀림 (5번까지 시도할 수 있음) */
    INVALID_EMAIL_CODE(HttpStatus.BAD_REQUEST, "인증 코드가 맞지 않아요."),
    /** 가입 이메일 인증 (#557): 받은 코드가 없거나 10분이 지났거나 5번 틀려 지워짐 */
    EMAIL_CODE_EXPIRED(HttpStatus.BAD_REQUEST, "인증 코드가 만료됐어요. 코드를 다시 받아 주세요."),
    /** 가입 이메일 인증 (#557): 가입할 때 인증 토큰이 없거나, 만료됐거나, 다른 이메일의 토큰 */
    EMAIL_NOT_VERIFIED(HttpStatus.BAD_REQUEST, "이메일 인증을 먼저 해 주세요."),
    /** 이메일 인증 (#557): 한 이메일이 하루에 받을 수 있는 메일 수(5통)를 넘음 */
    EMAIL_CODE_DAILY_LIMIT(HttpStatus.TOO_MANY_REQUESTS, "이 이메일로는 오늘 인증 메일을 더 받을 수 없어요. 내일 다시 시도해 주세요."),
    /** 이메일 인증 (#557): 앱 전체 하루 발송 상한(300통)에 닿았거나 메일 대기열이 가득 참. 남용이 의심돼 운영자가 확인해야 한다 */
    EMAIL_SEND_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "지금은 인증 메일을 보낼 수 없어요. 잠시 뒤 다시 시도해 주세요."),
    // 가입된 이메일인지 알려주지 않으려고 없는 이메일과 틀린 비밀번호를 같은 오류로 답한다
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 올바르지 않습니다."),
    LOGIN_LOCKED(HttpStatus.TOO_MANY_REQUESTS, "로그인에 여러 번 실패했어요. 15분 뒤에 다시 시도해 주세요."),
    LOGIN_NOT_ALLOWED(HttpStatus.FORBIDDEN, "탈퇴했거나 이용할 수 없는 회원입니다."),
    /**
     * 탈퇴했지만 복구 기한 안 (#593). 본문에는 restorableUntil만 싣고 복구 티켓은 쿠키(RESTORE_TICKET)로 준다(#633).
     * 프론트가 "복구할까요?"를 보여 준다
     */
    WITHDRAWN_RESTORABLE(HttpStatus.CONFLICT, "탈퇴한 계정이에요. 복구하면 다시 이용할 수 있어요."),
    /** 복구 (#593): 복구 티켓이 없거나 만료됨(10분). 다시 로그인하면 새 티켓을 받는다 */
    INVALID_RESTORE_TICKET(HttpStatus.UNAUTHORIZED, "복구 요청이 만료됐어요. 다시 로그인해 주세요."),
    /** 복구 (#593): 탈퇴 때 닫은 지갑을 다시 열지 못해 복구하지 않음. 티켓이 남아 있어 그대로 다시 시도하면 된다 */
    RESTORE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "지금은 복구할 수 없어요. 잠시 후 다시 시도해 주세요."),
    EMAIL_REGISTERED_WITH_PASSWORD(HttpStatus.CONFLICT, "이미 비밀번호로 가입된 이메일입니다. 이메일과 비밀번호로 로그인해 주세요."),
    PASSWORD_NOT_SET(HttpStatus.CONFLICT, "비밀번호로 가입한 회원만 비밀번호를 바꿀 수 있습니다."),
    INVALID_CURRENT_PASSWORD(HttpStatus.BAD_REQUEST, "현재 비밀번호가 올바르지 않습니다."),
    PASSWORD_CHANGE_LOCKED(HttpStatus.TOO_MANY_REQUESTS, "현재 비밀번호를 여러 번 틀렸어요. 15분 뒤에 다시 시도해 주세요."),
    CHANNEL_APPROVAL_NOT_ALLOWED(HttpStatus.CONFLICT, "등록만 됐거나 반려된 채널만 승인 요청할 수 있습니다."),
    /** 심사 대기가 아닌 채널 (이미 처리됨 포함) */
    CHANNEL_REVIEW_NOT_ALLOWED(HttpStatus.CONFLICT, "심사 대기 중인 채널만 승인·반려할 수 있습니다."),
    /** 채널 주인이 탈퇴 등으로 정상 회원이 아님 (목록을 거치지 않은 호출·처리 직전 탈퇴) */
    CHANNEL_OWNER_INACTIVE(HttpStatus.CONFLICT, "탈퇴했거나 이용할 수 없는 회원의 채널입니다."),
    REJECTION_REASON_REQUIRED(HttpStatus.BAD_REQUEST, "반려 사유를 입력해 주세요."),
    INVALID_REJECTION_REASON_LENGTH(HttpStatus.BAD_REQUEST, "반려 사유는 500자 이하로 입력해 주세요."),
    /** 승급 신청은 인플루언서만 (유저·이미 인증된 인플루언서는 불가) */
    UPGRADE_NOT_ALLOWED(HttpStatus.FORBIDDEN, "인플루언서만 승급을 신청할 수 있습니다."),
    UPGRADE_REASON_REQUIRED(HttpStatus.BAD_REQUEST, "승급 신청 사유를 입력해 주세요."),
    INVALID_UPGRADE_REASON_LENGTH(HttpStatus.BAD_REQUEST, "승급 신청 사유는 500자 이하로 입력해 주세요."),
    /** 회원당 심사 대기 신청은 1건 (동시 신청으로 DB 인덱스에 막혀도 같은 코드) */
    UPGRADE_REQUEST_ALREADY_PENDING(HttpStatus.CONFLICT, "이미 심사 중인 승급 신청이 있습니다."),
    UPGRADE_REQUEST_NOT_FOUND(HttpStatus.NOT_FOUND, "승급 신청을 찾을 수 없습니다."),
    /** 심사 대기가 아닌 신청 (이미 처리됨 포함) */
    UPGRADE_REQUEST_REVIEW_NOT_ALLOWED(HttpStatus.CONFLICT, "심사 대기 중인 승급 신청만 승인·반려할 수 있습니다."),
    /** 신청한 회원이 탈퇴 등으로 정상 회원이 아님 (목록을 거치지 않은 호출·처리 직전 탈퇴) */
    UPGRADE_REQUEST_MEMBER_INACTIVE(HttpStatus.CONFLICT, "탈퇴했거나 이용할 수 없는 회원의 승급 신청입니다."),
    /** 승인하려는데 회원이 더는 인플루언서가 아님 (인플루언서만 인증된 인플루언서가 된다) */
    UPGRADE_MEMBER_NOT_INFLUENCER(HttpStatus.CONFLICT, "인플루언서인 회원만 승급할 수 있습니다."),
    CHANNEL_NOT_FOUND(HttpStatus.NOT_FOUND, "채널을 찾을 수 없습니다."),
    CHANNEL_ALREADY_REGISTERED(HttpStatus.CONFLICT, "이미 등록된 채널입니다."),
    /** 치지직 연동 콜백 때 회원이 유저가 아님 (연동을 시작한 뒤 역할이 바뀐 경우, #370) */
    CHANNEL_CONNECT_NOT_ALLOWED(HttpStatus.FORBIDDEN, "유저만 치지직 채널을 연동할 수 있습니다."),
    INVALID_CHZZK_STATE(HttpStatus.BAD_REQUEST, "치지직 연동 요청이 올바르지 않습니다. 처음부터 다시 시도해 주세요."),
    /** 치지직이 요청을 거절함(4xx, 키 오류 제외: code 만료 등) 또는 code 없이 돌아옴. 처음부터 다시 연동하면 된다 */
    CHZZK_REQUEST_REJECTED(HttpStatus.BAD_REQUEST, "치지직 연동이 완료되지 않았습니다. 처음부터 다시 시도해 주세요."),
    /** 등록된 채널이 치지직 조회 결과에 없음 (삭제·정지 등) */
    CHZZK_CHANNEL_NOT_FOUND(HttpStatus.NOT_FOUND, "치지직에서 채널을 찾을 수 없습니다."),
    /** 받은 출연 리뷰 목록: 출연 내부 API를 부를 수 없거나(연결 실패·시간 초과·5xx·Client 없음) 거절됨(내부 키·계약 불일치) (#555) */
    APPEARANCE_REVIEW_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "지금은 출연 리뷰를 불러올 수 없습니다. 잠시 후 다시 시도해 주세요."),
    /** 탈퇴 (#593): 남은 돈·진행 중 거래가 있어 막힘. 응답 reasons에 정리할 항목을 모아 준다 */
    WITHDRAWAL_BLOCKED(HttpStatus.CONFLICT, "탈퇴하기 전에 정리할 항목이 있어요."),
    /**
     * 탈퇴 (#593): 이미 진행 중인 탈퇴가 있음 (두 번 누름, 처리 도중 멈춤). "2분"은 Withdrawal.STALE_AFTER와 같은 값이다:
     * 그 뒤에 다시 누르면 멈춘 작업을 정리하고 새로 진행한다
     */
    WITHDRAWAL_IN_PROGRESS(HttpStatus.CONFLICT, "이미 탈퇴를 처리하고 있어요. 결과가 나오지 않으면 2분쯤 뒤에 다시 시도해 주세요."),
    /** 탈퇴 (#593): 지갑·출연·강의에 확인하지 못해 진행하지 않음 (되돌렸거나 되돌리는 중) */
    WITHDRAWAL_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "지금은 탈퇴할 수 없어요. 잠시 후 다시 시도해 주세요.");

    private final HttpStatus status;
    private final String message;
}
