package com.hapbang.auxiliary.notification.domain;

import static org.assertj.core.api.Assertions.*;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.hapbang.auxiliary.notification.support.NotificationFixtures;
import com.hapbang.auxiliary.notification.support.TestNotificationTemplate;

/** 알림 한 건의 규칙: 값 형식(ND-020), 언급 회원 ID(ND-051), 읽음 멱등(ND-052), 파기 닉네임 교체(ND-036·ND-051) */
class NotificationTest {

    private static final Instant T = Instant.parse("2026-11-10T00:00:00Z");
    private final TestNotificationTemplate template = new TestNotificationTemplate();

    @Test
    void 값의_닉네임_회원을_언급_회원_ID로_담고_저장된_값으로_같은_문구를_다시_만들_수_있다() {
        Notification notification = Notification.create(NotificationFixtures.draft(1L, "s-1", 7L, "호스트", T),
                template::render);

        assertThat(notification.getMentionedUserIds()).containsExactly(7L);
        assertThat(notification.getPayload()).containsEntry("v", 1);
        assertThat(notification.getMessage()).isEqualTo("호스트님이 강의 s-1에 반응했습니다.");
        assertThat(template.render(notification.getPayload())).isEqualTo(notification.getMessage());
    }

    @Test
    void 값에_형식_버전이_없으면_만들_수_없다() {
        var draft = NotificationFixtures.draft(1L, "s-1", 7L, "호스트", T);
        draft.payload().remove("v");

        assertThatIllegalArgumentException().isThrownBy(() -> Notification.create(draft, template::render));
    }

    @Test
    void 이미_읽은_알림을_다시_읽어도_처음_읽은_시각이_남는다() {
        Notification notification = Notification.create(NotificationFixtures.draft(1L, "s-1", 7L, "호스트", T),
                template::render);
        Instant first = T.plusSeconds(10);

        notification.markRead(first);
        notification.markRead(first.plusSeconds(60));

        assertThat(notification.getReadAt()).isEqualTo(first);
    }

    @Test
    void 파기된_회원의_닉네임을_바꾸고_문구를_값으로_다시_만들며_두_번_해도_결과가_같다() {
        Notification notification = Notification.create(NotificationFixtures.draft(1L, "s-1", 7L, "호스트", T),
                template::render);

        assertThat(notification.maskNickname(7L, template::render)).isTrue();
        assertThat(notification.getMessage()).isEqualTo("탈퇴회원#7님이 강의 s-1에 반응했습니다.");
        assertThat(NotificationPayload.nicknames(notification.getPayload())).isEqualTo(Map.of("7", "탈퇴회원#7"));

        assertThat(notification.maskNickname(7L, template::render)).isFalse();
        assertThat(notification.getMessage()).isEqualTo("탈퇴회원#7님이 강의 s-1에 반응했습니다.");
    }

    @Test
    void 언급되지_않은_회원의_파기는_알림을_바꾸지_않는다() {
        Notification notification = Notification.create(NotificationFixtures.draft(1L, "s-1", 7L, "호스트", T),
                template::render);

        assertThat(notification.maskNickname(8L, template::render)).isFalse();
        assertThat(notification.getMessage()).isEqualTo("호스트님이 강의 s-1에 반응했습니다.");
    }
}
