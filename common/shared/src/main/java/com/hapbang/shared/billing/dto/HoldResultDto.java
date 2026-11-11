package com.hapbang.shared.billing.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.hapbang.shared.billing.domain.BillingReferenceType;

// 보류 결과 (내부 API holds). 보류는 실제로 만들어진 뒤에만 돌려주며, 잔액 부족은 결과가 아니라 예외(INSUFFICIENT_BALANCE)로 알린다.
// heldHp는 이 참조 대상의 구매자 몫 최초 보류액이다. promotionHeldHp·promotions는 플랫폼 몫(쿠폰 ID 오름차순)이고, 플랫폼 몫이 없으면
// 0·빈 목록이며 JSON에 싣지 않아 지금까지의 응답과 같다. replayed가 true면 같은 요청을 다시 보내 기존 보류를 그대로 돌려받은 것이다.
// Billing 내부 정보(지갑·보류 ID, HP 종류별 금액, 충전 건별 배분)는 담지 않는다.
public record HoldResultDto(
        BillingReferenceType referenceType,
        Long referenceId,
        long heldHp,
        @JsonInclude(JsonInclude.Include.NON_DEFAULT) Long promotionHeldHp,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<HoldPromotionDto> promotions,
        boolean replayed
) {

    public HoldResultDto {
        promotionHeldHp = promotionHeldHp == null ? 0L : promotionHeldHp;
        promotions = promotions == null ? List.of() : List.copyOf(promotions);
    }

    // 플랫폼 몫이 없는 보류 결과(지금까지의 응답과 같다)
    public HoldResultDto(BillingReferenceType referenceType, Long referenceId, long heldHp, boolean replayed) {
        this(referenceType, referenceId, heldHp, 0L, List.of(), replayed);
    }
}
