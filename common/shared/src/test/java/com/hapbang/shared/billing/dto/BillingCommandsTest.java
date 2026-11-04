package com.hapbang.shared.billing.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.hapbang.shared.billing.domain.BillingReferenceType;
import com.hapbang.shared.billing.domain.SettlementSourceType;

// Billing 연동 요청 DTO가 요청 구조(필수 값·참조 유형·요청 ID 형식)만 검사하고 금액 같은 금융 값은 검사하지 않는지 검증한다.
class BillingCommandsTest {

    private static final BillingReferenceType ITEM = BillingReferenceType.LECTURE_ORDER_ITEM;
    private static final BillingReferenceType REQUEST = BillingReferenceType.APPEARANCE_REQUEST;
    private static final SettlementSourceType SOURCE = SettlementSourceType.APPEARANCE_REQUEST;

    @Test
    @DisplayName("보류할 수 있는 참조 유형은 강의 주문 항목·출연 요청·출연 모집이다")
    void holdableReferenceTypes() {
        assertThat(HoldCommand.HOLDABLE_REFERENCE_TYPES).containsExactlyInAnyOrder(
                BillingReferenceType.LECTURE_ORDER_ITEM, BillingReferenceType.APPEARANCE_REQUEST,
                BillingReferenceType.APPEARANCE_RECRUIT);
        assertThatThrownBy(() -> HoldCommand.HOLDABLE_REFERENCE_TYPES.add(BillingReferenceType.LECTURE_ORDER))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @ParameterizedTest
    @EnumSource(value = BillingReferenceType.class,
            names = {"LECTURE_ORDER_ITEM", "APPEARANCE_REQUEST", "APPEARANCE_RECRUIT"}, mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("강의 주문 항목·출연 요청·출연 모집 외의 참조 유형(강의 주문 전체 포함)으로는 보류·반환·지급·위약금을 요청할 수 없다")
    void notHoldableReferenceType(BillingReferenceType referenceType) {
        assertThatThrownBy(() -> new HoldCommand(1L, referenceType, 1L, 1_000L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ReleaseCommand.full(1L, referenceType, 1L, "R1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PayoutCommand.full(1L, referenceType, 1L, "P1", 2L, SOURCE, 1L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> penalty(referenceType, "C1", 7_000L, 3_000L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("필수 식별자가 없으면 NullPointerException으로 거절한다")
    void requiredValues() {
        assertThatThrownBy(() -> new HoldCommand(null, ITEM, 1L, 1_000L)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new HoldCommand(1L, null, 1L, 1_000L)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new HoldCommand(1L, ITEM, null, 1_000L)).isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> ReleaseCommand.full(null, ITEM, 1L, "R1")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ReleaseCommand.full(1L, null, 1L, "R1")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ReleaseCommand.full(1L, ITEM, null, "R1")).isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> PayoutCommand.full(null, REQUEST, 7L, "P1", 2L, SOURCE, 7L))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> PayoutCommand.full(1L, REQUEST, 7L, "P1", null, SOURCE, 7L))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> PayoutCommand.full(1L, REQUEST, 7L, "P1", 2L, null, 7L))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> PayoutCommand.full(1L, REQUEST, 7L, "P1", 2L, SOURCE, null))
                .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> new PenaltyCommand(null, REQUEST, 7L, "C1", 2L, SOURCE, 7L, 7_000L, 3_000L))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PenaltyCommand(1L, REQUEST, 7L, "C1", null, SOURCE, 7L, 7_000L, 3_000L))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PenaltyCommand(1L, REQUEST, 7L, "C1", 2L, null, 7L, 7_000L, 3_000L))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PenaltyCommand(1L, REQUEST, 7L, "C1", 2L, SOURCE, null, 7_000L, 3_000L))
                .isInstanceOf(NullPointerException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "a:b", "R%1", "R 1", "반환-1"})
    @DisplayName("반환 요청 ID·지급 요청 ID·취소 ID는 영문·숫자·'-'·'_'만 쓸 수 있다 (':'는 Billing 키 구분자)")
    void invalidRequestId(String requestId) {
        assertThatThrownBy(() -> ReleaseCommand.full(1L, ITEM, 1L, requestId))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PayoutCommand.full(1L, REQUEST, 7L, requestId, 2L, SOURCE, 7L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> penalty(REQUEST, requestId, 7_000L, 3_000L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("요청 ID는 200자까지 쓸 수 있고, 없거나 201자 이상이면 거절한다")
    void requestIdLength() {
        String longest = "a".repeat(200);

        assertThatCode(() -> ReleaseCommand.full(1L, ITEM, 1L, longest)).doesNotThrowAnyException();
        assertThatThrownBy(() -> ReleaseCommand.full(1L, ITEM, 1L, longest + "a"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ReleaseCommand.full(1L, ITEM, 1L, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PayoutCommand.full(1L, REQUEST, 7L, null, 2L, SOURCE, 7L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> penalty(REQUEST, null, 7_000L, 3_000L)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("금액을 주면 부분 반환·지급, 금액 없이 만들면 전체 반환·지급이다")
    void partialAndFull() {
        ReleaseCommand partialRelease = ReleaseCommand.partial(1L, ITEM, 1L, "R1", 1_000L);
        ReleaseCommand fullRelease = ReleaseCommand.full(1L, ITEM, 1L, "R1");
        PayoutCommand partialPayout = PayoutCommand.partial(1L, REQUEST, 7L, "P1", 2L, SOURCE, 7L, 1_000L);
        PayoutCommand fullPayout = PayoutCommand.full(1L, REQUEST, 7L, "P1", 2L, SOURCE, 7L);

        assertThat(partialRelease.isFull()).isFalse();
        assertThat(partialRelease.amountHp()).isEqualTo(1_000L);
        assertThat(fullRelease.isFull()).isTrue();
        assertThat(fullRelease.amountHp()).isNull();
        assertThat(partialPayout.isFull()).isFalse();
        assertThat(partialPayout.amountHp()).isEqualTo(1_000L);
        assertThat(fullPayout.isFull()).isTrue();
        assertThat(fullPayout.amountHp()).isNull();
    }

    @Test
    @DisplayName("금액은 구조 검사 대상이 아니다: 0·음수 금액도 DTO는 만들어지고 Billing이 금융 오류로 거절한다")
    void amountsAreNotValidatedHere() {
        assertThatCode(() -> new HoldCommand(1L, ITEM, 1L, 0L)).doesNotThrowAnyException();
        assertThatCode(() -> new HoldCommand(1L, ITEM, 1L, -1L)).doesNotThrowAnyException();
        assertThatCode(() -> ReleaseCommand.partial(1L, ITEM, 1L, "R1", 0L)).doesNotThrowAnyException();
        assertThatCode(() -> PayoutCommand.partial(1L, REQUEST, 7L, "P1", 2L, SOURCE, 7L, -1L))
                .doesNotThrowAnyException();
        assertThatCode(() -> penalty(REQUEST, "C1", -1L, 0L)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("위약금 정산 요청은 출연이 계산한 호스트 반환액과 게스트 지급액을 그대로 담고, 비율을 계산하지 않는다")
    void penaltyCarriesBothAmounts() {
        PenaltyCommand command = penalty(REQUEST, "C1", 10_500L, 4_500L);

        assertThat(command.releaseAmountHp()).isEqualTo(10_500L);
        assertThat(command.payoutAmountHp()).isEqualTo(4_500L);
        assertThat(penalty(REQUEST, "C2", 5_000L, 5_000L).payoutAmountHp()).isEqualTo(5_000L);
    }

    private static PenaltyCommand penalty(BillingReferenceType referenceType, String cancellationId, long releaseHp,
            long payoutHp) {
        return new PenaltyCommand(1L, referenceType, 7L, cancellationId, 2L, SOURCE, 7L, releaseHp, payoutHp);
    }
}
