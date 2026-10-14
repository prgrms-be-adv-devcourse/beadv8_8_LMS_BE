package com.hapbang.billing.app;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.hapbang.shared.billing.domain.BillingReferenceType;
import com.hapbang.shared.billing.domain.SettlementSourceType;
import com.hapbang.shared.billing.dto.HoldCommand;
import com.hapbang.shared.billing.dto.PayoutCommand;
import com.hapbang.shared.billing.dto.PenaltyCommand;
import com.hapbang.shared.billing.dto.ReleaseCommand;

// Billing이 요청으로 만드는 보류·반환·지급·위약금 멱등키 규칙을 DB 없이 검증한다. 호출한 쪽은 키를 만들거나 넘기지 않는다.
class BillingKeysTest {

    private static final BillingReferenceType REQUEST = BillingReferenceType.APPEARANCE_REQUEST;
    private static final SettlementSourceType SOURCE = SettlementSourceType.APPEARANCE_REQUEST;

    @Test
    @DisplayName("보류 멱등키는 HOLD:{참조 유형 Enum 이름}:{참조 ID}로 결정론적으로 만든다")
    void holdKey() {
        assertThat(BillingKeys.hold(new HoldCommand(1L, BillingReferenceType.LECTURE_ORDER_ITEM, 1001L, 5_000L)))
                .isEqualTo("HOLD:LECTURE_ORDER_ITEM:1001");
        assertThat(BillingKeys.hold(new HoldCommand(2L, BillingReferenceType.APPEARANCE_REQUEST, 7L, 20_000L)))
                .isEqualTo("HOLD:APPEARANCE_REQUEST:7");
        assertThat(BillingKeys.hold(new HoldCommand(3L, BillingReferenceType.APPEARANCE_RECRUIT, 7L, 20_000L)))
                .isEqualTo("HOLD:APPEARANCE_RECRUIT:7");
    }

    @Test
    @DisplayName("같은 참조 대상이면 회원·금액이 달라도 같은 멱등키가 되어 참조 대상당 보류 1건으로 모인다")
    void sameReferenceSameKey() {
        HoldCommand first = new HoldCommand(1L, BillingReferenceType.LECTURE_ORDER_ITEM, 1001L, 5_000L);
        HoldCommand second = new HoldCommand(2L, BillingReferenceType.LECTURE_ORDER_ITEM, 1001L, 9_000L);

        assertThat(BillingKeys.hold(first)).isEqualTo(BillingKeys.hold(second));
    }

    @Test
    @DisplayName("반환 멱등키는 RELEASE:{보류 ID}:{반환 요청 ID}이고, 보류는 참조 대상의 보류 멱등키로 찾는다")
    void releaseKeys() {
        ReleaseCommand command = ReleaseCommand.partial(1L, BillingReferenceType.APPEARANCE_RECRUIT, 7L,
                "RECRUIT-7-RELEASE-UNDERFILLED-1", 20_000L);

        assertThat(BillingKeys.release(15L, command)).isEqualTo("RELEASE:15:RECRUIT-7-RELEASE-UNDERFILLED-1");
        assertThat(BillingKeys.hold(command)).isEqualTo("HOLD:APPEARANCE_RECRUIT:7");
    }

    @Test
    @DisplayName("지급 키는 PAYOUT:{보류 ID}:{지급 요청 ID}이고, 보류는 참조 대상의 보류 멱등키로 찾는다")
    void payoutKeys() {
        PayoutCommand command = PayoutCommand.partial(1L, REQUEST, 7L, "REQUEST-7-PAYOUT-1", 2L, SOURCE, 7L, 1_000L);

        assertThat(BillingKeys.payout(15L, command)).isEqualTo("PAYOUT:15:REQUEST-7-PAYOUT-1");
        assertThat(BillingKeys.hold(command)).isEqualTo("HOLD:APPEARANCE_REQUEST:7");
    }

    @Test
    @DisplayName("위약금 정산 키는 PENALTY:{보류 ID}:{취소 ID}이고, 반환·지급 상세와 원장 작업 키는 그 뒤에 :RELEASE·:PAYOUT을 붙인다")
    void penaltyKeys() {
        PenaltyCommand command = new PenaltyCommand(1L, REQUEST, 7L, "C1", 2L, SOURCE, 7L, 7_000L, 3_000L);

        assertThat(BillingKeys.penalty(15L, command)).isEqualTo("PENALTY:15:C1");
        assertThat(BillingKeys.penaltyRelease(15L, command)).isEqualTo("PENALTY:15:C1:RELEASE");
        assertThat(BillingKeys.penaltyPayout(15L, command)).isEqualTo("PENALTY:15:C1:PAYOUT");
        assertThat(BillingKeys.hold(command)).isEqualTo("HOLD:APPEARANCE_REQUEST:7");
    }

    @Test
    @DisplayName("가장 긴 요청 ID(200자)·보류 ID에서도 원장 멱등키({작업 키}:{줄번호})가 255자를 넘지 않는다")
    void longestKeysFitLedgerColumn() {
        String longest = "a".repeat(200);
        ReleaseCommand release = ReleaseCommand.full(1L, REQUEST, 7L, longest);
        PayoutCommand payout = PayoutCommand.full(1L, REQUEST, 7L, longest, 2L, SOURCE, 7L);
        PenaltyCommand penalty = new PenaltyCommand(1L, REQUEST, 7L, longest, 2L, SOURCE, 7L, 7_000L, 3_000L);

        assertThat((BillingKeys.release(Long.MAX_VALUE, release) + ":4").length()).isLessThanOrEqualTo(255);
        assertThat((BillingKeys.payout(Long.MAX_VALUE, payout) + ":3").length()).isLessThanOrEqualTo(255);
        assertThat((BillingKeys.penaltyRelease(Long.MAX_VALUE, penalty) + ":4").length()).isLessThanOrEqualTo(255);
        assertThat((BillingKeys.penaltyPayout(Long.MAX_VALUE, penalty) + ":3").length()).isLessThanOrEqualTo(255);
    }
}
