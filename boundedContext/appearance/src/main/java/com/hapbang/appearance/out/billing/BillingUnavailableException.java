package com.hapbang.appearance.out.billing;

/**
 * Billing이 응답하지 않을 때(타임아웃, 연결 실패 등) {@link BillingClient}가 던진다.
 * <p>
 * 요청이 Billing에서 처리됐는지 알 수 없는 상태다. 호출한 쪽은 대상을 그대로 두고, 나중에 같은 멱등 키로 다시 요청해 결과를 확인한다.
 */
public class BillingUnavailableException extends RuntimeException {

    public BillingUnavailableException(String message) {
        super(message);
    }

    public BillingUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
