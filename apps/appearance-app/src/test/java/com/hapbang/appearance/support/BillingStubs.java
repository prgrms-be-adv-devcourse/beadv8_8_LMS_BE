package com.hapbang.appearance.support;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.mockito.Mockito;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import com.hapbang.appearance.out.billing.BillingClient;
import com.hapbang.shared.billing.domain.BillingFailureCode;
import com.hapbang.shared.billing.dto.HoldCommand;
import com.hapbang.shared.billing.dto.HoldResultDto;
import com.hapbang.shared.billing.dto.PayoutCommand;
import com.hapbang.shared.billing.dto.PayoutResultDto;
import com.hapbang.shared.billing.dto.PenaltyCommand;
import com.hapbang.shared.billing.dto.PenaltyResultDto;
import com.hapbang.shared.billing.dto.ReleaseCommand;
import com.hapbang.shared.billing.dto.ReleaseResultDto;
import com.hapbang.shared.billing.out.BillingCallExceptions;
import com.hapbang.shared.billing.out.BillingCallResult;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Billing Mockito 대역의 응답을 정한다 (#289, #274). 기본은 모든 요청 성공이다({@link SucceedByDefault}).
 * <ul>
 *     <li>성공: {@code Succeeded}에 요청 값을 그대로 담은 결과 ({@code replayed = false}, 남은 보류 0)</li>
 *     <li>결과를 받지 못함(무응답·연결 오류 등): {@code BillingCallException(UNKNOWN_RESULT)} → 출연은 재시도 대기·{@code BILLING_UNAVAILABLE}</li>
 *     <li>지갑 거절: {@code Failed(코드, 설명, HTTP 상태)} → 출연은 보류 실패 응답이나 HP 작업 {@code DEAD}.
 *     실제 지갑 코드 중 테스트에 쓰는 것만 같은 값으로 둔다</li>
 * </ul>
 * HTTP 응답 해석(상태 코드·재전송·연결 오류)까지 보려면 Mock Billing 서버({@link MockBillingIntegrationTest})를 쓴다.
 */
public final class BillingStubs {

    /** 대역이 성공으로 받아들인 반환 요청 (테스트마다 비운다). {@link BillingCalls#releasedAmount}가 읽는다 */
    private static final List<ReleaseCommand> ACCEPTED_RELEASES = new ArrayList<>();

    private BillingStubs() {
    }

    /** 모든 Billing 요청이 결과를 받지 못하고 실패한다 (지갑이 처리했는지 알 수 없음) */
    public static void unavailable(BillingClient billingClient) {
        RuntimeException unknown = BillingCallExceptions.unknownResult("Billing 응답 없음 (테스트)");
        doThrow(unknown).when(billingClient).hold(any());
        doThrow(unknown).when(billingClient).release(any());
        doThrow(unknown).when(billingClient).payout(any());
        doThrow(unknown).when(billingClient).settlePenalty(any());
    }

    /** 실패 설정을 지우고 다시 모든 요청이 성공한다 (보낸 요청 기록도 지운다) */
    public static void available(BillingClient billingClient) {
        Mockito.reset(billingClient);
        succeed(billingClient);
    }

    /** 모든 Billing 요청을 이 오류 코드로 거절한다 */
    public static void reject(BillingClient billingClient, Rejection rejection) {
        BillingCallResult.Failed<?> rejected = rejection.failed();
        doReturn(rejected).when(billingClient).hold(any());
        doReturn(rejected).when(billingClient).release(any());
        doReturn(rejected).when(billingClient).payout(any());
        doReturn(rejected).when(billingClient).settlePenalty(any());
    }

    static List<ReleaseCommand> acceptedReleases() {
        return List.copyOf(ACCEPTED_RELEASES);
    }

    private static void succeed(BillingClient billingClient) {
        doAnswer(invocation -> held(invocation.getArgument(0))).when(billingClient).hold(any());
        doAnswer(invocation -> released(invocation.getArgument(0))).when(billingClient).release(any());
        doAnswer(invocation -> paid(invocation.getArgument(0))).when(billingClient).payout(any());
        doAnswer(invocation -> settled(invocation.getArgument(0))).when(billingClient).settlePenalty(any());
    }

    private static BillingCallResult<HoldResultDto> held(HoldCommand command) {
        return new BillingCallResult.Succeeded<>(new HoldResultDto(command.referenceType(), command.referenceId(),
                command.amountHp(), false));
    }

    private static BillingCallResult<ReleaseResultDto> released(ReleaseCommand command) {
        ACCEPTED_RELEASES.add(command);
        return new BillingCallResult.Succeeded<>(new ReleaseResultDto(command.referenceType(), command.referenceId(),
                command.releaseRequestId(), amountOf(command.amountHp()), 0, false));
    }

    private static BillingCallResult<PayoutResultDto> paid(PayoutCommand command) {
        return new BillingCallResult.Succeeded<>(new PayoutResultDto(command.referenceType(), command.referenceId(),
                command.payoutRequestId(), command.receiverUserId(), command.sourceType(), command.sourceId(),
                amountOf(command.amountHp()), 0, false));
    }

    private static BillingCallResult<PenaltyResultDto> settled(PenaltyCommand command) {
        return new BillingCallResult.Succeeded<>(new PenaltyResultDto(command.referenceType(), command.referenceId(),
                command.cancellationId(), command.receiverUserId(), command.sourceType(), command.sourceId(),
                command.releaseAmountHp(), command.payoutAmountHp(), 0, false));
    }

    /** 금액이 없는 요청(남은 보류 전액)은 대역이 남은 보류를 모르므로 0으로 둔다 */
    private static long amountOf(Long amountHp) {
        return amountHp == null ? 0 : amountHp;
    }

    /** 테스트에 쓰는 지갑 거절 (코드·상태·설명은 지갑 {@code BillingErrorCode}와 같다) */
    @Getter
    @RequiredArgsConstructor
    public enum Rejection {
        INSUFFICIENT_BALANCE(409, "HP 잔액이 부족합니다."),
        WALLET_NOT_FOUND(404, "지갑을 찾을 수 없습니다."),
        HOLD_NOT_FOUND(404, "HP 보류를 찾을 수 없습니다.");

        private final int httpStatus;
        private final String detail;

        public <T> BillingCallResult.Failed<T> failed() {
            return new BillingCallResult.Failed<>(BillingFailureCode.valueOf(name()), detail, httpStatus, name());
        }
    }

    /**
     * 테스트마다 Billing 대역을 "모든 요청 성공"으로 둔다. 대역은 테스트가 끝날 때 설정이 지워지므로({@code MockReset.AFTER})
     * 테스트 시작 때마다 다시 건다. 대역이 아닌 실제 구현(Mock Billing 서버를 쓰는 테스트)이면 아무것도 하지 않는다.
     */
    public static class SucceedByDefault implements BeforeEachCallback {

        @Override
        public void beforeEach(ExtensionContext context) {
            ACCEPTED_RELEASES.clear();
            BillingClient billingClient = SpringExtension.getApplicationContext(context).getBean(BillingClient.class);
            if (Mockito.mockingDetails(billingClient).isMock()) {
                succeed(billingClient);
            }
        }
    }
}
