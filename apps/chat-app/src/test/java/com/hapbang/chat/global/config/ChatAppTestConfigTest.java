package com.hapbang.chat.global.config;

import static org.assertj.core.api.Assertions.*;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;

import com.hapbang.chat.app.ChatDeliveryProperties;
import com.hapbang.chat.out.redis.RedisRealtimeChatFanout;
import com.hapbang.testsupport.IntegrationTest;
import com.zaxxer.hikari.HikariDataSource;

/**
 * 테스트 설정({@code hapbang-module-test.yaml})의 값이 코드 기본값보다 이긴다: 풀 5, 파이프라인 끔, 실시간 전달 끔(둘 다 운영 기본은 켜짐).
 * 테스트 설정에 없는 chat-app 기본값(Redis 명령 시간 제한 10s)은 그대로 들어온다 (설정 위치 정리).
 */
@IntegrationTest
class ChatAppTestConfigTest {

    @Autowired
    private Environment environment;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private ChatDeliveryProperties deliveryProperties;

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void 테스트_설정의_풀_크기와_파이프라인_끔이_코드_기본값보다_이긴다() throws Exception {
        assertThat(environment.getProperty("spring.datasource.hikari.maximum-pool-size", Integer.class)).isEqualTo(5);
        assertThat(dataSource.unwrap(HikariDataSource.class).getMaximumPoolSize()).isEqualTo(5);
        assertThat(deliveryProperties.pipeline().enabled()).isFalse();
        assertThat(environment.getProperty(ConditionalOnChatRealtime.REALTIME_ENABLED, Boolean.class)).isFalse();
        assertThat(applicationContext.getBeanNamesForType(RedisRealtimeChatFanout.class)).isEmpty();
        assertThat(environment.getProperty("spring.data.redis.timeout")).isEqualTo("10s");
    }
}
