package com.hapbang.chat.support;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import com.hapbang.chat.global.redis.ChatRealtimeRedisConfig;

/**
 * Redis·Kafka가 없는 모듈 테스트 컨텍스트가 브로커에 붙지 않고 뜨게 한다. 테스트 컨텍스트의 컴포넌트 스캔({@code com.hapbang})이 이
 * 설정을 읽는다. 운영 빈은 그대로 만들고, 시작할 때 브로커에 붙는 두 가지만 막는다.
 * <ul>
 *     <li>Redis 구독 컨테이너({@value ChatRealtimeRedisConfig#CONTAINER_BEAN_NAME}): 시작하면 서버 채널을 바로 구독한다. 테스트가
 *     Redis 주소({@code spring.data.redis.host})를 두지 않았으면 자동 시작하지 않는다. Redis 컨테이너를 쓰는 테스트
 *     ({@code RedisTestcontainersConfig}·{@code FixedPortRedis})는 주소를 두므로 운영처럼 구독한다. 그 밖의 Redis 빈은 쓸 때만
 *     연결하므로 그대로 둔다.</li>
 *     <li>회원 이벤트 DLT 확인기({@code ChatMemberEventTopicVerifier}): 앱이 뜬 뒤 브로커에 토픽을 묻는다. Kafka 리스너를 자동 시작하지
 *     않는 테스트(Spring Boot 표준 키 {@code spring.kafka.listener.auto-startup=false}, 모듈 테스트 설정의 기본)에서는 등록하지 않는다.
 *     Kafka 시험 클래스는 그 키를 켜므로 운영처럼 확인한다.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
public class ChatBrokerlessTestConfig {

    static final String TOPIC_VERIFIER_BEAN_NAME = "chatMemberEventTopicVerifier";

    @Bean
    static BeanPostProcessor chatRedisSubscriptionAutoStartup(Environment environment) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
                if (ChatRealtimeRedisConfig.CONTAINER_BEAN_NAME.equals(beanName)
                        && bean instanceof RedisMessageListenerContainer container
                        && !environment.containsProperty("spring.data.redis.host")) {
                    container.setAutoStartup(false);
                }
                return bean;
            }
        };
    }

    @Bean
    static BeanFactoryPostProcessor chatKafkaTopicVerifierRemoval(Environment environment) {
        return beanFactory -> {
            boolean listenersStart = environment.getProperty("spring.kafka.listener.auto-startup", Boolean.class, true);
            if (!listenersStart && beanFactory instanceof BeanDefinitionRegistry registry
                    && registry.containsBeanDefinition(TOPIC_VERIFIER_BEAN_NAME)) {
                registry.removeBeanDefinition(TOPIC_VERIFIER_BEAN_NAME);
            }
        };
    }
}
