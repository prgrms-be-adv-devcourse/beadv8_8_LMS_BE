package com.hapbang.auxiliary.notification.in.event;

import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.kafka.annotation.KafkaHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import com.hapbang.auxiliary.notification.app.CustomerSupportAlerts;
import com.hapbang.auxiliary.notification.app.NotificationAlertService;
import com.hapbang.auxiliary.notification.domain.NotificationDraft;
import com.hapbang.auxiliary.notification.in.event.kafka.NotificationKafkaConfig;
import com.hapbang.auxiliary.notification.in.event.kafka.NotificationKafkaConstants;
import com.hapbang.auxiliary.notification.in.event.kafka.NotificationKafkaConsumerSettings;
import com.hapbang.auxiliary.notification.in.event.kafka.RejectedEventException;
import com.hapbang.auxiliary.notification.in.event.kafka.SkippablePayloads;
import com.hapbang.auxiliary.notification.in.event.kafka.SkippedEventLog;
import com.hapbang.shared.customersupport.event.InquiryAnsweredEvent;
import com.hapbang.shared.customersupport.event.LectureObjectionFileRejectedEvent;

import lombok.extern.slf4j.Slf4j;

/**
 * 고객센터 이벤트({@code customersupport.events.v1}, 그룹 {@code auxiliary-app.notification-customersupport-alert})로 알림을
 * 만드는 진입 어댑터(N7: 문의 답변 도착·이의 제기 접수 불가, ND-122).
 * <ul>
 *     <li>컨테이너는 앱 기동 때 시작하지 않고 첫 회원 복제본 Backfill이 끝나면 시작한다(ND-078).</li>
 *     <li>두 이벤트 모두 사건 시각을 싣는다. 그 시각이 알림 시작 시각보다 이르면 만들지 않는다(ND-076).</li>
 *     <li>같은 토픽의 이의 제기 명령(강의가 소비한다)은 건너뛴다.</li>
 * </ul>
 */
@Slf4j
@Component
@ConditionalOnBooleanProperty(name = NotificationKafkaConstants.CUSTOMERSUPPORT_EVENTS_ENABLED, matchIfMissing = true)
@KafkaListener(id = NotificationCustomerSupportEventListener.LISTENER_ID, idIsGroup = false,
        topics = "#{__listener.topic}", groupId = "#{__listener.groupId}",
        containerFactory = NotificationKafkaConfig.CUSTOMERSUPPORT_EVENT_CONTAINER_FACTORY)
public class NotificationCustomerSupportEventListener {

    /** 리스너 컨테이너 ID(소비 그룹 이름과 다르다) */
    public static final String LISTENER_ID = "notification-customersupport-events";

    private final NotificationAlertService alerts;
    private final NotificationKafkaConsumerSettings settings;
    private final SkippedEventLog skippedLog = new SkippedEventLog(log);

    public NotificationCustomerSupportEventListener(NotificationAlertService alerts,
            @Qualifier(NotificationKafkaConfig.CUSTOMERSUPPORT_EVENT_SETTINGS) NotificationKafkaConsumerSettings settings) {
        this.alerts = alerts;
        this.settings = settings;
    }

    @KafkaHandler
    public void on(InquiryAnsweredEvent event) {
        alerts.create(valid(() -> CustomerSupportAlerts.inquiryAnswered(event)));
    }

    @KafkaHandler
    public void on(LectureObjectionFileRejectedEvent event) {
        alerts.create(valid(() -> CustomerSupportAlerts.lectureObjectionFileRejected(event)));
    }

    /** 처리기가 없는 레코드를 건너뛴다. 신뢰 패키지 밖 타입은 재시도 없이 DLT로 보낸다({@link SkippablePayloads}) */
    @KafkaHandler(isDefault = true)
    public void onUnsupported(@Payload(required = false) Object payload,
            @Header(name = KafkaHeaders.RECEIVED_KEY, required = false) String key,
            @Header(name = KafkaHeaders.RECEIVED_PARTITION, required = false) Integer partition,
            @Header(name = KafkaHeaders.OFFSET, required = false) Long offset) {
        skippedLog.skipped(SkippablePayloads.typeToSkip(payload,
                NotificationKafkaConstants.CUSTOMERSUPPORT_EVENTS_TRUSTED_PACKAGE), key, partition, offset);
    }

    /** 초안으로 바꾼다. 필수 값이 빈 이벤트(검증 실패)는 재시도 없이 DLT로 보낸다. 저장 단계의 오류는 바꾸지 않는다 */
    private static NotificationDraft valid(Supplier<NotificationDraft> convert) {
        try {
            return convert.get();
        } catch (IllegalArgumentException e) {
            throw new RejectedEventException("알림을 만들 수 없는 고객센터 이벤트입니다.", e);
        }
    }

    public String getTopic() {
        return settings.topic();
    }

    public String getGroupId() {
        return settings.groupId();
    }
}
