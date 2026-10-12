package com.hapbang.billing.domain;

import org.springframework.http.HttpStatus;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

// Billing API 오류 코드와 HTTP 상태·메시지를 정의한다.
@Getter
@RequiredArgsConstructor
public enum BillingErrorCode {
    WALLET_ACCESS_DENIED(HttpStatus.FORBIDDEN, "관리자는 지갑을 조회할 수 없습니다.");

    private final HttpStatus status;
    private final String message;
}
