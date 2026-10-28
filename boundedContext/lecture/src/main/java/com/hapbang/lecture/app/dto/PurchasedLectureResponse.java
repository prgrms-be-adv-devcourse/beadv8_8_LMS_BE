package com.hapbang.lecture.app.dto;

import java.time.LocalDateTime;

import com.hapbang.lecture.domain.Lecture;
import com.hapbang.lecture.domain.OrderItem;
import com.hapbang.lecture.domain.OrderItemStatus;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "시청 가능한 강의 (항목이 PURCHASED·CONFIRMED·PAID_OUT)")
public record PurchasedLectureResponse(
        Long lectureId,
        Long orderId,
        Long orderItemId,
        @Schema(description = "현재 강의 제목")
        String title,
        @Schema(description = "썸네일 조회 URL (Presigned GET, 1시간 유효). 썸네일이 없거나 볼 수 없으면 null", nullable = true)
        String thumbnailUrl,
        @Schema(description = "PURCHASED면 영상·자료를 보기 전에 재생 동의가 필요하다")
        OrderItemStatus itemStatus,
        @Schema(description = "판매자가 삭제한 강의. 기존 구매자는 계속 볼 수 있다")
        boolean lectureDeleted,
        LocalDateTime purchasedAt
) {

    public static PurchasedLectureResponse of(OrderItem item, Lecture lecture, String thumbnailUrl) {
        return new PurchasedLectureResponse(lecture.getId(), item.getOrder().getId(), item.getId(),
                lecture.getTitle(), thumbnailUrl, item.getStatus(), lecture.isDeleted(), item.getPurchasedAt());
    }
}
