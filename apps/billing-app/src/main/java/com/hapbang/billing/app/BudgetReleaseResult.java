package com.hapbang.billing.app;

// 쿠폰 예산 해제 결과. releasedHp는 이 해제의 금액(재요청도 처음 그대로), remainingHp는 응답 시점의 예약 남은 예산이다.
public record BudgetReleaseResult(String workId, Long reservationId, long releasedHp, long remainingHp,
        boolean replayed) {
}
