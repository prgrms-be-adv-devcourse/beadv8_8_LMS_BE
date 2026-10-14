package com.hapbang.billing.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.hapbang.billing.domain.BillingErrorCode;
import com.hapbang.billing.domain.BillingException;
import com.hapbang.billing.domain.BillingReferenceType;
import com.hapbang.billing.domain.SettlementSourceType;

// 지급 요청·지급 배분(수익 HP 우선)·위약금 계산(30% 소수점 버림)과 키 규칙을 DB 없이 검증한다.
class PayoutCommandTest {

    private static final BillingReferenceType REQUEST = BillingReferenceType.APPEARANCE_REQUEST;
    private static final SettlementSourceType SOURCE = SettlementSourceType.APPEARANCE_REQUEST;

    @Test
    @DisplayName("지급 키는 PAYOUT:{보류 ID}:{지급 요청 ID}이고, 보류는 참조 대상의 보류 멱등키로 찾는다")
    void payoutKeys() {
        PayoutCommand command = PayoutCommand.partial(1L, REQUEST, 7L, "REQUEST-7-PAYOUT-1", 2L, SOURCE, 7L, 1_000L);

        assertThat(command.idempotencyKey(15L)).isEqualTo("PAYOUT:15:REQUEST-7-PAYOUT-1");
        assertThat(command.holdKey()).isEqualTo("HOLD:APPEARANCE_REQUEST:7");
        assertThat(command.isFull()).isFalse();
        assertThat(PayoutCommand.full(1L, REQUEST, 7L, "P1", 2L, SOURCE, 7L).isFull()).isTrue();
    }

    @Test
    @DisplayName("지급 요청의 금액이 0 이하이면 INVALID_POINT_AMOUNT, 요청 ID 형식·참조 유형·필수 값이 틀리면 거절한다")
    void payoutValidation() {
        assertThatThrownBy(() -> PayoutCommand.partial(1L, REQUEST, 7L, "P1", 2L, SOURCE, 7L, 0L))
                .isInstanceOfSatisfying(BillingException.class, exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(BillingErrorCode.INVALID_POINT_AMOUNT));
        assertThatThrownBy(() -> PayoutCommand.full(1L, REQUEST, 7L, "a:b", 2L, SOURCE, 7L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PayoutCommand.full(1L, BillingReferenceType.LECTURE_ORDER, 7L, "P1", 2L, SOURCE, 7L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PayoutCommand.full(1L, REQUEST, 7L, "P1", null, SOURCE, 7L))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> PayoutCommand.full(1L, REQUEST, 7L, "P1", 2L, null, 7L))
                .isInstanceOf(NullPointerException.class);
    }

    @ParameterizedTest
    @CsvSource({
            "5000, 3000, 4000, 4000, 0",
            "3000, 5000, 4000, 3000, 1000",
            "0, 5000, 4000, 0, 4000",
            "3000, 5000, 8000, 3000, 5000"})
    @DisplayName("지급은 남은 수익 HP 보류를 먼저, 부족분만 충전 HP 보류에서 쓴다")
    void payoutSplit(long heldRevenue, long heldCharge, long amount, long revenue, long charge) {
        PayoutSplit split = PayoutSplit.of(heldRevenue, heldCharge, amount);

        assertThat(split).isEqualTo(new PayoutSplit(revenue, charge));
        assertThat(split.totalHp()).isEqualTo(amount);
    }

    @Test
    @DisplayName("남은 보류보다 많은 지급은 PAYOUT_AMOUNT_EXCEEDS_HELD, 0 이하는 INVALID_POINT_AMOUNT로 거절한다")
    void payoutSplitRejects() {
        assertThatThrownBy(() -> PayoutSplit.of(3_000L, 5_000L, 8_001L))
                .isInstanceOfSatisfying(BillingException.class, exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(BillingErrorCode.PAYOUT_AMOUNT_EXCEEDS_HELD));
        assertThatThrownBy(() -> PayoutSplit.of(3_000L, 5_000L, 0L))
                .isInstanceOfSatisfying(BillingException.class, exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(BillingErrorCode.INVALID_POINT_AMOUNT));
    }

    @ParameterizedTest
    @CsvSource({
            "10000, 3000, 7000",
            "10001, 3000, 7001",
            "10003, 3000, 7003",
            "10004, 3001, 7003",
            "15000, 4500, 10500",
            "20000, 6000, 14000",
            "4, 1, 3"})
    @DisplayName("위약금은 정산 기준액의 30%를 소수점 버림하고, 호스트 반환액은 나머지다(반환 + 위약금 = 기준액)")
    void penaltyAmounts(long base, long penalty, long release) {
        PenaltyCommand command = penalty(base);

        assertThat(command.penaltyHp()).isEqualTo(penalty);
        assertThat(command.releaseHp()).isEqualTo(release);
        assertThat(command.penaltyHp() + command.releaseHp()).isEqualTo(base);
    }

    @Test
    @DisplayName("위약금 정산 키는 PENALTY:{보류 ID}:{취소 ID}이고, 반환·지급 상세와 원장 작업 키는 그 뒤에 :RELEASE·:PAYOUT을 붙인다")
    void penaltyKeys() {
        PenaltyCommand command = penalty(10_000L);

        assertThat(command.settlementKey(15L)).isEqualTo("PENALTY:15:C1");
        assertThat(command.releaseKey(15L)).isEqualTo("PENALTY:15:C1:RELEASE");
        assertThat(command.payoutKey(15L)).isEqualTo("PENALTY:15:C1:PAYOUT");
        // 가장 긴 취소 ID·보류 ID에서도 원장 멱등키가 255자를 넘지 않는다.
        PenaltyCommand longest = new PenaltyCommand(1L, REQUEST, 7L, "a".repeat(200), 2L, SOURCE, 7L, 10_000L);
        assertThat((longest.releaseKey(Long.MAX_VALUE) + ":4").length()).isLessThanOrEqualTo(255);
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, -1L})
    @DisplayName("위약금 정산 기준액이 0 이하이면 INVALID_POINT_AMOUNT로 거절한다")
    void penaltyInvalidBase(long base) {
        assertThatThrownBy(() -> penalty(base))
                .isInstanceOfSatisfying(BillingException.class, exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(BillingErrorCode.INVALID_POINT_AMOUNT));
    }

    @ParameterizedTest
    @ValueSource(longs = {1L, 2L, 3L})
    @DisplayName("위약금이 0 HP가 되는 정산 기준액(1~3 HP)은 PENALTY_AMOUNT_ZERO로 거절한다")
    void penaltyZeroRejected(long base) {
        assertThatThrownBy(() -> penalty(base))
                .isInstanceOfSatisfying(BillingException.class, exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(BillingErrorCode.PENALTY_AMOUNT_ZERO));
    }

    private static PenaltyCommand penalty(long base) {
        return new PenaltyCommand(1L, REQUEST, 7L, "C1", 2L, SOURCE, 7L, base);
    }
}
