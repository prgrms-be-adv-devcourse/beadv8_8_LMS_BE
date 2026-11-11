package com.hapbang.coupon.app.port;

/**
 * 플랫폼 쿠폰의 프로모션 예산(billing). HTTP 어댑터가 예약·해제·작업 조회를 수행한다. 주소가 없어 구현을 등록하지
 * 못한 환경에서는 플랫폼 쿠폰을 열지 않는다(가짜 성공을 두지 않는다).
 * <p>
 * 같은 작업 ID의 재호출은 같은 결과를 돌려줘야 한다(멱등). 결과를 알 수 없으면 {@link Outcome#UNKNOWN}이다.
 */
public interface PromotionBudget {

    BudgetResult reserve(String workId, long couponId, int policyVersion, long amount);

    BudgetResult release(String workId, String reservationId, long amount, String target, String reason);

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
