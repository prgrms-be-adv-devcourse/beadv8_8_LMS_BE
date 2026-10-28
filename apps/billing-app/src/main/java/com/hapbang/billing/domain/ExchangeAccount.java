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

// 환전 신청 당시 입력한 송금 계좌의 스냅샷이다(환전 1건당 1행). 계좌를 미리 등록하지 않고 신청마다 입력한다(#285 A안).
// 계좌번호는 AES-256-GCM 암호문(PaymentKeyCipher)으로만 저장하고, 응답에는 마스킹한 값만 쓴다.
// 실제 계좌 존재 확인·실명 인증은 하지 않으므로 검증 상태는 PENDING으로 두고 VERIFIED로 저장하지 않는다. 신청 후 바꾸지 않는다.
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

    // 확인하지 않은 테스트 계좌 스냅샷(검증 상태 PENDING, 검증 시각 없음)
    public static ExchangeAccount unverified(Exchange exchange, String bankCode, String accountNumberEncrypted,
            String accountHolderName) {
        return ExchangeAccount.builder()
                .exchange(exchange)
                .bankCode(bankCode)
                .accountNumberEncrypted(accountNumberEncrypted)
                .accountHolderName(accountHolderName)
                .verificationStatus(AccountVerificationStatus.PENDING)
                .build();
    }
}
