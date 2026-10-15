package com.hapbang.billing.in;

import org.springframework.http.ResponseEntity;

import com.hapbang.global.response.RsData;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

// HP 충전 API의 Swagger 명세이며, 매핑은 ApiV1WalletChargeController가 맡는다 (#224).
@Tag(name = "HP 충전", description = "TossPayments 테스트 결제로 충전 HP를 충전한다")
public interface ApiV1WalletChargeSpec {

    @Operation(summary = "충전 주문 생성",
            description = "유저·인플루언서·인증된 인플루언서. 결제 전 충전 주문을 만들고(PENDING, HP 변화 없음) 결제창 호출에 쓸 "
                    + "orderId·orderName·amount를 돌려준다(201). 금액은 1,000 이상·1,000 단위(400 CHARGE_AMOUNT_INVALID). "
                    + "지갑이 없으면 404 WALLET_NOT_FOUND(먼저 GET /api/v1/wallets/me). 서버에 결제 설정이 없으면 503 CHARGE_UNAVAILABLE. "
                    + "비로그인 401, 관리자 403.")
    RsData<ChargeResponse> createOrder(Long userId, @Valid ChargeOrderRequest request);

    @Operation(summary = "결제 승인(충전)",
            description = "결제창 성공 리다이렉트로 받은 paymentKey·orderId·amount를 보내면 서버가 TossPayments에 승인을 요청하고 "
                    + "충전 HP를 한 번만 늘린다. 200 COMPLETED(충전됨), 202 PROCESSING(승인 결과 확인 중 — 같은 요청을 다시 보내거나 "
                    + "GET으로 상태를 확인한다). 같은 결제의 재요청은 처음 결과를 돌려준다. "
                    + "오류: 400 CHARGE_AMOUNT_MISMATCH(주문 금액과 다름), 400 CHARGE_PAYMENT_REJECTED(PG 미승인, pgCode), "
                    + "404 CHARGE_NOT_FOUND(없거나 남의 주문), 409 CHARGE_PAYMENT_KEY_CONFLICT(다른 결제키·이미 쓴 결제키), "
                    + "409 CHARGE_ALREADY_FAILED(실패한 주문 — 새 주문 필요), 503 CHARGE_UNAVAILABLE.")
    ResponseEntity<RsData<ChargeResponse>> confirm(Long userId, @Valid ChargeConfirmRequest request);

    @Operation(summary = "충전 주문 조회",
            description = "내 충전 주문의 상태(PENDING·PROCESSING·COMPLETED·FAILED). 없거나 남의 주문은 404 CHARGE_NOT_FOUND.")
    RsData<ChargeResponse> getCharge(Long userId, String orderId);
}
