package com.hapbang.billing.app;

// 정산 실행 한 번의 결과 (#305). 건수는 이번 실행에서 처리를 시도한 고유 환전 기준이며 상태는 각 환전 송금 실행(ExchangePayoutResult) 직후 값이다.
// - skipped: 송금 Client를 쓸 수 없는 환경(Mock이 꺼진 dev·prod 등)이라 대상 조회·송금 없이 건너뛰었다
// - targets: 처리를 시도한 환전 수(실행당 최대 ExchangeSettlementUseCase.MAX_PER_RUN), sent: 새 송금 시도를 보낸 수
// - completed·failed: 송금 성공·확정 실패로 끝난 수, inProgress: 결과 대기(PROCESSING·UNKNOWN), manualReview: 운영자 확인으로 넘어간 수
// - errors: 예외로 처리하지 못한 수(환전은 APPROVED로 남아 다음 실행에서 다시 처리한다)
// - capReached: 실행당 상한에 닿아 남은 대상을 다음 실행으로 넘겼다
public record ExchangeSettlementRunResult(boolean skipped, int targets, int sent, int completed, int failed,
        int inProgress, int manualReview, int errors, boolean capReached) {

    static ExchangeSettlementRunResult skippedRun() {
        return new ExchangeSettlementRunResult(true, 0, 0, 0, 0, 0, 0, 0, false);
    }
}
