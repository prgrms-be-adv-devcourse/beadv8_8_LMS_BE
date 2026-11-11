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

기존 단일 `apps`의 통합 테스트는 현재 `check`에 포함되지 않는다. 이전 시나리오는 Git 이력에서 확인하고 서비스 간 HTTP·이벤트·인증으로 전환하면서 재작성해야 한다. 아래 강의·billing HTTP 통합 테스트가 검증하는 경로 외의 분산 거래 전체는 앱별 테스트 통과만으로 보장하지 않는다. 자세한 미완료 범위는 [앱 구조](../architecture/app-modules.md)를 따른다.

다른 서비스의 포트를 대체하는 Fake는 테스트 소스에만 둔다. 앱 실행을 위해 Fake를 운영 소스에 넣지 않는다.

## 강의·billing HTTP 통합 테스트

강의 앱의 Spring 통합 테스트는 `RealBillingTestFixture`로 실제 billing 서버에 연결한다. `:lecture-app:test`가 먼저 `:billing-app:e2eServerJar`를 만들고, 공통 하네스가 테스트 JVM당 billing 프로세스와 전용 PostgreSQL 컨테이너(database `billing_e2e`)를 한 번 시작한다. 강의 DB와 billing DB는 분리하며, 강의 테스트 클래스패스에 billing 앱·엔티티·Repository를 추가하지 않는다. HTTP Client 자체의 응답 해석 단위 테스트와는 검증 범위가 다르다.

- 실행은 `./gradlew :lecture-app:test`로 한다. 다른 앱의 테스트를 직접 실행할 때 실제 billing이 필요한 경우에는 해당 Gradle 테스트 태스크에도 E2E Jar 의존과 하네스 활성화가 필요하다.
- 서버는 `apps/billing-app/src/e2eServer`만 fixture로 더한 실제 `BillingApplication`이다. 일반 billing 테스트 클래스는 포함하지 않고, 운영 `app.jar`에는 fixture를 포함하지 않는다. 독립 `billing-e2e.yml`·`e2e` 프로필로 실행해 로컬 `.env`·운영 앱 설정을 읽지 않는다. Flyway 마이그레이션과 JPA 검증은 실제 DB에 적용한다.
- 하네스가 임시 DB 자격·내부 API 키를 전달하고 서버는 loopback 임의 포트에 바인딩한다. 서버가 준비되면 `hapbang.e2e.ready-file`에 `{port, baseUrl, pid}` JSON을 원자적으로 쓴다. 기동 로그는 임시 디렉터리의 `billing.log`에 남는다. Jar 누락·컨테이너 실패·서버 종료·준비 시간 초과는 테스트 실패다. Fake 대체나 자동 건너뛰기를 하지 않는다.
- 지갑 조회·관리자 프로모션 충전/조회·예산 예약/해제·HP 보류/반환/지급/보류 취소/조회는 실제 운영 Controller와 내부 HTTP Client를 지난다. billing의 Kafka 소비·외부화·배치는 이 하네스에서 끈다. 따라서 이 테스트의 성공은 billing 이벤트의 Kafka 전달이나 Toss 결제 승인을 검증했다는 뜻이 아니다.

초기 상태는 loopback과 `X-Billing-E2e-Key`로 보호한 `/__test/billing/*` HTTP fixture만 준비한다. 이는 billing E2E Jar에만 존재하며 billing 소유 스키마 안에서 동작한다. 다른 앱의 DB를 직접 읽거나 쓰는 경로로 확장하지 않는다.

