package com.hapbang.billing.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.hapbang.billing.domain.BillingErrorCode;
import com.hapbang.billing.domain.BillingException;
import com.hapbang.billing.domain.ExchangePayoutStatus;
import com.hapbang.billing.domain.ExchangeStatus;
import com.hapbang.billing.domain.Wallet;
import com.hapbang.billing.out.MockPayoutHttpClient;
import com.hapbang.billing.out.WalletRepository;
import com.hapbang.testsupport.IntegrationTest;

// 정산 실행(#305)의 대상 선정·ID 커서 페이지·실행당 상한·예외 격리·결과 집계를 실제 PostgreSQL로 검증한다.
// 송금 실행(ExchangePayoutUseCase.execute)은 스파이로 바꿔 HTTP 없이 처리 결과만 정한다(실제 송금 흐름은 ExchangeSettlementIntegrationTest).
// 처리된 환전은 실제처럼 APPROVED가 아니게 바꿔(PROCESSING) 다음 실행 대상에서 빠지게 한다. 기준 시각은 ExchangePayoutClock으로 고정한다.
@IntegrationTest
@TestPropertySource(properties = {"spring.datasource.hikari.maximum-pool-size=4",
        "spring.datasource.hikari.minimum-idle=1"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ExchangeSettlementUseCaseTest {

    private static final Instant NOW = Instant.parse("2026-11-10T01:05:00Z"); // 11-10 10:05 KST

    @Autowired
    ExchangeSettlementUseCase settlementUseCase;

    @Autowired
    WalletRepository walletRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @MockitoSpyBean
    ExchangePayoutUseCase payoutUseCase;

    @MockitoSpyBean
    MockPayoutHttpClient client;

    @MockitoSpyBean
    ExchangePayoutClock clock;

    private final List<Long> executed = Collections.synchronizedList(new ArrayList<>());
    private Long walletId;

    @BeforeEach
    void setUp() {
        executed.clear();
        doReturn(NOW).when(clock).now();
        doReturn(true).when(client).isAvailable();
        // 송금 실행 대신: 호출을 기록하고 환전을 처리 중으로 바꾼다(실제 execute처럼 APPROVED에서 빠진다)
        doAnswer(invocation -> {
            Long id = invocation.getArgument(0);
            executed.add(id);
            jdbcTemplate.update("UPDATE billing.exchange SET status = 'PROCESSING' WHERE id = ?", id);
            return result(id, ExchangeStatus.PROCESSING, ExchangePayoutStatus.PROCESSING, ExchangePayoutAction.SENT);
        }).when(payoutUseCase).execute(anyLong());
        walletId = walletRepository.save(Wallet.create(2L)).getId();
    }

    @Test
    @DisplayName("대상은 APPROVED이고 정산 예정 시각 <= 기준 시각인 환전만이다(경계 포함, 지난 회차 포함). 다른 상태·예정일 전은 제외")
    void selectsOnlyDueApproved() {
        long due = insert("APPROVED", NOW);                         // 정각
        long overdue = insert("APPROVED", NOW.minus(40, ChronoUnit.DAYS)); // 지난 회차의 미처리 건
        insert("APPROVED", NOW.plus(1, ChronoUnit.MICROS));         // 1µs 뒤 → 제외
        insert("APPROVED", NOW.plus(30, ChronoUnit.DAYS));          // 다음 회차 → 제외
        insert("REQUESTED", NOW.minusSeconds(60));
        insert("REJECTED", NOW.minusSeconds(60));
        insert("CANCELED", NOW.minusSeconds(60));
        insert("PROCESSING", NOW.minusSeconds(60));
        insert("UNKNOWN", NOW.minusSeconds(60));
        insert("COMPLETED", NOW.minusSeconds(60));
        insert("FAILED", NOW.minusSeconds(60));
        insert("FINAL_FAILED", NOW.minusSeconds(60));

        ExchangeSettlementRunResult result = settlementUseCase.run();

        assertThat(executed).containsExactly(due, overdue);
        assertThat(result).isEqualTo(new ExchangeSettlementRunResult(false, 2, 2, 0, 0, 2, 0, 0, false));
        // 정산 예정 시각을 바꾸지 않는다(지난 회차를 다음 달로 미루지 않는다)
        assertThat(scheduledAt(overdue)).isEqualTo(NOW.minus(40, ChronoUnit.DAYS));
    }

    @Test
    @DisplayName("ID 커서로 100건씩 읽어 한 실행에서 고유 환전 1,000건까지만 처리하고, 남은 50건은 다음 실행에서 처리한다")
    void cursorAndCap() {
        List<Long> ids = insertMany(1_050, NOW.minusSeconds(60));

        ExchangeSettlementRunResult first = settlementUseCase.run();

        assertThat(first.targets()).isEqualTo(1_000);
        assertThat(first.capReached()).isTrue();
        assertThat(executed).hasSize(1_000).doesNotHaveDuplicates().isSorted()
                .containsExactlyElementsOf(ids.subList(0, 1_000));

        executed.clear();
        ExchangeSettlementRunResult second = settlementUseCase.run();

        assertThat(second.targets()).isEqualTo(50);
        assertThat(second.capReached()).isFalse();
        assertThat(executed).containsExactlyElementsOf(ids.subList(1_000, 1_050));

        executed.clear();
        assertThat(settlementUseCase.run().targets()).isZero();
        assertThat(executed).isEmpty();
    }

    @Test
    @DisplayName("예외가 난 환전은 APPROVED로 남지만 같은 실행에서 다시 읽지 않고(커서 전진) 나머지를 계속 처리한다. 다음 실행에서 다시 시도한다")
    void failuresDoNotRepeatWithinRun() {
        List<Long> ids = insertMany(250, NOW.minusSeconds(60));
        Set<Long> broken = Set.of(ids.get(0), ids.get(99), ids.get(100), ids.get(249));
        doAnswer(invocation -> {
            Long id = invocation.getArgument(0);
            executed.add(id);
            if (broken.contains(id)) {
                throw id.equals(ids.get(99)) ? new IllegalStateException("예상하지 못한 오류")
                        : new BillingException(BillingErrorCode.EXCHANGE_STATE_INCONSISTENT);
            }
            jdbcTemplate.update("UPDATE billing.exchange SET status = 'PROCESSING' WHERE id = ?", id);
            return result(id, ExchangeStatus.COMPLETED, ExchangePayoutStatus.COMPLETED, ExchangePayoutAction.SENT);
        }).when(payoutUseCase).execute(anyLong());

        ExchangeSettlementRunResult first = settlementUseCase.run();

        assertThat(executed).hasSize(250).doesNotHaveDuplicates().containsExactlyElementsOf(ids);
        assertThat(first).isEqualTo(new ExchangeSettlementRunResult(false, 250, 246, 246, 0, 0, 0, 4, false));
        // 예외가 난 환전은 바꾸지 않는다(FAILED·FINAL_FAILED로 만들지 않는다)
        assertThat(broken).allSatisfy(id -> assertThat(status(id)).isEqualTo("APPROVED"));

        executed.clear();
        ExchangeSettlementRunResult second = settlementUseCase.run();

        assertThat(executed).containsExactlyInAnyOrderElementsOf(broken).doesNotHaveDuplicates();
        assertThat(second.errors()).isEqualTo(4);
    }

    @Test
    @DisplayName("결과는 송금 실행 결과의 상태대로 센다: 성공·확정 실패·결과 대기·운영자 확인, 새로 보낸 수")
    void tally() {
        long completed = insert("APPROVED", NOW);
        long failed = insert("APPROVED", NOW);
        long processing = insert("APPROVED", NOW);
        long unknown = insert("APPROVED", NOW);
        long manual = insert("APPROVED", NOW);
        doAnswer(invocation -> {
            Long id = invocation.getArgument(0);
            jdbcTemplate.update("UPDATE billing.exchange SET status = 'PROCESSING' WHERE id = ?", id);
            if (id == completed) {
                return result(id, ExchangeStatus.COMPLETED, ExchangePayoutStatus.COMPLETED, ExchangePayoutAction.SENT);
            }
            if (id == failed) {
                return result(id, ExchangeStatus.FAILED, ExchangePayoutStatus.FAILED, ExchangePayoutAction.SENT);
            }
            if (id == processing) {
                return result(id, ExchangeStatus.PROCESSING, ExchangePayoutStatus.PROCESSING,
                        ExchangePayoutAction.SENT);
            }
            if (id == unknown) {
                return result(id, ExchangeStatus.UNKNOWN, ExchangePayoutStatus.UNKNOWN, ExchangePayoutAction.SENT);
            }
            return result(id, ExchangeStatus.UNKNOWN, ExchangePayoutStatus.MANUAL_REVIEW_REQUIRED,
                    ExchangePayoutAction.NONE);
        }).when(payoutUseCase).execute(anyLong());

        assertThat(settlementUseCase.run())
                .isEqualTo(new ExchangeSettlementRunResult(false, 5, 4, 1, 1, 2, 1, 0, false));
        assertThat(manual).isPositive();
    }

    @Test
    @DisplayName("송금 Client를 쓸 수 없는 환경이면 대상을 읽거나 송금하지 않고 건너뛴다(대상마다 503을 내지 않는다)")
    void skipsWhenClientUnavailable() {
        insert("APPROVED", NOW.minusSeconds(60));
        doReturn(false).when(client).isAvailable();

        ExchangeSettlementRunResult result = settlementUseCase.run();

        assertThat(result).isEqualTo(new ExchangeSettlementRunResult(true, 0, 0, 0, 0, 0, 0, 0, false));
        verify(payoutUseCase, never()).execute(anyLong());
    }

    @Test
    @DisplayName("대상이 없으면 아무것도 하지 않는다")
    void noTargets() {
        insert("APPROVED", NOW.plusSeconds(1));

        assertThat(settlementUseCase.run()).isEqualTo(new ExchangeSettlementRunResult(false, 0, 0, 0, 0, 0, 0, 0,
                false));
        verify(payoutUseCase, never()).execute(anyLong());
    }

    // 상태별 CHECK(승인·거절·취소·완료·실패·최종 실패 기록)를 지키는 환전 행
    private long insert(String status, Instant scheduledAt) {
        boolean approved = !Set.of("REQUESTED", "REJECTED", "CANCELED").contains(status);
        return jdbcTemplate.queryForObject("""
                INSERT INTO billing.exchange (user_id, wallet_id, requested_amount_hp, fee_rate, fee_amount_hp,
                    payout_amount_krw, status, idempotency_key, payout_scheduled_at, requested_at, created_at, updated_at,
                    approved_by, approved_at, rejected_by, rejected_at, rejection_reason, canceled_at, completed_at,
                    failed_at, final_failed_by, final_failed_at, final_failure_reason)
                VALUES (2, ?, 1000, 0.1, 100, 900, ?, gen_random_uuid()::text, ?, now(), now(), now(),
                    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id""", Long.class,
                walletId, status, ts(scheduledAt),
                approved ? 900L : null, approved ? ts(NOW.minusSeconds(86_400)) : null,
                status.equals("REJECTED") ? 900L : null, status.equals("REJECTED") ? ts(NOW) : null,
                status.equals("REJECTED") ? "사유" : null,
                status.equals("CANCELED") ? ts(NOW) : null,
                status.equals("COMPLETED") ? ts(NOW) : null,
                Set.of("FAILED", "FINAL_FAILED").contains(status) ? ts(NOW) : null,
                status.equals("FINAL_FAILED") ? 900L : null,
                status.equals("FINAL_FAILED") ? ts(NOW) : null,
                status.equals("FINAL_FAILED") ? "사유" : null);
    }

    private List<Long> insertMany(int count, Instant scheduledAt) {
        return jdbcTemplate.queryForList("""
                INSERT INTO billing.exchange (user_id, wallet_id, requested_amount_hp, fee_rate, fee_amount_hp,
                    payout_amount_krw, status, idempotency_key, payout_scheduled_at, requested_at, created_at, updated_at,
                    approved_by, approved_at)
                SELECT 2, ?, 1000, 0.1, 100, 900, 'APPROVED', gen_random_uuid()::text, ?, now(), now(), now(), 900, now()
                FROM generate_series(1, ?)
                RETURNING id""", Long.class, walletId, ts(scheduledAt), count)
                .stream().sorted().toList();
    }

    private String status(Long id) {
        return jdbcTemplate.queryForObject("SELECT status FROM billing.exchange WHERE id = ?", String.class, id);
    }

    private Instant scheduledAt(Long id) {
        return jdbcTemplate.queryForObject("SELECT payout_scheduled_at FROM billing.exchange WHERE id = ?",
                java.time.OffsetDateTime.class, id).toInstant();
    }

    // TIMESTAMPTZ에 시각을 그대로 넣는다(JVM·DB 시간대와 무관하게 같은 순간)
    private static java.time.OffsetDateTime ts(Instant instant) {
        return instant.atOffset(java.time.ZoneOffset.UTC);
    }

    private static ExchangePayoutResult result(Long id, ExchangeStatus exchangeStatus, ExchangePayoutStatus payoutStatus,
            ExchangePayoutAction action) {
        return new ExchangePayoutResult(id, exchangeStatus, 1, "8f14e45f-ceea-4672-9b6f-0c4ea4c6e5d1", payoutStatus,
                null, null, action);
    }
}
