package com.hapbang.billing.app;

// 관리자용 프로모션 지갑 칸별 잔액. 가용(미배정)·예약(쿠폰 예산)·보류(주문 항목 보류)이며 지갑 캐시라 원장 합계와 대사한다.
public record PromotionWalletResult(Long walletId, long availableHp, long reservedHp, long heldHp) {
}
