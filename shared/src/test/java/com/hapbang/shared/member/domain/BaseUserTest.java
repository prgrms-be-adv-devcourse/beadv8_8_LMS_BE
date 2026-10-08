package com.hapbang.shared.member.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BaseUserTest {

    /** 테스트용 하위 클래스 */
    static class TestUser extends BaseUser {
        TestUser(String nickname, Role role, UserStatus status) {
            super(nickname, role, status);
        }

        void change(String nickname, Role role, UserStatus status) {
            changeProfile(nickname, role, status);
        }
    }

    @Test
    @DisplayName("정상 상태의 인증된 인플루언서는 이용 가능하고 인플루언서 이상이다")
    void judge_activeVerifiedInfluencer() {
        TestUser user = new TestUser("tester1", Role.VERIFIED_INFLUENCER, UserStatus.ACTIVE);

        assertThat(user.isActive()).isTrue();
        assertThat(user.isWithdrawn()).isFalse();
        assertThat(user.isInfluencerOrAbove()).isTrue();
    }

    @Test
    @DisplayName("탈퇴한 유저는 이용할 수 없고 인플루언서 이상이 아니다")
    void judge_withdrawnUser() {
        TestUser user = new TestUser("tester1", Role.USER, UserStatus.WITHDRAWN);

        assertThat(user.isActive()).isFalse();
        assertThat(user.isWithdrawn()).isTrue();
        assertThat(user.isInfluencerOrAbove()).isFalse();
    }

    @Test
    @DisplayName("프로필을 바꾸면 닉네임·역할·상태가 모두 바뀐다")
    void changeProfile() {
        TestUser user = new TestUser("tester1", Role.USER, UserStatus.ACTIVE);

        user.change("tester2", Role.INFLUENCER, UserStatus.SUSPENDED);

        assertThat(user.getNickname()).isEqualTo("tester2");
        assertThat(user.getRole()).isEqualTo(Role.INFLUENCER);
        assertThat(user.getStatus()).isEqualTo(UserStatus.SUSPENDED);
    }
}
