package com.hapbang.billing.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;

import com.hapbang.billing.domain.BalanceBucket;
import com.hapbang.billing.domain.Exchange;
import com.hapbang.billing.domain.ExchangeAccount;
import com.hapbang.billing.domain.LedgerTransactionType;
import com.hapbang.billing.domain.MockPayoutTransfer;
import com.hapbang.billing.domain.PointType;
import com.hapbang.billing.domain.Wallet;
import com.hapbang.billing.out.ExchangeAccountRepository;
import com.hapbang.billing.out.ExchangeRepository;
import com.hapbang.billing.out.MockPayoutTransferRepository;
import com.hapbang.billing.out.WalletRepository;
import com.hapbang.shared.billing.domain.BillingReferenceType;
import com.hapbang.testsupport.IntegrationTest;

// 회원 정보 파기(UserMaskedEvent)에 맞춘 지갑의 계좌 지우기·열린 지갑 닫기(#674)를 실제 PostgreSQL로 검증한다.
@IntegrationTest
// 다시 열기(WalletWithdrawalFacade)는 내부 호출(ROLE_INTERNAL)만 허용한다
@WithMockUser(roles = "INTERNAL")
@ExtendWith(OutputCaptureExtension.class)
class WalletMaskingIntegrationTest {

    private static final Instant MASKED_AT = Instant.parse("2026-11-04T01:30:00Z");

    @Autowired
    WalletMaskingUseCase walletMaskingUseCase;

    @Autowired
    WalletWithdrawalFacade walletWithdrawalFacade;

    @Autowired
    ExchangeProcessing exchangeProcessing;

    @Autowired
    ExchangeReviewUseCase exchangeReviewUseCase;

    @Autowired
    LedgerRecordUseCase ledgerRecordUseCase;

    @Autowired
    ClosedWalletBalanceMetrics closedWalletBalanceMetrics;

    @Autowired
    WalletRepository walletRepository;

    @Autowired
    ExchangeRepository exchangeRepository;

    @Autowired
    ExchangeAccountRepository exchangeAccountRepository;

    @Autowired
    MockPayoutTransferRepository mockPayoutTransferRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    private final AtomicInteger sequence = new AtomicInteger();

    // ---------------------------------------------------------------------------------------------
    // 계좌 지우기
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("끝난 환전의 계좌번호 암호문·예금주를 비우고, 은행 코드·검증 상태와 환전 금액·상태는 남긴다")
    void erasesFinishedExchangeAccount() {
        Long walletId = wallet(1L);
        Exchange exchange = canceledExchange(1L, walletId);
        account(exchange);

        walletMaskingUseCase.erase(1L, MASKED_AT);

        assertThat(accountOf(exchange.getId())).containsEntry("account_number_encrypted", null)
                .containsEntry("account_holder_name", null)
                .containsEntry("bank_code", "004")
                .containsEntry("verification_status", "PENDING");
        assertThat(exchangeRepository.findById(exchange.getId())).hasValueSatisfying(saved -> {
            assertThat(saved.getStatus()).isEqualTo(exchange.getStatus());
            assertThat(saved.getRequestedAmountHp()).isEqualTo(1_000L);
            assertThat(saved.getPayoutAmountKrw()).isEqualTo(exchange.getPayoutAmountKrw());
        });
    }

    @Test
    @DisplayName("송금 성공(COMPLETED)·최종 실패(FINAL_FAILED)한 환전의 계좌도 지운다")
    void erasesCompletedAndFinalFailedExchangeAccount() {
        Long walletId = wallet(1L);
        Exchange completed = requestedExchange(1L, walletId);
        account(completed);
        Exchange finalFailed = requestedExchange(1L, walletId);
        account(finalFailed);
        jdbcTemplate.update("UPDATE billing.exchange SET status = 'COMPLETED', completed_at = now() WHERE id = ?",
                completed.getId());
        jdbcTemplate.update("""
                UPDATE billing.exchange SET status = 'FINAL_FAILED', failed_at = now(), final_failed_by = 99,
                    final_failed_at = now(), final_failure_reason = '최종 실패' WHERE id = ?""", finalFailed.getId());

        walletMaskingUseCase.erase(1L, MASKED_AT);

        assertThat(accountOf(completed.getId())).containsEntry("account_number_encrypted", null);
        assertThat(accountOf(finalFailed.getId())).containsEntry("account_number_encrypted", null);
    }

