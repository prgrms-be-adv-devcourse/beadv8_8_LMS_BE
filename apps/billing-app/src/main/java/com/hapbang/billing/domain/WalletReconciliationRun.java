package com.hapbang.billing.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 잔액 대사(BD-139) 한 회차의 실행 기록 (#822, BD-146). 회차가 끝날 때 한 번 저장하고 지우지 않는다.
// 기동할 때 이 기록으로 대사 지표를 채우고(가장 최근 실행, failed 0인 가장 최근 실행), 문제 지갑은 WalletReconciliationRunWallet에 둔다.
@Getter
@Entity
@Table(name = "wallet_reconciliation_run", schema = "billing",
        indexes = {
                @Index(name = "idx_wallet_reconciliation_run_1", columnList = "started_at")
        })
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WalletReconciliationRun extends BillingCreatedEntity {

    /** 대사 기준 시각(배치가 넘긴 지금). 마지막 성공 시각 지표의 값이다 */
    @Column(name = "started_at", nullable = false, updatable = false, columnDefinition = "timestamptz")
    private Instant startedAt;

    @Column(name = "finished_at", nullable = false, updatable = false, columnDefinition = "timestamptz")
    private Instant finishedAt;

    /** 대상 기준 시각(이 시각 이후 바뀐 지갑을 봤다) */
    @Column(name = "since_at", nullable = false, updatable = false, columnDefinition = "timestamptz")
    private Instant sinceAt;

    @Column(name = "matched_count", nullable = false, updatable = false)
    private int matchedCount;

    @Column(name = "mismatched_count", nullable = false, updatable = false)
    private int mismatchedCount;

    @Column(name = "failed_count", nullable = false, updatable = false)
    private int failedCount;

    private WalletReconciliationRun(Instant startedAt, Instant finishedAt, Instant sinceAt, int matchedCount,
            int mismatchedCount, int failedCount) {
        this.startedAt = startedAt;
        this.finishedAt = finishedAt;
        this.sinceAt = sinceAt;
        this.matchedCount = matchedCount;
        this.mismatchedCount = mismatchedCount;
        this.failedCount = failedCount;
    }

    public static WalletReconciliationRun create(Instant startedAt, Instant finishedAt, Instant sinceAt, int matchedCount,
            int mismatchedCount, int failedCount) {
        return new WalletReconciliationRun(startedAt, finishedAt, sinceAt, matchedCount, mismatchedCount, failedCount);
    }
}
