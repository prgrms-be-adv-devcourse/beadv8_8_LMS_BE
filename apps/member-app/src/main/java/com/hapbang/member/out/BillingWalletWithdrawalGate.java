package com.hapbang.member.out;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.hapbang.member.app.MemberWithdrawalUseCase;
import com.hapbang.shared.billing.domain.WithdrawalCloseOutcome;
import com.hapbang.shared.billing.dto.WithdrawalCloseCommand;
import com.hapbang.shared.billing.dto.WithdrawalCloseResultDto;
import com.hapbang.shared.billing.dto.WithdrawalReopenCommand;
import com.hapbang.shared.billing.dto.WithdrawalReopenResultDto;
import com.hapbang.shared.billing.out.BillingApiClient;
import com.hapbang.shared.billing.out.BillingCallException;
import com.hapbang.shared.billing.out.BillingCallFailureKind;
import com.hapbang.shared.billing.out.BillingCallResult;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 지갑 탈퇴 확인하고 닫기·다시 열기 (#654, docs/api/internal-withdrawals.md 4장, 지갑 #623). 지갑 Client의 결과를 회원 쪽 결과로 바꾼다.
 * <ul>
 *     <li>닫기 {@code CLOSED} → 닫힘, {@code BLOCKED} → 막힘(지갑이 준 이유 그대로).</li>
 *     <li>Client 예외는 지갑 Client 분류를 그대로 옮긴다: {@code RETRYABLE}(연결 실패·회로 열림·408·425·429) →
 *     {@link WithdrawalCallException.Kind#NOT_PROCESSED}, {@code UNKNOWN_RESULT}(시간 초과·5xx·읽을 수 없는 응답) →
 *     {@link WithdrawalCallException.Kind#UNKNOWN_RESULT}(닫혔을 수 있어 오케스트레이터가 다시 열기로 되돌린다).</li>
 *     <li>업무 거절(4xx: 이미 다시 연 탈퇴 작업, 같은 작업 ID 다른 회원, 잘못된 요청, 내부 키 오류)은 지갑이 처리하지 않은 것이
 *     확실해 {@code NOT_PROCESSED}다. 다시 시도해도 같은 결과라(설정 실수·버그) 사람이 보도록 error 로그를 남긴다.</li>
 *     <li>다시 열기는 {@code REOPENED}·{@code NOT_CLOSED} 모두 성공이다. 실패하면 예외를 던지고, 오케스트레이터가 NEEDS_RECOVERY로
 *     남겨 다시 누를 때나 정리 배치가 다시 연다.</li>
 *     <li>탈퇴 기능이 켜져 있고 지갑 Client 주소가 있을 때만 만든다(채팅 ChatAppearanceApiConfig와 같은 방식). 운영에서 주소를
 *     빠뜨리면 이 빈이 없어 탈퇴 오케스트레이터가 시작하지 못해 배포 때 드러난다.</li>
 * </ul>
 */
@Slf4j
@Component
@ConditionalOnBooleanProperty(MemberWithdrawalUseCase.ENABLED)
@ConditionalOnProperty(prefix = "hapbang.clients.billing", name = "base-url")
@RequiredArgsConstructor
public class BillingWalletWithdrawalGate implements WalletWithdrawalGate {

    private final BillingApiClient billingApiClient;

    @Override
    public WalletCloseResult close(long withdrawalId, long userId) {
        BillingCallResult<WithdrawalCloseResultDto> result;
        try {
            result = billingApiClient.closeForWithdrawal(new WithdrawalCloseCommand(withdrawalId, userId));
        } catch (BillingCallException e) {
            throw callFailed(e, "지갑 닫기");
        }
        return switch (result) {
            case BillingCallResult.Succeeded<WithdrawalCloseResultDto> succeeded ->
                    succeeded.value().outcome() == WithdrawalCloseOutcome.CLOSED
                            ? WalletCloseResult.closedWallet()
                            : WalletCloseResult.blocked(succeeded.value().reasons());
            case BillingCallResult.Failed<WithdrawalCloseResultDto> failed -> throw rejected(failed, withdrawalId, "닫기");
        };
    }

    @Override
    public void reopen(long withdrawalId, long userId) {
        BillingCallResult<WithdrawalReopenResultDto> result;
        try {
            result = billingApiClient.reopenForWithdrawal(new WithdrawalReopenCommand(withdrawalId, userId));
        } catch (BillingCallException e) {
            throw callFailed(e, "지갑 다시 열기");
        }
        if (result instanceof BillingCallResult.Failed<WithdrawalReopenResultDto> failed) {
            throw rejected(failed, withdrawalId, "다시 열기");
        }
    }

    private static WithdrawalCallException callFailed(BillingCallException e, String action) {
        WithdrawalCallException.Kind kind = e.getKind() == BillingCallFailureKind.RETRYABLE
                ? WithdrawalCallException.Kind.NOT_PROCESSED
                : WithdrawalCallException.Kind.UNKNOWN_RESULT;
        return new WithdrawalCallException(kind, action + " 결과를 받지 못했습니다.", e);
    }

    private static WithdrawalCallException rejected(BillingCallResult.Failed<?> failed, long withdrawalId, String action) {
        log.error("지갑이 탈퇴 {} 요청을 거절했습니다. 설정이나 코드를 확인해야 합니다. withdrawalId={}, status={}, code={}",
                action, withdrawalId, failed.httpStatus(), failed.rawCode());
        return new WithdrawalCallException(WithdrawalCallException.Kind.NOT_PROCESSED,
                "지갑이 " + action + "를 거절했습니다(" + failed.httpStatus() + " " + failed.rawCode() + ").", null);
    }
}
