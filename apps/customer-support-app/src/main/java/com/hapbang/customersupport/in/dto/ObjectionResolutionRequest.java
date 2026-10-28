package com.hapbang.customersupport.in.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 이의 제기 판단 요청 (관리자).
 *
 * @param accepted 인정이면 true(구매자에게 HP 반환), 기각이면 false(판매자에게 지급)
 * @param content  판단 안내 답변. 문의 답변으로 달린다
 */
public record ObjectionResolutionRequest(
        @NotNull(message = "인정 여부는 필수입니다.")
        Boolean accepted,
        @NotBlank(message = "답변 내용은 필수입니다.")
        String content
) {
}
