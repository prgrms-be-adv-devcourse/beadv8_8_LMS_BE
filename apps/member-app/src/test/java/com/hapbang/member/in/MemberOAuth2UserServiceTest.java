package com.hapbang.member.in;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import java.time.LocalDateTime;
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
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.test.util.ReflectionTestUtils;

import com.hapbang.member.app.MemberFacade;
import com.hapbang.member.domain.MemberErrorCode;
import com.hapbang.member.domain.MemberException;
import com.hapbang.member.domain.OAuthProvider;
import com.hapbang.member.domain.User;
import com.hapbang.member.domain.WithdrawnRestorableException;

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

    @Test
    @DisplayName("업무 실패(이미 비밀번호로 가입된 이메일)는 그 오류 코드를 담은 로그인 실패로 바꾼다 (#499)")
    void loadUser_businessFailure() {
        given(googleUserService.loadUser(any())).willReturn(new DefaultOAuth2User(
                List.of(),
                Map.of("sub", "google-sub-1", "email", "tester1@example.com", "name", "tester1"),
                "sub"));
        given(memberFacade.oauthLogin(OAuthProvider.GOOGLE, "google-sub-1", "tester1@example.com", "tester1"))
                .willThrow(new MemberException(MemberErrorCode.EMAIL_REGISTERED_WITH_PASSWORD));

        MemberOAuth2UserService service = new MemberOAuth2UserService(memberFacade, googleUserService);

        assertThatThrownBy(() -> service.loadUser(null))
                .isInstanceOf(OAuth2AuthenticationException.class)
                .extracting(e -> ((OAuth2AuthenticationException) e).getError().getErrorCode())
                .isEqualTo("EMAIL_REGISTERED_WITH_PASSWORD");
    }

    @Test
    @DisplayName("복구할 수 있는 탈퇴 회원이면 복구 티켓을 담은 로그인 실패로 바꾼다 (#593)")
    void loadUser_restorable() {
        given(googleUserService.loadUser(any())).willReturn(new DefaultOAuth2User(
                List.of(),
                Map.of("sub", "google-sub-1", "email", "tester1@example.com", "name", "tester1"),
                "sub"));
        given(memberFacade.oauthLogin(OAuthProvider.GOOGLE, "google-sub-1", "tester1@example.com", "tester1"))
                .willThrow(new WithdrawnRestorableException("ticket-1", LocalDateTime.of(2026, 12, 3, 12, 0)));

        MemberOAuth2UserService service = new MemberOAuth2UserService(memberFacade, googleUserService);

        assertThatThrownBy(() -> service.loadUser(null))
                .isInstanceOfSatisfying(RestorableOAuth2AuthenticationException.class, e -> {
                    assertThat(e.getError().getErrorCode()).isEqualTo("WITHDRAWN_RESTORABLE");
                    assertThat(e.getRestoreTicket()).isEqualTo("ticket-1");
                });
    }
}
