package com.hapbang.auxiliary.chatbot.in.kafka;

import java.time.Duration;
import java.util.List;

import com.hapbang.shared.appearance.event.AppearanceEventTopic;
import com.hapbang.shared.lecture.event.LectureEventTopic;

/**
 * 챗봇 Kafka 소비 설정 한 토픽 (#716, #717, #976). 테스트가 토픽·그룹·재시도를 바꿔 끼운다.
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

    /**
     * 처리에 실패했을 때 정해진 횟수만 다시 시도한 뒤 DLT로 보낸다. 다시 해도 같은 메시지(읽을 수 없음, 필수 값 없음)는 바로 DLT로 보낸다.
     * <ul>
     *     <li>출연·강의: 1초부터 두 배씩(최대 10초) 3번. 놓친 이벤트는 매시 싱크가 바로잡는다.</li>
     *     <li>회원: 1분 간격 3번(#976). 마스킹은 이벤트로만 알 수 있어 DLT로 보내기 전에 실패 기록을 남기고, 관리자가 다시 처리한다.
     *     기다리는 동안 이 그룹의 뒤 메시지도 기다리므로(순서 유지) 무한히 시도하지 않는다. 한 번 기다리는 1분은 소비자의
     *     {@code max.poll.interval.ms}(기본 5분)보다 짧다.</li>
     * </ul>
     *
     * @param maxRetries      DLT로 보내기 전 다시 시도하는 횟수
     * @param initialInterval 첫 재시도 간격(두 배씩 늘린다)
     * @param maxInterval     재시도 간격 상한
     */
    public record Retry(int maxRetries, Duration initialInterval, Duration maxInterval) {
    }

    /** 다시 읽어도 같은 실패(필수 필드가 빈 이벤트). 어느 토픽이든 다시 시도하지 않고, 회원 실패 기록에서는 다시 처리할 수 없는 것으로 본다 */
    public static final List<Class<? extends Exception>> NOT_RETRYABLE =
            List.of(NullPointerException.class, IllegalArgumentException.class);

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

    private static final Retry COPY_RETRY = new Retry(3, Duration.ofSeconds(1), Duration.ofSeconds(10));
    private static final Retry MEMBER_RETRY = new Retry(3, Duration.ofMinutes(1), Duration.ofMinutes(1));

    public static ChatbotKafkaSettings appearanceDefaults() {
        return of(AppearanceEventTopic.NAME, APPEARANCE_COPY_GROUP, APPEARANCE_TRUSTED_PACKAGE, COPY_RETRY);
    }

    public static ChatbotKafkaSettings memberDefaults() {
        return of(MEMBER_TOPIC, MEMBER_COPY_GROUP, MEMBER_TRUSTED_PACKAGE, MEMBER_RETRY);
    }

    public static ChatbotKafkaSettings lectureDefaults() {
        return of(LectureEventTopic.NAME, LECTURE_COPY_GROUP, LECTURE_TRUSTED_PACKAGE, COPY_RETRY);
    }

    public static ChatbotKafkaSettings of(String topic, String groupId, String trustedPackage, Retry retry) {
        return new ChatbotKafkaSettings(topic, groupId, trustedPackage, topic + "." + groupId + ".DLT", retry, 3);
    }
}
