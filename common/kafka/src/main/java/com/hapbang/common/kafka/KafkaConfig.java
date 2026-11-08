package com.hapbang.common.kafka;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.listener.RecordInterceptor;

/**
 * 모든 앱이 공통으로 쓰는 Kafka 빈을 두는 자동 설정.
 * <p>
 * 모든 앱이 루트 Gradle로 이 모듈에 의존하고, 이 클래스는
 * {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}로 등록되어 앱에서 바로 쓴다.
 * 연결 팩토리·KafkaTemplate은 Boot의 {@link KafkaAutoConfiguration}이 만들고, 연결·직렬화는 각 앱의 {@code spring.kafka.*}로 준다.
 * 토픽, 이벤트 DTO, 소비자 그룹 및 실패 처리 정책은 사용하는 앱이 소유한다.
 */
@AutoConfiguration(after = KafkaAutoConfiguration.class)
public class KafkaConfig {

    /**
     * 받은 레코드의 {@code eventType}·{@code eventKey}를 로그 MDC에 넣는다(#582). Boot는 {@link RecordInterceptor} 빈이 하나일
     * 때만 자기 리스너 컨테이너 팩토리에 붙이므로, 앱이 자기 인터셉터를 두면 이 빈은 만들지 않는다. 리스너 컨테이너 팩토리를 직접
     * 만드는 앱(chat)은 그 팩토리에 직접 붙인다.
     */
    @Bean
    @ConditionalOnMissingBean(RecordInterceptor.class)
    RecordInterceptor<Object, Object> eventLogContextRecordInterceptor() {
        return new EventLogContextRecordInterceptor<>();
    }
}
