package com.hapbang.billing.in;

import com.hapbang.billing.app.PromotionFundingCommand;
import com.hapbang.billing.domain.PromotionFundingSourceType;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

// 관리자 프로모션 지갑 충전 요청. 충전 작업 ID는 내부 API 요청 ID와 같은 형식(영문·숫자·-·_ 1~200자, ':' 없음)이다.
// 금액은 @Positive를 두지 않고 서버가 0 이하를 400 INVALID_POINT_AMOUNT로 거절한다(내부 API 금액 검사와 같은 코드).
public record PromotionFundingRequest(
        @Schema(description = "충전 작업 ID(영문·숫자·-·_ 1~200자). 같은 ID·같은 내용의 재요청은 처음 결과(replayed=true), "
                + "다른 내용이면 409 IDEMPOTENCY_KEY_CONFLICT", example = "PF-20261110-01")
        @NotNull @Pattern(regexp = "[A-Za-z0-9_-]{1,200}") String fundingWorkId,
        @Schema(description = "충전 금액(프로모션 HP). 1 이상, 0 이하는 400 INVALID_POINT_AMOUNT", example = "1000000")
        @NotNull Long amountHp,
        @Schema(description = "충전 출처. 관리자 발행(ADMIN_GRANT)만 있다", example = "ADMIN_GRANT")
        @NotNull PromotionFundingSourceType sourceType,
        @Schema(description = "충전 근거 번호(결재 번호 등, 1~200자)", example = "결재-2026-118")
        @NotBlank @Size(max = 200) String sourceRef,
        @Schema(description = "메모(선택, 500자 이하)", example = "11월 프로모션")
        @Size(max = 500) String memo
) {

    PromotionFundingCommand toCommand() {
        return new PromotionFundingCommand(fundingWorkId, amountHp, sourceType, sourceRef, memo);
    }
}
