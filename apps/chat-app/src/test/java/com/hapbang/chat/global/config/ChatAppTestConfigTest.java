package com.hapbang.chat.global.config;

import static org.assertj.core.api.Assertions.*;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;

import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import com.hapbang.chat.global.redis.ChatRealtimeRedisConfig;
import com.hapbang.chat.in.kafka.ChatMemberEventTopicVerifier;
import com.hapbang.chat.out.redis.RedisRealtimeChatFanout;
import com.hapbang.testsupport.IntegrationTest;
import com.zaxxer.hikari.HikariDataSource;

/**
 * 테스트 설정({@code hapbang-module-test.yaml})의 값이 코드 기본값보다 이긴다: 풀 5, Redis 명령 시간 제한 10s (설정 위치 정리).
 * Redis·Kafka가 없는 모듈 테스트도 운영 빈을 그대로 만들고, 브로커에 붙는 시작 동작만 테스트 설정이 막는다: Redis 주소가 없으면 Redis
 * 구독 컨테이너를 시작하지 않고, Kafka 리스너 자동 시작을 끄면 회원 이벤트 DLT 확인기를 등록하지 않는다.
 */
@IntegrationTest
class ChatAppTestConfigTest {

    @Autowired
    private Environment environment;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void 테스트_설정의_풀_크기가_코드_기본값보다_이긴다() throws Exception {
        assertThat(environment.getProperty("spring.datasource.hikari.maximum-pool-size", Integer.class)).isEqualTo(5);
        assertThat(dataSource.unwrap(HikariDataSource.class).getMaximumPoolSize()).isEqualTo(5);
        assertThat(environment.getProperty("spring.data.redis.timeout")).isEqualTo("10s");
    }

    @Test
    void 운영_빈은_그대로_만들고_브로커에_붙는_시작_동작만_막는다() {
        assertThat(applicationContext.getBeanNamesForType(RedisRealtimeChatFanout.class)).hasSize(1);
        RedisMessageListenerContainer container = applicationContext.getBean(ChatRealtimeRedisConfig.CONTAINER_BEAN_NAME,
                RedisMessageListenerContainer.class);
        assertThat(container.isAutoStartup()).isFalse();
        assertThat(container.isRunning()).isFalse();
        assertThat(environment.getProperty("spring.kafka.listener.auto-startup", Boolean.class)).isFalse();
        assertThat(applicationContext.getBeanNamesForType(ChatMemberEventTopicVerifier.class)).isEmpty();
    }
}
