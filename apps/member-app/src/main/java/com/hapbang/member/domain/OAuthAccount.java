package com.hapbang.member.domain;

import com.hapbang.shared.jpa.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** OAuth 제공자 계정과 회원 연결 */
@Entity
@Table(name = "oauth_account", schema = "members",
        uniqueConstraints = @UniqueConstraint(columnNames = {"provider", "provider_user_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OAuthAccount extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OAuthProvider provider;

    @Column(nullable = false)
    private String providerUserId;

    private OAuthAccount(User user, OAuthProvider provider, String providerUserId) {
        this.user = user;
        this.provider = provider;
        this.providerUserId = providerUserId;
    }

    /** 회원에 OAuth 계정 연결 */
    public static OAuthAccount connect(User user, OAuthProvider provider, String providerUserId) {
        return new OAuthAccount(user, provider, providerUserId);
    }

    /**
     * 탈퇴 회원 마스킹 (#594). 제공자 계정 ID를 이 연결의 ID를 넣은 값으로 바꿔, 같은 구글 계정으로 새로 가입할 수 있게 한다.
     * 행은 남긴다(삭제 금지). 값이 겹치지 않아 (제공자, 계정 ID) 유일 제약을 지킨다
     */
    public void mask() {
        this.providerUserId = "withdrawn_" + id;
    }
}
