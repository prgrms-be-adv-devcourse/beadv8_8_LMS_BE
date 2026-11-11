package com.hapbang.billing.domain;

import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Check;
import org.hibernate.annotations.Immutable;

// 프로모션 지갑 충전 기록. 관리자가 충전 작업 ID(fundingWorkId) 하나로 프로모션 지갑 가용 칸에 HP를 넣은 사실과 출처·근거·관리자다.
// 충전 작업 ID당 한 건이라 같은 작업의 재요청은 이 기록으로 처음 결과를 돌려준다. 원장(PROMOTION_FUND)은 이 기록의 ID를 참조한다.
@Entity
@Table(name = "promotion_funding", schema = "billing",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_promotion_funding_funding_work_id", columnNames = {"funding_work_id"})
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Immutable
@Check(constraints = "(amount_hp > 0)")
public class PromotionFunding extends BillingCreatedEntity {

    @Column(name = "funding_work_id", nullable = false, updatable = false, length = 200)
    private String fundingWorkId;

    @Column(name = "wallet_id", nullable = false, updatable = false)
    private Long walletId;

    @Column(name = "amount_hp", nullable = false, updatable = false)
    private Long amountHp;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, updatable = false)
    private PromotionFundingSourceType sourceType;

    // 충전 근거 번호(결재 번호 등)
    @Column(name = "source_ref", nullable = false, updatable = false, length = 200)
    private String sourceRef;

    @Column(name = "memo", updatable = false, length = 500)
    private String memo;

    // 충전한 관리자의 회원 ID
    @Column(name = "funded_by", nullable = false, updatable = false)
    private Long fundedBy;

    private PromotionFunding(String fundingWorkId, Long walletId, long amountHp,
            PromotionFundingSourceType sourceType, String sourceRef, String memo, Long fundedBy) {
        this.fundingWorkId = fundingWorkId;
        this.walletId = walletId;
        this.amountHp = amountHp;
        this.sourceType = sourceType;
        this.sourceRef = sourceRef;
        this.memo = memo;
        this.fundedBy = fundedBy;
    }

    // 충전 기록을 만든다. 금액은 1 HP 이상이며 0 이하는 INVALID_POINT_AMOUNT로 거절한다(0 HP 원장을 만들지 않는다).
    public static PromotionFunding of(String fundingWorkId, Long walletId, long amountHp,
            PromotionFundingSourceType sourceType, String sourceRef, String memo, Long fundedBy) {
        Objects.requireNonNull(fundingWorkId, "충전 작업 ID는 필수입니다.");
        Objects.requireNonNull(walletId, "지갑 ID는 필수입니다.");
        Objects.requireNonNull(sourceType, "충전 출처는 필수입니다.");
        Objects.requireNonNull(sourceRef, "충전 근거 번호는 필수입니다.");
        Objects.requireNonNull(fundedBy, "충전한 관리자 ID는 필수입니다.");
        if (amountHp <= 0) {
            throw new BillingException(BillingErrorCode.INVALID_POINT_AMOUNT);
        }
        return new PromotionFunding(fundingWorkId, walletId, amountHp, sourceType, sourceRef, memo, fundedBy);
    }
}
