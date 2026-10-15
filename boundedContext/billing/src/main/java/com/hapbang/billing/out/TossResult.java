package com.hapbang.billing.out;

// TossPayments 호출 결과를 승인 여부 판단에 필요한 세 가지로 나눈다 (#224).
// - Paid: 2xx로 Payment 객체를 받았다(상태는 호출한 쪽이 status로 판단한다).
// - Rejected: TossPayments가 요청을 처리하지 않았다고 확정한 4xx다. 승인되지 않았으므로 실패로 끝낼 수 있다.
// - Unknown: 시간 초과·연결 오류·5xx·이미 처리됨(ALREADY_PROCESSED_PAYMENT)·멱등 요청 처리 중(409)·요청 한도(429)·
//   인증 키 오류(401)처럼 승인 여부를 알 수 없거나 PG가 결제를 판단하지 않았다.
//   실패로 끝내지 않고 결제 조회로 다시 확인한다.
public sealed interface TossResult {

    record Paid(TossPayment payment) implements TossResult {
    }

    record Rejected(int httpStatus, String code, String message) implements TossResult {
    }

    record Unknown(String reason) implements TossResult {
    }

    // 주문 ID 조회에서만 쓴다. 결제가 없다(404 NOT_FOUND_PAYMENT). 실제 TossPayments는 인증만 하고 승인 전인 결제도 404로 답하므로
    // "승인되지 않음"이지 "실패 확정"이 아니다. 호출한 쪽은 FAILED로 끝내지 않고 같은 멱등키 승인 재요청으로 확인한다.
    record NotFound(String code) implements TossResult {
    }
}
