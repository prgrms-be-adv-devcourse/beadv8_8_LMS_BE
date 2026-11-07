package com.hapbang.coupon.global.exception;

import org.springframework.http.HttpStatus;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 쿠폰 업무 오류 코드. 강의 앱의 오류 코드(BaseErrorCode)와 같은 모양이다.
 * <ul>
 *     <li>{@link #getStatus()}: 응답 HTTP 상태</li>
 *     <li>{@link #getCode()}: 응답의 {@code code}. 프론트가 분기하는 식별자이며 enum 이름이다</li>
 *     <li>{@link #getMessage()}: 응답의 {@code detail} 기본값</li>
 * </ul>
 * 요청 값 검증·권한 거절·없는 경로 같은 공통 오류는 여기에 두지 않는다. 같은 JVM의 강의 공통 핸들러가 공통 코드로 응답한다.
 */
@Getter
@RequiredArgsConstructor
public enum CouponErrorCode {

    // 쿠폰 정책

    COUPON_NOT_FOUND(HttpStatus.NOT_FOUND, "쿠폰을 찾을 수 없습니다."),
    /** 판매자 쿠폰을 신청한 판매자나 발급 쿠폰을 받은 회원 본인이 아니다 */
    NOT_COUPON_OWNER(HttpStatus.FORBIDDEN, "본인의 쿠폰만 처리할 수 있습니다."),
    /** 지금 쿠폰 상태에서 허용하지 않는 상태 전이 */
    INVALID_COUPON_STATUS(HttpStatus.CONFLICT, "현재 쿠폰 상태에서는 처리할 수 없습니다."),
    /** 같은 쿠폰을 다른 요청이 먼저 바꿔 낙관적 락(@Version)이 충돌했다. 다시 읽고 시도하면 된다 */
    COUPON_CONCURRENT_UPDATE(HttpStatus.CONFLICT, "다른 요청과 동시에 바뀌었습니다. 다시 시도해 주세요."),
    /** 발행 검증 실패(수량·기간·할인 값·대상 등). 어느 규칙인지는 detail로 알린다 */
    INVALID_COUPON_POLICY(HttpStatus.BAD_REQUEST, "쿠폰 발행 조건이 올바르지 않습니다."),

    // 강의 조회 (강의 앱 내부 API). 강의 앱과 같은 뜻이라 같은 code·문구를 쓴다

    /** 쿠폰을 발행할 강의가 없거나 삭제되었다 */
    LECTURE_NOT_FOUND(HttpStatus.NOT_FOUND, "강의를 찾을 수 없습니다."),
    /** 판매자 쿠폰의 대상 강의가 신청한 판매자의 강의가 아니다 */
    NOT_LECTURE_SELLER(HttpStatus.FORBIDDEN, "강의 판매자만 처리할 수 있습니다."),
    /** 특정 강의 대상 쿠폰의 강의가 판매 중이 아니다. 쿠폰을 신청·발행하거나 승인·발행으로 발급을 열 수 없다 */
    COUPON_LECTURE_NOT_ON_SALE(HttpStatus.CONFLICT, "판매 중인 강의에만 쿠폰을 발행하거나 발급을 열 수 있습니다."),
    /** 강의 앱에 닿지 못했거나 응답을 읽지 못했다 */
    LECTURE_CATALOG_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "강의 정보를 확인할 수 없습니다. 잠시 후 다시 시도해 주세요."),

    // 프로모션 예산 (billing)

    /** billing이 예산 예약을 거절했다 */
    PROMOTION_BUDGET_REJECTED(HttpStatus.CONFLICT, "프로모션 예산을 확보하지 못했습니다."),
    /** 예산 결과를 알 수 없거나(UNKNOWN) 예산 연동이 없다. 성공으로 보지 않는다 */
    PROMOTION_BUDGET_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "프로모션 예산을 확인할 수 없습니다. 잠시 후 다시 시도해 주세요."),

    // 발급

    /** 발급 저장소(Redis)에 닿지 못했거나 결과를 확인하지 못했다 */
    COUPON_ISSUE_STORE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "쿠폰 발급을 처리할 수 없습니다. 잠시 후 다시 시도해 주세요."),
    COUPON_SOLD_OUT(HttpStatus.CONFLICT, "쿠폰이 모두 소진되었습니다."),
    COUPON_ALREADY_ISSUED(HttpStatus.CONFLICT, "이미 발급받은 쿠폰입니다."),
    /** 발급 운영 상태가 열려 있지 않거나 발급 준비(예산·Redis)가 끝나지 않았다 */
    COUPON_ISSUE_NOT_OPEN(HttpStatus.CONFLICT, "지금은 발급받을 수 없는 쿠폰입니다."),
    COUPON_ISSUE_NOT_IN_PERIOD(HttpStatus.CONFLICT, "쿠폰 발급 기간이 아닙니다."),
    /** 같은 발급 요청 ID로 다른 쿠폰·다른 내용의 요청이 왔다 */
    ISSUE_REQUEST_ID_CONFLICT(HttpStatus.CONFLICT, "이미 다른 요청에 쓰인 발급 요청 ID입니다."),
    /** 발급 처리 중 예상하지 못한 오류(Lua 오류 응답 등) */
    COUPON_ISSUE_FAILED(HttpStatus.SERVICE_UNAVAILABLE, "쿠폰 발급 중 오류가 발생했습니다. 잠시 후 다시 시도해 주세요."),
    INVALID_ISSUE_REQUEST_ID(HttpStatus.BAD_REQUEST, "발급 요청 ID 형식이 올바르지 않습니다.");

    private final HttpStatus status;
    private final String message;

    /** 응답의 {@code code}. enum 이름이다 */
    public String getCode() {
        return name();
    }
}
