package com.hapbang.auxiliary.notification.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.hapbang.auxiliary.notification.domain.NotificationUser;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;
import com.hapbang.shared.member.dto.UserDto;
import org.junit.jupiter.api.Test;

/** 받는 사람 정책: 탈퇴(ND-018)·정지(ND-072)·관리자(ND-003)는 받지 않는다 (test-spec NT-USR-03·05, NT-GEN-07) */
class ReceiverPolicyTest {

    private final ReceiverPolicy policy = new ReceiverPolicy();

    @Test
    void 정상_유저와_인플루언서는_받는다() {
        assertThat(policy.canReceive(user(Role.USER, UserStatus.ACTIVE))).isTrue();
        assertThat(policy.canReceive(user(Role.INFLUENCER, UserStatus.ACTIVE))).isTrue();
        assertThat(policy.canReceive(user(Role.VERIFIED_INFLUENCER, UserStatus.ACTIVE))).isTrue();
    }

    @Test
    void 탈퇴한_회원은_받지_않는다() {
        assertThat(policy.canReceive(user(Role.USER, UserStatus.WITHDRAWN))).isFalse();
    }

    @Test
    void 정지된_회원은_받지_않는다() {
        assertThat(policy.canReceive(user(Role.INFLUENCER, UserStatus.SUSPENDED))).isFalse();
    }

    @Test
    void 관리자는_받지_않는다() {
        assertThat(policy.canReceive(user(Role.ADMIN, UserStatus.ACTIVE))).isFalse();
    }

    private static NotificationUser user(Role role, UserStatus status) {
        return NotificationUser.create(new UserDto(7L, "회원", role, status, 1L));
    }
}
