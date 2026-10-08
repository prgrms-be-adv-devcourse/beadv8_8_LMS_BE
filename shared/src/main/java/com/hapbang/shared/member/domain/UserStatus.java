package com.hapbang.shared.member.domain;

/** 회원 상태 (SUSPENDED 기능은 추후 구현) */
public enum UserStatus {
    ACTIVE,
    SUSPENDED,
    WITHDRAWN;

    /** 정상 이용 가능 여부 */
    public boolean isActive() {
        return this == ACTIVE;
    }

    /** 탈퇴 여부 */
    public boolean isWithdrawn() {
        return this == WITHDRAWN;
    }
}
