package com.hapbang.billing.in;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import com.hapbang.billing.app.AdminExchangeResult;
import com.hapbang.billing.domain.AccountVerificationStatus;
import com.hapbang.billing.domain.ExchangeStatus;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;

import io.swagger.v3.oas.annotations.media.Schema;

// 관리자용 환전 신청 응답 (#294). 계좌번호는 끝 4자리만 보이게 마스킹한다(원문·암호문 없음). 실제 송금일은 담지 않는다.
public record AdminExchangeResponse(
        Long exchangeId,
        @Schema(description = "신청자 회원 ID")
        Long userId,
        @Schema(description = "신청 금액(수익 HP, 보류한 금액)")
        long requestedAmount,
        BigDecimal feeRate,
        @Schema(description = "수수료(HP), 신청 시 확정")
        long feeAmount,
        @Schema(description = "예상 수령액(원) = 신청 금액 − 수수료")
        long expectedPayoutAmount,
        @Schema(description = "REQUESTED(승인 대기), APPROVED(승인, 보류 유지·송금 대기), REJECTED(거절, 보류 반환), "
                + "CANCELED(회원 취소), PROCESSING(송금 결과 대기), UNKNOWN(결과 불명·운영자 확인, 보류 유지), "
                + "COMPLETED(송금 성공, 보류 확정 차감), FAILED(송금 시도 확정 실패, 보류 유지·관리자 판단 대기), "
                + "FINAL_FAILED(관리자 최종 실패 확정, 보류 반환)")
        ExchangeStatus status,
        @Schema(description = "잠정 정산 예정일(매월 10일, 토·일이면 다음 월요일, 공휴일 미반영). 실제 송금일이 아니다. 승인해도 바뀌지 않는다")
        LocalDate settlementRoundDate,
        Instant requestedAt,
        Instant canceledAt,
        @Schema(description = "승인한 관리자 회원 ID")
        Long approvedBy,
        Instant approvedAt,
        @Schema(description = "거절한 관리자 회원 ID")
        Long rejectedBy,
        Instant rejectedAt,
        String rejectionReason,
        @Schema(description = "송금 성공 시각(COMPLETED)")
        Instant completedAt,
        @Schema(description = "마지막 송금 시도의 확정 실패 시각(FAILED·FINAL_FAILED)")
        Instant failedAt,
        @Schema(description = "마지막 송금 시도의 실패 사유(예: MOCK_PAYOUT_FAILED:MOCK_TRANSFER_REJECTED)")
        String failureReason,
        @Schema(description = "최종 실패로 종료한 관리자 회원 ID")
        Long finalFailedBy,
        Instant finalFailedAt,
        String finalFailureReason,
        Account account,
        @Schema(description = "신청자의 현재 회원 정보(Billing 회원 복제본). 복제본이 없으면 null이며 그 신청은 승인할 수 없다(409)")
        Applicant applicant
) {

    static AdminExchangeResponse from(AdminExchangeResult result) {
        AdminExchangeResult.Account account = result.account();
        AdminExchangeResult.Applicant applicant = result.applicant();
        return new AdminExchangeResponse(result.exchangeId(), result.userId(), result.requestedAmountHp(),
                result.feeRate(), result.feeAmountHp(), result.payoutAmountKrw(), result.status(),
                result.settlementRoundDate(), result.requestedAt(), result.canceledAt(), result.approvedBy(),
                result.approvedAt(), result.rejectedBy(), result.rejectedAt(), result.rejectionReason(),
                result.completedAt(), result.failedAt(), result.failureReason(), result.finalFailedBy(),
                result.finalFailedAt(), result.finalFailureReason(),
                new Account(account.bankCode(), account.accountNumberMasked(), account.accountHolderName(),
                        account.verificationStatus()),
                applicant == null ? null : new Applicant(applicant.nickname(), applicant.role(), applicant.status(),
                        applicant.approvable()));
    }

    public record Account(
            String bankCode,
            @Schema(description = "마스킹한 계좌번호(끝 4자리만 표시)", example = "**********1234")
            String accountNumberMasked,
            String accountHolderName,
            AccountVerificationStatus verificationStatus
    ) {
    }

    public record Applicant(
            String nickname,
            Role role,
            UserStatus status,
            @Schema(description = "정상(ACTIVE) 상태의 인플루언서·인증된 인플루언서라 승인할 수 있는지")
            boolean approvable
    ) {
    }
}
