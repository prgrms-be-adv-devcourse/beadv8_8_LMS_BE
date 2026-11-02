package com.hapbang.billing.in;

import org.springframework.data.domain.Page;

import com.hapbang.billing.global.response.RsData;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

// 지갑 API의 Swagger 명세이며, 매핑은 ApiV1WalletController가 맡는다.
@Tag(name = "지갑", description = "로그인한 회원 자신의 HP 지갑")
public interface ApiV1WalletSpec {

    @Operation(summary = "내 지갑 조회",
            description = "유저·인플루언서·인증된 인플루언서. 충전·수익 HP의 가용·보류 잔액과 합계를 반환한다. "
                    + "지갑이 없으면 잔액 0인 지갑을 만들어 반환한다(만들어도 200). 응답은 RsData의 data에 담는다. "
                    + "비로그인 401, 관리자 403(ProblemDetail).")
    RsData<WalletResponse> me(Long userId);

    @Operation(summary = "내 HP 거래 내역",
            description = "유저·인플루언서·인증된 인플루언서. 원장을 업무 작업 하나씩 묶은 거래를 최신순(첫 원장 ID 내림차순)으로 "
                    + "반환한다. 증감은 부호 그대로이고 availableChangeHp(가용 변화)가 화면 기본 금액이다. "
                    + "totalChangeHp = availableChangeHp + heldChangeHp이며 charge(충전 HP)·revenue(수익 HP)도 같은 관계다. "
                    + "결제 보류(PAYMENT_HOLD)와 지급 확정(PAYMENT_SETTLED)은 별도 거래이고, 보류·반환은 총액 변화가 0이다. "
                    + "type: CHARGE, PAYMENT_HOLD, PAYMENT_RELEASE, PAYMENT_SETTLED, PENALTY_PAID, EARNING_RECEIVED, "
                    + "PENALTY_RECEIVED, REFUND_HOLD, REFUND_RELEASE, REFUND, EXCHANGE_HOLD, EXCHANGE_RELEASE, EXCHANGE, "
                    + "REVERSAL. 시각은 UTC Instant. 지갑이 없으면 빈 목록. page 0 이상, size 1~100(기본 20), 범위 밖은 "
                    + "400 INVALID_REQUEST. 비로그인 401, 관리자 403(ProblemDetail).")
    RsData<Page<WalletTransactionResponse>> transactions(Long userId,
            @Parameter(description = "페이지 번호(0부터)") @Min(0) int page,
            @Parameter(description = "페이지 크기(1~100)") @Min(1) @Max(100) int size);
}
