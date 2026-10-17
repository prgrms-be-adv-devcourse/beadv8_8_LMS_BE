package com.hapbang.billing.app;

import com.hapbang.billing.domain.BillingErrorCode;
import com.hapbang.billing.domain.EarningSourceType;

import lombok.extern.slf4j.Slf4j;

// 수익 출처 응답이 계약(요청한 상태·출처 이름·음수 아님·페이지 크기)에 맞는지 확인한다 (#295).
// 맞지 않으면 잘못된 내부 API 응답이므로 조회할 수 없는 것(EARNING_SOURCE_UNAVAILABLE)으로 보고 부분 결과를 주지 않는다.
@Slf4j
final class EarningSourceResponses {

    private EarningSourceResponses() {
    }

    static void require(EarningSourceType source, boolean valid, String reason) {
        if (!valid) {
            log.error("{} 내부 수익 API 응답이 계약과 맞지 않습니다: {}", source, reason);
            throw new EarningSourceException(BillingErrorCode.EARNING_SOURCE_UNAVAILABLE, source,
                    new IllegalStateException(reason));
        }
    }
}
