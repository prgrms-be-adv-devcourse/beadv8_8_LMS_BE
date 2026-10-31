package com.hapbang.billing.in;

import org.springframework.http.ResponseEntity;

import com.hapbang.billing.global.response.RsData;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

// 충전 HP 환불 API의 Swagger 명세이며, 매핑은 ApiV1WalletRefundController가 맡는다 (#260).
@Tag(name = "HP 환불", description = "충전 HP를 TossPayments 결제 취소로 환불한다")
public interface ApiV1WalletRefundSpec {

    @Operation(summary = "충전 HP 환불 요청",
            description = "유저·인플루언서·인증된 인플루언서. 충전 HP만 환불한다(수익 HP 불가). 금액은 1 이상·1 단위"
                    + "(0 이하 400 INVALID_REQUEST). 사용·보류되지 않은 충전 HP를 오래된 충전 건부터(FIFO) 배분해 보류하고, "
                    + "충전 건(결제 건)마다 TossPayments 부분 취소를 순서대로 요청한다. 앞선 결제 건 취소가 실패하면 뒤 결제 건은 "
                    + "요청하지 않고 반환한다. 200: COMPLETED(전부 환불)·PARTIALLY_COMPLETED(일부 환불, 나머지 반환)·FAILED(환불 없음, 전부 반환). "
                    + "202: PROCESSING(결과 확인 중 — 보류 유지, GET으로 확인)·MANUAL_REVIEW_REQUIRED(운영자 확인). "
                    + "Idempotency-Key 헤더 필수(영문·숫자·-·_ 1~64자, 400 REFUND_IDEMPOTENCY_KEY_INVALID): 같은 키 재요청은 처음 요청의 "
                    + "결과(다른 금액이면 409 IDEMPOTENCY_KEY_CONFLICT). 충전 HP 부족 409 INSUFFICIENT_BALANCE, 지갑 없음 404 "
                    + "WALLET_NOT_FOUND, 결제 설정 없음 503 REFUND_UNAVAILABLE, 비로그인 401, 관리자 403.")
    ResponseEntity<RsData<RefundResponse>> request(Long userId,
            @Parameter(description = "환불 요청 멱등키(클라이언트가 요청마다 새로 만든다)", required = true) String idempotencyKey,
            @Valid RefundRequest request);

    @Operation(summary = "환불 요청 조회",
            description = "내 환불 요청의 상태와 결제 건별 취소 결과. 없거나 남의 요청은 404 REFUND_NOT_FOUND.")
    RsData<RefundResponse> getRefund(Long userId, Long refundRequestId);

    @Operation(summary = "환불 요청 취소",
            description = "PG 취소를 시작하기 전(REQUESTED)인 내 환불 요청을 취소하고 보류한 충전 HP를 모두 반환한다(200 CANCELED). "
                    + "이미 PG 취소를 시작했거나 끝났으면 409 REFUND_NOT_CANCELABLE. 없거나 남의 요청은 404 REFUND_NOT_FOUND.")
    RsData<RefundResponse> cancel(Long userId, Long refundRequestId);
}
