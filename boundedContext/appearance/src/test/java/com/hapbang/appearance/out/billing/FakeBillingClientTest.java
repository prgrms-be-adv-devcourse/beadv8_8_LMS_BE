package com.hapbang.appearance.out.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.hapbang.appearance.out.billing.BillingClient.HoldCommand;
import com.hapbang.appearance.out.billing.BillingClient.HoldResult;
import com.hapbang.appearance.out.billing.BillingClient.HoldStatus;
import com.hapbang.appearance.out.billing.BillingClient.PayoutCommand;
import com.hapbang.appearance.out.billing.BillingClient.Reason;
import com.hapbang.appearance.out.billing.BillingClient.RefType;
import com.hapbang.appearance.out.billing.BillingClient.ReleaseCommand;
import com.hapbang.appearance.out.billing.BillingClient.Result;
import com.hapbang.appearance.out.billing.BillingClient.SettleCommand;
import com.hapbang.appearance.out.billing.BillingClient.SettleItem;
import com.hapbang.appearance.out.billing.BillingClient.SettleType;

class FakeBillingClientTest {

    private static final long HOST_ID = 1L;
    private static final long GUEST_ID = 2L;
    private static final long REQUEST_ID = 10L;
    private static final long APPEARANCE_ID = 100L;

    private final FakeBillingClient billingClient = new FakeBillingClient();

    @Test
    @DisplayName("잔액이 충분하면 보류하고 잔액에서 뺀다")
    void hold() {
        billingClient.setBalance(HOST_ID, 50_000);

        HoldResult result = billingClient.hold(holdCommand("REQUEST-10-HOLD-1", 30_000));

        assertThat(result.isHeld()).isTrue();
        assertThat(result.heldAmount()).isEqualTo(30_000);
        assertThat(billingClient.balanceOf(HOST_ID)).isEqualTo(20_000);
    }

    @Test
    @DisplayName("잔액이 부족하면 보류하지 않고 INSUFFICIENT_BALANCE를 돌려준다")
    void holdWithInsufficientBalance() {
        billingClient.setBalance(HOST_ID, 10_000);

        HoldResult result = billingClient.hold(holdCommand("REQUEST-10-HOLD-1", 30_000));

        assertThat(result.status()).isEqualTo(HoldStatus.INSUFFICIENT_BALANCE);
        assertThat(billingClient.balanceOf(HOST_ID)).isEqualTo(10_000);
        assertThat(billingClient.heldAmount(RefType.APPEARANCE_REQUEST, REQUEST_ID)).isZero();
    }

    @Test
    @DisplayName("같은 멱등 키로 다시 요청하면 한 번만 처리한다")
    void idempotentHold() {
        billingClient.setBalance(HOST_ID, 50_000);

        billingClient.hold(holdCommand("REQUEST-10-HOLD-1", 30_000));
        HoldResult retried = billingClient.hold(holdCommand("REQUEST-10-HOLD-1", 30_000));

        assertThat(retried.heldAmount()).isEqualTo(30_000);
        assertThat(billingClient.balanceOf(HOST_ID)).isEqualTo(20_000);
    }

    @Test
    @DisplayName("반환하면 보류가 줄고 호스트 잔액이 늘어난다")
    void release() {
        billingClient.setBalance(HOST_ID, 50_000);
        billingClient.hold(holdCommand("REQUEST-10-HOLD-1", 30_000));

        Result result = billingClient.release(new ReleaseCommand("REQUEST-10-RELEASE-1",
                RefType.APPEARANCE_REQUEST, REQUEST_ID, HOST_ID, 30_000, Reason.REQUEST_REJECTED));

        assertThat(result.remainingHeldAmount()).isZero();
        assertThat(billingClient.balanceOf(HOST_ID)).isEqualTo(50_000);
    }