    @Test
    @DisplayName("지운 계좌를 회원·관리자가 조회하면 계좌번호는 ****, 예금주는 null이고 복호화 실패 로그를 남기지 않는다")
    void erasedAccountView(CapturedOutput output) {
        Long walletId = wallet(1L);
        Exchange exchange = canceledExchange(1L, walletId);
        account(exchange);
        walletMaskingUseCase.erase(1L, MASKED_AT);

        ExchangeResult member = exchangeProcessing.get(1L, exchange.getId());
        AdminExchangeResult admin = exchangeReviewUseCase.get(exchange.getId());

        assertThat(member.account().accountNumberMasked()).isEqualTo(ExchangeAccountCipher.UNREADABLE_MASK);
        assertThat(member.account().accountHolderName()).isNull();
        assertThat(admin.account().accountNumberMasked()).isEqualTo(ExchangeAccountCipher.UNREADABLE_MASK);
        assertThat(admin.account().accountHolderName()).isNull();
        assertThat(output).doesNotContain("복호화하지 못해");
    }

    @Test
    @DisplayName("끝난 환전의 끝난 가상 송금은 예금주·계좌번호 해시를 마스킹 값으로 덮고 금액·상태·거래 번호는 남긴다")
    void masksFinishedTransfer() {
        Long walletId = wallet(1L);
        Exchange exchange = canceledExchange(1L, walletId);
        String payoutRequestId = succeededTransfer(exchange.getId());

        walletMaskingUseCase.erase(1L, MASKED_AT);

        assertThat(transferOf(payoutRequestId))
                .containsEntry("account_holder_name", WalletMaskingUseCase.MASKED_HOLDER_NAME)
                .containsEntry("account_number_hash", WalletMaskingUseCase.MASKED_ACCOUNT_HASH)
                .containsEntry("amount_krw", 900L)
                .containsEntry("status", "SUCCEEDED")
                .containsEntry("transaction_id", "tx-" + payoutRequestId);
    }

    @Test
    @DisplayName("처리 중인 환전의 계좌와 그 송금은 지우지 않고 error 로그를 남긴다")
    void keepsInProgressExchange(CapturedOutput output) {
        Long walletId = wallet(1L);
        Exchange requested = requestedExchange(1L, walletId);
        account(requested);
        String payoutRequestId = succeededTransfer(requested.getId());

        walletMaskingUseCase.erase(1L, MASKED_AT);

        assertThat(accountOf(requested.getId())).containsEntry("account_number_encrypted", "encrypted")
                .containsEntry("account_holder_name", "홍길동");
        assertThat(transferOf(payoutRequestId)).containsEntry("account_holder_name", "홍길동")
                .containsEntry("account_number_hash", "a".repeat(64));
        assertThat(output).contains("처리 중인 환전이 있어 그 환전의 계좌는 지우지 않았다", "userId=1",
                "exchangeIds=[" + requested.getId() + "]");
    }

    @Test
    @DisplayName("같은 이벤트를 두 번 받아도 결과가 같다")
    void idempotent() {
        Long walletId = wallet(1L);
        Exchange exchange = canceledExchange(1L, walletId);
        account(exchange);
        String payoutRequestId = succeededTransfer(exchange.getId());

        walletMaskingUseCase.erase(1L, MASKED_AT);
        Map<String, Object> accountAfterFirst = accountOf(exchange.getId());
        Map<String, Object> transferAfterFirst = transferOf(payoutRequestId);
        Instant closedAtAfterFirst = walletOf(walletId).getClosedAt();
        walletMaskingUseCase.erase(1L, MASKED_AT);

        assertThat(accountOf(exchange.getId())).isEqualTo(accountAfterFirst);
        assertThat(transferOf(payoutRequestId)).isEqualTo(transferAfterFirst);
        assertThat(walletOf(walletId).getClosedAt()).isEqualTo(closedAtAfterFirst);
    }

    @Test
    @DisplayName("다른 회원의 계좌·송금·지갑은 건드리지 않는다")
    void otherMemberUntouched() {
        wallet(1L);
        Long otherWalletId = wallet(2L);
        Exchange other = canceledExchange(2L, otherWalletId);
        account(other);
        String payoutRequestId = succeededTransfer(other.getId());

        walletMaskingUseCase.erase(1L, MASKED_AT);

        assertThat(accountOf(other.getId())).containsEntry("account_number_encrypted", "encrypted");
        assertThat(transferOf(payoutRequestId)).containsEntry("account_holder_name", "홍길동");
        assertThat(walletOf(otherWalletId).isClosed()).isFalse();
    }

