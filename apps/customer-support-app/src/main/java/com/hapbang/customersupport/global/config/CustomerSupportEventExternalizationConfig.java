package com.hapbang.customersupport.global.config;

import java.util.Map;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.modulith.events.EventExternalizationConfiguration;

import com.hapbang.customersupport.app.CustomerSupportBatchProperties;
import com.hapbang.customersupport.app.CustomerSupportExternalEvents;
import com.hapbang.shared.customersupport.event.CustomerSupportEventTopic;
import com.hapbang.shared.customersupport.event.LectureObjectionResolutionRequestedEvent;
import com.hapbang.shared.customersupport.event.LectureObjectionWithdrawalRequestedEvent;

/**
 * 고객센터 이벤트를 Kafka로 내보낸다 (Modulith 이벤트 외부화, #279). 규칙은 docs/architecture/infrastructure/kafka.md(#339)를 따른다.
 * <ul>
 *     <li>대상은 {@link CustomerSupportExternalEvents}가 정한다. 토픽({@value CustomerSupportEventTopic#NAME})과
 *     key({@code inquiry:<문의 ID>})는 레코드의 {@code @Externalized}가 정하고, 이 설정은 헤더만 더한다.</li>
 *     <li>판단·철회 요청은 연동 상태 선점과 같은 트랜잭션에서 발행 기록({@code event_publication})에 남고 커밋 뒤에 Kafka로 간다.
 *     보내기에 실패하면 재발행 배치({@code CustomerSupportEventRepublishUseCase})와 서버 재시작 때 다시 보낸다.</li>
 *     <li>토픽은 고객센터 앱이 선언한다. 외부화를 끈 테스트에서는 선언하지 않는다.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CustomerSupportBatchProperties.class)
public class CustomerSupportEventExternalizationConfig {

    public static final String EVENT_TYPE = "eventType";
    public static final String EVENT_VERSION = "eventVersion";

    /** {@value #EVENT_TYPE} 헤더 값. 레코드 이름을 바꿔도 그대로 둔다(받는 쪽 계약) */
    public static final String RESOLUTION_REQUESTED = "LectureObjectionResolutionRequestedEvent";
    public static final String WITHDRAWAL_REQUESTED = "LectureObjectionWithdrawalRequestedEvent";

    /** 본문 형식 버전 (kafka.md 이벤트 버전) */
    static final String VERSION = "1";

    /** kafka.md 토픽 규칙: partition 3개, broker 1대라 복제 1 */
    static final int PARTITIONS = 3;

    @Bean
    EventExternalizationConfiguration customerSupportEventExternalization() {
        return EventExternalizationConfiguration.externalizing()
                .select(CustomerSupportExternalEvents::isExternalized)
                .headers(LectureObjectionResolutionRequestedEvent.class, event -> headers(RESOLUTION_REQUESTED))
                .headers(LectureObjectionWithdrawalRequestedEvent.class, event -> headers(WITHDRAWAL_REQUESTED))
                .build();
    }

    @Bean
    @ConditionalOnProperty(name = "spring.modulith.events.externalization.enabled", matchIfMissing = true)
    NewTopic customerSupportEventTopic() {
        return TopicBuilder.name(CustomerSupportEventTopic.NAME).partitions(PARTITIONS).replicas(1).build();
    }

    private static Map<String, Object> headers(String eventType) {
        return Map.of(EVENT_TYPE, eventType, EVENT_VERSION, VERSION);
    }
}
