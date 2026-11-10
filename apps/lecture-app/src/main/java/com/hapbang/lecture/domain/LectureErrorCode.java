package com.hapbang.lecture.domain;

import org.springframework.http.HttpStatus;

import com.hapbang.lecture.global.exception.BaseErrorCode;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum LectureErrorCode implements BaseErrorCode {
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "요청 값이 올바르지 않습니다."),
    INVALID_PRICE(HttpStatus.BAD_REQUEST, "강의 가격은 1,000 HP 이상, 1,000 HP 단위여야 합니다."),
    /** 가격 인상 제한: 그 강의 대상 플랫폼 쿠폰이 발급 중이거나 쓸 수 있는 발급 쿠폰이 남아 있다 */
    LECTURE_PRICE_RAISE_BLOCKED(HttpStatus.CONFLICT, "이 강의에 쓸 수 있는 플랫폼 쿠폰이 있어 가격을 올릴 수 없습니다."),
    /** 가격 인상 제한: 쿠폰 모듈에 닿지 못했거나 결과를 모른다. 막는 쪽으로 처리한다 */
    LECTURE_PRICE_RAISE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "지금은 가격을 올릴 수 있는지 확인할 수 없습니다. 잠시 후 다시 시도해 주세요."),
    /** 강의 정보 수정: 가격 인상 제한을 확인한 뒤 수정하기 전에 다른 요청이 가격을 바꿨다 */
    LECTURE_PRICE_CHANGED(HttpStatus.CONFLICT, "그 사이 강의 가격이 바뀌었습니다. 강의 정보를 다시 불러와 수정해 주세요."),
    CONTENT_REQUIRED(HttpStatus.BAD_REQUEST, "강의 콘텐츠는 1개 이상이어야 합니다."),
    NOT_LECTURE_SELLER(HttpStatus.FORBIDDEN, "강의 판매자만 처리할 수 있습니다."),
    LECTURE_NOT_FOUND(HttpStatus.NOT_FOUND, "강의를 찾을 수 없습니다."),
    NOT_PENDING_INSPECTION(HttpStatus.CONFLICT, "검수 대기 중인 강의가 아닙니다."),
    NOT_REJECTED(HttpStatus.CONFLICT, "반려된 강의만 재검수를 신청할 수 있습니다."),
    CONTENT_NOT_EDITABLE(HttpStatus.CONFLICT, "반려된 강의만 영상·자료를 수정할 수 있습니다. 판매 중인 강의는 삭제 후 다시 등록해 주세요."),
    REJECTION_REASON_REQUIRED(HttpStatus.BAD_REQUEST, "반려 사유를 입력해 주세요."),
    LECTURE_USER_NOT_FOUND(HttpStatus.NOT_FOUND, "강의 회원 정보를 찾을 수 없습니다."),
    OWN_LECTURE_NOT_PURCHASABLE(HttpStatus.FORBIDDEN, "본인 강의는 담거나 구매할 수 없습니다."),
    ALREADY_IN_CART(HttpStatus.CONFLICT, "이미 장바구니에 담은 강의입니다."),
    ALREADY_OWNED_LECTURE(HttpStatus.CONFLICT, "이미 구매한 강의입니다."),
    /** 주문 결제 실패(보류 확정 거절). 지갑과 같은 code 문자열이다 (D69 (1)) */
    INSUFFICIENT_BALANCE(HttpStatus.CONFLICT, "HP 잔액이 부족합니다."),
    /** 탈퇴로 닫힌 지갑이라 결제(보류)가 거절됐다 (D76, 지갑 #623). 지갑 응답과 같은 코드·문구다 */
    WALLET_CLOSED(HttpStatus.CONFLICT, "탈퇴한 회원의 지갑이에요."),
    /** 쿠폰 주문: 서버가 다시 계산한 구매자 금액 합이 구매자가 본 금액({@code expectedBuyerAmountHp})과 다르다. 다시 계산해 주문한다 */
    ORDER_QUOTE_CHANGED(HttpStatus.CONFLICT, "결제 금액이 바뀌었습니다. 쿠폰 할인을 다시 확인해 주세요."),
    /** 쿠폰 주문: 쿠폰 할인 계산이 거절됐다. 응답에 쿠폰 오류 코드({@code couponCode})와 실패한 발급 쿠폰({@code memberCouponId})이 있다 */
    COUPON_CALCULATION_REJECTED(HttpStatus.CONFLICT, "선택한 쿠폰을 쓸 수 없습니다. 쿠폰을 다시 골라 주세요."),
    /** 쿠폰 주문: 쿠폰 예약이 거절되어 결제 실패로 끝났다. 응답에 쿠폰 오류 코드({@code couponCode})와 실패한 발급 쿠폰({@code memberCouponId})이 있다 */
    COUPON_RESERVATION_REJECTED(HttpStatus.CONFLICT, "쿠폰을 예약하지 못해 결제하지 못했습니다. 쿠폰을 다시 골라 주세요."),
    /** 쿠폰 주문: 플랫폼이 부담하는 할인이 있다. 플랫폼 부담 재원 처리가 생기기 전까지 막는다 */
    COUPON_PLATFORM_FUNDING_UNAVAILABLE(HttpStatus.CONFLICT, "플랫폼 쿠폰은 아직 주문에 쓸 수 없습니다."),
    /** 쿠폰 주문: 쿠폰 모듈에 닿지 못했거나 결과를 모른다. 주문을 만들지 않았다 */
    COUPON_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "지금은 쿠폰을 확인할 수 없습니다. 잠시 후 다시 주문해 주세요."),
    ORDER_NOT_FOUND(HttpStatus.NOT_FOUND, "주문을 찾을 수 없습니다."),
    ORDER_ITEM_NOT_FOUND(HttpStatus.NOT_FOUND, "주문 항목을 찾을 수 없습니다."),
    ORDER_ITEM_NOT_PURCHASED(HttpStatus.CONFLICT, "이미 구매 확정·취소된 항목입니다."),
    CANCEL_PERIOD_EXPIRED(HttpStatus.CONFLICT, "구매 취소 기간이 지났습니다. 구매일 다음 날부터 7일 이내에만 취소할 수 있습니다."),
    OBJECTION_FILED(HttpStatus.CONFLICT, "이의 제기가 접수된 항목은 구매 취소할 수 없습니다."),
    /** 이의 제기 접수·철회: 구매자 본인 항목이 아니다 (없는 항목 포함) */
    OBJECTION_NOT_BUYER(HttpStatus.NOT_FOUND, "구매자 본인의 주문 항목이 아닙니다."),
    /** 이의 제기 접수: 구매 취소되었거나 반환된 항목이다 (처리 중 포함) */
    OBJECTION_NOT_OBJECTIONABLE(HttpStatus.CONFLICT, "구매 취소되었거나 반환된 항목은 이의 제기할 수 없습니다."),
    /** 이의 제기 접수: 이미 판매자에게 지급된 항목이다 */
    OBJECTION_ALREADY_PAID_OUT(HttpStatus.CONFLICT, "이미 판매자에게 지급된 항목은 이의 제기할 수 없습니다."),
    /** 이의 제기 접수: 이의 제기가 이미 접수되었거나 판단된 항목이다 */
    OBJECTION_ALREADY_FILED(HttpStatus.CONFLICT, "이미 이의 제기한 항목입니다."),
    /** 이의 제기 접수: 지급 예정 시각(구매일 다음 날부터 30일 경과)이 지났다 */
    OBJECTION_PERIOD_EXPIRED(HttpStatus.CONFLICT, "이의 제기 기간이 지났습니다. 구매일 다음 날부터 30일 이내에만 이의 제기할 수 있습니다."),
    /** 이의 제기 판단·철회: 접수되어 판단을 기다리는 항목이 아니다 (판단은 없는 항목 포함) */
    OBJECTION_NOT_FILED(HttpStatus.CONFLICT, "판단을 기다리는 이의 제기가 없습니다."),
    /** 이의 제기 판단·철회: 접수할 때의 문의와 다른 문의다 */
    OBJECTION_INQUIRY_MISMATCH(HttpStatus.CONFLICT, "이의 제기를 접수한 문의가 아닙니다."),
    /** 이의 제기 명령: 같은 명령 ID로 다른 내용이 왔다. 먼저 처리한 결과는 그대로다 (#278) */
    OBJECTION_COMMAND_CONFLICT(HttpStatus.CONFLICT, "같은 명령 ID로 다른 내용의 이의 제기 명령이 왔습니다."),
    HP_TASK_NOT_FOUND(HttpStatus.NOT_FOUND, "HP 작업을 찾을 수 없습니다."),
    HP_TASK_NOT_DEAD(HttpStatus.CONFLICT, "자동 처리가 멈춘(DEAD) HP 작업만 재시도할 수 있습니다."),
    /** 보류 무효화: 주문 항목 보류({@code ORDER_HOLD}) 작업이 아니다 (D73) */
    HP_TASK_NOT_VOIDABLE(HttpStatus.CONFLICT, "주문 항목 보류 작업만 보류 무효화할 수 있습니다."),
    /** 서비스 간 HP 대사: 같은 앱에서 이미 실행 중이다 (D74) */
    HP_RECONCILIATION_RUNNING(HttpStatus.CONFLICT, "HP 대사가 이미 실행 중입니다."),
    /** 서비스 간 HP 대사: 실행 기록이 없다 (D74) */
    HP_RECONCILIATION_NOT_FOUND(HttpStatus.NOT_FOUND, "HP 대사 실행 기록이 없습니다."),
    FILE_NOT_FOUND(HttpStatus.NOT_FOUND, "파일을 찾을 수 없습니다."),
    NOT_FILE_UPLOADER(HttpStatus.FORBIDDEN, "파일을 올린 회원만 처리할 수 있습니다."),
    UNSUPPORTED_FILE_CONTENT_TYPE(HttpStatus.BAD_REQUEST, "이 용도로 올릴 수 없는 파일 형식입니다."),
    FILE_TOO_LARGE(HttpStatus.BAD_REQUEST, "파일이 이 용도의 최대 크기를 넘습니다."),
    FILE_SIZE_MISMATCH(HttpStatus.BAD_REQUEST, "올린 파일의 크기가 등록한 크기와 다릅니다."),
    FILE_CONTENT_TYPE_MISMATCH(HttpStatus.BAD_REQUEST, "올린 파일의 형식(Content-Type)이 등록한 형식과 다릅니다."),
    FILE_LENGTH_REQUIRED(HttpStatus.LENGTH_REQUIRED, "파일 크기(Content-Length)를 알 수 없습니다. Content-Length 헤더와 함께 보내 주세요."),
    FILE_ALREADY_UPLOADED(HttpStatus.CONFLICT, "이미 올린 파일입니다. 다른 파일을 올리려면 파일 정보를 새로 등록해 주세요."),
    /** 앱 전체 동시 업로드가 상한에 닿았다 (D81). 응답에 {@code Retry-After}가 붙고 파일은 업로드 대기로 남는다 */
    FILE_UPLOAD_BUSY(HttpStatus.SERVICE_UNAVAILABLE, "지금 올리는 파일이 많습니다. 잠시 후 다시 올려 주세요."),
    INVALID_LECTURE_FILE(HttpStatus.BAD_REQUEST, "강의에 연결할 수 없는 파일입니다."),
    LECTURE_CONTENT_NOT_FOUND(HttpStatus.NOT_FOUND, "강의 콘텐츠를 찾을 수 없습니다."),
    CONTENT_ACCESS_DENIED(HttpStatus.FORBIDDEN, "강의 콘텐츠를 볼 수 있는 회원이 아닙니다."),
    PLAY_CONSENT_REQUIRED(HttpStatus.FORBIDDEN, "재생 동의 후 볼 수 있습니다. 재생하면 구매가 확정되어 단순 변심으로 구매 취소할 수 없습니다."),
    ORDER_ITEM_NOT_REVIEWABLE(HttpStatus.CONFLICT, "리뷰를 쓸 수 있는 주문 항목이 아닙니다."),
    ALREADY_REVIEWED(HttpStatus.CONFLICT, "이미 리뷰를 쓴 강의입니다. 기존 리뷰를 수정하거나 삭제한 뒤 다시 써 주세요."),
    REVIEW_NOT_FOUND(HttpStatus.NOT_FOUND, "리뷰를 찾을 수 없습니다."),
    NOT_REVIEW_WRITER(HttpStatus.FORBIDDEN, "리뷰 작성자만 처리할 수 있습니다.");

    private final HttpStatus status;
    private final String message;
}
