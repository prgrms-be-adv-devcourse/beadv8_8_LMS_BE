package com.hapbang.billing.app;

// 관리자 프로모션 지갑 충전 결과. availableHp는 응답 시점(지갑 락 아래)의 프로모션 HP 가용 잔액이다.
// 같은 충전 작업의 재요청(replayed)도 금액은 처음 기록 그대로이고, 가용 잔액은 그 시점의 값이다.
public record PromotionFundingResult(Long fundingId, String fundingWorkId, long amountHp, long availableHp,
        boolean replayed) {
}
