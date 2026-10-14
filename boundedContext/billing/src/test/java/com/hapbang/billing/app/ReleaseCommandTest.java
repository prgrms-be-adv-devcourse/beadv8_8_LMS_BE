package com.hapbang.billing.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.hapbang.billing.domain.BillingErrorCode;
import com.hapbang.billing.domain.BillingException;
import com.hapbang.billing.domain.BillingReferenceType;

// 반환 요청의 값 검증과 반환 멱등키 규칙을 DB 없이 검증한다.
class ReleaseCommandTest {

    @Test
    @DisplayName("반환 멱등키는 RELEASE:{보류 ID}:{반환 요청 ID}이고, 보류는 참조 대상의 보류 멱등키로 찾는다")
    void keys() {
        ReleaseCommand command = ReleaseCommand.partial(1L, BillingReferenceType.APPEARANCE_RECRUIT, 7L,
                "RECRUIT-7-RELEASE-UNDERFILLED-1", 20_000L);

        assertThat(command.idempotencyKey(15L)).isEqualTo("RELEASE:15:RECRUIT-7-RELEASE-UNDERFILLED-1");
        assertThat(command.holdKey()).isEqualTo("HOLD:APPEARANCE_RECRUIT:7");
    }

    @Test
    @DisplayName("금액을 주면 부분 반환, 금액 없이 만들면 전체 반환이다")
    void partialAndFull() {
        ReleaseCommand partial = ReleaseCommand.partial(1L, BillingReferenceType.LECTURE_ORDER_ITEM, 1L, "R1", 1_000L);
        ReleaseCommand full = ReleaseCommand.full(1L, BillingReferenceType.LECTURE_ORDER_ITEM, 1L, "R1");

        assertThat(partial.isFull()).isFalse();
        assertThat(partial.amountHp()).isEqualTo(1_000L);
        assertThat(full.isFull()).isTrue();
        assertThat(full.amountHp()).isNull();
    }

    @Test
    @DisplayName("부분 반환 금액이 0 이하이면 INVALID_POINT_AMOUNT로 거절한다")
    void invalidAmount() {
        assertThatThrownBy(() -> ReleaseCommand.partial(1L, BillingReferenceType.LECTURE_ORDER_ITEM, 1L, "R1", 0L))
                .isInstanceOfSatisfying(BillingException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(BillingErrorCode.INVALID_POINT_AMOUNT));
        assertThatThrownBy(() -> ReleaseCommand.partial(1L, BillingReferenceType.LECTURE_ORDER_ITEM, 1L, "R1", -1L))
                .isInstanceOf(BillingException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "a:b", "R%1", "R 1", "반환-1"})
    @DisplayName("반환 요청 ID는 영문·숫자·'-'·'_'만 쓸 수 있다 (':'는 키 구분자)")
    void invalidRequestId(String releaseRequestId) {
        assertThatThrownBy(() -> ReleaseCommand.full(1L, BillingReferenceType.LECTURE_ORDER_ITEM, 1L, releaseRequestId))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("반환 요청 ID는 200자까지 쓸 수 있고, 그때도 원장 멱등키가 255자를 넘지 않는다")
    void requestIdLength() {
        String longest = "a".repeat(200);
        ReleaseCommand command = ReleaseCommand.full(1L, BillingReferenceType.LECTURE_ORDER_ITEM, 1L, longest);

        assertThat((command.idempotencyKey(Long.MAX_VALUE) + ":4").length()).isLessThanOrEqualTo(255);
        assertThatThrownBy(() -> ReleaseCommand.full(1L, BillingReferenceType.LECTURE_ORDER_ITEM, 1L, longest + "a"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ReleaseCommand.full(1L, BillingReferenceType.LECTURE_ORDER_ITEM, 1L, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @EnumSource(value = BillingReferenceType.class,
            names = {"LECTURE_ORDER_ITEM", "APPEARANCE_REQUEST", "APPEARANCE_RECRUIT"}, mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("보류할 수 없는 참조 유형으로는 반환을 요청할 수 없다")
    void notHoldableReferenceType(BillingReferenceType referenceType) {
        assertThatThrownBy(() -> ReleaseCommand.full(1L, referenceType, 1L, "R1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("회원 ID·참조 유형·참조 ID는 필수다")
    void requiredValues() {
        assertThatThrownBy(() -> ReleaseCommand.full(null, BillingReferenceType.LECTURE_ORDER_ITEM, 1L, "R1"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ReleaseCommand.full(1L, null, 1L, "R1"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ReleaseCommand.full(1L, BillingReferenceType.LECTURE_ORDER_ITEM, null, "R1"))
                .isInstanceOf(NullPointerException.class);
    }
}
