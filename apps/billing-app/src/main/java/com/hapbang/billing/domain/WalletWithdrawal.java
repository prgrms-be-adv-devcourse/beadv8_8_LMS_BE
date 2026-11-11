package com.hapbang.billing.domain;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

import com.hapbang.shared.billing.domain.WithdrawalCloseOutcome;
import com.hapbang.shared.billing.domain.WithdrawalReopenOutcome;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreRemove;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 회원 탈퇴용 지갑 닫기·다시 열기 기록 (#623, docs/api/internal-withdrawals.md 4장). 회원 앱의 탈퇴 작업 ID당 한 건이다.
// - 닫기·다시 열기의 멱등 키 저장소다. 같은 탈퇴 작업의 재요청은 이 기록으로 처음 결과를 돌려준다.
// - 다시 열기 기록이 있는 탈퇴 작업의 늦은 닫기를 거절(WITHDRAWAL_ALREADY_REOPENED)하는 근거다(보류 무효화 HoldVoid와 같은 방식).
// - 지갑이 아직 없는 회원은 이 기록만 남긴다. 지갑을 만들 때 회원의 마지막 CLOSED 기록이 다시 열리지 않았으면 닫힌 지갑으로 만든다.
// PK가 회원 앱이 준 값이라 Persistable로 새 기록임을 알려 저장 전 조회 없이 INSERT한다(같은 ID 동시 저장은 PK 충돌로 드러난다).
@Entity
@Table(name = "wallet_withdrawal", schema = "billing",
        indexes = {
                @Index(name = "idx_wallet_withdrawal_1", columnList = "user_id, closed_at")
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WalletWithdrawal implements Persistable<Long> {

    @Id
    @Column(name = "withdrawal_id", nullable = false, updatable = false)
    private Long withdrawalId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "close_outcome", updatable = false)
    private WithdrawalCloseOutcome closeOutcome;

    // 막힌 이유 목록 JSON (WithdrawalBlockReasonDto 모양). BLOCKED일 때만 있다
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "block_reasons", columnDefinition = "jsonb", updatable = false)
    private String blockReasons;

    @Enumerated(EnumType.STRING)
    @Column(name = "reopen_outcome")
    private WithdrawalReopenOutcome reopenOutcome;

    @Column(name = "closed_at", updatable = false, columnDefinition = "timestamptz")
    private Instant closedAt;

    @Column(name = "reopened_at", columnDefinition = "timestamptz")
    private Instant reopenedAt;

    @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "timestamptz")
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, columnDefinition = "timestamptz")
    private Instant updatedAt;

    @Transient
    private boolean persisted;

    private WalletWithdrawal(long withdrawalId, long userId, WithdrawalCloseOutcome closeOutcome, String blockReasons,
            Instant closedAt) {
        this.withdrawalId = withdrawalId;
        this.userId = userId;
        this.closeOutcome = closeOutcome;
        this.blockReasons = blockReasons;
        this.closedAt = closedAt;
    }

    // 막힌 이유가 없어 지갑을 닫았다(지갑이 없으면 기록만 남긴다).
    public static WalletWithdrawal closed(long withdrawalId, long userId, Instant closedAt) {
        return new WalletWithdrawal(withdrawalId, userId, WithdrawalCloseOutcome.CLOSED, null,
                Objects.requireNonNull(closedAt, "닫은 시각은 필수입니다."));
    }

    // 막힌 이유가 있어 지갑을 닫지 않았다. 이유는 응답 모양 그대로의 JSON이다.
    public static WalletWithdrawal blocked(long withdrawalId, long userId, String blockReasons) {
        return new WalletWithdrawal(withdrawalId, userId, WithdrawalCloseOutcome.BLOCKED,
                Objects.requireNonNull(blockReasons, "막힌 이유는 필수입니다."), null);
    }

    // 닫기를 받기 전에 다시 열기가 왔다. 지갑은 바꾸지 않았고(NOT_CLOSED), 이후 이 탈퇴 작업의 닫기는 거절한다.
    public static WalletWithdrawal reopenedBeforeClose(long withdrawalId, long userId, Instant reopenedAt) {
        WalletWithdrawal withdrawal = new WalletWithdrawal(withdrawalId, userId, null, null, null);
        withdrawal.recordReopen(WithdrawalReopenOutcome.NOT_CLOSED, reopenedAt);
        return withdrawal;
    }

    // 다시 열기 결과를 남긴다. 한 번만 남긴다(재요청은 처음 결과를 돌려준다).
    public void recordReopen(WithdrawalReopenOutcome outcome, Instant reopenedAt) {
        if (this.reopenOutcome != null) {
            throw new IllegalStateException("이미 다시 열기를 받은 탈퇴 작업입니다. withdrawalId=" + withdrawalId);
        }
        this.reopenOutcome = Objects.requireNonNull(outcome, "다시 열기 결과는 필수입니다.");
        this.reopenedAt = Objects.requireNonNull(reopenedAt, "다시 연 시각은 필수입니다.");
    }

    public boolean isReopened() {
        return reopenOutcome != null;
    }

    public boolean isClosed() {
        return closeOutcome == WithdrawalCloseOutcome.CLOSED;
    }

    public boolean isOwnedBy(long userId) {
        return this.userId == userId;
    }

    @Override
    public Long getId() {
        return withdrawalId;
    }

    @Override
    public boolean isNew() {
        return !persisted;
    }

    @PrePersist
    void recordCreation() {
        createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        updatedAt = createdAt;
    }

    @PreUpdate
    void recordUpdate() {
        updatedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    @PostLoad
    @PostPersist
    void markPersisted() {
        persisted = true;
    }

    // 금융 기록은 물리 삭제하지 않는다
    @PreRemove
    void rejectRemoval() {
        throw new UnsupportedOperationException("금융 데이터는 물리 삭제할 수 없습니다.");
    }
}
