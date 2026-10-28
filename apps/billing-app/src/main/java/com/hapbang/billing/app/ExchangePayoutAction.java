package com.hapbang.billing.app;

// 송금 실행 한 번에 Mock 은행에 무엇을 했는지 (#304). 정산 Scheduler가 실행 결과(시도·확인 건수)를 셀 때 쓴다.
public enum ExchangePayoutAction {
    /** 새 송금 시도를 만들어 보냈다(새 지급 요청 ID) */
    SENT,
    /** 결과를 모르는 시도를 같은 지급 요청 ID로 조회했다 */
    CHECKED,
    /** 조회 결과 은행에 기록이 없어 같은 지급 요청 ID·같은 내용으로 다시 보냈다 */
    RESENT,
    /** 은행을 부르지 않았다(이미 끝남, 다른 실행이 방금 보냄·확인 중, 운영자 확인 대상, 결과 반영 실패 등) */
    NONE
}
