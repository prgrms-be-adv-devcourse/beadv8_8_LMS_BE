package com.hapbang.auxiliary.notification.app;

import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import com.hapbang.auxiliary.notification.app.AlertTemplate.Keys;
import com.hapbang.auxiliary.notification.domain.NotificationDraft;
import com.hapbang.auxiliary.notification.domain.NotificationPayload;
import com.hapbang.shared.appearance.dto.AppearanceApplicationDto;
import com.hapbang.shared.appearance.dto.AppearanceRequestDto;
import com.hapbang.shared.appearance.event.AppearanceApplicationSubmittedEvent;
import com.hapbang.shared.appearance.event.AppearanceRequestSentEvent;
import com.hapbang.shared.billing.domain.SettlementSourceType;
import com.hapbang.shared.billing.event.ChargeCompletedEvent;
import com.hapbang.shared.billing.event.ExchangeCompletedEvent;
import com.hapbang.shared.billing.event.ExchangeFinalFailedEvent;
import com.hapbang.shared.billing.event.ExchangePayoutFailedEvent;
import com.hapbang.shared.billing.event.ExchangeRejectedEvent;
import com.hapbang.shared.billing.event.PayoutCompletedEvent;
import com.hapbang.shared.billing.event.RefundCompletedEvent;
import com.hapbang.shared.member.dto.UserDto;

/**
 * N5 11종(ND-069)의 이벤트를 알림 초안으로 바꾼다. 받는 사람·원본 대상 ID(ND-073)·이동 대상(ND-074)·발생 시각·값은 알림 종류 문서
 * 각 행과 같다. 문구는 {@link AlertTemplate}이 값으로 만든다.
 * <p>
 * 발생 시각(ND-021): 이벤트의 사건 시각을 쓴다. 사건 시각 필드가 없는 이벤트(출연 요청 도착·가입·회원 변경)는 Kafka 레코드
 * timestamp를 대체 시각으로 받는다(ND-081). 이 값은 발행 시각이라 실제 사건 시각과 같다고 보장하지 않는다. 출연의
 * {@code LocalDateTime}은 KST다(출연 앱 "모든 기간·일시는 KST").
 */
public final class AlertDrafts {

    /** 출연 앱의 {@code LocalDateTime} 기준 시간대 */
    static final ZoneId KST = ZoneId.of("Asia/Seoul");

    static final String TARGET_APPEARANCE_REQUEST = "APPEARANCE_REQUEST";
    static final String TARGET_APPEARANCE_RECRUIT = "APPEARANCE_RECRUIT";
    static final String TARGET_APPEARANCE = "APPEARANCE";
    static final String TARGET_CHARGE = "CHARGE";
    static final String TARGET_REFUND_REQUEST = "REFUND_REQUEST";
    static final String TARGET_EXCHANGE = "EXCHANGE";

    private AlertDrafts() {
    }

    /** 출연 요청 도착: 게스트에게. 호스트 닉네임은 언급 회원(ND-051). 발생 시각은 레코드 timestamp(ND-081) */
    public static NotificationDraft appearanceRequestSent(AppearanceRequestSentEvent event, String hostNickname,
            Instant recordTime) {
        AppearanceRequestDto request = event.request();
        Map<String, Object> payload = payload();
        payload.put(Keys.HOST_ID, request.hostId());
        payload.put(Keys.TITLE, request.title());
        payload.put(Keys.FEE, request.fee());
        payload.put(Keys.ACCEPT_DEADLINE, request.acceptDeadline().toString());
        payload.put(NotificationPayload.NICKNAMES, nicknames(request.hostId(), hostNickname));
        return draft(request.guestId(), AlertTemplate.APPEARANCE_REQUEST_SENT, event, request.requestId(),
                TARGET_APPEARANCE_REQUEST, request.requestId(), payload, recordTime);
    }

    /** 모집 지원 도착: 호스트에게. 게스트 닉네임은 언급 회원. 발생 시각은 지원 시각(KST) */
    public static NotificationDraft applicationSubmitted(AppearanceApplicationSubmittedEvent event,
            String guestNickname) {
        AppearanceApplicationDto application = event.application();
        Map<String, Object> payload = payload();
        payload.put(Keys.GUEST_ID, application.guestId());
        payload.put(Keys.RECRUIT_TITLE, application.recruitTitle());
        payload.put(NotificationPayload.NICKNAMES, nicknames(application.guestId(), guestNickname));
        return draft(application.hostId(), AlertTemplate.APPEARANCE_APPLICATION_SUBMITTED, event,
                application.applicationId(), TARGET_APPEARANCE_RECRUIT, application.recruitId(), payload,
                appliedAt(application));
    }

    /** 모집 지원 도착의 발생 시각: 지원 시각(KST {@code LocalDateTime}) */
    static Instant appliedAt(AppearanceApplicationDto application) {
        return application.appliedAt().atZone(KST).toInstant();
    }

    public static NotificationDraft chargeCompleted(ChargeCompletedEvent event) {
        Map<String, Object> payload = payload();
        payload.put(Keys.AMOUNT_HP, event.amountHp());
        payload.put(Keys.PAYMENT_AMOUNT_KRW, event.paymentAmountKrw());
        return draft(event.userId(), AlertTemplate.CHARGE_COMPLETED, event, event.chargeId(), TARGET_CHARGE,
                event.chargeId(), payload, event.completedAt());
    }

