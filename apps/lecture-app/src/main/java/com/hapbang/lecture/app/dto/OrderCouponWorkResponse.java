package com.hapbang.lecture.app.dto;

import java.time.LocalDateTime;

import com.hapbang.lecture.domain.LectureOrderStatus;
import com.hapbang.lecture.domain.OrderCouponWork;
import com.hapbang.lecture.domain.OrderCouponWorkKind;
import com.hapbang.lecture.domain.OrderCouponWorkStatus;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "주문의 쿠폰 예약·해제 작업 (관리자)")
public record OrderCouponWorkResponse(
        @Schema(description = "쿠폰 모듈 멱등 키. lecture-order:<주문 ID>:reserve 또는 :release")
        String workId,
        Long orderId,
        OrderCouponWorkKind kind,
        OrderCouponWorkStatus status,
        @Schema(description = "예약 거절 때 쿠폰 오류 코드(특정 쿠폰 때문이면 CODE:memberCouponId)", nullable = true)
        String rejectCode,
        @Schema(description = "보낸 횟수(주문 요청의 첫 시도 포함). 관리자 다시 시도 때 0이 된다")
        int attemptCount,
        @Schema(description = "결과를 모를 때 다시 보낼 시각. 그 전에는 다른 실행이 시도 중이다")
        LocalDateTime nextAttemptAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        @Schema(description = "주문 상태. 예약 작업이 멈추면 결제 처리 중(HOLD_PENDING)으로 남는다")
        LectureOrderStatus orderStatus
) {

    public static OrderCouponWorkResponse of(OrderCouponWork work, LectureOrderStatus orderStatus) {
        return new OrderCouponWorkResponse(work.getWorkId(), work.getOrderId(), work.getKind(), work.getStatus(),
                work.getRejectCode(), work.getAttemptCount(), work.getNextAttemptAt(), work.getCreatedAt(),
                work.getUpdatedAt(), orderStatus);
    }
}