| fixture | 용도와 경계 |
| --- | --- |
| `POST /reset` | billing 스키마의 금융 테스트 데이터를 비우고 시스템 지갑을 다시 만든다. Flyway 이력은 유지한다. 공통 리스너가 각 테스트의 트랜잭션 시작 전에 호출한다. |
| `POST /wallets` | `userId`, `chargeHp`, `revenueHp`를 받아 양수만 더한다. 충전 HP는 실제 완료 `PointCharge`와 원장, 수익 HP는 실제 원장 엔진으로 준비한다. 0은 해당 종류의 원장·충전 건을 만들지 않는다. 잔액 덮어쓰기나 기존 원장 삭제는 하지 않는다. |
| `POST /wallets/{userId}/close` | 실제 지갑 폐쇄 상태를 만든다. `WALLET_CLOSED` 같은 업무 거절을 실제 API에서 받기 위한 전제다. |
| `GET /snapshot?userId=...` | 실제 지갑·원장·충전 원금·정합성 대사 결과를 반환한다. 없는 지갑을 만들지 않는다. |

충전·수익 seed는 외부 결제 승인과 회원 생성 경로를 생략하고, 거래 테스트에 필요한 완료 충전·원장 초기 상태만 billing 안에 준비한다. 이후 금융 성공·거절·멱등·원금 FIFO·복구 결과는 실제 billing 엔진이 결정한다. 예산은 `promotionWallet(amountHp)`를 통해 실제 관리자 충전 API로 준비한다. 부족 상태는 작은 초기 잔액이나 전체 reset으로 만들고, 응답을 지정해 성공·실패를 만들어 내지 않는다.

`RealBillingTestFixture.calls()`는 실제 `BillingApiClient`를 관찰해 명령과 호출 시 강의 트랜잭션 유무를 남긴다. `duringCall`·`duringNextCall`은 동시 실행 순서만 제어한다. 통신 프록시는 정상 업무 응답을 그대로 전달하고, `disconnect`·응답 유실은 연결 또는 전달 중단으로 재현한다. 임의 HTTP 상태·업무 응답을 만들거나 금융 결과를 바꾸지 않는다. 회로 테스트는 실제 Client의 회로를 열고 닫는다.

