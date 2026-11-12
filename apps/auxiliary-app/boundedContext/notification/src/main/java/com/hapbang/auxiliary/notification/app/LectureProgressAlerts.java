package com.hapbang.auxiliary.notification.app;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.hapbang.auxiliary.notification.app.LectureProgressTemplates.Keys;
import com.hapbang.auxiliary.notification.domain.NotificationDraft;
import com.hapbang.auxiliary.notification.domain.NotificationPayload;
import com.hapbang.shared.lecture.domain.LectureConfirmationType;
import com.hapbang.shared.lecture.dto.LectureOrderItemDto;
import com.hapbang.shared.lecture.event.LectureApprovedEvent;
import com.hapbang.shared.lecture.event.LectureObjectionFiledEvent;
import com.hapbang.shared.lecture.event.LectureOrderHoldFailedEvent;
import com.hapbang.shared.lecture.event.LectureOrderItemCanceledEvent;
import com.hapbang.shared.lecture.event.LectureOrderItemConfirmedEvent;
import com.hapbang.shared.lecture.event.LectureOrderPaidEvent;
import com.hapbang.shared.lecture.event.LectureRejectedEvent;

/**
 * 강의 진행 이벤트 7종(N7 강의, M4, ND-114)을 알림 초안으로 바꾼다.
 * <ul>
 *     <li>받는 사람: 검수 2종은 판매자, 주문 2종·구매 취소·자동 구매 확정은 구매자, 이의 제기 접수는 구매자·판매자 각 1건.</li>
 *     <li>원본 대상 ID는 이벤트의 사건 ID다. 검수 2종은 {@code <강의 ID>:<검수 회차>}, 주문 2종은 주문 ID, 구매 취소·확정은 주문 항목 ID,
 *     이의 제기 접수는 문의 ID(ND-115).</li>
 *     <li>이동 대상은 검수 2종 {@code LECTURE}/강의 ID, 주문 2종 {@code LECTURE_ORDER}/주문 ID, 나머지
 *     {@code LECTURE_ORDER_ITEM}/주문 항목 ID(ND-116).</li>
 *     <li>구매 확정은 자동 구매 확정({@code AUTO})만 알린다. 직접·첫 재생 확정은 빈 목록이다(ND-114).</li>
 *     <li>발생 시각은 이벤트의 사건 시각이다. 필수 값이 비면 {@link IllegalArgumentException}을 던진다.</li>
 * </ul>
 */
public final class LectureProgressAlerts {

    static final String TARGET_LECTURE = "LECTURE";
    static final String TARGET_LECTURE_ORDER = "LECTURE_ORDER";
    static final String TARGET_LECTURE_ORDER_ITEM = "LECTURE_ORDER_ITEM";

    private LectureProgressAlerts() {
    }

    /** 검수 승인: 판매자에게 */
    public static NotificationDraft approved(LectureApprovedEvent event) {
        Long lectureId = require(event.lectureId(), "lectureId");
        return draft(require(event.sellerUserId(), "sellerUserId"), LectureProgressTemplates.LECTURE_APPROVED, event,
                inspection(lectureId, event.inspectionSequence()), TARGET_LECTURE, lectureId,
                payload(event.lectureTitle()), event.approvedAt());
    }

    /** 검수 반려: 판매자에게, 반려 사유를 넣는다 */
    public static NotificationDraft rejected(LectureRejectedEvent event) {
        Long lectureId = require(event.lectureId(), "lectureId");
        Map<String, Object> payload = payload(event.lectureTitle());
        payload.put(Keys.REJECTION_REASON, require(event.rejectionReason(), "rejectionReason"));
        return draft(require(event.sellerUserId(), "sellerUserId"), LectureProgressTemplates.LECTURE_REJECTED, event,
                inspection(lectureId, event.inspectionSequence()), TARGET_LECTURE, lectureId, payload,
                event.rejectedAt());
    }

    /** 구매 완료: 구매자에게, 주문당 1건 */
    public static NotificationDraft paid(LectureOrderPaidEvent event) {
        return order(LectureProgressTemplates.LECTURE_ORDER_PAID, event, event.orderId(), event.buyerUserId(),
                event.items(), event.paidAt());
    }

    /** 결제 실패(보류 거절·무효화 모두): 구매자에게, 주문당 1건 */
    public static NotificationDraft holdFailed(LectureOrderHoldFailedEvent event) {
        return order(LectureProgressTemplates.LECTURE_ORDER_HOLD_FAILED, event, event.orderId(), event.buyerUserId(),
                event.items(), event.failedAt());
    }

