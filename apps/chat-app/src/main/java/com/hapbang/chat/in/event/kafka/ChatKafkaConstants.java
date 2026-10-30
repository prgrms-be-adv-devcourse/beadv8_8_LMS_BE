package com.hapbang.chat.in.event.kafka;

import java.time.Duration;

/**
 * chat-app Kafka 소비의 이름과 값. 규칙은 {@code docs/architecture/infrastructure/kafka.md}의 "소비"·"재시도와 DLT"다.
 * <p>
 * 값은 이 클래스에만 두고 yaml·환경 변수로 바꾸지 않는다(사용자 결정, 2026-10-30). 설정 클래스({@link ChatKafkaConfig},
 * {@link ChatKafkaErrorHandlerConfig}, {@link ChatKafkaTopicConfig})가 이 값으로 빈을 만든다. 남은 설정 키는 소비를 켜고 끄는
 * {@code *.enabled} 둘뿐이다.
 */
public final class ChatKafkaConstants {

    // 회원 이벤트 → 채팅 회원 복제본

    public static final String MEMBER_EVENTS_TOPIC = "member.events.v1";
    /** 소비 그룹. 이름은 {@code <앱>.<목적>}이다 */
    public static final String MEMBER_REPLICA_GROUP = "chat-app.member-replica";
    /** 그룹별 DLT {@code <토픽>.<그룹>.DLT}. Spring Kafka 기본값({@code <토픽>.DLT})은 쓰지 않는다 */
    public static final String MEMBER_EVENTS_DLT = MEMBER_EVENTS_TOPIC + "." + MEMBER_REPLICA_GROUP + ".DLT";
    /** 역직렬화기가 믿는 유일한 패키지(와일드카드 불가) */
    public static final String MEMBER_EVENTS_TRUSTED_PACKAGE = "com.hapbang.shared.member.event";
    /** 회원 이벤트 소비를 켤지. 없으면 켜고, 모듈 테스트 설정이 끈다 */
    public static final String MEMBER_EVENTS_ENABLED = "hapbang.chat.member-events.enabled";

    // 출연 이벤트 → 채팅 출연 알림

    public static final String APPEARANCE_EVENTS_TOPIC = "appearance.events.v1";
    public static final String APPEARANCE_NOTIFICATION_GROUP = "chat-app.appearance-notification";
    public static final String APPEARANCE_EVENTS_DLT = APPEARANCE_EVENTS_TOPIC + "." + APPEARANCE_NOTIFICATION_GROUP + ".DLT";
    public static final String APPEARANCE_EVENTS_TRUSTED_PACKAGE = "com.hapbang.shared.appearance.event";
    public static final String APPEARANCE_EVENTS_ENABLED = "hapbang.chat.appearance-events.enabled";

    // 소비

    /** 커밋한 offset이 없는 새 그룹의 시작점 */
    public static final String AUTO_OFFSET_RESET = "earliest";

    // 블로킹 재시도: 1초, 2초, 4초(상한 10초)로 3회 재시도한 뒤 DLT

    /** 첫 처리 실패 뒤 재시도 횟수 */
    public static final int MAX_RETRIES = 3;
    public static final Duration RETRY_INITIAL_INTERVAL = Duration.ofSeconds(1);
    public static final double RETRY_MULTIPLIER = 2.0;
    public static final Duration RETRY_MAX_INTERVAL = Duration.ofSeconds(10);

    // DLT

    /** 원본과 같은 partition 수. 원본과 같은 partition 번호로 보낸다 */
    public static final int DEAD_LETTER_PARTITIONS = 3;
    /** 브로커가 1대라 1이다 */
    public static final short DEAD_LETTER_REPLICAS = 1;
    public static final Duration DEAD_LETTER_RETENTION = Duration.ofDays(7);
    /**
     * DLT 발행 한 번에 쓰는 최대 시간(메타데이터 대기 절반 + 전송 완료 대기 나머지). 넘거나 실패하면 처리 성공으로 보지 않고 다시
     * 처리한다. 미확정 초기값(Q12)
     */
    public static final Duration DEAD_LETTER_SEND_TIMEOUT = Duration.ofSeconds(10);
    /** 이보다 짧으면 나눈 몫이 너무 작아 정상 발행도 실패한다 */
    public static final Duration MIN_DEAD_LETTER_SEND_TIMEOUT = Duration.ofSeconds(1);

    private ChatKafkaConstants() {
    }
}
