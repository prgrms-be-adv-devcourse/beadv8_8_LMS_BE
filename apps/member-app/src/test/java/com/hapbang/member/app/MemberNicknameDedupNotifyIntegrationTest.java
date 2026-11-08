package com.hapbang.member.app;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

import com.hapbang.member.MemberIntegrationTest;
import com.hapbang.member.domain.User;
import com.hapbang.member.out.NicknameDedupRepository;
import com.hapbang.member.out.UserRepository;
import com.hapbang.shared.member.event.UserModifiedEvent;

/** 마이그레이션이 바꾼 중복 닉네임 알리기 (#646). 기록 행은 마이그레이션이 만드는 것처럼 SQL로 넣는다 */
@MemberIntegrationTest
@RecordApplicationEvents
class MemberNicknameDedupNotifyIntegrationTest {

    @Autowired
    MemberFacade memberFacade;

    @Autowired
    UserRepository userRepository;

    @Autowired
    NicknameDedupRepository nicknameDedupRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    ApplicationEvents events;

    @Test
    @DisplayName("아직 알리지 않은 회원의 지금 상태로 UserModifiedEvent를 보내고, 다시 해도 또 보내지 않는다")
    void notifiesOnce() {
        User user = userRepository.save(User.join("dedup@example.com", "tester2222"));
        jdbcTemplate.update("""
                insert into members.nickname_dedup (user_id, old_nickname, new_nickname)
                values (?, 'tester', 'tester2222')""", user.getId());

        assertThat(memberFacade.notifyNicknameDedup()).isEqualTo(1);
        assertThat(memberFacade.notifyNicknameDedup()).isZero();

        assertThat(events.stream(UserModifiedEvent.class))
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.user().id()).isEqualTo(user.getId());
                    assertThat(event.user().nickname()).isEqualTo("tester2222");
                });
        assertThat(nicknameDedupRepository.findById(user.getId()).orElseThrow().getNotifiedAt()).isNotNull();
    }

    @Test
    @DisplayName("알릴 기록이 없으면 아무것도 보내지 않는다")
    void nothingPending() {
        assertThat(memberFacade.notifyNicknameDedup()).isZero();
        assertThat(events.stream(UserModifiedEvent.class)).isEmpty();
    }
}
