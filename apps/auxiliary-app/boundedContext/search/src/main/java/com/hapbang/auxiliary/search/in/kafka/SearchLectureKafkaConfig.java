package com.hapbang.auxiliary.search.in.kafka;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.kafka.autoconfigure.DefaultKafkaConsumerFactoryCustomizer;
import org.springframework.boot.kafka.autoconfigure.DefaultKafkaProducerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.RecordInterceptor;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JacksonJsonDeserializer;

/**
 * 검색 모듈의 강의 이벤트 소비 설정(#882, 토픽 {@code lecture.events.v1}, 그룹 {@code auxiliary-app.search-lecture}). 공통 규칙(재시도 3회·
 * DLT·offset)은 {@link SearchKafkaFactories}로 회원 소비와 같다.
 * <ul>
 *     <li>값은 {@code __TypeId__} 헤더의 타입으로 읽고, 신뢰 패키지는 정확히 {@code com.hapbang.shared.lecture.event}다.</li>
 *     <li><b>이 앱에 클래스가 없는 강의 이벤트는 읽지 않고 건너뛴다</b>({@link TrustedEventDeserializer} → {@link MissingTypeEvent}, Q3). 강의
 *     앱이 같은 토픽에 새 이벤트를 더해 먼저 배포하면 클래스를 찾지 못해 DLT로 가고 경보가 울리기 때문이다(#872).</li>
 *     <li>그 밖에 읽을 수 없는 메시지(신뢰 밖 타입·타입 헤더 없음·깨진 본문)는 다시 시도하지 않고 DLT로 보낸다.</li>
 *     <li>DLT는 여기서 만든다(보존 7일). 원본 토픽은 강의 앱이 만든다.</li>
 * </ul>
 * {@value #ENABLED}가 꺼지면 뜨지 않는다(기본 켜짐. Kafka가 없는 테스트가 끈다).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBooleanProperty(name = SearchLectureKafkaConfig.ENABLED, matchIfMissing = true)
public class SearchLectureKafkaConfig {

    public static final String ENABLED = "hapbang.search.lecture-events.enabled";
    public static final String LECTURE_EVENT_SETTINGS = "searchLectureEventSettings";
    public static final String LECTURE_EVENT_CONTAINER_FACTORY = "searchLectureEventListenerContainerFactory";

    static final String DEAD_LETTER_PRODUCER_FACTORY = "searchLectureDeadLetterProducerFactory";

    private final String bootstrapServers;
    private final ObjectProvider<DefaultKafkaConsumerFactoryCustomizer> consumerFactoryCustomizers;
    private final ObjectProvider<DefaultKafkaProducerFactoryCustomizer> producerFactoryCustomizers;

    public SearchLectureKafkaConfig(@Value("${spring.kafka.bootstrap-servers:localhost:9092}") String bootstrapServers,
            ObjectProvider<DefaultKafkaConsumerFactoryCustomizer> consumerFactoryCustomizers,
            ObjectProvider<DefaultKafkaProducerFactoryCustomizer> producerFactoryCustomizers) {
        this.bootstrapServers = bootstrapServers;
        this.consumerFactoryCustomizers = consumerFactoryCustomizers;
        this.producerFactoryCustomizers = producerFactoryCustomizers;
    }

    @Bean(name = LECTURE_EVENT_SETTINGS)
    SearchKafkaSettings searchLectureEventSettings() {
        return SearchKafkaSettings.lectureDefaults();
    }

    @Bean(defaultCandidate = false)
    ConsumerFactory<String, Object> searchLectureEventConsumerFactory(
            @Qualifier(LECTURE_EVENT_SETTINGS) SearchKafkaSettings settings) {
        return SearchKafkaFactories.consumerFactory(bootstrapServers, settings,
                eventDeserializer(SearchKafkaSettings.LECTURE_TRUSTED_PACKAGE), consumerFactoryCustomizers);
    }

    @Bean(defaultCandidate = false)
    KafkaTemplate<String, Object> searchLectureEventDeadLetterTemplate() {
        return SearchKafkaFactories.deadLetterTemplate(bootstrapServers, DEAD_LETTER_PRODUCER_FACTORY,
                producerFactoryCustomizers);
    }

    @Bean(defaultCandidate = false)
    DefaultErrorHandler searchLectureEventErrorHandler(
            @Qualifier(LECTURE_EVENT_SETTINGS) SearchKafkaSettings settings,
            @Qualifier("searchLectureEventDeadLetterTemplate") KafkaTemplate<String, Object> template) {
        return SearchKafkaFactories.errorHandler(settings, template);
    }

    @Bean(name = LECTURE_EVENT_CONTAINER_FACTORY, defaultCandidate = false)
    ConcurrentKafkaListenerContainerFactory<String, Object> searchLectureEventListenerContainerFactory(
            @Qualifier("searchLectureEventConsumerFactory") ConsumerFactory<String, Object> consumerFactory,
            @Qualifier("searchLectureEventErrorHandler") DefaultErrorHandler errorHandler,
            ObjectProvider<RecordInterceptor<String, Object>> recordInterceptor) {
        return SearchKafkaFactories.containerFactory(consumerFactory, errorHandler, recordInterceptor);
    }

    @Bean
    NewTopic searchLectureEventDeadLetterTopic(@Qualifier(LECTURE_EVENT_SETTINGS) SearchKafkaSettings settings) {
        return SearchKafkaFactories.deadLetterTopic(settings);
    }

    /**
     * 강의 이벤트 value 역직렬화기. {@code __TypeId__} 헤더를 지우지 않아 본문을 읽지 못해 DLT로 보낸 원본에 타입이 남는다(고쳐 다시 넣을 수 있다).
     */
    static ErrorHandlingDeserializer<Object> eventDeserializer(String trustedPackage) {
        JacksonJsonDeserializer<Object> json = new JacksonJsonDeserializer<>();
        json.addTrustedPackages(trustedPackage);
        json.setRemoveTypeHeaders(false);
        return new ErrorHandlingDeserializer<>(new TrustedEventDeserializer(json, trustedPackage));
    }
}
