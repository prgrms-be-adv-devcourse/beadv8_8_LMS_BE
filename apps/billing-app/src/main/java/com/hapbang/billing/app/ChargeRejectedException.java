package com.hapbang.billing.app;

import com.hapbang.billing.domain.BillingErrorCode;
import com.hapbang.billing.domain.BillingException;

// PG가 결제를 승인하지 않아 충전이 실패로 끝났다 (#224). 응답 detail은 PG가 준 사유 메시지(없으면 기본 메시지),
// 추가 속성 pgCode는 PG 오류 코드 또는 PG 결제 상태(PG_STATUS_ABORTED 등)다.
public class ChargeRejectedException extends BillingException {

    ChargeRejectedException(String failureReason) {
        super(BillingErrorCode.CHARGE_PAYMENT_REJECTED, messageOf(failureReason));
        addProperty("pgCode", codeOf(failureReason));
    }

    // 실패 사유 "CODE: message"의 코드 부분
    static String codeOf(String failureReason) {
        if (failureReason == null) {
            return null;
        }
        int separator = failureReason.indexOf(": ");
        return separator < 0 ? failureReason : failureReason.substring(0, separator);
    }

    private static String messageOf(String failureReason) {
        if (failureReason == null) {
            return null;
        }
        int separator = failureReason.indexOf(": ");
        if (separator < 0) {
            return null;
        }
        String message = failureReason.substring(separator + 2);
        return message.isBlank() || "null".equals(message) ? null : message;
    }
}
