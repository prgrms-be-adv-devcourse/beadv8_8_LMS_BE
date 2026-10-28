package com.hapbang.common.redis;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import tools.jackson.databind.jsontype.BasicPolymorphicTypeValidator;

/**
 * 모든 앱이 공통으로 쓰는 Redis 빈을 두는 자동 설정.
 * <p>
 * 모든 앱이 루트 Gradle로 이 모듈에 의존하고, 이 클래스는
 * {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}로 등록되어 앱에서 바로 쓴다.
 * 연결 팩토리·StringRedisTemplate은 Boot의 {@link DataRedisAutoConfiguration}이 만들고, 연결은 각 앱의 {@code spring.data.redis.*}로 준다.
 * 문자열·카운터·Pub/Sub은 StringRedisTemplate을 쓴다. 캐시 TTL, 키 규칙, Pub/Sub 구독은 사용하는 앱이 소유한다.
 */
@AutoConfiguration(after = DataRedisAutoConfiguration.class)
public class RedisConfig {

    /**
     * 객체 값을 JSON으로 저장하는 템플릿. 키·해시 키는 문자열, 값·해시 값은 JSON(Jackson 3)이다.
     * 원래 타입으로 읽도록 값에 타입 정보를 넣되, 임의 클래스 역직렬화를 막기 위해 com.hapbang·java 패키지 타입만 허용한다.
     * 앱이 같은 이름의 빈을 등록하면 그 빈을 쓴다.
     */
    @Bean
    @ConditionalOnMissingBean(name = "jsonRedisTemplate")
    RedisTemplate<String, Object> jsonRedisTemplate(RedisConnectionFactory connectionFactory) {
        GenericJacksonJsonRedisSerializer json = GenericJacksonJsonRedisSerializer.builder()
                .enableDefaultTyping(BasicPolymorphicTypeValidator.builder()
                        .allowIfSubType("com.hapbang.")
                        .allowIfSubType("java.")
                        .build())
                .build();
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(StringRedisSerializer.UTF_8);
        template.setHashKeySerializer(StringRedisSerializer.UTF_8);
        template.setValueSerializer(json);
        template.setHashValueSerializer(json);
        return template;
    }
}
