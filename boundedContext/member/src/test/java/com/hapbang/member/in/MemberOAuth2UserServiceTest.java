package com.hapbang.member.in;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.test.util.ReflectionTestUtils;

import com.hapbang.member.app.MemberFacade;
import com.hapbang.member.domain.OAuthProvider;
import com.hapbang.member.domain.User;

@ExtendWith(MockitoExtension.class)
class MemberOAuth2UserServiceTest {

    @Mock
    MemberFacade memberFacade;

    @Mock
    OAuth2UserService<OAuth2UserRequest, OAuth2User> googleUserService;

    @Test
    @DisplayName("구글 사용자 정보로 로그인하고 회원 ID와 역할을 담은 로그인 정보를 반환한다")
    void loadUser() {
        given(googleUserService.loadUser(any())).willReturn(new DefaultOAuth2User(
                List.of(),
                Map.of("sub", "google-sub-1", "email", "tester1@example.com", "name", "tester1"),
                "sub"));

        User user = User.join("tester1@example.com", "tester1");
        ReflectionTestUtils.setField(user, "id", 1L);
        given(memberFacade.oauthLogin(OAuthProvider.GOOGLE, "google-sub-1", "tester1@example.com", "tester1"))
                .willReturn(user);

        MemberOAuth2UserService service = new MemberOAuth2UserService(memberFacade, googleUserService);

        OAuth2User result = service.loadUser(null);

        assertThat(result.getName()).isEqualTo("1");
        assertThat(result.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_USER");
    }
}
