package com.hapbang.appearance.out.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.hapbang.shared.billing.domain.BillingReferenceType;
import com.hapbang.shared.billing.domain.SettlementSourceType;
import com.hapbang.shared.billing.dto.HoldCommand;
import com.hapbang.shared.billing.dto.HoldResultDto;
import com.hapbang.shared.billing.dto.PayoutCommand;
import com.hapbang.shared.billing.dto.PayoutResultDto;
import com.hapbang.shared.billing.dto.PenaltyCommand;
import com.hapbang.shared.billing.dto.PenaltyResultDto;
import com.hapbang.shared.billing.dto.ReleaseCommand;
import com.hapbang.shared.billing.dto.ReleaseResultDto;

/**
 * Fake Billing이 Billing 구현의 규칙(보류 1건, 요청 ID 재전송, 거절 코드)을 따르는지 확인한다.
 */
class FakeBillingClientTest {

    private static final long HOST_ID = 1L;
    private static final long GUEST_ID = 2L;
    private static final long REQUEST_ID = 10L;
    private static final long APPEARANCE_ID = 100L;
    private static final BillingReferenceType REQUEST = BillingReferenceType.APPEARANCE_REQUEST;

    private final FakeBillingClient billingClient = new FakeBillingClient();

    private HoldResultDto hold(long amount) {
        return billingClient.hold(new HoldCommand(HOST_ID, REQUEST, REQUEST_ID, amount));
    }

    private ReleaseResultDto release(String releaseRequestId, long amount) {
        return billingClient.release(ReleaseCommand.partial(HOST_ID, REQUEST, REQUEST_ID, releaseRequestId, amount));
    }

    private PayoutResultDto payout(String payoutRequestId, long appearanceId, long amount) {
        return billingClient.payout(PayoutCommand.partial(HOST_ID, REQUEST, REQUEST_ID, payoutRequestId, GUEST_ID,
                SettlementSourceType.APPEARANCE_REQUEST, appearanceId, amount));
    }

    private PenaltyResultDto penalty(String cancellationId, long releaseAmount, long payoutAmount) {
        return billingClient.settlePenalty(new PenaltyCommand(HOST_ID, REQUEST, REQUEST_ID, cancellationId, GUEST_ID,
                SettlementSourceType.APPEARANCE_REQUEST, APPEARANCE_ID, releaseAmount, payoutAmount));
    }

    private static void assertRejected(ThrowingCallable action, FakeBillingErrorCode errorCode) {
        assertThatThrownBy(action)
                .isInstanceOf(FakeBillingException.class)
                .extracting("errorCode").isEqualTo(errorCode);
    }

    // ---------------------------------------------------------------------------------------------
    // 보류
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("잔액이 충분하면 보류하고 잔액에서 뺀다")
    void hold() {
        billingClient.setBalance(HOST_ID, 50_000);

        HoldResultDto result = hold(30_000);

        assertThat(result.heldHp()).isEqualTo(30_000);
        assertThat(result.replayed()).isFalse();
        assertThat(billingClient.balanceOf(HOST_ID)).isEqualTo(20_000);
        assertThat(billingClient.heldAmount(REQUEST, REQUEST_ID)).isEqualTo(30_000);
    }

    @Test
    @DisplayName("잔액이 부족하면 INSUFFICIENT_BALANCE(409, Billing 메시지)로 거절하고 보류를 만들지 않는다")
    void holdInsufficientBalance() {
        billingClient.setBalance(HOST_ID, 10_000);

        assertThatThrownBy(() -> hold(30_000))
                .isInstanceOf(FakeBillingException.class)
                .hasMessage("HP 잔액이 부족합니다.")
                .extracting("errorCode").isEqualTo(FakeBillingErrorCode.INSUFFICIENT_BALANCE);
        assertThat(FakeBillingErrorCode.INSUFFICIENT_BALANCE.getStatus().value()).isEqualTo(409);
        assertThat(billingClient.balanceOf(HOST_ID)).isEqualTo(10_000);
        assertThat(billingClient.heldAmount(REQUEST, REQUEST_ID)).isZero();
    }

    @Test
    @DisplayName("잔액 부족으로 거절된 뒤 잔액을 채워 다시 보내면 새로 보류한다")
    void holdAfterInsufficientBalance() {
        billingClient.setBalance(HOST_ID, 10_000);
        assertRejected(() -> hold(30_000), FakeBillingErrorCode.INSUFFICIENT_BALANCE);
        billingClient.setBalance(HOST_ID, 30_000);

        HoldResultDto result = hold(30_000);

        assertThat(result.replayed()).isFalse();
        assertThat(billingClient.balanceOf(HOST_ID)).isZero();
    }

