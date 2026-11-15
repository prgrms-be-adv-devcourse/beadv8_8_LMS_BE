package com.hapbang.auxiliary.notification.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.hapbang.auxiliary.notification.domain.NotificationDraft;
import com.hapbang.auxiliary.notification.domain.NotificationPayload;
import com.hapbang.shared.member.event.InfluencerChannelApprovedEvent;
import com.hapbang.shared.member.event.InfluencerChannelRejectedEvent;
import com.hapbang.shared.member.event.InfluencerUpgradeRequestRejectedEvent;

/**
 * N7 회원 3종의 알림 초안과 문구(#915, NT-GEN-22). 받는 사람·원본 대상 ID(ND-119)·이동 대상 없음(ND-120)·발생 시각·문구(ND-121,
 * 알림 종류 0.10)·필수 값 검증.
 */
class MemberReviewAlertsTest {

    private static final long USER = 7L;
    private static final Instant AT = Instant.parse("2026-11-12T02:00:00Z");

    @Test
    void 채널_인증_승인은_채널과_심사_회차로_만들고_이동_대상이_없다() {
        NotificationDraft draft = MemberReviewAlerts.channelApproved(
                new InfluencerChannelApprovedEvent(USER, 41L, 2, false, AT));

        assertThat(draft.receiverUserId()).isEqualTo(USER);
        assertThat(draft.kind()).isEqualTo("INFLUENCER_CHANNEL_APPROVED");
        assertThat(draft.sourceEventType()).isEqualTo("InfluencerChannelApprovedEvent");
        assertThat(draft.sourceTargetId()).isEqualTo("41:2");
        assertThat(draft.targetType()).isNull();
        assertThat(draft.targetId()).isNull();
        assertThat(draft.occurredAt()).isEqualTo(AT);
        // 문구에 쓰는 값만 남긴다(승인 방식은 문구에 쓰지 않는다)
        assertThat(draft.payload()).containsOnlyKeys(NotificationPayload.VERSION);
        assertThat(draft.payload()).containsEntry(NotificationPayload.VERSION, 1);
        assertThat(NotificationPayload.nicknames(draft.payload())).isEmpty();
        assertThat(render(draft)).isEqualTo("채널 인증이 승인되었습니다.");
    }

    @Test
    void 자동_승인과_관리자_승인은_같은_문구다() {
        NotificationDraft auto = MemberReviewAlerts.channelApproved(
                new InfluencerChannelApprovedEvent(USER, 41L, 1, true, AT));
        NotificationDraft admin = MemberReviewAlerts.channelApproved(
                new InfluencerChannelApprovedEvent(USER, 41L, 2, false, AT));

        assertThat(auto.kind()).isEqualTo(admin.kind());
        assertThat(render(auto)).isEqualTo(render(admin));
        assertThat(auto.payload()).isEqualTo(admin.payload());
    }

    @Test
    void 채널_인증_반려는_사유를_넣고_승인과_같은_사건_ID여도_원본_이벤트_종류가_다르다() {
        NotificationDraft draft = MemberReviewAlerts.channelRejected(
                new InfluencerChannelRejectedEvent(USER, 41L, 2, "팔로워 수가 기준에 못 미칩니다.", AT));
        NotificationDraft approved = MemberReviewAlerts.channelApproved(
                new InfluencerChannelApprovedEvent(USER, 41L, 2, false, AT));

        assertThat(draft.receiverUserId()).isEqualTo(USER);
        assertThat(draft.kind()).isEqualTo("INFLUENCER_CHANNEL_REJECTED");
        assertThat(draft.sourceEventType()).isEqualTo("InfluencerChannelRejectedEvent");
        assertThat(draft.sourceTargetId()).isEqualTo("41:2").isEqualTo(approved.sourceTargetId());
        assertThat(draft.sourceEventType()).isNotEqualTo(approved.sourceEventType());
        assertThat(draft.targetType()).isNull();
        assertThat(draft.targetId()).isNull();
        assertThat(draft.occurredAt()).isEqualTo(AT);
        assertThat(draft.payload()).containsOnlyKeys(NotificationPayload.VERSION, "rejectionReason");
        assertThat(render(draft)).isEqualTo("채널 인증이 반려되었습니다. 사유: 팔로워 수가 기준에 못 미칩니다.");
    }

    @Test
    void 같은_채널의_다른_회차_반려는_사건_ID가_다르다() {
        NotificationDraft first = MemberReviewAlerts.channelRejected(
                new InfluencerChannelRejectedEvent(USER, 41L, 1, "사유", AT));
        NotificationDraft second = MemberReviewAlerts.channelRejected(
                new InfluencerChannelRejectedEvent(USER, 41L, 2, "사유", AT));

        assertThat(first.sourceTargetId()).isEqualTo("41:1");
        assertThat(second.sourceTargetId()).isEqualTo("41:2");
    }

    @Test
    void 승급_반려는_승급_신청_ID로_사유를_넣어_만든다() {
        NotificationDraft draft = MemberReviewAlerts.upgradeRequestRejected(
                new InfluencerUpgradeRequestRejectedEvent(USER, 51L, "강의 계획이 부족합니다.", AT));

        assertThat(draft.receiverUserId()).isEqualTo(USER);
        assertThat(draft.kind()).isEqualTo("INFLUENCER_UPGRADE_REQUEST_REJECTED");
        assertThat(draft.sourceEventType()).isEqualTo("InfluencerUpgradeRequestRejectedEvent");
        assertThat(draft.sourceTargetId()).isEqualTo("51");
        assertThat(draft.targetType()).isNull();
        assertThat(draft.targetId()).isNull();
        assertThat(draft.occurredAt()).isEqualTo(AT);
        assertThat(draft.payload()).containsOnlyKeys(NotificationPayload.VERSION, "rejectionReason");
        assertThat(render(draft)).isEqualTo("인증된 인플루언서 승급 신청이 반려되었습니다. 사유: 강의 계획이 부족합니다.");
    }

    @Test
    void 필수_값이_비면_거절한다() {
        assertThatThrownBy(() -> MemberReviewAlerts.channelApproved(
                new InfluencerChannelApprovedEvent(null, 41L, 1, true, AT)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MemberReviewAlerts.channelApproved(
                new InfluencerChannelApprovedEvent(USER, null, 1, true, AT)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MemberReviewAlerts.channelApproved(
                new InfluencerChannelApprovedEvent(USER, 41L, null, true, AT)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MemberReviewAlerts.channelApproved(
                new InfluencerChannelApprovedEvent(USER, 41L, 1, true, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MemberReviewAlerts.channelRejected(
                new InfluencerChannelRejectedEvent(USER, 41L, 1, null, AT)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MemberReviewAlerts.upgradeRequestRejected(
                new InfluencerUpgradeRequestRejectedEvent(USER, null, "사유", AT)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MemberReviewAlerts.upgradeRequestRejected(
                new InfluencerUpgradeRequestRejectedEvent(USER, 51L, "사유", null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 반려_사유가_공백뿐이면_거절한다() {
        assertThatThrownBy(() -> MemberReviewAlerts.channelRejected(
                new InfluencerChannelRejectedEvent(USER, 41L, 1, "  ", AT)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MemberReviewAlerts.upgradeRequestRejected(
                new InfluencerUpgradeRequestRejectedEvent(USER, 51L, "", AT)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static String render(NotificationDraft draft) {
        return MemberReviewTemplates.valueOf(draft.kind()).render(draft.payload());
    }
}