    /** 구매 취소: 구매자에게, 반환 HP를 넣는다 */
    public static NotificationDraft canceled(LectureOrderItemCanceledEvent event) {
        Long orderItemId = require(event.orderItemId(), "orderItemId");
        Map<String, Object> payload = payload(event.lectureTitle());
        payload.put(Keys.BUYER_AMOUNT_HP, require(event.buyerAmountHp(), "buyerAmountHp"));
        return draft(require(event.buyerUserId(), "buyerUserId"), LectureProgressTemplates.LECTURE_ORDER_ITEM_CANCELED,
                event, String.valueOf(orderItemId), TARGET_LECTURE_ORDER_ITEM, orderItemId, payload,
                event.canceledAt());
    }

    /** 구매 확정: 자동 구매 확정만 구매자에게. 직접·첫 재생 확정은 빈 목록 */
    public static List<NotificationDraft> confirmed(LectureOrderItemConfirmedEvent event) {
        if (require(event.confirmationType(), "confirmationType") != LectureConfirmationType.AUTO) {
            return List.of();
        }
        Long orderItemId = require(event.orderItemId(), "orderItemId");
        return List.of(draft(require(event.buyerUserId(), "buyerUserId"),
                LectureProgressTemplates.LECTURE_ORDER_ITEM_CONFIRMED, event, String.valueOf(orderItemId),
                TARGET_LECTURE_ORDER_ITEM, orderItemId, payload(event.lectureTitle()), event.confirmedAt()));
    }

    /** 이의 제기 접수: 구매자, 판매자 순서로 하나씩 */
    public static List<NotificationDraft> objectionFiled(LectureObjectionFiledEvent event) {
        Long buyer = require(event.buyerUserId(), "buyerUserId");
        Long seller = require(event.sellerUserId(), "sellerUserId");
        return List.of(objectionFiledFor(event, buyer, Keys.ROLE_BUYER), objectionFiledFor(event, seller,
                Keys.ROLE_SELLER));
    }

    private static NotificationDraft objectionFiledFor(LectureObjectionFiledEvent event, Long receiver, String role) {
        Map<String, Object> payload = payload(event.lectureTitle());
        payload.put(Keys.RECEIVER_ROLE, role);
        return draft(receiver, LectureProgressTemplates.LECTURE_OBJECTION_FILED, event,
                String.valueOf(require(event.inquiryId(), "inquiryId")), TARGET_LECTURE_ORDER_ITEM,
                require(event.orderItemId(), "orderItemId"), payload, event.filedAt());
    }

    /** 주문 2종: 제목은 첫 항목, 나머지는 수만 넣는다 */
    private static NotificationDraft order(LectureProgressTemplates kind, Object event, Long orderId, Long buyer,
            List<LectureOrderItemDto> items, OffsetDateTime occurredAt) {
        Long id = require(orderId, "orderId");
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("강의 진행 이벤트에 필수 값이 없습니다: items");
        }
        LectureOrderItemDto first = require(items.getFirst(), "items[0]");
        Map<String, Object> payload = payload(first.lectureTitle());
        payload.put(Keys.OTHER_ITEM_COUNT, items.size() - 1);
        return draft(require(buyer, "buyerUserId"), kind, event, String.valueOf(id), TARGET_LECTURE_ORDER, id, payload,
                occurredAt);
    }

    private static String inspection(Long lectureId, Integer inspectionSequence) {
        return lectureId + ":" + require(inspectionSequence, "inspectionSequence");
    }

    private static Map<String, Object> payload(String lectureTitle) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(NotificationPayload.VERSION, LectureProgressTemplates.PAYLOAD_VERSION);
        payload.put(Keys.LECTURE_TITLE, require(lectureTitle, "lectureTitle"));
        return payload;
    }

    private static NotificationDraft draft(Long receiver, LectureProgressTemplates kind, Object event,
            String sourceTargetId, String targetType, Long targetId, Map<String, Object> payload,
            OffsetDateTime occurredAt) {
        return new NotificationDraft(receiver, kind.kind(), event.getClass().getSimpleName(), sourceTargetId,
                targetType, String.valueOf(targetId), payload, require(occurredAt, "사건 시각").toInstant());
    }

    private static <T> T require(T value, String name) {
        if (value == null) {
            throw new IllegalArgumentException("강의 진행 이벤트에 필수 값이 없습니다: " + name);
        }
        return value;
    }
}
