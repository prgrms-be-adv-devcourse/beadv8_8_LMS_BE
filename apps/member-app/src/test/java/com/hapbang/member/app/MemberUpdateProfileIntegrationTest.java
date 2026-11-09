package com.hapbang.member.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

import com.hapbang.member.MemberIntegrationTest;
import com.hapbang.member.domain.MemberErrorCode;
import com.hapbang.member.domain.MemberException;
import com.hapbang.member.domain.User;
import com.hapbang.member.out.UserRepository;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.event.UserModifiedEvent;

@MemberIntegrationTest
@RecordApplicationEvents
class MemberUpdateProfileIntegrationTest {

    @Autowired
    MemberFacade memberFacade;

    @Autowired
    UserRepository userRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    ApplicationEvents events;

    @Test
    @DisplayName("닉네임을 바꾸면 DB에 저장되고 변경 이벤트가 한 번 발행된다")
    void updateProfile() {
        User user = userRepository.save(User.join("tester1@example.com", "tester1"));

        memberFacade.updateProfile(user.getId(), "새 닉네임", null, null);

        assertThat(userRepository.findById(user.getId()).orElseThrow().getNickname()).isEqualTo("새 닉네임");
        assertThat(events.stream(UserModifiedEvent.class))
                .singleElement()
                .satisfies(event -> assertThat(event.user().id()).isEqualTo(user.getId()));
    }

    @Test
    @DisplayName("닉네임·콘텐츠 분야·자기소개를 한 요청으로 바꾸면 version은 1 오르고 이벤트는 한 번, 오른 version과 새 값이 실린다 (#711)")
    void updateProfile_all() {
        User user = userRepository.save(influencer());
        Long before = user.getVersion();

        memberFacade.updateProfile(user.getId(), "새 닉네임", List.of("MAPLESTORY", "GAME"), "메이플 위주");

        User saved = userRepository.findById(user.getId()).orElseThrow();
        assertThat(saved.getVersion()).isEqualTo(before + 1);
        assertThat(saved.getContentCategories()).containsExactly("MAPLESTORY", "GAME");
        assertThat(events.stream(UserModifiedEvent.class))
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.user().version()).isEqualTo(before + 1);
                    assertThat(event.user().contentCategories()).containsExactly("MAPLESTORY", "GAME");
                    assertThat(event.user().bio()).isEqualTo("메이플 위주");
                });
    }

    @Test
    @DisplayName("목록에서 꺼진 분야는 이미 고른 회원이 다시 보내면 그대로 두고, 새로 고르면 INVALID_CONTENT_CATEGORY (Q50)")
    void updateProfile_turnedOffCategory() {
        User user = userRepository.save(influencer());
        memberFacade.updateProfile(user.getId(), null, List.of("GAME"), null);
        jdbcTemplate.update("update members.content_category set active = false where code in ('GAME', 'BEAUTY')");

        memberFacade.updateProfile(user.getId(), null, List.of("GAME", "MUKBANG"), null);

        assertThat(userRepository.findById(user.getId()).orElseThrow().getContentCategories())
                .containsExactly("GAME", "MUKBANG");
        assertThatThrownBy(() -> memberFacade.updateProfile(user.getId(), null, List.of("GAME", "BEAUTY"), null))
                .isInstanceOf(MemberException.class)
                .extracting("errorCode").isEqualTo(MemberErrorCode.INVALID_CONTENT_CATEGORY);
        assertThat(userRepository.findById(user.getId()).orElseThrow().getContentCategories())
                .containsExactly("GAME", "MUKBANG");
    }

    private static User influencer() {
        User user = User.join("tester1@example.com", "tester1");
        user.changeRole(Role.INFLUENCER);
        return user;
    }
}
