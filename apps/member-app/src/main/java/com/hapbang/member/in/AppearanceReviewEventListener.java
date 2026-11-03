package com.hapbang.member.in;

import org.springframework.kafka.annotation.KafkaHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.hapbang.member.app.MemberAppearanceRatingFacade;
import com.hapbang.member.global.config.MemberKafkaConfig;
import com.hapbang.shared.appearance.event.AppearanceEventTopic;
import com.hapbang.shared.appearance.event.AppearanceReviewPublishedEvent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 출연 앱이 Kafka로 보낸 리뷰 공개 이벤트를 받아 받은 회원의 출연 평점에 더한다 (#555).
 * <p>
 * {@code appearance.events.v1}에는 출연 이벤트 타입이 섞여 온다. 리뷰 공개만 처리하고 다른 타입은 무시하고 넘어간다(ADR 0001).
 * 같은 리뷰가 다시 오면 리뷰 ID로 걸러 한 번만 반영한다. 필수 값이 없거나 범위 밖이면 다시 처리해도 같으므로
 * {@link RejectedEventException}으로 재시도 없이 DLT로 보낸다({@link MemberKafkaConfig}).
 * <p>
 * 반영이 커밋된 뒤 리스너가 끝나야 offset이 커밋된다. 커밋 전에 죽으면 같은 이벤트를 다시 받고 리뷰 ID가 흡수한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@KafkaListener(
        topics = AppearanceEventTopic.NAME,
        groupId = MemberKafkaConfig.APPEARANCE_REVIEW_GROUP,
        properties = {
                "value.deserializer=org.springframework.kafka.support.serializer.ErrorHandlingDeserializer",
                "spring.deserializer.value.delegate.class=org.springframework.kafka.support.serializer.JacksonJsonDeserializer",
                // 하위 패키지까지 자동으로 허용된다고 가정하지 않고 실제 이벤트 패키지만 믿는다.
                "spring.json.trusted.packages=com.hapbang.shared.appearance.event",
                "auto.offset.reset=earliest",
                // 토픽은 출연 앱이 partition 3개로 선언한다. 소비자가 먼저 붙어도 partition 1개짜리 토픽을 만들지 않게 한다
                "allow.auto.create.topics=false"
        })
public class AppearanceReviewEventListener {

    private final MemberAppearanceRatingFacade memberAppearanceRatingFacade;

    @KafkaHandler
    public void on(AppearanceReviewPublishedEvent event) {
        if (event.reviewId() == null || event.appearanceId() == null || event.reviewerUserId() == null
                || event.revieweeUserId() == null || event.revieweeRole() == null || event.publishedAt() == null) {
            throw new RejectedEventException("출연 평점에 필요한 값이 없습니다. reviewId=" + event.reviewId());
        }
        if (event.rating() < 1 || event.rating() > 5) {
            throw new RejectedEventException("별점이 1~5가 아닙니다. reviewId=" + event.reviewId());
        }
        if (!memberAppearanceRatingFacade.applyReviewPublished(event)) {
            log.debug("이미 반영한 출연 리뷰라 건너뛴다 reviewId={}", event.reviewId());
        }
    }

    /** 회원이 쓰지 않는 출연 이벤트(예: 출연 요청 도착). 토픽에 타입이 섞이므로 실패로 보내지 않고 넘어간다. */
    @KafkaHandler(isDefault = true)
    public void ignore(Object event) {
        log.debug("회원이 쓰지 않는 출연 이벤트라 무시한다 type={}", event.getClass().getSimpleName());
    }
}
