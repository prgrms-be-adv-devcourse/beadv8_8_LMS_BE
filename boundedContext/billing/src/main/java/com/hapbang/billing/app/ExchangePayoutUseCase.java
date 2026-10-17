package com.hapbang.billing.app;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.hapbang.billing.app.ExchangePayoutProcessing.Step;
import com.hapbang.billing.domain.BillingErrorCode;
import com.hapbang.billing.domain.BillingException;
import com.hapbang.billing.domain.ExchangePayoutStatus;
import com.hapbang.billing.out.PayoutTransferClient;
import com.hapbang.billing.out.PayoutTransferRequest;
import com.hapbang.billing.out.PayoutTransferResult;

import lombok.extern.slf4j.Slf4j;

// 관리자가 승인한 환전의 가상 송금을 실행하고 결과를 반영한다 (#304). 정산 Scheduler(후속 Issue)가 execute·recoverStale을 부른다.
// 트랜잭션을 열지 않는다: DB 단계는 ExchangePayoutProcessing의 짧은 트랜잭션, Mock 은행 HTTP(PayoutTransferClient)는 그 사이
// (트랜잭션·락 밖)에서 부른다(#288 계약).
// ① 트랜잭션 1(지갑 → 환전 → 보류 락): 상태·정산 예정 시각·보류 확인, 송금 시도(지급 요청 ID UUID) PROCESSING 저장·커밋
// ② 트랜잭션 밖: send(지급 요청 ID = Idempotency-Key)
// ③ 트랜잭션 2(같은 락 순서): 결과 반영. 성공이면 보류 전액 확정 차감(요청 ID EXCHANGE-PAYOUT-{환전 ID}, 한 번만)
// 중복 송금 방지: 환전 락 아래에서 상태로 판단(APPROVED만 자동 첫 시도, FAILED는 관리자만 새 시도), DB가 환전마다 FAILED 아닌 시도를
// 1건으로 제한, 결과를 모르면 새 ID로 보내지 않고 같은 ID로 조회·재전송(Mock 은행이 같은 ID를 한 번만 처리). ①을 먼저 커밋하므로
// ②③ 어느 단계가 실패해도(서버 중단 포함) 결과 확인(recoverStale)이 같은 ID로 확정한다. 은행 성공 후 ③이 실패해도 시도가
// PROCESSING으로 남아 조회로 다시 반영한다(재송금 없음). 자동 결과 확인은 최초 송금 요청 후 24시간까지, 이후 운영자 확인.
// 관리자 기능(retryFailed·recheck·failFinally)은 패키지 안의 ExchangeAdminFacade(@PreAuthorize ADMIN)만 부른다.
@Slf4j
@Service
public class ExchangePayoutUseCase {

    static final int BATCH_SIZE = 100;

    private final ExchangePayoutProcessing processing;
    private final PayoutTransferClient payoutTransferClient;
    private final ExchangePayoutClock clock;
    private final Duration minimumAge;

    public ExchangePayoutUseCase(ExchangePayoutProcessing processing, PayoutTransferClient payoutTransferClient,
            ExchangePayoutClock clock,
            @Value("${hapbang.billing.exchange.payout.recovery-after:PT1M}") Duration minimumAge) {
        this.processing = processing;
        this.payoutTransferClient = payoutTransferClient;
        this.clock = clock;
        this.minimumAge = minimumAge;
    }

    // 승인된 환전을 송금한다(정산 Scheduler가 정산 예정 시각이 지난 승인 건마다 부른다).
    // - APPROVED: 정산 예정 시각 전이면 EXCHANGE_PAYOUT_NOT_DUE, 지나면 새 시도로 송금하고 결과 반영
    // - PROCESSING·UNKNOWN: 새로 보내지 않는다. 마지막 송금·확인 후 일정 시간(기본 1분)이 지났으면 같은 ID로 결과 확인(기한이 지났으면
    //   운영자 확인으로 넘김), 아니면 그대로
    // - COMPLETED·FAILED·FINAL_FAILED·운영자 확인 대기: 은행을 부르지 않고 현재 결과(FAILED를 자동으로 다시 보내지 않는다)
    // - REQUESTED·REJECTED·CANCELED: EXCHANGE_NOT_PAYABLE. 송금을 쓸 수 없는 환경(Mock 꺼짐)이면 EXCHANGE_PAYOUT_UNAVAILABLE
    public ExchangePayoutResult execute(Long exchangeId) {
        requireAvailable();
        return run(exchangeId, processing.start(exchangeId, checkedBefore()));
    }

