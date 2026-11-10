package com.hapbang.billing.in;

import com.hapbang.billing.global.response.RsData;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

// 관리자 프로모션 지갑 조회·충전 API의 Swagger 명세이며, 매핑은 ApiV1SettlementPromotionWalletAdminController가 맡는다.
@Tag(name = "프로모션 지갑(관리자)", description = "플랫폼 쿠폰 재원을 담는 프로모션 지갑의 칸별 잔액을 조회하고 관리자가 충전한다")
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
}
