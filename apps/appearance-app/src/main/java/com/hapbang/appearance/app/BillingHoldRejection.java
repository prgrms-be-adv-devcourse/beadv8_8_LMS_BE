package com.hapbang.appearance.app;

import com.hapbang.appearance.domain.AppearanceErrorCode;
import com.hapbang.appearance.domain.AppearanceHoldRejectedException;
import com.hapbang.shared.billing.domain.BillingFailureCode;
import com.hapbang.shared.billing.out.BillingCallResult;

import lombok.extern.slf4j.Slf4j;

/**
 * 지갑의 보류 거절({@code Failed})을 사용자 응답으로 바꾼다 (#274). 요청서 보내기·모집글 올리기가 같이 쓴다.
 * <ul>
 *     <li>사용자에게 알릴 거절(잔액 부족)만 같은 이름의 출연 코드로 응답한다. 문구는 출연이 정한다(지갑 설명을 그대로 보이지 않는다).</li>
 *     <li>그 밖의 거절(내부 API 키 오류 등 목록 밖 코드, 멱등 충돌, 보류 불변식 위반)은 출연 설정이나 지갑 쪽 문제라
 *     {@code HP_HOLD_FAILED}로만 응답하고, 지갑 코드·HTTP 상태·설명은 오류 로그에만 남긴다.</li>
 * </ul>
 */
@Slf4j
final class BillingHoldRejection {

    private BillingHoldRejection() {
    }

    static AppearanceHoldRejectedException toException(BillingCallResult.Failed<?> failed, String target, Long id) {
        if (failed.code() == BillingFailureCode.INSUFFICIENT_BALANCE) {
            log.info("HP 보류 거절(잔액 부족), {}을 HOLD_FAILED로 둔다 id={}", target, id);
            return new AppearanceHoldRejectedException(AppearanceErrorCode.INSUFFICIENT_BALANCE);
        }
        log.error("HP 보류 거절, {}을 HOLD_FAILED로 둔다 id={}, code={}, rawCode={}, httpStatus={}, detail={}", target, id,
                failed.code(), failed.rawCode(), failed.httpStatus(), failed.detail());
        return new AppearanceHoldRejectedException(AppearanceErrorCode.HP_HOLD_FAILED);
    }
}
