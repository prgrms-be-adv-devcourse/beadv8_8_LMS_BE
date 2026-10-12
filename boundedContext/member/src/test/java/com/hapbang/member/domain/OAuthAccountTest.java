package com.hapbang.member.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OAuthAccountTest {

    @Test
    @DisplayName("회원에 OAuth 계정을 연결하면 회원·제공자·제공자 ID가 저장된다")
    void connect() {
        User user = User.join("tester1@example.com", "tester1");

        OAuthAccount account = OAuthAccount.connect(user, OAuthProvider.GOOGLE, "google-sub-1");

        assertThat(account.getUser()).isSameAs(user);
        assertThat(account.getProvider()).isEqualTo(OAuthProvider.GOOGLE);
        assertThat(account.getProviderUserId()).isEqualTo("google-sub-1");
    }
}
