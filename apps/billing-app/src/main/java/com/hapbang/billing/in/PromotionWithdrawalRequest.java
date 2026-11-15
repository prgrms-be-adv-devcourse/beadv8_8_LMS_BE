package com.hapbang.billing.in;

import com.hapbang.billing.app.PromotionWithdrawalCommand;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

// 관리자 프로모션 지갑 회수 요청. 회수 작업 ID는 충전 작업 ID와 같은 형식(영문·숫자·-·_ 1~200자, ':' 없음)이다.
// 금액은 @Positive를 두지 않고 서버가 0 이하를 400 INVALID_POINT_AMOUNT로 거절한다(충전과 같은 코드).
public record PromotionWithdrawalRequest(
        @Schema(description = "회수 작업 ID(영문·숫자·-·_ 1~200자). 같은 ID·같은 내용의 재요청은 처음 결과(replayed=true), "
                + "다른 내용이면 409 IDEMPOTENCY_KEY_CONFLICT", example = "PW-20261130-01")
        @NotNull @Pattern(regexp = "[A-Za-z0-9_-]{1,200}") String withdrawalWorkId,
        @Schema(description = "회수 금액(프로모션 HP). 1 이상이고 예약되지 않은 가용 잔액 이하", example = "200000")
        @NotNull Long amountHp,
        @Schema(description = "회수 근거 번호(결재 번호 등, 1~200자)", example = "결재-2026-130")
        @NotBlank @Size(max = 200) String referenceNo,
        @Schema(description = "메모(선택, 500자 이하)", example = "11월 프로모션 종료 후 남은 재원 회수")
        @Size(max = 500) String memo
) {

    PromotionWithdrawalCommand toCommand() {
        return new PromotionWithdrawalCommand(withdrawalWorkId, amountHp, referenceNo, memo);
    }
}
