package com.hapbang.billing.app;

import static com.hapbang.billing.app.ChangedWalletReconciliationUseCaseTest.lastSuccess;
import static com.hapbang.billing.app.ChangedWalletReconciliationUseCaseTest.wallets;
import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.hapbang.billing.domain.Wallet;
import com.hapbang.billing.domain.WalletReconciliationResult;
import com.hapbang.billing.out.PointLedgerRepository;
import com.hapbang.billing.out.WalletRepository;
import com.hapbang.testsupport.IntegrationTest;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

// 잔액 대사 실행 기록(#822, BD-146)을 실제 PostgreSQL로 확인한다: 끝날 때 남기는 행, 새로 뜬 인스턴스의 이월, 기동 시 지표 복원.
@IntegrationTest
class WalletReconciliationRunIntegrationTest {

    private static final Duration WINDOW = Duration.ofHours(72);

    @Autowired
    ChangedWalletReconciliationUseCase changedWalletReconciliationUseCase;

    @Autowired
    WalletReconciliationRunRecorder recorder;

    @Autowired
    WalletReconciliationUseCase walletReconciliationUseCase;

    @Autowired
    WalletRepository walletRepository;

    @Autowired
    PointLedgerRepository pointLedgerRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("대사가 끝나면 실행 1행과 불일치 지갑 행을 남기고, 일치한 지갑은 남기지 않는다")
    void recordsRunAndProblemWallets() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Long mismatched = mismatchedWallet(1L, now.minus(Duration.ofHours(1)));
        Long matched = walletRepository.save(Wallet.create(2L)).getId();

        ChangedWalletReconciliationUseCase.Result result = changedWalletReconciliationUseCase.reconcileChanged(now);

