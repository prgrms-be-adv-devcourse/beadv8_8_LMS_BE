package com.hapbang.shared.member.domain;

import com.hapbang.shared.jpa.BaseEntity;

import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.MappedSuperclass;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 원본·복제 회원 공통 필드와 판단 메서드 */
@MappedSuperclass
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public abstract class BaseUser extends BaseEntity {

    private String nickname;

    @Enumerated(EnumType.STRING)
    private Role role;

    @Enumerated(EnumType.STRING)
    private UserStatus status;

    /** 닉네임·역할·상태로 생성 */
    protected BaseUser(String nickname, Role role, UserStatus status) {
        this.nickname = nickname;
        this.role = role;
        this.status = status;
    }

    /** 정상 이용 가능 여부 */
    public boolean isActive() {
        return status.isActive();
    }

    /** 탈퇴 여부 */
    public boolean isWithdrawn() {
        return status.isWithdrawn();
    }

    /** 인플루언서 이상 여부 */
    public boolean isInfluencerOrAbove() {
        return role.isInfluencerOrAbove();
    }

    /** 닉네임·역할·상태 변경 (하위 클래스 전용) */
    protected void changeProfile(String nickname, Role role, UserStatus status) {
        this.nickname = nickname;
        this.role = role;
        this.status = status;
    }
}
