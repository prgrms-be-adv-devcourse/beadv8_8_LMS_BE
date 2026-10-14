package com.hapbang.appearance.out.billing;

/**
 * Billing 요청이 처리됐는지 알 수 없을 때(응답 없음, 연결 실패 등) {@link BillingClient}가 던진다.
 * Billing이 거절한 경우(잔액 부족 등)는 이 예외가 아니라 Billing 업무 예외다.
 * <p>
 * 호출한 쪽은 대상을 그대로 두고, 나중에 같은 요청(같은 참조 대상·같은 요청 ID)으로 다시 보내 결과를 확인한다.
 */
public class BillingUnavailableException extends RuntimeException {

    public BillingUnavailableException(String message) {
        super(message);
    }

    public BillingUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
