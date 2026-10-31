package com.hapbang.shared.member.domain;

import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 원본 회원 부모 (ID 자동 생성) */
@MappedSuperclass
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public abstract class SourceUser extends BaseUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 닉네임·역할·상태로 생성 */
    protected SourceUser(String nickname, Role role, UserStatus status) {
        super(nickname, role, status);
    }
}
