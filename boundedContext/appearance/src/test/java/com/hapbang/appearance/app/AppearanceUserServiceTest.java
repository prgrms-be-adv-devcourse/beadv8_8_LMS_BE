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
    @DisplayName("가입 정보로 출연 회원을 만든다")
    void createIfAbsentCreates() {
        appearanceUserService.createIfAbsent(AppearanceUserFixture.influencer(1L));

        assertThat(appearanceUserRepository.findById(1L)).isPresent();
    }

    @Test
    @DisplayName("이미 있는 회원이면 가입 정보를 무시한다 (반영된 변경 정보를 덮어쓰지 않음)")
    void createIfAbsentIgnoresExisting() {
        appearanceUserService.createIfAbsent(AppearanceUserFixture.normalUser(1L));
        appearanceUserService.update(AppearanceUserFixture.user(1L, Role.INFLUENCER, UserStatus.ACTIVE));

        appearanceUserService.createIfAbsent(AppearanceUserFixture.normalUser(1L));

        assertThat(appearanceUserRepository.count()).isEqualTo(1);
        assertThat(appearanceUserRepository.findById(1L).orElseThrow().getRole()).isEqualTo(Role.INFLUENCER);
    }

    @Test
    @DisplayName("있는 회원이면 변경 정보로 갱신한다")
    void updateUpdates() {
        appearanceUserService.createIfAbsent(AppearanceUserFixture.normalUser(1L));

        appearanceUserService.update(AppearanceUserFixture.user(1L, Role.INFLUENCER, UserStatus.ACTIVE));

        assertThat(appearanceUserRepository.count()).isEqualTo(1);
        assertThat(appearanceUserRepository.findById(1L).orElseThrow().getRole()).isEqualTo(Role.INFLUENCER);
    }

    @Test
    @DisplayName("없는 회원의 변경 정보면 APPEARANCE_USER_NOT_FOUND, 출연 회원을 만들지 않는다")
    void updateNotFound() {
        assertThatThrownBy(() -> appearanceUserService.update(AppearanceUserFixture.influencer(1L)))
                .isInstanceOf(AppearanceException.class)
                .extracting("errorCode").isEqualTo(AppearanceErrorCode.APPEARANCE_USER_NOT_FOUND);
        assertThat(appearanceUserRepository.findById(1L)).isEmpty();
    }

    @Test
    @DisplayName("참여 자격이 있는 회원이면 출연 회원을 돌려준다")
    void getParticipant() {
        appearanceUserService.createIfAbsent(AppearanceUserFixture.influencer(1L));

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
        appearanceUserService.createIfAbsent(AppearanceUserFixture.normalUser(1L));

        assertThatThrownBy(() -> appearanceUserService.getParticipant(1L))
                .isInstanceOf(AppearanceException.class)
                .extracting("errorCode").isEqualTo(AppearanceErrorCode.NOT_INFLUENCER);
    }

    @Test
    @DisplayName("참여하던 인플루언서가 정지 상태로 갱신되면 APPEARANCE_USER_NOT_ACTIVE")
    void getParticipantAfterSuspended() {
        appearanceUserService.createIfAbsent(AppearanceUserFixture.influencer(1L));

        appearanceUserService.update(AppearanceUserFixture.user(1L, Role.INFLUENCER, UserStatus.SUSPENDED));

        assertThatThrownBy(() -> appearanceUserService.getParticipant(1L))
                .isInstanceOf(AppearanceException.class)
                .extracting("errorCode").isEqualTo(AppearanceErrorCode.APPEARANCE_USER_NOT_ACTIVE);
    }
}
