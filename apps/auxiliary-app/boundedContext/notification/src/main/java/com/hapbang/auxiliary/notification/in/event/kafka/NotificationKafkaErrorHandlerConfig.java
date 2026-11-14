package com.hapbang.auxiliary.notification.in.event.kafka;

import java.time.Duration;

import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;

/**
 * 알림 Kafka 소비자의 오류 처리기({@link DefaultErrorHandler} + {@link DeadLetterPublishingRecoverer}). 규칙은
 * {@code docs/architecture/infrastructure/kafka.md}의 "재시도와 DLT"이고 채팅 설정을 복사했다(ND-010, ND-028).
 * <ul>
 *     <li>처리에 실패하면 같은 자리에서 1초부터 두 배씩 최대 10초까지 늘리며 3회 블로킹 재시도한 뒤 그룹별 DLT로 보낸다. 재시도하는
 *     동안 그 partition의 뒤 메시지는 기다려 같은 key의 순서가 지켜진다.</li>
 *     <li>다시 처리해도 같은 결과인 실패는 재시도 없이 바로 DLT로 보낸다: 역직렬화 실패 등 spring-kafka 기본 분류와 검증 실패·신뢰 밖
 *     타입({@link RejectedEventException}).</li>
 *     <li>DLT에는 원본과 같은 partition 번호로 보낸다.</li>
 *     <li>DLT 발행 결과를 기다리고 실패·시간 초과면 예외를 던져 offset을 커밋하지 않는다(DLT 발행 실패는 처리 성공이 아니다).</li>
 * </ul>
 * 오류 처리기는 기본 후보에서 빼서 Boot의 기본 리스너 컨테이너 팩토리가 가져가지 않게 한다.
 */
@Configuration(proxyBeanMethods = false)
public class NotificationKafkaErrorHandlerConfig {

    private final NotificationKafkaMetrics metrics;

    public NotificationKafkaErrorHandlerConfig(NotificationKafkaMetrics metrics) {
        this.metrics = metrics;
    }

    public static final String MEMBER_EVENT_ERROR_HANDLER = "notificationMemberEventErrorHandler";

    @Bean(name = MEMBER_EVENT_ERROR_HANDLER, defaultCandidate = false)
    @ConditionalOnBooleanProperty(name = NotificationKafkaConstants.MEMBER_EVENTS_ENABLED, matchIfMissing = true)
    DefaultErrorHandler notificationMemberEventErrorHandler(
            @Qualifier(NotificationKafkaConfig.MEMBER_EVENT_DEAD_LETTER_TEMPLATE) KafkaTemplate<String, Object> deadLetterTemplate,
            @Qualifier(NotificationKafkaConfig.MEMBER_EVENT_SETTINGS) NotificationKafkaConsumerSettings settings) {
        return errorHandler(deadLetterTemplate, settings);
    }

    public static final String APPEARANCE_EVENT_ERROR_HANDLER = "notificationAppearanceEventErrorHandler";

    @Bean(name = APPEARANCE_EVENT_ERROR_HANDLER, defaultCandidate = false)
    @ConditionalOnBooleanProperty(name = NotificationKafkaConstants.APPEARANCE_EVENTS_ENABLED, matchIfMissing = true)
    DefaultErrorHandler notificationAppearanceEventErrorHandler(
            @Qualifier(NotificationKafkaConfig.APPEARANCE_EVENT_DEAD_LETTER_TEMPLATE)
            KafkaTemplate<String, Object> deadLetterTemplate,
            @Qualifier(NotificationKafkaConfig.APPEARANCE_EVENT_SETTINGS) NotificationKafkaConsumerSettings settings) {
        return errorHandler(deadLetterTemplate, settings);
    }

    public static final String BILLING_EVENT_ERROR_HANDLER = "notificationBillingEventErrorHandler";

    @Bean(name = BILLING_EVENT_ERROR_HANDLER, defaultCandidate = false)
    @ConditionalOnBooleanProperty(name = NotificationKafkaConstants.BILLING_EVENTS_ENABLED, matchIfMissing = true)
    DefaultErrorHandler notificationBillingEventErrorHandler(
            @Qualifier(NotificationKafkaConfig.BILLING_EVENT_DEAD_LETTER_TEMPLATE)
            KafkaTemplate<String, Object> deadLetterTemplate,
            @Qualifier(NotificationKafkaConfig.BILLING_EVENT_SETTINGS) NotificationKafkaConsumerSettings settings) {
        return errorHandler(deadLetterTemplate, settings);
    }

