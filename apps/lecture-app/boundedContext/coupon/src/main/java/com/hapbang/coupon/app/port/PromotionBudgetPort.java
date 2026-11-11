package com.hapbang.coupon.app.port;

/**
 * 플랫폼 쿠폰의 프로모션 예산(billing). 실제 어댑터는 billing 계약이 정해진 뒤 붙인다. 지금은 구현이 없고, 구현이 없으면
 * 플랫폼 쿠폰을 열지 않는다(가짜 성공을 두지 않는다).
 * <p>
 * 같은 작업 ID의 재호출은 같은 결과를 돌려줘야 한다(멱등). 결과를 알 수 없으면 {@link Outcome#UNKNOWN}이다.
 */
public interface PromotionBudgetPort {

    BudgetResult reserve(String workId, long couponId, int policyVersion, long amount);

    BudgetResult release(String workId, String reservationId, long amount, String reason);

    BudgetResult status(String workId);

    enum Outcome {
        SUCCEEDED, REJECTED, UNKNOWN
    }

    /**
     * @param reservationId 예산 예약 ID(성공한 reserve만)
     * @param reason        거절·불명 사유
     */
    record BudgetResult(Outcome outcome, String reservationId, String reason) {
    }
}
