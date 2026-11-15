package com.hapbang.coupon.out.kafka;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

import com.hapbang.shared.coupon.event.CouponEventTopic;

/**
 * 쿠폰 이벤트 토픽 선언. 팀 Kafka 규칙대로 업무 토픽은 발행 앱(쿠폰)이 선언한다: partition 3, replication factor 1,
 * 보존은 broker 기본값. DLT 토픽은 실패 처리를 소유한 소비자 설정이 선언한다.
 */
@Configuration(proxyBeanMethods = false)
public class CouponKafkaTopicConfig {

    private static final int PARTITIONS = 3;

    @Bean
    NewTopic couponEventsTopic() {
        return TopicBuilder.name(CouponEventTopic.NAME).partitions(PARTITIONS).replicas(1).build();
    }
}
