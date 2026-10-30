package com.hapbang.billing.domain;

import com.hapbang.shared.billing.domain.BillingReferenceType;
import com.hapbang.shared.billing.domain.HoldVoidOutcome;

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
import org.hibernate.annotations.Check;
import org.hibernate.annotations.Immutable;

// 보류 무효화 기록 (#479). 호출한 모듈이 참조 대상의 보류를 더는 쓰지 않는다고 알린 사실과 그때의 결과다. 참조 대상당 한 건이며,
// 이 기록이 있는 참조 대상에는 보류를 만들지 않는다(HOLD_VOIDED). 같은 무효화 재요청은 이 기록으로 처음 결과를 돌려준다.
@Entity
@Table(name = "hold_void", schema = "billing",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_hold_void_reference", columnNames = {"reference_type", "reference_id"})
        },
        indexes = {
                @Index(name = "idx_hold_void_1", columnList = "wallet_id")
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Immutable
@Check(constraints = "((outcome = 'RELEASED' AND released_hp > 0) OR (outcome <> 'RELEASED' AND released_hp = 0))")
public class HoldVoid extends BillingCreatedEntity {

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "wallet_id", nullable = false, updatable = false)
    private Long walletId;

    @Enumerated(EnumType.STRING)
    @Column(name = "reference_type", nullable = false, updatable = false)
    private BillingReferenceType referenceType;

    @Column(name = "reference_id", nullable = false, updatable = false)
    private Long referenceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false, updatable = false)
    private HoldVoidOutcome outcome;

    @Column(name = "released_hp", nullable = false, updatable = false)
    private Long releasedHp;

    private HoldVoid(Long userId, Long walletId, BillingReferenceType referenceType, Long referenceId,
            HoldVoidOutcome outcome, long releasedHp) {
        this.userId = userId;
        this.walletId = walletId;
        this.referenceType = referenceType;
        this.referenceId = referenceId;
        this.outcome = outcome;
        this.releasedHp = releasedHp;
    }

    // 무효화 결과를 기록한다. 반환한 금액은 RELEASED일 때만 0보다 크다.
    public static HoldVoid of(Long userId, Long walletId, BillingReferenceType referenceType, Long referenceId,
            HoldVoidOutcome outcome, long releasedHp) {
        if ((outcome == HoldVoidOutcome.RELEASED) != (releasedHp > 0)) {
            throw new IllegalArgumentException("반환한 금액은 RELEASED일 때만 0보다 큽니다.");
        }
        return new HoldVoid(userId, walletId, referenceType, referenceId, outcome, releasedHp);
    }
}
