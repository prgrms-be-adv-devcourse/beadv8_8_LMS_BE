package com.hapbang.lecture.domain;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.hapbang.shared.lecture.domain.LecturePurchaseResult;

// 주문 항목 상태를 AI 추천용 구매 결과로 줄이는 규칙(#712, D82)을 모든 상태에서 확인한다
class OrderItemStatusTest {

    @Test
    void 모든_항목_상태를_구매_결과로_바꾼다() {
        assertThat(OrderItemStatus.HOLD_PENDING.toPurchaseResult()).isEqualTo(LecturePurchaseResult.PENDING);
        assertThat(OrderItemStatus.HOLD_COMPENSATING.toPurchaseResult()).isEqualTo(LecturePurchaseResult.PENDING);
        assertThat(OrderItemStatus.HOLD_FAILED.toPurchaseResult()).isEqualTo(LecturePurchaseResult.FAILED);
        assertThat(OrderItemStatus.PURCHASED.toPurchaseResult()).isEqualTo(LecturePurchaseResult.OWNED);
        assertThat(OrderItemStatus.CONFIRMED.toPurchaseResult()).isEqualTo(LecturePurchaseResult.OWNED);
        assertThat(OrderItemStatus.CANCELED.toPurchaseResult()).isEqualTo(LecturePurchaseResult.CANCELED);
        assertThat(OrderItemStatus.RETURNED.toPurchaseResult()).isEqualTo(LecturePurchaseResult.RETURNED);
        assertThat(OrderItemStatus.CANCEL_PENDING.toPurchaseResult()).isEqualTo(LecturePurchaseResult.PENDING);
        assertThat(OrderItemStatus.RETURN_PENDING.toPurchaseResult()).isEqualTo(LecturePurchaseResult.PENDING);
        assertThat(OrderItemStatus.PAYOUT_PENDING.toPurchaseResult()).isEqualTo(LecturePurchaseResult.OWNED);
        assertThat(OrderItemStatus.PAID_OUT.toPurchaseResult()).isEqualTo(LecturePurchaseResult.OWNED);
    }

    // 한쪽만 맞으면 보유 강의가 추천되거나, 살 수 있는 강의가 추천에서 영원히 빠진다
    @ParameterizedTest
    @EnumSource(OrderItemStatus.class)
    void 다시_살_수_없는_상태는_OWNED나_PENDING이고_그_반대도_같다(OrderItemStatus status) {
        assertThat(status.toPurchaseResult().isHeld()).isEqualTo(status.isHeld());
    }

    // 용어집: 보유와 시청 가능은 다르다. OWNED는 시청 가능, PENDING은 보유 중이지만 볼 수 없는 상태다
    @ParameterizedTest
    @EnumSource(OrderItemStatus.class)
    void OWNED는_시청_가능한_상태와_같다(OrderItemStatus status) {
        assertThat(status.toPurchaseResult() == LecturePurchaseResult.OWNED).isEqualTo(status.isViewable());
    }
}
