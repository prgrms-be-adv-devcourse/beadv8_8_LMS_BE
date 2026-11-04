package com.hapbang.billing.out;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

// TossPayments Payment.cancels[] 한 건 (#260). 취소 한 번마다 transactionKey가 따로 생긴다. cancelStatus DONE이 취소 성공이다.
@JsonIgnoreProperties(ignoreUnknown = true)
public record TossCancel(String transactionKey, Long cancelAmount, String cancelReason, String canceledAt,
        String cancelStatus) {

    public static final String STATUS_DONE = "DONE";

    // 취소 사유에 우리 표식(환불 멱등키를 대괄호로 감싼 값)이 들어 있는지 확인한다.
    public boolean hasMarker(String marker) {
        return cancelReason != null && cancelReason.contains(marker);
    }

    // 취소가 성공(DONE)했는지 확인한다.
    public boolean isDone() {
        return STATUS_DONE.equals(cancelStatus);
    }
}
