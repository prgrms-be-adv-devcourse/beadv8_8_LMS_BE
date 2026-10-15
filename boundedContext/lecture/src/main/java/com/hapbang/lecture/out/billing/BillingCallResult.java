package com.hapbang.lecture.out.billing;

import java.util.Objects;

import com.hapbang.shared.billing.domain.BillingFailureCode;

/**
 * billing이 응답한 결과 (D56). 성공 또는 확정 실패 두 가지다.
 * {@link Failed}에는 업무 거절(잔액 부족)만이 아니라 재시도 대상(지갑 없음)과 불변식 위반도 들어오므로 "거절"이라 부르지 않는다.
 */
public sealed interface BillingCallResult<T> {

    record Succeeded<T>(T value) implements BillingCallResult<T> {

        public Succeeded {
            Objects.requireNonNull(value, "value");
        }
    }

    /**
     * @param code   모듈 간 실패 식별자
     * @param detail 사용자에게 보여 줄 수 있는 billing 메시지. 원인 예외나 내부 코드 문자열은 넣지 않는다
     */
    record Failed<T>(BillingFailureCode code, String detail) implements BillingCallResult<T> {

        public Failed {
            Objects.requireNonNull(code, "code");
        }
    }
}
