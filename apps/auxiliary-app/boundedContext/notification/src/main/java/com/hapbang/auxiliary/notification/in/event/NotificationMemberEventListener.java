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

import com.hapbang.auxiliary.notification.app.MemberReviewAlerts;
import com.hapbang.auxiliary.notification.app.NotificationAlertService;
import com.hapbang.auxiliary.notification.app.NotificationMaskingService;
import com.hapbang.auxiliary.notification.app.NotificationMemberAlertService;
import com.hapbang.auxiliary.notification.app.NotificationUserSyncResult;
import com.hapbang.auxiliary.notification.app.exception.NotificationErrorCode;
import com.hapbang.auxiliary.notification.app.exception.NotificationException;
import com.hapbang.auxiliary.notification.domain.NotificationDraft;
import com.hapbang.auxiliary.notification.in.event.kafka.NotificationKafkaConfig;
import com.hapbang.auxiliary.notification.in.event.kafka.NotificationKafkaConstants;
import com.hapbang.auxiliary.notification.in.event.kafka.NotificationKafkaConsumerSettings;
import com.hapbang.auxiliary.notification.in.event.kafka.RejectedEventException;
import com.hapbang.auxiliary.notification.in.event.kafka.SkippablePayloads;
import com.hapbang.auxiliary.notification.in.event.kafka.SkippedEventLog;
import com.hapbang.shared.member.event.InfluencerChannelApprovedEvent;
import com.hapbang.shared.member.event.InfluencerChannelRejectedEvent;
import com.hapbang.shared.member.event.InfluencerUpgradeRequestRejectedEvent;
import com.hapbang.shared.member.event.UserJoinedEvent;
import com.hapbang.shared.member.event.UserMaskedEvent;
import com.hapbang.shared.member.event.UserModifiedEvent;

import lombok.extern.slf4j.Slf4j;

