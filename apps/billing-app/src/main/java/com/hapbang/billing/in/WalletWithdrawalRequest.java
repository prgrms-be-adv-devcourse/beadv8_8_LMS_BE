package com.hapbang.billing.in;

import java.util.Objects;

// 탈퇴용 지갑 닫기·다시 열기 요청 본문 (#623). 탈퇴 작업 ID는 경로로 받는다. userId가 없으면 본문을 읽지 못해 400 INVALID_REQUEST다.
record WalletWithdrawalRequest(Long userId) {

    WalletWithdrawalRequest {
        Objects.requireNonNull(userId, "회원 ID는 필수입니다.");
    }
}