    // 자동 결과 확인(재시작·장애 복구): 마지막 송금·확인 후 일정 시간(기본 1분)이 지난 결과 대기 시도(PROCESSING·UNKNOWN)를 오래된 순으로
    // 최대 BATCH_SIZE(100)건 같은 지급 요청 ID로 확인한다. 은행에 기록이 없으면 같은 ID·같은 내용으로 다시 보낸다. 최초 송금 요청 후
    // 24시간이 지난 시도는 은행을 부르지 않고 운영자 확인(MANUAL_REVIEW_REQUIRED)으로 넘긴다(보류 유지, 실패로 보지 않음).
    // 건마다 따로 처리하고 예외는 건별로 잡아 다음 건으로 넘어간다. 운영자 확인 대상은 다루지 않는다.
    public ExchangePayoutRecoveryResult recoverStale() {
        if (!payoutTransferClient.isAvailable()) {
            return ExchangePayoutRecoveryResult.EMPTY;
        }
        List<Long> exchangeIds = processing.staleExchangeIds(checkedBefore(), BATCH_SIZE);
        int completed = 0;
        int failed = 0;
        int inProgress = 0;
        int manualReview = 0;
        int errors = 0;
        for (Long exchangeId : exchangeIds) {
            try {
                ExchangePayoutResult result = execute(exchangeId);
                switch (result.exchangeStatus()) {
                    case COMPLETED -> completed++;
                    case FAILED -> failed++;
                    default -> {
                        if (result.payoutStatus() == ExchangePayoutStatus.MANUAL_REVIEW_REQUIRED) {
                            manualReview++;
                        } else {
                            inProgress++;
                        }
                    }
                }
            } catch (RuntimeException exception) {
                errors++;
                log.warn("환전 송금 결과 확인 실패, 다음 실행에서 다시 확인한다: exchangeId={}", exchangeId, exception);
            }
        }
        ExchangePayoutRecoveryResult summary = new ExchangePayoutRecoveryResult(exchangeIds.size(), completed, failed,
                inProgress, manualReview, errors);
        if (!exchangeIds.isEmpty()) {
            log.info("환전 송금 결과 확인: {}", summary);
        }
        return summary;
    }

    // 관리자 수동 재송금: 마지막 시도가 확정 실패(FAILED)인 환전만 새 시도(새 지급 요청 ID)로 보낸다. 그 밖은 EXCHANGE_PAYOUT_NOT_RETRYABLE
    ExchangePayoutResult retryFailed(Long adminId, Long exchangeId) {
        requireAvailable();
        return run(exchangeId, processing.startRetry(adminId, exchangeId));
    }

    // 관리자 결과 재확인: 결과가 확정되지 않은 마지막 시도를 같은 지급 요청 ID로 조회만 한다(새로 보내지 않는다). 그 밖은
    // EXCHANGE_PAYOUT_NOT_CHECKABLE. 반영 실패는 그대로 알린다(관리자가 다시 시도할 수 있게)
    ExchangePayoutResult recheck(Long adminId, Long exchangeId) {
        requireAvailable();
        String payoutRequestId = processing.startManualCheck(adminId, exchangeId);
        processing.applyManualCheck(exchangeId, payoutRequestId, payoutTransferClient.find(payoutRequestId));
        return processing.result(exchangeId, ExchangePayoutAction.CHECKED);
    }

    // 관리자 최종 실패 확정: 마지막 시도가 확정 실패(FAILED)인 환전만 FINAL_FAILED + 보류 전액 반환(한 트랜잭션). 은행을 부르지 않는다
    ExchangePayoutResult failFinally(Long adminId, Long exchangeId, String reason) {
        processing.failFinally(adminId, exchangeId, reason);
        return processing.result(exchangeId, ExchangePayoutAction.NONE);
    }

    private ExchangePayoutResult run(Long exchangeId, Step step) {
        ExchangePayoutAction action = switch (step) {
            case Step.Send send -> {
                deliver(exchangeId, send.request().payoutRequestId(), payoutTransferClient.send(send.request()));
                yield ExchangePayoutAction.SENT;
            }
            case Step.Check check -> check(exchangeId, check);
            case Step.Stop ignored -> ExchangePayoutAction.NONE;
        };
        return processing.result(exchangeId, action);
    }

    // 같은 지급 요청 ID로 결과를 조회한다. 은행에 기록이 없으면(보내기 전에 멈췄거나 처리 전 5xx) 같은 ID·같은 내용으로 다시 보낸다.
    // Mock 은행은 같은 ID를 한 번만 처리하므로 앞선 요청이 늦게 도착해도 중복 송금되지 않는다.
    private ExchangePayoutAction check(Long exchangeId, Step.Check check) {
        PayoutTransferResult found = payoutTransferClient.find(check.payoutRequestId());
        if (!(found instanceof PayoutTransferResult.NotFound)) {
            deliver(exchangeId, check.payoutRequestId(), found);
            return ExchangePayoutAction.CHECKED;
        }
        PayoutTransferRequest resend = check.resend();
        if (resend == null) {
            deliver(exchangeId, check.payoutRequestId(), new PayoutTransferResult.Unknown(check.payoutRequestId(),
                    "NOT_FOUND_ACCOUNT_UNREADABLE"));
            return ExchangePayoutAction.CHECKED;
        }
        deliver(exchangeId, check.payoutRequestId(), payoutTransferClient.send(resend));
        return ExchangePayoutAction.RESENT;
    }

    // 결과를 반영한다. 반영이 실패해도(DB 오류 등) 시도는 결과 대기로 남아 결과 확인이 같은 ID로 다시 반영하므로 예외를 넘기지 않는다.
    private void deliver(Long exchangeId, String payoutRequestId, PayoutTransferResult result) {
        try {
            processing.apply(exchangeId, payoutRequestId, result);
        } catch (RuntimeException exception) {
            log.error("환전 송금 결과 반영 실패, 결과 확인에서 같은 ID로 다시 반영한다: exchangeId={}, payoutRequestId={}, result={}",
                    exchangeId, payoutRequestId, result.getClass().getSimpleName(), exception);
        }
    }

    private void requireAvailable() {
        if (!payoutTransferClient.isAvailable()) {
            throw new BillingException(BillingErrorCode.EXCHANGE_PAYOUT_UNAVAILABLE);
        }
    }

    private Instant checkedBefore() {
        return clock.now().minus(minimumAge);
    }
}
