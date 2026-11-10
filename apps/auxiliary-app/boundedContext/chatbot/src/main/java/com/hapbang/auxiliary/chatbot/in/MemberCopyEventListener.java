package com.hapbang.auxiliary.chatbot.in;

import java.util.Objects;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.kafka.annotation.KafkaHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.hapbang.auxiliary.chatbot.app.CopyApplier;
import com.hapbang.auxiliary.chatbot.app.MemberMasking;
import com.hapbang.auxiliary.chatbot.in.kafka.ChatbotKafkaConfig;
import com.hapbang.auxiliary.chatbot.in.kafka.ChatbotKafkaSettings;
import com.hapbang.auxiliary.chatbot.out.CopyWrite;
import com.hapbang.shared.member.dto.UserDto;
import com.hapbang.shared.member.event.UserJoinedEvent;
import com.hapbang.shared.member.event.UserMaskedEvent;
import com.hapbang.shared.member.event.UserModifiedEvent;

import lombok.extern.slf4j.Slf4j;

/**
 * 회원 이벤트({@code member.events.v1})를 회원 사본과 탈퇴 처리에 반영한다 (#717, 그룹 {@code auxiliary-app.chatbot-member-copy}).
 * <ul>
 *     <li>가입·변경은 적용기로 반영한다. 변경 이벤트에서도 사본이 없으면 만든다(읽기용 사본 예외, docs/dev/module-integration.md).</li>
 *     <li>마스킹은 그 회원의 데이터를 지우고 이후 다시 받지 않게 한다({@link MemberMasking}).</li>
 *     <li>로그아웃 등 다른 타입은 기본 처리기가 건너뛴다. 최소 한 번 전달·순서 보장 없음이라 version·멱등 처리로 맞춘다.</li>
 * </ul>
 */
@Slf4j
@Component
@ConditionalOnBooleanProperty(name = ChatbotKafkaConfig.ENABLED, matchIfMissing = true)
@KafkaListener(id = "chatbot-member-copy", idIsGroup = false, topics = "#{__listener.topic}",
        groupId = "#{__listener.groupId}", containerFactory = ChatbotKafkaConfig.MEMBER_EVENT_CONTAINER_FACTORY)
public class MemberCopyEventListener {

    private final CopyApplier copyApplier;
    private final MemberMasking memberMasking;
    private final ChatbotKafkaSettings settings;

    public MemberCopyEventListener(CopyApplier copyApplier, MemberMasking memberMasking,
            @Qualifier(ChatbotKafkaConfig.MEMBER_EVENT_SETTINGS) ChatbotKafkaSettings settings) {
        this.copyApplier = copyApplier;
        this.memberMasking = memberMasking;
        this.settings = settings;
    }

    @KafkaHandler
    public void on(UserJoinedEvent event) {
        apply(event.getClass().getSimpleName(), event.user());
    }

    @KafkaHandler
    public void on(UserModifiedEvent event) {
        apply(event.getClass().getSimpleName(), event.user());
    }

    @KafkaHandler
    public void on(UserMaskedEvent event) {
        Objects.requireNonNull(event.userId(), "userId는 필수입니다.");
        MemberMasking.Result result = memberMasking.mask(event);
        log.info("회원 마스킹을 반영했다 userId={}, first={}, purged={}", event.userId(), result.first(), result.purged());
    }

    @KafkaHandler(isDefault = true)
    public void skip(Object event) {
        log.debug("챗봇이 쓰지 않는 회원 이벤트를 건너뛴다 type={}", event.getClass().getSimpleName());
    }

    private void apply(String type, UserDto user) {
        Objects.requireNonNull(user, "user는 필수입니다.");
        Objects.requireNonNull(user.id(), "user.id는 필수입니다.");
        Objects.requireNonNull(user.role(), "user.role은 필수입니다.");
        Objects.requireNonNull(user.status(), "user.status는 필수입니다.");
        CopyWrite write = copyApplier.apply(user);
        log.info("회원 이벤트를 회원 사본에 반영했다 type={}, userId={}, version={}, ourVersion={}, outcome={}", type,
                user.id(), user.version(), write.ourVersion(), write.outcome());
    }

    public String getTopic() {
        return settings.topic();
    }

    public String getGroupId() {
        return settings.groupId();
    }
}
