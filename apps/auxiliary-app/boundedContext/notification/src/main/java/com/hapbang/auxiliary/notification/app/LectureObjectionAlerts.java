package com.hapbang.auxiliary.notification.app;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.hapbang.auxiliary.notification.app.LectureObjectionTemplates.Keys;
import com.hapbang.auxiliary.notification.domain.NotificationDraft;
import com.hapbang.auxiliary.notification.domain.NotificationPayload;
import com.hapbang.shared.lecture.event.LectureObjectionResolvedEvent;
import com.hapbang.shared.lecture.event.LectureObjectionReturnActionRequiredEvent;
import com.hapbang.shared.lecture.event.LectureObjectionReturnCompletedEvent;
import com.hapbang.shared.lecture.event.LectureObjectionWithdrawnEvent;

/**
 * 이의 제기 결과 4종(N6, ND-082)의 이벤트를 알림 초안으로 바꾼다.
 * <ul>
 *     <li>받는 사람: 판단은 구매자·판매자 각 1건, 반환 완료·반환 지연은 구매자, 철회는 판매자(알림 종류 문서 2·5장).</li>
 *     <li>원본 대상 ID는 문의 ID이고, 반환 지연은 {@code <문의 ID>:<반환 지연 회차>}다(ND-083, ND-045).</li>
 *     <li>이동 대상은 {@code LECTURE_ORDER_ITEM}/주문 항목 ID이고 구매자·판매자 모두 같다(ND-084).</li>
 *     <li>발생 시각은 이벤트의 사건 시각이다. 제목은 구매할 때의 강의 제목({@code lectureTitle})이다.</li>
 *     <li>필수 값이 비면 {@link IllegalArgumentException}을 던진다. 이전 형식인지는 이 클래스가 아니라 소비자가 원본 JSON으로
 *     판단한다(ND-086, {@code ObjectionLegacyFormat}).</li>
 * </ul>
 */
public final class LectureObjectionAlerts {

    static final String TARGET_LECTURE_ORDER_ITEM = "LECTURE_ORDER_ITEM";

    private LectureObjectionAlerts() {
    }

    /** 이의 제기 판단(인정·기각): 구매자, 판매자 순서로 하나씩 */
    public static List<NotificationDraft> resolved(LectureObjectionResolvedEvent event) {
        Long buyer = require(event.buyerUserId(), "buyerUserId");
        Long seller = require(event.sellerUserId(), "sellerUserId");
        return List.of(
                resolvedFor(event, buyer, Keys.ROLE_BUYER),
                resolvedFor(event, seller, Keys.ROLE_SELLER));
    }

    /** 이의 제기 반환 완료: 구매자에게 */
    public static NotificationDraft returnCompleted(LectureObjectionReturnCompletedEvent event) {
        return draft(require(event.buyerUserId(), "buyerUserId"),
                LectureObjectionTemplates.LECTURE_OBJECTION_RETURN_COMPLETED, event, event.inquiryId(),
                String.valueOf(require(event.inquiryId(), "inquiryId")), event.orderItemId(),
                payload(event.lectureTitle()), event.returnedAt());
    }

    /** 이의 제기 철회: 판매자에게 */
    public static NotificationDraft withdrawn(LectureObjectionWithdrawnEvent event) {
        return draft(require(event.sellerUserId(), "sellerUserId"), LectureObjectionTemplates.LECTURE_OBJECTION_WITHDRAWN,
                event, event.inquiryId(), String.valueOf(require(event.inquiryId(), "inquiryId")), event.orderItemId(),
                payload(event.lectureTitle()), event.withdrawnAt());
    }

    /** 반환 지연: 구매자에게. 회차마다 원본 대상 ID가 달라 회차마다 하나씩 생긴다 */
    public static NotificationDraft returnActionRequired(LectureObjectionReturnActionRequiredEvent event) {
        Long inquiryId = require(event.inquiryId(), "inquiryId");
        Integer sequence = require(event.returnDelaySequence(), "returnDelaySequence");
        Map<String, Object> payload = payload(event.lectureTitle());
        payload.put(Keys.RETURN_DELAY_SEQUENCE, sequence);
        return draft(require(event.buyerUserId(), "buyerUserId"),
                LectureObjectionTemplates.LECTURE_OBJECTION_RETURN_ACTION_REQUIRED, event, inquiryId,
                inquiryId + ":" + sequence, event.orderItemId(), payload, event.occurredAt());
    }

    private static NotificationDraft resolvedFor(LectureObjectionResolvedEvent event, Long receiver, String role) {
        Map<String, Object> payload = payload(event.lectureTitle());
        payload.put(Keys.ACCEPTED, event.accepted());
        payload.put(Keys.RECEIVER_ROLE, role);
        return draft(receiver, LectureObjectionTemplates.LECTURE_OBJECTION_RESOLVED, event, event.inquiryId(),
                String.valueOf(require(event.inquiryId(), "inquiryId")), event.orderItemId(), payload,
                event.resolvedAt());
    }

    private static Map<String, Object> payload(String lectureTitle) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(NotificationPayload.VERSION, LectureObjectionTemplates.PAYLOAD_VERSION);
        payload.put(Keys.LECTURE_TITLE, require(lectureTitle, "lectureTitle"));
        return payload;
    }

    private static NotificationDraft draft(Long receiver, LectureObjectionTemplates kind, Object event, Long inquiryId,
            String sourceTargetId, Long orderItemId, Map<String, Object> payload, OffsetDateTime occurredAt) {
        require(inquiryId, "inquiryId");
        return new NotificationDraft(receiver, kind.kind(), event.getClass().getSimpleName(), sourceTargetId,
                TARGET_LECTURE_ORDER_ITEM, String.valueOf(require(orderItemId, "orderItemId")), payload,
                require(occurredAt, "사건 시각").toInstant());
    }

    private static <T> T require(T value, String name) {
        if (value == null) {
            throw new IllegalArgumentException("이의 제기 이벤트에 필수 값이 없습니다: " + name);
        }
        return value;
    }
}
