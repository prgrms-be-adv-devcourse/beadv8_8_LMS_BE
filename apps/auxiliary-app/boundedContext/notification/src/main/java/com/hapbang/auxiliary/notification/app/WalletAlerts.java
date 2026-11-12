package com.hapbang.auxiliary.notification.app;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import com.hapbang.auxiliary.notification.app.WalletAlertTemplates.Keys;
import com.hapbang.auxiliary.notification.domain.NotificationDraft;
import com.hapbang.auxiliary.notification.domain.NotificationPayload;
import com.hapbang.shared.billing.event.ChargeFailedEvent;
import com.hapbang.shared.billing.event.ExchangeApprovedEvent;
import com.hapbang.shared.billing.event.RefundFailedEvent;
import com.hapbang.shared.billing.event.RefundManualReviewRequiredEvent;

/**
 * N7 지갑 4종(#907, ND-105)의 이벤트를 알림 초안으로 바꾼다.
 * <ul>
 *     <li>받는 사람: 이벤트의 {@code userId}(충전·환불·환전한 회원, 알림 종류 문서 3장).</li>
 *     <li>원본 대상 ID는 충전 ID·환불 요청 ID·환전 ID다(ND-106). 환불 2종은 같은 ID를 쓰지만 원본 이벤트 종류가 달라 서로 막지 않는다.</li>
 *     <li>이동 대상은 {@code CHARGE}·{@code REFUND_REQUEST}·{@code EXCHANGE}다(ND-107).</li>
 *     <li>발생 시각은 이벤트의 사건 시각이다(ND-021).</li>
 *     <li>필수 값이 비면 {@link IllegalArgumentException}을 던진다. 소비자는 재시도 없이 DLT로 보낸다.</li>
 * </ul>
 */
public final class WalletAlerts {

    static final String TARGET_CHARGE = "CHARGE";
    static final String TARGET_REFUND_REQUEST = "REFUND_REQUEST";
    static final String TARGET_EXCHANGE = "EXCHANGE";

    private WalletAlerts() {
    }

    /** 충전 실패: 충전한 회원에게 */
    public static NotificationDraft chargeFailed(ChargeFailedEvent event) {
        Map<String, Object> payload = payload();
        payload.put(Keys.AMOUNT_HP, event.amountHp());
        return draft(event.userId(), WalletAlertTemplates.CHARGE_FAILED, event, event.chargeId(), TARGET_CHARGE,
                payload, event.failedAt());
    }

    /** 환불 실패(환불한 금액 없이 끝남): 신청한 회원에게 */
    public static NotificationDraft refundFailed(RefundFailedEvent event) {
        Map<String, Object> payload = payload();
        payload.put(Keys.REQUESTED_AMOUNT_HP, event.requestedAmountHp());
        return draft(event.userId(), WalletAlertTemplates.REFUND_FAILED, event, event.refundRequestId(),
                TARGET_REFUND_REQUEST, payload, event.failedAt());
    }

    /** 환불 운영자 확인 필요: 신청한 회원에게 */
    public static NotificationDraft refundManualReviewRequired(RefundManualReviewRequiredEvent event) {
        Map<String, Object> payload = payload();
        payload.put(Keys.REQUESTED_AMOUNT_HP, event.requestedAmountHp());
        return draft(event.userId(), WalletAlertTemplates.REFUND_MANUAL_REVIEW_REQUIRED, event,
                event.refundRequestId(), TARGET_REFUND_REQUEST, payload, event.manualReviewRequiredAt());
    }

    /** 환전 승인: 신청한 회원에게. 송금 예정 시각은 값에 남겨 문구에서 KST 날짜로 쓴다 */
    public static NotificationDraft exchangeApproved(ExchangeApprovedEvent event) {
        Map<String, Object> payload = payload();
        payload.put(Keys.REQUESTED_AMOUNT_HP, event.requestedAmountHp());
        payload.put(Keys.PAYOUT_SCHEDULED_AT, require(event.payoutScheduledAt(), "payoutScheduledAt").toString());
        return draft(event.userId(), WalletAlertTemplates.EXCHANGE_APPROVED, event, event.exchangeId(),
                TARGET_EXCHANGE, payload, event.approvedAt());
    }

    private static Map<String, Object> payload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(NotificationPayload.VERSION, WalletAlertTemplates.PAYLOAD_VERSION);
        return payload;
    }

    /** 원본 대상과 이동 대상은 같은 자원 ID다(ND-106·ND-107) */
    private static NotificationDraft draft(Long receiver, WalletAlertTemplates kind, Object event, Long targetId,
            String targetType, Map<String, Object> payload, Instant occurredAt) {
        String id = String.valueOf(require(targetId, "대상 ID"));
        return new NotificationDraft(require(receiver, "userId"), kind.kind(), event.getClass().getSimpleName(), id,
                targetType, id, payload, require(occurredAt, "사건 시각"));
    }

    private static <T> T require(T value, String name) {
        if (value == null) {
            throw new IllegalArgumentException("지갑 이벤트에 필수 값이 없습니다: " + name);
        }
        return value;
    }
}
