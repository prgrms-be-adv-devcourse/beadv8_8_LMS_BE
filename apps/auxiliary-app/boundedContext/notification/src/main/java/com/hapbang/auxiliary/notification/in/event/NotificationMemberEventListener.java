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

import com.hapbang.auxiliary.notification.app.NotificationUserSyncResult;
import com.hapbang.auxiliary.notification.app.NotificationUserSyncService;
import com.hapbang.auxiliary.notification.app.exception.NotificationErrorCode;
import com.hapbang.auxiliary.notification.app.exception.NotificationException;
import com.hapbang.auxiliary.notification.in.event.kafka.NotificationKafkaConfig;
import com.hapbang.auxiliary.notification.in.event.kafka.NotificationKafkaConstants;
import com.hapbang.auxiliary.notification.in.event.kafka.NotificationKafkaConsumerSettings;
import com.hapbang.auxiliary.notification.in.event.kafka.RejectedEventException;
import com.hapbang.auxiliary.notification.in.event.kafka.SkippablePayloads;
import com.hapbang.auxiliary.notification.in.event.kafka.SkippedEventLog;
import com.hapbang.shared.member.event.UserJoinedEvent;
import com.hapbang.shared.member.event.UserModifiedEvent;

import lombok.extern.slf4j.Slf4j;

/**
 * 회원 이벤트 Kafka 토픽({@code member.events.v1}, 그룹 {@code auxiliary-app.notification-member-replica})을 받아 알림 회원 복제본을
 * 만드는 진입 어댑터(ND-018, ND-047). 채팅의 회원 이벤트 리스너를 복사했다(ND-028).
 * <ul>
 *     <li>처리는 {@link NotificationUserSyncService}에 맡긴다. 이 메서드가 정상 반환한 뒤(DB 커밋 뒤)에 offset을 커밋한다.</li>
 *     <li>예외는 삼키지 않는다. 가입 전 변경(복제본 없음, ND-035)과 동시 반영 충돌은 블로킹 재시도 뒤 DLT로 간다. 복제할 수 없는
 *     정보는 다시 처리해도 같으므로 {@link RejectedEventException}으로 바꿔 재시도 없이 DLT로 보낸다. 읽지 못한 값·신뢰 패키지 밖
 *     타입은 역직렬화 단계에서 재시도 없이 DLT로 간다.</li>
 *     <li>처리기가 없는 회원 이벤트(파기·로그아웃 등), 클래스패스에 아직 없는 종류, tombstone은 오류로 보지 않고 로그만 남긴 뒤
 *     offset을 넘긴다(ND-010). 파기({@code UserMaskedEvent})로 저장 문구를 고치는 일은 알림 저장(N2)과 함께 한다(ND-036, ND-046).</li>
 *     <li>알림은 만들지 않는다. 그래서 첫 배포 때 보존 기간 안의 과거 회원 이벤트를 읽어도 복제본만 채운다(ND-048).</li>
 * </ul>
 */
@Slf4j
@Component
@ConditionalOnBooleanProperty(name = NotificationKafkaConstants.MEMBER_EVENTS_ENABLED, matchIfMissing = true)
@KafkaListener(id = NotificationMemberEventListener.LISTENER_ID, idIsGroup = false, topics = "#{__listener.topic}",
        groupId = "#{__listener.groupId}", containerFactory = NotificationKafkaConfig.MEMBER_EVENT_CONTAINER_FACTORY)
public class NotificationMemberEventListener {

    /** 리스너 컨테이너 ID(소비 그룹 이름과 다르다) */
    public static final String LISTENER_ID = "notification-member-events";

    private final NotificationUserSyncService syncService;
    private final NotificationKafkaConsumerSettings settings;
    private final SkippedEventLog skippedLog = new SkippedEventLog(log);

    public NotificationMemberEventListener(NotificationUserSyncService syncService,
            @Qualifier(NotificationKafkaConfig.MEMBER_EVENT_SETTINGS) NotificationKafkaConsumerSettings settings) {
        this.syncService = syncService;
        this.settings = settings;
    }

    @KafkaHandler
    public void on(UserJoinedEvent event) {
        NotificationUserSyncResult result = rejectInvalid(() -> syncService.syncJoined(event.user()));
        log.info("알림 회원 가입 반영 userId={}, outcome={}, memberVersion={}", result.userId(), result.outcome(),
                result.memberVersion());
    }

    @KafkaHandler
    public void on(UserModifiedEvent event) {
        NotificationUserSyncResult result = rejectInvalid(() -> syncService.syncModified(event.user()));
        log.info("알림 회원 변경 반영 userId={}, outcome={}, memberVersion={}", result.userId(), result.outcome(),
                result.memberVersion());
    }

    /**
     * 처리기가 없는 레코드를 건너뛴다. 건너뛰는 것은 tombstone, 클래스패스에 없는 신뢰 패키지 안 종류, 패키지가 정확히 신뢰 패키지인
     * 다른 레코드뿐이고, 그 밖 타입은 {@link RejectedEventException}으로 재시도 없이 DLT로 보낸다({@link SkippablePayloads}).
     */
    @KafkaHandler(isDefault = true)
    public void onUnsupported(@Payload(required = false) Object payload,
            @Header(name = KafkaHeaders.RECEIVED_KEY, required = false) String key,
            @Header(name = KafkaHeaders.RECEIVED_PARTITION, required = false) Integer partition,
            @Header(name = KafkaHeaders.OFFSET, required = false) Long offset) {
        skippedLog.skipped(SkippablePayloads.typeToSkip(payload, NotificationKafkaConstants.MEMBER_EVENTS_TRUSTED_PACKAGE),
                key, partition, offset);
    }

    private static NotificationUserSyncResult rejectInvalid(Supplier<NotificationUserSyncResult> sync) {
        try {
            return sync.get();
        } catch (NotificationException e) {
            if (e.getErrorCode() == NotificationErrorCode.INVALID_MEMBER_EVENT) {
                throw new RejectedEventException("복제할 수 없는 회원 정보입니다.", e);
            }
            throw e;
        }
    }

    /** {@code @KafkaListener}의 토픽(SpEL {@code __listener.topic}) */
    public String getTopic() {
        return settings.topic();
    }

    /** {@code @KafkaListener}의 소비자 그룹(SpEL {@code __listener.groupId}) */
    public String getGroupId() {
        return settings.groupId();
    }
}
