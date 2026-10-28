package com.hapbang.common.redis;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;

/**
 * 모든 앱이 공통으로 쓰는 Redis 빈을 두는 자동 설정.
 * <p>
 * 모든 앱이 루트 Gradle로 이 모듈에 의존하고, 이 클래스는
 * {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}로 등록되어 앱에서 바로 쓴다.
 * 연결 팩토리·StringRedisTemplate은 Boot의 {@link DataRedisAutoConfiguration}이 만들고, 연결은 각 앱의 {@code spring.data.redis.*}로 준다.
 * 공통 빈은 Kafka·Redis 인프라를 구성한 뒤(#339, #340) 여기에 추가한다. 캐시 TTL, 키 규칙, Pub/Sub 구독은 사용하는 앱이 소유한다.
 */
@AutoConfiguration(after = DataRedisAutoConfiguration.class)
public class RedisConfig {
}