테스트 사이에는 fixture가 billing 데이터·관측 목록·통신 장애·회로 상태를 초기화한다. JVM 종료 시 하네스가 billing 프로세스를 종료하고 전용 PostgreSQL 컨테이너를 정리한다. 수동으로 공유 컨테이너를 중단하거나 다른 앱의 스키마를 비우지 않는다. 구현은 [`common/test-support/.../billing`](../../common/test-support/src/main/java/com/hapbang/testsupport/billing)과 [`billing-app/src/e2eServer`](../../apps/billing-app/src/e2eServer)를 따른다.

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
- 로컬 Docker가 기본 주소의 임의 포트 게시를 하지 못해 포트 매핑이 비어 있을 때만 `HAPBANG_TEST_BIND_ADDRESS=127.0.0.1 ./gradlew ...`로 명시한다. 공통 `TestContainerBindAddress`가 Testcontainers의 `CreateContainerCmdModifier` SPI로 기존 포트 게시 주소만 바꾸고 포트 번호는 동적으로 유지한다. 기본 실행과 CI에는 이 환경 변수를 설정하지 않아 기존 Testcontainers 동작을 유지한다.
- 준비 완료 후 동적 호스트·포트를 `spring.kafka.bootstrap-servers`, `spring.data.redis.host`, `spring.data.redis.port`에 주입한다. 외부 서버나 고정 호스트 포트는 필요 없다.
- member-app은 회원 이벤트를 Kafka로 외부화하므로 통합 테스트가 모두 `@MemberIntegrationTest`(`@IntegrationTest` + `KafkaTestcontainersConfig`)를 쓴다. 다른 테스트의 메시지가 같은 토픽에 섞이므로 Kafka 메시지를 확인하는 테스트는 자신이 만든 회원 ID(key)로 거른다. 공통 Kafka 설정 없이 `@IntegrationTest`만 쓰면 `localhost:9092`로 보내려 한다.
- 토픽·소비자 그룹·직렬화와 Redis 키·채널은 사용하는 테스트가 지정한다. 공통 설정은 업무 정책을 정하지 않는다.
- 설정을 가져온 테스트 JVM에서 종류별 컨테이너를 한 번 시작해 공유한다. Spring 컨텍스트가 닫혀도 컨테이너는 유지하며, 테스트 JVM 종료 후 Testcontainers가 정리한다. JVM이 다른 모듈끼리는 공유하지 않는다. 실행 간 재사용이나 영속 볼륨은 설정하지 않는다.
- 테스트마다 UUID 등으로 고유한 토픽·소비자 그룹·Redis 키·채널·Stream을 사용한다. 자신이 만든 자원만 정리하고 소비자·구독·연결을 종료한다. `FLUSHALL`·`FLUSHDB` 등 전역 초기화를 하지 않는다. 기존 DB 정리 리스너는 Kafka·Redis 자원을 정리하지 않는다.
- broker 장애는 `KafkaTestcontainersConfig.withBrokerPaused(Runnable)`로 만든다(#339). 공유 컨테이너를 `docker pause`로 멈춘 채 작업을 실행하고 `finally`에서 되살린다. stop/start는 매핑 포트가 바뀌어 client가 다시 붙지 못하므로 쓰지 않는다. 멈춘 동안의 전송이 빨리 실패하게 하려면 테스트에서 producer `max.block.ms`·`delivery.timeout.ms`를 몇 초로 줄인다(기본 60초·120초). consumer 중단은 `KafkaListenerEndpointRegistry`에서 그룹 ID로 container를 찾아 `stop()`/`start()`한다. 예: `LectureMemberEventListenerTest`.
- Redis 장애는 `RedisTestcontainersConfig.withRedisPaused(Runnable)`로 만든다(#770). Kafka와 같이 공유 컨테이너를 `docker pause`로 멈추고 `finally`에서 되살린다. 멈춘 Redis는 연결을 끊지 않고 응답만 하지 않으므로, 명령이 빨리 실패하게 하려면 `spring.data.redis.timeout`을 줄인다(Lettuce 기본 60초, member-app은 운영 설정이 1초). 되살린 뒤 다시 붙을 때까지 `await()`로 기다린다. 예: `RedisTestcontainersConfigTest`.
- 비동기 수신에는 제한 시간을 둔다. `./gradlew :test-support:test`로 공통 설정의 Kafka 발행·수신, Redis 저장·조회·Streams 소비·ACK, 두 설정 동시 사용을 검증한다.

위 Streams 소비·ACK는 공통 컨테이너의 기능 스모크 테스트이며 채팅 전달 전체의 검증을 대체하지 않는다. 채팅은 2026-10-29 사용자 변경 결정으로 PostgreSQL outbox → Redis Streams → consumer group → Pub/Sub을 도입한다(#340). 앱 통합 테스트는 XADD·PUBLISH·DB 발행 체크포인트·XACK 사이의 종료/중복, DB lease·PEL 회수, Stream/그룹 유실 재구성, 미처리 보존 정리와 Pub/Sub 수신 유실·FE 이력 복구를 검증한다. 상세 기준은 [채팅 테스트 기준](../../apps/chat-app/docs/workflow/test-criteria.md)을 따른다. AOF·noeviction·OOM 등 운영 설정 시험은 별도 컨테이너로 격리해 공유 컨테이너의 전역 설정을 바꾸거나 다른 테스트의 키를 지우지 않는다.

chat-app 테스트 설정(`apps/chat-app/src/test/resources/hapbang-module-test.yaml`)은 연결 풀을 5로 줄이고 전달 파이프라인(`hapbang.chat.delivery.pipeline.enabled`)과 실시간 전달(`hapbang.chat.realtime.enabled`)을 명시적으로 끈다(둘 다 운영 코드 기본값은 켜짐). 파이프라인을 검증하는 테스트만 `@TestPropertySource`로 켜고 메모리 실시간 전파(`InMemoryRealtimeChatFanout`)와 Redis 컨테이너를 쓴다. 실시간 경로 E2E(`@ChatE2ETest` 등)는 둘 다 켠다.

이 설정은 자동 테스트 전용이다. 로컬 앱 실행·운영 배포 구성, 업무 이벤트의 Kafka 전환과 Redis 채팅 전달 로직은 별도다.

## 설정과 내부 API

- 내부 API 키가 필수인 컨텍스트는 `hapbang.internal-api.key`를 테스트 값으로 설정한다. `@IntegrationTest`는 테스트 키를 제공한다. 직접 `@SpringBootTest`를 쓰는 테스트도 필요한 값을 명시해야 한다.
- 앱의 YAML이 테스트에 영향을 주지 않도록 테스트 설정의 우선순위와 활성 프로필을 확인한다. 실제 기동 검증과 서비스 일부만 로드하는 테스트를 구분한다.
- 모듈 테스트(`spring.config.name=hapbang-module-test`)는 `application.yaml`을 읽지 않고 `@ConfigurationProperties`의 코드 기본값을 쓴다. 운영 기본값과 다르게 둬야 하는 값은 앱의 `src/test/resources/hapbang-module-test.yaml`에 둔다(예: chat-app은 회원·출연 이벤트 Kafka 소비 `hapbang.chat.member-events.enabled`·`hapbang.chat.appearance-events.enabled`를 끄고, Kafka 시험 클래스만 켠다). chat-app의 Kafka 소비 값(토픽·그룹·재시도·DLT)은 설정 키가 아니라 코드 상수로 만든 소비자 값 빈이므로, Kafka 시험은 그 빈을 `@TestBean`으로 바꿔 자기 토픽·그룹을 쓰고 재시도를 줄인다.
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

PR CI(`.github/workflows/ci.yml`)는 PR에서 바뀐 앱만 테스트한다(#488). `changes` job이 `.github/scripts/apps.sh tested`로 앱을 고르고, 앱마다 러너 하나(`test (<앱>)`)에서 `./gradlew :<앱>:check :<앱>:bootJar --parallel`로 테스트해 jar를 만든 뒤 자기 `Dockerfile`로 이미지가 빌드되는지 확인한다(push하지 않음). native 앱(지금은 customer-support-app)은 이어서 `nativeCompile`과 `Dockerfile.native` 이미지 빌드를 확인한다(약 10~14분 추가, [Native Image](../architecture/infrastructure/native-image.md)). JVM 테스트는 native 실행 파일에서만 나는 reflection·resource 오류를 잡지 못한다. `structure` job은 `./gradlew verifyModuleStructure`로 모듈 구조를 검사하고 common 라이브러리를 테스트한다. `verifyModuleStructure`는 `--parallel`과 함께 실행하면 Gradle이 막으므로 따로 실행한다. `manifests` job은 `k8s/`와 `apps/<앱>/k8s`의 overlay를 렌더링해 schema로 검사한다([k8s.md](../deploy/k8s.md#ci)). 필수 체크 `verify`는 이 job들의 결과를 모은다. `manifests`도 모으므로(#792) manifest 검사가 실패하면 병합할 수 없다.

- **바뀐 앱**: `apps/<앱>/`가 바뀌면 그 앱, `common/`·`gradle/`·루트 `build.gradle.kts`·`settings.gradle.kts`·`gradlew`·`ci.yml`·`apps.sh`가 바뀌면 전체다. `apps/<앱>/k8s/`(`manifests` job이 검사)와 `*.md`는 판정에서 빼므로, 이것만 바꾼 PR은 Gradle을 돌리지 않는다.
- 판정 규칙을 바꾸면 `.github/scripts/apps-test.sh`에 사례를 더한다. `changes` job이 매번 실행한다.
- dev 발행(`publish.yml`)이 호출할 때는 발행할 앱(이미지가 없는 앱)만 테스트한다(#559).
