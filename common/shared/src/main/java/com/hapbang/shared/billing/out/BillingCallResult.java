package com.hapbang.shared.billing.out;

import com.hapbang.shared.billing.domain.BillingFailureCode;

/**
 * 지갑 내부 API 호출 결과 (D62, D64). 지갑이 응답한 결과만 담는다. 결과를 받지 못한 실패는 {@link BillingCallException}이다.
 *
 * @param <T> 성공 결과 DTO
 */
public sealed interface BillingCallResult<T> {

    /** 지갑이 처리했다 */
    record Succeeded<T>(T value) implements BillingCallResult<T> {
    }

    /**
     * 지갑이 처리하지 않고 실패로 응답했다. 업무 거절만이 아니라 재시도 대상·불변식 위반도 여기로 온다.
     *
     * @param code       실패 코드. 목록에 없으면 {@link BillingFailureCode#UNMAPPED}
     * @param detail     지갑 오류 응답의 사용자용 설명. 본문이 없으면 {@code null}
     * @param httpStatus 지갑이 응답한 HTTP 상태
     * @param rawCode    지갑 오류 응답의 원래 {@code code}. 본문이 없으면 {@code null}. 사용자에게 보여 주지 않는다
     */
    record Failed<T>(BillingFailureCode code, String detail, int httpStatus, String rawCode)
            implements BillingCallResult<T> {
    }
}
