dependencies {
    // RedisConnectionFactory·StringRedisTemplate을 사용하는 앱에 API를 노출한다.
    // 버전은 루트 Spring Boot BOM을 따른다.
    api("org.springframework.boot:spring-boot-starter-data-redis")
    // jsonRedisTemplate의 값 직렬화(Jackson 3). Spring Data Redis에서는 선택 의존성이다
    implementation("tools.jackson.core:jackson-databind")
}
