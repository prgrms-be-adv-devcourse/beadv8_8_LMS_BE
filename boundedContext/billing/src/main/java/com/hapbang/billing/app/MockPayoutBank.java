package com.hapbang.billing.app;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.hapbang.billing.domain.BillingErrorCode;
import com.hapbang.billing.domain.BillingException;
import com.hapbang.billing.domain.MockPayoutTransfer;
import com.hapbang.billing.out.ConditionalOnMockPayout;
import com.hapbang.billing.out.MockPayoutProperties;
import com.hapbang.billing.out.MockPayoutScenario;
import com.hapbang.billing.out.MockPayoutTransferRepository;
import com.hapbang.billing.out.PaymentKeyCipher;

import lombok.extern.slf4j.Slf4j;

// Mock 은행의 가상 송금 처리 (#288, Mock이 켜졌을 때만 빈이 생긴다). 실제 돈·은행·외부 API는 없다.
// 환전·지갑·보류·원장·ExchangePayout을 읽거나 바꾸지 않는다(은행 쪽 기록 mock_payout_transfer만 다룬다).
// - 멱등성: 지급 요청 ID(UUID)마다 처음 요청을 DB에 남긴다(서버 재시작 뒤에도 같은 결과). 같은 ID·같은 내용은 저장된 결과,
//   같은 ID·다른 내용(환전 ID·금액·은행·계좌번호·예금주)은 MOCK_PAYOUT_IDEMPOTENCY_CONFLICT(409). 동시 요청은 UNIQUE로 하나만 접수된다.
// - 트랜잭션: 접수(PROCESSING)·결과 확정을 각각 짧은 트랜잭션으로 커밋하고, 시나리오의 지연(DELAY·TIMEOUT)은 트랜잭션 밖에서 기다린다.
//   DB 커넥션·락을 쥔 채 잠자지 않는다.
// - 계좌번호 원문은 저장·로그에 남기지 않는다(HMAC만 저장해 비교).
@Slf4j
@Service
@ConditionalOnMockPayout
public class MockPayoutBank {

    static final String FAILURE_CODE = "MOCK_TRANSFER_REJECTED";
    static final String TRANSACTION_PREFIX = "MOCKTX-";

    private final MockPayoutTransferRepository repository;
    private final MockPayoutScenarios scenarios;
    private final PaymentKeyCipher paymentKeyCipher;
    private final TransactionTemplate transactionTemplate;
    private final Duration delay;
    private final Duration timeoutDelay;

    public MockPayoutBank(MockPayoutTransferRepository repository, MockPayoutScenarios scenarios,
            PaymentKeyCipher paymentKeyCipher, PlatformTransactionManager transactionManager,
            MockPayoutProperties properties) {
        this.repository = repository;
        this.scenarios = scenarios;
        this.paymentKeyCipher = paymentKeyCipher;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.delay = properties.delay();
        this.timeoutDelay = properties.timeoutDelay();
    }

    // 가상 송금을 처리하고 현재 기록을 돌려준다. 이미 접수한 ID면 처리하지 않고 저장된 결과(처리 중이면 PROCESSING)를 돌려준다.
    public MockPayoutTransferView transfer(MockPayoutCommand command) {
        if (!paymentKeyCipher.isConfigured()) {
            throw new BillingException(BillingErrorCode.MOCK_PAYOUT_UNAVAILABLE);
        }
        String accountHash = paymentKeyCipher.hash(command.accountNumber());
        Optional<MockPayoutTransfer> existing = repository.findByPayoutRequestId(command.payoutRequestId());
        if (existing.isPresent()) {
            return replay(existing.get(), command, accountHash);
        }
        MockPayoutScenario scenario = scenarios.take(command.payoutRequestId());
        if (scenario == MockPayoutScenario.SERVER_ERROR) {
            // 기록 전에 끝난다: 이 요청은 처리되지 않았고 결과 조회는 NOT_FOUND다
            log.warn("가상 송금 SERVER_ERROR 시나리오(처리하지 않음): payoutRequestId={}", command.payoutRequestId());
            throw new BillingException(BillingErrorCode.MOCK_PAYOUT_SERVER_ERROR);
        }
        Optional<MockPayoutTransfer> received = receive(command, accountHash);
        if (received.isEmpty()) {
            // 같은 ID의 동시 요청이 먼저 접수했다
            return replay(repository.findByPayoutRequestId(command.payoutRequestId()).orElseThrow(), command,
                    accountHash);
        }
        if (scenario == MockPayoutScenario.DELAY) {
            sleep(delay);
        }
        MockPayoutTransferView result = complete(command.payoutRequestId(), scenario != MockPayoutScenario.FAILED);
        if (scenario == MockPayoutScenario.TIMEOUT) {
            // 결과는 이미 커밋했고 응답만 늦는다(호출한 쪽에서는 응답 유실). 결과 조회로 확인할 수 있다
            sleep(timeoutDelay);
        }
        log.info("가상 송금 처리: payoutRequestId={}, exchangeId={}, status={}", result.payoutRequestId(),
                result.exchangeId(), result.status());
        return result;
    }

    // 지급 요청 ID의 현재 기록. 없으면 MOCK_PAYOUT_NOT_FOUND
    public MockPayoutTransferView find(String payoutRequestId) {
        return repository.findByPayoutRequestId(payoutRequestId).map(MockPayoutTransferView::of)
                .orElseThrow(() -> new BillingException(BillingErrorCode.MOCK_PAYOUT_NOT_FOUND));
    }

    private MockPayoutTransferView replay(MockPayoutTransfer transfer, MockPayoutCommand command, String accountHash) {
        if (!transfer.sameRequest(command.exchangeId(), command.amountKrw(), command.bankCode(), accountHash,
                command.accountHolderName())) {
            log.warn("가상 송금 멱등키 충돌: payoutRequestId={}", command.payoutRequestId());
            throw new BillingException(BillingErrorCode.MOCK_PAYOUT_IDEMPOTENCY_CONFLICT);
        }
        return MockPayoutTransferView.of(transfer);
    }

    // 접수(PROCESSING)를 커밋한다. 같은 ID가 이미 있으면(동시 요청) 비어 있다.
    private Optional<MockPayoutTransfer> receive(MockPayoutCommand command, String accountHash) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        try {
            return Optional.ofNullable(transactionTemplate.execute(status -> repository.saveAndFlush(
                    MockPayoutTransfer.received(command.payoutRequestId(), command.exchangeId(), command.amountKrw(),
                            command.bankCode(), accountHash, command.accountHolderName(), now))));
        } catch (DataIntegrityViolationException duplicate) {
            if (repository.findByPayoutRequestId(command.payoutRequestId()).isPresent()) {
                return Optional.empty();
            }
            throw duplicate;
        }
    }

    private MockPayoutTransferView complete(String payoutRequestId, boolean success) {
        return transactionTemplate.execute(status -> {
            MockPayoutTransfer transfer = repository.findByPayoutRequestId(payoutRequestId).orElseThrow();
            Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
            if (success) {
                transfer.succeed(TRANSACTION_PREFIX + UUID.randomUUID(), now);
            } else {
                transfer.fail(FAILURE_CODE, now);
            }
            return MockPayoutTransferView.of(repository.save(transfer));
        });
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
