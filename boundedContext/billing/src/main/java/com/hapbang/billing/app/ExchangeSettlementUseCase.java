package com.hapbang.billing.app;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;

import com.hapbang.billing.domain.BillingException;
import com.hapbang.billing.domain.ExchangePayoutStatus;
import com.hapbang.billing.out.PayoutTransferClient;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

// 정산일 자동 송금 실행 (#305). 정산 예정 시각이 지난 관리자 승인(APPROVED) 환전을 찾아 기존 송금 실행(ExchangePayoutUseCase.execute)을
// 건마다 부른다. 금융 처리(잠금·송금 시도·멱등성·확정 차감)는 모두 execute에 맡긴다.
// 트랜잭션을 열지 않는다: 대상 조회는 짧은 읽기 트랜잭션, 송금 HTTP는 execute 안에서 트랜잭션 밖이다(#304 구조).
// - 송금 Client를 쓸 수 없으면(Mock이 꺼진 dev·prod) 대상마다 503을 내지 않고 실행 시작에서 건너뛴다
// - 대상: APPROVED + 정산 예정 시각 <= 기준 시각(지난 회차의 미처리 건 포함). 회차를 다음 달로 미루지 않는다
// - ID 커서로 PAGE_SIZE건씩 읽고, 한 실행에서 고유 환전 MAX_PER_RUN건까지 처리한다. 남은 대상은 다음 실행에서 처리한다.
//   예외가 난 환전은 APPROVED로 남지만 커서가 지나가므로 같은 실행에서 다시 읽지 않는다
// - 환전마다 예외를 잡아 다음 환전을 계속 처리한다. 예외가 난 환전을 FAILED·FINAL_FAILED로 바꾸지 않는다(다음 실행에서 다시 시도)
@Slf4j
@Service
@RequiredArgsConstructor
public class ExchangeSettlementUseCase {

    static final int PAGE_SIZE = 100;
    static final int MAX_PER_RUN = 1_000;

    private final ExchangePayoutUseCase exchangePayoutUseCase;
    private final ExchangePayoutProcessing exchangePayoutProcessing;
    private final PayoutTransferClient payoutTransferClient;
    private final ExchangePayoutClock clock;

    public ExchangeSettlementRunResult run() {
        if (!payoutTransferClient.isAvailable()) {
            log.warn("환전 정산 실행을 건너뛴다: 송금 Client를 쓸 수 없는 환경이다(가상 송금 Mock 꺼짐 등)");
            return ExchangeSettlementRunResult.skippedRun();
        }
        long started = System.nanoTime();
        Instant dueAt = clock.now();
        Tally tally = new Tally();
        long afterId = 0L;
        boolean capReached = false;
        while (true) {
            int limit = Math.min(PAGE_SIZE, MAX_PER_RUN - tally.targets);
            List<Long> ids = exchangePayoutProcessing.dueExchangeIds(dueAt, afterId, limit);
            for (Long exchangeId : ids) {
                tally.process(exchangeId);
                afterId = exchangeId;
            }
            if (tally.targets >= MAX_PER_RUN) {
                capReached = !exchangePayoutProcessing.dueExchangeIds(dueAt, afterId, 1).isEmpty();
                break;
            }
            if (ids.size() < limit) {
                break;
            }
        }
        ExchangeSettlementRunResult result = tally.result(capReached);
        log.info("환전 정산 실행 완료: {}, 소요 {}ms", result, Duration.ofNanos(System.nanoTime() - started).toMillis());
        return result;
    }

    private final class Tally {

        private int targets;
        private int sent;
        private int completed;
        private int failed;
        private int inProgress;
        private int manualReview;
        private int errors;

        void process(Long exchangeId) {
            targets++;
            try {
                ExchangePayoutResult result = exchangePayoutUseCase.execute(exchangeId);
                if (result.action() == ExchangePayoutAction.SENT) {
                    sent++;
                }
                switch (result.exchangeStatus()) {
                    case COMPLETED -> completed++;
                    case FAILED -> failed++;
                    case PROCESSING, UNKNOWN -> {
                        if (result.payoutStatus() == ExchangePayoutStatus.MANUAL_REVIEW_REQUIRED) {
                            manualReview++;
                        } else {
                            inProgress++;
                        }
                    }
                    default -> {
                    }
                }
            } catch (BillingException exception) {
                errors++;
                log.warn("환전 정산 송금 실패(다음 실행에서 다시 처리): exchangeId={}, code={}", exchangeId,
                        exception.getErrorCode());
            } catch (RuntimeException exception) {
                errors++;
                log.error("환전 정산 송금 중 예상하지 못한 오류(다음 실행에서 다시 처리): exchangeId={}", exchangeId, exception);
            }
        }

        ExchangeSettlementRunResult result(boolean capReached) {
            return new ExchangeSettlementRunResult(false, targets, sent, completed, failed, inProgress, manualReview,
                    errors, capReached);
        }
    }
}
