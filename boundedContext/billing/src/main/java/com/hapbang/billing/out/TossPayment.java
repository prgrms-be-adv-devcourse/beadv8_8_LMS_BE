package com.hapbang.billing.out;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

// TossPayments Payment 객체 중 충전 확정·환불(취소)에 쓰는 필드다 (#224, #260). paymentKey는 원문이므로 로그·응답에 내보내지 않는다.
// status: READY, IN_PROGRESS, WAITING_FOR_DEPOSIT, DONE, CANCELED, PARTIAL_CANCELED, ABORTED, EXPIRED
// balanceAmount: 취소할 수 있는 남은 금액. cancels: 취소 이력(취소마다 한 건 추가, 결과 확인에서 우리 취소를 찾는다)
@JsonIgnoreProperties(ignoreUnknown = true)
public record TossPayment(String paymentKey, String orderId, String status, Long totalAmount, String approvedAt,
        String method, Long balanceAmount, List<TossCancel> cancels) {

    public static final String STATUS_DONE = "DONE";

    public TossPayment {
        cancels = cancels == null ? List.of() : List.copyOf(cancels);
    }

    // 충전 승인 결과(취소 이력 없음)
    public TossPayment(String paymentKey, String orderId, String status, Long totalAmount, String approvedAt,
            String method) {
        this(paymentKey, orderId, status, totalAmount, approvedAt, method, totalAmount, List.of());
    }

    // 결제가 승인 완료(DONE)인지 확인한다.
    public boolean isDone() {
        return STATUS_DONE.equals(status);
    }

    @Override
    public String toString() {
        return "TossPayment[orderId=" + orderId + ", status=" + status + ", totalAmount=" + totalAmount
                + ", balanceAmount=" + balanceAmount + ", cancels=" + cancels.size() + ", approvedAt=" + approvedAt
                + ", method=" + method + "]";
    }
}
