package com.hapbang.billing.in;

import java.time.Instant;

import com.hapbang.billing.app.AdminExchangePayoutResult;
import com.hapbang.billing.domain.ExchangePayoutStatus;
import com.hapbang.billing.domain.ExchangeStatus;

import io.swagger.v3.oas.annotations.media.Schema;

// 관리자용 환전 송금 시도 응답 (#304). 계좌번호는 담지 않는다.
public record AdminExchangePayoutResponse(
        Long exchangeId,
        @Schema(description = "환전 상태(처리 후)")
        ExchangeStatus exchangeStatus,
        Long payoutId,
        @Schema(description = "환전 안의 송금 시도 번호(1부터). 관리자 재송금마다 1씩 늘어난다")
        int attemptNo,
        @Schema(description = "지급 요청 ID(UUID, Mock 은행 Idempotency-Key). 결과 재확인은 이 ID로 한다")
        String payoutRequestId,
        @Schema(description = "PROCESSING(결과 대기), UNKNOWN(결과 불명, 같은 ID로 자동 확인), COMPLETED(성공), FAILED(확정 실패), "
                + "MANUAL_REVIEW_REQUIRED(운영자 확인: 은행 409·4xx 거절, 성공 응답 불일치, 최초 송금 후 24시간 동안 결과 미확정)")
        ExchangePayoutStatus status,
        @Schema(description = "송금 금액(원) = 환전 예상 수령액")
        long amountKrw,
        @Schema(description = "Mock 은행 거래 번호(성공)")
        String transactionId,
        @Schema(description = "실패 코드·결과 불명·운영자 확인 사유(예: MOCK_TRANSFER_REJECTED, NO_RESPONSE, AUTO_CHECK_EXPIRED, "
                + "BANK_RECORD_NOT_FOUND, REJECTED_400:코드)")
        String failureReason,
        @Schema(description = "최초 송금 요청 시각(자동 결과 확인 기한 24시간의 기준)")
        Instant requestedAt,
        @Schema(description = "이 시도를 보낸 관리자 회원 ID(수동 재송금). 자동 송금이면 null")
        Long requestedBy,
        Instant completedAt,
        Instant failedAt,
        @Schema(description = "마지막 송금·자동 결과 확인 시작 시각")
        Instant lastCheckedAt,
        @Schema(description = "자동 결과 확인 횟수(처음 송금·관리자 재확인 제외)")
        int resultCheckCount,
        @Schema(description = "운영자 확인으로 처음 넘어간 시각")
        Instant manualReviewAt,
        @Schema(description = "마지막으로 결과를 재확인한 관리자 회원 ID")
        Long manualCheckedBy,
        Instant manualCheckedAt
) {

    static AdminExchangePayoutResponse from(AdminExchangePayoutResult result) {
        return new AdminExchangePayoutResponse(result.exchangeId(), result.exchangeStatus(), result.payoutId(),
                result.attemptNo(), result.payoutRequestId(), result.status(), result.amountKrw(),
                result.transactionId(), result.failureReason(), result.requestedAt(), result.requestedBy(),
                result.completedAt(), result.failedAt(), result.lastCheckedAt(), result.resultCheckCount(),
                result.manualReviewAt(), result.manualCheckedBy(), result.manualCheckedAt());
    }
}
