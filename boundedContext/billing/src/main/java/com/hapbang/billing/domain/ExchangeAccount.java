package com.hapbang.billing.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 환전별 선택적 계좌 정보를 위한 확장용 모델이며, 현재 MVP에서는 실제 계좌 입력·암호화·검증을 수행하지 않는다.
@Entity
@Table(name = "exchange_account", schema = "billing",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_exchange_account_exchange_id", columnNames = {"exchange_id"})
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ExchangeAccount extends BillingCreatedEntity {

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "exchange_id", nullable = false, unique = true)
    private Exchange exchange;

    @Column(name = "bank_code", nullable = true)
    private String bankCode;

    @Column(name = "account_number_encrypted", nullable = true)
    private String accountNumberEncrypted;

    @Column(name = "account_holder_name", nullable = true)
    private String accountHolderName;

    @Enumerated(EnumType.STRING)
    @Column(name = "verification_status", nullable = false)
    private AccountVerificationStatus verificationStatus;

    @Column(name = "verified_at", nullable = true, columnDefinition = "timestamptz")
    private Instant verifiedAt;

    // 빌더로 전달받은 환전·선택적 계좌 정보·검증 상태로 계좌 객체를 초기화한다.
    @Builder
    private ExchangeAccount(
            Exchange exchange,
            String bankCode,
            String accountNumberEncrypted,
            String accountHolderName,
            AccountVerificationStatus verificationStatus,
            Instant verifiedAt
    ) {
        this.exchange = exchange;
        this.bankCode = bankCode;
        this.accountNumberEncrypted = accountNumberEncrypted;
        this.accountHolderName = accountHolderName;
        this.verificationStatus = verificationStatus;
        this.verifiedAt = verifiedAt;
    }
}
