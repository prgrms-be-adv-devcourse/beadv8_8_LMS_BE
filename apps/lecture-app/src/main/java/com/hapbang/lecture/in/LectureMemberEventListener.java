package com.hapbang.lecture.in;

import org.springframework.kafka.annotation.KafkaHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.hapbang.lecture.app.LectureUserFacade;
import com.hapbang.lecture.config.LectureKafkaConfig;
import com.hapbang.shared.member.event.UserJoinedEvent;
import com.hapbang.shared.member.event.UserModifiedEvent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 회원 앱이 Kafka로 보낸 회원 이벤트를 받아 강의 회원 복제본을 동기화한다 (#429).
 * <p>
 * {@code member.events.v1}에는 회원 이벤트 타입이 섞여 온다. 받을 타입마다 {@link KafkaHandler}를 두고,
 * 모르는 타입은 무시하고 넘어간다(ADR 0001). 복제본은 가입 이벤트로만 만들고, 늦거나 중복된 이벤트는
 * 회원 원본 버전으로 거른다({@code ReplicaUser}). 가입보다 먼저 온 변경 이벤트는 재시도 뒤 DLT로 간다({@link LectureKafkaConfig}).
 * <p>
 * 반영이 커밋된 뒤 리스너가 끝나야 offset이 커밋된다. 커밋 전에 죽으면 같은 이벤트를 다시 받고 버전 비교가 흡수한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@KafkaListener(
        topics = LectureKafkaConfig.MEMBER_EVENTS_TOPIC,
        groupId = LectureKafkaConfig.MEMBER_REPLICA_GROUP,
        properties = {
                "value.deserializer=org.springframework.kafka.support.serializer.ErrorHandlingDeserializer",
                "spring.deserializer.value.delegate.class=org.springframework.kafka.support.serializer.JacksonJsonDeserializer",
                // 하위 패키지까지 자동으로 허용된다고 가정하지 않고 실제 이벤트 패키지만 믿는다.
                "spring.json.trusted.packages=com.hapbang.shared.member.event",
                "auto.offset.reset=earliest"
        })
public class LectureMemberEventListener {

    private final LectureUserFacade lectureUserFacade;

    @KafkaHandler
    public void on(UserJoinedEvent event) {
        lectureUserFacade.syncJoined(event.user());
    }

    @KafkaHandler
    public void on(UserModifiedEvent event) {
        lectureUserFacade.syncModified(event.user());
    }

    /** 강의가 쓰지 않는 회원 이벤트(예: 로그아웃). 토픽에 타입이 섞이므로 실패로 보내지 않고 넘어간다. */
    @KafkaHandler(isDefault = true)
    public void ignore(Object event) {
        log.debug("강의가 쓰지 않는 회원 이벤트라 무시한다 type={}", event.getClass().getSimpleName());
    }
}
