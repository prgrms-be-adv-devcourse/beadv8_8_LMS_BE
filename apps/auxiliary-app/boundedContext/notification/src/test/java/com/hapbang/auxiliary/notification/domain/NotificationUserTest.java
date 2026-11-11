package com.hapbang.auxiliary.notification.domain;

import static org.assertj.core.api.Assertions.*;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;
import com.hapbang.shared.member.dto.UserDto;

/** 알림 회원 복제본(ND-018). 버전 규칙은 shared {@code ReplicaUser}를 그대로 따른다(test-spec NT-USR-01·02) */
class NotificationUserTest {

    @Test
    void 가입_정보로_회원_ID_닉네임_역할_상태_버전을_복제한다() {
        NotificationUser user = NotificationUser.create(new UserDto(7L, "게스트", Role.INFLUENCER, UserStatus.ACTIVE, 1L));

        assertThat(user.getId()).isEqualTo(7L);
        assertThat(user.getNickname()).isEqualTo("게스트");
        assertThat(user.getRole()).isEqualTo(Role.INFLUENCER);
        assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(user.getMemberVersion()).isEqualTo(1L);
    }

    @Test
    void 더_큰_버전의_변경만_반영한다() {
        NotificationUser user = NotificationUser.create(new UserDto(7L, "게스트", Role.INFLUENCER, UserStatus.ACTIVE, 3L));

        user.syncFrom(new UserDto(7L, "옛닉네임", Role.USER, UserStatus.SUSPENDED, 2L));
        assertThat(user.getNickname()).isEqualTo("게스트");
        assertThat(user.getMemberVersion()).isEqualTo(3L);

        user.syncFrom(new UserDto(7L, "새닉네임", Role.VERIFIED_INFLUENCER, UserStatus.WITHDRAWN, 4L));
        assertThat(user.getNickname()).isEqualTo("새닉네임");
        assertThat(user.getRole()).isEqualTo(Role.VERIFIED_INFLUENCER);
        assertThat(user.isWithdrawn()).isTrue();
        assertThat(user.getMemberVersion()).isEqualTo(4L);
    }

    @Test
    void 닉네임_역할_상태가_없는_정보는_복제할_수_없다() {
        assertThat(NotificationUser.isReplicable(new UserDto(7L, "게스트", Role.USER, UserStatus.ACTIVE, 1L))).isTrue();
        assertThat(NotificationUser.isReplicable(null)).isFalse();
        assertThat(NotificationUser.isReplicable(new UserDto(null, "게스트", Role.USER, UserStatus.ACTIVE, 1L))).isFalse();
        assertThat(NotificationUser.isReplicable(new UserDto(7L, " ", Role.USER, UserStatus.ACTIVE, 1L))).isFalse();
        assertThat(NotificationUser.isReplicable(new UserDto(7L, "게스트", null, UserStatus.ACTIVE, 1L))).isFalse();
        assertThat(NotificationUser.isReplicable(new UserDto(7L, "게스트", Role.USER, null, 1L))).isFalse();

        assertThatIllegalStateException()
                .isThrownBy(() -> NotificationUser.create(new UserDto(7L, null, Role.USER, UserStatus.ACTIVE, 1L)));
    }

    @Test
    void 복제본이_없는_회원의_파기는_파기_상태_복제본으로_만든다() {
        Instant at = Instant.parse("2026-11-10T00:00:00Z");

        NotificationUser masked = NotificationUser.createMasked(7L, at);

        assertThat(masked.getId()).isEqualTo(7L);
        assertThat(masked.getNickname()).isEqualTo("탈퇴회원#7");
        assertThat(masked.getStatus()).isEqualTo(UserStatus.WITHDRAWN);
        assertThat(masked.getRole()).isNull();
        assertThat(masked.getMaskedAt()).isEqualTo(at);
        assertThat(masked.isMasked()).isTrue();
    }

    @Test
    void 있는_복제본을_파기하면_닉네임과_상태를_바꾸고_처음_파기_시각을_남긴다() {
        NotificationUser user = NotificationUser.create(new UserDto(7L, "호스트", Role.INFLUENCER, UserStatus.ACTIVE, 3L));
        Instant first = Instant.parse("2026-11-10T00:00:00Z");

        user.markMasked(first);
        user.markMasked(first.plusSeconds(60));

        assertThat(user.getNickname()).isEqualTo("탈퇴회원#7");
        assertThat(user.isWithdrawn()).isTrue();
        assertThat(user.getMaskedAt()).isEqualTo(first);
    }

    @Test
    void 파기된_복제본은_뒤에_오는_변경으로_되살아나지_않는다() {
        NotificationUser user = NotificationUser.createMasked(7L, Instant.parse("2026-11-10T00:00:00Z"));

        user.syncFrom(new UserDto(7L, "원래닉네임", Role.INFLUENCER, UserStatus.ACTIVE, 9L));

        assertThat(user.getNickname()).isEqualTo("탈퇴회원#7");
        assertThat(user.isWithdrawn()).isTrue();
    }
}
