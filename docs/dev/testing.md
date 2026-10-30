# 빌드와 테스트

## Gradle

- 앱: `:member-app`, `:billing-app`, `:appearance-app`, `:lecture-app`, `:chat-app`, `:customer-support-app`.
- 공통 라이브러리: `:shared`, `:test-support`, `:kafka`, `:redis`.
- `./gradlew check`는 활성 테스트를 실행하며 Testcontainers를 사용하는 테스트는 Docker가 필요하다.
- `./gradlew verifyModuleStructure`는 앱 간 실행 의존과 common → 앱 의존을 막고 필수 실행 파일을 검사한다.
- 앱별 검증: `./gradlew :member-app:check :member-app:bootJar`.
- 실제 실행 여부는 `apps/<앱>/build/test-results/test/TEST-*.xml`로 확인한다. up-to-date로 생략된 테스트를 새 실행으로 보고하지 않는다.

## 테스트 경계

기존 업무 모듈 테스트와 앱 내부 global 테스트는 각 앱의 `src/test`에 있다. `common/test-support`가 PostgreSQL·S3Mock·Kafka·Redis·테스트 로그인 지원을 제공한다. 앱 테스트가 다른 앱을 Gradle 의존으로 가져와 모든 빈을 한 컨텍스트에 올리면 독립 배포 경계를 검증할 수 없으므로 금지한다.

기존 단일 `apps`의 통합 테스트는 현재 `check`에 포함되지 않는다. 이전 시나리오는 Git 이력에서 확인하고 서비스 간 HTTP·이벤트·인증으로 전환하면서 재작성해야 한다. 현재 테스트 통과는 분산 거래 전체의 동작 보장이 아니다. 자세한 미완료 범위는 [앱 구조](../architecture/app-modules.md)를 따른다.

다른 서비스의 포트를 대체하는 Fake는 테스트 소스에만 둔다. 앱 실행을 위해 Fake를 운영 소스에 넣지 않는다.

## Kafka·Redis Testcontainers

Kafka·Redis 통신이 필요한 테스트에서만 공통 설정을 `@Import`한다. 둘 중 하나만 가져와도 된다. 기존 `@IntegrationTest`는 PostgreSQL만 기본으로 실행한다.

```java
import com.hapbang.testsupport.IntegrationTest;
import com.hapbang.testsupport.KafkaTestcontainersConfig;
import com.hapbang.testsupport.RedisTestcontainersConfig;
import org.springframework.context.annotation.Import;

@IntegrationTest
@Import({KafkaTestcontainersConfig.class, RedisTestcontainersConfig.class})
class DomainIntegrationTest {
}
```

- Kafka는 `apache/kafka:4.2.1`의 단일 노드 KRaft, Redis는 로컬 Compose와 같은 `redis:8`을 사용한다. Kafka 이미지 버전은 현재 Spring Boot BOM의 Kafka 버전에 맞췄다.
- Docker가 실행 중이어야 한다. 최초 실행에는 이미지 다운로드가 필요하다. Docker나 컨테이너 기동 실패는 테스트 실패이며 Mock 대체·자동 건너뛰기는 하지 않는다.
- 준비 완료 후 동적 호스트·포트를 `spring.kafka.bootstrap-servers`, `spring.data.redis.host`, `spring.data.redis.port`에 주입한다. 외부 서버나 고정 호스트 포트는 필요 없다.
- member-app은 회원 이벤트를 Kafka로 외부화하므로 통합 테스트가 모두 `@MemberIntegrationTest`(`@IntegrationTest` + `KafkaTestcontainersConfig`)를 쓴다. 다른 테스트의 메시지가 같은 토픽에 섞이므로 Kafka 메시지를 확인하는 테스트는 자신이 만든 회원 ID(key)로 거른다. 공통 Kafka 설정 없이 `@IntegrationTest`만 쓰면 `localhost:9092`로 보내려 한다.
- 토픽·소비자 그룹·직렬화와 Redis 키·채널은 사용하는 테스트가 지정한다. 공통 설정은 업무 정책을 정하지 않는다.
- 설정을 가져온 테스트 JVM에서 종류별 컨테이너를 한 번 시작해 공유한다. Spring 컨텍스트가 닫혀도 컨테이너는 유지하며, 테스트 JVM 종료 후 Testcontainers가 정리한다. JVM이 다른 모듈끼리는 공유하지 않는다. 실행 간 재사용이나 영속 볼륨은 설정하지 않는다.
- 테스트마다 UUID 등으로 고유한 토픽·소비자 그룹·Redis 키·채널·Stream을 사용한다. 자신이 만든 자원만 정리하고 소비자·구독·연결을 종료한다. `FLUSHALL`·`FLUSHDB` 등 전역 초기화를 하지 않는다. 기존 DB 정리 리스너는 Kafka·Redis 자원을 정리하지 않는다.
- 비동기 수신에는 제한 시간을 둔다. `./gradlew :test-support:test`로 공통 설정의 Kafka 발행·수신, Redis 저장·조회·Streams 소비·ACK, 두 설정 동시 사용을 검증한다.

