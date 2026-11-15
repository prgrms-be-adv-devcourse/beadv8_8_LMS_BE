package com.hapbang.auxiliary.notification.support;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import com.hapbang.auxiliary.notification.domain.NotificationDraft;

/** 알림 저장 테스트의 초안. 종류는 {@link TestNotificationTemplate}이다 */
public final class NotificationFixtures {

    private NotificationFixtures() {
    }

    /**
     * @param receiverId 받는 사람
     * @param sourceId   원본 대상 ID(중복 판정 키)
     * @param actorId    문구에 닉네임이 들어가는 다른 회원
     * @param actorName  그 회원의 닉네임
     * @param occurredAt 발생 시각
     */
    public static NotificationDraft draft(long receiverId, String sourceId, long actorId, String actorName,
            Instant occurredAt) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("v", 1);
        payload.put("actorId", actorId);
        payload.put("title", "강의 " + sourceId);
        payload.put("nicknames", Map.of(String.valueOf(actorId), actorName));
        return new NotificationDraft(receiverId, TestNotificationTemplate.KIND, "TestEvent", sourceId, "LECTURE",
                sourceId, payload, occurredAt);
    }

    /** 문구의 주인공(actor) 말고도 다른 회원(other)의 닉네임을 값에 함께 담은 초안 */
    public static NotificationDraft draftMentioning(long receiverId, String sourceId, long actorId, String actorName,
            long otherId, String otherName) {
        NotificationDraft draft = draft(receiverId, sourceId, actorId, actorName, Instant.parse("2026-11-10T00:00:00Z"));
        Map<String, Object> nicknames = new LinkedHashMap<>();
        nicknames.put(String.valueOf(actorId), actorName);
        nicknames.put(String.valueOf(otherId), otherName);
        draft.payload().put("nicknames", nicknames);
        return draft;
    }
}
