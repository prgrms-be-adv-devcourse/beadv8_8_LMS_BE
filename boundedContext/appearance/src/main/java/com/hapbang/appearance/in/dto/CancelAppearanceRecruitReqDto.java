package com.hapbang.appearance.in.dto;

import com.hapbang.appearance.domain.AppearanceCancelReasonRules;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/**
 * 출연 모집 전체 취소 요청. 요청 본문 없이 보내도 된다 (사유 없음, 확인 없음).
 */
public record CancelAppearanceRecruitReqDto(
        @Schema(description = "취소 사유 (선택, 500자 이하). 모집글과 일방 취소되는 모든 출연에 같은 사유가 남는다",
                example = "방송 일정이 갑자기 취소되었습니다.")
        @Size(max = AppearanceCancelReasonRules.REASON_MAX, message = "취소 사유는 500자 이하여야 합니다.")
        String reason,
        @Schema(description = "성립한 출연 중 게스트의 출연 취소 요청이 처리 중인 것이 있어도 취소하겠다는 확인. 그런 출연이 있는데 "
                + "true가 아니면 409 `CANCEL_REQUEST_CONFIRM_REQUIRED` (확인 화면을 띄운 뒤 true로 다시 보낸다)", example = "false")
        Boolean confirmPendingCancelRequest
) {

    public boolean confirmed() {
        return Boolean.TRUE.equals(confirmPendingCancelRequest);
    }
}
