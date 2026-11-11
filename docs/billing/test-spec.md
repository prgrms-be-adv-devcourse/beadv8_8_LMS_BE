# 지갑(Billing) 모듈 — 테스트 검증 기준

> 문서 탐색: [지갑 문서 INDEX](INDEX.md)

> 문서 지도: [현재 구현](module.md) · [결정 기록](decisions.md) · **[테스트 검증 기준](test-spec.md)** · [회귀 실행 기록](regression-log.md)
>
> - 이 문서는 **무엇을 검증해야 하는가**(검증 대상, 합격 기준, 근거 결정)를 `BT-*` ID로 정리하고 실제 테스트에 연결한다.
> - **실행 결과**(언제, 어느 커밋에서, 무슨 명령을 돌렸고 결과가 어땠나)는 [regression-log.md](regression-log.md)에만 적는다. 이 문서에는 통과·실패, 실행 ID, 실행 건수를 적지 않는다.
> - 정책 본문은 `docs/policy.md`와 [decisions.md](decisions.md)(`BD-*`)에 있다. 이 문서는 근거 ID만 적는다.
> - 테스트 클래스 수와 메서드 수는 **7.2 한 곳에서만** 관리한다. 다른 장은 7장을 가리킨다. 기준은 `dev` 40fa9b25(2026-11-06)이다.
> - 변경 이력은 8.4에 있다.

## 1. 실행 방법

### 1.1 공통

- Java 25(Gradle Toolchain). Integration·Repository 테스트는 Docker가 필요하다(Testcontainers `postgres:18`, JVM당 컨테이너 1개, reuse 없음).
- 결과는 `apps/billing-app/build/test-results/test/TEST-*.xml`로 확인한다. `UP-TO-DATE`·`FROM-CACHE`로 건너뛴 태스크는 새 실행으로 기록하지 않는다(`docs/dev/testing.md`). 새로 실행하려면 `--rerun-tasks` 또는 `cleanTest`를 쓴다.
- CI는 billing-app이 테스트 대상인 PR에서 billing-app 전용 러너로 `./gradlew :billing-app:check :billing-app:bootJar --no-daemon --parallel`을 돌린다(`.github/workflows/ci.yml`, `TZ=Asia/Seoul`). 대상 판정은 `docs/dev/testing.md`의 CI에 있다.
- 실행했으면 결과를 [regression-log.md](regression-log.md) 1장 형식으로 남긴다.

### 1.2 단계별 명령

| 단계 | 명령 | Docker | 비고 |
| --- | --- | --- | --- |
| C. Compile | `./gradlew :billing-app:compileJava :billing-app:compileTestJava` | 불필요 | 모듈 구조 검사는 `./gradlew verifyModuleStructure`(따로 실행, `--parallel` 금지) |
| U. Unit | 1.3의 `--tests` 목록 | 불필요 | 7.2의 U 행 |
| S. Slice (선택) | `./gradlew :billing-app:test --tests 'com.hapbang.billing.global.auth.GatewayUserHeaderFilterTest' --tests 'com.hapbang.billing.global.config.PageConfigTest' --tests 'com.hapbang.billing.global.config.SecurityConfigTest' --tests 'com.hapbang.billing.global.exception.GlobalExceptionHandlerTest' --tests 'com.hapbang.billing.global.internal.InternalApiSecurityConfigTest'` | 불필요 | 7.2의 S 행(`@WebMvcTest`), 보안·오류 응답 |
| I. Integration | `./gradlew :billing-app:test` | **필요** | 전체 실행(Unit 포함). Integration만 따로 고르는 Gradle 설정은 없다 |
| T. Contract | 없음 | — | 6.2 |

S 명령은 클래스를 하나씩 적는다. `global.config.*`로 고르면 같은 패키지의 Integration 테스트(`BillingEventKafkaTest`·`BillingTracingTest`·`ManagementPortTest`)까지 돌아 Docker가 필요하다.

### 1.3 Unit만 실행하기 (현재 Gradle 설정 그대로)

**Unit만 실행하는 Gradle 설정은 없다.** 루트 `build.gradle.kts`의 테스트 설정은 `tasks.withType<Test> { useJUnitPlatform() }` 하나다. 태그(`@Tag` 사용 0건), 전용 태스크, 소스셋 분리, 테스트 resources가 모두 없다. 클래스 이름 접미사도 종류와 맞지 않는다(`WalletFacadeTest`·`ExchangeSettlementUseCaseTest`·`ApiV1*ControllerTest`는 Integration). 그래서 지금은 클래스 목록을 명시해야 한다.

```bash
./gradlew :billing-app:test --rerun-tasks \
  --tests 'com.hapbang.billing.app.BillingCommandValidatorTest' \
  --tests 'com.hapbang.billing.app.BillingExternalEventsTest' \
  --tests 'com.hapbang.billing.app.BillingKeysTest' \
  --tests 'com.hapbang.billing.app.BillingBatchJobLockTest' \
  --tests 'com.hapbang.billing.app.ChangedWalletReconciliationUseCaseTest' \
  --tests 'com.hapbang.billing.app.BillingSharedDtoAccessTest' \
  --tests 'com.hapbang.billing.app.BillingUserBackfillUseCaseTest' \
  --tests 'com.hapbang.billing.app.ChargeUsageHistoryTest' \
  --tests 'com.hapbang.billing.app.ClosedWalletBalanceMetricsTest' \
  --tests 'com.hapbang.billing.app.EarningQueryUseCaseTest' \
  --tests 'com.hapbang.billing.app.EarningSourceTest' \
  --tests 'com.hapbang.billing.app.ExchangeAccountCipherTest' \
  --tests 'com.hapbang.billing.app.ExchangeSettlementBatchConditionTest' \
  --tests 'com.hapbang.billing.app.ExchangeSettlementRoundTest' \
  --tests 'com.hapbang.billing.app.HoldSplitTest' \
  --tests 'com.hapbang.billing.app.LedgerOperationTest' \
  --tests 'com.hapbang.billing.app.LedgerRecordUseCaseTest' \
  --tests 'com.hapbang.billing.app.LockTimeoutsTest' \
  --tests 'com.hapbang.billing.app.PaymentHoldRequestsTest' \
  --tests 'com.hapbang.billing.app.PayoutSplitTest' \
  --tests 'com.hapbang.billing.app.ReleaseSplitTest' \
  --tests 'com.hapbang.billing.app.WalletCreateUseCaseTest' \
  --tests 'com.hapbang.billing.app.WalletMaskingStatusesTest' \
  --tests 'com.hapbang.billing.domain.*' \
  --tests 'com.hapbang.billing.global.auth.LoginUserIdArgumentResolverTest' \
  --tests 'com.hapbang.billing.global.config.DefaultSecurityUserTest' \
  --tests 'com.hapbang.billing.global.exception.BusinessExceptionTest' \
  --tests 'com.hapbang.billing.global.internal.InternalApiKeyFilterTest' \
  --tests 'com.hapbang.billing.global.internal.InternalApiPortConfigTest' \
  --tests 'com.hapbang.billing.global.internal.InternalApiPropertiesTest' \
  --tests 'com.hapbang.billing.global.ratelimit.UserRateLimiterTest' \
  --tests 'com.hapbang.billing.global.scheduling.ConditionalOnSchedulingEnabledTest' \
  --tests 'com.hapbang.billing.global.scheduling.JobLockTest' \
  --tests 'com.hapbang.billing.in.BillingFailureCodeContractTest' \
  --tests 'com.hapbang.billing.in.BillingStatusContractTest' \
  --tests 'com.hapbang.billing.out.MockPayoutActivationTest' \
  --tests 'com.hapbang.billing.out.PaymentKeyCipherTest' \
  --tests 'com.hapbang.billing.out.TossPaymentsClientTest'
```

- `com.hapbang.billing.domain.*`는 7.1의 `domain.` 클래스 전부이고 모두 Unit이다(`WalletApplyLedgerAccessTest`는 ArchUnit).
- `BillingUserBackfillUseCaseTest`·`EarningSourceTest`·`TossPaymentsClientTest`는 테스트 안에서 JDK `HttpServer`로 로컬 가짜 서버를 띄운다. `InternalApiPortConfigTest`는 내장 Tomcat 리스너를 직접 띄운다. `DefaultSecurityUserTest`는 `WebApplicationContextRunner`로 Boot 보안 자동 설정과 실제 설정 파일만 올린다. Docker는 필요 없다.
- 테스트 클래스를 더하거나 종류를 바꾸면 이 목록과 7장을 함께 고친다. 이 목록의 클래스 수는 7.2의 U 행과 같아야 한다.

### 1.4 Unit 분리 방법 제안 (Gradle은 아직 바꾸지 않는다)

| 안 | 방법 | 장점 | 단점·영향 | 판단 |
| --- | --- | --- | --- | --- |
| A | `common/test-support`의 `@IntegrationTest`·`@RepositoryTest` 메타 어노테이션에 `@Tag("integration")`을 더한다. 직접 `@SpringBootTest`를 쓰는 클래스(7.1의 "직접 `@SpringBootTest`" 행)에도 태그를 붙인다. 루트 `test` 태스크는 `excludeTags("integration")`, 새 `integrationTest` 태스크는 `includeTags`. `check`가 둘 다 실행 | I·R 클래스 대부분이 메타 어노테이션 한 곳으로 분류된다. 이름 변경 없음 | `test-support`·루트 Gradle 변경 → **모든 앱에 영향**, CI 명령·시간 변화. 테스트 방식은 미정 항목(루트 AGENTS 11장) | 권장. 팀 합의 필요 |
| B | 1.3처럼 `--tests` 목록을 쓰거나, Gradle `test` 태스크 `filter`에 목록을 넣는다 | 코드 변경 없음 | 목록을 손으로 관리해야 한다 | 지금 단계에서 쓴다 |
| C | 클래스 이름 접미사(`*IntegrationTest`)로 나눈다 | 단순 | 기존 클래스 이름을 바꿔야 한다(이번 범위에서 금지) | 비권장 |
| D | `src/integrationTest` 소스셋 분리 | 경계가 가장 명확 | 파일 이동이 크고 모든 앱 규칙이 바뀐다 | 비권장 |

## 2. 단계 정의

| 단계 | 정의 | 이 모듈의 대상 | 합격 기준 |
| --- | --- | --- | --- |
| C. Compile | main·test 컴파일, 앱 간 의존 금지 | `:billing-app` 전체 | 컴파일 오류 0, `verifyModuleStructure` 통과 |
| U. Unit | Spring 전체 컨텍스트·DB 없음. `ApplicationContextRunner` 경량 컨텍스트와 로컬 `HttpServer`는 허용 | 7.2의 U 행 | 실패 0. 건너뜀이 있으면 사유를 기록. 실행 목록이 7장 표와 같다(2.2) |
| S. Slice | `@WebMvcTest` (DB 없음) | 7.2의 S 행 | 실패 0 |
| I. Integration | `@IntegrationTest`·`@RepositoryTest`·직접 `@SpringBootTest` + PostgreSQL Testcontainers(일부는 Kafka도). billing-app **한 앱** 컨텍스트 | 7.2의 I·R 행 | 실패 0. JVM 칸이 "경계"인 항목은 통과해도 분리 운영을 보장하지 않는다 |
| T. Contract | 독립 프로세스 사이 계약(내부 HTTP, 이벤트, 인증)을 제공자·소비자 양쪽에서 같은 정의로 검증 | 없음 | 6.2의 대상이 생기면 정한다 (TBD) |

### 2.1 Integration 테스트가 검증하지 않는 것

