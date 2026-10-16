package com.hapbang.billing.domain;

// Mock 은행이 받은 가상 송금 요청의 처리 상태 (#288). 실제 돈은 움직이지 않는다.
public enum MockPayoutTransferStatus {
    /** 요청을 접수했고 결과를 정하기 전이다 */
    PROCESSING,
    /** 가상 송금 성공 */
    SUCCEEDED,
    /** 가상 송금 확정 실패(은행이 거절했다). 응답 지연·5xx 같은 장애와 다르다 */
    FAILED
}
