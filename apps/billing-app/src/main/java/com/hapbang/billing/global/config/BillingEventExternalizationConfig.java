package com.hapbang.billing.global.config;

import java.util.Map;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.modulith.events.EventExternalizationConfiguration;

import com.hapbang.billing.app.BillingExternalEvents;
import com.hapbang.shared.billing.event.BillingEventTopic;
import com.hapbang.shared.billing.event.ChargeCompletedEvent;
import com.hapbang.shared.billing.event.ChargeFailedEvent;
import com.hapbang.shared.billing.event.ExchangeApprovedEvent;
import com.hapbang.shared.billing.event.ExchangeCompletedEvent;
import com.hapbang.shared.billing.event.ExchangeFinalFailedEvent;
import com.hapbang.shared.billing.event.ExchangePayoutFailedEvent;
import com.hapbang.shared.billing.event.ExchangeRejectedEvent;
import com.hapbang.shared.billing.event.PayoutCompletedEvent;
import com.hapbang.shared.billing.event.RefundCompletedEvent;
import com.hapbang.shared.billing.event.RefundFailedEvent;
import com.hapbang.shared.billing.event.RefundManualReviewRequiredEvent;

/**
 * 지갑 이벤트를 Kafka로 내보낸다 (Modulith 이벤트 외부화, #497). 규칙은 docs/architecture/infrastructure/kafka.md(#339)를 따른다.
 * <ul>
 *     <li>대상은 {@link BillingExternalEvents}가 정한다(shared 지갑 이벤트 중 {@code @Externalized}가 붙은 것).
 *     토픽({@value BillingEventTopic#NAME})과 key는 레코드의 {@code @Externalized}가 정하고, 이 설정은 헤더만 더한다.</li>
 *     <li>업무 트랜잭션 안에서 발행한 이벤트가 Modulith 발행 기록({@code event_publication})에 남고 커밋 뒤에 Kafka로 간다.
 *     롤백되면 보내지 않고, 보내기에 실패하면 기록이 남아 재발행 배치와 서버 재시작 때 다시 보낸다.</li>
 *     <li>본문은 shared 이벤트 레코드를 그대로 JSON으로 싣는다. 헤더는 {@value #EVENT_TYPE}·{@value #EVENT_VERSION} 두 개다.
 *     이벤트 ID는 두지 않고, 받는 쪽은 이벤트마다 정한 대상 ID로 중복을 거른다.</li>
 *     <li>토픽은 지갑 앱이 선언한다(브로커 자동 생성은 꺼져 있음). 외부화를 끈 테스트에서는 선언하지 않는다.</li>
 * </ul>
 * 내보낼 이벤트를 더하면 shared 레코드에 {@code @Externalized}를 붙이고, 여기에 {@value #EVENT_TYPE} 헤더를 더한다.
 */
@Configuration(proxyBeanMethods = false)
public class BillingEventExternalizationConfig {

    public static final String EVENT_TYPE = "eventType";
    public static final String EVENT_VERSION = "eventVersion";

    /** 본문 형식 버전. 선택 필드를 추가만 하면 그대로 두고, 깨지는 변경은 새 레코드 타입으로 한다 (kafka.md 이벤트 버전) */
    public static final String VERSION = "1";

    /** kafka.md 토픽 규칙: partition 3개, broker 1대라 복제 1 */
    public static final int PARTITIONS = 3;

    @Bean
    EventExternalizationConfiguration billingEventExternalization() {
        return EventExternalizationConfiguration.externalizing()
                .select(BillingExternalEvents::isExternalized)
                // eventType 값은 레코드 이름이다. 레코드 이름을 바꿔도 이 값은 그대로 둔다(받는 쪽 계약)
                .headers(ChargeCompletedEvent.class, event -> headers("ChargeCompletedEvent"))
                .headers(PayoutCompletedEvent.class, event -> headers("PayoutCompletedEvent"))
                .headers(RefundCompletedEvent.class, event -> headers("RefundCompletedEvent"))
                .headers(ExchangeCompletedEvent.class, event -> headers("ExchangeCompletedEvent"))
                .headers(ExchangeRejectedEvent.class, event -> headers("ExchangeRejectedEvent"))
                .headers(ExchangePayoutFailedEvent.class, event -> headers("ExchangePayoutFailedEvent"))
                .headers(ExchangeFinalFailedEvent.class, event -> headers("ExchangeFinalFailedEvent"))
                // 알림센터용 (#873, BD-162)
                .headers(ChargeFailedEvent.class, event -> headers("ChargeFailedEvent"))
                .headers(RefundFailedEvent.class, event -> headers("RefundFailedEvent"))
                .headers(RefundManualReviewRequiredEvent.class, event -> headers("RefundManualReviewRequiredEvent"))
                .headers(ExchangeApprovedEvent.class, event -> headers("ExchangeApprovedEvent"))
                .build();
    }

    @Bean
    @ConditionalOnProperty(name = "spring.modulith.events.externalization.enabled", matchIfMissing = true)
    NewTopic billingEventTopic() {
        return TopicBuilder.name(BillingEventTopic.NAME).partitions(PARTITIONS).replicas(1).build();
    }

    private static Map<String, Object> headers(String eventType) {
        return Map.of(EVENT_TYPE, eventType, EVENT_VERSION, VERSION);
    }
}
