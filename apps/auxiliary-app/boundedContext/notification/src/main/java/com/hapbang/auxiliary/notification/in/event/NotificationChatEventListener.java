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

import com.hapbang.auxiliary.notification.app.GroupRoomAlerts;
import com.hapbang.auxiliary.notification.app.NotificationAlertService;
import com.hapbang.auxiliary.notification.in.event.kafka.NotificationKafkaConfig;
import com.hapbang.auxiliary.notification.in.event.kafka.NotificationKafkaConstants;
import com.hapbang.auxiliary.notification.in.event.kafka.NotificationKafkaConsumerSettings;
import com.hapbang.auxiliary.notification.in.event.kafka.RejectedEventException;
import com.hapbang.auxiliary.notification.in.event.kafka.SkippablePayloads;
import com.hapbang.auxiliary.notification.in.event.kafka.SkippedEventLog;
import com.hapbang.shared.chat.event.GroupMemberKickedEvent;
import com.hapbang.shared.chat.event.GroupMembersInvitedEvent;
import com.hapbang.shared.chat.event.GroupRoomClosedEvent;

import lombok.extern.slf4j.Slf4j;

/**
 * 채팅 이벤트({@code chat.events.v1}, 그룹 {@code auxiliary-app.notification-chat-alert})로 단체방 초대·강퇴·폐쇄 알림을 만드는 진입
 * 어댑터(N9, ND-129).
 * <ul>
 *     <li>컨테이너는 앱 기동 때 시작하지 않고 첫 회원 복제본 Backfill이 끝나면 시작한다(ND-078). 새 그룹이라 {@code earliest}로 읽고,
 *     알림 시작 시각보다 이른 사건은 만들지 않는다(ND-076·ND-095).</li>
 *     <li>필수 값이 빈 이벤트(검증 실패)는 다시 해도 같으므로 재시도 없이 DLT로 보낸다. 저장 단계의 오류(받는 사람 복제본 없음, DB
 *     장애)는 바꾸지 않아 블로킹 재시도 뒤 DLT로 간다(ND-010·ND-035).</li>
 *     <li>초대·폐쇄는 받는 사람이 여럿이다. 저장은 받는 사람마다 따로 커밋하고, offset은 레코드 전체가 끝나야 커밋한다. 한 사람의
 *     저장이 실패하면 레코드 전체를 다시 처리하고(DLT에서 다시 넣을 때도 같다), 먼저 저장한 사람은 유일 키(원본 이벤트 종류, 원본 대상
 *     ID, 받는 사람)로 건너뛰므로 두 번 만들지 않는다(ND-019). 그래서 DLT로 간 레코드라도 일부 받는 사람의 알림은 이미 있을 수 있다.</li>
 * </ul>
 */
@Slf4j
@Component
@ConditionalOnBooleanProperty(name = NotificationKafkaConstants.CHAT_EVENTS_ENABLED, matchIfMissing = true)
@KafkaListener(id = NotificationChatEventListener.LISTENER_ID, idIsGroup = false, topics = "#{__listener.topic}",
        groupId = "#{__listener.groupId}", containerFactory = NotificationKafkaConfig.CHAT_EVENT_CONTAINER_FACTORY)
public class NotificationChatEventListener {

    /** 리스너 컨테이너 ID(소비 그룹 이름과 다르다) */
    public static final String LISTENER_ID = "notification-chat-events";

    private final NotificationAlertService alerts;
    private final NotificationKafkaConsumerSettings settings;
    private final SkippedEventLog skippedLog = new SkippedEventLog(log);

    public NotificationChatEventListener(NotificationAlertService alerts,
            @Qualifier(NotificationKafkaConfig.CHAT_EVENT_SETTINGS) NotificationKafkaConsumerSettings settings) {
        this.alerts = alerts;
        this.settings = settings;
    }

    @KafkaHandler
    public void on(GroupMembersInvitedEvent event) {
        valid(() -> GroupRoomAlerts.invited(event)).forEach(alerts::create);
    }

    @KafkaHandler
    public void on(GroupMemberKickedEvent event) {
        alerts.create(valid(() -> GroupRoomAlerts.kicked(event)));
    }

    /** 폐쇄한 호스트를 빼고 남은 사람이 없으면 알림 없이 커밋한다 */
    @KafkaHandler
    public void on(GroupRoomClosedEvent event) {
        valid(() -> GroupRoomAlerts.closed(event)).forEach(alerts::create);
    }

    /** 처리기가 없는 레코드를 건너뛴다. 신뢰 패키지 밖 타입은 재시도 없이 DLT로 보낸다({@link SkippablePayloads}) */
    @KafkaHandler(isDefault = true)
    public void onUnsupported(@Payload(required = false) Object payload,
            @Header(name = KafkaHeaders.RECEIVED_KEY, required = false) String key,
            @Header(name = KafkaHeaders.RECEIVED_PARTITION, required = false) Integer partition,
            @Header(name = KafkaHeaders.OFFSET, required = false) Long offset) {
        skippedLog.skipped(SkippablePayloads.typeToSkip(payload, NotificationKafkaConstants.CHAT_EVENTS_TRUSTED_PACKAGE),
                key, partition, offset);
    }

    /** 초안으로 바꾼다. 필수 값이 빈 이벤트는 재시도 없이 DLT로 보낸다. 저장 단계의 오류는 바꾸지 않는다 */
    private static <T> T valid(Supplier<T> convert) {
        try {
            return convert.get();
        } catch (IllegalArgumentException e) {
            throw new RejectedEventException("알림을 만들 수 없는 채팅 이벤트입니다.", e);
        }
    }

    public String getTopic() {
        return settings.topic();
    }

    public String getGroupId() {
        return settings.groupId();
    }
}
