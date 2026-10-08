package com.hapbang.shared.member.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class RoleTest {

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"INFLUENCER", "VERIFIED_INFLUENCER"})
    @DisplayName("인플루언서와 인증된 인플루언서는 인플루언서 이상이다")
    void isInfluencerOrAbove_true(Role role) {
        assertThat(role.isInfluencerOrAbove()).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"USER", "ADMIN"})
    @DisplayName("유저와 관리자는 인플루언서 이상이 아니다")
    void isInfluencerOrAbove_false(Role role) {
        assertThat(role.isInfluencerOrAbove()).isFalse();
    }
}
