package com.hapbang.member.out;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import com.hapbang.member.domain.OAuthAccount;
import com.hapbang.member.domain.OAuthProvider;
import com.hapbang.member.domain.User;
import com.hapbang.testsupport.RepositoryTest;

@RepositoryTest
class OAuthAccountRepositoryTest {

    @Autowired
    UserRepository userRepository;

    @Autowired
    OAuthAccountRepository oAuthAccountRepository;

    @Test
    @DisplayName("제공자와 제공자 ID로 연결된 회원을 찾는다")
    void findByProviderAndProviderUserId() {
        User user = userRepository.save(User.join("tester1@example.com", "tester1"));
        oAuthAccountRepository.save(OAuthAccount.connect(user, OAuthProvider.GOOGLE, "google-sub-1"));

        Optional<OAuthAccount> found =
                oAuthAccountRepository.findByProviderAndProviderUserId(OAuthProvider.GOOGLE, "google-sub-1");

        assertThat(found).isPresent();
        assertThat(found.get().getUser().getId()).isEqualTo(user.getId());
    }

    @Test
    @DisplayName("없는 제공자 ID로 찾으면 비어 있다")
    void findByProviderAndProviderUserId_notFound() {
        assertThat(oAuthAccountRepository.findByProviderAndProviderUserId(OAuthProvider.GOOGLE, "none")).isEmpty();
    }

    @Test
    @DisplayName("같은 제공자·제공자 ID로 두 번 연결하면 유니크 제약 위반이다")
    void save_duplicate() {
        User user1 = userRepository.save(User.join("tester1@example.com", "tester1"));
        User user2 = userRepository.save(User.join("tester2@example.com", "tester2"));
        oAuthAccountRepository.saveAndFlush(OAuthAccount.connect(user1, OAuthProvider.GOOGLE, "google-sub-1"));

        assertThatThrownBy(() -> oAuthAccountRepository.saveAndFlush(
                OAuthAccount.connect(user2, OAuthProvider.GOOGLE, "google-sub-1")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