    @Test
    @DisplayName("같은 대상·같은 회원·같은 금액으로 다시 보내면 처음 보류를 돌려주고(replayed) 한 번만 뺀다")
    void holdReplay() {
        billingClient.setBalance(HOST_ID, 50_000);
        hold(30_000);

        HoldResultDto retried = hold(30_000);

        assertThat(retried.replayed()).isTrue();
        assertThat(retried.heldHp()).isEqualTo(30_000);
        assertThat(billingClient.balanceOf(HOST_ID)).isEqualTo(20_000);
    }

    @Test
    @DisplayName("같은 대상에 다른 금액이나 다른 회원이면 IDEMPOTENCY_KEY_CONFLICT")
    void holdConflict() {
        hold(30_000);

        assertRejected(() -> hold(40_000), FakeBillingErrorCode.IDEMPOTENCY_KEY_CONFLICT);
        assertRejected(() -> billingClient.hold(new HoldCommand(GUEST_ID, REQUEST, REQUEST_ID, 30_000)),
                FakeBillingErrorCode.IDEMPOTENCY_KEY_CONFLICT);
    }

    @Test
    @DisplayName("금액 0 이하는 INVALID_POINT_AMOUNT, 지갑이 없으면 WALLET_NOT_FOUND")
    void holdInvalid() {
        assertRejected(() -> hold(0), FakeBillingErrorCode.INVALID_POINT_AMOUNT);

        billingClient.removeWallet(HOST_ID);
        assertRejected(() -> hold(30_000), FakeBillingErrorCode.WALLET_NOT_FOUND);
    }

