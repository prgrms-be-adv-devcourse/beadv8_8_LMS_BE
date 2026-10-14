package com.hapbang.appearance.out.billing;

import com.hapbang.global.exception.BusinessException;

/**
 * {@link FakeBillingClient}의 거절. Billing 업무 예외({@code BillingException})처럼 공통 업무 예외를 상속해,
 * 공통 핸들러가 Billing과 같은 HTTP 상태·{@code code}·메시지로 응답한다.
 */
public class FakeBillingException extends BusinessException {

    public FakeBillingException(FakeBillingErrorCode errorCode) {
        super(errorCode);
    }
}