    public static NotificationDraft refundCompleted(RefundCompletedEvent event) {
        Map<String, Object> payload = payload();
        payload.put(Keys.REQUESTED_AMOUNT_HP, event.requestedAmountHp());
        payload.put(Keys.REFUNDED_AMOUNT_HP, event.refundedAmountHp());
        return draft(event.userId(), AlertTemplate.REFUND_COMPLETED, event, event.refundRequestId(),
                TARGET_REFUND_REQUEST, event.refundRequestId(), payload, event.completedAt());
    }

    /**
     * 출연 원천 지급(ND-013). 강의 원천({@code LECTURE_ORDER_ITEM})은 하루 묶음(N8)이라 비운다. 원천 ID는 요청·모집 모두 출연 ID다
     * ({@code SettlementSourceType}). 제목은 지갑이 발행하기 전까지 없다(ND-070)
     */
    public static Optional<NotificationDraft> payoutCompleted(PayoutCompletedEvent event) {
        if (event.sourceType() == SettlementSourceType.LECTURE_ORDER_ITEM) {
            return Optional.empty();
        }
        Map<String, Object> payload = payload();
        payload.put(Keys.PAYOUT_TYPE, event.payoutType().name());
        payload.put(Keys.SOURCE_TYPE, event.sourceType().name());
        payload.put(Keys.PAID_AMOUNT_HP, event.paidAmountHp());
        return Optional.of(draft(event.receiverUserId(), AlertTemplate.PAYOUT_COMPLETED, event, event.settlementId(),
                TARGET_APPEARANCE, event.sourceId(), payload, event.paidAt()));
    }

    public static NotificationDraft exchangeRejected(ExchangeRejectedEvent event) {
        Map<String, Object> payload = payload();
        payload.put(Keys.REQUESTED_AMOUNT_HP, event.requestedAmountHp());
        return draft(event.userId(), AlertTemplate.EXCHANGE_REJECTED, event, event.exchangeId(), TARGET_EXCHANGE,
                event.exchangeId(), payload, event.rejectedAt());
    }

    public static NotificationDraft exchangeCompleted(ExchangeCompletedEvent event) {
        Map<String, Object> payload = payload();
        payload.put(Keys.REQUESTED_AMOUNT_HP, event.requestedAmountHp());
        payload.put(Keys.FEE_AMOUNT_HP, event.feeAmountHp());
        payload.put(Keys.PAYOUT_AMOUNT_KRW, event.payoutAmountKrw());
        return draft(event.userId(), AlertTemplate.EXCHANGE_COMPLETED, event, event.exchangeId(), TARGET_EXCHANGE,
                event.exchangeId(), payload, event.completedAt());
    }

    /** 한 환전에서 여러 번 나므로 원본 대상 ID는 송금 시도 ID다(ND-033·ND-073) */
    public static NotificationDraft exchangePayoutFailed(ExchangePayoutFailedEvent event) {
        Map<String, Object> payload = payload();
        payload.put(Keys.PAYOUT_AMOUNT_KRW, event.payoutAmountKrw());
        payload.put(Keys.ATTEMPT_NO, event.attemptNo());
        return draft(event.userId(), AlertTemplate.EXCHANGE_PAYOUT_FAILED, event, event.payoutAttemptId(),
                TARGET_EXCHANGE, event.exchangeId(), payload, event.failedAt());
    }

    public static NotificationDraft exchangeFinalFailed(ExchangeFinalFailedEvent event) {
        Map<String, Object> payload = payload();
        payload.put(Keys.REQUESTED_AMOUNT_HP, event.requestedAmountHp());
        return draft(event.userId(), AlertTemplate.EXCHANGE_FINAL_FAILED, event, event.exchangeId(), TARGET_EXCHANGE,
                event.exchangeId(), payload, event.finalFailedAt());
    }

    /** 가입 환영. 이동 대상 없음. 발생 시각은 레코드 timestamp(ND-081) */
    public static NotificationDraft userJoined(UserDto user, Instant recordTime) {
        return new NotificationDraft(user.id(), AlertTemplate.USER_JOINED.kind(), "UserJoinedEvent",
                String.valueOf(user.id()), null, null, payload(), recordTime);
    }

    /** 승급 승인(ND-016·ND-071). 다시 승급할 수 있어 원본 대상 ID는 {@code <회원 ID>:<회원 버전>}(ND-073). 이동 대상 없음 */
    public static NotificationDraft upgradeApproved(UserDto user, Instant recordTime) {
        return new NotificationDraft(user.id(), AlertTemplate.INFLUENCER_UPGRADE_REQUEST_APPROVED.kind(),
                "UserModifiedEvent", user.id() + ":" + user.version(), null, null, payload(), recordTime);
    }

    private static NotificationDraft draft(Long receiver, AlertTemplate kind, Object event, Long sourceTargetId,
            String targetType, Long targetId, Map<String, Object> payload, Instant occurredAt) {
        return new NotificationDraft(receiver, kind.kind(), event.getClass().getSimpleName(),
                String.valueOf(sourceTargetId), targetType, String.valueOf(targetId), payload, occurredAt);
    }

    private static Map<String, Object> payload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(NotificationPayload.VERSION, AlertTemplate.PAYLOAD_VERSION);
        return payload;
    }

    private static Map<String, Object> nicknames(Long memberId, String nickname) {
        Map<String, Object> nicknames = new LinkedHashMap<>();
        nicknames.put(String.valueOf(memberId), nickname);
        return nicknames;
    }
}
