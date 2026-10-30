package com.hapbang.member.global.config;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.modulith.events.EventExternalizationConfiguration;

/**
 * 회원 앱의 Kafka 발행 설정. 규칙은 docs/architecture/infrastructure/kafka.md의 "토픽 규칙"·"발행"이다.
 * <ul>
 *     <li>회원 이벤트 토픽은 발행하는 이 앱이 선언한다. broker는 토픽을 자동으로 만들지 않는다.
 *     보존은 broker 기본값(시간 기준)을 따른다.</li>
 *     <li>토픽과 key는 shared 레코드의 {@code @Externalized}가 정한다. 여기서는 Modulith 기본 외부화 설정
 *     ({@code @Externalized} 이벤트를 고른다)에 {@code eventType}·{@code eventVersion} 헤더만 더한다.</li>
 *     <li>헤더 값은 UTF-8 바이트로 넣는다. 문자열로 넣으면 Spring Kafka가 타입 정보 헤더({@code spring_json_header_types})를 더 붙인다.
 *     역직렬화에 쓰는 {@code __TypeId__}는 JSON 변환기가 따로 붙인다.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
public class MemberKafkaConfig {

    public static final String MEMBER_EVENTS_TOPIC = "member.events.v1";
    public static final String EVENT_TYPE_HEADER = "eventType";
    public static final String EVENT_VERSION_HEADER = "eventVersion";

    private static final int PARTITIONS = 3;
    /** broker가 하나라 복제본도 하나다. */
    private static final int REPLICAS = 1;
    /** 지금 발행하는 회원 이벤트 레코드의 payload 버전. 깨지는 변경은 새 레코드 타입과 새 버전으로 더한다. */
    private static final String EVENT_VERSION = "1";

    @Bean
    NewTopic memberEventsTopic() {
        return TopicBuilder.name(MEMBER_EVENTS_TOPIC)
                .partitions(PARTITIONS)
                .replicas(REPLICAS)
                .build();
    }

    /** Modulith 자동 설정과 같은 대상·경로를 쓰고 헤더만 더한다. 이 빈이 있으면 자동 설정의 빈은 만들어지지 않는다. */
    @Bean
    EventExternalizationConfiguration eventExternalizationConfiguration(BeanFactory beanFactory) {
        return EventExternalizationConfiguration.defaults(AutoConfigurationPackages.get(beanFactory))
                .headers(event -> Map.of(
                        EVENT_TYPE_HEADER, utf8(event.getClass().getSimpleName()),
                        EVENT_VERSION_HEADER, utf8(EVENT_VERSION)))
                .build();
    }

    private static byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
