package com.hapbang.appearance.in.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/**
 * 출연 요청 수락 요청. 게스트가 화면에서 본 요청서 버전을 그대로 보낸다.
 * 그사이 호스트가 요청서를 수정했으면 수락이 막혀, 게스트는 본 조건 그대로 동의하게 된다.
 */
public record AcceptAppearanceRequestReqDto(
        @Schema(description = "상세 조회 응답의 version 값", example = "0")
        @NotNull(message = "요청서 버전은 필수입니다.")
        Long version
) {
}
