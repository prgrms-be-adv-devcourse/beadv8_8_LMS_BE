package com.hapbang.member.out;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.hapbang.shared.billing.domain.BillingFailureCode;
import com.hapbang.shared.billing.domain.WithdrawalCloseOutcome;
import com.hapbang.shared.billing.domain.WithdrawalReopenOutcome;
import com.hapbang.shared.billing.dto.WithdrawalCloseCommand;
import com.hapbang.shared.billing.dto.WithdrawalCloseResultDto;
import com.hapbang.shared.billing.dto.WithdrawalReopenCommand;
import com.hapbang.shared.billing.dto.WithdrawalReopenResultDto;
import com.hapbang.shared.billing.out.BillingApiClient;
import com.hapbang.shared.billing.out.BillingCallException;
import com.hapbang.shared.billing.out.BillingCallFailureKind;
import com.hapbang.shared.billing.out.BillingCallResult;
import com.hapbang.shared.member.dto.WithdrawalBlockReasonDto;

/**
 * 지갑 탈퇴 닫기·다시 열기 결과 변환 (#654, 이슈 #654의 변환 표). 지갑 Client의 결과·예외를 회원 쪽 결과·예외로 바꾼다
 */
class BillingWalletWithdrawalGateTest {

    private static final long WITHDRAWAL_ID = 7L;
    private static final long USER_ID = 42L;
    private static final WithdrawalCloseCommand CLOSE = new WithdrawalCloseCommand(WITHDRAWAL_ID, USER_ID);
    private static final WithdrawalReopenCommand REOPEN = new WithdrawalReopenCommand(WITHDRAWAL_ID, USER_ID);

    private final BillingApiClient billingApiClient = mock(BillingApiClient.class);
    private final BillingWalletWithdrawalGate gate = new BillingWalletWithdrawalGate(billingApiClient);

    @Test
    @DisplayName("닫기 CLOSED면 닫힘")
    void closed() {
        given(billingApiClient.closeForWithdrawal(CLOSE)).willReturn(new BillingCallResult.Succeeded<>(
                new WithdrawalCloseResultDto(WITHDRAWAL_ID, WithdrawalCloseOutcome.CLOSED, List.of(), false)));

        assertThat(gate.close(WITHDRAWAL_ID, USER_ID)).isEqualTo(WalletCloseResult.closedWallet());
    }

    @Test
    @DisplayName("닫기 BLOCKED면 막힘이고 지갑이 준 이유를 그대로 넘긴다")
    void blocked() {
        List<WithdrawalBlockReasonDto> reasons = List.of(
                WithdrawalBlockReasonDto.of("BILLING_CHARGE_BALANCE_REMAINING", "충전 HP 12,000이 남아 있어요."));
        given(billingApiClient.closeForWithdrawal(CLOSE)).willReturn(new BillingCallResult.Succeeded<>(
                new WithdrawalCloseResultDto(WITHDRAWAL_ID, WithdrawalCloseOutcome.BLOCKED, reasons, false)));

        assertThat(gate.close(WITHDRAWAL_ID, USER_ID)).isEqualTo(WalletCloseResult.blocked(reasons));
    }

    @Test
    @DisplayName("연결 실패·회로 열림(RETRYABLE)은 처리 안 됨, 시간 초과·5xx(UNKNOWN_RESULT)는 결과 불명으로 바꾼다 (닫기·다시 열기)")
    void callExceptions() {
        // 가짜 예외는 다른 스터빙 밖에서 먼저 만든다(Mockito 스터빙 중첩 금지)
        BillingCallException retryable = callException(BillingCallFailureKind.RETRYABLE);
        BillingCallException unknown = callException(BillingCallFailureKind.UNKNOWN_RESULT);
        given(billingApiClient.closeForWithdrawal(CLOSE)).willThrow(retryable);
        given(billingApiClient.reopenForWithdrawal(REOPEN)).willThrow(unknown);

        assertKind(() -> gate.close(WITHDRAWAL_ID, USER_ID), WithdrawalCallException.Kind.NOT_PROCESSED);
        assertKind(() -> gate.reopen(WITHDRAWAL_ID, USER_ID), WithdrawalCallException.Kind.UNKNOWN_RESULT);
    }

    @Test
    @DisplayName("닫기가 시간 초과·5xx(UNKNOWN_RESULT)면 결과 불명이다(닫혔을 수 있어 오케스트레이터가 되돌린다)")
    void closeUnknown() {
        BillingCallException unknown = callException(BillingCallFailureKind.UNKNOWN_RESULT);
        given(billingApiClient.closeForWithdrawal(CLOSE)).willThrow(unknown);

        assertKind(() -> gate.close(WITHDRAWAL_ID, USER_ID), WithdrawalCallException.Kind.UNKNOWN_RESULT);
    }

