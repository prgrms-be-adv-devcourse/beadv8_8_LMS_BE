# Redis 공통 모듈

모든 앱이 루트 Gradle(`build.gradle.kts`의 `-app` 공통 의존성)로 의존하며 자동 설정(`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`의 `RedisConfig`)으로 바로 쓴다. 연결은 각 앱의 `spring.data.redis.*`로 준다.

- 연결 팩토리(Lettuce)·`StringRedisTemplate`은 Boot의 `DataRedisAutoConfiguration`이 만든다. 문자열·카운터·Pub/Sub은 `StringRedisTemplate`을 쓴다.
- `RedisConfig`는 모든 앱 공통 Redis 빈을 두는 자리다. 객체 값용 `jsonRedisTemplate`(`RedisTemplate<String, Object>`)을 제공한다. 키·해시 키는 문자열, 값·해시 값은 JSON(Jackson 3 `GenericJacksonJsonRedisSerializer`)이다. 값에 타입 정보(`@class`)를 넣고 `com.hapbang`·`java` 패키지 타입만 역직렬화한다. 앱이 같은 이름의 빈을 등록하면 앱의 빈이 우선한다.
- JSON 값은 DTO·record나 변경 가능한 컬렉션(`HashMap` 등)으로 저장한다. 최상위 값이 `Map.of`·`List.of` 같은 불변 컬렉션이면 읽을 때 실패하고, 최상위 `Long`은 `Integer`로 읽힌다(직렬화기 동작).
- Boot 기본 `RedisTemplate<Object, Object>`의 Java 직렬화를 서비스 간 데이터 계약으로 사용하지 않는다.
- 캐시 TTL, 키 규칙, Pub/Sub 채널과 리스너는 각 앱의 책임이다(TTL은 값을 넣을 때 지정한다).
- 연결 주소·인증·TLS·클러스터 등은 실행 앱의 `spring.data.redis.*` 설정으로 주입한다. 이 모듈에는 `application.yaml`이나 실제 서버 정보가 없다. 로컬은 `docker/compose.yaml`의 redis를 앱의 Docker Compose 지원이 연결한다.
- 이 모듈은 Redis 서버를 배포하지 않는다.

테스트는 두지 않는다. 실제 통신 검증은 별도다.
