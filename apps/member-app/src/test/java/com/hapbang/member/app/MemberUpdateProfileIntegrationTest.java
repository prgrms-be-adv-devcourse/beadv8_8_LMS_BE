package com.hapbang.member.app;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

import com.hapbang.member.MemberIntegrationTest;
import com.hapbang.member.domain.User;
import com.hapbang.member.out.UserRepository;
import com.hapbang.shared.member.event.UserModifiedEvent;

@MemberIntegrationTest
@RecordApplicationEvents
class MemberUpdateProfileIntegrationTest {

    @Autowired
    MemberFacade memberFacade;

    @Autowired
    UserRepository userRepository;

    @Autowired
    ApplicationEvents events;

    @Test
    @DisplayName("닉네임을 바꾸면 DB에 저장되고 변경 이벤트가 한 번 발행된다")
    void updateProfile() {
        User user = userRepository.save(User.join("tester1@example.com", "tester1"));

        memberFacade.updateProfile(user.getId(), "새 닉네임");

        assertThat(userRepository.findById(user.getId()).orElseThrow().getNickname()).isEqualTo("새 닉네임");
        assertThat(events.stream(UserModifiedEvent.class))
                .singleElement()
                .satisfies(event -> assertThat(event.user().id()).isEqualTo(user.getId()));
    }
}
