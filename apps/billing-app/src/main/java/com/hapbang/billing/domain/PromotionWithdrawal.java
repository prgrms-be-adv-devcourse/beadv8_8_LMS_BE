package com.hapbang.billing.domain;

import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Check;
import org.hibernate.annotations.Immutable;

// 프로모션 지갑 회수 기록. 관리자가 회수 작업 ID(withdrawalWorkId) 하나로 예약되지 않은 프로모션 HP(가용 칸)를 거둬들인 사실과 근거·관리자다.
// 회원 탈퇴 기록(WalletWithdrawal)과 다르다. 회수 작업 ID당 한 건이라 같은 작업의 재요청은 이 기록으로 처음 결과를 돌려준다.
// 원장(PROMOTION_WITHDRAW)은 이 기록의 ID를 참조한다.
@Entity
@Table(name = "promotion_withdrawal", schema = "billing",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_promotion_withdrawal_withdrawal_work_id",
                        columnNames = {"withdrawal_work_id"})
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Immutable
@Check(constraints = "(amount_hp > 0)")
public class PromotionWithdrawal extends BillingCreatedEntity {

    @Column(name = "withdrawal_work_id", nullable = false, updatable = false, length = 200)
    private String withdrawalWorkId;

    @Column(name = "wallet_id", nullable = false, updatable = false)
    private Long walletId;

    @Column(name = "amount_hp", nullable = false, updatable = false)
    private Long amountHp;

    // 회수 근거 번호(결재 번호 등)
    @Column(name = "reference_no", nullable = false, updatable = false, length = 200)
    private String referenceNo;

    @Column(name = "memo", updatable = false, length = 500)
    private String memo;

    // 회수한 관리자의 회원 ID
    @Column(name = "withdrawn_by", nullable = false, updatable = false)
    private Long withdrawnBy;

    private PromotionWithdrawal(String withdrawalWorkId, Long walletId, long amountHp, String referenceNo, String memo,
            Long withdrawnBy) {
        this.withdrawalWorkId = withdrawalWorkId;
        this.walletId = walletId;
        this.amountHp = amountHp;
        this.referenceNo = referenceNo;
        this.memo = memo;
        this.withdrawnBy = withdrawnBy;
    }

    // 회수 기록을 만든다. 금액은 1 HP 이상이며 0 이하는 INVALID_POINT_AMOUNT로 거절한다(0 HP 원장을 만들지 않는다).
    public static PromotionWithdrawal of(String withdrawalWorkId, Long walletId, long amountHp, String referenceNo,
            String memo, Long withdrawnBy) {
        Objects.requireNonNull(withdrawalWorkId, "회수 작업 ID는 필수입니다.");
        Objects.requireNonNull(walletId, "지갑 ID는 필수입니다.");
        Objects.requireNonNull(referenceNo, "회수 근거 번호는 필수입니다.");
        Objects.requireNonNull(withdrawnBy, "회수한 관리자 ID는 필수입니다.");
        if (amountHp <= 0) {
            throw new BillingException(BillingErrorCode.INVALID_POINT_AMOUNT);
        }
        return new PromotionWithdrawal(withdrawalWorkId, walletId, amountHp, referenceNo, memo, withdrawnBy);
    }
}
