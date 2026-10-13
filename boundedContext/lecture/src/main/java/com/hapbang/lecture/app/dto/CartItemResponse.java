package com.hapbang.lecture.app.dto;

import com.hapbang.lecture.domain.Lecture;

import io.swagger.v3.oas.annotations.media.Schema;

public record CartItemResponse(
        Long lectureId,
        String title,
        long priceHp,
        @Schema(description = "썸네일 조회 URL (Presigned GET, 1시간 유효). 썸네일이 없거나 볼 수 없으면 null", nullable = true)
        String thumbnailUrl,
        @Schema(description = "담은 뒤 판매 중지·삭제되면 false. 주문할 수 없다")
        boolean purchasable
) {

    public static CartItemResponse of(Lecture lecture, String thumbnailUrl) {
        return new CartItemResponse(lecture.getId(), lecture.getTitle(), lecture.getPriceHp(), thumbnailUrl,
                lecture.isOnSale());
    }
}
