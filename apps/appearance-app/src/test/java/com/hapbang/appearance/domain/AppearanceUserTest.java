package com.hapbang.appearance.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.hapbang.appearance.fixture.AppearanceUserFixture;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;
import com.hapbang.shared.member.dto.UserDto;

class AppearanceUserTest {

    @ParameterizedTest(name = "{0}, {1} → 참여 가능")
    @CsvSource({"INFLUENCER, ACTIVE", "VERIFIED_INFLUENCER, ACTIVE"})
    @DisplayName("정상 상태의 인플루언서 이상은 출연 거래에 참여할 수 있다")
    void canAppear(Role role, UserStatus status) {
        AppearanceUser user = AppearanceUser.from(AppearanceUserFixture.user(1L, role, status));

        assertThatCode(user::validateCanAppear).doesNotThrowAnyException();
    }

    @ParameterizedTest(name = "{0} → NOT_INFLUENCER")
    @CsvSource({"USER", "ADMIN"})
    @DisplayName("인플루언서가 아니면 참여할 수 없다 (일반 유저, 관리자)")
    void notInfluencer(Role role) {
        AppearanceUser user = AppearanceUser.from(AppearanceUserFixture.user(1L, role, UserStatus.ACTIVE));

        assertThatThrownBy(user::validateCanAppear)
                .isInstanceOf(AppearanceException.class)
                .extracting("errorCode").isEqualTo(AppearanceErrorCode.NOT_INFLUENCER);
    }

    @ParameterizedTest(name = "{0} → APPEARANCE_USER_NOT_ACTIVE")
    @CsvSource({"WITHDRAWN", "SUSPENDED"})
    @DisplayName("정상 상태가 아니면 인플루언서여도 참여할 수 없다 (탈퇴, 정지)")
    void notActive(UserStatus status) {
        AppearanceUser user = AppearanceUser.from(AppearanceUserFixture.user(1L, Role.INFLUENCER, status));

        assertThatThrownBy(user::validateCanAppear)
                .isInstanceOf(AppearanceException.class)
                .extracting("errorCode").isEqualTo(AppearanceErrorCode.APPEARANCE_USER_NOT_ACTIVE);
    }

    @Test
    @DisplayName("원본 회원 정보가 바뀌면 그대로 반영된다")
    void syncFrom() {
        AppearanceUser user = AppearanceUser.from(AppearanceUserFixture.normalUser(1L));

        user.syncFrom(new UserDto(1L, "renamed", Role.INFLUENCER, UserStatus.ACTIVE));

        assertThat(user.getNickname()).isEqualTo("renamed");
        assertThat(user.getRole()).isEqualTo(Role.INFLUENCER);
    }
}
