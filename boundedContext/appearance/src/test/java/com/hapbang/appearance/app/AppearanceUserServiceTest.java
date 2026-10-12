package com.hapbang.appearance.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.hapbang.appearance.domain.AppearanceErrorCode;
import com.hapbang.appearance.domain.AppearanceException;
import com.hapbang.appearance.domain.AppearanceUser;
import com.hapbang.appearance.fixture.AppearanceUserFixture;
import com.hapbang.appearance.out.AppearanceUserRepository;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;
import com.hapbang.testsupport.IntegrationTest;

@IntegrationTest
class AppearanceUserServiceTest {

    @Autowired
    AppearanceUserService appearanceUserService;

    @Autowired
    AppearanceUserRepository appearanceUserRepository;

    @Test
    @DisplayName("처음 받은 회원 정보면 출연 회원을 만든다")
    void syncCreates() {
        appearanceUserService.sync(AppearanceUserFixture.influencer(1L));

        assertThat(appearanceUserRepository.findById(1L)).isPresent();
    }

    @Test
    @DisplayName("이미 있는 회원이면 새로 만들지 않고 정보만 갱신한다")
    void syncUpdates() {
        appearanceUserService.sync(AppearanceUserFixture.normalUser(1L));

        appearanceUserService.sync(AppearanceUserFixture.user(1L, Role.INFLUENCER, UserStatus.ACTIVE));

        assertThat(appearanceUserRepository.count()).isEqualTo(1);
        assertThat(appearanceUserRepository.findById(1L).orElseThrow().getRole()).isEqualTo(Role.INFLUENCER);
    }

    @Test
    @DisplayName("참여 자격이 있는 회원이면 출연 회원을 돌려준다")
    void getParticipant() {
        appearanceUserService.sync(AppearanceUserFixture.influencer(1L));

        AppearanceUser participant = appearanceUserService.getParticipant(1L);

        assertThat(participant.getId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("출연 회원 정보가 없으면 APPEARANCE_USER_NOT_FOUND")
    void getParticipantNotFound() {
        assertThatThrownBy(() -> appearanceUserService.getParticipant(999L))
                .isInstanceOf(AppearanceException.class)
                .extracting("errorCode").isEqualTo(AppearanceErrorCode.APPEARANCE_USER_NOT_FOUND);
    }

    @Test
    @DisplayName("인플루언서가 아니면 NOT_INFLUENCER")
    void getParticipantNotInfluencer() {
        appearanceUserService.sync(AppearanceUserFixture.normalUser(1L));

        assertThatThrownBy(() -> appearanceUserService.getParticipant(1L))
                .isInstanceOf(AppearanceException.class)
                .extracting("errorCode").isEqualTo(AppearanceErrorCode.NOT_INFLUENCER);
    }
}