    // ---------------------------------------------------------------------------------------------
    // 열린 지갑 닫기
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("다시 열린 채 남은 지갑은 탈퇴 작업 ID 없이 닫고, 그 탈퇴 작업의 다시 열기 재요청으로 열리지 않는다")
    void closesReopenedWallet(CapturedOutput output) {
        Long walletId = wallet(1L);
        walletWithdrawalFacade.close(100L, 1L);
        walletWithdrawalFacade.reopen(100L, 1L);  // 회원 쪽에서는 시간 제한으로 실패했지만 지갑은 늦게 처리했다

        walletMaskingUseCase.erase(1L, MASKED_AT);

        Wallet wallet = walletOf(walletId);
        assertThat(wallet.isClosed()).isTrue();
        assertThat(wallet.getClosedByWithdrawalId()).isNull();
        assertThat(output).contains("파기된 회원의 지갑이 열려 있어 닫았다", "walletId=" + walletId);

        walletWithdrawalFacade.reopen(100L, 1L);
        assertThat(walletOf(walletId).isClosed()).isTrue();
    }

    @Test
    @DisplayName("잔액이 남은 열린 지갑도 닫고, 잔액은 그대로 두어 잔액이 남은 닫힌 지갑 지표에 잡힌다")
    void closesWalletWithBalance() {
        Long walletId = wallet(1L);
        revenue(walletId, 3_000L);

        walletMaskingUseCase.erase(1L, MASKED_AT);

        Wallet wallet = walletOf(walletId);
        assertThat(wallet.isClosed()).isTrue();
        assertThat(wallet.getRevenueBalanceHp()).isEqualTo(3_000L);
        assertThat(walletRepository.findClosedIdsWithBalance()).containsExactly(walletId);
        assertThat(closedWalletBalanceMetrics.count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("이미 탈퇴로 닫힌 지갑은 탈퇴 작업 ID와 닫은 시각을 바꾸지 않는다")
    void keepsWithdrawalClosedWallet() {
        Long walletId = wallet(1L);
        walletWithdrawalFacade.close(100L, 1L);
        Instant closedAt = walletOf(walletId).getClosedAt();

        walletMaskingUseCase.erase(1L, MASKED_AT);

        assertThat(walletOf(walletId).isClosedBy(100L)).isTrue();
        assertThat(walletOf(walletId).getClosedAt()).isEqualTo(closedAt);
    }

    @Test
    @DisplayName("지갑이 없는 회원(관리자 등)은 아무것도 바꾸지 않고 끝난다")
    void noWallet() {
        walletMaskingUseCase.erase(9L, MASKED_AT);

        assertThat(walletRepository.findByUserId(9L)).isEmpty();
    }

    // ---------------------------------------------------------------------------------------------
    // 준비
    // ---------------------------------------------------------------------------------------------

    private Long wallet(Long userId) {
        return walletRepository.save(Wallet.create(userId)).getId();
    }

    private Wallet walletOf(Long walletId) {
        return walletRepository.findById(walletId).orElseThrow();
    }

    private Exchange requestedExchange(Long userId, Long walletId) {
        Instant now = Instant.now();
        return exchangeRepository.save(Exchange.requested(userId, walletId, 1_000L,
                "exchange-" + sequence.incrementAndGet(), ExchangeSettlementRound.of(now), now));
    }

    // 끝난 환전(관리자 승인 전 회원이 취소)
    private Exchange canceledExchange(Long userId, Long walletId) {
        Exchange exchange = requestedExchange(userId, walletId);
        exchange.cancel(Instant.now());
        return exchangeRepository.save(exchange);
    }

    private void account(Exchange exchange) {
        exchangeAccountRepository.save(ExchangeAccount.unverified(exchange, "004", "encrypted", "홍길동"));
    }

    private String succeededTransfer(Long exchangeId) {
        String payoutRequestId = "payout-" + sequence.incrementAndGet();
        MockPayoutTransfer transfer = MockPayoutTransfer.received(payoutRequestId, exchangeId, 900L, "004",
                "a".repeat(64), "홍길동", Instant.now());
        transfer.succeed("tx-" + payoutRequestId, Instant.now());
        mockPayoutTransferRepository.save(transfer);
        return payoutRequestId;
    }

    private void revenue(Long walletId, long amountHp) {
        int id = sequence.incrementAndGet();
        ledgerRecordUseCase.record(new LedgerOperation("PAYOUT:" + id, LedgerTransactionType.PAYOUT,
                BillingReferenceType.SETTLEMENT, (long) id, null, Instant.now(),
                List.of(LedgerEntry.of(walletId, PointType.REVENUE, BalanceBucket.AVAILABLE, amountHp))));
    }

    private Map<String, Object> accountOf(Long exchangeId) {
        return jdbcTemplate.queryForMap("""
                SELECT bank_code, account_number_encrypted, account_holder_name, verification_status
                FROM billing.exchange_account WHERE exchange_id = ?""", exchangeId);
    }

    private Map<String, Object> transferOf(String payoutRequestId) {
        return jdbcTemplate.queryForMap("""
                SELECT account_holder_name, account_number_hash, amount_krw, status, transaction_id, updated_at
                FROM billing.mock_payout_transfer WHERE payout_request_id = ?""", payoutRequestId);
    }
}
