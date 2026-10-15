package com.hapbang.lecture.out.billing;

import java.util.Objects;

import lombok.Getter;

/**
 * billing 결과를 받지 못한 실패 (D56). 결과 타입이 아니라 런타임 예외다.
 * 원인 예외는 진단용으로만 {@link #getCause()}에 남기고 사용자 응답에는 넣지 않는다.
 */
@Getter
public class BillingCallException extends RuntimeException {

    private final BillingCallFailureKind kind;

    public BillingCallException(BillingCallFailureKind kind, Throwable cause) {
        super("billing 호출 결과를 받지 못했습니다. kind=" + kind, cause);
        this.kind = Objects.requireNonNull(kind, "kind");
    }
}
