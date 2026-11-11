package com.hapbang.shared.billing.dto;

// 쿠폰 예산 해제 결과. releasedHp는 이 해제가 되돌린 금액이고, remainingHp는 응답 시점의 예약 남은 예산이다.
// replayed가 true면 같은 workId로 다시 보내 처음 결과를 돌려받은 것이다(releasedHp는 처음 그대로, remainingHp는 그 시점 값).
public record PromotionBudgetReleaseResultDto(
        String workId,
        String reservationId,
        long releasedHp,
        long remainingHp,
        boolean replayed
) {
}
