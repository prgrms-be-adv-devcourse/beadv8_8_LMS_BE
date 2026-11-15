package com.hapbang.lecture.app.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "결제 실패 확정 결과 (관리자)")
public record OrderCouponWorkFailResponse(
        @Schema(description = """
                RESERVED_RELEASING: 쿠폰 쪽 예약이 남아 있어 예약 작업 RESERVED, 해제 작업을 만들고 한 번 보냈다.
                REJECTED: 쿠폰이 예약을 거절했었다. 예약 작업 REJECTED.
                NOT_RESERVED: 쿠폰 쪽 예약이 없다(기록 없음·모두 해제). 예약 작업은 DEAD 그대로.
                셋 다 주문은 결제 실패(HOLD_FAILED)다""")
        String outcome,
        @Schema(description = "처리한 뒤의 예약 작업")
        OrderCouponWorkResponse work
) {
}
