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
import com.hapbang.appearance.support.AppearanceIntegrationTest;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;

@AppearanceIntegrationTest
class AppearanceUserFacadeTest {

    @Autowired
    AppearanceUserFacade appearanceUserFacade;

    @Autowired
    AppearanceUserSupport appearanceUserSupport;

    @Autowired
    AppearanceUserRepository appearanceUserRepository;

    @Test
    @DisplayName("가입 정보로 출연 회원을 만든다")
    void createIfAbsentCreates() {
        appearanceUserFacade.syncJoined(AppearanceUserFixture.influencer(1L));

        assertThat(appearanceUserRepository.findById(1L)).isPresent();
    }

    @Test
    @DisplayName("이미 있는 회원이면 가입 정보를 무시한다 (반영된 변경 정보를 덮어쓰지 않음)")
    void createIfAbsentIgnoresExisting() {
        appearanceUserFacade.syncJoined(AppearanceUserFixture.normalUser(1L));
        appearanceUserFacade.syncModified(AppearanceUserFixture.user(1L, Role.INFLUENCER, UserStatus.ACTIVE));

        appearanceUserFacade.syncJoined(AppearanceUserFixture.normalUser(1L));

        assertThat(appearanceUserRepository.count()).isEqualTo(1);
        assertThat(appearanceUserRepository.findById(1L).orElseThrow().getRole()).isEqualTo(Role.INFLUENCER);
    }

    @Test
    @DisplayName("있는 회원이면 변경 정보로 갱신한다")
    void updateUpdates() {
        appearanceUserFacade.syncJoined(AppearanceUserFixture.normalUser(1L));

        appearanceUserFacade.syncModified(AppearanceUserFixture.user(1L, Role.INFLUENCER, UserStatus.ACTIVE));

        assertThat(appearanceUserRepository.count()).isEqualTo(1);
        assertThat(appearanceUserRepository.findById(1L).orElseThrow().getRole()).isEqualTo(Role.INFLUENCER);
    }

    @Test
    @DisplayName("없는 회원의 변경 정보면 APPEARANCE_USER_NOT_FOUND, 출연 회원을 만들지 않는다")
    void updateNotFound() {
        assertThatThrownBy(() -> appearanceUserFacade.syncModified(AppearanceUserFixture.influencer(1L)))
                .isInstanceOf(AppearanceException.class)
                .extracting("errorCode").isEqualTo(AppearanceErrorCode.APPEARANCE_USER_NOT_FOUND);
        assertThat(appearanceUserRepository.findById(1L)).isEmpty();
    }

    @Test
    @DisplayName("참여 자격이 있는 회원이면 출연 회원을 돌려준다")
    void getParticipant() {
        appearanceUserFacade.syncJoined(AppearanceUserFixture.influencer(1L));

        AppearanceUser participant = appearanceUserSupport.getParticipant(1L);

        assertThat(participant.getId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("출연 회원 정보가 없으면 APPEARANCE_USER_NOT_FOUND")
    void getParticipantNotFound() {
        assertThatThrownBy(() -> appearanceUserSupport.getParticipant(999L))
                .isInstanceOf(AppearanceException.class)
                .extracting("errorCode").isEqualTo(AppearanceErrorCode.APPEARANCE_USER_NOT_FOUND);
    }

    @Test
    @DisplayName("인플루언서가 아니면 NOT_INFLUENCER")
    void getParticipantNotInfluencer() {
        appearanceUserFacade.syncJoined(AppearanceUserFixture.normalUser(1L));

        assertThatThrownBy(() -> appearanceUserSupport.getParticipant(1L))
                .isInstanceOf(AppearanceException.class)
                .extracting("errorCode").isEqualTo(AppearanceErrorCode.NOT_INFLUENCER);
    }

    @Test
    @DisplayName("참여하던 인플루언서가 정지 상태로 갱신되면 APPEARANCE_USER_NOT_ACTIVE")
    void getParticipantAfterSuspended() {
        appearanceUserFacade.syncJoined(AppearanceUserFixture.influencer(1L));

        appearanceUserFacade.syncModified(AppearanceUserFixture.user(1L, Role.INFLUENCER, UserStatus.SUSPENDED));

        assertThatThrownBy(() -> appearanceUserSupport.getParticipant(1L))
                .isInstanceOf(AppearanceException.class)
                .extracting("errorCode").isEqualTo(AppearanceErrorCode.APPEARANCE_USER_NOT_ACTIVE);
    }
}
