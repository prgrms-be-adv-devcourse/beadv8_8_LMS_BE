package com.hapbang.appearance.app.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 리뷰 작성 결과 (#512). 공개 여부는 담지 않는다: 상대가 썼는지 알 수 있게 되면 블라인드가 깨진다.
 */
public record AppearanceReviewIdResDto(
        @Schema(description = "작성한 리뷰 ID") Long id
) {
}
