package com.hapbang.appearance.out;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.hapbang.appearance.domain.AppearanceUser;
import com.hapbang.appearance.fixture.AppearanceUserFixture;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.testsupport.RepositoryTest;

@RepositoryTest
class AppearanceUserRepositoryTest {

    @Autowired
    AppearanceUserRepository appearanceUserRepository;

    @Test
    @DisplayName("원본 회원 ID 그대로 appearances.users에 저장하고 조회한다")
    void saveAndFind() {
        appearanceUserRepository.saveAndFlush(AppearanceUser.from(AppearanceUserFixture.influencer(7L)));

        AppearanceUser found = appearanceUserRepository.findById(7L).orElseThrow();

        assertThat(found.getId()).isEqualTo(7L);
        assertThat(found.getNickname()).isEqualTo("user7");
        assertThat(found.getRole()).isEqualTo(Role.INFLUENCER);
        assertThat(found.getCreatedAt()).isNotNull();
        assertThat(found.getUpdatedAt()).isNotNull();
    }
}
