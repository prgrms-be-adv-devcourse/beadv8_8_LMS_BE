package com.hapbang.member.domain;

import java.time.LocalDateTime;

import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.SourceUser;
import com.hapbang.shared.member.domain.UserStatus;
import com.hapbang.shared.member.dto.UserDto;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
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

    private User(String email, String nickname) {
        super(nickname, Role.USER, UserStatus.ACTIVE);
        this.email = email;
    }

    /** 신규 가입 (USER, ACTIVE, 프로필 이미지 없음) */
    public static User join(String email, String nickname) {
        return new User(email, nickname);
    }

    /** 다른 모듈에 넘길 회원 정보 */
    public UserDto toDto() {
        return new UserDto(getId(), getNickname(), getRole(), getStatus());
    }
}
