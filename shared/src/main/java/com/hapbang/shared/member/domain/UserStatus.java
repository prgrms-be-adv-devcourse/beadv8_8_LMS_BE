package com.hapbang.shared.member.domain;

/** 회원 상태 (SUSPENDED 기능은 추후 구현) */
public enum UserStatus {
    /** 정상 */
    ACTIVE,
    /** 정지: 관리자 조치로 이용 제한 (추후 구현) */
    SUSPENDED,
    /** 탈퇴: 30일 뒤 개인정보 마스킹, 그 전에는 복구 가능 */
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
