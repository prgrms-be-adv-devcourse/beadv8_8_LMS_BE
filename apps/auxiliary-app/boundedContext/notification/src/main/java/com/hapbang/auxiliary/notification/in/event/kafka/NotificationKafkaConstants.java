package com.hapbang.auxiliary.notification.in.event.kafka;

import java.time.Duration;

/**
 * 알림 모듈 Kafka 소비의 이름과 값. 규칙은 {@code docs/architecture/infrastructure/kafka.md}의 "소비"·"재시도와 DLT"이고, 채팅 코드를
 * 복사해 쓴다(ND-028).
 * <p>
 * 값은 이 클래스에만 두고 yaml·환경 변수로 바꾸지 않는다. 남은 설정 키는 소비자마다 켜고 끄는 {@code *.enabled}뿐이다.
 * 소비 그룹 이름은 {@code auxiliary-app.notification-<목적>}이다(ND-047).
 */
public final class NotificationKafkaConstants {

    /** 소비 그룹 이름 앞부분(ND-047). 뒤에 목적을 붙인다 */
    public static final String GROUP_PREFIX = "auxiliary-app.notification-";

    // 회원 이벤트 → 알림 회원 복제본(ND-018)

    public static final String MEMBER_EVENTS_TOPIC = "member.events.v1";
    public static final String MEMBER_REPLICA_GROUP = GROUP_PREFIX + "member-replica";
    /** 그룹별 DLT {@code <토픽>.<그룹>.DLT}(ND-010). Spring Kafka 기본값({@code <토픽>.DLT})은 쓰지 않는다 */
    public static final String MEMBER_EVENTS_DLT = deadLetterTopic(MEMBER_EVENTS_TOPIC, MEMBER_REPLICA_GROUP);
    /** 역직렬화기가 믿는 유일한 패키지(와일드카드 불가) */
    public static final String MEMBER_EVENTS_TRUSTED_PACKAGE = "com.hapbang.shared.member.event";
    /** 회원 이벤트 소비를 켤지. 없으면 켜고(운영), 모듈 테스트 설정이 끈다 */
    public static final String MEMBER_EVENTS_ENABLED = "hapbang.notification.member-events.enabled";

    // 출연 이벤트 → 알림(N5: 출연 요청 도착·모집 지원 도착, ND-069. N6: 출연 취소 요청 3종, ND-082·ND-087)

    public static final String APPEARANCE_EVENTS_TOPIC = "appearance.events.v1";
    public static final String APPEARANCE_ALERT_GROUP = GROUP_PREFIX + "appearance-alert";
    public static final String APPEARANCE_EVENTS_DLT = deadLetterTopic(APPEARANCE_EVENTS_TOPIC, APPEARANCE_ALERT_GROUP);
    public static final String APPEARANCE_EVENTS_TRUSTED_PACKAGE = "com.hapbang.shared.appearance.event";
    public static final String APPEARANCE_EVENTS_ENABLED = "hapbang.notification.appearance-events.enabled";

    // 지갑 이벤트 → 알림(N5: 지갑 7종, ND-069)

    public static final String BILLING_EVENTS_TOPIC = "billing.events.v1";
    public static final String BILLING_ALERT_GROUP = GROUP_PREFIX + "billing-alert";
    public static final String BILLING_EVENTS_DLT = deadLetterTopic(BILLING_EVENTS_TOPIC, BILLING_ALERT_GROUP);
    public static final String BILLING_EVENTS_TRUSTED_PACKAGE = "com.hapbang.shared.billing.event";
    public static final String BILLING_EVENTS_ENABLED = "hapbang.notification.billing-events.enabled";

    // 강의 이벤트 → 알림(N6: 이의 제기 결과 4종, ND-082·ND-087·ND-096)

    public static final String LECTURE_EVENTS_TOPIC = "lecture.events.v1";
    public static final String LECTURE_ALERT_GROUP = GROUP_PREFIX + "lecture-alert";
    public static final String LECTURE_EVENTS_DLT = deadLetterTopic(LECTURE_EVENTS_TOPIC, LECTURE_ALERT_GROUP);
    public static final String LECTURE_EVENTS_TRUSTED_PACKAGE = "com.hapbang.shared.lecture.event";
    public static final String LECTURE_EVENTS_ENABLED = "hapbang.notification.lecture-events.enabled";

    // 고객센터 이벤트 → 알림(N7: 문의 답변 도착·이의 제기 접수 불가, ND-122)

    public static final String CUSTOMERSUPPORT_EVENTS_TOPIC = "customersupport.events.v1";
    public static final String CUSTOMERSUPPORT_ALERT_GROUP = GROUP_PREFIX + "customersupport-alert";
    public static final String CUSTOMERSUPPORT_EVENTS_DLT = deadLetterTopic(CUSTOMERSUPPORT_EVENTS_TOPIC,
            CUSTOMERSUPPORT_ALERT_GROUP);
    public static final String CUSTOMERSUPPORT_EVENTS_TRUSTED_PACKAGE = "com.hapbang.shared.customersupport.event";
    public static final String CUSTOMERSUPPORT_EVENTS_ENABLED = "hapbang.notification.customersupport-events.enabled";

    // 소비

    /**
     * 커밋한 offset이 없는 새 그룹의 시작점(kafka.md 팀 규칙). 보존 기간 안의 과거 회원 이벤트로 복제본을 채운다(ND-048). 알림을 만드는
     * 그룹이 읽는 과거 사건은 알림 시작 시각으로 거른다(ND-076)
     */
    public static final String AUTO_OFFSET_RESET = "earliest";
    /** 소비자 동시성. 알림 담당 제안(U13, 확정 전)대로 1로 시작한다 */
    public static final int CONCURRENCY = 1;

    // 블로킹 재시도: 1초, 2초, 4초(상한 10초)로 3회 재시도한 뒤 DLT

    /** 첫 처리 실패 뒤 재시도 횟수 */
    public static final int MAX_RETRIES = 3;
    public static final Duration RETRY_INITIAL_INTERVAL = Duration.ofSeconds(1);
    public static final double RETRY_MULTIPLIER = 2.0;
    public static final Duration RETRY_MAX_INTERVAL = Duration.ofSeconds(10);

    // DLT

    /** 원본과 같은 partition 수(회원·출연·지갑·강의 토픽 모두 3개). 원본과 같은 partition 번호로 보낸다 */
    public static final int DEAD_LETTER_PARTITIONS = 3;
    /** 브로커가 1대라 1이다 */
    public static final short DEAD_LETTER_REPLICAS = 1;
    public static final Duration DEAD_LETTER_RETENTION = Duration.ofDays(7);
    /** DLT 발행 한 번에 쓰는 최대 시간(메타데이터 대기 절반 + 전송 완료 대기 나머지). 넘거나 실패하면 처리 성공으로 보지 않는다 */
    public static final Duration DEAD_LETTER_SEND_TIMEOUT = Duration.ofSeconds(10);
    /** 이보다 짧으면 나눈 몫이 너무 작아 정상 발행도 실패한다 */
    public static final Duration MIN_DEAD_LETTER_SEND_TIMEOUT = Duration.ofSeconds(1);

    private NotificationKafkaConstants() {
    }

    /** 그룹별 DLT 이름 {@code <토픽>.<그룹>.DLT} */
    public static String deadLetterTopic(String topic, String groupId) {
        return topic + "." + groupId + ".DLT";
    }
}
