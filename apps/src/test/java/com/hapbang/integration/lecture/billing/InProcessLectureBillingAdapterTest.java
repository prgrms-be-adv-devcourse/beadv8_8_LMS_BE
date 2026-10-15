package com.hapbang.integration.lecture.billing;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;

import com.hapbang.billing.app.HoldFacade;
import com.hapbang.billing.app.HoldResult;
import com.hapbang.billing.app.PayoutResult;
import com.hapbang.billing.app.ReleaseResult;
import com.hapbang.billing.domain.BillingErrorCode;
import com.hapbang.billing.domain.BillingException;
import com.hapbang.billing.domain.PayoutType;
import com.hapbang.billing.domain.PointHoldStatus;
import com.hapbang.lecture.out.billing.BillingCallException;
import com.hapbang.lecture.out.billing.BillingCallFailureKind;
import com.hapbang.lecture.out.billing.BillingCallResult;
import com.hapbang.shared.billing.domain.BillingFailureCode;
import com.hapbang.shared.billing.domain.BillingReferenceType;
import com.hapbang.shared.billing.domain.SettlementSourceType;
import com.hapbang.shared.billing.dto.HoldCommand;
import com.hapbang.shared.billing.dto.HoldResultDto;
import com.hapbang.shared.billing.dto.PayoutCommand;
import com.hapbang.shared.billing.dto.PayoutResultDto;
import com.hapbang.shared.billing.dto.ReleaseCommand;
import com.hapbang.shared.billing.dto.ReleaseResultDto;

// 강의 BillingClient를 billing 진입점에 잇는 Adapter (D56·D62). billing은 mock이다
class InProcessLectureBillingAdapterTest {

    private static final BillingReferenceType ITEM = BillingReferenceType.LECTURE_ORDER_ITEM;
    private static final HoldCommand HOLD = new HoldCommand(1L, ITEM, 10L, 5_000L);
    private static final ReleaseCommand RELEASE = ReleaseCommand.full(1L, ITEM, 10L, "LECTURE-CANCEL-10");
    private static final PayoutCommand PAYOUT = PayoutCommand.full(1L, ITEM, 10L, "LECTURE-PAYOUT-10", 2L,
            SettlementSourceType.LECTURE_ORDER_ITEM, 10L);

    private final HoldFacade holdFacade = mock(HoldFacade.class);
    private final InProcessLectureBillingAdapter adapter = new InProcessLectureBillingAdapter(holdFacade);

    @Test
    void 보류_성공은_같은_값의_Succeeded로_돌려준다() {
        when(holdFacade.hold(any())).thenReturn(new HoldResult(100L, 200L, ITEM, 10L, 5_000L, 3_000L, 2_000L, List.of(),
                true));

        assertThat(adapter.hold(HOLD)).isEqualTo(
                new BillingCallResult.Succeeded<>(new HoldResultDto(ITEM, 10L, 5_000L, true)));
    }

    @Test
    void 반환_성공은_같은_값의_Succeeded로_돌려준다() {
        when(holdFacade.release(any())).thenReturn(new ReleaseResult(100L, 300L, 200L, ITEM, 10L, 5_000L, 2_000L, 3_000L,
                List.of(), 0L, PointHoldStatus.RELEASED, false));

        assertThat(adapter.release(RELEASE)).isEqualTo(new BillingCallResult.Succeeded<>(
                new ReleaseResultDto(ITEM, 10L, "LECTURE-CANCEL-10", 5_000L, 0L, false)));
    }

    @Test
    void 지급_성공은_같은_값의_Succeeded로_돌려준다() {
        when(holdFacade.payout(any())).thenReturn(new PayoutResult(100L, 300L, 400L, 200L, 500L, ITEM, 10L,
                SettlementSourceType.LECTURE_ORDER_ITEM, 10L, PayoutType.NORMAL, 5_000L, 3_000L, 2_000L, List.of(), 0L,
                PointHoldStatus.PAID, false));

        assertThat(adapter.payout(PAYOUT)).isEqualTo(new BillingCallResult.Succeeded<>(new PayoutResultDto(ITEM, 10L,
                "LECTURE-PAYOUT-10", 2L, SettlementSourceType.LECTURE_ORDER_ITEM, 10L, 5_000L, 0L, false)));
    }

    @Test
    void 보류가_잔액_부족으로_거절되면_Failed로_돌려준다() {
        when(holdFacade.hold(any())).thenThrow(new BillingException(BillingErrorCode.INSUFFICIENT_BALANCE));

        assertThat(adapter.hold(HOLD)).isEqualTo(new BillingCallResult.Failed<>(
                BillingFailureCode.INSUFFICIENT_BALANCE, BillingErrorCode.INSUFFICIENT_BALANCE.getMessage()));
    }

    @Test
    void 이미_닫힌_보류의_반환은_Failed로_돌려준다() {
        when(holdFacade.release(any())).thenThrow(new BillingException(BillingErrorCode.HOLD_ALREADY_CLOSED));

        assertThat(adapter.release(RELEASE)).isEqualTo(new BillingCallResult.Failed<>(
                BillingFailureCode.HOLD_ALREADY_CLOSED, BillingErrorCode.HOLD_ALREADY_CLOSED.getMessage()));
    }

    @Test
    void 판매자_지갑이_없는_지급은_Failed로_돌려준다() {
        when(holdFacade.payout(any())).thenThrow(new BillingException(BillingErrorCode.WALLET_NOT_FOUND));

        assertThat(adapter.payout(PAYOUT)).isEqualTo(new BillingCallResult.Failed<>(
                BillingFailureCode.WALLET_NOT_FOUND, BillingErrorCode.WALLET_NOT_FOUND.getMessage()));
    }

    @Test
    void billing_기술_예외는_원래_예외를_담은_RETRYABLE_BillingCallException이다() {
        CannotAcquireLockException cause = new CannotAcquireLockException("deadlock detected");
        when(holdFacade.payout(any())).thenThrow(cause);

        assertThatThrownBy(() -> adapter.payout(PAYOUT))
                .isInstanceOfSatisfying(BillingCallException.class, exception -> {
                    assertThat(exception.getKind()).isEqualTo(BillingCallFailureKind.RETRYABLE);
                    assertThat(exception.getCause()).isSameAs(cause);
                });
    }

    @Test
    void 나올_수_없는_billing_코드는_기술_실패로_다시_감싸지_않는다() {
        BillingException impossible = new BillingException(BillingErrorCode.CHARGE_UNAVAILABLE);
        when(holdFacade.hold(any())).thenThrow(impossible);

        assertThatThrownBy(() -> adapter.hold(HOLD))
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasCause(impossible);
    }
}