- `@IntegrationTest`는 `spring.config.name=hapbang-module-test`로 **앱의 `application.yaml`을 읽지 않는다.** 내부 API 키는 `test-internal-api-key`이고, 배치는 `hapbang.scheduling.enabled`가 없어 꺼진다(추정). 실제 설정으로 기동하는지는 검증하지 않는다(BT-CFG-02). 실제 설정 파일을 읽는 것은 보안 자동 설정 범위의 `DefaultSecurityUserTest`(BT-SEC-08)뿐이다.
- `ModuleTestConfiguration`은 `@ComponentScan("com.hapbang")`라 billing 테스트 클래스패스의 `shared` 빈도 함께 뜬다. 다른 앱의 빈은 클래스패스에 없다.
- 로그인은 `GatewayLogin.as`(운영에서 Gateway가 넘기는 `X-User-Id`·`X-User-Role` 헤더를 싣는 후처리기, #537)로 흉내 낸다. 요청이 실제 `GatewayUserHeaderFilter`를 거친다. Envoy의 JWT 검증과 헤더 제거는 검증하지 않는다(BT-SEC-05).
- 회원 이벤트는 `BillingMemberEventKafkaTest`가 Kafka Testcontainers의 `member.events.v1`에 member-app과 같은 형식(shared 레코드 JSON, `__TypeId__` 헤더, key 회원 ID)으로 보내 두 소비 그룹의 처리를 검증한다. 리스너 로직은 직접 호출로도 본다(`WalletCreationIntegrationTest`). 실제 member-app 발행(#416)과 이어 보는 검증은 없다(BT-EVT-01, 6.2).

### 2.2 Unit 실행 목록 대조

Unit은 "실패 0"만으로 합격으로 보지 않는다. 테스트가 빠지거나 실행되지 않은 채 통과하는 것을 막기 위해, 실행 결과(`TEST-*.xml`)를 7장 표와 대조한다(2026-10-29 결정). 분리 이전 테스트 수는 기준선으로 쓰지 않는다는 결정([regression-log.md](regression-log.md) 5장)은 그대로다.

1. 7장 표에서 종류가 U인 클래스마다 `TEST-com.hapbang.billing.<클래스>.xml`이 있다. 없으면 실행되지 않은 것이다.
2. 소스의 `@Test`·`@ParameterizedTest` 메서드 수(7.2 세는 방법)가 7장 표의 메서드 수와 같다. 다르면 표를 먼저 고친다(8.1).
3. `@Test` 메서드는 XML testcase 이름으로 정확히 한 번 나온다. 이름은 `@DisplayName`이고, 없으면 `메서드명(파라미터 타입)`이다.
4. 나머지 testcase는 `@ParameterizedTest` 실행으로 세고, 그 수가 파라미터 메서드 수 이상이다. 실행 이름은 `name` 속성에 따라 형식이 달라서 메서드별로 짝짓지는 않는다.
5. 클래스 수, 메서드 수, 실행 수, 대조 문제 수를 regression-log 실행 기록에 적는다. 대조 문제가 1건이라도 있으면 통과가 아니다.

## 3. 테스트 인프라와 대역

| 구성 | 위치 | 내용 |
| --- | --- | --- |
| `@IntegrationTest` | `common/test-support` | `@SpringBootTest(ModuleTestConfiguration)` + `PostgresTestcontainersConfig` + `DatabaseCleanUpListener`. Modulith 이벤트 `completion-mode=delete`, OAuth2 client 테스트 값, 가짜 S3 설정, 내부 API 키 |
| `@RepositoryTest` | 같은 곳 | `@DataJpaTest` + PG 컨테이너, 테스트마다 롤백 |
| `PostgresTestcontainersConfig` | 같은 곳 | `postgres:18`, static 시작(JVM당 1개), Flyway baseline 0·out-of-order, `ddl-auto=validate` |
| `DatabaseCleanUp(Listener)` | 같은 곳 | 테스트 메서드마다 JPA 매핑 테이블 TRUNCATE(IDENTITY 값은 초기화하지 않음) |
| `GatewayLogin.as(userId, role)` | `billing.fixture` | 사용자 헤더 후처리기 (#537) |
| `WalletMemberFixture.active·member` | `billing.fixture` | 지갑 회원(복제본) 데이터. 충전·환불·환전 신청 테스트는 `setUp`에서 저장한다 (#537) |
| `WebSliceTestConfiguration` | 같은 곳 | `@WebMvcTest` 진입점 |
| `KafkaTestcontainersConfig`, `RedisTestcontainersConfig` (#380) | `common/test-support` | `@Import`로 가져온 테스트에서만 켜지는 Kafka(`apache/kafka:4.2.1` 단일 노드 KRaft)·Redis(`redis:8`). JVM당 종류별 1개, 동적 포트를 `spring.kafka.bootstrap-servers`·`spring.data.redis.*`에 주입. `@IntegrationTest` 기본값은 그대로 PostgreSQL만 띄운다. billing에서는 Kafka를 `BillingMemberEventKafkaTest`·`BillingEventKafkaTest`가 가져오고, Redis는 쓰지 않는다. 쓰는 규칙은 `docs/dev/testing.md` "Kafka·Redis Testcontainers" |
| `BillingFixture` | `fixture/` | 엔티티 빌더 |
| `ChargeFixture` | `fixture/` | 테스트 전용 암호화·HMAC 키, Toss URL·타임아웃 등록 |
| `FakeTossPaymentsServer` | `fixture/` | JDK `HttpServer` 가짜 TossPayments(승인 멱등키, 조회 404, 취소, 장애 재현) |

| 대역 종류 | 쓰는 곳 | 대신하는 대상 |
| --- | --- | --- |
| JDK `HttpServer` 가짜 서버 | `EarningSourceTest`, `ApiV1SettlementEarningControllerTest`, `BillingUserBackfill*Test`, `BillingTracingTest`(회원 내부 API) | lecture·appearance·member 앱의 내부 API (**제공자 코드는 검증하지 않는다**) |
| `FakeTossPaymentsServer` | 충전·환불·`TossPaymentsClientTest` | TossPayments |
| Mock 은행 시나리오(`MockPayoutScenarios`) | 송금·정산·Mock HTTP 테스트 | 은행(운영 코드의 Mock 은행 자체를 실제 HTTP로 호출) |
| `@MockitoSpyBean` | `HoldCreateIntegrationTest`(Repository 장애 주입), 송금·정산(Clock·RequestIds·Client·`InternalHoldFacade`), `RefundIntegrationTest`(`RefundClock`), `BillingMemberEventIsolationTest`, `BillingMemberEventKafkaTest`(복제본 반영 실패 주입) | 장애·시각 주입 |
| Kafka Testcontainers (`KafkaTestcontainersConfig`) | `BillingMemberEventKafkaTest`, `BillingEventKafkaTest` | member-app의 이벤트 발행(테스트가 shared 레코드를 JSON + `__TypeId__`로 직접 보낸다), 지갑 이벤트 발행 확인 |
| 가짜 `EarningSource` | `EarningQueryUseCaseTest` | 강의·출연 수익 출처 |
| Mockito `mock` | `LedgerRecordUseCaseTest`, `WalletCreateUseCaseTest`, `BillingUserBackfillUseCaseTest`, `ExchangeSettlementBatchConditionTest`, `ClosedWalletBalanceMetricsTest` | Repository·UseCase |

## 4. 검증 대상 (`BT-*`)

### 4.1 표 읽는 법

- **ID**: `BT-<영역>-<번호>`. 한 번 붙인 ID는 바꾸거나 재사용하지 않는다. 대상이 없어지면 행을 지우지 않고 명세 상태를 "폐기"로 바꾼다.
- **검증 기준**: 합격하려면 테스트가 확인해야 하는 동작이다.
- **근거**: [decisions.md](decisions.md)의 `BD-*`, [module.md](module.md) 4장 불변식 `INV-*`, `docs/policy.md` 장 번호. 일부만 대체된 결정은 유효한 부분을 근거로 쓰고 대체한 결정을 함께 적는다. 근거 결정이 없으면 Issue 번호와 "BD 없음"을 적는다.
- **단계**: U / S / I / R(Repository, I 단계에서 실행) / T.
- **테스트**: `클래스#메서드`. 메서드가 많으면 대표만 적고 `…`·`*`로 줄인다. 메서드 수는 적지 않는다(7장). 연결된 테스트가 없으면 "테스트 없음", 기준 일부만 검증하면 "일부"로 시작하고 6.1에 빈 곳을 적는다.
- **JVM**: JVM 분리 영향.

| 값 | 뜻 |
| --- | --- |
| 없음 | Billing 앱 안에서 닫힌다. 분리 전후 의미가 같다 |
| 경계 | 테스트는 대역(GatewayLogin, 가짜 서버, 직접 붙인 헤더)으로 경계를 흉내 낸다. 통과해도 분리 환경의 동작은 보장하지 않는다 |
| 깨짐 | 검증하는 계약 자체가 독립 JVM에서 성립하지 않는다 |
| 미확인 | 코드로 영향을 판단하지 못했다 |

- **명세 상태**: 이 검증 기준이 지금도 맞는지다. 실행 결과가 아니다.

| 값 | 뜻 |
| --- | --- |
| 유효 | 지금 코드·결정에 맞는 기준이다 |
| 폐기 | 검증 대상이 없어졌다. 행은 남긴다 |
| 대체됨 → BT-… | 다른 BT가 이 기준을 이어받았다 |

- 항목별 실행 결과는 [regression-log.md](regression-log.md)에서 찾는다(3장 실행 기록, 4.2 BT별 마지막 기록).

### 4.2 지갑·원장 (WAL·LED)

| ID | 대상 | 검증 기준 | 근거 | 단계 | 테스트 | JVM | 명세 상태 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| BT-WAL-01 | 지갑 중복 생성 | 회원당 1개. UNIQUE 위반(23505 + 제약 이름)일 때만 기존 지갑을 돌려준다. 언어 설정과 무관 | BD-1, BD-4, BD-5 | U, I | `WalletCreateUseCaseTest`, `WalletDuplicateViolationTest`, `WalletCreationIntegrationTest#concurrentCreation`, `#uniqueConflict_*` | 없음 | 유효 |
| BT-WAL-02 | 가입 이벤트 → 지갑 | 일반·인플루언서 생성, ADMIN 제외, 재전달·중복 이벤트에도 1개, Kafka 그룹 `billing-app.wallet-creation`(#430) | BD-2, BD-3, BD-123 | I | `WalletCreationIntegrationTest#userJoined_*`, `#walletLookupThenUserJoined`, `#concurrentEventAndLookup`, `BillingMemberEventKafkaTest#joined_createsWalletAndReplica`, `#duplicateAndStaleEventsAreIgnored`, `#adminJoined_replicaWithoutWallet` | 없음 | 유효 |
| BT-WAL-03 | 내 지갑 조회 | 없으면 생성, 본인 것만, ADMIN 403, 비로그인 401, Swagger 응답 일치 | BD-2, BD-3 | I | `ApiV1WalletControllerTest`, `WalletFacadeTest` | 경계 (인증) | 유효 |
| BT-WAL-04 | 거래 내역 | 유형 분류, 지갑별 합계 = 잔액, 페이징, 본인 것만, 지갑 없으면 빈 페이지 | decisions 12장 | I | `WalletTransactionQueryIntegrationTest`, `ApiV1WalletTransactionControllerTest` | 경계 (인증) | 유효 |
| BT-WAL-05 | 회원 탈퇴용 지갑 확인하고 닫기·다시 열기 (#623) | 막힌 이유가 없으면 닫고 `CLOSED`·빈 이유. 충전·수익 HP 잔액이 남으면 닫지 않고 `BLOCKED`·금액을 적은 이유. 처리 중인 환불·환전·확인 중인 충전도 `BLOCKED`이고 환전은 정산 예정일을 준다. 출연·강의 보류와 주문만 한 충전(PENDING)은 막지 않는다. 같은 탈퇴 작업 재요청은 처음 결과를 replayed로(CLOSED·BLOCKED 각각), 같은 탈퇴 작업 ID를 다른 회원이 보내면 닫기·다시 열기 모두 `IDEMPOTENCY_KEY_CONFLICT`. 닫힌 지갑 다시 열기 = `REOPENED`, 재요청 replayed, 그 뒤 닫기 `WITHDRAWAL_ALREADY_REOPENED`. 닫기 전에 받은 다시 열기는 `NOT_CLOSED`를 기록하고 늦은 닫기는 지갑을 닫지 않고 거절. `BLOCKED`였던 작업·다른 탈퇴 작업으로 닫힌 지갑의 다시 열기는 `NOT_CLOSED`. 지갑이 없으면 기록만 남기고 `CLOSED`, 나중에 만든 지갑은 닫혀 있다가 다시 열기로 열린다(닫고 다시 열었으면 열린 채로 생긴다). 동시에 와도 닫힌 지갑에 새 보류가 생기지 않고, 닫기·다시 열기 뒤 닫힌 채 남지 않고, 닫기·지갑 생성 뒤 열린 채 남지 않는다. 내부 API는 RsData로 돌려주고 `userId` 없으면 400 `INVALID_REQUEST`, 키 없으면 401 | BD-141 | I | `WalletWithdrawalIntegrationTest#closed`, `#blockedBy*`, `#pendingChargeDoesNotBlock`, `#closeReplay`, `#otherUser`, `#reopen*`, `#closeWithoutWallet`, `#reopenWithoutWallet`, `#concurrent*`, `ApiV1BillingInternalControllerTest#closeForWithdrawal`, `#reopenForWithdrawal`, `#withdrawalRequestErrors` | 없음 (호출하는 member-app과의 계약 테스트는 없다) | 유효 |
| BT-WAL-06 | 닫힌 지갑의 HP 이동과 잔액 지표 (#623, #674) | 닫힌 지갑은 충전 주문·보류·환불·환전 신청을 `WALLET_CLOSED`로 거절한다. 이미 생긴 보류의 반환·재요청과 다른 회원이 보낸 지급은 받고, 들어온 HP를 warn 로그와 지표로 알린다. 잔액이 남은 닫힌 지갑 지표는 수집할 때마다 DB에서 센다(재시작 직후 0으로 보이지 않는다). 세지 못하면 예전 값이나 0이 아니라 NaN | BD-141, BD-145 | U, I | `WalletWithdrawalIntegrationTest#closedWalletRejectsNewMoney`, `#closedWalletKeepsObligations`, `ClosedWalletBalanceMetricsTest#countsOnEveryScrape`, `#nanOnFailure`, `WalletMaskingIntegrationTest#closesWalletWithBalance` | 없음 | 유효 |
| BT-WAL-07 | 회원 정보 파기 때 계좌 지우기 (#674) | 끝난 환전(송금 성공·최종 실패 포함)의 계좌번호 암호문·예금주를 비우고 은행 코드·검증 상태와 환전 금액·상태는 남긴다. 끝난 환전의 끝난 가상 송금은 예금주·계좌번호 해시를 마스킹 값으로 덮고 금액·상태·거래 번호는 남긴다. 처리 중인 환전의 계좌와 그 송금은 지우지 않고 error 로그를 남긴다. 지운 계좌를 회원·관리자가 조회하면 계좌번호 `****`, 예금주 null이고 복호화 실패 로그를 남기지 않는다. 같은 이벤트를 두 번 받아도 결과가 같고, 다른 회원의 계좌·송금·지갑은 건드리지 않는다. 모든 환전 상태는 끝난 상태와 처리 중 상태 중 정확히 한 곳에 있다 | BD-144 | U, I | `WalletMaskingIntegrationTest#erasesFinishedExchangeAccount`, `#erasesCompletedAndFinalFailedExchangeAccount`, `#erasedAccountView`, `#masksFinishedTransfer`, `#keepsInProgressExchange`, `#idempotent`, `#otherMemberUntouched`, `WalletMaskingStatusesTest#everyStatusIsFinishedOrInProgress`, `ExchangeAccountCipherTest#erased` | 없음 | 유효 |
| BT-WAL-08 | 회원 정보 파기 때 지갑 닫기 (#674) | 파기 이벤트를 받으면 회원 이벤트 그룹이 열린 지갑을 닫고 DLT로 보내지 않는다. 파기로 닫으면 탈퇴 작업 ID 없이 닫히고 다시 열기 대상이 아니다(다시 열린 채 남은 지갑도 닫고, 그 탈퇴 작업의 다시 열기 재요청으로 열리지 않는다). 잔액이 남아도 닫고 잔액은 그대로 둔다. 이미 탈퇴로 닫힌 지갑은 탈퇴 작업 ID와 닫은 시각을 바꾸지 않는다. 지갑이 없는 회원은 아무것도 바꾸지 않는다. 플랫폼 지갑은 파기로 닫을 수 없다 | BD-145 | U, I | `WalletTest#closeForMasking`, `#closeForMaskingKeepsWithdrawalClose`, `#platformCannotCloseForMasking`, `WalletMaskingIntegrationTest#closesReopenedWallet`, `#closesWalletWithBalance`, `#keepsWithdrawalClosedWallet`, `#noWallet`, `BillingMemberEventKafkaTest#masked_closesOpenWallet` | 없음 | 유효 |
| BT-LED-01 | 원장·지갑 원자성 | 원장 실패 = 지갑 롤백, 지갑 실패 = 원장 롤백 | BD-7, INV-1 | I | `LedgerRecordIntegrationTest#ledgerFailure_rollsBackWallet`, `#walletFailure_rollsBackLedger` | 없음 | 유효 |
| BT-LED-02 | 잔액 변경 유일 경로 | `Wallet.applyLedger`는 `LedgerRecordUseCase`만 호출 | BD-7 | U | `WalletApplyLedgerAccessTest#onlyLedgerRecordUseCaseAppliesLedger` | 없음 | 유효 |
| BT-LED-03 | 잔액 규칙 | 버킷 분리, 음수 금지, 오버플로, 0 HP 금지, 같은 원장 재적용 금지 | BD-8, BD-12, INV-3 | U, I | `WalletTest#applyLedger_*`(지갑 유형·FEE 가드 포함 #336), `LedgerOperationTest`, `LedgerRecordIntegrationTest#insufficientBalance`, `#balanceOverflow`, `#loadedLedgerCannotBeReapplied` | 없음 | 유효 |
| BT-LED-04 | 지갑 락 | ID 오름차순, 반대 순서 동시 작업에 교착 없음, 오래된 엔티티에 lost update 없음, 트랜잭션 필수. 이 순서는 `billing` database 안의 것뿐이다 | BD-10, BD-11, BD-142 | U, I | `LedgerRecordUseCaseTest#locksWalletsInIdOrder`, `LedgerRecordIntegrationTest#opposingWalletOrder_noDeadlock`, `#staleWalletInCallerTransaction_noLostUpdate`, `#lockWallet_*` | 없음 | 유효 |
| BT-LED-05 | 원장 멱등 | 같은 키·같은 내용 = 재생, 다른 내용 = 충돌, 동시 중복 = 1회 반영, 다른 지갑 같은 키 = 충돌 | BD-14~BD-16 | I | `LedgerRecordIntegrationTest#retry_replays`, `#sameKeyDifferentContent_conflict`, `#concurrentDuplicates_appliedOnce`, `#concurrentSameKeyDifferentWallets` | 없음 | 유효 |
| BT-LED-06 | 역분개 | 누적 한도, 역분개의 역분개 금지, 음수 잔액 롤백, 동시 역분개 한도, 재생 | BD-18 | I | `LedgerReversalIntegrationTest` | 없음 | 유효 |
| BT-LED-07 | 원장 append-only | SQL UPDATE·DELETE 거부(트리거) | INV-2 | R | `BillingConstraintsTest#원장은_SQL로도_수정하거나_삭제할_수_없다` | 없음 | 유효 |
| BT-LED-08 | 잔액 대사 | 일치·캐시 불일치·원장 불일치 탐지, 동시 작업 중 같은 스냅숏, 수수료 수입 칸(FEE 가용, #336) | BD-20, INV-1 | I | `WalletReconciliationIntegrationTest`, `ExchangePayoutIntegrationTest`의 플랫폼 지갑 대사(BT-EXP-08) | 없음 | 유효 |
| BT-LED-09 | 원장 기록의 트랜잭션 참여 | 지갑 앱 안 호출자 트랜잭션에 참여한다(`REQUIRED`). 다른 앱 트랜잭션 참여는 검증 대상이 아니다(BD-143) | BD-53 (지갑 안 호출 부분. 다른 앱 참여 부분은 BD-143으로 대체) | I | `LedgerRecordIntegrationTest#joinsCallerTransaction`, `#multipleRecordsInSameTransaction_keepPendingChanges` | 없음 (Billing 내부 호출) | 유효 |
| BT-LED-10 | 잔액 대사 자동 실행 (#607) | 창(72시간) 안에서 수정된 지갑만 대사하고 창 밖은 대사 안 함, 원장만 생긴 지갑·캐시만 바뀐 지갑을 불일치로 잡음, 고치지 않음, 수정·원장이 함께 있어도 한 번만, 한 지갑의 오류에도 계속, 오류 지갑은 끝에 1회 재시도, 재시도에도 실패하면 다음 회차에 창 밖이어도 대사하고 성공하면 빠짐, 결과별 지갑 수(다음 실행은 덮어씀)·마지막 성공 시각(failed 0일 때만, 전부 실패면 그대로, 대상 0개는 성공), 창 설정값 적용(24h)·기본 72h, 다른 pod가 락을 쥐면 실행 안 함 | BD-139, BD-20, INV-1 | U, I | `ChangedWalletReconciliationIntegrationTest`, `ChangedWalletReconciliationUseCaseTest`, `BillingBatchJobLockTest`(대사 배치), `ManagementPortTest`(지표 노출) | 없음 | 유효 |

### 4.3 보류·반환·지급·위약금·확정 차감 (HLD·REL·PAY·PEN·CAP)

| ID | 대상 | 검증 기준 | 근거 | 단계 | 테스트 | JVM | 명세 상태 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| BT-HLD-01 | 보류 배분 | 수익 HP 먼저, 충전 HP는 완료 시각·ID 순 FIFO, 배분 가능한 상태만 사용 | BD-24 | U, I | `HoldSplitTest`, `HoldCreateIntegrationTest#revenueFirstThenCharge`, `#fifoByCompletedAt`, `#fifoSameCompletedAt_byId`, `#allocatableStatusesOnly`, `#holdAndUsageRows` | 없음 | 유효 |
| BT-HLD-02 | 보류 멱등 | 참조당 1건, 재요청 = 처음 배분 그대로, 금액·지갑이 다르면 충돌, 동시 요청 1건 | BD-21, BD-23 | I | `HoldCreateIntegrationTest#retry_replays`, `#retryAfterBalanceChange_keepsOriginalSplit`, `#sameReference*_conflict`, `#concurrentSameRequest_oneHold`, `#concurrentSameReferenceDifferentWallets` | 없음 | 유효 |
| BT-HLD-03 | 보류 정합성 오류 | 원금 불일치 `CHARGE_PRINCIPAL_MISMATCH`, 원장만·보류만 있으면 `HOLD_STATE_INCONSISTENT` | BD-23, BD-25, INV-4 | I | `HoldCreateIntegrationTest#chargePrincipalMismatch`, `#ledgerWithoutHold_inconsistent`, `#holdWithoutLedger_inconsistent` | 없음 | 유효 |
| BT-HLD-04 | 조회 순서 경합 | 원장 → 보류 순서로 정상 경쟁은 충돌, 비정상은 불일치로 분류 | BD-27 | I | `HoldCreateIntegrationTest#lookupRace_*`, `#concurrentInsert_uncommittedHold_uniqueViolation` | 없음 | 유효 |
| BT-HLD-05 | 보류 원자성 | 어느 단계가 실패해도 전부 롤백, 동시 보류에 이중 배분 없음 | — | I | `HoldCreateIntegrationTest#*_rollsBackAll`, `#concurrentDistinctHolds_noDoubleAllocation` | 없음 | 유효 |
| BT-HLD-06 | 멱등키 형식 | 결정론적 키, 최장 키 255자 이내 | BD-14, BD-21, BD-30, BD-44, BD-48 | U | `BillingKeysTest` | 없음 | 유효 |
| BT-REL-01 | 반환 순서 | 충전 HP 먼저, 원금 LIFO 복원, 복원 원금은 다시 FIFO 배분 | BD-29 | U, I | `ReleaseSplitTest`, `ChargeUsageHistoryTest#releaseLifo`, `HoldReleaseIntegrationTest#partial_chargeOnly_lifo`, `#partial_mixed_chargeFirst`, `#restoredPrincipal_reallocatedFifo` | 없음 | 유효 |
| BT-REL-02 | 반환 범위·상태 | 부분·전체, 초과 거절, 닫힌 보류 거절, 지급분은 반환 불가 | BD-32, BD-34 | U, I | `PointHoldReleaseTest`, `HoldReleaseIntegrationTest#partial_exceeds`, `#full_*`, `#closedHold_newRequestRejected`, `#paidAmount_notReleasable` | 없음 | 유효 |
| BT-REL-03 | 반환 멱등 | 같은 ID 재생(닫힌 뒤 포함), 다른 금액 충돌, 다른 보류 같은 ID는 별개, 동시 반환에 초과 없음 | BD-30~BD-32 | I | `HoldReleaseIntegrationTest#sameRequest_*`, `#sameRequestId_*`, `#retryAfterClosed_replays`, `#differentRequests_concurrent_noOverRelease` | 없음 | 유효 |
| BT-REL-04 | 이력 재생 대조 | 상세를 재생한 결과가 배분 누적값과 다르면 `HOLD_STATE_INCONSISTENT` | BD-33, INV-5 | U, I | `ChargeUsageHistoryTest#replay*`, `HoldReleaseIntegrationTest#usageNotMatchingHistory_inconsistent`, `#ledgerWithoutDetail_inconsistent` | 없음 | 유효 |
| BT-REL-05 | 반환 소유·원자성 | 없는 보류·남의 보류 거절, 단계별 실패 전체 롤백 | BD-32 | I | `HoldReleaseIntegrationTest#notFound`, `#otherMembersHold`, `#*_rollsBackAll` | 없음 | 유효 |
| BT-REL-06 | 보류 무효화 (#479) | 남은 보류 전부 반환(`RELEASED`, `RELEASE:{holdId}:VOID`), 끝난 보류는 그대로(`ALREADY_CLOSED`), 보류 없음은 표시만(`VOIDED`). 무효화 뒤 보류·보류 재요청은 `HOLD_VOIDED`, 같은 무효화는 처음 결과 재생, 남의 보류·기록은 `HOLD_OWNER_MISMATCH`, 같은 참조 대상의 보류·무효화 동시 실행에 남은 보류 없음 | BD-125, BD-126 | S, I | `HoldVoidIntegrationTest`, `ApiV1BillingInternalControllerTest#voidHold`, shared `BillingApiClientTest#보류_무효화는_hold_voids_경로로_보내고_결과를_돌려준다` | 없음 | 유효 |
| BT-PAY-01 | 지급 배분 | 수익 HP 보류 먼저, 충전 원금 FIFO 소진, 받는 쪽 수익 HP 가용, 초과 거절 | BD-39 | U, I | `PayoutSplitTest`, `PointHoldPayoutTest`, `HoldPayoutIntegrationTest#payout_revenueOnly_full`, `#payout_chargeOnly_fifo`, `#payout_mixed_revenueFirst`, `#payout_exceeds` | 없음 | 유효 |
| BT-PAY-02 | 원천 1회 정산 | 같은 원천은 보류·유형과 무관하게 1회, NORMAL·PENALTY 배타, 동시 요청도 DB UNIQUE로 1건 | BD-42, BD-43, INV-6 | I, R | `HoldPayoutIntegrationTest#sameSource_normalAndPenaltyExclusive`, `#request_contractSettlesOnce`, `#sameContractAcrossHolds_rejected`, `#concurrent_sameContractAcrossHosts_*`, `BillingConstraintsTest#지급_원천은_보류와_지급_유형이_달라도_한_번만_정산한다` | 없음 | 유효 |
| BT-PAY-03 | 지급 멱등 | 같은 요청 ID 재생, 다른 보류의 같은 ID는 별개, 동시 같은 지급 1회 | BD-44 | I | `HoldPayoutIntegrationTest#payout_idempotency`, `#payout_sameRequestIdOtherHold`, `#concurrent_samePayout` | 없음 | 유효 |
| BT-PAY-04 | 중복 지급 오류 순서 | 끝난 보류 `HOLD_ALREADY_CLOSED`가 원천 중복 `PAYOUT_ALREADY_SETTLED`보다 먼저 | BD-69, BD-70 | I | 테스트 없음 (6.1) | 없음 | 유효 |
| BT-PAY-05 | 혼합 이력·경합 | 반환·지급 혼합 재생, 반환과 지급 동시 실행 시 한쪽만 성공(지갑 → 보류 락), 양방향 지급에 교착 없음 | BD-34, BD-73 (금융 경합 부분. 같은 트랜잭션 flush 전제는 BD-142로 대체) | I | `HoldPayoutIntegrationTest#mixed_*`, `#concurrent_releaseAndPayout`, `#concurrent_bidirectional`, `#concurrent_sameGuestFromManyHosts` | 없음 | 유효 |
| BT-PAY-06 | 지급 요청 검증 | 자기 지급 `PAYOUT_TO_PAYER`, 원천 불일치 `PAYOUT_SOURCE_MISMATCH`, 실패 전체 롤백 | BD-41, BD-45 | I | `HoldPayoutIntegrationTest#payout_invalidRequests`(세부 미확인), `#payout_failures_rollBackAll` | 없음 | 유효 |
| BT-PAY-07 | 판매자 지갑 없음 | 지급 때 `WALLET_NOT_FOUND`로 롤백, 지갑 생성 후 같은 요청 ID로 재지급 | BD-68 | I | 일부: `HoldPayoutIntegrationTest#payout_invalidRequests`(받는 회원 지갑 없음 → `WALLET_NOT_FOUND`, 상태 불변). 재지급은 테스트 없음 (6.1) | 없음 | 유효 |
| BT-PEN-01 | 위약금 처리 | 받은 반환액 + 지급액을 원자적으로 처리, 비율 검증 없음, 기준액이 남은 보류를 넘으면 거절, 합계 overflow 거절 | BD-56 | U, I | `BillingCommandValidatorTest#penaltyAnyRatio`, `#penaltyOverflow`, `HoldPayoutIntegrationTest#penalty_10000`, `#penalty_10001_mixed`, `#penalty_otherRatio`, `#penalty_rejects` | 없음 | 유효 |
| BT-PEN-02 | 0 HP 규칙 | 게스트 지급 0 거절, 호스트 반환 0 허용(반환 상세·원장 없음) | BD-57, BD-58 | U, I | `BillingCommandValidatorTest#penaltyZeroPayout`, `HoldPayoutIntegrationTest#penalty_zeroRejected`, `#penalty_noRelease*` | 없음 | 유효 |
| BT-PEN-03 | 위약금 멱등·원자성 | 같은 취소 ID 재생, 동시 같은 취소 1회, 단계별 실패 전체 롤백 | BD-48, BD-59 | I | `HoldPayoutIntegrationTest#penalty_idempotency`, `#penalty_concurrentSameCancellation`, `#penalty_*Failure_rollsBack*` | 없음 | 유효 |
| BT-PEN-04 | 모집 위약금 | 게스트별 위약금·정상 지급 혼합 | BD-41, BD-43 | I | `HoldPayoutIntegrationTest#penalty_recruitOneGuest`, `#recruit_penaltyForOneGuest_normalForAnother`, `#payout_recruit_multipleGuests` | 없음 | 유효 |
| BT-CAP-01 | 종류별 보류 | 참조 유형별 MIXED·CHARGE_ONLY·REVENUE_ONLY, 종류 잔액 부족 거절, DB 제약 | BD-82, INV-8 | I | `HoldCaptureIntegrationTest#holdByType`, `#holdByType_insufficient`, `#internalHold_idempotencyAndScope`, `#dbConstraints` | 없음 | 유효 |
| BT-CAP-02 | 확정 차감 | 전체·부분, 이어서 반환, 멱등, 거절 조건, 동시 실행, 원장 실패 롤백 | BD-83, BD-86 | I | `HoldCaptureIntegrationTest#capture_*` | 없음 | 유효 |
| BT-CAP-03 | 충전 건 지정 | PG 결과대로 지정 충전 건만 확정 차감·반환, 금액 필수, 정확한 PG 금액 | BD-84, BD-85 | U, I | `ChargeUsageHistoryTest#targetedCharge*`, `HoldCaptureIntegrationTest#capture_byCharge_*` | 없음 | 유효 |
| BT-CAP-04 | MIXED 보호 | MIXED 보류는 확정 차감 불가, 지급과 확정 차감이 한 보류를 나눠 쓰지 않음, MIXED 회귀 | BD-83, BD-86 | I | `HoldCaptureIntegrationTest#mixedHold_regression`, `#captureAndPayout_neverShareHold` | 없음 | 유효 |

### 4.4 내부 API와 모듈 경계 (PORT·EVT)

| ID | 대상 | 검증 기준 | 근거 | 단계 | 테스트 | JVM | 명세 상태 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| BT-PORT-01 | 내부 API 결과 변환 | 보류·반환·지급·위약금 정산이 shared 결과 DTO(RsData `data`)로 바뀐다. 강의 주문 항목별 보류·반환 | BD-128, BD-111, BD-61 (BD-51은 계약 위치 부분만, BD-52는 폐기) | I | `ApiV1BillingInternalControllerTest#hold`, `#release`, `#payout`, `#settlePenalty`, `#lectureOrderItems` | 없음 | 유효 |
| BT-PORT-02 | 요청 단위 트랜잭션 | 요청 하나 = 지갑 트랜잭션 하나. 처리 중 실패(위약금의 반환 뒤 지급 실패 포함)는 그 요청의 반환·지급·원장 변경을 전부 롤백한다. 지갑 안 UseCase(환불·환전)가 부르는 보류는 그 트랜잭션에 참여한다. 다른 앱 트랜잭션 참여는 검증 대상이 아니다 | BD-143, BD-53 (지갑 안 호출 부분) | I | `ApiV1BillingInternalControllerTest#technicalFailureRollsBack`, `HoldCreateIntegrationTest#joinsCallerTransaction`, `HoldReleaseIntegrationTest#joinsCallerTransaction`, `#staleEntitiesInCallerTransaction_refreshed`, `HoldPayoutIntegrationTest#penalty_joinsCallerTransaction` | 없음 | 유효 |
| BT-PORT-03 | 금융 오류 응답 | 잔액 부족·지갑 없음·보류 없음·금액 오류·멱등 충돌이 `BillingErrorCode`의 HTTP 상태와 `code`로 오고 값이 바뀌지 않는다 | BD-60 (오류를 성공으로 해석하지 않는 부분), BD-70, BD-111 | I | `ApiV1BillingInternalControllerTest#businessErrors`, `#amountErrors`, `#idempotencyConflict` | 없음 | 유효 |
| BT-PORT-04 | 원격 보류·반환·지급·위약금 계약 | 내부 HTTP 경로·키 인증·요청/응답 DTO·오류 코드·타임아웃·결과 불명 처리·같은 요청 ID 재시도. 호출 쪽 Resilience4j timeout·circuit open일 때 성공으로 해석하지 않음. 보상 처리: 보류 실패 → 주문이 남지 않음, 주문 실패 → 보류 반환 | 루트 AGENTS 3장, D62·D63, BD-70, BD-143, 인프라 문서 3·10장, 강의 D64·D66 | T | 일부: shared `BillingApiClientTest`(실패 분류, RETRYABLE만 재시도, 회로 OPEN = `CIRCUIT_OPEN`·RETRYABLE, 시간 상한 = 결과 불명, 429·`HP_LOCK_TIMEOUT` 미집계, #411). 소비 앱의 결과 처리·보상은 각 앱 테스트가 Fake Client로 확인한다(강의 HP 작업 #275~#277, 출연 #274). 두 앱을 실제 HTTP로 잇는 계약 테스트는 없다 | 경계 | 유효 |
| BT-PORT-05 | 소비 앱 기동 | lecture-app·appearance-app이 Billing 원격 Client로 기동한다 | `docs/architecture/app-modules.md`, BD-128 | T | 테스트 없음 (Billing 범위 밖. k3s prod 단독 기동으로 확인한 기록은 `docs/deploy/k8s.md`) | 없음 | 유효 |
| BT-PORT-06 | 내부 Command 경계 (#494) | `app`·`domain`·`out`은 `shared.billing.dto`를 참조하지 않는다. 내부 Command의 참조 유형 목록·요청 ID 형식은 shared 계약과 같은 값을 받고 거절한다. 구조 오류는 NPE·IAE | BD-129 | U | `BillingSharedDtoAccessTest#onlyInPackageUsesSharedDto`, `PaymentHoldRequestsTest` | 없음 | 유효 |
| BT-PORT-07 | 대사용 보류·지급 조회 (#577) | 참조 대상별 조회: 요청 순서·중복 한 번·없는 ID 빈 기록, 보류만·일부 반환·지급·위약금·무효화만, 충전·수익 HP별 금액과 합계, 최초 = 남은 + 반환 + 지급 + 확정 차감, 지급 건, 0개·501개·보류 불가 유형 400, 키 없음 401. 목록: 바뀐 순서·`hasMore`가 false가 될 때까지 커서로 끝까지·마지막 커서로 다시 부르면 빈 페이지에 같은 커서·같은 변경 시각은 보류 위치로 나눔·다른 유형 섞이지 않음·최근 10초 안에 바뀐 보류 제외·잘못된 파라미터 400. shared Enum과 지갑 Enum 상수 일치, Client 경로·쿼리·본문 | BD-137 | U, I | `HoldQueryApiTest`, `BillingStatusContractTest`, shared `HoldQueryCommandTest`·`BillingApiClientTest` | 경계 (호출하는 강의·출연 대사는 각 모듈 하위 이슈) | 유효 |
| BT-PORT-08 | 내부 API 오류 코드 대조 (#273) | 내부 API에서 나오는 Billing 오류 코드는 같은 이름의 shared `BillingFailureCode`로 바뀐다(`HP_LOCK_TIMEOUT` 포함). `BillingFailureCode`는 `UNMAPPED`를 빼고 모두 어떤 Billing 오류 코드에서 바뀌어 나온다 | BD-113, BD-108 | U | `BillingFailureCodeContractTest#sameName`, `#everyFailureCodeIsReachable` | 없음 (shared Enum을 같은 classpath에서 대조) | 유효 |
| BT-EVT-01 | 다른 앱에서 온 회원 이벤트 | member-app의 가입·변경 이벤트가 Kafka `member.events.v1`로 billing-app에 전달되어 지갑·복제본이 생긴다. 소비 그룹 `billing-app.member-replica`(복제본)·`billing-app.wallet-creation`(지갑)이 따로 받는다. 같은 회원·같은 버전 재전달은 한 번만 반영(지갑 하나, 복제본 한 번), 순서가 바뀌어도 회원 버전 비교로 최신만 반영, 가입보다 변경이 먼저 와도 복제본 생성, 모르는 타입은 무시, 재시도 3회 초과는 그 그룹 DLT, 역직렬화 실패는 재시도 없이 두 그룹 DLT(`member.events.v1.billing-app.member-replica.DLT`·`member.events.v1.billing-app.wallet-creation.DLT`, partition 3·보존 7일), 한 그룹의 실패가 다른 그룹을 막지 않는다 | BD-2, BD-103, BD-123, BD-136, kafka.md | I | `BillingMemberEventKafkaTest` | 없음 | 유효 |
| BT-EVT-02 | 지갑 이벤트 발행 (#497) | 충전·지급(정상·위약금)·환불(전부·일부)·환전(완료·거절·시도별 송금 실패·최종 실패) 끝 상태 커밋과 함께 `billing.events.v1`에 정해진 key·헤더로 한 번 나간다. 실패·재요청에서는 나가지 않고, 롤백하면 안 나가며, 못 보낸 기록은 재발행한다 | BD-130 | U, I | `BillingEventKafkaTest`, `BillingExternalEventsTest`, `ChargeIntegrationTest#confirm_completes`·`#confirm_replay`·`#confirm_rejected`·`#recovery_batch`, `HoldPayoutIntegrationTest#payout_revenueOnly_full`·`#payout_idempotency`·`#penalty_10000`, `RefundIntegrationTest#refund_fullCharge`·`#refund_firstRejected`·`#refund_secondRejected`, `ExchangeReviewIntegrationTest#reject`, `ExchangePayoutIntegrationTest#success`·`#failedThenRetry`·`#failFinally`·`#zeroPayoutCompletesWithoutTransfer` | 없음 | 유효 |

### 4.5 충전·환불 (CHG·RFD)

| ID | 대상 | 검증 기준 | 근거 | 단계 | 테스트 | JVM | 명세 상태 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| BT-CHG-01 | 충전 주문 | 1,000 단위, 지갑 없으면 거절, 남의 주문·금액 불일치 거절 | BD-81 | I | `ChargeIntegrationTest#createOrder*`, `#walletNotFound`, `#confirm_otherMembersOrder`, `#confirm_amountMismatch` | 없음 | 유효 |
| BT-CHG-02 | 승인 완료 | COMPLETED + 충전 원장 1줄 + 가용 원금 = 충전액. FAILED는 완료되지 않음 | BD-76 | U, I | `PointChargeTest#chargeConfirmFlow`, `ChargeIntegrationTest#confirm_completes`, `#failedNeverCompleted` | 없음 | 유효 |
| BT-CHG-03 | 승인 멱등 | 같은 결제키 재생, 다른 결제키 충돌, 동시 승인 1회, 결제키 해시 UNIQUE 경합 | BD-79 | I | `ChargeIntegrationTest#confirm_replay`, `#confirm_paymentKeyConflict`, `#confirm_concurrent`, `#confirm_paymentKeyHashUniqueRace` | 없음 | 유효 |
| BT-CHG-04 | PG 결과 판정 | 거절 = FAILED, 401·409·429 등 = 불명확 유지, 5xx·타임아웃 = 조회, PARTIAL_CANCELED = 운영자 확인, 승인 결과 불일치 = `CHARGE_STATE_INCONSISTENT` | BD-77 | U, I | `TossPaymentsClientTest`, `ChargeIntegrationTest#confirm_rejected`, `#confirm_timeoutButApproved`, `#confirm_unknownThenRetried`, `#confirm_rateLimitOrAuthErrorKept`, `#confirm_approved*Mismatch`, `#recovery_partialCanceledKeptForOperator` | 없음 (외부 장애) | 유효 |
| BT-CHG-05 | 승인 후 복구 | 승인 후 DB 실패·불명확 건을 조회로 확정, 미확정 건 순환, 세션 만료 FAILED | BD-76, BD-78 | I | `ChargeIntegrationTest#confirm_dbFailureAfterApproval`, `#recovery_*` | 없음 | 유효 |
| BT-CHG-06 | 결제키 보호 | 무작위 IV 암호화, HMAC, 변조 감지, 응답·저장에 원문 없음, 설정 오류 시 기동 실패 | BD-80 | U, I | `PaymentKeyCipherTest`, `ChargeIntegrationTest#confirm_paymentKeyProtected`, `#recovery_unreadablePaymentKey` | 없음 | 유효 |
| BT-CHG-07 | 결제 설정 없음 | 앱은 뜨고 충전·환불·환전 API만 503 | BD-80 | I | `ApiV1WalletChargeUnavailableTest` | 없음 | 유효 |
| BT-CHG-08 | 충전 HP 사용 | 충전한 HP가 보류·반환에 쓰인다 | BD-24 | I | `ChargeIntegrationTest#chargedHpUsedByHoldAndRelease` | 없음 (#486부터 `HoldFacade`로 보류) | 유효 |
| BT-CHG-09 | 충전 API | 상태 코드(201·200·202), 요청 검증, 권한 | A10.1 | I | `ApiV1WalletChargeControllerTest` | 경계 (인증) | 유효 |
| BT-RFD-01 | 환불 접수 | 충전 HP만, FIFO로 여러 충전 건에 나눔, 금액 1 HP 이상·1 HP 단위(#453) | BD-87 (금액 규칙은 BD-119로 대체), BD-119 | I | `RefundIntegrationTest#refund_singleChargePartial`, `#refund_fullCharge`, `#refund_fifoAcrossCharges`, `#refund_chargeHpOnly`, `#refund_invalidAmount`, `#refund_anyAmountFromOne` | 없음 | 유효 |
| BT-RFD-02 | PG 취소 멱등키 | UUID를 한 번 만들어 저장, DB ID와 무관 | BD-88 | U, I | `BillingKeysTest#refundCancelKeyIsUnique`, `RefundIntegrationTest#refund_cancelKeyIsStoredUuid`, `#refund_cancelKeyIndependentOfDbIds` | 없음 | 유효 |
| BT-RFD-03 | PG 결과 분류 | 요청 오류 = 반환, 환급 불가 = 보류 유지·운영자 확인, 중복 취소 = 운영자 확인, 불명확 = 조회 후 확정 | BD-89 | I | `RefundIntegrationTest#refund_firstRejected`, `#refund_secondRejected`, `#refund_pgRefundNotPossibleKeepsClaim`, `#refund_duplicateCancelAtPg`, `#refund_unknown*`, `#refund_canceled*`, `#refund_alreadyCanceled*`, `#refund_lookupNotFound` | 없음 (외부 장애) | 유효 |
| BT-RFD-04 | UNKNOWN 한도 | 24시간 또는 12회 → 운영자 확인, 마지막 확인에서 성공하면 확정 차감 | BD-90 | U, I | `PointRefundTest#unknownLimit`, `RefundIntegrationTest#unknown_*` | 없음 | 유효 |
| BT-RFD-05 | 멱등키 안전 기간 | 최초 요청 + 14일 경계부터 재POST 금지, 이후 조회만 | BD-91 | U, I | `PointRefundTest#idempotencyWindow`, `RefundIntegrationTest#idempotencyWindow_*` | 없음 | 유효 |
| BT-RFD-06 | 취소·경합 | REQUESTED만 회원 취소, 처리와 취소 경합, 처리와 복구 경합, 동시 요청에 과환불 없음 | BD-92 | U, I | `PointRefundTest#cancel_onlyPending`, `RefundIntegrationTest#cancel_*`, `#processing_concurrentWithRecovery`, `#concurrentRequests_noOverRefund`, `#additionalRequestWhileUnknown` | 없음 | 유효 |
| BT-RFD-07 | 접수 멱등·복구 | 같은 키 재생, REQUESTED 재개, 실패 요청 순환 | BD-87 (접수·멱등 부분), BD-92 | I | `RefundIntegrationTest#refund_idempotentReplay`, `#recovery_*` | 없음 | 유효 |
| BT-RFD-08 | 환불 API | 200·202, 요청 검증, 취소, 권한 | A10.2 | I | `ApiV1WalletRefundControllerTest` | 경계 (인증) | 유효 |

### 4.6 환전·송금·Mock 은행 (EXC·EXP·MCK)

| ID | 대상 | 검증 기준 | 근거 | 단계 | 테스트 | JVM | 명세 상태 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| BT-EXC-01 | 환전 신청 | 수익 HP만, 금액 1 HP 이상·1 HP 단위와 송금액 버림(송금 = floor(신청 × 90%), #453), REVENUE_ONLY 보류, 계좌 암호화 스냅샷, 계좌 저장 실패 전체 롤백 | BD-94, BD-95 (수수료 계산은 BD-120으로 대체), BD-120 | U, I | `ExchangeTest#payoutAndFee`·`#tooSmallAmount`·`#anyAmountFromOne`, `ExchangeAccountCipherTest`, `ExchangeIntegrationTest#request`, `#accountSaveFailureRollsBackAll`, `#accountSnapshotEncrypted`, `#chargeHpNotExchangeable`, `#insufficientRevenue` | 없음 (#486부터 수익 HP를 `HoldFacade` 지급으로 만든다) | 유효 |
| BT-EXC-02 | 신청 멱등·동시성 | 같은 키 재생, 복호화 불가 재요청 거절, 동시 신청에 과보류 없음 | BD-95 | I | `ExchangeIntegrationTest#idempotentReplay`, `#concurrentSameKey`, `#concurrentRequestsDoNotOverHold`, `#replayWithUnreadableAccount` | 없음 | 유효 |
| BT-EXC-03 | 회원 취소 | REQUESTED만, 반환 1회, 상태 변경과 직렬화, 승인 뒤 거절 | BD-97, BD-98 | I | `ExchangeIntegrationTest#cancel`, `#concurrentCancel`, `#cancelWaitsForConcurrentStatusChange`, `#notCancelableAfterApproval` | 없음 | 유효 |
| BT-EXC-04 | 정산 회차 | 기준일·주말 보정·잠정 예정 시각·승인 시점 회차 | BD-96 | U | `ExchangeSettlementRoundTest` | 없음 | 유효 |
| BT-EXC-05 | 관리자 승인·거절 | 상태 전이, 멱등(같은 관리자·같은 사유), 승인·거절·취소 경합에 하나만 성공, 보류 정합성 | BD-102 | I | `ExchangeReviewIntegrationTest` | 없음 | 유효 |
| BT-EXC-06 | 신청자 복제본 | 복제본 없음·자격 없음이면 승인 409, 거절은 신청자 상태 무관 | BD-102, BD-103 | I | `ExchangeReviewIntegrationTest#approveWithoutReplica`, `#approveNotEligible`, `#rejectRegardlessOfApplicant` | 경계 (복제본이 이벤트·Backfill로 채워진다는 전제) | 유효 |
| BT-EXC-07 | 환전 API | 계좌번호 미노출·마스킹, 요청 검증, 권한 | A11 | I | `ApiV1SettlementExchangeControllerTest`, `ApiV1SettlementExchangeAdminControllerTest` | 경계 (인증) | 유효 |
| BT-EXP-01 | 송금 성공 | 확정 차감 1회, 완료 재실행 멱등, 예정 전·미승인 거절 | BD-101 | U, I | `ExchangePayoutTest`, `ExchangePayoutIntegrationTest#success`, `#completedIsIdempotent`, `#notDue`, `#notApproved` | 없음 | 유효 |
| BT-EXP-02 | 송금 실패·재시도·최종 실패 | FAILED는 보류 유지, 재시도는 새 요청 ID, 관리자 최종 실패(FINAL_FAILED) = 반환, 불명확한 상태에서는 최종 실패 금지 | BD-101 | I | `ExchangePayoutIntegrationTest#failedThenRetry`, `#failFinally*`, `#recheckRejectedThenRetry` | 없음 | 유효 |
| BT-EXP-03 | 결과 불명확·복구 | 타임아웃·5xx·처리 중·전송 전 중단을 같은 ID로 확인, 24시간 뒤 운영자 확인, 은행 성공 뒤 반영 실패 복구 | BD-101 | I | `ExchangePayoutIntegrationTest#timeoutThenRecover`, `#serverErrorThenResend`, `#bankProcessing`, `#crashBeforeSend`, `#applyFailsAfterBankSuccess`, `#autoCheckExpires`, `#recheck*` | 없음 (외부 장애) | 유효 |
| BT-EXP-04 | 송금 경합 | 동시 실행·복구·재확인·최종 실패와 재시도 경합 | BD-98, BD-101 | I | `ExchangePayoutIntegrationTest#concurrent*`, `#finalizeVersusRetry`, `#recheckVersusRecovery`, `#concurrentSameFinalize` | 없음 | 유효 |
| BT-EXP-05 | 정산 배치 처리 | 예정 지난 APPROVED만, 커서·상한, 실패 격리·같은 실행에서 반복 없음, Client 없으면 건너뜀, 늦은 승인은 다음 회차 | decisions 12장 | I | `ExchangeSettlementUseCaseTest`, `ExchangeSettlementIntegrationTest` | 없음 | 유효 |
| BT-EXP-06 | 배치 등록 조건 | 스케줄링·배치별 스위치 | — | U | `ExchangeSettlementBatchConditionTest` | 없음 | 유효 |
| BT-EXP-07 | 송금 관리자 API | 목록·재확인·재시도·최종 실패, 권한 | A11 | I | `ExchangePayoutIntegrationTest#adminApi`, `#adminApiAuthorization` | 경계 (인증) | 유효 |
| BT-EXP-08 | 수수료 수입 기록 (#336) | 송금 성공(송금액 0원은 완료) 때 확정 차감과 같은 트랜잭션에 `EXCHANGE_FEE` 1줄, 회원 차감 = 송금 + 수수료, 환전당 1회(재실행·수동 재송금), 최종 실패·결과 대기는 기록 없음, 플랫폼 지갑 캐시 = 원장 합 | BD-114, BD-115, BD-116 | U, I | `ExchangePayoutIntegrationTest#success`, `#completedIsIdempotent`, `#feeIncomeOncePerExchange`, `#zeroPayoutCompletesWithoutTransfer`(`assertConsistent`), `WalletTest#platform_feeIncome` | 없음 | 유효 |
| BT-EXP-09 | 플랫폼 지갑 접근 (#336) | 관리자 API 잔액·내역(최신순, 신청 = 송금 + 수수료), 관리자가 아니면 403·비로그인 401, 회원 ID로 조회되지 않음, 지갑 유형·FEE 보류 칸 가드 | BD-114, BD-117 | U, I | `ExchangePayoutIntegrationTest#platformWalletAdminApi`, `#platformWalletAdminApiAuthorization`, `#platformWalletNotReachableByMember`, `WalletTest#walletTypeGuards` | 경계 (인증) | 유효 |
| BT-EXP-10 | 송금액 0원 환전 (#453) | 신청 1 HP: 정산 예정 전 NOT_DUE, 지나면 은행 호출·송금 시도 없이 확정 차감 1 + 수수료 수입 1 + COMPLETED, 다시 실행해도 그대로 | BD-121 | U, I | `ExchangeTest#completeWithoutTransfer`, `ExchangePayoutIntegrationTest#zeroPayoutCompletesWithoutTransfer` | 없음 | 유효 |
| BT-MCK-01 | Mock 은행 HTTP | 같은 요청 재생·다른 내용 409, 시나리오, 트랜잭션 안 호출 금지, 접근 키, 계좌 미로그, 재시작 후 복구 | BD-99 | U, I | `MockPayoutTransferTest`, `MockPayoutHttpIntegrationTest` | 없음 (같은 앱 안의 HTTP) | 유효 |
| BT-MCK-02 | Mock 활성 조건 | 배포 프로필·기본 프로필에서는 빈·엔드포인트·HTTP 없음 | BD-100 | U, I | `MockPayoutActivationTest`, `MockPayoutDeployedProfileTest`(`@Nested` 프로필 5개), `MockPayoutDisabledTest` | 없음 | 유효 |

### 4.7 수익 조회·회원 복제본 (ERN·USR)

| ID | 대상 | 검증 기준 | 근거 | 단계 | 테스트 | JVM | 명세 상태 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| BT-ERN-01 | 수익 합산 | 상태별 합, 전역 정렬 병합, 페이지 = 전체 병합의 부분, 깊이 1,000 제한, 한쪽 실패 = 전체 실패 | internal-earnings.md | U | `EarningQueryUseCaseTest` | 없음 | 유효 |
| BT-ERN-02 | 출처 Client 변환·장애 분류 | 응답 변환(출연 KST), 연결 실패·5xx·해석 실패 = 503 UNAVAILABLE, 4xx = 503 REJECTED, Client 없음 = 503 | internal-earnings.md 4·7장 | U | `EarningSourceTest`(변환·장애 분류) | 경계 (실제 HTTP지만 응답은 Billing 쪽 가짜 서버) | 유효 |
| BT-ERN-03 | 수익 API | 역할, 본인 것만, 출처 장애, 깊이, 내부 키 헤더 송신 | internal-earnings.md | I | `ApiV1SettlementEarningControllerTest` | 경계 (인증·가짜 서버) | 유효 |
| BT-ERN-04 | 제공자 계약 일치 | lecture-app·appearance-app 실제 응답이 Billing Client 기대와 같다 | internal-earnings.md | T | 테스트 없음 | 경계 | 유효 |
| BT-USR-01 | 복제본 동기화 | 가입·변경 Upsert, 버전 비교, 버전 없는 정보 무시, 늦은 가입 이벤트가 덮어쓰지 않음, 가입보다 변경이 먼저 와도 생성, 동시 Upsert | BD-103 | I | `BillingUserSyncIntegrationTest` | 없음 (UseCase 직접 호출. Kafka 경로는 BT-EVT-01) | 유효 |
| BT-USR-02 | 그룹 격리·재시도 | 복제본 그룹 실패가 지갑 생성 그룹을 막지 않음(재시도 뒤 복제본 DLT만), 복제본 PK 충돌 재시도는 새 트랜잭션 | BD-103, BD-123 | I | `BillingMemberEventKafkaTest#replicaFailureDoesNotBlockWalletCreation`, `BillingMemberEventIsolationTest` | 없음 | 유효 |
| BT-USR-03 | Backfill | 페이지 순회·한도·재개 커서, 실패 분류, 계약 위반, 동시 실행 금지(배포 중 다른 pod 포함 409, 락 오류 503 `BACKFILL_LOCK_UNAVAILABLE`, #598), 이벤트와 경합, 관리자 API, 관리자가 아닌 회원 지갑 확인·생성(다시 실행해도 같음, 지갑 실패는 failed, #552) | BD-132, BD-138 | U, I | `BillingUserBackfillUseCaseTest`, `BillingUserBackfillIntegrationTest` | 경계 (MemberApiClient 실제 HTTP, 가짜 member 서버) | 유효 |
| BT-USR-04 | member 제공자 계약 일치 | member-app 내부 회원 API 응답이 Billing Client 기대와 같다 | `docs/api/internal-members.md` | T | 테스트 없음 | 경계 | 유효 |

### 4.8 보안·오류·DB·설정 (SEC·ERR·DB·CFG)

| ID | 대상 | 검증 기준 | 근거 | 단계 | 테스트 | JVM | 명세 상태 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| BT-SEC-01 | URL 인가 | 관리자 경로 두 겹, `@PreAuthorize` 역할, 공개 경로(헬스체크 등), 비로그인 401 | A14, policy.md 역할표, BD-131 | S | `SecurityConfigTest` | 경계 (사용자 헤더로 인증, #537) | 유효 |
| BT-SEC-02 | `@LoginUserId` | 로그인 사용자 ID 해석, 익명 처리 | BD-131 | U | `LoginUserIdArgumentResolverTest`, `SecurityConfigTest#사용자_헤더로_인증하면_LoginUserId에_회원_ID가_들어간다` | 경계 (principal 이름 = 헤더의 회원 ID, #537) | 유효 |
| BT-SEC-03 | 내부 API 키 | 맞는 키만 통과, 경로 prefix 보호, 사용자 헤더와 키 분리, 두 체인 모두 CSRF 없음(#537), 키 미노출, 키 없으면 기동 실패 | 루트 AGENTS 7장, BD-112 | U, S | `InternalApiKeyFilterTest`, `InternalApiPropertiesTest`, `InternalApiSecurityConfigTest` | 없음 | 유효 |
| BT-SEC-04 | 기능별 역할 | Facade `@PreAuthorize` 역할(관리자 지갑 403 등) | policy.md 역할표, BD-112 | I | `WalletFacadeTest`, 각 `ApiV1*ControllerTest#authorization`·`#roles` | 경계 (인증) | 유효 |
| BT-SEC-05 | 분리 환경 인증 | member-app이 발급한 JWT를 Envoy가 검증해 넘긴 `X-User-Id`·`X-User-Role` 헤더로 billing-app이 사용자·관리자를 인증하고 `@PreAuthorize`로 인가한다. 헤더 없는 요청은 비로그인(401), 외부에서 붙인 `X-User-*`는 앱에 닿지 않는다 | BD-131, #370~#372, 인프라 문서 3장 | S, T | 일부: `GatewayUserHeaderFilterTest`(헤더 인증·헤더 없음 401·한쪽만/형식 오류 익명 + 경고 로그·CSRF 없이 POST·세션·쿠키 없음). Envoy JWT 검증·외부 `X-User-*` 제거는 Gateway 테스트(`k8s/gateway/tests`) 몫 | 경계 (앱 쪽만 검증) | 유효 |
| BT-SEC-06 | HP 경로의 회원 상태 확인 | 토큰 역할과 함께 지갑 회원(복제본) 상태·역할을 확인한다. 충전 주문·승인 = 정상 + 유저 이상(관리자 제외), 환불 신청 = 정상·정지, 환전 신청 = 정상 + 인플루언서 이상. 복제본 없음 404 `WALLET_MEMBER_NOT_FOUND`, 부적격 403 `WALLET_MEMBER_NOT_ALLOWED`, 기록·HP 변화 없음. 결제창에 있는 동안 정지되면 승인 403·TossPayments 미호출. 조회는 확인하지 않음 | BD-131, policy.md 2장, 인프라 문서 3장 인증 흐름 7 | I | `ApiV1WalletChargeControllerTest#walletMember_*`, `ApiV1WalletRefundControllerTest#walletMember_*`, `ApiV1SettlementExchangeControllerTest#walletMember_*`, 환전 승인은 BT-EXC-06 | 경계 (회원 이벤트 반영 지연 동안은 404) | 유효 |
| BT-SEC-07 | Swagger 공개 경로 (#498) | 명세·UI는 로그인 없이 모듈 경로(`/api/v1/wallets/...`)에서 열리고 옛 루트 경로는 명세를 주지 않는다. 로컬 명세에는 사용자 헤더 인증만, 운영 명세에는 Bearer 인증만 있다(`X-User-*` 입력칸 없음). 운영 Swagger 목록에 6개 앱 명세가 있고 지갑이 기본이며 `persistAuthorization`을 켜지 않는다 | #498 (BD 없음): `docs/deploy/gateway.md` "Swagger 공개 결정", `docs/api/conventions.md` Swagger 주소(46-47줄), 루트 AGENTS 7장 | I | `BillingSwaggerPublicTest`(`@Nested` 기본·운영 프로필) | 없음 | 유효 |
| BT-SEC-08 | Boot 기본 사용자 미생성 (#733) | 실행 설정(local·prod 프로필의 `application.yaml`)으로 기동하면 `UserDetailsService`가 없고 생성 비밀번호를 로그에 남기지 않는다. 대조군: 설정 파일을 읽지 않으면 기본 사용자와 생성 비밀번호가 생긴다(테스트가 운영 조건을 재현하는지 확인) | #733 (BD 없음): `docs/deploy/security.md` "앱에 Boot 기본 사용자 없음", `apps/billing-app/src/main/resources/application.yaml` `spring.autoconfigure.exclude`(16줄) | U | `DefaultSecurityUserTest` | 없음 | 유효 |
| BT-ERR-01 | 오류 응답 | `ProblemDetail` + `code`, 검증 오류 항목, 403·405·500(내부 메시지 미노출) | conventions.md, BD-105, BD-106 | U, S | `GlobalExceptionHandlerTest`, `BusinessExceptionTest` | 없음 | 유효 |
| BT-ERR-02 | 페이지 응답 | 형식, 최대 100, 기본 20 | A14 | S | `PageConfigTest` | 없음 | 유효 |
| BT-DB-01 | 스키마 | Flyway 테이블(#479 `hold_void` 포함) + Hibernate validate, TIMESTAMPTZ·BIGINT, FK는 Billing 내부만, 다른 도메인 테이블 없음 | module.md 8.2 | R | `BillingSchemaTest` | 없음 (database 분리 시 다시 확인) | 유효 |
| BT-DB-02 | 제약 | CHECK·UNIQUE·FK·트리거·멱등키 | INV-2~INV-8 | R | `BillingConstraintsTest` | 없음 | 유효 |
| BT-DB-03 | Enum 저장값 | CHECK 허용값 = Enum 상수 이름 | BD-37 | R | `BillingEnumStorageTest` | 없음 | 유효 |
| BT-DB-04 | 엔티티 저장 | 전체 엔티티 저장·조회, Instant 보존, 감사 시각, ID 참조 FK | BD-36 | R | `BillingRepositoryTest` | 없음 | 유효 |
| BT-CFG-01 | 배치 스위치 | `hapbang.scheduling.enabled` 조건 | — | U | `ConditionalOnSchedulingEnabledTest` | 없음 | 유효 |
| BT-CFG-02 | 실제 설정 기동 | 앱 `application.yaml`·프로필(local·prod)과 필수 환경 변수로 기동, 누락 시 기대한 실패 | module.md 9장 | T | 테스트 없음 (`@IntegrationTest`는 앱 yaml을 읽지 않는다. 보안 자동 설정 범위만 BT-SEC-08) | 미확인 | 유효 |
| BT-CFG-03 | 복구 배치 실행 | `ChargeRecoveryBatch`·`RefundRecoveryBatch`가 등록되고 UseCase에 위임 | BD-78, BD-92 | U | 일부: 위임은 `BillingBatchJobLockTest`(락을 잡으면 UseCase 1회 호출, #598). 등록은 테스트 없음 (6.1) | 없음 | 유효 |
| BT-CFG-04 | Modulith 이벤트 기록 끔 (#471) | 폐기: #497에서 발행 기록을 다시 켰다 | BD-124 (대체됨 → BD-130) | — | 테스트 없음 (`ModulithEventsDisabledTest` 삭제) | 없음 | 대체됨 → BT-EVT-02 |
| BT-CFG-05 | 나가는 호출 회로 (#562) | 상대별 회로. 강의·출연·회원은 `UNAVAILABLE`만 실패·`REJECTED`는 성공, 수익 조회는 연결 실패만 1회 재시도(시간 초과·5xx는 안 함), 열리면 요청 없이 503. Toss는 `Unknown`만 실패·`Rejected`·`NotFound`는 성공, 열리면 `Unknown(CIRCUIT_OPEN)`, 충전 주문·승인·환불 신청 503(상태 변경 없음), 결과 확인 배치 건너뜀 후 닫히면 이어서 확정. 회로 상태 지표가 Prometheus에 나온다 | BD-134 | U, I | `EarningSourceTest`(회로·재시도), `TossPaymentsClientTest`(회로), `ApiV1WalletChargeControllerTest#circuitOpen`, `ApiV1WalletRefundControllerTest#circuitOpen`, `ChargeIntegrationTest#recovery_skippedWhileCircuitOpen`, `ManagementPortTest` | 경계 (가짜 서버·닫힌 포트, 회로 강제 상태) | 유효 |
| BT-CFG-06 | 배치·Backfill 실행 락 (#598) | 두 pod(서로 다른 락 연결) 중 한쪽만 잡고 풀면 다른 쪽이 잡음, 같은 pod 재진입은 메모리로 차단(PostgreSQL은 같은 session 재획득 허용), 작업 예외에도 해제, 락 연결이 끊기면 다른 pod가 잡고 끊긴 쪽은 예외 없이 끝낸 뒤 다시 연결, pod 종료 시 해제, 동시 시작 시 한쪽만 실행, 락 오류면 실행 안 함, 작업마다 락 번호 다름, 건너뛴 횟수 지표, 배치마다 다른 pod·락 오류에 UseCase를 부르지 않음 | BD-138 (락 번호 규칙은 BD-139로 대체) | U, I | `JobLockTest`, `JobLockPostgresIntegrationTest`, `BillingBatchJobLockTest`(배치별), `ManagementPortTest#지표와_헬스체크는_관리_포트에서_로그인_없이_응답한다`(관리 포트 노출) | 없음 (rolling update 중 두 pod 겹침을 연결 둘로 흉내) | 유효 |
| BT-CFG-07 | 회원별 요청 한도 (#627) | 한도까지 통과·넘으면 429 `TOO_MANY_REQUESTS` + `Retry-After`(1~60초), 넘은 요청은 주문을 만들지 않음, API·회원마다 따로, 종류별 설정값(신청·취소·일반 조회·수익 조회) 적용, 주기가 지나면 다시 통과, 쓰지 않은 카운터 정리(쓰는 중인 카운터는 정리와 경합해도 남음), 관리자 API 제외, 비로그인은 401 먼저, API별 거절 지표(관리 포트 노출 포함) | BD-140 | U, I | `UserRateLimiterTest`, `RateLimitIntegrationTest`, `ManagementPortTest` | 없음 (rolling update 중 두 pod의 한도 2배는 확인 안 함) | 유효 |
| BT-CFG-08 | 락 대기 상한 (#273) | Billing 커넥션의 `lock_timeout`은 3초이고, 지갑 락을 3초 안에 얻지 못하면 503 `HP_LOCK_TIMEOUT`이며 HP가 움직이지 않는다. 원인에 SQLState 55P03이 있으면 원인을 담은 `HP_LOCK_TIMEOUT`으로 바꾸고, 다른 SQLState(40P01·23505)와 업무 예외는 그대로 다시 던진다. 성공하면 결과를 그대로 돌려준다 | BD-108 | U, I | `LockTimeoutsTest#lockTimeout`, `#otherFailures`, `#success`, `ApiV1BillingInternalControllerTest#lockTimeout` | 없음 | 유효 |
| BT-CFG-09 | 내부 API 포트 분리 (#372) | 내부 API 포트는 `/api/v1/internal` 경로만 받고 그 밖의 경로·`/error`·`/api/v1/internal-other`는 404다. 일반 포트에서는 내부 경로(인코딩한 경로 포함)가 404다. 포트 0·65536과 일반 포트와 같은 번호는 거절한다 | #372 (BD 없음): `docs/deploy/gateway.md` "인증과 내부 포트", `docs/deploy/security.md` "내부 API 차단", 루트 AGENTS 7장 내부 API | U | `InternalApiPortConfigTest#separatesRealListenersAndNormalizedPaths`, `#rejectsInvalidOrSharedPort` | 없음 | 유효 |
| BT-CFG-10 | 분산 추적 이어받기 (#631, #630) | 샘플링된 `traceparent`로 들어온 요청은 같은 trace의 server span을 남기고, 샘플링되지 않은 요청은 span을 남기지 않는다. 관리 포트의 헬스체크·지표 요청은 span을 남기지 않는다. 내부 API 포트로 들어온 요청도 같은 trace의 server span을 남긴다. 회원 내부 API 호출은 지금 trace를 이어 `traceparent`를 보내고 client span을 남긴다. 회원 이벤트는 보낸 쪽 trace를 이어받아 두 소비 그룹이 각각 처리한다 | #631·#630 (BD 없음): `docs/architecture/infrastructure/observability.md` "앱 trace 계측 (#579, #631, #630)", `application.yaml` `management.tracing`(74-80줄) | I | `BillingTracingTest`, `BillingMemberEventKafkaTest#continuesSenderTrace` | 경계 (Envoy·member-app 대신 테스트가 `traceparent`를 붙인다) | 유효 |

## 5. JVM 분리 영향 정리

### 5.1 단일 JVM에서는 동작했지만 독립 JVM에서 동작하지 않을 수 있는 것

| 항목 | 단일 JVM에서의 동작 | 독립 JVM에서의 상태 (코드 기준) | 관련 BT | 관련 결정 |
| --- | --- | --- | --- | --- |
| ~~BillingPort 호출~~ | 강의·출연이 `BillingPort` 빈을 주입받아 Java로 호출 | 해소: 강의(#275~#277)·출연(#274)이 지갑 내부 HTTP API를 부르고, `BillingPort`·`BillingPortAdapter`는 #486에서 지웠다 | BT-PORT-01, 04, 05 | BD-128 (BD-51 일부 대체, BD-52 폐기) |
| ~~모듈 간 트랜잭션 경계~~ | 주문 저장 + 보류가 한 DB 트랜잭션, 실패하면 함께 롤백, 모듈 간 락 순서 | 해소: 내부 API 요청 하나 = 지갑 트랜잭션 하나이고, 강의·출연은 로컬 트랜잭션 밖에서 호출하고 부분 성공은 각 모듈 HP 작업으로 복구한다(BD-143, 강의 D62·D65). 모듈 간 락 순서는 두지 않는다(BD-142). 이 전제(같은 트랜잭션 참여·모듈 간 락 순서)를 검증하는 BT는 없다 | BT-PORT-02, 03 | BD-143, BD-142 (BD-53·BD-60 일부 대체, BD-64 대체, BD-72 폐기, BD-73 일부 대체, BD-107 폐기) |
| 회원 이벤트 수신 | #430부터 Kafka `member.events.v1`을 소비 그룹 둘로 받는다. 멱등은 자연 키(BD-123) | 발행 쪽 #416은 병합됐다(PR #465). 앱 시작 전 가입자의 지갑은 조회 시 보완, 복제본은 Backfill로 채운다. member-app 실제 발행과 이어 보는 계약 테스트는 없다(6.2) | BT-WAL-02, BT-USR-01·02, BT-EVT-01 | BD-2, BD-103, BD-123 (BD-6은 BD-123으로 대체) |
| ~~인증·인가~~ | member의 OAuth2 로그인 세션 principal을 같은 앱이 해석 | 해소(#537): Envoy가 넘긴 사용자 헤더로 인증한다(`GatewayUserHeaderFilter`, STATELESS, CSRF 없음). 충전·환불·환전 신청은 지갑 회원도 확인한다. Envoy JWT 검증과 외부 `X-User-*` 제거는 앱 테스트가 다루지 않는다 | BT-SEC-01·02·04·05·06 | BD-131 |
| 내부 HTTP API | 수익 조회·Backfill은 이미 HTTP | Client는 분리 구조에 맞다. 대신 base-url 환경 변수가 필수다(기본값 없음). 지갑도 내부 API를 제공한다: 보류·반환·지급·위약금(#273), 무효화(#479), 대사 조회(#577), 탈퇴 닫기·다시 열기(#623). 내부 포트로만 받는다(BT-CFG-09). 제공자·소비자를 잇는 계약 테스트는 없다 | BT-ERN-02·04, BT-USR-03·04, BT-SEC-03, BT-PORT-01~04·07, BT-WAL-05, BT-CFG-09 | BD-111, BD-112, BD-137, BD-141 |
| 멱등성·재시도 | 실패하면 호출자 트랜잭션과 함께 롤백 → 같은 요청 ID로 재요청 | Billing 내부 멱등(요청 ID 재생)은 그대로다. 호출 쪽 결과 불명 분류·재시도는 shared `BillingApiClientTest`(#411)가 본다. 두 앱을 잇는 테스트는 없다 | BT-PAY-03·04, BT-PORT-04 | BD-69, BD-70 |
| 외부 서비스 장애 | TossPayments·Mock 은행 장애는 이미 트랜잭션 밖 호출 + 복구 배치 | 변화 없음. 새로 생기는 장애 지점은 강의·출연·회원 앱 장애다(수익 조회 503은 검증 대상, Billing 다운 시 호출자 동작은 Billing 범위 밖) | BT-CHG-04·05, BT-RFD-03, BT-EXP-03, BT-ERN-02 | BD-76, BD-89, BD-101 |
| 배치·단일 실행 | 앱 1개 | rolling update(`maxSurge: 1`) 중 두 pod가 잠깐 겹친다. #598부터 배치·Backfill이 PostgreSQL advisory lock으로 한 곳만 실행한다(module.md 6.1). 한쪽이 끝낸 뒤 늦게 시작한 같은 회차는 막지 않는다(UseCase 락·멱등성) | BT-EXP-04·05, BT-USR-03, BT-CFG-06 | BD-138, BD-139 |
| 설정 | 공용 설정 | 앱별 yaml, 필수 환경 변수(내부 키·DB·Client URL). 실제 설정 기동 테스트 없음 | BT-CFG-02 | — |

### 5.2 영향별 개수

4장 표의 JVM 칸 첫 단어로 센다. 명세 상태가 "폐기"·"대체됨"인 행도 센다. 4장을 고치면 함께 고친다(마지막 갱신 2026-11-06).

| JVM | 개수 | ID |
| --- | --- | --- |
| 없음 | 100 | 아래 셋을 뺀 나머지 |
| 경계 | 22 | BT-WAL-03·04, BT-PORT-04·07, BT-CHG-09, BT-RFD-08, BT-EXC-06·07, BT-EXP-07·09, BT-ERN-02·03·04, BT-USR-03·04, BT-SEC-01·02·04·05·06, BT-CFG-05·10 |
| 깨짐 | 0 | — |
| 미확인 | 1 | BT-CFG-02 |
| 합계 | 123 | |

## 6. 빈 곳

### 6.1 테스트가 없거나 부족한 항목 (Billing 안)

| 항목 | 내용 | BT |
| --- | --- | --- |
| 판매자 지갑 없음 재지급 | 지갑 없음 거절·상태 불변은 `payout_invalidRequests`에 있다. 지갑 생성 뒤 같은 요청 ID 재지급은 없다(2026-10-29 코드 확인) | BT-PAY-07 |
| 중복 지급 오류 순서 | `payout_invalidRequests`의 `HOLD_ALREADY_CLOSED` 검사는 정산되지 않은 원천으로 해서, 끝난 보류와 원천 중복이 함께 걸리는 경우의 순서를 시험하지 않는다(2026-10-29 코드 확인) | BT-PAY-04 |
| 복구 배치 빈 | `ChargeRecoveryBatch`·`RefundRecoveryBatch` 등록 테스트 없음(`@IntegrationTest`에서 스케줄링이 꺼짐, 추정). 위임은 `BillingBatchJobLockTest`(#598) | BT-CFG-03 |
| 실제 설정 기동 | 앱 yaml·프로필로 기동하는 테스트 없음(보안 자동 설정만 BT-SEC-08) | BT-CFG-02 |
| 분리 환경 인증 | Envoy JWT 검증·외부 `X-User-*` 제거는 앱 테스트 밖(Gateway 테스트 몫) | BT-SEC-05 |
| 레거시 SettlementStatus | SCHEDULED 등 레거시 행이 있을 때 `PAYOUT_ALREADY_SETTLED`. 테스트가 없다. SCHEDULED 지급 건 픽스처(`BillingFixture.settlement`)는 `BillingConstraintsTest`·`BillingRepositoryTest`에서만 쓴다(2026-10-29 코드 확인). BD-71이 미결이라 BT를 만들지 않았다 | BD-71 |
| 이름으로 참조되지 않는 클래스 | `HoldCaptureUseCase`·`HoldPayoutUseCase`·`HoldReleaseUseCase`·`HoldProcessing`·`PenaltySettlementUseCase`·Facade·`ExchangeLocks`·`CaptureSplit`·`InternalHoldRequests`는 Facade·컨트롤러를 거쳐 간접 검증된다. 직접 테스트가 필요한지는 TBD | — |

### 6.2 Contract 단계 대상 (현재 테스트 없음)

| BT | 경계 | 제공자 | 소비자 | 선행 조건 |
| --- | --- | --- | --- | --- |
| BT-PORT-04 | 보류·반환·지급·위약금 | billing-app (#273, 서버 쪽은 `ApiV1BillingInternalControllerTest`) | lecture-app, appearance-app | 계약은 확정됐다(#273, #280에서 Java Port 삭제). 두 앱을 잇는 계약 테스트가 남는다 (Resilience4j 설정은 #411, `CONTEXT.md` 2장) |
| BT-EVT-01 | 회원 이벤트 (`member.events.v1`) | member-app (#416, PR #465) | billing-app (#430, 그룹 2개) | 발행 형식 #416(key 회원 ID, `__TypeId__` 헤더, `eventId` 없음). 소비 쪽은 `BillingMemberEventKafkaTest`가 같은 형식을 직접 보내 검증한다. 실제 발행과 이어 보는 계약 검증이 남는다 |
| BT-WAL-05 | 탈퇴 닫기·다시 열기 | billing-app (#623) | member-app | 서버 쪽은 `ApiV1BillingInternalControllerTest`. 지금 가능 (`docs/api/internal-withdrawals.md`) |
| BT-SEC-05 | 인증 | member-app(JWT 발급), Envoy(검증·헤더 전달) | 모든 앱(헤더 인증 필터) | #370~#372 |
| BT-ERN-04 | 수익 조회 | lecture-app, appearance-app | billing-app | 지금 가능 (`docs/api/internal-earnings.md`) |
| BT-USR-04 | 회원 목록 | member-app | billing-app | 지금 가능 (`docs/api/internal-members.md`) |
| BT-CFG-02 | 실제 설정 기동 | billing-app | — | 지금 가능 |

Contract 테스트 방식(도구, 계약 파일 위치, 제공자·소비자 어느 레포·모듈에서 돌릴지)은 TBD다. 루트 AGENTS 11장의 미정 항목(테스트 방식)이므로 팀이 정한다.

참고로 정해진 것은 두 가지다. 인프라 문서 1장은 Kafka 이벤트 payload를 바꿀 때 최소 한 버전의 하위 호환 consumer contract test를 두기로 했고, 7장은 schema registry 서버를 두지 않기로 했다. 같은 문서 3장 "CI 검증"은 PR에서 앱 사이 JWT 헤더 인증·Kafka 흐름을 검증하는 것을 목표로 한다. 구체적인 도구는 아직 없다.

## 7. 테스트 클래스 목록

### 7.1 클래스별

전체 메서드 이름은 다음 명령으로 뽑는다.

```bash
grep -rnE "@(Test|ParameterizedTest)" -A3 apps/billing-app/src/test/java | grep -E "void "
```

| 클래스 (`com.hapbang.billing.` 아래) | 종류 | 메서드 | Docker | BT |
| --- | --- | --- | --- | --- |
| app.BillingCommandValidatorTest | U | 6 | — | PEN-01·02 |
| app.BillingExternalEventsTest | U | 2 | — | EVT-02 |
| app.BillingKeysTest | U | 7 | — | HLD-06, RFD-02 |
| app.BillingBatchJobLockTest | U | 2 (배치별 반복) | — | CFG-03, CFG-06, LED-10 |
| app.BillingSharedDtoAccessTest | U (ArchUnit) | 1 | — | PORT-06 |
| app.BillingUserBackfillIntegrationTest | I | 14 | 필요 | USR-03 |
| app.BillingUserBackfillUseCaseTest | U (로컬 HTTP) | 17 | — | USR-03 |
| app.BillingUserSyncIntegrationTest | I | 6 | 필요 | USR-01 |
| app.ChangedWalletReconciliationIntegrationTest | I | 4 | 필요 | LED-10 |
| app.ChangedWalletReconciliationUseCaseTest | U | 7 | — | LED-10 |
| app.ChargeIntegrationTest | I | 32 | 필요 | CHG-01~06·08, EVT-02, CFG-05 |
| app.ChargeUsageHistoryTest | U | 11 | — | REL-01·04, CAP-03 |
| app.ClosedWalletBalanceMetricsTest | U | 2 | — | WAL-06 |
| app.EarningQueryUseCaseTest | U | 13 | — | ERN-01 |
| app.EarningSourceTest | U (로컬 HTTP) | 13 | — | ERN-02, CFG-05 |
| app.ExchangeAccountCipherTest | U | 4 | — | EXC-01, WAL-07 |
| app.ExchangeIntegrationTest | I | 21 | 필요 | EXC-01~03 |
| app.ExchangePayoutIntegrationTest | I (직접 `@SpringBootTest`) | 38 | 필요 | EXP-01~04·07~10, EVT-02 |
| app.ExchangeReviewIntegrationTest | I | 20 | 필요 | EXC-05·06, EVT-02 |
| app.ExchangeSettlementBatchConditionTest | U (ACR) | 3 | — | EXP-06 |
| app.ExchangeSettlementIntegrationTest | I (직접 `@SpringBootTest`) | 10 | 필요 | EXP-05 |
| app.ExchangeSettlementRoundTest | U | 7 | — | EXC-04 |
| app.ExchangeSettlementUseCaseTest | I | 6 | 필요 | EXP-05 |
| app.HoldCaptureIntegrationTest | I | 21 | 필요 | CAP-01~04 |
| app.HoldCreateIntegrationTest | I | 30 | 필요 | HLD-01~05, PORT-02 |
| app.HoldPayoutIntegrationTest | I | 39 | 필요 | PAY-01~07, PEN-01~04, PORT-02, EVT-02 |
| app.HoldReleaseIntegrationTest | I | 34 | 필요 | REL-01~05, PORT-02 |
| app.HoldVoidIntegrationTest | I | 8 | 필요 | REL-06 |
| app.HoldSplitTest | U | 6 | — | HLD-01 |
| app.LedgerOperationTest | U | 6 | — | LED-03 |
| app.LedgerRecordIntegrationTest | I | 27 | 필요 | LED-01·03·04·05·09 |
| app.LedgerRecordUseCaseTest | U | 2 | — | LED-04 |
| app.LedgerReversalIntegrationTest | I | 8 | 필요 | LED-06 |
| app.LockTimeoutsTest | U | 3 | — | CFG-08 |
| app.MockPayoutHttpIntegrationTest | I (직접 `@SpringBootTest`) | 19 | 필요 | MCK-01 |
| app.PaymentHoldRequestsTest | U | 3 | — | PORT-06 |
| app.PayoutSplitTest | U | 2 | — | PAY-01 |
| app.RefundIntegrationTest | I | 39 | 필요 | RFD-01~07, EVT-02 |
| app.ReleaseSplitTest | U | 6 | — | REL-01 |
| app.WalletCreateUseCaseTest | U | 12 | — | WAL-01 |
| app.WalletCreationIntegrationTest | I | 11 | 필요 | WAL-01·02 |
| app.WalletDuplicateViolationTest | I | 5 | 필요 | WAL-01 |
| app.WalletFacadeTest | I | 2 | 필요 | WAL-03, SEC-04 |
| app.WalletMaskingIntegrationTest | I | 11 | 필요 | WAL-06·07·08 |
| app.WalletMaskingStatusesTest | U | 1 | — | WAL-07 |
| app.WalletReconciliationIntegrationTest | I | 6 | 필요 | LED-08 |
| app.WalletTransactionQueryIntegrationTest | I | 12 | 필요 | WAL-04 |
| app.WalletWithdrawalIntegrationTest | I | 17 | 필요 | WAL-05·06 |
| domain.ExchangePayoutTest | U | 15 | — | EXP-01 |
| domain.ExchangeTest | U | 14 | — | EXC-01, EXP-10 |
| domain.MockPayoutTransferTest | U | 2 | — | MCK-01 |
| domain.PointChargeTest | U | 6 | — | CHG-02 |
| domain.PointHoldPayoutTest | U | 5 | — | PAY-01 |
| domain.PointHoldReleaseTest | U | 12 | — | REL-02 |
| domain.PointRefundTest | U | 5 | — | RFD-04~06 |
| domain.WalletApplyLedgerAccessTest | U (ArchUnit) | 1 | — | LED-02 |
| domain.WalletTest | U | 14 | — | LED-03, EXP-08·09, WAL-08 |
| global.auth.GatewayUserHeaderFilterTest | S | 4 | — | SEC-05 |
| global.auth.LoginUserIdArgumentResolverTest | U | 4 | — | SEC-02 |
| global.config.BillingEventKafkaTest | I (Kafka) | 4 | 필요 | EVT-02 |
| global.config.BillingTracingTest | I (직접 `@SpringBootTest`) | 5 | 필요 | CFG-10 |
| global.config.DefaultSecurityUserTest | U (ACR) | 2 | — | SEC-08 |
| global.config.ManagementPortTest | I (직접 `@SpringBootTest`) | 3 | 필요 | LED-10, CFG-05~07 (관리 포트 지표) |
| global.config.PageConfigTest | S | 3 | — | ERR-02 |
| global.config.SecurityConfigTest | S | 11 | — | SEC-01·02 |
| global.exception.BusinessExceptionTest | U | 6 | — | ERR-01 |
| global.exception.GlobalExceptionHandlerTest | S | 15 | — | ERR-01 |
| global.internal.InternalApiKeyFilterTest | U | 2 | — | SEC-03 |
| global.internal.InternalApiPortConfigTest | U (로컬 HTTP) | 2 | — | CFG-09 |
| global.internal.InternalApiPropertiesTest | U (ACR) | 4 | — | SEC-03 |
| global.internal.InternalApiSecurityConfigTest | S | 11 | — | SEC-03 |
| global.ratelimit.RateLimitIntegrationTest | I | 6 | 필요 | CFG-07 |
| global.ratelimit.UserRateLimiterTest | U | 8 | — | CFG-07 |
| global.scheduling.ConditionalOnSchedulingEnabledTest | U (ACR) | 3 | — | CFG-01 |
| global.scheduling.JobLockPostgresIntegrationTest | I | 6 | 필요 | CFG-06 |
| global.scheduling.JobLockTest | U | 10 | — | CFG-06 |
| in.ApiV1BillingInternalControllerTest | I | 19 | 필요 | PORT-01~03, REL-06, WAL-05, CFG-08 |
| in.ApiV1SettlementEarningControllerTest | I | 10 | 필요 | ERN-03 |
| in.ApiV1SettlementExchangeAdminControllerTest | I | 4 | 필요 | EXC-07 |
| in.ApiV1SettlementExchangeControllerTest | I | 8 | 필요 | EXC-07, SEC-06 |
| in.ApiV1WalletChargeControllerTest | I | 11 | 필요 | CHG-09, SEC-06, CFG-05 |
| in.ApiV1WalletChargeUnavailableTest | I | 3 | 필요 | CHG-07 |
| in.ApiV1WalletControllerTest | I | 8 | 필요 | WAL-03 |
| in.ApiV1WalletRefundControllerTest | I | 9 | 필요 | RFD-08, SEC-06, CFG-05 |
| in.ApiV1WalletTransactionControllerTest | I | 7 | 필요 | WAL-04 |
| in.BillingFailureCodeContractTest | U | 2 | — | PORT-08 |
| in.BillingMemberEventIsolationTest | I | 1 | 필요 | USR-02 |
| in.BillingMemberEventKafkaTest | I (Kafka) | 11 | 필요 | EVT-01, WAL-02, WAL-08, USR-02, CFG-10 |
| in.BillingStatusContractTest | U | 2 | — | PORT-07 |
| in.BillingSwaggerPublicTest | I (`@Nested`) | 6 | 필요 | SEC-07 |
| in.HoldQueryApiTest | I | 7 | 필요 | PORT-07 |
| in.MockPayoutDeployedProfileTest | I (`@Nested` 5개) | 2 | 필요 | MCK-02 |
| in.MockPayoutDisabledTest | I | 3 | 필요 | MCK-02 |
| out.BillingConstraintsTest | R | 33 | 필요 | DB-02, LED-07, PAY-02 |
| out.BillingEnumStorageTest | R | 1 | 필요 | DB-03 |
| out.BillingRepositoryTest | R | 7 | 필요 | DB-04 |
| out.BillingSchemaTest | R | 6 | 필요 | DB-01 |
| out.MockPayoutActivationTest | U | 3 | — | MCK-02 |
| out.PaymentKeyCipherTest | U | 6 | — | CHG-06 |
| out.TossPaymentsClientTest | U (로컬 HTTP) | 10 | — | CHG-04, CFG-05 |

### 7.2 종류별 합계 (이 문서의 테스트 수 원본)

기준: `dev` 40fa9b25, 2026-11-06. 테스트 수는 이 표에서만 관리하고, 다른 장은 이 표를 가리킨다.

| 종류 | 클래스 | 메서드 |
| --- | --- | --- |
| U (ACR·로컬 HTTP·ArchUnit 포함) | 46 | 274 |
| S (`@WebMvcTest`) | 5 | 44 |
| R (`@RepositoryTest`) | 4 | 47 |
| I (`@IntegrationTest`·직접 `@SpringBootTest`) | 45 | 573 |
| 합계 | 100 | 938 |

**세는 방법**

- 메서드 = 소스에서 줄 앞이 `@Test` 또는 `@ParameterizedTest`인 줄의 수다(`@RepeatedTest`는 0개). 파라미터 케이스·`@Nested` 프로필 반복으로 늘어나는 **실행 수**는 세지 않는다. 실행 수는 regression-log에 XML로 센다.
- 클래스 = 그런 줄이 하나라도 있는 `.java` 파일의 수다. `@Nested` 안쪽 클래스는 바깥 파일 하나로 센다. fixture·설정 클래스는 들어가지 않는다.
- 종류는 7.1 표의 "종류" 칸이다. 어노테이션으로 자동 판정하지 않는다.

```bash
cd apps/billing-app/src/test/java
# 클래스 수
grep -rlE '^\s*@(Test|ParameterizedTest)\b' --include='*.java' . | wc -l
# 메서드 수
grep -rhE '^\s*@(Test|ParameterizedTest)\b' --include='*.java' . | wc -l
# 클래스별 메서드 수 (7.1과 대조)
grep -rcE '^\s*@(Test|ParameterizedTest)\b' --include='*.java' . | grep -v ':0$' | sort
```

## 8. 운영 규칙

### 8.1 이 문서를 고칠 때

| 상황 | 할 일 |
| --- | --- |
| 테스트 클래스 추가·삭제·종류 변경, 메서드 수 변경 | 7.1 목록과 7.2 합계(7.2 명령으로 다시 센다), 1.3 명령, 관련 `BT-*` 행 |
| 새 검증 대상 | 영역의 다음 번호로 `BT-*`를 만든다. 근거(`BD-*`·`INV-*`·policy 장)를 적고, 근거 결정이 없으면 Issue 번호와 "BD 없음"을 적는다. 테스트가 없으면 "테스트 없음"으로 두고 6.1에 올린다 |
| 근거 결정이 대체·폐기됨 | 근거 칸에 대체한 결정을 적는다. 검증 기준이 더는 맞지 않으면 기준을 고치거나 명세 상태를 "폐기"·"대체됨 → BT-…"로 바꾼다 |
| 대상이 없어짐 | 행을 지우지 않고 명세 상태를 "폐기"로 바꾼다 |
| JVM 칸 변경 | 5.2 개수 |
| 회귀 실행 | 결과는 regression-log에만 쓴다. 이 문서는 고치지 않는다 |

### 8.2 별도 검토 사항 (이번에는 하지 않음)

- `BT-*` ID를 테스트 메서드 이름이나 `@DisplayName`에 넣을지는 미정이다. 지금은 이 문서에서만 연결하고 기존 테스트 이름은 바꾸지 않는다. 테스트 컨벤션은 루트 AGENTS 11장의 미정 항목이다.
- 1.4 A안(태그 분리)은 팀 합의 뒤 별도 Issue로 진행한다.

### 8.3 Skill과의 역할 분리

| 산출물 | 위치 | 이 문서와의 관계 |
| --- | --- | --- |
| `/grill-with-docs` | `docs/GLOSSARY.md`, `docs/adr/` | 미결 정책·설계를 질문으로 정리한다. 정해진 결과는 decisions.md(`BD-*`)나 ADR로 남는다. 이 문서에는 근거 ID만 적는다 |
| `/to-spec` | GitHub 이슈(`ready-for-agent`) | 확정된 내용을 구현 명세로 만든다. 스펙의 Testing Decisions에서 이 문서의 `BT-*`를 가리킨다. 스펙 내용은 복사하지 않고, 이 문서의 명세 상태 칸에 이슈 번호를 적지 않는다 |

### 8.4 변경 이력

이력의 클래스·메서드 수는 그때 값이다. 지금 값은 7.2에 있다.

- 2026-11-06 Billing 문서 정리: 상태 칸을 "명세 상태"(유효·폐기·대체됨)로 바꾸고, 칸에 있던 실행 결과(`R-003 통과`, `#607 통과` 등)는 regression-log 3.3(H 기록)·4.2로 옮겼다. BT 표의 메서드 수를 지우고 7.2 한 곳에서만 관리한다. 7장을 40fa9b25 코드와 맞췄다(#674 `WalletMaskingIntegrationTest`·`WalletMaskingStatusesTest`, #733 `DefaultSecurityUserTest` 추가, `WalletTest`·`ExchangeAccountCipherTest`·`BillingMemberEventKafkaTest` 메서드 수 갱신). BT 연결이 없던 기능에 BT-WAL-05~08, BT-PORT-08, BT-SEC-07·08, BT-CFG-08~10을 더했다. 폐기·대체된 결정(BD-6·52·64·72·107, BD-53·60·73 일부)을 근거에서 대체 결정으로 바꾸고, BT-USR-01의 JVM 칸(없앤 `Scenario.publish`)과 5.1·5.2를 고쳤다. S 명령을 클래스 목록으로 바꿨다(`global.config.*`가 Integration 테스트까지 돌렸다).
- 2026-11-04 #660 반영: 7장 목록을 지금 코드와 맞췄다(97개 클래스·918개). 빠져 있던 9개 클래스(#623 `WalletWithdrawalIntegrationTest`·`ClosedWalletBalanceMetricsTest`, #631 `BillingTracingTest`, `GatewayUserHeaderFilterTest`·`ManagementPortTest`·`BillingStatusContractTest`·`BillingSwaggerPublicTest`·`HoldQueryApiTest`)를 더하고 메서드 수가 달라진 10개를 고쳤다. `EarningSourceAdapterTest`는 검사 대상에 맞춰 `EarningSourceTest`로 이름을 바꿨다. 1.3 Unit 명령에 빠진 Unit 3개를 더했다. 직접 `@SpringBootTest` 5개에서 지갑에 없는 S3·OAuth 설정을 지웠다(검증 내용 변경 없음).
- 2026-11-03 #577 반영: 서비스 간 HP 대사 조회(BT-PORT-07)를 더했다.
- 2026-11-03 #627 반영: 회원별 요청 한도(BT-CFG-07)를 더했다. 공유 테스트 컨텍스트는 같은 회원 ID를 반복해 쓰므로 `hapbang-module-test.yaml`에서 한도를 크게 두고, 한도 동작은 작은 값의 `UserRateLimiter`와 따로 띄운 컨텍스트(`RateLimitIntegrationTest`)로 확인한다.
- 2026-11-03 #607 반영: 변경된 지갑의 잔액 대사 배치(BT-LED-10)를 더했다. `BillingBatchJobLockTest`가 배치 6개를 본다.
- 2026-11-03 #598 반영: 배치·Backfill 실행 락(BT-CFG-06, `JobLockTest`·`JobLockPostgresIntegrationTest`·`BillingBatchJobLockTest`)을 더했다. 배치 5개의 UseCase 위임은 `BillingBatchJobLockTest`가 보므로 BT-CFG-03은 등록만 남았다. BT-USR-03에 pod 사이 동시 실행·락 오류를 더했다.
- 2026-11-02 #562 반영: 나가는 호출 회로(BT-CFG-05)를 더했다. 공유 테스트 컨텍스트는 실패율로 회로가 열리지 않게 `hapbang.billing.outbound.minimum-number-of-calls`를 크게 두고(`hapbang-module-test.yaml`), 열고 닫히는 동작은 작은 설정으로 직접 만든 `OutboundCircuitBreakers`로, 통합 테스트는 상태를 강제로 바꿔 확인한다.
- 2026-11-02 #552 반영: 관리 포트 분리(BT-CFG, `ManagementPortTest`: 지표·헬스체크는 8082에서만, 8080·8081은 404, 관리 포트는 업무 API 없음)와 Backfill 지갑 생성(BT-USR-03)을 더했다.
- 2026-11-02 #537 반영: 테스트 로그인을 `TestLogin`(OAuth2 흉내)에서 `GatewayLogin`(사용자 헤더)으로 바꿔 API 테스트가 실제 헤더 필터를 거친다. CSRF 토큰(`csrf()`)과 OAuth2 등록 속성을 뺐다. BT-SEC-05(`GatewayUserHeaderFilterTest`)·BT-SEC-06(충전·환불·환전 컨트롤러 테스트의 `walletMember_*`)을 앱 쪽 검증으로 채웠다.
- 2026-11-02 #497 반영: 지갑 이벤트 발행(BT-EVT-02, `BillingEventKafkaTest`·`BillingExternalEventsTest`, 각 흐름 통합 테스트의 발행 기록 검증)을 더했고, BT-CFG-04(`ModulithEventsDisabledTest`)는 폐기했다. 7장은 82개 클래스·801개다.
- 2026-11-02 #280 반영: 소비 앱이 Java Port 없이 단독 기동하므로 BT-PORT-05를 k3s 기동 확인으로 바꾸고(JVM 깨짐 → 없음) Contract 대상에서 뺐다. BT-PORT-04는 소비 앱 테스트를 적고 JVM을 "경계"로 바꿨다.
- 2026-11-01 #494 반영: 내부 Command 경계(BT-PORT-06, `BillingSharedDtoAccessTest`·`PaymentHoldRequestsTest`)를 더했다. 기존 테스트는 shared Command 대신 `Payment*Command`를 쓴다. 7장은 81개 클래스·796개다.
- 2026-10-31 #486 반영: `BillingPortIntegrationTest`를 지우고 BT-PORT-01~03을 내부 API 테스트로 옮겼다(JVM 깨짐 → 없음). 다른 모듈 트랜잭션 참여 검증은 HTTP에 없는 동작이라 요청 단위 롤백으로 대신했다. 충전·환전·환불 테스트의 준비는 `HoldFacade`로 바꿨다(BT-CHG-08·BT-EXC-01 경계 → 없음). 7장은 79개 클래스·792개다.
- 2026-10-30 #479 반영: 보류 무효화(BT-REL-06)를 더했다. 7장 목록은 80개 클래스·801개다.
- 2026-10-30 #471 반영: Modulith 이벤트 기록 자동 설정을 뺀 것을 BT-CFG-04(`ModulithEventsDisabledTest`)로 확인한다.
- 2026-10-30 #430 반영: 회원 이벤트 리스너 두 개가 Kafka 소비로 바뀌었다. BT-EVT-01은 `BillingMemberEventKafkaTest`(Kafka Testcontainers)로 검증하고 JVM 칸을 "없음"으로, BT-WAL-02·BT-USR-02는 Kafka 경로 테스트로 옮겨 "없음"으로 바꿨다. 같은 JVM `Scenario.publish` 테스트는 없앴다(리스너 직접 호출 또는 Kafka 테스트로 대체).
- 2026-10-30 #428 반영: BT-EVT-01을 Kafka 소비 규칙(#339)에 맞춰 `eventId` inbox에서 자연 키 멱등·소비 그룹 둘·그룹별 DLT로 고쳤다.
- 2026-10-30 #336·#453 반영: 플랫폼 지갑·수수료 수입(BT-EXP-08·09), 송금액 0원 환전(BT-EXP-10), 1 HP 단위 환불·환전(BT-RFD-01·BT-EXC-01)을 더했다. 7장 목록은 그때 코드 기준 77개 클래스·786개다. #273·#372에서 빠졌던 4개 클래스(`LockTimeoutsTest`·`BillingFailureCodeContractTest`·`InternalApiPortConfigTest`·`ApiV1BillingInternalControllerTest`)도 넣었다.
- 2026-10-29 dev da834b1 반영: billing-app 테스트 코드 변경은 없다. `common/test-support`에 Kafka·Redis Testcontainers 설정이 추가됐다(3장). #369 최종 인프라 결정을 Contract 대상(6.2)과 BT-PORT-04·BT-EVT-01·BT-SEC-05에 반영했고, BT-SEC-06을 더했다.
- 2026-10-29 첫 작성: 73개 클래스, `@Test`·`@ParameterizedTest` 756개 기준.
