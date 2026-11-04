package com.hapbang.billing.in;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

// 관리자 환전 최종 실패 확정 요청 (#304). 길이(앞뒤 공백을 뺀 1~500자)는 서버가 EXCHANGE_FINAL_FAILURE_REASON_INVALID로 검사한다.
public record ExchangeFinalFailureRequest(
        @Schema(description = "최종 실패 사유(앞뒤 공백 제외 1~500자)", example = "은행 확정 실패: 계좌 정보 오류로 송금 불가")
        @NotBlank String reason
) {
}
