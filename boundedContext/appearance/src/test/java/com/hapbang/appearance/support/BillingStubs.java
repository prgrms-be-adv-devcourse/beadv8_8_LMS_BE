package com.hapbang.appearance.support;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import org.mockito.Mockito;
import org.springframework.http.HttpStatus;

import com.hapbang.appearance.out.billing.BillingClient;
import com.hapbang.global.exception.BaseErrorCode;
import com.hapbang.global.exception.BusinessException;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Billing 대역의 실패를 만든다 (#289). 기본은 모든 요청 성공이다.
 * <ul>
 *     <li>결과를 알 수 없는 실패(무응답·연결 오류 등): 업무 예외가 아닌 런타임 예외 → 출연은 재시도 대기·{@code BILLING_UNAVAILABLE}</li>
 *     <li>Billing 거절: 공통 업무 예외({@link BusinessException}) → 출연은 그대로 응답하거나 HP 작업 {@code DEAD}.
 *     출연이 보는 것은 "업무 예외인지"와 오류 코드·메시지뿐이라, 실제 Billing 오류 코드 중 테스트에 쓰는 것만 같은 값으로 둔다</li>
 * </ul>
 */
public final class BillingStubs {

    private BillingStubs() {
    }

    /** 모든 Billing 요청이 결과를 알 수 없게 실패한다 */
    public static void unavailable(BillingClient billingClient) {
        RuntimeException unknown = new IllegalStateException("Billing 응답 없음 (테스트)");
        doThrow(unknown).when(billingClient).hold(any());
        doThrow(unknown).when(billingClient).release(any());
        doThrow(unknown).when(billingClient).payout(any());
        doThrow(unknown).when(billingClient).settlePenalty(any());
    }

    /** 실패 설정을 지우고 다시 모든 요청이 성공한다 (보낸 요청 기록도 지운다) */
    public static void available(BillingClient billingClient) {
        Mockito.reset(billingClient);
    }

    /** 모든 Billing 요청을 이 오류 코드로 거절한다 */
    public static void reject(BillingClient billingClient, Rejection rejection) {
        BusinessException rejected = rejection.exception();
        doThrow(rejected).when(billingClient).hold(any());
        doThrow(rejected).when(billingClient).release(any());
        doThrow(rejected).when(billingClient).payout(any());
        doThrow(rejected).when(billingClient).settlePenalty(any());
    }

    /** 테스트에 쓰는 Billing 거절 (코드·상태·메시지는 Billing {@code BillingErrorCode}와 같다) */
    @Getter
    @RequiredArgsConstructor
    public enum Rejection implements BaseErrorCode {
        INSUFFICIENT_BALANCE(HttpStatus.CONFLICT, "HP 잔액이 부족합니다."),
        WALLET_NOT_FOUND(HttpStatus.NOT_FOUND, "지갑을 찾을 수 없습니다."),
        HOLD_NOT_FOUND(HttpStatus.NOT_FOUND, "HP 보류를 찾을 수 없습니다.");

        private final HttpStatus status;
        private final String message;

        public BusinessException exception() {
            return new BusinessException(this);
        }
    }
}
