package com.hapbang.shared.billing.out;

/**
 * 테스트에서 {@link BillingCallException}을 만든다. 생성자가 shared 패키지 안에서만 열려 있어 같은 패키지에 둔다.
 * 운영 코드는 이 예외를 {@link BillingApiClient}에서만 받는다.
 */
public final class BillingCallExceptions {

    private BillingCallExceptions() {
    }

    public static BillingCallException of(BillingCallFailureKind kind, int httpStatus, String rawCode) {
        return new BillingCallException(kind, httpStatus, rawCode, "테스트 지갑 실패 " + kind, null);
    }
}
