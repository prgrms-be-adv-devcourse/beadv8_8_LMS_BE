package com.hapbang.auxiliary.notification.loadtest;

import java.util.List;

import com.hapbang.auxiliary.notification.in.event.kafka.NotificationKafkaConstants;

/** B0가 넣는 토픽(운영 토픽 이름 그대로, 격리 환경 전용). 파티션 수는 운영 선언과 같은 3이다 */
public final class B0Topics {

    public static final String MEMBER = NotificationKafkaConstants.MEMBER_EVENTS_TOPIC;
    public static final String BILLING = NotificationKafkaConstants.BILLING_EVENTS_TOPIC;
    public static final String APPEARANCE = NotificationKafkaConstants.APPEARANCE_EVENTS_TOPIC;
    public static final String LECTURE = NotificationKafkaConstants.LECTURE_EVENTS_TOPIC;
    public static final String CUSTOMERSUPPORT = NotificationKafkaConstants.CUSTOMERSUPPORT_EVENTS_TOPIC;
    public static final String CHAT = NotificationKafkaConstants.CHAT_EVENTS_TOPIC;
    public static final List<String> ALL = List.of(MEMBER, BILLING, APPEARANCE, LECTURE, CUSTOMERSUPPORT, CHAT);
    public static final int PARTITIONS = 3;

    private B0Topics() {
    }
}
