package com.hapbang.appearance.domain;

import org.springframework.http.HttpStatus;

import com.hapbang.appearance.global.exception.BaseErrorCode;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 출연 모듈 오류 코드. 응답의 {@code code} 값으로 그대로 나간다. 기능을 추가할 때 함께 늘려 간다.
 * <ul>
 *     <li>{@code status}: HTTP 상태. 상태 때문에 처리할 수 없는 경우(중복, 잔액 부족 등)는 409 (강의·채팅 모듈과 같은 기준)</li>
 *     <li>{@code message}: 오류 설명이자 사용자에게 보여 줄 기본 메시지 (응답의 {@code detail})</li>
 * </ul>
 */
@Getter
@RequiredArgsConstructor
public enum AppearanceErrorCode implements BaseErrorCode {
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "요청 값이 올바르지 않습니다."),

    // 출연 회원 (호스트·게스트 자격)
    APPEARANCE_USER_NOT_FOUND(HttpStatus.NOT_FOUND, "출연 회원 정보를 찾을 수 없습니다."),
    APPEARANCE_USER_NOT_ACTIVE(HttpStatus.FORBIDDEN, "현재 이용할 수 없는 회원입니다."),
    NOT_INFLUENCER(HttpStatus.FORBIDDEN, "인플루언서 이상만 출연 거래에 참여할 수 있습니다."),

    // 출연 요청서
    CANNOT_REQUEST_SELF(HttpStatus.BAD_REQUEST, "자기 자신에게는 출연을 요청할 수 없습니다."),
    FEE_BELOW_MINIMUM(HttpStatus.BAD_REQUEST, "출연료가 최저 출연료보다 적습니다."),
    INVALID_REQUEST_SCHEDULE(HttpStatus.BAD_REQUEST, "수락 기한은 현재 이후이고 방송 예정 일시보다 앞서야 합니다."),
    NOT_REQUEST_HOST(HttpStatus.FORBIDDEN, "출연 요청서를 보낸 호스트만 수정할 수 있습니다."),
    APPEARANCE_REQUEST_NOT_FOUND(HttpStatus.NOT_FOUND, "출연 요청서를 찾을 수 없습니다."),
    DUPLICATE_APPEARANCE_REQUEST(HttpStatus.CONFLICT, "이미 처리 중이거나 응답 대기 중인 출연 요청이 있습니다."),
    APPEARANCE_REQUEST_NOT_EDITABLE(HttpStatus.CONFLICT, "게스트가 응답하기 전인 출연 요청서만 수정할 수 있습니다."),
    NOT_REQUEST_GUEST(HttpStatus.FORBIDDEN, "출연 요청을 받은 게스트만 응답할 수 있습니다."),
    APPEARANCE_REQUEST_NOT_AWAITING_RESPONSE(HttpStatus.CONFLICT, "응답 대기 중인 출연 요청이 아닙니다."),
    APPEARANCE_REQUEST_EXPIRED(HttpStatus.CONFLICT, "수락 기한이 지난 출연 요청입니다."),
    APPEARANCE_REQUEST_MODIFIED(HttpStatus.CONFLICT, "요청서 내용이 바뀌었습니다. 다시 확인한 뒤 수락해 주세요."),

    // 출연 모집글
    RECRUIT_COUNT_OUT_OF_RANGE(HttpStatus.BAD_REQUEST, "모집 인원이 허용 범위를 벗어났습니다."),
    INVALID_RECRUIT_SCHEDULE(HttpStatus.BAD_REQUEST, "모집 마감 일시는 현재 이후이고 방송 예정 일시보다 앞서야 합니다."),
    NOT_RECRUIT_HOST(HttpStatus.FORBIDDEN, "출연 모집글을 올린 호스트만 수정할 수 있습니다."),
    APPEARANCE_RECRUIT_NOT_FOUND(HttpStatus.NOT_FOUND, "출연 모집글을 찾을 수 없습니다."),
    APPEARANCE_RECRUIT_NOT_EDITABLE(HttpStatus.CONFLICT, "모집 중인 출연 모집글만 수정할 수 있습니다."),
    APPEARANCE_RECRUIT_NOT_OPEN(HttpStatus.CONFLICT, "모집 중인 출연 모집글이 아닙니다."),
    RECRUIT_FULL(HttpStatus.CONFLICT, "모집 인원이 모두 찼습니다."),

    // 출연 모집 지원
    CANNOT_APPLY_OWN_RECRUIT(HttpStatus.BAD_REQUEST, "자신이 올린 모집글에는 지원할 수 없습니다."),
    DUPLICATE_APPLICATION(HttpStatus.CONFLICT, "이미 지원한 모집글입니다. 지원을 취소했거나 거절된 경우에도 다시 지원할 수 없습니다."),
    APPLICATION_NOT_FOUND(HttpStatus.NOT_FOUND, "지원을 찾을 수 없습니다."),
    NOT_APPLICATION_GUEST(HttpStatus.FORBIDDEN, "지원한 게스트만 지원을 취소할 수 있습니다."),
    APPLICATION_NOT_PENDING(HttpStatus.CONFLICT, "호스트 응답을 기다리는 지원이 아닙니다."),

    // 출연·출연 취소 요청 (#231)
    APPEARANCE_NOT_FOUND(HttpStatus.NOT_FOUND, "출연을 찾을 수 없습니다."),
    NOT_APPEARANCE_GUEST(HttpStatus.FORBIDDEN, "출연하는 게스트만 출연 취소를 요청할 수 있습니다."),
    NOT_APPEARANCE_HOST(HttpStatus.FORBIDDEN, "출연의 호스트만 출연 취소 요청에 응답할 수 있습니다."),
    APPEARANCE_NOT_CANCELABLE(HttpStatus.CONFLICT, "출연 취소를 요청할 수 있는 출연이 아닙니다."),
    APPEARANCE_SCHEDULE_PASSED(HttpStatus.CONFLICT, "방송 예정 일시가 지난 출연입니다."),
    CANCEL_REQUEST_ALREADY_PENDING(HttpStatus.CONFLICT, "처리 중인 출연 취소 요청이 있습니다."),
    CANCEL_REQUEST_LIMIT_EXCEEDED(HttpStatus.CONFLICT, "출연 취소 요청 횟수를 모두 사용했습니다."),
    CANCEL_REQUEST_NOT_PENDING(HttpStatus.CONFLICT, "처리 중인 출연 취소 요청이 없습니다."),

    // 호스트 일방 취소·모집 전체 취소 (#239)
    CANCEL_REQUEST_CONFIRM_REQUIRED(HttpStatus.CONFLICT,
            "처리 중인 출연 취소 요청이 있습니다. 확인 후 다시 시도해 주세요."),
    APPEARANCE_RECRUIT_NOT_CANCELABLE(HttpStatus.CONFLICT, "취소할 수 있는 출연 모집글이 아닙니다."),

    // 출연 확인 (#282)
    APPEARANCE_NOT_CONFIRMABLE(HttpStatus.CONFLICT, "출연 확인을 할 수 있는 출연이 아닙니다."),
    APPEARANCE_CONFIRM_NOT_OPEN(HttpStatus.CONFLICT, "방송 예정 일시가 지난 뒤에 출연 확인을 할 수 있습니다."),
    APPEARANCE_CONFIRM_PERIOD_ENDED(HttpStatus.CONFLICT, "출연 확인 기간이 지났습니다. 출연은 자동으로 확인됩니다."),

    // HP 작업 관리 (관리자, #397)
    HP_TASK_NOT_FOUND(HttpStatus.NOT_FOUND, "HP 작업을 찾을 수 없습니다."),
    HP_TASK_NOT_DEAD(HttpStatus.CONFLICT, "격리된(DEAD) HP 작업만 다시 시도할 수 있습니다."),

    // 공통
    CONCURRENT_UPDATE(HttpStatus.CONFLICT, "다른 요청이 먼저 처리되었습니다. 새로 고침한 뒤 다시 시도해 주세요."),

    // 외부 연동
    BILLING_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE,
            "HP 보류 처리가 지연되고 있습니다. 잠시 후 보낸 요청 목록에서 결과를 확인해 주세요.");

    private final HttpStatus status;
    private final String message;
}
