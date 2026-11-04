package com.hapbang.shared.member.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class UserStatusTest {

    @Test
    @DisplayName("정상 상태만 이용 가능하다")
    void isActive_true() {
        assertThat(UserStatus.ACTIVE.isActive()).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = UserStatus.class, names = {"SUSPENDED", "WITHDRAWN"})
    @DisplayName("정지·탈퇴 상태는 이용할 수 없다")
    void isActive_false(UserStatus status) {
        assertThat(status.isActive()).isFalse();
    }

    @Test
    @DisplayName("탈퇴 상태만 탈퇴로 판단한다")
    void isWithdrawn_true() {
        assertThat(UserStatus.WITHDRAWN.isWithdrawn()).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = UserStatus.class, names = {"ACTIVE", "SUSPENDED"})
    @DisplayName("정상·정지 상태는 탈퇴가 아니다")
    void isWithdrawn_false(UserStatus status) {
        assertThat(status.isWithdrawn()).isFalse();
    }
}
