package com.hapbang.billing.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.hapbang.billing.domain.BalanceBucket;
import com.hapbang.billing.domain.BillingErrorCode;
import com.hapbang.billing.domain.BillingException;
import com.hapbang.billing.domain.ExchangeStatus;
import com.hapbang.billing.domain.LedgerTransactionType;
import com.hapbang.billing.domain.PointChargeStatus;
import com.hapbang.billing.domain.PointType;
import com.hapbang.billing.domain.Wallet;
import com.hapbang.billing.fixture.ChargeFixture;
import com.hapbang.billing.out.WalletRepository;
import com.hapbang.shared.billing.domain.BillingReferenceType;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;
import com.hapbang.shared.member.dto.UserDto;
import com.hapbang.testsupport.IntegrationTest;

// 관리자 환전 승인·거절(#294)을 실제 PostgreSQL로 검증한다: 신청자 회원 복제본 자격, 상태 전이·멱등성, 거절 반환,
// 승인·거절·회원 취소 경쟁(지갑 → 환전 → 보류 잠금), 보류 정합성, 송금·확정 차감 없음, 원장·지갑 정합성.
// 이 클래스만 쓰는 컨텍스트라 연결 풀을 작게 두고 끝나면 닫는다(다른 테스트 컨텍스트와 함께 DB 최대 연결 수를 넘지 않게).
@IntegrationTest
@TestPropertySource(properties = {"spring.datasource.hikari.maximum-pool-size=4",
        "spring.datasource.hikari.minimum-idle=1"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ExchangeReviewIntegrationTest {

    private static final long INFLUENCER = 2L;
    private static final long ADMIN = 900L;
    private static final long OTHER_ADMIN = 901L;
    private static final String ACCOUNT = "12345678901234";
    private static final Instant T1 = Instant.parse("2026-10-01T00:00:00Z");

    @DynamicPropertySource
    static void cipherProperties(DynamicPropertyRegistry registry) {
        registry.add("hapbang.billing.payment-key.encryption-key", () -> ChargeFixture.ENCRYPTION_KEY);
        registry.add("hapbang.billing.payment-key.hmac-key", () -> ChargeFixture.HMAC_KEY);
    }

    @Autowired
    ExchangeReviewUseCase reviewUseCase;

    @Autowired
    ExchangeRequestUseCase exchangeRequestUseCase;

    @Autowired
    ExchangeProcessing exchangeProcessing;

    @Autowired
    BillingUserSyncUseCase billingUserSyncUseCase;

    @Autowired
    InternalHoldFacade internalHoldFacade;

    @Autowired
    LedgerRecordUseCase ledgerRecordUseCase;

    @Autowired
    WalletRepository walletRepository;

    @Autowired
    WalletReconciliationUseCase walletReconciliationUseCase;

    // 승인 시각(정산 회차 확정 기준, #305). 바꾸지 않은 테스트는 실제 시각을 쓴다
    @MockitoSpyBean
    ExchangePayoutClock exchangePayoutClock;

    @Autowired
    JdbcTemplate jdbcTemplate;

    private final AtomicInteger sequence = new AtomicInteger();

    // ---------------------------------------------------------------------------------------------
    // 승인
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("승인: REQUESTED → APPROVED, 관리자·시각을 남기고 보류·지갑·원장·정산 예정일은 그대로이며 송금·확정 차감이 없다")
    void approve() {
        Long walletId = influencer(Role.INFLUENCER, UserStatus.ACTIVE, 20_000L);
        Long exchangeId = request(5_000L);
        Map<String, Object> before = exchangeRow(exchangeId);
        Map<String, Object> walletBefore = walletRow(walletId);
        long ledgerBefore = count("billing.point_ledger");

        AdminExchangeResult result = reviewUseCase.approve(ADMIN, exchangeId);

        assertThat(result.status()).isEqualTo(ExchangeStatus.APPROVED);
        assertThat(result.approvedBy()).isEqualTo(ADMIN);
        assertThat(result.approvedAt()).isNotNull();
        assertThat(result.applicant().approvable()).isTrue();
        assertThat(exchangeRow(exchangeId)).containsEntry("status", "APPROVED").containsEntry("approved_by", ADMIN)
                .containsEntry("payout_scheduled_at", before.get("payout_scheduled_at"))
                .containsEntry("point_hold_id", before.get("point_hold_id"));
        assertThat(holdOf(exchangeId)).containsEntry("status", "HELD").containsEntry("held_revenue_hp", 5_000L);
        assertThat(walletRow(walletId)).isEqualTo(walletBefore);
        assertThat(count("billing.point_ledger")).isEqualTo(ledgerBefore);
        assertThat(count("billing.point_hold_detail")).isZero();
        assertThat(count("billing.exchange_payout")).isZero();
        assertThat(count("billing.mock_payout_transfer")).isZero();
        assertConsistent(walletId);
    }

    // ---------------------------------------------------------------------------------------------
    // 승인 때 정산 회차 확정 (#305)
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("정산일 00:00 KST 전에 승인하면 신청 때 잠정 회차 그대로다(마감 1µs 전)")
    void approveBeforeCutoffKeepsRound() {
        influencer(Role.INFLUENCER, UserStatus.ACTIVE, 5_000L);
        Long exchangeId = request(5_000L);
        Instant provisional = scheduledAt(exchangeId);
        doReturn(provisional.minus(1, ChronoUnit.MICROS)).when(exchangePayoutClock).now();

        AdminExchangeResult result = reviewUseCase.approve(ADMIN, exchangeId);

        assertThat(scheduledAt(exchangeId)).isEqualTo(provisional);
        assertThat(result.settlementRoundDate()).isEqualTo(ExchangeSettlementRound.dateOf(provisional));
        assertThat(result.approvedAt()).isEqualTo(provisional.minus(1, ChronoUnit.MICROS));
    }

    @ParameterizedTest
    @CsvSource({"0", "3600", "432000", "2678400"})
    @DisplayName("정산일 00:00 KST 정각·당일·이후에 승인하면 승인 뒤 첫 회차로 옮긴다(보류·원장은 그대로, 승인 응답에도 반영)")
    void approveOnOrAfterCutoffMovesToNextRound(long secondsAfterCutoff) {
        Long walletId = influencer(Role.INFLUENCER, UserStatus.ACTIVE, 5_000L);
        Long exchangeId = request(5_000L);
        Instant provisional = scheduledAt(exchangeId);
        Instant approvedAt = provisional.plusSeconds(secondsAfterCutoff);
        doReturn(approvedAt).when(exchangePayoutClock).now();
        Map<String, Object> walletBefore = walletRow(walletId);
        long ledgerBefore = count("billing.point_ledger");

        AdminExchangeResult result = reviewUseCase.approve(ADMIN, exchangeId);

        Instant expected = ExchangeSettlementRound.firstRoundAfter(approvedAt);
        assertThat(scheduledAt(exchangeId)).isEqualTo(expected).isAfter(approvedAt).isAfter(provisional);
        assertThat(result.settlementRoundDate()).isEqualTo(ExchangeSettlementRound.dateOf(expected));
        assertThat(exchangeRow(exchangeId)).containsEntry("status", "APPROVED");
        assertThat(holdOf(exchangeId)).containsEntry("status", "HELD").containsEntry("held_revenue_hp", 5_000L);
        assertThat(walletRow(walletId)).isEqualTo(walletBefore);
        assertThat(count("billing.point_ledger")).isEqualTo(ledgerBefore);
    }

    @Test
    @DisplayName("승인에 실패하면(회원 복제본 없음) 회차도 바꾸지 않는다")
    void failedApprovalKeepsRound() {
        influencerWithoutReplica(5_000L);
        Long exchangeId = request(5_000L);
        Instant provisional = scheduledAt(exchangeId);
        doReturn(provisional.plusSeconds(86_400)).when(exchangePayoutClock).now();

        assertBillingError(() -> reviewUseCase.approve(ADMIN, exchangeId),
                BillingErrorCode.EXCHANGE_APPLICANT_NOT_SYNCED);

        assertThat(scheduledAt(exchangeId)).isEqualTo(provisional);
        assertThat(exchangeRow(exchangeId)).containsEntry("status", "REQUESTED");
    }

    @Test
    @DisplayName("인증된 인플루언서도 승인할 수 있다")
    void approveVerifiedInfluencer() {
        influencer(Role.VERIFIED_INFLUENCER, UserStatus.ACTIVE, 5_000L);

        assertThat(reviewUseCase.approve(ADMIN, request(5_000L)).status()).isEqualTo(ExchangeStatus.APPROVED);
    }

    @Test
    @DisplayName("같은 관리자의 재승인은 처음 결과(관리자·시각 그대로), 다른 관리자의 승인은 409다")
    void approveIdempotency() {
        influencer(Role.INFLUENCER, UserStatus.ACTIVE, 5_000L);
        Long exchangeId = request(5_000L);
        AdminExchangeResult first = reviewUseCase.approve(ADMIN, exchangeId);

        AdminExchangeResult again = reviewUseCase.approve(ADMIN, exchangeId);

        assertThat(again.approvedBy()).isEqualTo(ADMIN);
        assertThat(again.approvedAt()).isEqualTo(first.approvedAt());
        assertBillingError(() -> reviewUseCase.approve(OTHER_ADMIN, exchangeId),
                BillingErrorCode.EXCHANGE_ALREADY_REVIEWED);
        assertThat(exchangeRow(exchangeId)).containsEntry("approved_by", ADMIN);
    }

    @Test
    @DisplayName("Billing 회원 복제본이 없는 신청자(기존 회원 Backfill 전)는 409 EXCHANGE_APPLICANT_NOT_SYNCED이고 아무것도 바뀌지 않는다")
    void approveWithoutReplica() {
        Long walletId = influencerWithoutReplica(5_000L);
        Long exchangeId = request(5_000L);
        Map<String, Object> before = exchangeRow(exchangeId);

        assertBillingError(() -> reviewUseCase.approve(ADMIN, exchangeId),
                BillingErrorCode.EXCHANGE_APPLICANT_NOT_SYNCED);

        assertThat(exchangeRow(exchangeId)).isEqualTo(before);
        assertThat(holdOf(exchangeId)).containsEntry("status", "HELD").containsEntry("held_revenue_hp", 5_000L);
        assertThat(reviewUseCase.get(exchangeId).applicant()).isNull();
        assertThat(count("billing.mock_payout_transfer")).isZero();
        assertConsistent(walletId);
    }

    @ParameterizedTest
    @CsvSource({"USER, ACTIVE", "INFLUENCER, SUSPENDED", "VERIFIED_INFLUENCER, WITHDRAWN", "ADMIN, ACTIVE",
            "INFLUENCER, WITHDRAWN"})
    @DisplayName("신청자가 정상 상태의 인플루언서·인증된 인플루언서가 아니면 409 EXCHANGE_APPLICANT_NOT_ELIGIBLE이고 아무것도 바뀌지 않는다")
    void approveNotEligible(Role role, UserStatus status) {
        influencer(Role.INFLUENCER, UserStatus.ACTIVE, 5_000L);
        Long exchangeId = request(5_000L);
        sync(role, status, 5L);
        Map<String, Object> before = exchangeRow(exchangeId);

        assertBillingError(() -> reviewUseCase.approve(ADMIN, exchangeId),
                BillingErrorCode.EXCHANGE_APPLICANT_NOT_ELIGIBLE);

        assertThat(exchangeRow(exchangeId)).isEqualTo(before);
        assertThat(holdOf(exchangeId)).containsEntry("status", "HELD");
        assertThat(reviewUseCase.get(exchangeId).applicant().approvable()).isFalse();
    }

    // ---------------------------------------------------------------------------------------------
    // 거절
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("거절: REQUESTED → REJECTED, 사유는 앞뒤 공백을 빼서 저장하고 보류한 수익 HP를 같은 트랜잭션에서 전부 반환한다")
    void reject() {
        Long walletId = influencer(Role.INFLUENCER, UserStatus.ACTIVE, 10_000L);
        Long exchangeId = request(4_000L);

        AdminExchangeResult result = reviewUseCase.reject(ADMIN, exchangeId, "  계좌 정보 불일치  ");

        assertThat(result.status()).isEqualTo(ExchangeStatus.REJECTED);
        assertThat(result.rejectedBy()).isEqualTo(ADMIN);
        assertThat(result.rejectedAt()).isNotNull();
        assertThat(result.rejectionReason()).isEqualTo("계좌 정보 불일치");
        assertThat(result.approvedBy()).isNull();
        assertThat(holdOf(exchangeId)).containsEntry("status", "RELEASED").containsEntry("held_revenue_hp", 0L);
        assertWallet(walletId, 10_000L, 0L);
        assertThat(ledgerOf(exchangeId)).containsExactly("REVENUE AVAILABLE -4000 HOLD", "REVENUE HELD 4000 HOLD",
                "REVENUE HELD -4000 RELEASE", "REVENUE AVAILABLE 4000 RELEASE");
        assertThat(releaseDetailKeys(exchangeId)).singleElement().asString().endsWith(":EXCHANGE-REJECT-" + exchangeId);
        assertConsistent(walletId);
    }

    @Test
    @DisplayName("같은 관리자·같은 사유의 재거절은 처음 결과이고 다시 반환하지 않는다. 다른 사유·다른 관리자는 409다")
    void rejectIdempotency() {
        Long walletId = influencer(Role.INFLUENCER, UserStatus.ACTIVE, 10_000L);
        Long exchangeId = request(4_000L);
        AdminExchangeResult first = reviewUseCase.reject(ADMIN, exchangeId, "사유");

        AdminExchangeResult again = reviewUseCase.reject(ADMIN, exchangeId, "  사유 ");

        assertThat(again.rejectedAt()).isEqualTo(first.rejectedAt());
        assertBillingError(() -> reviewUseCase.reject(ADMIN, exchangeId, "다른 사유"),
                BillingErrorCode.EXCHANGE_ALREADY_REVIEWED);
        assertBillingError(() -> reviewUseCase.reject(OTHER_ADMIN, exchangeId, "사유"),
                BillingErrorCode.EXCHANGE_ALREADY_REVIEWED);
        assertThat(releaseDetailKeys(exchangeId)).hasSize(1);
        assertThat(ledgerOf(exchangeId)).hasSize(4);
        assertWallet(walletId, 10_000L, 0L);
    }

    @Test
    @DisplayName("거절은 신청자의 회원 상태와 관계없이 된다(복제본 없음·정지)")
    void rejectRegardlessOfApplicant() {
        Long walletId = influencerWithoutReplica(10_000L);
        Long noReplica = request(3_000L);
        assertThat(reviewUseCase.reject(ADMIN, noReplica, "사유").status()).isEqualTo(ExchangeStatus.REJECTED);

        sync(Role.INFLUENCER, UserStatus.SUSPENDED, 3L);
        Long suspended = request(3_000L);
        assertThat(reviewUseCase.reject(ADMIN, suspended, "사유").status()).isEqualTo(ExchangeStatus.REJECTED);
        assertWallet(walletId, 10_000L, 0L);
    }

    @ParameterizedTest
    @CsvSource(value = {"''", "'   '"})
    @DisplayName("거절 사유가 비었거나 500자를 넘으면 400 EXCHANGE_REJECTION_REASON_INVALID이고 아무것도 바뀌지 않는다")
    void rejectReasonInvalid(String reason) {
        influencer(Role.INFLUENCER, UserStatus.ACTIVE, 5_000L);
        Long exchangeId = request(5_000L);

        assertBillingError(() -> reviewUseCase.reject(ADMIN, exchangeId, reason),
                BillingErrorCode.EXCHANGE_REJECTION_REASON_INVALID);
        assertBillingError(() -> reviewUseCase.reject(ADMIN, exchangeId, "가".repeat(501)),
                BillingErrorCode.EXCHANGE_REJECTION_REASON_INVALID);

        assertThat(exchangeRow(exchangeId)).containsEntry("status", "REQUESTED");
        assertThat(holdOf(exchangeId)).containsEntry("status", "HELD");
        assertThat(reviewUseCase.reject(ADMIN, exchangeId, " " + "가".repeat(500) + " ").rejectionReason())
                .hasSize(500);
    }

    // ---------------------------------------------------------------------------------------------
    // 상태 전이
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("승인 후 거절·거절 후 승인·취소된 신청의 승인·거절은 409, 승인된 신청은 회원이 취소할 수 없다")
    void crossTransitions() {
        Long walletId = influencer(Role.INFLUENCER, UserStatus.ACTIVE, 30_000L);
        Long approved = request(1_000L);
        reviewUseCase.approve(ADMIN, approved);
        Long rejected = request(1_000L);
        reviewUseCase.reject(ADMIN, rejected, "사유");
        Long canceled = request(1_000L);
        exchangeProcessing.cancel(INFLUENCER, canceled);

        assertBillingError(() -> reviewUseCase.reject(ADMIN, approved, "사유"), BillingErrorCode.EXCHANGE_ALREADY_REVIEWED);
        assertBillingError(() -> exchangeProcessing.cancel(INFLUENCER, approved),
                BillingErrorCode.EXCHANGE_NOT_CANCELABLE);
        assertBillingError(() -> reviewUseCase.approve(ADMIN, rejected), BillingErrorCode.EXCHANGE_ALREADY_REVIEWED);
        assertBillingError(() -> exchangeProcessing.cancel(INFLUENCER, rejected),
                BillingErrorCode.EXCHANGE_NOT_CANCELABLE);
        assertBillingError(() -> reviewUseCase.approve(ADMIN, canceled), BillingErrorCode.EXCHANGE_ALREADY_REVIEWED);
        assertBillingError(() -> reviewUseCase.reject(ADMIN, canceled, "사유"), BillingErrorCode.EXCHANGE_ALREADY_REVIEWED);
        assertBillingError(() -> reviewUseCase.approve(ADMIN, 999_999L), BillingErrorCode.EXCHANGE_NOT_FOUND);

        assertThat(exchangeRow(approved)).containsEntry("status", "APPROVED");
        assertThat(exchangeRow(rejected)).containsEntry("status", "REJECTED");
        assertThat(exchangeRow(canceled)).containsEntry("status", "CANCELED");
        // 승인 1,000은 보류 유지, 거절·취소 2,000은 반환
        assertWallet(walletId, 29_000L, 1_000L);
        assertConsistent(walletId);
    }

    // ---------------------------------------------------------------------------------------------
    // 보류 정합성
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("보류가 다른 환전의 것이면 승인·거절 모두 중단하고(500) 어떤 보류도 반환하지 않는다")
    void holdOfAnotherExchange() {
        Long walletId = influencer(Role.INFLUENCER, UserStatus.ACTIVE, 10_000L);
        Long target = request(3_000L);
        Long other = request(3_000L);
        jdbcTemplate.update("UPDATE billing.exchange SET point_hold_id = (SELECT point_hold_id FROM billing.exchange "
                + "WHERE id = ?) WHERE id = ?", other, target);

        assertBillingError(() -> reviewUseCase.approve(ADMIN, target), BillingErrorCode.EXCHANGE_STATE_INCONSISTENT);
        assertBillingError(() -> reviewUseCase.reject(ADMIN, target, "사유"),
                BillingErrorCode.EXCHANGE_STATE_INCONSISTENT);

        assertThat(exchangeRow(target)).containsEntry("status", "REQUESTED");
        assertThat(count("billing.point_hold_detail")).isZero();
        assertWallet(walletId, 4_000L, 6_000L);
    }

    @Test
    @DisplayName("남은 수익 HP 보류가 신청 금액과 다르면(일부 반환됨) 승인·거절 모두 중단한다")
    void partiallyReleasedHold() {
        Long walletId = influencer(Role.INFLUENCER, UserStatus.ACTIVE, 10_000L);
        Long exchangeId = request(3_000L);
        internalHoldFacade.release(InternalReleaseCommand.partial(INFLUENCER, BillingReferenceType.EXCHANGE, exchangeId,
                "TEST-PARTIAL", 1_000L));

        assertBillingError(() -> reviewUseCase.approve(ADMIN, exchangeId), BillingErrorCode.EXCHANGE_STATE_INCONSISTENT);
        assertBillingError(() -> reviewUseCase.reject(ADMIN, exchangeId, "사유"),
                BillingErrorCode.EXCHANGE_STATE_INCONSISTENT);

        assertThat(exchangeRow(exchangeId)).containsEntry("status", "REQUESTED");
        assertThat(releaseDetailKeys(exchangeId)).hasSize(1);
        assertWallet(walletId, 8_000L, 2_000L);
    }

    // ---------------------------------------------------------------------------------------------
    // 동시성
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("승인·거절·회원 취소가 동시에 오면 하나만 상태를 바꾸고, 반환은 거절·취소가 이겼을 때만 한 번이다")
    void approveRejectCancelRace() throws Exception {
        Long walletId = influencer(Role.INFLUENCER, UserStatus.ACTIVE, 50_000L);
        for (int round = 0; round < 5; round++) {
            Long exchangeId = request(2_000L);

            List<Object> outcomes = runConcurrently(List.of(
                    () -> reviewUseCase.approve(ADMIN, exchangeId).status(),
                    () -> reviewUseCase.reject(OTHER_ADMIN, exchangeId, "사유").status(),
                    () -> exchangeProcessing.cancel(INFLUENCER, exchangeId).status()));

            assertThat(outcomes).filteredOn(ExchangeStatus.class::isInstance).hasSize(1);
            ExchangeStatus winner = (ExchangeStatus) outcomes.stream().filter(ExchangeStatus.class::isInstance)
                    .findFirst().orElseThrow();
            assertThat(exchangeRow(exchangeId)).containsEntry("status", winner.name());
            assertThat(releaseDetailKeys(exchangeId)).hasSize(winner == ExchangeStatus.APPROVED ? 0 : 1);
            assertThat(outcomes).filteredOn(BillingErrorCode.class::isInstance)
                    .allMatch(code -> code == BillingErrorCode.EXCHANGE_ALREADY_REVIEWED
                            || code == BillingErrorCode.EXCHANGE_NOT_CANCELABLE);
        }
        assertConsistent(walletId);
    }

    @Test
    @DisplayName("서로 다른 관리자가 동시에 승인하면 한 명만 성공하고, 같은 관리자가 동시에 승인하면 모두 같은 결과다")
    void concurrentApprovals() throws Exception {
        influencer(Role.INFLUENCER, UserStatus.ACTIVE, 10_000L);
        Long first = request(1_000L);
        List<Callable<Object>> differentAdmins = new ArrayList<>();
        for (long admin = 900; admin < 905; admin++) {
            long adminId = admin;
            differentAdmins.add(() -> reviewUseCase.approve(adminId, first).approvedBy());
        }
        List<Object> outcomes = runConcurrently(differentAdmins);
        assertThat(outcomes).filteredOn(Long.class::isInstance).hasSize(1);
        assertThat(outcomes).filteredOn(BillingErrorCode.class::isInstance)
                .containsOnly(BillingErrorCode.EXCHANGE_ALREADY_REVIEWED).hasSize(4);
        assertThat(exchangeRow(first).get("approved_by")).isEqualTo(outcomes.stream()
                .filter(Long.class::isInstance).findFirst().orElseThrow());

        Long second = request(1_000L);
        List<Callable<Object>> sameAdmin = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            sameAdmin.add(() -> reviewUseCase.approve(ADMIN, second).approvedAt());
        }
        List<Object> same = runConcurrently(sameAdmin);
        assertThat(same).allMatch(Instant.class::isInstance).containsOnly(same.getFirst());
    }

    @Test
    @DisplayName("같은 관리자·같은 사유의 거절이 동시에 와도 반환은 한 번이다")
    void concurrentSameRejection() throws Exception {
        Long walletId = influencer(Role.INFLUENCER, UserStatus.ACTIVE, 10_000L);
        Long exchangeId = request(4_000L);
        List<Callable<Object>> rejects = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            rejects.add(() -> reviewUseCase.reject(ADMIN, exchangeId, "사유").status());
        }

        assertThat(runConcurrently(rejects)).containsOnly(ExchangeStatus.REJECTED);

        assertThat(releaseDetailKeys(exchangeId)).hasSize(1);
        assertWallet(walletId, 10_000L, 0L);
        assertConsistent(walletId);
    }

    // ---------------------------------------------------------------------------------------------
    // 조회·충전 HP 영향
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("관리자 목록: 승인 대기는 오래된 순, 처리된 상태는 최신순. 계좌번호는 마스킹하고 신청자 현재 정보를 함께 준다")
    void listAndGet() {
        influencer(Role.INFLUENCER, UserStatus.ACTIVE, 30_000L);
        Long first = request(1_000L);
        Long second = request(1_000L);
        Long third = request(1_000L);
        reviewUseCase.approve(ADMIN, first);
        reviewUseCase.approve(ADMIN, third);

        Page<AdminExchangeResult> requested = reviewUseCase.list(ExchangeStatus.REQUESTED, PageRequest.of(0, 20));
        Page<AdminExchangeResult> approved = reviewUseCase.list(ExchangeStatus.APPROVED, PageRequest.of(0, 20));

        assertThat(requested.getContent()).extracting(AdminExchangeResult::exchangeId).containsExactly(second);
        assertThat(approved.getContent()).extracting(AdminExchangeResult::exchangeId).containsExactly(third, first);
        AdminExchangeResult detail = reviewUseCase.get(second);
        assertThat(detail.userId()).isEqualTo(INFLUENCER);
        assertThat(detail.account().accountNumberMasked()).isEqualTo("**********1234");
        assertThat(detail.applicant().role()).isEqualTo(Role.INFLUENCER);
        assertThat(detail.toString()).doesNotContain(ACCOUNT).doesNotContain("v1:");
        assertBillingError(() -> reviewUseCase.get(999_999L), BillingErrorCode.EXCHANGE_NOT_FOUND);
    }

    @Test
    @DisplayName("승인·거절은 충전 HP·충전 원금에 영향을 주지 않는다")
    void chargeHpUnaffected() {
        Long walletId = influencer(Role.INFLUENCER, UserStatus.ACTIVE, 10_000L);
        charge(walletId, 7_000L);
        reviewUseCase.approve(ADMIN, request(2_000L));
        reviewUseCase.reject(ADMIN, request(3_000L), "사유");

        assertThat(walletRepository.findById(walletId).orElseThrow())
                .extracting(Wallet::getChargeBalanceHp, Wallet::getHeldChargeHp).containsExactly(7_000L, 0L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT sum(remaining_principal_hp) FROM billing.point_charge WHERE wallet_id = ?", Long.class, walletId))
                .isEqualTo(7_000L);
        assertWallet(walletId, 8_000L, 2_000L);
        assertConsistent(walletId);
    }

    // ---------------------------------------------------------------------------------------------
    // 도우미
    // ---------------------------------------------------------------------------------------------

    // 신청자 지갑 + 수익 HP + 회원 복제본(정상 상태, 회원 버전 0)
    private Long influencer(Role role, UserStatus status, long revenueHp) {
        Long walletId = influencerWithoutReplica(revenueHp);
        sync(role, status, 0L);
        return walletId;
    }

    private Long influencerWithoutReplica(long revenueHp) {
        Long walletId = walletRepository.save(Wallet.create(INFLUENCER)).getId();
        ledgerRecordUseCase.record(new LedgerOperation("SEED:" + sequence.incrementAndGet(),
                LedgerTransactionType.PAYOUT, BillingReferenceType.SETTLEMENT, (long) sequence.get(), null, T1,
                List.of(LedgerEntry.of(walletId, PointType.REVENUE, BalanceBucket.AVAILABLE, revenueHp))));
        return walletId;
    }

    private void sync(Role role, UserStatus status, long version) {
        billingUserSyncUseCase.apply(new UserDto(INFLUENCER, "신청자", role, status, version));
    }

    private Long request(long amountHp) {
        return exchangeRequestUseCase.create(INFLUENCER, "key-" + sequence.incrementAndGet(),
                new ExchangeCommand(amountHp, "004", ACCOUNT, "홍길동"));
    }

    private void charge(Long walletId, long amountHp) {
        Long chargeId = jdbcTemplate.queryForObject("""
                INSERT INTO billing.point_charge (wallet_id, amount_hp, payment_amount_krw, remaining_principal_hp,
                    pg_provider, pg_order_id, status, requested_at, completed_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'TOSS_PAYMENTS', ?, ?, now(), now(), now(), now())
                RETURNING id""", Long.class,
                walletId, amountHp, amountHp, amountHp, "order-" + sequence.incrementAndGet(),
                PointChargeStatus.COMPLETED.name());
        ledgerRecordUseCase.record(new LedgerOperation("CHARGE:" + chargeId, LedgerTransactionType.CHARGE,
                BillingReferenceType.POINT_CHARGE, chargeId, null, T1,
                List.of(LedgerEntry.of(walletId, PointType.CHARGE, BalanceBucket.AVAILABLE, amountHp))));
    }

    private void assertWallet(Long walletId, long revenue, long heldRevenue) {
        assertThat(walletRepository.findById(walletId).orElseThrow())
                .extracting(Wallet::getRevenueBalanceHp, Wallet::getHeldRevenueHp).containsExactly(revenue, heldRevenue);
        assertThat(walletReconciliationUseCase.reconcile(walletId).matched()).isTrue();
    }

    // 환전별: 수수료 + 수령액 = 신청 금액, APPROVED·REQUESTED는 전액 보류 중·반환 없음, REJECTED·CANCELED는 전액 반환, 환전 원장 합 0
    private void assertConsistent(Long walletId) {
        for (Map<String, Object> exchange : jdbcTemplate.queryForList("SELECT * FROM billing.exchange")) {
            Long id = (Long) exchange.get("id");
            long requested = (Long) exchange.get("requested_amount_hp");
            Map<String, Object> hold = holdOf(id);
            long released = jdbcTemplate.queryForObject("""
                    SELECT coalesce(sum(revenue_amount_hp), 0) FROM billing.point_hold_detail
                    WHERE point_hold_id = ? AND action_type = 'RELEASE'""", Long.class, hold.get("id"));
            switch ((String) exchange.get("status")) {
                case "REQUESTED", "APPROVED" -> {
                    assertThat(hold).containsEntry("held_revenue_hp", requested).containsEntry("status", "HELD");
                    assertThat(released).isZero();
                }
                case "REJECTED", "CANCELED" -> {
                    assertThat(hold).containsEntry("held_revenue_hp", 0L).containsEntry("status", "RELEASED");
                    assertThat(released).isEqualTo(requested);
                }
                default -> {
                }
            }
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT coalesce(sum(amount_hp), 0) FROM billing.point_ledger
                    WHERE reference_type = 'EXCHANGE' AND reference_id = ?""", Long.class, id)).isZero();
        }
        assertThat(walletReconciliationUseCase.reconcile(walletId).matched()).isTrue();
    }

    private Instant scheduledAt(Long exchangeId) {
        return jdbcTemplate.queryForObject("SELECT payout_scheduled_at FROM billing.exchange WHERE id = ?",
                java.time.OffsetDateTime.class, exchangeId).toInstant();
    }

    private Map<String, Object> exchangeRow(Long exchangeId) {
        return jdbcTemplate.queryForMap("SELECT * FROM billing.exchange WHERE id = ?", exchangeId);
    }

    private Map<String, Object> walletRow(Long walletId) {
        return jdbcTemplate.queryForMap("SELECT * FROM billing.wallet WHERE id = ?", walletId);
    }

    private Map<String, Object> holdOf(Long exchangeId) {
        return jdbcTemplate.queryForMap(
                "SELECT * FROM billing.point_hold WHERE reference_type = 'EXCHANGE' AND reference_id = ?", exchangeId);
    }

    private List<String> releaseDetailKeys(Long exchangeId) {
        return jdbcTemplate.queryForList("""
                SELECT d.idempotency_key FROM billing.point_hold_detail d
                JOIN billing.point_hold h ON h.id = d.point_hold_id
                WHERE h.reference_type = 'EXCHANGE' AND h.reference_id = ? AND d.action_type = 'RELEASE'""",
                String.class, exchangeId);
    }

    private List<String> ledgerOf(Long exchangeId) {
        return jdbcTemplate.queryForList("""
                SELECT point_type || ' ' || balance_bucket || ' ' || amount_hp || ' ' || transaction_type
                FROM billing.point_ledger WHERE reference_type = 'EXCHANGE' AND reference_id = ? ORDER BY id""",
                String.class, exchangeId);
    }

    private long count(String table) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    // 작업을 동시에 시작해 결과(성공 값 또는 BillingErrorCode)를 모은다.
    private static List<Object> runConcurrently(List<Callable<Object>> tasks) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (Callable<Object> task : tasks) {
                futures.add(executor.submit(() -> {
                    start.await();
                    try {
                        return task.call();
                    } catch (BillingException exception) {
                        return exception.getErrorCode();
                    }
                }));
            }
            start.countDown();
            List<Object> outcomes = new ArrayList<>();
            for (Future<Object> future : futures) {
                outcomes.add(future.get(30, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            executor.shutdownNow();
        }
    }

    private static void assertBillingError(Runnable action, BillingErrorCode code) {
        assertThatThrownBy(action::run).isInstanceOf(BillingException.class)
                .extracting(exception -> ((BillingException) exception).getErrorCode()).isEqualTo(code);
    }
}
