package com.hapbang.member.domain;

import java.time.LocalDateTime;

import com.hapbang.shared.jpa.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 이메일·비밀번호 로그인 정보 (#499). 구글 계정({@link OAuthAccount})처럼 로그인 수단을 회원과 분리해,
 * 회원 응답·이벤트에 비밀번호 해시가 섞이지 않게 한다. 회원당 하나이고 구글 회원은 없다.
 * 해시는 app 레이어의 PasswordEncoder가 만들어 넘긴다 (도메인은 원문 비밀번호를 갖지 않는다)
 */
@Entity
@Table(name = "password_credential", schema = "members")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PasswordCredential extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", unique = true)
    private User user;

    @Column(nullable = false)
    private String passwordHash;

    @Column(nullable = false)
    private LocalDateTime passwordChangedAt;

    private PasswordCredential(User user, String passwordHash, LocalDateTime now) {
        this.user = user;
        this.passwordHash = passwordHash;
        this.passwordChangedAt = now;
    }

    /** 가입할 때 회원에 비밀번호 로그인 정보를 만든다 */
    public static PasswordCredential register(User user, String passwordHash, LocalDateTime now) {
        return new PasswordCredential(user, passwordHash, now);
    }

    /** 비밀번호를 바꾼다 (새 해시) */
    public void changePassword(String passwordHash, LocalDateTime now) {
        this.passwordHash = passwordHash;
        this.passwordChangedAt = now;
    }

    @Override
    public String toString() {
        // 로그 등에 해시가 찍히지 않게 한다
        return "PasswordCredential[id=" + id + "]";
    }
}
