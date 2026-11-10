package com.hapbang.billing.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 잔액 대사 실행 기록의 문제 지갑(불일치·실패) (#822, BD-146). 일치한 지갑과 버킷별 차이·오류 내용은 남기지 않는다(로그에만).
// 가장 최근 실행의 FAILED 지갑이 다음 회차 이월 대상이다.
@Getter
@Entity
@Table(name = "wallet_reconciliation_run_wallet", schema = "billing",
        uniqueConstraints = {
                // 한 실행에서 지갑은 한 결과만 가진다(재시도로 끝난 지갑은 마지막 결과 하나)
                @UniqueConstraint(name = "uk_wallet_reconciliation_run_wallet_run_id_wallet_id",
                        columnNames = {"run_id", "wallet_id"})
        },
        indexes = {
                @Index(name = "idx_wallet_reconciliation_run_wallet_1", columnList = "run_id, result")
        })
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WalletReconciliationRunWallet extends BillingCreatedEntity {

    @Column(name = "run_id", nullable = false, updatable = false)
    private Long runId;

    @Column(name = "wallet_id", nullable = false, updatable = false)
    private Long walletId;

    @Enumerated(EnumType.STRING)
    @Column(name = "result", nullable = false, updatable = false)
    private WalletReconciliationResult result;

    private WalletReconciliationRunWallet(Long runId, Long walletId, WalletReconciliationResult result) {
        this.runId = runId;
        this.walletId = walletId;
        this.result = result;
    }

    public static WalletReconciliationRunWallet create(Long runId, Long walletId, WalletReconciliationResult result) {
        return new WalletReconciliationRunWallet(runId, walletId, result);
    }
}
