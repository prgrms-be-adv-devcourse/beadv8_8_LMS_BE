package com.hapbang.member.app;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

import com.hapbang.member.domain.OAuthProvider;
import com.hapbang.member.domain.User;
import com.hapbang.member.out.OAuthAccountRepository;
import com.hapbang.member.out.UserRepository;
import com.hapbang.shared.member.event.UserJoinedEvent;
import com.hapbang.testsupport.IntegrationTest;

@IntegrationTest
@RecordApplicationEvents
class MemberOAuthLoginIntegrationTest {

    @Autowired
    MemberFacade memberFacade;

    @Autowired
    UserRepository userRepository;

    @Autowired
    OAuthAccountRepository oAuthAccountRepository;

    @Autowired
    ApplicationEvents events;

    @Test
    @DisplayName("처음 로그인하면 회원과 OAuth 계정이 저장되고 가입 이벤트가 발행된다")
    void firstLogin() {
        User user = memberFacade.oauthLogin(OAuthProvider.GOOGLE, "google-sub-1", "tester1@example.com", "tester1");

        assertThat(user.getId()).isNotNull();
        assertThat(userRepository.count()).isEqualTo(1);
        assertThat(oAuthAccountRepository.findByProviderAndProviderUserId(OAuthProvider.GOOGLE, "google-sub-1"))
                .isPresent();
        assertThat(events.stream(UserJoinedEvent.class))
                .singleElement()
                .satisfies(e -> assertThat(e.user().id()).isEqualTo(user.getId()));
    }

    @Test
    @DisplayName("같은 구글 계정으로 다시 로그인하면 같은 회원을 반환하고 다시 가입하지 않는다")
    void secondLogin() {
        User first = memberFacade.oauthLogin(OAuthProvider.GOOGLE, "google-sub-1", "tester1@example.com", "tester1");

        User second = memberFacade.oauthLogin(OAuthProvider.GOOGLE, "google-sub-1", "tester1@example.com", "tester1");

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(second.getNickname()).isEqualTo("tester1");
        assertThat(userRepository.count()).isEqualTo(1);
        assertThat(events.stream(UserJoinedEvent.class)).hasSize(1);
    }
}
