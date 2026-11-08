package com.hapbang.coupon.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 쿠폰 도메인 규칙 위반. HTTP 상태·응답 코드로 바꾸는 일은 쿠폰 global이 한다(여기에는 HTTP를 두지 않는다).
 */
@Getter
@RequiredArgsConstructor
public enum CouponDomainError {

    // 발행 검증
    MISSING_TERMS("쿠폰 발행에 필요한 값이 빠졌습니다."),
    INVALID_QUANTITY("수량은 1장 이상이어야 합니다."),
    INVALID_ISSUE_PERIOD("발급 시작 시각은 발급 종료 시각보다 앞서야 합니다."),
    INVALID_RATE("정률 할인은 1% 이상 100% 이하여야 합니다."),
    INVALID_AMOUNT("정액 할인은 1 HP 이상이어야 합니다."),
    MAX_DISCOUNT_REQUIRED("모든 강의 대상 정률 쿠폰은 장당 최대 할인액(1 HP 이상)이 필요합니다."),
    MAX_DISCOUNT_NOT_ALLOWED("장당 최대 할인액은 모든 강의 대상 정률 쿠폰에만 입력합니다."),
    SELLER_TARGET_LECTURE_ONLY("판매자 쿠폰은 특정 강의 대상만 발행할 수 있습니다."),
    LECTURE_TARGET_REQUIRED("특정 강의 대상 쿠폰은 강의와 강의 가격이 필요합니다."),
    LECTURE_TARGET_NOT_ALLOWED("모든 강의 대상 쿠폰에는 강의와 강의 가격을 지정하지 않습니다."),
    INVALID_BASE_PRICE("강의 가격은 1 HP 이상이어야 합니다."),
    INVALID_RELATIVE_EXPIRY("접수 기준 만료는 1일 이상이어야 하고 고정 만료 시각을 함께 지정하지 않습니다."),
    INVALID_FIXED_EXPIRY("고정 만료는 발급 종료 시각보다 뒤의 만료 시각이 필요하고 만료 일수를 함께 지정하지 않습니다."),
    /** 만료 상한 일수를 넘었다. 던질 때 상한 일수를 넣은 메시지로 바꾼다 */
    EXPIRY_TOO_LONG("쿠폰 만료가 허용 상한보다 깁니다."),

    // 발급 기간
    ISSUE_PERIOD_ENDED("발급 종료 시각이 지난 쿠폰은 발행하거나 발급을 열 수 없습니다."),

    // 대상 강의
    LECTURE_NOT_ON_SALE("판매 중인 강의에만 특정 강의 쿠폰을 발행하거나 발급을 열 수 있습니다."),

    // 상태 전이
    INVALID_STATUS_TRANSITION("현재 상태에서는 할 수 없는 요청입니다."),
    REJECT_REASON_REQUIRED("거절 사유가 필요합니다."),
    BUDGET_NOT_RESERVED("프로모션 예산을 확보하지 않아 발급을 열 수 없습니다."),
    REDIS_NOT_READY("발급 저장소가 준비되지 않아 발급을 열 수 없습니다."),
    BUDGET_RESERVATION_MISMATCH("이미 다른 예산 예약이 기록된 쿠폰입니다."),

    // 예산
    BUDGET_NOT_APPLICABLE("판매자 쿠폰에는 프로모션 예산이 없습니다."),
    BUDGET_OVERFLOW("예산액이 너무 큽니다. 할인 값이나 수량을 줄여 주세요."),
    INVALID_BUDGET_WORK_TRANSITION("예산 작업을 이 결과로 바꿀 수 없습니다."),

    // 발급
    UNKNOWN_LUA_RESULT("발급 접수 결과를 해석하지 못했습니다."),

    // 조회·권한
    COUPON_NOT_FOUND("쿠폰을 찾을 수 없습니다."),
    NOT_COUPON_OWNER("본인이 신청한 쿠폰이 아닙니다.");

    private final String message;
}