/**
 * 회원 이벤트 Kafka 토픽({@code member.events.v1}, 그룹 {@code auxiliary-app.notification-member-replica})을 받아 알림 회원 복제본을
 * 만드는 진입 어댑터(ND-018, ND-047). 채팅의 회원 이벤트 리스너를 복사했다(ND-028).
 * <ul>
 *     <li>처리는 {@link NotificationMemberAlertService}에 맡긴다. 복제본 반영과 가입 환영·승급 승인 알림이 한 트랜잭션이다
 *     (ND-080). 이 메서드가 정상 반환한 뒤(DB 커밋 뒤)에 offset을 커밋한다.</li>
 *     <li>가입보다 먼저 온 변경(복제본 없음)은 그 정보로 복제본을 만든다(ND-049).</li>
 *     <li>예외는 삼키지 않는다. 동시 반영 충돌·DB 장애는 블로킹 재시도 뒤 DLT로 간다. 복제할 수 없는
 *     정보는 다시 처리해도 같으므로 {@link RejectedEventException}으로 바꿔 재시도 없이 DLT로 보낸다. 읽지 못한 값·신뢰 패키지 밖
 *     타입은 역직렬화 단계에서 재시도 없이 DLT로 간다.</li>
 *     <li>파기({@code UserMaskedEvent})는 그 회원이 언급된 저장 알림의 닉네임을 바꾼다(ND-036·ND-051).</li>
 *     <li>처리기가 없는 회원 이벤트(로그아웃 등), 클래스패스에 아직 없는 종류, tombstone은 오류로 보지 않고 로그만 남긴 뒤
 *     offset을 넘긴다(ND-010).</li>
 *     <li>회원 이벤트에는 사건 시각이 없어 레코드 timestamp를 알림의 대체 시각으로 넘긴다(ND-081). 그 시각이 알림 시작 시각보다
 *     이르면 복제본만 채운다(ND-076). 그래서 보존 기간 안의 과거 회원 이벤트를 다시 읽어도 알림이 쏟아지지 않는다.</li>
 *     <li>이 리스너는 Backfill을 기다리지 않는다(ND-078·ND-080).</li>
 *     <li>채널 인증 승인·반려, 승급 반려(N7 회원 3종, ND-118)는 이벤트의 사건 시각으로 알림만 만든다(복제본은 바꾸지 않는다).
 *     필수 값이 빈 이벤트는 재시도 없이 DLT로 보낸다. 받는 사람 복제본이 없으면 저장이 실패해 재시도 뒤 DLT로 간다(ND-035).</li>
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

    private final NotificationMemberAlertService memberService;
    private final NotificationAlertService alerts;
    private final NotificationMaskingService maskingService;
    private final NotificationKafkaConsumerSettings settings;
    private final SkippedEventLog skippedLog = new SkippedEventLog(log);

    public NotificationMemberEventListener(NotificationMemberAlertService memberService,
            NotificationAlertService alerts, NotificationMaskingService maskingService,
            @Qualifier(NotificationKafkaConfig.MEMBER_EVENT_SETTINGS) NotificationKafkaConsumerSettings settings) {
        this.memberService = memberService;
        this.alerts = alerts;
        this.maskingService = maskingService;
        this.settings = settings;
    }

    @KafkaHandler
    public void on(UserJoinedEvent event, @Header(KafkaHeaders.RECEIVED_TIMESTAMP) long timestamp) {
        NotificationUserSyncResult result = rejectInvalid(
                () -> memberService.joined(event.user(), Instant.ofEpochMilli(timestamp)));
        log.info("알림 회원 가입 반영 userId={}, outcome={}, memberVersion={}", result.userId(), result.outcome(),
                result.memberVersion());
    }

    @KafkaHandler
    public void on(UserModifiedEvent event, @Header(KafkaHeaders.RECEIVED_TIMESTAMP) long timestamp) {
        NotificationUserSyncResult result = rejectInvalid(
                () -> memberService.modified(event.user(), Instant.ofEpochMilli(timestamp)));
        log.info("알림 회원 변경 반영 userId={}, outcome={}, memberVersion={}", result.userId(), result.outcome(),
                result.memberVersion());
    }

    /**
     * 회원 파기: 그 회원이 언급된 저장 알림의 닉네임을 바꾸고 문구를 다시 만든다(ND-036·ND-051). 같은 파기를 다시 받아도 결과가 같다.
     * 회원 ID가 없거나 1보다 작으면 다시 처리해도 같으므로 재시도 없이 DLT로 보낸다.
     */
    @KafkaHandler
    public void on(UserMaskedEvent event) {
        if (event.userId() == null || event.userId() < 1) {
            throw new RejectedEventException("파기된 회원 ID가 올바르지 않습니다.", null);
        }
        int changed = maskingService.mask(event.userId());
        log.info("파기된 회원의 알림 닉네임 반영 userId={}, changed={}", event.userId(), changed);
    }

    @KafkaHandler
    public void on(InfluencerChannelApprovedEvent event) {
        alerts.create(valid(() -> MemberReviewAlerts.channelApproved(event)));
    }

    @KafkaHandler
    public void on(InfluencerChannelRejectedEvent event) {
        alerts.create(valid(() -> MemberReviewAlerts.channelRejected(event)));
    }

    @KafkaHandler
    public void on(InfluencerUpgradeRequestRejectedEvent event) {
        alerts.create(valid(() -> MemberReviewAlerts.upgradeRequestRejected(event)));
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

    /** N7 회원 3종을 초안으로 바꾼다. 필수 값이 빈 이벤트(검증 실패)는 재시도 없이 DLT로 보낸다. 저장 단계의 오류는 바꾸지 않는다 */
    private static NotificationDraft valid(Supplier<NotificationDraft> convert) {
        try {
            return convert.get();
        } catch (IllegalArgumentException e) {
            throw new RejectedEventException("알림을 만들 수 없는 회원 심사 이벤트입니다.", e);
        }
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
