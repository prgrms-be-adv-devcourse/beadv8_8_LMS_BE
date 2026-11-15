package com.hapbang.shared.billing.dto;

import java.util.Objects;

// 쿠폰 예산 해제 요청. 예약의 남은 예산에서만 빼 프로모션 지갑 가용 칸으로 되돌린다(주문 항목에 보류 중인 예산은 빼지 않는다).
// 멱등 키는 workId다. 같은 workId·같은 내용(예약·금액·대상·사유)이면 처음 결과(replayed), 다르면 409 IDEMPOTENCY_KEY_CONFLICT다.
// target은 지갑이 기록만 하며 같은 쿠폰의 서로 다른 해제를 사람이 구분하게 한다(예: UNISSUED:30, MEMBER_COUPON:901, ADMIN).
// 이 record는 작업 ID·예약 ID 형식과 대상·사유 길이 같은 요청 구조만 검사하고 금액은 검사하지 않는다.
public record PromotionBudgetReleaseCommand(
        String workId,
        String reservationId,
        long amountHp,
        String target,
        String reason
) {

    public PromotionBudgetReleaseCommand {
        PromotionBudgetRequests.requireWorkId(workId);
        PromotionBudgetRequests.requireReservationId(reservationId);
        Objects.requireNonNull(target, "해제 대상은 필수입니다.");
        if (target.isBlank() || target.length() > PromotionBudgetRequests.MAX_TARGET_LENGTH) {
            throw new IllegalArgumentException("해제 대상은 1~" + PromotionBudgetRequests.MAX_TARGET_LENGTH + "자여야 합니다.");
        }
        if (reason != null && reason.length() > PromotionBudgetRequests.MAX_REASON_LENGTH) {
            throw new IllegalArgumentException("해제 사유는 " + PromotionBudgetRequests.MAX_REASON_LENGTH + "자 이하여야 합니다.");
        }
    }
}
