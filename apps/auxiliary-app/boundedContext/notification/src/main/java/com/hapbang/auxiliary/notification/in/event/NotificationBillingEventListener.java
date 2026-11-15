package com.hapbang.auxiliary.notification.in.event;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.kafka.annotation.KafkaHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import com.hapbang.auxiliary.notification.app.AlertDrafts;
import com.hapbang.auxiliary.notification.app.NotificationAlertService;
import com.hapbang.auxiliary.notification.in.event.kafka.NotificationKafkaConfig;
import com.hapbang.auxiliary.notification.in.event.kafka.NotificationKafkaConstants;
import com.hapbang.auxiliary.notification.in.event.kafka.NotificationKafkaConsumerSettings;
import com.hapbang.auxiliary.notification.in.event.kafka.SkippablePayloads;
import com.hapbang.auxiliary.notification.in.event.kafka.SkippedEventLog;
import com.hapbang.shared.billing.event.ChargeCompletedEvent;
import com.hapbang.shared.billing.event.ExchangeCompletedEvent;
import com.hapbang.shared.billing.event.ExchangeFinalFailedEvent;
import com.hapbang.shared.billing.event.ExchangePayoutFailedEvent;
import com.hapbang.shared.billing.event.ExchangeRejectedEvent;
import com.hapbang.shared.billing.event.PayoutCompletedEvent;
import com.hapbang.shared.billing.event.RefundCompletedEvent;

import lombok.extern.slf4j.Slf4j;

/**
 * 지갑 이벤트({@code billing.events.v1}, 그룹 {@code auxiliary-app.notification-billing-alert})로 알림을 만드는 진입 어댑터
 * (N5: 지갑 7종, ND-069).
 * <ul>
 *     <li>컨테이너는 앱 기동 때 시작하지 않고 첫 회원 복제본 Backfill이 끝나면 시작한다(ND-078).</li>
 *     <li>지갑 이벤트는 모두 사건 시각을 싣는다. 그 시각이 알림 시작 시각보다 이르면 만들지 않는다(ND-076).</li>
 *     <li>강의 원천 지급은 하루 묶음(N8)이라 이 소비자에서 만들지 않고 커밋한다(ND-013).</li>
 * </ul>
 */
@Slf4j
@Component
@ConditionalOnBooleanProperty(name = NotificationKafkaConstants.BILLING_EVENTS_ENABLED, matchIfMissing = true)
@KafkaListener(id = NotificationBillingEventListener.LISTENER_ID, idIsGroup = false, topics = "#{__listener.topic}",
        groupId = "#{__listener.groupId}", containerFactory = NotificationKafkaConfig.BILLING_EVENT_CONTAINER_FACTORY)
public class NotificationBillingEventListener {

    /** 리스너 컨테이너 ID(소비 그룹 이름과 다르다) */
    public static final String LISTENER_ID = "notification-billing-events";

    private final NotificationAlertService alerts;
    private final NotificationKafkaConsumerSettings settings;
    private final SkippedEventLog skippedLog = new SkippedEventLog(log);

    public NotificationBillingEventListener(NotificationAlertService alerts,
            @Qualifier(NotificationKafkaConfig.BILLING_EVENT_SETTINGS) NotificationKafkaConsumerSettings settings) {
        this.alerts = alerts;
        this.settings = settings;
    }

    @KafkaHandler
    public void on(ChargeCompletedEvent event) {
        alerts.create(AlertDrafts.chargeCompleted(event));
    }

    @KafkaHandler
    public void on(RefundCompletedEvent event) {
        alerts.create(AlertDrafts.refundCompleted(event));
    }

    @KafkaHandler
    public void on(PayoutCompletedEvent event) {
        AlertDrafts.payoutCompleted(event).ifPresentOrElse(alerts::create,
                () -> log.debug("강의 원천 지급은 하루 묶음(N8)이라 건너뛰었다 settlementId={}", event.settlementId()));
    }

    @KafkaHandler
    public void on(ExchangeRejectedEvent event) {
        alerts.create(AlertDrafts.exchangeRejected(event));
    }

    @KafkaHandler
    public void on(ExchangeCompletedEvent event) {
        alerts.create(AlertDrafts.exchangeCompleted(event));
    }

    @KafkaHandler
    public void on(ExchangePayoutFailedEvent event) {
        alerts.create(AlertDrafts.exchangePayoutFailed(event));
    }

    @KafkaHandler
    public void on(ExchangeFinalFailedEvent event) {
        alerts.create(AlertDrafts.exchangeFinalFailed(event));
    }

    /** 처리기가 없는 레코드를 건너뛴다. 신뢰 패키지 밖 타입은 재시도 없이 DLT로 보낸다({@link SkippablePayloads}) */
    @KafkaHandler(isDefault = true)
    public void onUnsupported(@Payload(required = false) Object payload,
            @Header(name = KafkaHeaders.RECEIVED_KEY, required = false) String key,
            @Header(name = KafkaHeaders.RECEIVED_PARTITION, required = false) Integer partition,
            @Header(name = KafkaHeaders.OFFSET, required = false) Long offset) {
        skippedLog.skipped(SkippablePayloads.typeToSkip(payload, NotificationKafkaConstants.BILLING_EVENTS_TRUSTED_PACKAGE),
                key, partition, offset);
    }

    public String getTopic() {
        return settings.topic();
    }

    public String getGroupId() {
        return settings.groupId();
    }
}