    public static final String LECTURE_EVENT_ERROR_HANDLER = "notificationLectureEventErrorHandler";

    @Bean(name = LECTURE_EVENT_ERROR_HANDLER, defaultCandidate = false)
    @ConditionalOnBooleanProperty(name = NotificationKafkaConstants.LECTURE_EVENTS_ENABLED, matchIfMissing = true)
    DefaultErrorHandler notificationLectureEventErrorHandler(
            @Qualifier(NotificationKafkaConfig.LECTURE_EVENT_DEAD_LETTER_TEMPLATE)
            KafkaTemplate<String, Object> deadLetterTemplate,
            @Qualifier(NotificationKafkaConfig.LECTURE_EVENT_SETTINGS) NotificationKafkaConsumerSettings settings) {
        return errorHandler(deadLetterTemplate, settings);
    }

    public static final String CUSTOMERSUPPORT_EVENT_ERROR_HANDLER = "notificationCustomerSupportEventErrorHandler";

    @Bean(name = CUSTOMERSUPPORT_EVENT_ERROR_HANDLER, defaultCandidate = false)
    @ConditionalOnBooleanProperty(name = NotificationKafkaConstants.CUSTOMERSUPPORT_EVENTS_ENABLED, matchIfMissing = true)
    DefaultErrorHandler notificationCustomerSupportEventErrorHandler(
            @Qualifier(NotificationKafkaConfig.CUSTOMERSUPPORT_EVENT_DEAD_LETTER_TEMPLATE)
            KafkaTemplate<String, Object> deadLetterTemplate,
            @Qualifier(NotificationKafkaConfig.CUSTOMERSUPPORT_EVENT_SETTINGS) NotificationKafkaConsumerSettings settings) {
        return errorHandler(deadLetterTemplate, settings);
    }

    public static final String CHAT_EVENT_ERROR_HANDLER = "notificationChatEventErrorHandler";

    @Bean(name = CHAT_EVENT_ERROR_HANDLER, defaultCandidate = false)
    @ConditionalOnBooleanProperty(name = NotificationKafkaConstants.CHAT_EVENTS_ENABLED, matchIfMissing = true)
    DefaultErrorHandler notificationChatEventErrorHandler(
            @Qualifier(NotificationKafkaConfig.CHAT_EVENT_DEAD_LETTER_TEMPLATE)
            KafkaTemplate<String, Object> deadLetterTemplate,
            @Qualifier(NotificationKafkaConfig.CHAT_EVENT_SETTINGS) NotificationKafkaConsumerSettings settings) {
        return errorHandler(deadLetterTemplate, settings);
    }

    /** 처리 실패 시도는 재시도·역직렬화 실패를 포함해 {@link NotificationKafkaMetrics#retryListener()}가 센다(ND-159) */
    DefaultErrorHandler errorHandler(KafkaOperations<?, ?> deadLetterTemplate,
            NotificationKafkaConsumerSettings settings) {
        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(settings.maxRetries());
        backOff.setInitialInterval(settings.retryInitialInterval().toMillis());
        backOff.setMultiplier(NotificationKafkaConstants.RETRY_MULTIPLIER);
        backOff.setMaxInterval(settings.retryMaxInterval().toMillis());
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(deadLetterRecoverer(deadLetterTemplate, settings),
                backOff);
        errorHandler.addNotRetryableExceptions(RejectedEventException.class);
        errorHandler.setRetryListeners(metrics.retryListener());
        return errorHandler;
    }

    /**
     * DLT 복구기. 원본과 같은 partition 번호로 보낸다. DLT의 partition이 그보다 적으면 복구기가 전송 전에 확인해 partition을 비우고
     * (-1) key로 고르게 한다(기본 동작). 결과 대기는 전송 완료 대기 몫({@link NotificationKafkaConsumerSettings#deadLetterDeliveryMillis()})만
     * 기다린다(기본 여유 5초를 더하지 않는다).
     */
    static DeadLetterPublishingRecoverer deadLetterRecoverer(KafkaOperations<?, ?> deadLetterTemplate,
            NotificationKafkaConsumerSettings settings) {
        String deadLetterTopic = settings.deadLetterTopic();
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(deadLetterTemplate,
                (record, exception) -> new TopicPartition(deadLetterTopic, record.partition()));
        recoverer.setFailIfSendResultIsError(true);
        recoverer.setTimeoutBuffer(0);
        recoverer.setWaitForSendResultTimeout(Duration.ofMillis(settings.deadLetterDeliveryMillis()));
        return recoverer;
    }
}
