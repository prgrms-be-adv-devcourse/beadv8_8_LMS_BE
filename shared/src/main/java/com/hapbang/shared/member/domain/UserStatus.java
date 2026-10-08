package com.hapbang.shared.member.domain;

/**
 * 회원 상태.
 * SUSPENDED(정지)는 값만 두고 기능은 추후 구현한다.
 */
public enum UserStatus {
    ACTIVE,
    SUSPENDED,
    WITHDRAWN;

    /**
     * 서비스를 정상 이용할 수 있는 상태인지 확인한다.
     */
    public boolean isActive() {
        return this == ACTIVE;
    }

    /**
     * 탈퇴한 상태인지 확인한다. 탈퇴 회원은 "탈퇴한 회원"으로 표시한다.
     */
    public boolean isWithdrawn() {
        return this == WITHDRAWN;
    }
}
