package com.hapbang.coupon.app.port;

import com.hapbang.coupon.domain.BudgetWorkKind;

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
     * @param reservationId 성공한 명령·조회가 확인한 예산 예약 ID
     * @param reason        거절·불명 사유
     * @param work          성공한 작업 조회의 대조 근거. 명령 응답은 요청과 어댑터가 대조하므로 생략한다
     */
    record BudgetResult(Outcome outcome, String reservationId, String reason, WorkMetadata work) {
        public BudgetResult(Outcome outcome, String reservationId, String reason) {
            this(outcome, reservationId, reason, null);
        }
    }

    /** 작업 조회와 예약 조회를 합친 근거. 영속 작업의 내용과 모두 일치해야 완료로 기록한다. */
    record WorkMetadata(String workId, BudgetWorkKind kind, Long couponId, Long amount) {
    }
}
