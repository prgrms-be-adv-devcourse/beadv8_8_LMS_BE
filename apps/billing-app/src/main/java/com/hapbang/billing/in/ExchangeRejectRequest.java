package com.hapbang.billing.in;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

// 관리자 환전 거절 요청 (#294). 길이(앞뒤 공백을 뺀 1~500자)는 서버가 EXCHANGE_REJECTION_REASON_INVALID로 검사한다.
public record ExchangeRejectRequest(
        @Schema(description = "거절 사유(앞뒤 공백 제외 1~500자). 신청자에게 보이는 값", example = "계좌 정보가 신청자와 일치하지 않습니다.")
        @NotBlank String rejectionReason
) {
}