    @Test
    @DisplayName("지급하면 보류가 줄고 게스트 잔액이 늘어난다")
    void payout() {
        billingClient.setBalance(HOST_ID, 50_000);
        billingClient.setBalance(GUEST_ID, 0);
        billingClient.hold(holdCommand("REQUEST-10-HOLD-1", 30_000));

        Result result = billingClient.payout(new PayoutCommand("REQUEST-10-PAYOUT-1",
                RefType.APPEARANCE_REQUEST, REQUEST_ID, APPEARANCE_ID, GUEST_ID, 30_000,
                Reason.APPEARANCE_CONFIRMED));

        assertThat(result.remainingHeldAmount()).isZero();
        assertThat(billingClient.balanceOf(GUEST_ID)).isEqualTo(30_000);
    }

    @Test
    @DisplayName("일괄 정산은 위약금 지급과 나머지 반환을 한 번에 처리한다")
    void settle() {
        billingClient.setBalance(HOST_ID, 50_000);
        billingClient.setBalance(GUEST_ID, 0);
        billingClient.hold(holdCommand("REQUEST-10-HOLD-1", 30_000));

        Result result = billingClient.settle(settleCommand(9_000, 21_000));

        assertThat(result.remainingHeldAmount()).isZero();
        assertThat(billingClient.balanceOf(GUEST_ID)).isEqualTo(9_000);
        assertThat(billingClient.balanceOf(HOST_ID)).isEqualTo(41_000);
    }

    @Test
    @DisplayName("보류보다 많이 처리하려 하면 아무것도 바꾸지 않고 예외를 던진다")
    void cannotExceedHeldAmount() {
        billingClient.setBalance(HOST_ID, 50_000);
        billingClient.hold(holdCommand("REQUEST-10-HOLD-1", 30_000));

        assertThatThrownBy(() -> billingClient.settle(settleCommand(20_000, 20_000)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(billingClient.heldAmount(RefType.APPEARANCE_REQUEST, REQUEST_ID)).isEqualTo(30_000);
    }

    @Test
    @DisplayName("요청 값이 잘못되면 Billing을 부르기 전에 막는다")
    void invalidCommand() {
        assertThatThrownBy(() -> holdCommand(" ", 30_000)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> holdCommand("REQUEST-10-HOLD-1", 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("무응답으로 설정하면 BillingUnavailableException을 던지고 아무것도 처리하지 않는다. reset하면 다시 응답한다")
    void unavailable() {
        billingClient.setBalance(HOST_ID, 50_000);
        billingClient.setUnavailable(true);

        assertThatThrownBy(() -> billingClient.hold(holdCommand("REQUEST-10-HOLD-1", 30_000)))
                .isInstanceOf(BillingUnavailableException.class);
        assertThat(billingClient.balanceOf(HOST_ID)).isEqualTo(50_000);

        billingClient.reset();
        assertThat(billingClient.hold(holdCommand("REQUEST-10-HOLD-1", 30_000)).isHeld()).isTrue();
    }

    @Test
    @DisplayName("멱등 키는 {refType}-{refId}-{동작}-{순번} 형식으로 만든다")
    void idempotencyKey() {
        assertThat(BillingClient.idempotencyKey(RefType.APPEARANCE_REQUEST, 12L, "HOLD", 1))
                .isEqualTo("APPEARANCE_REQUEST-12-HOLD-1");
    }

    private HoldCommand holdCommand(String idempotencyKey, long amount) {
        return new HoldCommand(idempotencyKey, RefType.APPEARANCE_REQUEST, REQUEST_ID, HOST_ID, amount);
    }

    private SettleCommand settleCommand(long penalty, long releaseAmount) {
        return new SettleCommand("REQUEST-10-SETTLE-1", RefType.APPEARANCE_REQUEST, REQUEST_ID,
                List.of(
                        new SettleItem(SettleType.PAYOUT, GUEST_ID, APPEARANCE_ID,
                                penalty, Reason.HOST_CANCEL_PENALTY),
                        new SettleItem(SettleType.RELEASE, HOST_ID, APPEARANCE_ID,
                                releaseAmount, Reason.HOST_CANCELED)));
    }
}
