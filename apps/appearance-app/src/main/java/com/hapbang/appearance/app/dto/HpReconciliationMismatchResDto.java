package com.hapbang.appearance.app.dto;

import com.hapbang.appearance.domain.HpReconciliationMismatch;
import com.hapbang.appearance.domain.HpReconciliationRule;
import com.hapbang.appearance.domain.HpTaskRefType;

import io.swagger.v3.oas.annotations.media.Schema;

/** 서비스 간 HP 대사에서 어긋난 건 하나 (#581) */
public record HpReconciliationMismatchResDto(
        Long id,
        @Schema(description = "지갑 참조 유형: APPEARANCE_REQUEST 출연 요청서 / APPEARANCE_RECRUIT 출연 모집글") HpTaskRefType referenceType,
        @Schema(description = "요청서·모집글 ID") Long referenceId,
        @Schema(description = "지급 건 규칙처럼 출연 하나에 걸린 어긋남이면 그 출연 ID") Long appearanceId,
        @Schema(description = "어긋난 이유 (규칙). HP_TASK_DEAD는 격리 작업이 있어 비교하지 않았다는 표시") HpReconciliationRule rule,
        @Schema(description = "출연 쪽 상태 요약: 요청서·모집글 상태, 출연, 상태로 계산한 기대값, 끝난 HP 작업의 합") String appearanceState,
        @Schema(description = "지갑 쪽 상태 요약: 보류 금액, 무효화 기록, 지급 건") String walletState
) {

    public static HpReconciliationMismatchResDto from(HpReconciliationMismatch mismatch) {
        return new HpReconciliationMismatchResDto(mismatch.getId(), mismatch.getReferenceType(),
                mismatch.getReferenceId(), mismatch.getAppearanceId(), mismatch.getRule(),
                mismatch.getAppearanceState(), mismatch.getWalletState());
    }
}
