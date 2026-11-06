package com.hapbang.member.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

import com.hapbang.member.MemberIntegrationTest;
import com.hapbang.member.app.dto.TestInfluencerResponse;
import com.hapbang.member.domain.MemberErrorCode;
import com.hapbang.member.domain.MemberException;
import com.hapbang.member.domain.User;
import com.hapbang.member.fixture.MemberFixture;
import com.hapbang.member.out.UserRepository;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;
import com.hapbang.shared.member.event.UserModifiedEvent;

/** 관리자 인플루언서 승급 (#724): 부하 시험 이메일이 아닌 일반 회원도 승급되고, 역할·상태·권한 검사는 그대로인지 실제 DB로 확인한다 */
@MemberIntegrationTest
@RecordApplicationEvents
@WithMockUser(roles = "ADMIN")
class MemberTestAccountIntegrationTest {

    private static final Long ADMIN_ID = 99L;

    @Autowired
    MemberTestAccountFacade memberTestAccountFacade;

    @Autowired
    UserRepository userRepository;

    @Autowired
    ApplicationEvents events;

    @Test
    @DisplayName("일반 이메일의 USER도 인플루언서로 바뀌고 변경 이벤트가 1번 발행된다")
    void promote_anyUser() {
        User user = userRepository.save(MemberFixture.user());

        TestInfluencerResponse response = memberTestAccountFacade.promote(ADMIN_ID, user.getId());

        assertThat(response.changed()).isTrue();
        assertThat(response.role()).isEqualTo(Role.INFLUENCER);
        assertThat(userRepository.findById(user.getId()).orElseThrow().getRole()).isEqualTo(Role.INFLUENCER);
        assertThat(events.stream(UserModifiedEvent.class))
                .singleElement()
                .satisfies(event -> assertThat(event.user().role()).isEqualTo(Role.INFLUENCER));
    }

    @Test
    @DisplayName("이미 인플루언서면 changed=false이고 이벤트를 다시 발행하지 않는다")
    void promote_alreadyInfluencer() {
        User influencer = userRepository.save(MemberFixture.member(Role.INFLUENCER));

        TestInfluencerResponse response = memberTestAccountFacade.promote(ADMIN_ID, influencer.getId());

        assertThat(response.changed()).isFalse();
        assertThat(response.role()).isEqualTo(Role.INFLUENCER);
        assertThat(events.stream(UserModifiedEvent.class)).isEmpty();
    }

    @Test
    @DisplayName("인증된 인플루언서는 409이고 역할은 그대로다")
    void promote_verifiedInfluencer() {
        assertRoleNotAllowed(Role.VERIFIED_INFLUENCER);
    }

    @Test
    @DisplayName("관리자 회원은 409이고 역할은 그대로다 (관리자를 인플루언서로 바꾸지 않는다)")
    void promote_admin() {
        assertRoleNotAllowed(Role.ADMIN);
    }

    private void assertRoleNotAllowed(Role role) {
        User member = userRepository.save(MemberFixture.member(role));

        assertThatThrownBy(() -> memberTestAccountFacade.promote(ADMIN_ID, member.getId()))
                .isInstanceOf(MemberException.class)
                .extracting("errorCode").isEqualTo(MemberErrorCode.TEST_ACCOUNT_ROLE_NOT_ALLOWED);
        assertThat(userRepository.findById(member.getId()).orElseThrow().getRole()).isEqualTo(role);
        assertThat(events.stream(UserModifiedEvent.class)).isEmpty();
    }

    @Test
    @DisplayName("탈퇴 회원·없는 회원은 404")
    void promote_inactiveOrMissing() {
        User withdrawn = userRepository.save(MemberFixture.member(Role.USER, UserStatus.WITHDRAWN));

        assertThatThrownBy(() -> memberTestAccountFacade.promote(ADMIN_ID, withdrawn.getId()))
                .isInstanceOf(MemberException.class)
                .extracting("errorCode").isEqualTo(MemberErrorCode.MEMBER_NOT_FOUND);
        assertThatThrownBy(() -> memberTestAccountFacade.promote(ADMIN_ID, 999_999L))
                .isInstanceOf(MemberException.class)
                .extracting("errorCode").isEqualTo(MemberErrorCode.MEMBER_NOT_FOUND);
        assertThat(userRepository.findById(withdrawn.getId()).orElseThrow().getRole()).isEqualTo(Role.USER);
    }

    @Test
    @WithMockUser(roles = "INFLUENCER")
    @DisplayName("관리자가 아니면 거절된다")
    void promote_notAdmin() {
        User user = userRepository.save(MemberFixture.user());

        assertThatThrownBy(() -> memberTestAccountFacade.promote(ADMIN_ID, user.getId()))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(userRepository.findById(user.getId()).orElseThrow().getRole()).isEqualTo(Role.USER);
    }
}
