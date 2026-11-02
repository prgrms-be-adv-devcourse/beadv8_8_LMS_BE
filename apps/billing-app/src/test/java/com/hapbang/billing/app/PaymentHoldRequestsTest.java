package com.hapbang.billing.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.hapbang.shared.billing.domain.BillingReferenceType;
import com.hapbang.shared.billing.domain.SettlementSourceType;
import com.hapbang.shared.billing.dto.HoldCommand;
import com.hapbang.shared.billing.dto.ReleaseCommand;

// billing app에 복사해 둔 HP 결제 보류 규칙(참조 유형 목록·요청 ID 형식)이 shared HTTP 계약과 같은지 고정한다 (#494).
// 둘이 다르면 Controller가 받은 요청을 내부 Command로 바꾸다 실패하거나, 계약이 막는 값을 내부에서 받게 된다.
class PaymentHoldRequestsTest {

    private static final List<String> REQUEST_IDS = List.of("R1", "LECTURE-CANCEL-31", "a_b-C", "x".repeat(200),
            "x".repeat(201), "", "R:1", "R 1", "환불", "R/1");

    @Test
    @DisplayName("보류할 수 있는 참조 유형 목록은 shared HoldCommand.HOLDABLE_REFERENCE_TYPES와 같다")
    void holdableReferenceTypesMatchContract() {
        assertThat(PaymentHoldRequests.HOLDABLE_REFERENCE_TYPES)
                .containsExactlyInAnyOrderElementsOf(HoldCommand.HOLDABLE_REFERENCE_TYPES);
        for (BillingReferenceType type : BillingReferenceType.values()) {
            boolean shared = accepts(() -> new HoldCommand(1L, type, 1L, 1L));
            boolean internal = accepts(() -> new PaymentHoldCommand(1L, type, 1L, 1L));
            assertThat(internal).as(type.name()).isEqualTo(shared);
        }
    }

    @Test
    @DisplayName("요청 ID 형식은 shared 계약과 같은 값을 받고 같은 값을 거절한다")
    void requestIdFormatMatchesContract() {
        BillingReferenceType item = BillingReferenceType.LECTURE_ORDER_ITEM;
        for (String requestId : REQUEST_IDS) {
            boolean shared = accepts(() -> ReleaseCommand.full(1L, item, 1L, requestId));
            boolean internal = accepts(() -> PaymentReleaseCommand.full(1L, item, 1L, requestId));
            assertThat(internal).as(requestId).isEqualTo(shared);
        }
        assertThat(accepts(() -> PaymentReleaseCommand.full(1L, item, 1L, null))).isFalse();
    }

    @Test
    @DisplayName("내부 Command는 필수 값이 없으면 NullPointerException, 형식 오류는 IllegalArgumentException이다")
    void structureErrors() {
        BillingReferenceType request = BillingReferenceType.APPEARANCE_REQUEST;
        assertThatThrownBy(() -> new PaymentHoldCommand(null, request, 1L, 1L))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PaymentHoldVoidCommand(1L, request, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PaymentHoldVoidCommand(1L, BillingReferenceType.EXCHANGE, 1L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PaymentPayoutCommand.full(1L, request, 1L, "P1", 2L, null, 1L))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PaymentPenaltyCommand(1L, request, 1L, "C:1", 2L,
                SettlementSourceType.APPEARANCE_REQUEST, 1L, 0L, 1L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatCode(() -> PaymentPayoutCommand.partial(1L, request, 1L, "P1", 2L,
                SettlementSourceType.APPEARANCE_REQUEST, 1L, 0L))
                .doesNotThrowAnyException();
        assertThat(Arrays.asList(PaymentReleaseCommand.full(1L, request, 1L, "R1").isFull(),
                PaymentReleaseCommand.partial(1L, request, 1L, "R1", 1L).isFull())).containsExactly(true, false);
    }

    private static boolean accepts(Runnable creation) {
        try {
            creation.run();
            return true;
        } catch (IllegalArgumentException | NullPointerException exception) {
            return false;
        }
    }
}
