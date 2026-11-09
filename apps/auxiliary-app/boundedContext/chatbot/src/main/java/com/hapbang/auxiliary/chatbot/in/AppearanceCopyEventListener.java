package com.hapbang.auxiliary.chatbot.in;

import java.util.Objects;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.kafka.annotation.KafkaHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.hapbang.auxiliary.chatbot.app.CopyApplier;
import com.hapbang.auxiliary.chatbot.in.kafka.ChatbotKafkaConfig;
import com.hapbang.auxiliary.chatbot.in.kafka.ChatbotKafkaSettings;
import com.hapbang.auxiliary.chatbot.out.CopyWrite;
import com.hapbang.shared.appearance.dto.AppearanceSyncDto;
import com.hapbang.shared.appearance.event.AppearanceChangedEvent;
import com.hapbang.shared.appearance.event.AppearanceReviewPublishedEvent;

import lombok.extern.slf4j.Slf4j;

/**
 * 출연 이벤트({@code appearance.events.v1})를 출연·리뷰 사본에 반영한다 (#716, 그룹 {@code auxiliary-app.chatbot-appearance-copy}).
 * <ul>
 *     <li>출연 변경({@link AppearanceChangedEvent})과 리뷰 공개({@link AppearanceReviewPublishedEvent})만 쓴다. 요청·지원 도착 등
 *     다른 타입은 기본 처리기가 건너뛴다.</li>
 *     <li>최소 한 번 전달·순서 보장 없음이라 적용기가 version(출연)·해시(리뷰)로 멱등 처리한다.</li>
 *     <li>트랜잭션을 열지 않는다(반영은 한 문장). 반영이 끝나야 offset을 커밋한다.</li>
 * </ul>
 */
@Slf4j
@Component
@ConditionalOnBooleanProperty(name = ChatbotKafkaConfig.ENABLED, matchIfMissing = true)
@KafkaListener(id = "chatbot-appearance-copy", idIsGroup = false, topics = "#{__listener.topic}",
        groupId = "#{__listener.groupId}", containerFactory = ChatbotKafkaConfig.APPEARANCE_EVENT_CONTAINER_FACTORY)
public class AppearanceCopyEventListener {

    private final CopyApplier copyApplier;
    private final ChatbotKafkaSettings settings;

    public AppearanceCopyEventListener(CopyApplier copyApplier,
            @Qualifier(ChatbotKafkaConfig.APPEARANCE_EVENT_SETTINGS) ChatbotKafkaSettings settings) {
        this.copyApplier = copyApplier;
        this.settings = settings;
    }

    @KafkaHandler
    public void on(AppearanceChangedEvent event) {
        AppearanceSyncDto appearance = Objects.requireNonNull(event.appearance(), "appearance는 필수입니다.");
        CopyWrite write = copyApplier.apply(appearance, null);
        log.info("출연 변경 이벤트를 출연 사본에 반영했다 appearanceId={}, version={}, ourVersion={}, outcome={}",
                appearance.id(), appearance.version(), write.ourVersion(), write.outcome());
    }

    @KafkaHandler
    public void on(AppearanceReviewPublishedEvent event) {
        CopyWrite write = copyApplier.apply(event);
        log.info("리뷰 공개 이벤트를 리뷰 사본에 반영했다 reviewId={}, appearanceId={}, outcome={}",
                event.reviewId(), event.appearanceId(), write.outcome());
    }

    @KafkaHandler(isDefault = true)
    public void skip(Object event) {
        log.debug("챗봇이 쓰지 않는 출연 이벤트를 건너뛴다 type={}", event.getClass().getSimpleName());
    }

    public String getTopic() {
        return settings.topic();
    }

    public String getGroupId() {
        return settings.groupId();
    }
}
