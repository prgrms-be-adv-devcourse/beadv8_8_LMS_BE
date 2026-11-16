package com.hapbang.auxiliary.notification.in.event.kafka;

/**
 * 알림 Kafka 리스너가 구독할 토픽·소비자 그룹을 {@code @KafkaListener}에 내보인다(ND-181). 리스너는
 * {@code topics = "#{__listener.topic}", groupId = "#{__listener.groupId}"}로 이 getter를 읽는다. 값은 리스너가 받은 소비자 값
 * 빈이라, 시험이 그 빈을 바꾸면 리스너도 바뀐 토픽·그룹을 쓴다.
 */
public interface NotificationEventSubscriber {

    /** 리스너가 이름으로 받은 소비자 값 */
    NotificationKafkaConsumerSettings settings();

    /** {@code @KafkaListener}의 토픽(SpEL {@code __listener.topic}) */
    default String getTopic() {
        return settings().topic();
    }

    /** {@code @KafkaListener}의 소비자 그룹(SpEL {@code __listener.groupId}) */
    default String getGroupId() {
        return settings().groupId();
    }
}
