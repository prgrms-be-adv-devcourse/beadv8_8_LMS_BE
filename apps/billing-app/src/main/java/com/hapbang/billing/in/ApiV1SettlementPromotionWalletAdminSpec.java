package com.hapbang.billing.in;

import com.hapbang.billing.global.response.RsData;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

// 관리자 프로모션 지갑 조회·충전·회수 API의 Swagger 명세이며, 매핑은 ApiV1SettlementPromotionWalletAdminController가 맡는다.
@Tag(name = "프로모션 지갑(관리자)", description = "플랫폼 쿠폰 재원을 담는 프로모션 지갑의 칸별 잔액을 조회하고 관리자가 충전·회수한다")
public interface ApiV1SettlementPromotionWalletAdminSpec {

    @Operation(summary = "프로모션 지갑 잔액",
            description = "관리자. 가용(미배정)·예약(쿠폰 예산)·보류(주문 항목 보류) 칸별 잔액이다. 비로그인 401, 관리자가 아니면 403.")
    RsData<PromotionWalletResponse> getPromotionWallet();

    @Operation(summary = "프로모션 지갑 충전",
            description = "관리자. 근거 번호를 붙여 새 HP를 발행해 프로모션 지갑 가용 칸에 넣는다(원장 PROMOTION_FUND 한 줄). "
                    + "같은 충전 작업 ID·같은 내용(금액·출처·근거 번호·메모)의 재요청은 다시 충전하지 않고 처음 결과를 돌려준다"
                    + "(replayed=true). 같은 ID에 다른 내용이면 409 IDEMPOTENCY_KEY_CONFLICT. 금액 0 이하는 400 INVALID_POINT_AMOUNT, "
                    + "작업 ID 형식 오류는 400 INVALID_REQUEST. 비로그인 401, 관리자가 아니면 403.")
    RsData<PromotionFundingResponse> fund(Long adminUserId, PromotionFundingRequest request);

    @Operation(summary = "프로모션 지갑 회수",
            description = "관리자. 충전으로 발행한 HP 중 쿠폰 예산으로 묶지 않은 가용 칸에서만 거둬들인다(원장 PROMOTION_WITHDRAW 한 줄). "
                    + "예약·보류 칸은 회수하지 않으며, 가용 잔액보다 많으면 409 PROMOTION_BALANCE_INSUFFICIENT이고 아무것도 바뀌지 않는다. "
                    + "같은 회수 작업 ID·같은 내용(금액·근거 번호·메모)의 재요청은 다시 회수하지 않고 처음 결과를 돌려준다(replayed=true). "
                    + "같은 ID에 다른 내용이면 409 IDEMPOTENCY_KEY_CONFLICT. 금액 0 이하는 400 INVALID_POINT_AMOUNT, "
                    + "작업 ID 형식 오류는 400 INVALID_REQUEST. 비로그인 401, 관리자가 아니면 403.")
    RsData<PromotionWithdrawalResponse> withdraw(Long adminUserId, PromotionWithdrawalRequest request);
}
