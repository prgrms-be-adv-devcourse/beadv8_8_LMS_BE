package com.hapbang.auxiliary.chatbot.in.kafka;

import java.time.Duration;

import com.hapbang.shared.appearance.event.AppearanceEventTopic;
import com.hapbang.shared.lecture.event.LectureEventTopic;

/**
 * 챗봇 Kafka 소비 설정 한 토픽 (#716, #717). 테스트가 토픽·그룹·재시도를 바꿔 끼운다.
 * 이름 규칙은 docs/architecture/infrastructure/kafka.md: 그룹 {@code <앱>.<목적>}, DLT {@code <topic>.<group>.DLT}.
 *
 * @param topic                받는 토픽
 * @param groupId              소비 그룹
 * @param trustedPackage       값 타입을 읽어도 되는 패키지. 와일드카드 없이 정확히 이 패키지의 타입만 읽는다
 * @param deadLetterTopic      처리하지 못한 메시지를 보내는 토픽
 * @param retry                처리에 실패했을 때 다시 시도하는 방법
 * @param deadLetterPartitions DLT 파티션 수
 */
public record ChatbotKafkaSettings(
        String topic,
        String groupId,
        String trustedPackage,
        String deadLetterTopic,
        Retry retry,
        int deadLetterPartitions
) {

    /** 처리에 실패했을 때 다시 시도하는 방법. 다시 해도 같은 메시지(읽을 수 없음, 필수 값 없음)는 어느 쪽이든 바로 DLT로 보낸다 */
    public sealed interface Retry {

        /**
         * 정해진 횟수만 다시 시도한 뒤 DLT로 보낸다. 출연·강의 토픽: 놓친 이벤트는 매시 싱크가 바로잡는다.
         *
         * @param maxRetries      DLT로 보내기 전 다시 시도하는 횟수
         * @param initialInterval 첫 재시도 간격(두 배씩 늘린다)
         * @param maxInterval     재시도 간격 상한
         */
        record ThenDeadLetter(int maxRetries, Duration initialInterval, Duration maxInterval) implements Retry {
        }

        /**
         * 성공할 때까지 같은 간격으로 다시 시도하고 DLT로 보내지 않는다(#835). 회원 토픽: 마스킹은 이벤트로만 알 수 있어
         * DLT로 보내면 지워야 할 개인 활동 데이터가 사본에 영구히 남는다. 그동안 이 그룹의 뒤 메시지는 기다린다(순서 유지).
         *
         * @param interval 재시도 간격
         */
        record UntilSuccess(Duration interval) implements Retry {
        }
    }

    /** 출연 이벤트를 받아 출연·리뷰 사본에 반영하는 그룹 */
    public static final String APPEARANCE_COPY_GROUP = "auxiliary-app.chatbot-appearance-copy";
    /** 회원 이벤트를 받아 회원 사본·마스킹에 반영하는 그룹 (#717) */
    public static final String MEMBER_COPY_GROUP = "auxiliary-app.chatbot-member-copy";
    /** 강의 이벤트를 받아 강의 사본 4종에 반영하는 그룹 (#717) */
    public static final String LECTURE_COPY_GROUP = "auxiliary-app.chatbot-lecture-copy";

    public static final String APPEARANCE_TRUSTED_PACKAGE = "com.hapbang.shared.appearance.event";
    public static final String MEMBER_TRUSTED_PACKAGE = "com.hapbang.shared.member.event";
    public static final String LECTURE_TRUSTED_PACKAGE = "com.hapbang.shared.lecture.event";

    /** 회원 이벤트 토픽. shared에 토픽 상수가 없어 회원 이벤트의 {@code @Externalized} 값과 같게 둔다 */
    public static final String MEMBER_TOPIC = "member.events.v1";

    private static final Retry THEN_DEAD_LETTER = new Retry.ThenDeadLetter(3, Duration.ofSeconds(1), Duration.ofSeconds(10));
    private static final Retry UNTIL_SUCCESS = new Retry.UntilSuccess(Duration.ofSeconds(10));

    public static ChatbotKafkaSettings appearanceDefaults() {
        return of(AppearanceEventTopic.NAME, APPEARANCE_COPY_GROUP, APPEARANCE_TRUSTED_PACKAGE, THEN_DEAD_LETTER);
    }

    public static ChatbotKafkaSettings memberDefaults() {
        return of(MEMBER_TOPIC, MEMBER_COPY_GROUP, MEMBER_TRUSTED_PACKAGE, UNTIL_SUCCESS);
    }

    public static ChatbotKafkaSettings lectureDefaults() {
        return of(LectureEventTopic.NAME, LECTURE_COPY_GROUP, LECTURE_TRUSTED_PACKAGE, THEN_DEAD_LETTER);
    }

    public static ChatbotKafkaSettings of(String topic, String groupId, String trustedPackage, Retry retry) {
        return new ChatbotKafkaSettings(topic, groupId, trustedPackage, topic + "." + groupId + ".DLT", retry, 3);
    }
}
