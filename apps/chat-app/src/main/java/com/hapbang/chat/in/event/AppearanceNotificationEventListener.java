package com.hapbang.chat.in.event;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.kafka.annotation.KafkaHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import com.hapbang.chat.app.ChatNotificationFacade;
import com.hapbang.chat.app.dto.ChatMessageResponse;
import com.hapbang.chat.app.dto.ChatNotificationResult;
import com.hapbang.chat.in.event.kafka.ChatKafkaConfig;
import com.hapbang.chat.in.event.kafka.ChatKafkaConstants;
import com.hapbang.chat.in.event.kafka.ChatKafkaConsumerSettings;
import com.hapbang.chat.in.event.kafka.RejectedEventException;
import com.hapbang.chat.in.event.kafka.SkippablePayloads;
import com.hapbang.chat.in.event.kafka.SkippedEventLog;
import com.hapbang.chat.in.websocket.ChatMessageSender;
import com.hapbang.shared.appearance.dto.AppearanceApplicationDto;
import com.hapbang.shared.appearance.dto.AppearanceRequestDto;
import com.hapbang.shared.appearance.event.AppearanceApplicationSubmittedEvent;
import com.hapbang.shared.appearance.event.AppearanceRequestSentEvent;

import lombok.extern.slf4j.Slf4j;

/**
 * 출연 이벤트 Kafka 토픽({@code appearance.events.v1})을 받아 채팅 알림 메시지로 남기고 STOMP로 보낸다(설계 문서 5.5).
 * 알림 모듈로 옮기기 전까지 출연 알림은 이 Kafka 소비로 유지한다(Q7).
 * <ul>
 *     <li>출연 요청 도착({@link AppearanceRequestSentEvent})과 출연 모집 지원 도착({@link AppearanceApplicationSubmittedEvent})만
 *     처리한다. 출연 취소 이벤트처럼 처리기가 없는 종류는 건너뛰고 커밋한다(규칙은 회원 이벤트 소비와 같다).</li>
 *     <li>알림 저장은 {@link ChatNotificationFacade}가 한다. 같은 알림은 (알림 종류, 출연 알림 대상 ID) 유일 제약으로 한 번만 남고,
 *     다시 받으면 아무것도 하지 않는다. 리스너는 트랜잭션을 열지 않아 알림이 커밋된 뒤 STOMP로 보내고, 그다음 offset을 커밋한다.</li>
 *     <li>알림에 필요한 값이 빠진 이벤트는 다시 처리해도 같으므로 {@link RejectedEventException}으로 재시도 없이 DLT로 보낸다.
 *     채팅 회원 복제본이 아직 없으면 재시도 뒤 DLT로 간다.</li>
 * </ul>
 */
@Slf4j
@Component
@ConditionalOnBooleanProperty(name = ChatKafkaConstants.APPEARANCE_EVENTS_ENABLED, matchIfMissing = true)
@KafkaListener(id = "chat-appearance-events", idIsGroup = false, topics = "#{__listener.topic}",
        groupId = "#{__listener.groupId}", containerFactory = ChatKafkaConfig.APPEARANCE_EVENT_CONTAINER_FACTORY)
public class AppearanceNotificationEventListener {

    private final ChatNotificationFacade chatNotificationFacade;
    private final ChatMessageSender chatMessageSender;
    private final ChatKafkaConsumerSettings settings;
    private final SkippedEventLog skippedLog = new SkippedEventLog(log);

    public AppearanceNotificationEventListener(ChatNotificationFacade chatNotificationFacade,
            ChatMessageSender chatMessageSender,
            @Qualifier(ChatKafkaConfig.APPEARANCE_EVENT_SETTINGS) ChatKafkaConsumerSettings settings) {
        this.chatNotificationFacade = chatNotificationFacade;
        this.chatMessageSender = chatMessageSender;
        this.settings = settings;
    }

    @KafkaHandler
    public void on(AppearanceRequestSentEvent event) {
        AppearanceRequestDto request = event.request();
        if (request == null || request.requestId() == null || request.hostId() == null || request.guestId() == null
                || request.title() == null || request.scheduledAt() == null || request.acceptDeadline() == null) {
            throw new RejectedEventException("출연 요청 도착 알림에 필요한 값이 없습니다.", null);
        }
        chatNotificationFacade.notifyAppearanceRequestSent(request).ifPresent(this::deliver);
    }

    @KafkaHandler
    public void on(AppearanceApplicationSubmittedEvent event) {
        AppearanceApplicationDto application = event.application();
        if (application == null || application.applicationId() == null || application.hostId() == null
                || application.guestId() == null || application.recruitTitle() == null) {
            throw new RejectedEventException("출연 모집 지원 도착 알림에 필요한 값이 없습니다.", null);
        }
        chatNotificationFacade.notifyAppearanceApplicationSubmitted(application).ifPresent(this::deliver);
    }

    /** 처리기가 없는 종류(예: 출연 취소 이벤트)와 tombstone은 건너뛰고, 신뢰 패키지 밖 타입은 DLT로 보낸다 */
    @KafkaHandler(isDefault = true)
    public void onUnsupported(@Payload(required = false) Object payload,
            @Header(name = KafkaHeaders.RECEIVED_KEY, required = false) String key,
            @Header(name = KafkaHeaders.RECEIVED_PARTITION, required = false) Integer partition,
            @Header(name = KafkaHeaders.OFFSET, required = false) Long offset) {
        skippedLog.skipped(SkippablePayloads.typeToSkip(payload, ChatKafkaConstants.APPEARANCE_EVENTS_TRUSTED_PACKAGE),
                key, partition, offset);
    }

    /** {@code @KafkaListener}의 토픽(SpEL {@code __listener.topic}) */
    public String getTopic() {
        return settings.topic();
    }

    /** {@code @KafkaListener}의 소비자 그룹(SpEL {@code __listener.groupId}) */
    public String getGroupId() {
        return settings.groupId();
    }

    private void deliver(ChatNotificationResult result) {
        for (ChatMessageResponse rejoinNotice : result.rejoinNotices()) {
            chatMessageSender.deliver(rejoinNotice, result.remainingUserIds());
        }
        chatMessageSender.deliver(result.sent().message(), result.sent().deliverToUserIds());
        chatMessageSender.notifyNewMessage(result.sent().message(), result.sent().notifyUserIds());
    }
}
