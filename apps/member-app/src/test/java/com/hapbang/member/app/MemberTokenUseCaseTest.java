package com.hapbang.member.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.Optional;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.hapbang.member.domain.MemberErrorCode;
import com.hapbang.member.domain.MemberException;
import com.hapbang.member.domain.User;
import com.hapbang.member.fixture.MemberFixture;
import com.hapbang.member.out.RefreshTokenStore;
import com.hapbang.member.out.RotationResult;
import com.hapbang.member.out.UserRepository;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;

@ExtendWith(MockitoExtension.class)
class MemberTokenUseCaseTest {

    @Mock
    AccessTokenIssuer accessTokenIssuer;

    @Mock
    RefreshTokenStore refreshTokenStore;

    @Mock
    UserRepository userRepository;

    @InjectMocks
    MemberTokenUseCase useCase;

    @Test
    @DisplayName("로그인하면 access token과 refresh token을 주고, 저장소에는 refresh token 원문이 아니라 해시를 넘긴다")
    void issue() {
        given(accessTokenIssuer.issue(42L, Role.USER)).willReturn("access");

        TokenPair tokens = useCase.issue(42L, Role.USER);

        assertThat(tokens.accessToken()).isEqualTo("access");
        assertThat(tokens.refreshToken()).isNotBlank();
        ArgumentCaptor<String> savedHash = ArgumentCaptor.forClass(String.class);
        verify(refreshTokenStore).save(eq(42L), savedHash.capture());
        assertThat(savedHash.getValue())
                .isNotEqualTo(tokens.refreshToken())
                .isEqualTo(MemberTokenUseCase.hash(tokens.refreshToken()));
    }

    @Test
    @DisplayName("로그인할 때마다 다른 refresh token을 준다")
    void issue_uniqueRefreshToken() {
        assertThat(useCase.issue(42L, Role.USER).refreshToken())
                .isNotEqualTo(useCase.issue(42L, Role.USER).refreshToken());
    }

    @Test
    @DisplayName("재발급하면 새 refresh token으로 회전하고, 토큰의 옛 역할이 아니라 회원의 지금 역할로 access token을 준다")
    void refresh_usesCurrentRole() {
        User upgraded = member(42L, Role.INFLUENCER, UserStatus.ACTIVE);
        given(refreshTokenStore.rotate(eq(MemberTokenUseCase.hash("old")), anyString(), any()))
                .willReturn(new RotationResult.Rotated(42L));
        given(userRepository.findById(42L)).willReturn(Optional.of(upgraded));
        given(accessTokenIssuer.issue(42L, Role.INFLUENCER)).willReturn("access-influencer");

        TokenPair tokens = useCase.refresh("old");

        assertThat(tokens.accessToken()).isEqualTo("access-influencer");
        ArgumentCaptor<String> newHash = ArgumentCaptor.forClass(String.class);
        verify(refreshTokenStore).rotate(eq(MemberTokenUseCase.hash("old")), newHash.capture(), any());
        assertThat(newHash.getValue()).isEqualTo(MemberTokenUseCase.hash(tokens.refreshToken()));
        assertThat(tokens.refreshToken()).isNotEqualTo("old");
    }

    @Test
    @DisplayName("없거나 만료·폐기된 refresh token이면 INVALID_REFRESH_TOKEN")
    void refresh_notFound() {
        given(refreshTokenStore.rotate(anyString(), anyString(), any())).willReturn(new RotationResult.NotFound());

        assertInvalidRefreshToken(() -> useCase.refresh("unknown"));
        verify(refreshTokenStore, never()).revokeAll(any());
    }

    @Test
    @DisplayName("재사용(탈취 의심)이면 그 회원의 refresh token을 모두 폐기하고 INVALID_REFRESH_TOKEN")
    void refresh_reused() {
        given(refreshTokenStore.rotate(anyString(), anyString(), any())).willReturn(new RotationResult.Reused(42L));

        assertInvalidRefreshToken(() -> useCase.refresh("stolen"));
        verify(refreshTokenStore).revokeAll(42L);
        verifyNoInteractions(accessTokenIssuer);
    }

    @Test
    @DisplayName("탈퇴·정지 회원이면 refresh token을 모두 폐기하고 INVALID_REFRESH_TOKEN")
    void refresh_inactiveMember() {
        given(refreshTokenStore.rotate(anyString(), anyString(), any())).willReturn(new RotationResult.Rotated(42L));
        given(userRepository.findById(42L)).willReturn(Optional.of(member(42L, Role.USER, UserStatus.WITHDRAWN)));

        assertInvalidRefreshToken(() -> useCase.refresh("old"));
        verify(refreshTokenStore).revokeAll(42L);
        verifyNoInteractions(accessTokenIssuer);
    }

    @Test
    @DisplayName("회원이 없으면 refresh token을 모두 폐기하고 INVALID_REFRESH_TOKEN")
    void refresh_missingMember() {
        given(refreshTokenStore.rotate(anyString(), anyString(), any())).willReturn(new RotationResult.Rotated(42L));
        given(userRepository.findById(42L)).willReturn(Optional.empty());

        assertInvalidRefreshToken(() -> useCase.refresh("old"));
        verify(refreshTokenStore).revokeAll(42L);
    }

    @Test
    @DisplayName("refresh token 쿠키가 없으면 저장소를 부르지 않고 INVALID_REFRESH_TOKEN")
    void refresh_blank() {
        assertInvalidRefreshToken(() -> useCase.refresh(null));
        assertInvalidRefreshToken(() -> useCase.refresh(" "));
        verifyNoInteractions(refreshTokenStore);
    }

    @Test
    @DisplayName("로그아웃하면 이 기기의 refresh token만 해시로 폐기한다")
    void logout() {
        useCase.logout("mine");

        verify(refreshTokenStore).revoke(MemberTokenUseCase.hash("mine"));
        verify(refreshTokenStore, never()).revokeAll(any());
    }

    @Test
    @DisplayName("refresh token 없이 로그아웃해도 실패하지 않는다")
    void logout_withoutToken() {
        useCase.logout(null);

        verifyNoInteractions(refreshTokenStore);
    }

    @Test
    @DisplayName("토큰 쌍을 문자열로 바꿔도 토큰 값은 드러나지 않는다")
    void tokenPair_toString() {
        assertThat(new TokenPair("access-value", "refresh-value").toString())
                .doesNotContain("access-value", "refresh-value");
    }

    private static User member(Long id, Role role, UserStatus status) {
        User user = MemberFixture.member(role, status);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    private static void assertInvalidRefreshToken(ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOf(MemberException.class)
                .extracting("errorCode")
                .isEqualTo(MemberErrorCode.INVALID_REFRESH_TOKEN);
    }
}
