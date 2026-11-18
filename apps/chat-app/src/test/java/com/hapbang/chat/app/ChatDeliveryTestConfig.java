package com.hapbang.chat.app;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

import com.hapbang.testsupport.RedisTestcontainersConfig;

/**
 * 전달 파이프라인 테스트 설정. 실제 Redis(redis:8 컨테이너)와 메모리 실시간 전파를 쓴다. 운영 Redis 전파 빈도 등록되지만 주입
 * 대상은 메모리 전파다.
 */
@TestConfiguration(proxyBeanMethods = false)
@Import(RedisTestcontainersConfig.class)
public class ChatDeliveryTestConfig {

    @Bean
    @Primary
    InMemoryRealtimeChatFanout inMemoryRealtimeChatFanout() {
        return new InMemoryRealtimeChatFanout();
    }
}
