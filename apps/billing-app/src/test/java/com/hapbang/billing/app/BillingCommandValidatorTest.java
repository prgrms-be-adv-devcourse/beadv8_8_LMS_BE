package com.hapbang.billing.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.hapbang.billing.domain.BillingErrorCode;
import com.hapbang.billing.domain.BillingException;
import com.hapbang.shared.billing.domain.BillingReferenceType;
import com.hapbang.shared.billing.domain.SettlementSourceType;
import com.hapbang.shared.billing.dto.HoldCommand;
import com.hapbang.shared.billing.dto.PayoutCommand;
import com.hapbang.shared.billing.dto.PenaltyCommand;
import com.hapbang.shared.billing.dto.ReleaseCommand;

// shared 요청 DTO가 검사하지 않는 금액을 Billing이 DTO 이동 전과 같은 오류 코드(INVALID_POINT_AMOUNT·PENALTY_AMOUNT_ZERO, 400)로
// 거절하는지 DB 없이 검증한다. 위약금 비율은 검사하지 않는다.
class BillingCommandValidatorTest {

    private static final BillingReferenceType ITEM = BillingReferenceType.LECTURE_ORDER_ITEM;
    private static final BillingReferenceType REQUEST = BillingReferenceType.APPEARANCE_REQUEST;
    private static final SettlementSourceType SOURCE = SettlementSourceType.APPEARANCE_REQUEST;

    @ParameterizedTest
    @ValueSource(longs = {0L, -1L, Long.MIN_VALUE})
    @DisplayName("보류·부분 반환·부분 지급 금액이 0 이하이면 INVALID_POINT_AMOUNT로 거절한다")
    void nonPositiveAmounts(long amountHp) {
        assertBillingError(() -> BillingCommandValidator.verify(new HoldCommand(1L, ITEM, 1L, amountHp)),
                BillingErrorCode.INVALID_POINT_AMOUNT);
        assertBillingError(() -> BillingCommandValidator.verify(ReleaseCommand.partial(1L, ITEM, 1L, "R1", amountHp)),
                BillingErrorCode.INVALID_POINT_AMOUNT);
        assertBillingError(() -> BillingCommandValidator.verify(
                PayoutCommand.partial(1L, REQUEST, 7L, "P1", 2L, SOURCE, 7L, amountHp)),
                BillingErrorCode.INVALID_POINT_AMOUNT);
    }

    @Test
    @DisplayName("양수 금액과 전체 반환·전체 지급(금액 없음)은 통과한다")
    void validAmounts() {
        assertThatCode(() -> BillingCommandValidator.verify(new HoldCommand(1L, ITEM, 1L, 1L)))
                .doesNotThrowAnyException();
        assertThatCode(() -> BillingCommandValidator.verify(ReleaseCommand.full(1L, ITEM, 1L, "R1")))
                .doesNotThrowAnyException();
        assertThatCode(() -> BillingCommandValidator.verify(PayoutCommand.full(1L, REQUEST, 7L, "P1", 2L, SOURCE, 7L)))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @CsvSource({
            "7000, 3000, 10000",
            "10500, 4500, 15000",
            "5000, 5000, 10000",
            "9999, 1, 10000",
            "0, 10000, 10000",
            "3, 1, 4"})
    @DisplayName("위약금 정산은 출연이 보낸 반환액·지급액을 비율과 관계없이 받고, 정산 기준액은 두 금액의 합이다")
    void penaltyAnyRatio(long releaseHp, long payoutHp, long settlementHp) {
        assertThat(BillingCommandValidator.verify(penalty(releaseHp, payoutHp))).isEqualTo(settlementHp);
    }

    @Test
    @DisplayName("위약금(게스트 지급액)이 0이면 PENALTY_AMOUNT_ZERO로 거절한다")
    void penaltyZeroPayout() {
        assertBillingError(() -> BillingCommandValidator.verify(penalty(10_000L, 0L)),
                BillingErrorCode.PENALTY_AMOUNT_ZERO);
        assertBillingError(() -> BillingCommandValidator.verify(penalty(0L, 0L)), BillingErrorCode.PENALTY_AMOUNT_ZERO);
    }

    @Test
    @DisplayName("반환액이나 지급액이 음수이면 INVALID_POINT_AMOUNT로 거절한다")
    void penaltyNegativeAmounts() {
        assertBillingError(() -> BillingCommandValidator.verify(penalty(-1L, 3_000L)),
                BillingErrorCode.INVALID_POINT_AMOUNT);
        assertBillingError(() -> BillingCommandValidator.verify(penalty(7_000L, -1L)),
                BillingErrorCode.INVALID_POINT_AMOUNT);
    }

    @Test
    @DisplayName("반환액 + 지급액이 long 범위를 넘으면 INVALID_POINT_AMOUNT로 거절한다")
    void penaltyOverflow() {
        assertBillingError(() -> BillingCommandValidator.verify(penalty(Long.MAX_VALUE, 1L)),
                BillingErrorCode.INVALID_POINT_AMOUNT);
    }

    private static PenaltyCommand penalty(long releaseHp, long payoutHp) {
        return new PenaltyCommand(1L, REQUEST, 7L, "C1", 2L, SOURCE, 7L, releaseHp, payoutHp);
    }

    private static void assertBillingError(Executable action, BillingErrorCode errorCode) {
        assertThatThrownBy(action::execute).isInstanceOfSatisfying(BillingException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(errorCode));
    }
}