위 Streams 소비·ACK는 공통 컨테이너의 기능 스모크 테스트이며 채팅 전달 전체의 검증을 대체하지 않는다. 채팅은 2026-10-29 사용자 변경 결정으로 PostgreSQL outbox → Redis Streams → consumer group → Pub/Sub을 도입한다(#340). 앱 통합 테스트는 XADD·PUBLISH·DB 발행 체크포인트·XACK 사이의 종료/중복, DB lease·PEL 회수, Stream/그룹 유실 재구성, 미처리 보존 정리와 Pub/Sub 수신 유실·FE 이력 복구를 검증한다. 상세 기준은 [채팅 테스트 기준](../../apps/chat-app/docs/workflow/test-criteria.md)을 따른다. AOF·noeviction·OOM 등 운영 설정 시험은 별도 컨테이너로 격리해 공유 컨테이너의 전역 설정을 바꾸거나 다른 테스트의 키를 지우지 않는다.

이 설정은 자동 테스트 전용이다. 로컬 앱 실행·운영 배포 구성, 업무 이벤트의 Kafka 전환과 Redis 채팅 전달 로직은 별도다.

## 설정과 내부 API

- 내부 API 키가 필수인 컨텍스트는 `hapbang.internal-api.key`를 테스트 값으로 설정한다. `@IntegrationTest`는 테스트 키를 제공한다. 직접 `@SpringBootTest`를 쓰는 테스트도 필요한 값을 명시해야 한다.
- 앱의 YAML이 테스트에 영향을 주지 않도록 테스트 설정의 우선순위와 활성 프로필을 확인한다. 실제 기동 검증과 서비스 일부만 로드하는 테스트를 구분한다.
- 모듈 테스트(`spring.config.name=hapbang-module-test`)는 `application.yaml`을 읽지 않고 `@ConfigurationProperties`의 코드 기본값을 쓴다. 운영 기본값과 다르게 둬야 하는 값은 앱의 `src/test/resources/hapbang-module-test.yaml`에 둔다(예: chat-app은 회원·출연 이벤트 Kafka 소비 `hapbang.chat.member-events.enabled`·`hapbang.chat.appearance-events.enabled`를 끄고, Kafka 시험 클래스만 켠다).
- Jackson으로 읽은 `OffsetDateTime`의 offset이 UTC로 정규화될 수 있으므로 같은 시각인지 확인할 때는 instant를 비교한다.
- S3를 호출하는 테스트는 [S3 문서](../deploy/s3.md)의 테스트 설정을 사용한다.

## 이벤트 리스너

- `@ApplicationModuleListener`를 테스트할 때는 `TransactionTemplate` 안에서 이벤트를 발행하고 `await()`로 커밋 뒤 비동기 처리를 확인한다.
- "처리되지 않음"은 `await().during(Duration.ofMillis(500))`처럼 관찰 구간을 둔다.
- 이 검증은 같은 JVM 내부의 Spring Event 처리다. 다른 앱으로 전달되는 Kafka 연동 검증이 아니다.
- 실패한 이벤트가 남으면 종료 시 `publications left unfinished` 로그가 찍힐 수 있다.

## 배치

- 배치를 켠 `@SpringBootTest`에는 `@DirtiesContext`를 붙여 캐시된 컨텍스트의 배치가 다음 테스트 DB를 바꾸지 않도록 한다.
- 실패 격리는 `@MockitoSpyBean`으로 특정 항목 조회가 실패하도록 해 확인한다.
- 기존 통합 `SchedulingEnabledTest` 시나리오는 Git 이력을 참고해 앱별 배치 등록·cron 검증으로 옮긴다.

## CI

PR의 `verify`(`.github/workflows/ci.yml`)는 `./gradlew verifyModuleStructure`로 모듈 구조를 검사하고, `./gradlew check bootJar --parallel`로 여섯 앱과 common 라이브러리를 테스트해 앱별 jar를 만든 뒤, 앱마다 자기 `Dockerfile`로 이미지가 빌드되는지 확인한다(push하지 않음). `verifyModuleStructure`는 `--parallel`과 함께 실행하면 Gradle이 막으므로 따로 실행한다.
