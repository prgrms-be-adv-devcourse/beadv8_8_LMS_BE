package com.hapbang.shared.billing.dto;

// 쿠폰 예산 예약 요청. 쿠폰 하나의 플랫폼 부담 예산만큼 프로모션 지갑 가용 HP를 예약 칸으로 옮긴다. 쿠폰당 예약은 하나다.
// 멱등 키는 workId다. 같은 workId·같은 내용(쿠폰·정책 버전·금액)이면 처음 결과(replayed), 다르면 409 IDEMPOTENCY_KEY_CONFLICT다.
// 이 record는 작업 ID 형식·쿠폰 ID·정책 버전 같은 요청 구조만 검사하고 금액은 검사하지 않는다(0 이하는 지갑이 INVALID_POINT_AMOUNT).
public record PromotionBudgetReserveCommand(
        String workId,
        long couponId,
        int policyVersion,
        long amountHp
) {

    public PromotionBudgetReserveCommand {
        PromotionBudgetRequests.requireWorkId(workId);
        BillingRequests.requirePositiveId(couponId, "쿠폰 ID");
        if (policyVersion < 1) {
            throw new IllegalArgumentException("정책 버전은 1 이상이어야 합니다: " + policyVersion);
        }
    }
}
