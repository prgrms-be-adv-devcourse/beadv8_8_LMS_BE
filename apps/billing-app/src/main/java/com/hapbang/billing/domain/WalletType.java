package com.hapbang.billing.domain;

// 지갑이 회원의 것인지 플랫폼의 것인지 구분한다 (#336).
public enum WalletType {
    /** 회원 지갑. 회원 한 명에 하나이며 충전 HP·수익 HP를 담는다. 수수료 수입은 담지 않는다 */
    MEMBER,
    /** 플랫폼 지갑. 회원이 없고 하나뿐이며 수수료 수입만 담는다. 회원 기능에는 나오지 않고 관리자만 조회한다 */
    PLATFORM
}
