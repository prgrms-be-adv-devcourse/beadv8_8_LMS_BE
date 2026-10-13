package com.hapbang.lecture.app.dto;

import com.hapbang.lecture.domain.Lecture;

import io.swagger.v3.oas.annotations.media.Schema;

public record CartItemResponse(
        Long lectureId,
        String title,
        long priceHp,
        @Schema(nullable = true)
        String thumbnailUrl,
        @Schema(description = "담은 뒤 판매 중지·삭제되면 false. 주문할 수 없다")
        boolean purchasable
) {

    // TODO: 썸네일 URL은 5단계(S3, #90)에서 채운다.
    public static CartItemResponse of(Lecture lecture) {
        return new CartItemResponse(lecture.getId(), lecture.getTitle(), lecture.getPriceHp(), null,
                lecture.isOnSale());
    }
}
