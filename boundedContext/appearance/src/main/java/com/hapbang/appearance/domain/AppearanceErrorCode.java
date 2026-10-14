package com.hapbang.appearance.domain;

import org.springframework.http.HttpStatus;

import com.hapbang.global.exception.BaseErrorCode;

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
    INSUFFICIENT_HP(HttpStatus.CONFLICT, "HP 잔액이 부족합니다."),
    APPEARANCE_REQUEST_NOT_EDITABLE(HttpStatus.CONFLICT, "게스트가 응답하기 전인 출연 요청서만 수정할 수 있습니다."),

    // 공통
    CONCURRENT_UPDATE(HttpStatus.CONFLICT, "다른 요청이 먼저 처리되었습니다. 새로 고침한 뒤 다시 시도해 주세요."),

    // 외부 연동
    BILLING_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE,
            "HP 보류 처리가 지연되고 있습니다. 잠시 후 보낸 요청 목록에서 결과를 확인해 주세요.");

    private final HttpStatus status;
    private final String message;
}
