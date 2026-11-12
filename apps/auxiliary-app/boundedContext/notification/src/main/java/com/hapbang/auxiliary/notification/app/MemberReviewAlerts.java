package com.hapbang.auxiliary.notification.app;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import com.hapbang.auxiliary.notification.domain.NotificationDraft;
import com.hapbang.auxiliary.notification.domain.NotificationPayload;
import com.hapbang.shared.member.event.InfluencerChannelApprovedEvent;
import com.hapbang.shared.member.event.InfluencerChannelRejectedEvent;
import com.hapbang.shared.member.event.InfluencerUpgradeRequestRejectedEvent;

/**
 * N7 회원 3종(#915, ND-118)의 이벤트를 알림 초안으로 바꾼다.
 * <ul>
 *     <li>받는 사람: 이벤트의 {@code userId}(채널 주인, 승급 신청한 회원).</li>
 *     <li>원본 대상 ID는 채널 2종이 {@code <channelId>:<reviewSequence>}(ND-045), 승급 반려가 승급 신청 ID다(ND-119). 같은 채널·회차의
 *     승인과 반려는 원본 이벤트 종류가 달라 서로 막지 않는다.</li>
 *     <li>이동 대상은 없다(ND-120).</li>
 *     <li>발생 시각은 이벤트의 사건 시각이다(ND-021).</li>
 *     <li>필수 값이 비거나 반려 사유가 공백뿐이면 {@link IllegalArgumentException}을 던진다. 소비자는 재시도 없이 DLT로 보낸다.</li>
 * </ul>
 */
public final class MemberReviewAlerts {

    private MemberReviewAlerts() {
    }

    /** 채널 인증 승인(자동·관리자): 채널 주인에게. 승인 방식은 문구에 쓰지 않아 값에 남기지 않는다 */
    public static NotificationDraft channelApproved(InfluencerChannelApprovedEvent event) {
        return draft(event.userId(), MemberReviewTemplates.INFLUENCER_CHANNEL_APPROVED, event,
                channelReview(event.channelId(), event.reviewSequence()), payload(), event.approvedAt());
    }

    /** 채널 인증 반려: 채널 주인에게 사유를 넣어 */
    public static NotificationDraft channelRejected(InfluencerChannelRejectedEvent event) {
        return draft(event.userId(), MemberReviewTemplates.INFLUENCER_CHANNEL_REJECTED, event,
                channelReview(event.channelId(), event.reviewSequence()), reasonPayload(event.rejectionReason()),
                event.rejectedAt());
    }

    /** 승급 반려: 신청한 회원에게 사유를 넣어 */
    public static NotificationDraft upgradeRequestRejected(InfluencerUpgradeRequestRejectedEvent event) {
        return draft(event.userId(), MemberReviewTemplates.INFLUENCER_UPGRADE_REQUEST_REJECTED, event,
                String.valueOf(require(event.upgradeRequestId(), "upgradeRequestId")),
                reasonPayload(event.rejectionReason()), event.rejectedAt());
    }

    private static String channelReview(Long channelId, Integer reviewSequence) {
        return require(channelId, "channelId") + ":" + require(reviewSequence, "reviewSequence");
    }

    private static Map<String, Object> payload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(NotificationPayload.VERSION, MemberReviewTemplates.PAYLOAD_VERSION);
        return payload;
    }

    private static Map<String, Object> reasonPayload(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("회원 심사 이벤트에 반려 사유가 없습니다.");
        }
        Map<String, Object> payload = payload();
        payload.put(MemberReviewTemplates.Keys.REJECTION_REASON, reason);
        return payload;
    }

    private static NotificationDraft draft(Long receiver, MemberReviewTemplates kind, Object event,
            String sourceTargetId, Map<String, Object> payload, Instant occurredAt) {
        return new NotificationDraft(require(receiver, "userId"), kind.kind(), event.getClass().getSimpleName(),
                sourceTargetId, null, null, payload, require(occurredAt, "사건 시각"));
    }

    private static <T> T require(T value, String name) {
        if (value == null) {
            throw new IllegalArgumentException("회원 심사 이벤트에 필수 값이 없습니다: " + name);
        }
        return value;
    }
}
