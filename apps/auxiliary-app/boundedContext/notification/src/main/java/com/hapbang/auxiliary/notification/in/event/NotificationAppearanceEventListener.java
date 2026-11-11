package com.hapbang.auxiliary.notification.in.event;

import java.time.Instant;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.kafka.annotation.KafkaHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import com.hapbang.auxiliary.notification.app.AppearanceCancelRequestAlerts;
import com.hapbang.auxiliary.notification.app.NotificationAlertService;
import com.hapbang.auxiliary.notification.domain.NotificationDraft;
import com.hapbang.auxiliary.notification.in.event.kafka.NotificationKafkaConfig;
import com.hapbang.auxiliary.notification.in.event.kafka.NotificationKafkaConstants;
import com.hapbang.auxiliary.notification.in.event.kafka.NotificationKafkaConsumerSettings;
import com.hapbang.auxiliary.notification.in.event.kafka.RejectedEventException;
import com.hapbang.auxiliary.notification.in.event.kafka.SkippablePayloads;
import com.hapbang.auxiliary.notification.in.event.kafka.SkippedEventLog;
import com.hapbang.shared.appearance.event.AppearanceApplicationSubmittedEvent;
import com.hapbang.shared.appearance.event.AppearanceCancelRequestAcceptedEvent;
import com.hapbang.shared.appearance.event.AppearanceCancelRequestRejectedEvent;
import com.hapbang.shared.appearance.event.AppearanceCancelRequestedEvent;
import com.hapbang.shared.appearance.event.AppearanceRequestSentEvent;

import lombok.extern.slf4j.Slf4j;

/**
 * 출연 이벤트({@code appearance.events.v1}, 그룹 {@code auxiliary-app.notification-appearance-alert})로 알림을 만드는 진입
 * 어댑터(N5: 출연 요청 도착·모집 지원 도착, ND-069. N6: 출연 취소 요청 도착·수락·거절, ND-082·ND-087).
 * <ul>
 *     <li>컨테이너는 앱 기동 때 시작하지 않고 첫 회원 복제본 Backfill이 끝나면 시작한다(ND-078).</li>
 *     <li>출연 요청 도착에는 사건 시각이 없어 레코드 timestamp를 대체 시각으로 넘긴다(ND-081).</li>
 *     <li>처리 실패(복제본 없음, DB 장애)는 블로킹 재시도 뒤 DLT로 간다(ND-010·ND-035).</li>
 *     <li>출연 취소 요청 3종은 필수 값이 비면 다시 처리해도 같으므로 재시도 없이 DLT로 보낸다. #817 전에는 Kafka로 나간 적이 없어
 *     이전 형식이 없다(ND-086).</li>
 *     <li>알림 대상이 아닌 출연 이벤트(리뷰 공개, 출연 변경 등)는 로그만 남기고 넘긴다.</li>
 * </ul>
 */
@Slf4j
@Component
@ConditionalOnBooleanProperty(name = NotificationKafkaConstants.APPEARANCE_EVENTS_ENABLED, matchIfMissing = true)
@KafkaListener(id = NotificationAppearanceEventListener.LISTENER_ID, idIsGroup = false, topics = "#{__listener.topic}",
        groupId = "#{__listener.groupId}", containerFactory = NotificationKafkaConfig.APPEARANCE_EVENT_CONTAINER_FACTORY)
public class NotificationAppearanceEventListener {

    /** 리스너 컨테이너 ID(소비 그룹 이름과 다르다) */
    public static final String LISTENER_ID = "notification-appearance-events";

    private final NotificationAlertService alerts;
    private final NotificationKafkaConsumerSettings settings;
    private final SkippedEventLog skippedLog = new SkippedEventLog(log);

    public NotificationAppearanceEventListener(NotificationAlertService alerts,
            @Qualifier(NotificationKafkaConfig.APPEARANCE_EVENT_SETTINGS) NotificationKafkaConsumerSettings settings) {
        this.alerts = alerts;
        this.settings = settings;
    }

    @KafkaHandler
    public void on(AppearanceRequestSentEvent event, @Header(KafkaHeaders.RECEIVED_TIMESTAMP) long timestamp) {
        alerts.requestSent(event, Instant.ofEpochMilli(timestamp));
    }

    @KafkaHandler
    public void on(AppearanceApplicationSubmittedEvent event) {
        alerts.applicationSubmitted(event);
    }

    @KafkaHandler
    public void on(AppearanceCancelRequestedEvent event) {
        alerts.cancelRequested(event, valid(() -> AppearanceCancelRequestAlerts.requested(event, null)));
    }

    @KafkaHandler
    public void on(AppearanceCancelRequestAcceptedEvent event) {
        alerts.create(valid(() -> AppearanceCancelRequestAlerts.accepted(event)));
    }

    @KafkaHandler
    public void on(AppearanceCancelRequestRejectedEvent event) {
        alerts.create(valid(() -> AppearanceCancelRequestAlerts.rejected(event)));
    }

    /** 처리기가 없는 레코드를 건너뛴다. 신뢰 패키지 밖 타입은 재시도 없이 DLT로 보낸다({@link SkippablePayloads}) */
    @KafkaHandler(isDefault = true)
    public void onUnsupported(@Payload(required = false) Object payload,
            @Header(name = KafkaHeaders.RECEIVED_KEY, required = false) String key,
            @Header(name = KafkaHeaders.RECEIVED_PARTITION, required = false) Integer partition,
            @Header(name = KafkaHeaders.OFFSET, required = false) Long offset) {
        skippedLog.skipped(SkippablePayloads.typeToSkip(payload, NotificationKafkaConstants.APPEARANCE_EVENTS_TRUSTED_PACKAGE),
                key, partition, offset);
    }

    /** 초안으로 바꾼다. 필수 값이 빈 이벤트(검증 실패)는 재시도 없이 DLT로 보낸다(ND-086). 저장 단계의 오류는 바꾸지 않는다 */
    private static NotificationDraft valid(Supplier<NotificationDraft> convert) {
        try {
            return convert.get();
        } catch (IllegalArgumentException e) {
            throw new RejectedEventException("알림을 만들 수 없는 출연 이벤트입니다.", e);
        }
    }

    public String getTopic() {
        return settings.topic();
    }

    public String getGroupId() {
        return settings.groupId();
    }
}
