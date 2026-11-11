package com.hapbang.member.domain;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Set;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.hapbang.shared.jpa.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 회원 탈퇴 작업 기록 (#593). 탈퇴 한 번 시도마다 하나다. id가 지갑 닫기·다시 열기 호출의 멱등 키(withdrawalId)다.
 * 상태 흐름은 {@link WithdrawalStatus}에 있고, 허용하지 않는 전이는 막는다(배치와 요청이 겹쳐도 끝난 작업을 되살리지 않게).
 * 회원 상태는 이 기록이 아니라 {@link User}가 갖는다.
 * <p>
 * 요청과 복구(다시 누를 때 정리·배치)가 같은 작업을 동시에 바꾸지 못하게 낙관적 락을 건다
 */
@Entity
@Table(name = "withdrawal", schema = "members")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Withdrawal extends BaseEntity {

    static final int LAST_ERROR_MAX_LENGTH = 1000;
    /**
     * 진행 중인 작업이 이 시간 넘게 바뀌지 않으면 처리하던 요청이 죽은 것으로 본다. 정상 요청은 다른 앱이 모두 최악으로 느려도
     * 30초 안팎이라(출연·강의 조회 5초씩, 지갑 닫기·다시 열기 약 9초씩) 4배 여유를 둔다. 탈퇴 409 문구의 "2분"과 같은 값이다
     */
    public static final Duration STALE_AFTER = Duration.ofMinutes(2);
    private static final Set<WithdrawalStatus> ROLLBACK_FROM =
            EnumSet.of(WithdrawalStatus.CHECKING, WithdrawalStatus.WALLET_CLOSED, WithdrawalStatus.NEEDS_RECOVERY);
    private static final Set<WithdrawalStatus> RECOVERY_FROM =
            EnumSet.of(WithdrawalStatus.CHECKING, WithdrawalStatus.WALLET_CLOSED);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private WithdrawalStatus status;

    /** 막힌 이유 기록 (WithdrawalBlockReasonDto 목록 JSON). 막혀서 끝났을 때만 있다 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String blockReasons;

    /** 배치가 되돌리기를 다시 시도한 횟수 */
    @Column(nullable = false)
    private int attempts;

    @Column(length = LAST_ERROR_MAX_LENGTH)
    private String lastError;

    private LocalDateTime completedAt;

    @Version
    private Long version;

    private Withdrawal(User user) {
        this.user = user;
        this.status = WithdrawalStatus.CHECKING;
    }

    /** 탈퇴를 시작한다 (확인하는 중) */
    public static Withdrawal start(User user) {
        return new Withdrawal(user);
    }

    /** 지갑을 닫았다 */
    public void walletClosed() {
        require(status == WithdrawalStatus.CHECKING);
        this.status = WithdrawalStatus.WALLET_CLOSED;
    }

    /** 회원 저장까지 끝났다 */
    public void complete(LocalDateTime now) {
        require(status == WithdrawalStatus.WALLET_CLOSED);
        this.status = WithdrawalStatus.COMPLETED;
        this.completedAt = now;
    }

    /** 남은 돈·거래가 있어 막혔다. 지갑을 닫았다면 다시 연 뒤에 부른다 */
    public void block(String blockReasons, LocalDateTime now) {
        require(status == WithdrawalStatus.CHECKING || status == WithdrawalStatus.WALLET_CLOSED);
        this.status = WithdrawalStatus.BLOCKED;
        this.blockReasons = blockReasons;
        this.completedAt = now;
    }

    /** 시스템 실패로 되돌렸다 (닫은 지갑이 있으면 다시 연 뒤) */
    public void rollBack(String error, LocalDateTime now) {
        require(ROLLBACK_FROM.contains(status));
        this.status = WithdrawalStatus.ROLLED_BACK;
        this.lastError = truncate(error);
        this.completedAt = now;
    }

    /** 지갑을 닫았는지 모르거나 다시 열기가 실패했다. 배치가 다시 열기를 다시 부른다 */
    public void needRecovery(String error) {
        require(RECOVERY_FROM.contains(status));
        this.status = WithdrawalStatus.NEEDS_RECOVERY;
        this.lastError = truncate(error);
    }

    /** 배치의 다시 열기가 또 실패했다 */
    public void recoveryFailed(String error) {
        require(status == WithdrawalStatus.NEEDS_RECOVERY);
        this.attempts++;
        this.lastError = truncate(error);
    }

    /**
     * 복구가 필요한지: 다시 열기에 실패한 작업이거나, 진행 중인데 {@link #STALE_AFTER} 넘게 바뀌지 않은 작업(처리하던 요청이 죽음).
     * 아직 처리 중일 수 있는 진행 중 작업은 건드리지 않는다
     */
    public boolean needsRecovery(LocalDateTime now) {
        if (status == WithdrawalStatus.NEEDS_RECOVERY) {
            return true;
        }
        return RECOVERY_FROM.contains(status) && getUpdatedAt() != null
                && !getUpdatedAt().isAfter(now.minus(STALE_AFTER));
    }

    private void require(boolean allowed) {
        if (!allowed) {
            throw new IllegalStateException("탈퇴 작업 상태를 바꿀 수 없습니다. id=" + id + ", status=" + status);
        }
    }

    private static String truncate(String error) {
        if (error == null || error.length() <= LAST_ERROR_MAX_LENGTH) {
            return error;
        }
        return error.substring(0, LAST_ERROR_MAX_LENGTH);
    }
}
