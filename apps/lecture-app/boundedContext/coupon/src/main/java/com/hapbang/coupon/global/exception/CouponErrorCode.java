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
    /** 발급 종료 시각이 지났다. 그런 쿠폰은 발행하거나 열기·승인·재개로 발급을 열 수 없다 */
    COUPON_ISSUE_PERIOD_ENDED(HttpStatus.CONFLICT, "발급 종료 시각이 지난 쿠폰은 발행하거나 발급을 열 수 없습니다."),

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
    INVALID_ISSUE_REQUEST_ID(HttpStatus.BAD_REQUEST, "발급 요청 ID 형식이 올바르지 않습니다."),
    /** 발급 요청 이벤트를 Kafka에 보내지 못했다(ACK 실패·시간 초과). 다시 보내면 같은 요청 ID로 다시 보낸다 */
    COUPON_EVENT_PUBLISH_FAILED(HttpStatus.SERVICE_UNAVAILABLE, "쿠폰 발급 이벤트를 보내지 못했습니다. 잠시 후 다시 시도해 주세요."),

    // 용량 상한

    /** Redis 기록이 남은 쿠폰의 수량 합에 이번 수량을 더하면 합산 상한을 넘는다. 기존 쿠폰이 정리된 뒤 다시 시도한다 */
    COUPON_CAPACITY_EXCEEDED(HttpStatus.CONFLICT, "발급 저장소 용량이 부족해 지금은 발급을 열 수 없습니다."),

    // 대사 (발급 수량 대사)

    /** 신규 접수 보류가 대사 불일치(RECONCILE)가 아닌 다른 사유(적체·복구)라 대사 보류 해제로 풀 수 없다 */
    COUPON_RECONCILE_HOLD_NOT_RELEASABLE(HttpStatus.CONFLICT, "대사 불일치 보류가 아니라 풀 수 없습니다."),

    // 복구 (Redis 유실 복구 절차)

    /** 이 쿠폰의 복구가 이미 진행 중이다. 진행 중인 복구를 끝내거나 이어서 진행한다 */
    COUPON_RECOVERY_IN_PROGRESS(HttpStatus.CONFLICT, "이미 진행 중인 복구가 있습니다."),
    /** 복구 실행을 찾을 수 없다 */
    COUPON_RECOVERY_NOT_FOUND(HttpStatus.NOT_FOUND, "복구 실행을 찾을 수 없습니다."),
    /** 복구의 지금 단계에서 요청한 단계로 넘어갈 수 없다(미처리 요청이 남았거나 순서가 맞지 않다) */
    COUPON_RECOVERY_STEP_NOT_ALLOWED(HttpStatus.CONFLICT, "지금 복구 단계에서는 처리할 수 없습니다."),

    // DLT (발급 요청 처리 실패 격리)

    /** DLT 기록을 찾을 수 없다 */
    COUPON_DLT_NOT_FOUND(HttpStatus.NOT_FOUND, "DLT 기록을 찾을 수 없습니다."),
    /** 이미 재발행했거나 영구 거절한 DLT 기록이다 */
    COUPON_DLT_ALREADY_RESOLVED(HttpStatus.CONFLICT, "이미 처리한 DLT 기록입니다."),

    // 사용 (강의 → 쿠폰 내부 API: 계산·예약·해제). 실패한 발급 쿠폰이 있으면 응답에 memberCouponId를 싣는다(CouponUsageException)

    /** 쿠폰의 적용 대상이 아닌 강의에 두었다 */
    COUPON_NOT_APPLICABLE(HttpStatus.CONFLICT, "이 강의에 적용할 수 없는 쿠폰입니다."),
    /** 본인 소유가 아니거나, AVAILABLE이 아니거나, 만료되었다 */
    COUPON_NOT_USABLE(HttpStatus.CONFLICT, "사용할 수 없는 쿠폰입니다."),
    /** 한 강의에 같은 주체 두 장, 단독 사용 쿠폰과 다른 쿠폰, 한 발급 쿠폰을 두 강의에 두었다 */
    COUPON_STACKING_VIOLATION(HttpStatus.CONFLICT, "함께 사용할 수 없는 쿠폰 조합입니다."),
    /** 요청의 기대 세대가 지금 발급 쿠폰의 세대와 다르다(다른 사용 시도가 먼저 바꿨다). 다시 계산해야 한다 */
    COUPON_GENERATION_MISMATCH(HttpStatus.CONFLICT, "쿠폰 상태가 바뀌었습니다. 다시 계산해 주세요."),
    /** 계산 뒤 쿠폰 정책 버전이 바뀌었다. 다시 계산해야 한다 */
    COUPON_POLICY_VERSION_CHANGED(HttpStatus.CONFLICT, "쿠폰 조건이 바뀌었습니다. 다시 계산해 주세요."),
    /** 다시 계산한 할인 금액·적용 순서가 요청의 근거와 다르다 */
    COUPON_AMOUNT_MISMATCH(HttpStatus.CONFLICT, "쿠폰 할인 금액이 계산 결과와 다릅니다. 다시 계산해 주세요."),
    /** 요청의 강의 가격·판매자가 강의의 지금 값과 다르다 */
    COUPON_LECTURE_PRICE_MISMATCH(HttpStatus.CONFLICT, "강의 가격이 바뀌었습니다. 다시 계산해 주세요."),
    /** 같은 작업 ID로 다른 내용의 요청이 왔다 */
    COUPON_WORK_CONFLICT(HttpStatus.CONFLICT, "이미 다른 내용에 쓰인 작업 ID입니다."),
    /** 해제할 수 없는 사용 시도다(예약 상태가 아니거나, 다른 주문·다른 세대다) */
    COUPON_RELEASE_NOT_ALLOWED(HttpStatus.CONFLICT, "해제할 수 없는 쿠폰 사용입니다."),
    /** 작업 ID 형식이 올바르지 않다(1~100자, 영문·숫자·{@code :_-}) */
    INVALID_WORK_ID(HttpStatus.BAD_REQUEST, "작업 ID 형식이 올바르지 않습니다."),
    /** 한 요청의 항목 수가 상한을 넘었다 */
    COUPON_TOO_MANY_ITEMS(HttpStatus.BAD_REQUEST, "한 번에 계산할 수 있는 강의 수를 넘었습니다."),
    /** 한 주문에 같은 강의가 두 번 있다 */
    COUPON_DUPLICATE_LECTURE(HttpStatus.BAD_REQUEST, "같은 강의가 두 번 들어 있습니다.");

    private final HttpStatus status;
    private final String message;

    /** 응답의 {@code code}. enum 이름이다 */
    public String getCode() {
        return name();
    }
}
