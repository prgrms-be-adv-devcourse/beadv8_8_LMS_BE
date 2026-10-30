package com.hapbang.shared.billing.out;

/**
 * 출연 테스트의 Billing Mockito 대역이 "결과를 받지 못함"을 만든다 (#274).
 * {@link BillingCallException} 생성자는 공용 Client 안에서만 쓰도록 패키지 전용이라, 운영 코드는 건드리지 않고 테스트 코드만 같은 패키지에 둔다.
 * HTTP로 실제 실패를 만드는 테스트는 Mock Billing 서버를 쓴다.
 */
public final class BillingCallExceptions {

    private BillingCallExceptions() {
    }

    /** 지갑이 처리했는지 알 수 없음 (5xx·응답 시간 초과·응답 도중 끊김) */
    public static BillingCallException unknownResult(String message) {
        return new BillingCallException(BillingCallFailureKind.UNKNOWN_RESULT, 0, null, message, null);
    }
}
