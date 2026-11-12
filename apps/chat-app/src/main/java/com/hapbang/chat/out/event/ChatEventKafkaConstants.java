package com.hapbang.chat.out.event;

/**
 * chat-app Kafka 발행(알림센터용 단체방 이벤트)의 값. 규칙은 {@code docs/architecture/infrastructure/kafka.md}의 "토픽 규칙"·"발행"이다.
 * <p>
 * 값은 이 클래스에만 두고 yaml·환경 변수로 바꾸지 않는다(Kafka 설정은 코드로, 사용자 결정 2026-10-30·2026-11-12). 연결 주소만
 * {@code spring.kafka.bootstrap-servers}에서 받는다. 소비 쪽 값은 {@code ChatKafkaConstants}에 있다.
 */
public final class ChatEventKafkaConstants {

    /** 토픽 partition 수(kafka.md 토픽 규칙) */
    public static final int TOPIC_PARTITIONS = 3;
    /** 브로커가 1대라 1이다 */
    public static final short TOPIC_REPLICAS = 1;

    /** 발행 확인 수준. 리더와 동기화된 복제본 모두가 받아야 성공으로 본다(브로커 1대라 리더 하나) */
    public static final String PRODUCER_ACKS = "all";

    private ChatEventKafkaConstants() {
    }
}
