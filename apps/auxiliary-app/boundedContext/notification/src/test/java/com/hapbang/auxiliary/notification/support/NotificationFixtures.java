package com.hapbang.auxiliary.notification.support;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import com.hapbang.auxiliary.notification.domain.NotificationDraft;
import com.hapbang.auxiliary.notification.out.NotificationRepository.ReceiverUnread;

/** 알림 저장 테스트의 초안(종류는 {@link TestNotificationTemplate})과 실시간 시험이 함께 쓰는 도우미 */
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

    /** 다른 회원 닉네임이 없는 초안(실시간 전달의 잠금 없는 묶음 경로, ND-205) */
    public static NotificationDraft draftWithoutMention(long receiverId, String sourceId, Instant occurredAt) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("v", 1);
        payload.put("title", "강의 " + sourceId);
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

    /** 빈 하나만 돌려주는 {@link ObjectProvider}(실시간 시험의 발행기·지표 등록부) */
    public static <T> ObjectProvider<T> provider(Class<T> type, T bean) {
        return new StaticListableBeanFactory(Map.of("bean", bean)).getBeanProvider(type);
    }

    /** 받는 사람별 안 읽은 수 조회 결과 한 줄 */
    public static ReceiverUnread unread(Long receiverUserId, long unread) {
        return new ReceiverUnread() {
            @Override
            public Long getReceiverUserId() {
                return receiverUserId;
            }

            @Override
            public long getUnread() {
                return unread;
            }
        };
    }
}