    @Test
    @DisplayName("닫기 응답이 계약과 다르면(결과값 없음, 이유 없는 BLOCKED) 막힘으로 넘기지 않고 결과 불명으로 되돌리게 한다")
    void closeContractMismatch() {
        given(billingApiClient.closeForWithdrawal(CLOSE)).willReturn(new BillingCallResult.Succeeded<>(
                new WithdrawalCloseResultDto(WITHDRAWAL_ID, null, List.of(), false)));
        assertKind(() -> gate.close(WITHDRAWAL_ID, USER_ID), WithdrawalCallException.Kind.UNKNOWN_RESULT);

        given(billingApiClient.closeForWithdrawal(CLOSE)).willReturn(new BillingCallResult.Succeeded<>(
                new WithdrawalCloseResultDto(WITHDRAWAL_ID, WithdrawalCloseOutcome.BLOCKED, List.of(), false)));
        assertKind(() -> gate.close(WITHDRAWAL_ID, USER_ID), WithdrawalCallException.Kind.UNKNOWN_RESULT);
    }

    @Test
    @DisplayName("지갑 Client가 예상하지 못한 예외를 던지면 요청이 갔는지 몰라 결과 불명이다 (닫기·다시 열기)")
    void unexpectedException() {
        given(billingApiClient.closeForWithdrawal(CLOSE)).willThrow(new IllegalStateException("timeout wrapper"));
        given(billingApiClient.reopenForWithdrawal(REOPEN)).willThrow(new IllegalStateException("timeout wrapper"));

        assertKind(() -> gate.close(WITHDRAWAL_ID, USER_ID), WithdrawalCallException.Kind.UNKNOWN_RESULT);
        assertKind(() -> gate.reopen(WITHDRAWAL_ID, USER_ID), WithdrawalCallException.Kind.UNKNOWN_RESULT);
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({
            "409, WITHDRAWAL_ALREADY_REOPENED",
            "409, IDEMPOTENCY_KEY_CONFLICT",
            "400, INVALID_REQUEST",
            "401, ''"
    })
    @DisplayName("닫기 업무 거절(4xx)은 지갑이 처리하지 않은 것이 확실해 처리 안 됨이다(되돌리지 않음)")
    void closeRejected(int status, String code) {
        given(billingApiClient.closeForWithdrawal(CLOSE)).willReturn(
                new BillingCallResult.Failed<>(BillingFailureCode.from(code), null, status, code));

        assertKind(() -> gate.close(WITHDRAWAL_ID, USER_ID), WithdrawalCallException.Kind.NOT_PROCESSED);
    }

    @Test
    @DisplayName("다시 열기는 REOPENED·NOT_CLOSED 모두 성공이다")
    void reopenSucceeded() {
        given(billingApiClient.reopenForWithdrawal(REOPEN)).willReturn(new BillingCallResult.Succeeded<>(
                new WithdrawalReopenResultDto(WITHDRAWAL_ID, WithdrawalReopenOutcome.REOPENED, false)));
        assertThatCode(() -> gate.reopen(WITHDRAWAL_ID, USER_ID)).doesNotThrowAnyException();

        given(billingApiClient.reopenForWithdrawal(REOPEN)).willReturn(new BillingCallResult.Succeeded<>(
                new WithdrawalReopenResultDto(WITHDRAWAL_ID, WithdrawalReopenOutcome.NOT_CLOSED, true)));
        assertThatCode(() -> gate.reopen(WITHDRAWAL_ID, USER_ID)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("다시 열기 거절(4xx)은 예외라 오케스트레이터가 NEEDS_RECOVERY로 남긴다")
    void reopenRejected() {
        given(billingApiClient.reopenForWithdrawal(REOPEN)).willReturn(new BillingCallResult.Failed<>(
                BillingFailureCode.IDEMPOTENCY_KEY_CONFLICT, null, 409, "IDEMPOTENCY_KEY_CONFLICT"));

        assertKind(() -> gate.reopen(WITHDRAWAL_ID, USER_ID), WithdrawalCallException.Kind.NOT_PROCESSED);
    }

    private static BillingCallException callException(BillingCallFailureKind kind) {
        BillingCallException exception = mock(BillingCallException.class);
        given(exception.getKind()).willReturn(kind);
        return exception;
    }

    private static void assertKind(org.assertj.core.api.ThrowableAssert.ThrowingCallable call,
                                   WithdrawalCallException.Kind kind) {
        assertThatThrownBy(call).isInstanceOfSatisfying(WithdrawalCallException.class,
                e -> assertThat(e.getKind()).isEqualTo(kind));
    }
}
