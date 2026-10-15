package com.hapbang.lecture.app.dto;

import java.time.LocalDateTime;

import com.hapbang.lecture.domain.OrderItem;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "지급 실패 기록이 있는 미지급(구매 확정) 주문 항목 (관리자, D54)")
public record PayoutFailureResponse(
        Long orderItemId,
        Long orderId,
        Long lectureId,
        Long sellerUserId,
        Long buyerUserId,
        @Schema(description = "주문 시점 가격 (지급할 HP)")
        long priceHp,
        @Schema(description = "지급 예정 시각 (구매일 + 31일 00:00 KST)")
        LocalDateTime payoutScheduledAt,
        @Schema(description = "지급 실패 횟수. 격리를 해제해도 이력으로 남는다")
        int payoutFailureCount,
        @Schema(description = "마지막 실패 코드. billing 오류 코드(예: PAYOUT_ALREADY_SETTLED, WALLET_NOT_FOUND), "
                + "PAYOUT_AMOUNT_MISMATCH(지급액 불일치) 또는 예외 클래스 이름(일시 오류)")
        String lastPayoutFailureCode,
        LocalDateTime lastPayoutFailedAt,
        @Schema(description = "지급 격리 시각. 값이 있으면 재시도로 풀리지 않는 실패라 지급 배치가 처리하지 않는다. "
                + "null이면 다음 지급 배치에서 다시 시도한다", nullable = true)
        LocalDateTime payoutQuarantinedAt
) {

    public static PayoutFailureResponse of(OrderItem item) {
        return new PayoutFailureResponse(item.getId(), item.getOrder().getId(), item.getLectureId(),
                item.getSellerUserId(), item.getBuyerUserId(), item.getPriceHp(), item.getPayoutScheduledAt(),
                item.getPayoutFailureCount(), item.getLastPayoutFailureCode(), item.getLastPayoutFailedAt(),
                item.getPayoutQuarantinedAt());
    }
}
