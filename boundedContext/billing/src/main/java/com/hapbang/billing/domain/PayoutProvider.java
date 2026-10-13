package com.hapbang.billing.domain;

// 환전의 KRW 송금을 처리하는 제공자를 식별하며 현재 MVP에서는 MOCK을 사용한다.
public enum PayoutProvider {
    /** MVP의 가상 송금(Mock Controller). 실제 은행 송금·지급대행 API는 쓰지 않는다 */
    MOCK
}