    @Test
    @DisplayName("보류 멱등 키는 Billing이 만든다: 요청에 멱등 키가 없고, 보류할 수 없는 참조 유형은 요청을 만들 때 막힌다")
    void holdCommandStructure() {
        assertThatThrownBy(() -> new HoldCommand(HOST_ID, BillingReferenceType.RECRUIT_APPEARANCE, REQUEST_ID, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---------------------------------------------------------------------------------------------
    // 반환
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("반환하면 보류에서 빼서 호스트 잔액으로 돌려준다")
    void release() {
        billingClient.setBalance(HOST_ID, 50_000);
        hold(30_000);

        ReleaseResultDto result = release("APPEARANCE_REQUEST-10-RELEASE-1", 30_000);

        assertThat(result.releasedHp()).isEqualTo(30_000);
        assertThat(result.remainingHeldHp()).isZero();
        assertThat(result.replayed()).isFalse();
        assertThat(billingClient.balanceOf(HOST_ID)).isEqualTo(50_000);
    }

    @Test
    @DisplayName("같은 반환 요청 ID로 다시 보내면 처음 결과를 돌려주고(replayed) 두 번 반환하지 않는다 (보류가 끝났어도)")
    void releaseReplay() {
        billingClient.setBalance(HOST_ID, 50_000);
        hold(30_000);
        release("APPEARANCE_REQUEST-10-RELEASE-1", 30_000);

        ReleaseResultDto retried = release("APPEARANCE_REQUEST-10-RELEASE-1", 30_000);

        assertThat(retried.replayed()).isTrue();
        assertThat(retried.releasedHp()).isEqualTo(30_000);
        assertThat(billingClient.balanceOf(HOST_ID)).isEqualTo(50_000);
    }

    @Test
    @DisplayName("전체 반환(금액 없음)은 남은 보류 전부를 돌려준다")
    void releaseFull() {
        hold(30_000);
        release("PART-1", 10_000);

        ReleaseResultDto result = billingClient.release(ReleaseCommand.full(HOST_ID, REQUEST, REQUEST_ID, "ALL-1"));

        assertThat(result.releasedHp()).isEqualTo(20_000);
        assertThat(result.remainingHeldHp()).isZero();
    }

    @Test
    @DisplayName("반환 거절: 보류 없음 / 다른 회원 / 남은 보류 초과 / 끝난 보류에 새 요청 / 같은 ID에 다른 금액")
    void releaseRejected() {
        assertRejected(() -> release("R-1", 1_000), FakeBillingErrorCode.HOLD_NOT_FOUND);

        hold(30_000);
        assertRejected(() -> billingClient.release(ReleaseCommand.partial(GUEST_ID, REQUEST, REQUEST_ID, "R-1", 1_000)),
                FakeBillingErrorCode.HOLD_OWNER_MISMATCH);
        assertRejected(() -> release("R-1", 30_001), FakeBillingErrorCode.RELEASE_AMOUNT_EXCEEDS_HELD);

        release("R-1", 30_000);
        assertRejected(() -> release("R-2", 1_000), FakeBillingErrorCode.HOLD_ALREADY_CLOSED);
        assertRejected(() -> release("R-1", 10_000), FakeBillingErrorCode.IDEMPOTENCY_KEY_CONFLICT);
    }

    @Test
    @DisplayName("반환 요청 ID는 영문·숫자·'-'·'_'만 쓸 수 있다 (요청을 만들 때 막힌다)")
    void releaseRequestIdFormat() {
        assertThatThrownBy(() -> ReleaseCommand.partial(HOST_ID, REQUEST, REQUEST_ID, "REQUEST:10", 1_000))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---------------------------------------------------------------------------------------------
    // 지급 · 위약금
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("지급하면 보류에서 빼서 게스트 잔액에 더하고, 같은 지급 요청 ID 재전송은 처음 결과")
    void payout() {
        billingClient.setBalance(HOST_ID, 50_000);
        billingClient.setBalance(GUEST_ID, 0);
        hold(30_000);

        PayoutResultDto result = payout("P-1", APPEARANCE_ID, 30_000);
        PayoutResultDto retried = payout("P-1", APPEARANCE_ID, 30_000);

        assertThat(result.paidHp()).isEqualTo(30_000);
        assertThat(retried.replayed()).isTrue();
        assertThat(billingClient.balanceOf(GUEST_ID)).isEqualTo(30_000);
        assertThat(billingClient.heldAmount(REQUEST, REQUEST_ID)).isZero();
    }

    @Test
    @DisplayName("지급 거절: 자기 자신 / 원천 유형 불일치 / 출연 요청 보류에 두 번째 지급 / 남은 보류 초과")
    void payoutRejected() {
        hold(30_000);

        assertRejected(() -> billingClient.payout(PayoutCommand.partial(HOST_ID, REQUEST, REQUEST_ID, "P-1", HOST_ID,
                SettlementSourceType.APPEARANCE_REQUEST, APPEARANCE_ID, 1_000)), FakeBillingErrorCode.PAYOUT_TO_PAYER);
        assertRejected(() -> billingClient.payout(PayoutCommand.partial(HOST_ID, REQUEST, REQUEST_ID, "P-1", GUEST_ID,
                SettlementSourceType.RECRUIT_APPEARANCE, APPEARANCE_ID, 1_000)),
                FakeBillingErrorCode.PAYOUT_SOURCE_MISMATCH);
        assertRejected(() -> payout("P-1", APPEARANCE_ID, 30_001), FakeBillingErrorCode.PAYOUT_AMOUNT_EXCEEDS_HELD);

        payout("P-1", APPEARANCE_ID, 10_000);
        assertRejected(() -> payout("P-2", APPEARANCE_ID + 1, 10_000), FakeBillingErrorCode.PAYOUT_ALREADY_SETTLED);
    }

    @Test
    @DisplayName("위약금: 출연이 계산한 반환액은 호스트에게, 위약금은 게스트에게 함께 옮긴다 (Billing은 비율을 계산하지 않는다)")
    void settlePenalty() {
        billingClient.setBalance(HOST_ID, 50_000);
        billingClient.setBalance(GUEST_ID, 0);
        hold(30_000);

        PenaltyResultDto result = penalty("C-1", 21_000, 9_000);

        assertThat(result.releasedHp()).isEqualTo(21_000);
        assertThat(result.paidHp()).isEqualTo(9_000);
        assertThat(result.remainingHeldHp()).isZero();
        assertThat(billingClient.balanceOf(GUEST_ID)).isEqualTo(9_000);
        assertThat(billingClient.balanceOf(HOST_ID)).isEqualTo(41_000);
        assertThat(penalty("C-1", 21_000, 9_000).replayed()).isTrue();
    }

    @Test
    @DisplayName("위약금 거절: 위약금 0 / 합계가 남은 보류 초과(아무것도 옮기지 않음) / 같은 취소 ID에 다른 금액 / 이미 지급한 출연")
    void settlePenaltyRejected() {
        billingClient.setBalance(HOST_ID, 50_000);
        hold(30_000);

        assertRejected(() -> penalty("C-1", 30_000, 0), FakeBillingErrorCode.PENALTY_AMOUNT_ZERO);
        assertRejected(() -> penalty("C-1", 21_001, 9_000), FakeBillingErrorCode.PENALTY_BASE_EXCEEDS_HELD);
        assertThat(billingClient.heldAmount(REQUEST, REQUEST_ID)).isEqualTo(30_000);

        penalty("C-1", 1_000, 9_000);
        assertRejected(() -> penalty("C-1", 1_000, 8_000), FakeBillingErrorCode.IDEMPOTENCY_KEY_CONFLICT);
        assertRejected(() -> penalty("C-2", 1_000, 9_000), FakeBillingErrorCode.PAYOUT_ALREADY_SETTLED);
    }

    // ---------------------------------------------------------------------------------------------
    // 결과를 알 수 없는 실패
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("무응답 설정이면 아무것도 처리하지 않고 BillingUnavailableException (업무 예외가 아니다)")
    void unavailable() {
        billingClient.setBalance(HOST_ID, 50_000);
        billingClient.setUnavailable(true);

        assertThatThrownBy(() -> hold(30_000)).isInstanceOf(BillingUnavailableException.class);
        assertThat(billingClient.balanceOf(HOST_ID)).isEqualTo(50_000);

        billingClient.setUnavailable(false);
        assertThat(hold(30_000).replayed()).isFalse();
    }
}
