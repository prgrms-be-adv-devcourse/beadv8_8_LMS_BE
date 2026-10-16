package com.hapbang.member.domain;

import java.time.LocalDateTime;

import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.SourceUser;
import com.hapbang.shared.member.domain.UserStatus;
import com.hapbang.shared.member.dto.UserDto;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 회원 원본 */
@Entity
@Table(name = "users", schema = "members")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User extends SourceUser {

    @Column(unique = true)
    private String email;

    private String profileImageKey;

    private LocalDateTime deletedAt;

    /** 낙관적 락 버전. 동시에 수정하면 늦게 저장하는 쪽이 실패한다 (값은 JPA가 관리, MemberSupport.flush가 409로 바꾼다) */
    @Version
    private Long version;

    private User(String email, String nickname) {
        super(Nickname.validate(nickname), Role.USER, UserStatus.ACTIVE);
        this.email = email;
    }

    /** 신규 가입 (USER, ACTIVE, 프로필 이미지 없음) */
    public static User join(String email, String nickname) {
        return new User(email, nickname);
    }

    /** 닉네임 변경 (닉네임 규칙 검증) */
    public void changeNickname(String nickname) {
        changeProfile(Nickname.validate(nickname), getRole(), getStatus());
    }

    /** 프로필 이미지 변경 (S3 키, null이면 삭제). API는 S3 공통 설정 이후 */
    public void changeProfileImage(String profileImageKey) {
        this.profileImageKey = profileImageKey;
    }

    /** 역할 변경 (승급·테스트 계정) */
    public void changeRole(Role role) {
        changeProfile(getNickname(), role, getStatus());
    }

    /** 다른 모듈에 넘길 회원 정보 */
    public UserDto toDto() {
        return new UserDto(getId(), getNickname(), getRole(), getStatus());
    }
}
