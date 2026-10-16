package com.hapbang.billing.app;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import com.hapbang.billing.domain.AccountVerificationStatus;
import com.hapbang.billing.domain.BillingUser;
import com.hapbang.billing.domain.Exchange;
import com.hapbang.billing.domain.ExchangeAccount;
import com.hapbang.billing.domain.ExchangeStatus;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;

// 관리자용 환전 신청 결과 (#294). 계좌번호는 마스킹한 값만 담는다(원문·암호문 없음).
// applicant는 Billing 회원 복제본의 현재 역할·상태다. 복제본이 없으면(기존 회원 Backfill 전) null이며 그 신청은 승인할 수 없다.
public record AdminExchangeResult(
        Long exchangeId,
        Long userId,
        long requestedAmountHp,
        BigDecimal feeRate,
        long feeAmountHp,
        long payoutAmountKrw,
        ExchangeStatus status,
        LocalDate settlementRoundDate,
        Instant requestedAt,
        Instant canceledAt,
        Long approvedBy,
        Instant approvedAt,
        Long rejectedBy,
        Instant rejectedAt,
        String rejectionReason,
        Account account,
        Applicant applicant
) {

    static AdminExchangeResult of(Exchange exchange, ExchangeAccount account, String maskedAccountNumber,
            BillingUser applicant) {
        return new AdminExchangeResult(exchange.getId(), exchange.getUserId(), exchange.getRequestedAmountHp(),
                exchange.getFeeRate(), exchange.getFeeAmountHp(), exchange.getPayoutAmountKrw(), exchange.getStatus(),
                ExchangeSettlementRound.dateOf(exchange.getPayoutScheduledAt()), exchange.getRequestedAt(),
                exchange.getCanceledAt(), exchange.getApprovedBy(), exchange.getApprovedAt(), exchange.getRejectedBy(),
                exchange.getRejectedAt(), exchange.getRejectionReason(),
                new Account(account.getBankCode(), maskedAccountNumber, account.getAccountHolderName(),
                        account.getVerificationStatus()),
                applicant == null ? null : new Applicant(applicant.getNickname(), applicant.getRole(),
                        applicant.getStatus(), applicant.canReceiveExchangeApproval()));
    }

    public record Account(String bankCode, String accountNumberMasked, String accountHolderName,
            AccountVerificationStatus verificationStatus) {
    }

    // 신청자의 현재 회원 정보(Billing 복제본). approvable: 정상 상태의 인플루언서라 승인할 수 있는지
    public record Applicant(String nickname, Role role, UserStatus status, boolean approvable) {
    }
}
