package com.hapbang.lecture.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/**
 * 실행 설정(application.yaml)이 Redis 명령·연결 제한 시간을 짧게 두는지 확인한다. 기본값(Lettuce 60초)이면 Redis가 멈췄을 때 쿠폰 발급
 * 접수와 배치가 60초씩 묶인다. 모듈 테스트는 이 파일을 읽지 않아 설정 파일을 직접 읽는다. 짧은 제한 시간에서 실제로 빨리 503이 되는지는
 * 쿠폰 모듈의 사용 e2e가 Redis를 멈춰 본다.
 */
class RedisTimeoutConfigTest {

    @Test
    void 실행_설정은_Redis_명령_제한_시간_2초와_연결_제한_시간_1초를_둔다() throws IOException {
        Binder binder = binderOf("application.yaml");

        assertThat(binder.bind("spring.data.redis.timeout", Duration.class).get()).isEqualTo(Duration.ofSeconds(2));
        assertThat(binder.bind("spring.data.redis.connect-timeout", Duration.class).get())
                .isEqualTo(Duration.ofSeconds(1));
    }

    private static Binder binderOf(String file) throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader().load(file, new ClassPathResource(file));
        StandardEnvironment environment = new StandardEnvironment();
        sources.forEach(environment.getPropertySources()::addLast);
        return new Binder(ConfigurationPropertySources.get(environment));
    }
}
