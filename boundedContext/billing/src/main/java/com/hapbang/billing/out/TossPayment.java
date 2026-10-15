package com.hapbang.billing.out;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

// TossPayments Payment 객체 중 충전 확정에 쓰는 필드다 (#224). paymentKey는 원문이므로 로그·응답에 내보내지 않는다.
// status: READY, IN_PROGRESS, WAITING_FOR_DEPOSIT, DONE, CANCELED, PARTIAL_CANCELED, ABORTED, EXPIRED
@JsonIgnoreProperties(ignoreUnknown = true)
public record TossPayment(String paymentKey, String orderId, String status, Long totalAmount, String approvedAt,
        String method) {

    public static final String STATUS_DONE = "DONE";

    // 결제가 승인 완료(DONE)인지 확인한다.
    public boolean isDone() {
        return STATUS_DONE.equals(status);
    }

    @Override
    public String toString() {
        return "TossPayment[orderId=" + orderId + ", status=" + status + ", totalAmount=" + totalAmount
                + ", approvedAt=" + approvedAt + ", method=" + method + "]";
    }
}
