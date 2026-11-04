package com.hapbang.member.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

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

    @Test
    @DisplayName("마스킹하면 제공자 계정 ID가 연결 ID를 넣은 값이 되어 같은 구글 계정으로 새로 가입할 수 있다 (#594)")
    void mask() {
        OAuthAccount account = OAuthAccount.connect(User.join("tester1@example.com", "tester1"),
                OAuthProvider.GOOGLE, "google-sub-1");
        ReflectionTestUtils.setField(account, "id", 7L);

        account.mask();

        assertThat(account.getProviderUserId()).isEqualTo("withdrawn_7");
        assertThat(account.getProvider()).isEqualTo(OAuthProvider.GOOGLE);
    }
}
