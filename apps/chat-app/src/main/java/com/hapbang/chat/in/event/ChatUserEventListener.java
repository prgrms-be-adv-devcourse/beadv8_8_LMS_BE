package com.hapbang.chat.in.event;

import java.util.function.Supplier;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.kafka.annotation.KafkaHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import com.hapbang.chat.app.ChatUserFacade;
import com.hapbang.chat.app.dto.ChatUserSyncResult;
import com.hapbang.chat.app.exception.ChatErrorCode;
import com.hapbang.chat.app.exception.ChatException;
import com.hapbang.chat.in.event.kafka.MissingTypeEvent;
import com.hapbang.chat.in.event.kafka.RejectedEventException;
import com.hapbang.chat.in.event.kafka.SkippablePayloads;
import com.hapbang.chat.in.event.kafka.SkippedEventLog;
import com.hapbang.shared.member.event.UserJoinedEvent;
import com.hapbang.shared.member.event.UserModifiedEvent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 회원 이벤트 Kafka 토픽({@code member.events.v1})을 받아 채팅 회원(ChatUser)으로 복제하는 진입 어댑터(설계 문서 5.3).
 * <p>
 * member-app이 shared 레코드({@link UserJoinedEvent}·{@link UserModifiedEvent})를 그대로 JSON으로 싣고 {@code __TypeId__} 헤더로
 * 종류를 알린다. 한 그룹이 같은 토픽의 두 종류를 회원 ID key 순서대로 받도록 클래스 단위 리스너에 종류별 핸들러를 둔다.
 * <ul>
 *     <li>처리는 {@link ChatUserFacade}에 위임한다. 회원 반영과 연결 재검사 작업 기록은 서비스의 한 트랜잭션이고,
 *     이 메서드가 정상 반환한 뒤(DB 커밋 뒤)에 offset을 커밋한다({@link ChatMemberEventKafkaConfig}).</li>
 *     <li>예외는 삼키지 않는다. 가입 전 변경(복제본 없음)과 동시 반영 충돌은 제한된 재시도 뒤 DLT로 간다.
 *     복제할 수 없는 정보({@code INVALID_REQUEST})는 다시 처리해도 같으므로 {@link RejectedEventException}으로 바꿔
 *     재시도 없이 DLT로 보낸다. 읽지 못한 값·신뢰 패키지 밖 타입은 역직렬화 단계에서 재시도 없이 DLT로 간다.</li>
 *     <li>모르는 종류는 건너뛴다(사용자 결정, 2026-10-30). 회원 쪽이 같은 토픽에 새 이벤트(예: 로그아웃)를 더할 수 있으므로,
 *     신뢰 패키지 안의 처리기가 없는 종류, 신뢰 패키지 안인데 chat-app 클래스패스에 아직 없는 종류({@link MissingTypeEvent}),
 *     값이 없는 레코드(tombstone)는 오류로 보지 않고 로그만 남긴 뒤 offset을 넘긴다.</li>
 *     <li>채팅할 수 없게 된 회원의 연결을 여기서 직접 닫지 않는다. 연결 종료는 기록한 제어 작업이 제어 Stream·제어 채널을 거쳐 처리한다.</li>
 * </ul>
 */
@Slf4j
@Component
@ConditionalOnBooleanProperty(name = ChatMemberEventProperties.ENABLED_PROPERTY, matchIfMissing = true)
@RequiredArgsConstructor
@KafkaListener(id = "chat-member-events", idIsGroup = false, topics = "#{__listener.topic}",
        groupId = "#{__listener.groupId}", containerFactory = ChatMemberEventKafkaConfig.CONTAINER_FACTORY)
public class ChatUserEventListener {

    private final ChatUserFacade chatUserFacade;
    private final ChatMemberEventProperties properties;
    private final SkippedEventLog skippedLog = new SkippedEventLog(log);

    @KafkaHandler
    public void on(UserJoinedEvent event) {
        ChatUserSyncResult result = rejectInvalid(() -> chatUserFacade.syncJoined(event.user()));
        log.info("채팅 회원 가입 반영 userId={}, outcome={}, memberVersion={}", result.userId(), result.outcome(),
                result.memberVersion());
    }

    @KafkaHandler
    public void on(UserModifiedEvent event) {
        ChatUserSyncResult result = rejectInvalid(() -> chatUserFacade.syncModified(event.user()));
        log.info("채팅 회원 변경 반영 userId={}, outcome={}, memberVersion={}, canChat={}", result.userId(),
                result.outcome(), result.memberVersion(), result.canChat());
    }

    /**
     * 처리기가 없는 레코드를 건너뛴다. 건너뛰는 것은 tombstone, 클래스패스에 없는 신뢰 패키지 안 종류({@link MissingTypeEvent}),
     * 패키지가 정확히 신뢰 패키지인 다른 레코드(예: 로그아웃 이벤트)뿐이고, 그 밖 타입은 {@link RejectedEventException}으로
     * 재시도 없이 DLT로 보낸다({@link SkippablePayloads}). 건너뛰면 정상 반환해 offset을 커밋한다. 로그 규칙은 {@link SkippedEventLog}.
     */
    @KafkaHandler(isDefault = true)
    public void onUnsupported(@Payload(required = false) Object payload,
            @Header(name = KafkaHeaders.RECEIVED_KEY, required = false) String key,
            @Header(name = KafkaHeaders.RECEIVED_PARTITION, required = false) Integer partition,
            @Header(name = KafkaHeaders.OFFSET, required = false) Long offset) {
        skippedLog.skipped(SkippablePayloads.typeToSkip(payload, ChatMemberEventKafkaConfig.TRUSTED_PACKAGE), key,
                partition, offset);
    }

    /** 따로 세는 건너뛴 종류 수(테스트용) */
    int skipCounterKeys() {
        return skippedLog.counterKeys();
    }

    private static ChatUserSyncResult rejectInvalid(Supplier<ChatUserSyncResult> sync) {
        try {
            return sync.get();
        } catch (ChatException e) {
            if (e.getErrorCode() == ChatErrorCode.INVALID_REQUEST) {
                throw new RejectedEventException("복제할 수 없는 회원 정보입니다.", e);
            }
            throw e;
        }
    }

    /** {@code @KafkaListener}의 토픽(SpEL {@code __listener.topic}) */
    public String getTopic() {
        return properties.topic();
    }

    /** {@code @KafkaListener}의 소비자 그룹(SpEL {@code __listener.groupId}) */
    public String getGroupId() {
        return properties.groupId();
    }
}
