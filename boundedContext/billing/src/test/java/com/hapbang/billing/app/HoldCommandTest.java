package com.hapbang.billing.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.hapbang.billing.domain.BillingErrorCode;
import com.hapbang.billing.domain.BillingException;
import com.hapbang.billing.domain.BillingReferenceType;

// 보류 요청의 값 검증과 Billing이 만드는 보류 멱등키 규칙을 DB 없이 검증한다.
class HoldCommandTest {

    @Test
    @DisplayName("보류 멱등키는 HOLD:{참조 유형 Enum 이름}:{참조 ID}로 결정론적으로 만든다")
    void idempotencyKey() {
        assertThat(new HoldCommand(1L, BillingReferenceType.LECTURE_ORDER_ITEM, 1001L, 5_000L).idempotencyKey())
                .isEqualTo("HOLD:LECTURE_ORDER_ITEM:1001");
        assertThat(new HoldCommand(2L, BillingReferenceType.APPEARANCE_REQUEST, 7L, 20_000L).idempotencyKey())
                .isEqualTo("HOLD:APPEARANCE_REQUEST:7");
        assertThat(new HoldCommand(3L, BillingReferenceType.APPEARANCE_RECRUIT, 7L, 20_000L).idempotencyKey())
                .isEqualTo("HOLD:APPEARANCE_RECRUIT:7");
    }

    @Test
    @DisplayName("같은 참조 대상이면 회원·금액이 달라도 같은 멱등키가 되어 참조 대상당 보류 1건으로 모인다")
    void sameReferenceSameKey() {
        HoldCommand first = new HoldCommand(1L, BillingReferenceType.LECTURE_ORDER_ITEM, 1001L, 5_000L);
        HoldCommand second = new HoldCommand(2L, BillingReferenceType.LECTURE_ORDER_ITEM, 1001L, 9_000L);

        assertThat(first.idempotencyKey()).isEqualTo(second.idempotencyKey());
    }

    @ParameterizedTest
    @EnumSource(value = BillingReferenceType.class,
            names = {"LECTURE_ORDER_ITEM", "APPEARANCE_REQUEST", "APPEARANCE_RECRUIT"}, mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("강의 주문 항목·출연 요청·출연 모집 외의 참조 유형(강의 주문 전체 포함)으로는 보류할 수 없다")
    void notHoldableReferenceType(BillingReferenceType referenceType) {
        assertThatThrownBy(() -> new HoldCommand(1L, referenceType, 1L, 1_000L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("0 이하 금액은 INVALID_POINT_AMOUNT로 거절한다")
    void invalidAmount() {
        assertThatThrownBy(() -> new HoldCommand(1L, BillingReferenceType.LECTURE_ORDER_ITEM, 1L, 0L))
                .isInstanceOfSatisfying(BillingException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(BillingErrorCode.INVALID_POINT_AMOUNT));
        assertThatThrownBy(() -> new HoldCommand(1L, BillingReferenceType.LECTURE_ORDER_ITEM, 1L, -1L))
                .isInstanceOf(BillingException.class);
    }

    @Test
    @DisplayName("회원 ID·참조 유형·참조 ID는 필수다")
    void requiredValues() {
        assertThatThrownBy(() -> new HoldCommand(null, BillingReferenceType.LECTURE_ORDER_ITEM, 1L, 1_000L))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new HoldCommand(1L, null, 1L, 1_000L))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new HoldCommand(1L, BillingReferenceType.LECTURE_ORDER_ITEM, null, 1_000L))
                .isInstanceOf(NullPointerException.class);
    }
}