        Map<String, Object> run = jdbcTemplate.queryForMap("SELECT * FROM billing.wallet_reconciliation_run");
        assertThat(((Timestamp) run.get("started_at")).toInstant()).isEqualTo(now);
        assertThat(((Timestamp) run.get("since_at")).toInstant()).isEqualTo(now.minus(WINDOW));
        assertThat(((Timestamp) run.get("finished_at")).toInstant()).isAfterOrEqualTo(now);
        assertThat(run.get("matched_count")).isEqualTo(result.matched());
        assertThat(run.get("mismatched_count")).isEqualTo(1);
        assertThat(run.get("failed_count")).isEqualTo(0);
        List<Map<String, Object>> problems = jdbcTemplate.queryForList(
                "SELECT run_id, wallet_id, result FROM billing.wallet_reconciliation_run_wallet");
        assertThat(problems).containsExactly(Map.of("run_id", run.get("id"), "wallet_id", mismatched, "result", "MISMATCHED"));
        assertThat(problems).noneMatch(row -> row.get("wallet_id").equals(matched));
    }

    @Test
    @DisplayName("가장 최근 실행 기록의 FAILED 지갑은 새로 뜬 인스턴스에서도 창 밖이어도 대사한다")
    void carriesFailedWalletAcrossRestart() {
        Instant now = Instant.now();
        Long outsideWindow = mismatchedWallet(1L, now.minus(WINDOW).minus(Duration.ofDays(1)));
        Long runId = insertRun(now.minus(Duration.ofDays(1)), 0, 0, 1);
        insertProblemWallet(runId, outsideWindow, WalletReconciliationResult.FAILED);
        // 앞 pod의 메모리는 없다: 같은 빈을 새로 만든 인스턴스로 대사한다
        ChangedWalletReconciliationUseCase restarted = new ChangedWalletReconciliationUseCase(walletRepository,
                pointLedgerRepository, walletReconciliationUseCase, recorder, WINDOW);

        ChangedWalletReconciliationUseCase.Result result = restarted.reconcileChanged(now);

        assertThat(result.mismatchedWalletIds()).contains(outsideWindow);
    }

    @Test
    @DisplayName("이월은 가장 최근 실행 기록의 FAILED 지갑만이고, 그보다 오래된 실행의 FAILED 지갑은 창 밖이면 대사하지 않는다")
    void carriesOnlyLatestRunFailedWallets() {
        Instant now = Instant.now();
        Instant outsideWindow = now.minus(WINDOW).minus(Duration.ofDays(2));
        Long olderFailed = mismatchedWallet(1L, outsideWindow);
        Long latestFailed = mismatchedWallet(2L, outsideWindow);
        // 최근 실행을 먼저 넣어 ID가 더 작다: ID가 아니라 실행 시작 시각으로 가장 최근 실행을 고르는지까지 본다
        Long latestRunId = insertRun(now.minus(Duration.ofDays(1)), 0, 0, 1);
        Long olderRunId = insertRun(now.minus(Duration.ofDays(2)), 0, 0, 1);
        insertProblemWallet(olderRunId, olderFailed, WalletReconciliationResult.FAILED);
        insertProblemWallet(latestRunId, latestFailed, WalletReconciliationResult.FAILED);

        ChangedWalletReconciliationUseCase.Result result = changedWalletReconciliationUseCase.reconcileChanged(now);

        assertThat(result.mismatchedWalletIds()).contains(latestFailed).doesNotContain(olderFailed);
    }

    @Test
    @DisplayName("기동 때 가장 최근 실행의 결과별 수와, 최근 실행이 실패여도 그 앞 성공 실행의 시작 시각으로 지표를 채운다")
    void restoresMetricsFromRecords() {
        Instant success = Instant.parse("2026-11-08T19:02:45Z");
        Instant latest = Instant.parse("2026-11-09T19:02:45Z");
        insertRun(success.minus(Duration.ofDays(1)), 9, 0, 0);
        insertRun(success, 7, 1, 0);
        insertRun(latest, 4, 2, 3);
        ChangedWalletReconciliationUseCase restarted = new ChangedWalletReconciliationUseCase(walletRepository,
                pointLedgerRepository, walletReconciliationUseCase, recorder, WINDOW);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        restarted.bindTo(registry);

        new WalletReconciliationMetricsRestorer(recorder, restarted).restore();

        assertThat(wallets(registry, "matched")).isEqualTo(4);
        assertThat(wallets(registry, "mismatched")).isEqualTo(2);
        assertThat(wallets(registry, "failed")).isEqualTo(3);
        assertThat(lastSuccess(registry)).isEqualTo(success.getEpochSecond());
    }

    private Long insertRun(Instant startedAt, int matched, int mismatched, int failed) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO billing.wallet_reconciliation_run (started_at, finished_at, since_at, matched_count,
                    mismatched_count, failed_count, created_at)
                VALUES (?, ?, ?, ?, ?, ?, now()) RETURNING id""", Long.class,
                Timestamp.from(startedAt), Timestamp.from(startedAt.plusSeconds(3)),
                Timestamp.from(startedAt.minus(WINDOW)), matched, mismatched, failed);
    }

    private void insertProblemWallet(Long runId, Long walletId, WalletReconciliationResult result) {
        jdbcTemplate.update("INSERT INTO billing.wallet_reconciliation_run_wallet (run_id, wallet_id, result, created_at) "
                + "VALUES (?, ?, ?, now())", runId, walletId, result.name());
    }

    // 캐시를 원장보다 1 크게 틀고, 수정·생성 시각을 지정한 시각으로 되돌린 지갑을 만든다.
    private Long mismatchedWallet(Long userId, Instant changedAt) {
        Long walletId = walletRepository.save(Wallet.create(userId)).getId();
        jdbcTemplate.update("UPDATE billing.wallet SET charge_balance_hp = charge_balance_hp + 1, created_at = ?, "
                + "updated_at = ? WHERE id = ?", Timestamp.from(changedAt), Timestamp.from(changedAt), walletId);
        return walletId;
    }
}
