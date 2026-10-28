package com.hapbang.billing.app;

import com.hapbang.billing.domain.BillingErrorCode;
import com.hapbang.billing.domain.BillingException;
import com.hapbang.billing.domain.EarningSourceType;

// 수익 출처(강의·출연) 하나를 조회하지 못해 통합 수익 조회 전체가 실패했다 (#295). 응답에 실패한 출처(source)를 더한다.
// 내부 메시지·내부 API 키는 응답에 넣지 않는다.
public class EarningSourceException extends BillingException {

    private final EarningSourceType source;

    EarningSourceException(BillingErrorCode errorCode, EarningSourceType source, Throwable cause) {
        super(errorCode, cause);
        this.source = source;
        addProperty("source", source.name());
    }

    public EarningSourceType getSource() {
        return source;
    }
}
