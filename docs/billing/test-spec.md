# 지갑(Billing) 모듈 — 테스트 검증 기준

> 문서 지도: [현재 구현](module.md) · [결정 기록](decisions.md) · **[테스트 검증 기준](test-spec.md)** · [회귀 실행 기록](regression-log.md)
>
> - 이 문서는 "무엇을 어떤 기준으로 검증하는가"와 검증 대상 ID(`BT-*`)를 실제 테스트에 연결한다. 실행 결과는 [regression-log.md](regression-log.md)에만 적는다.
> - 기준: 2026-10-29 작업 트리, `apps/billing-app/src/test/java/com/hapbang/billing` 73개 클래스, `@Test`·`@ParameterizedTest` 어노테이션 756개(`@RepeatedTest` 0). 파라미터 케이스와 `@Nested`로 실제 실행 수는 더 많다.
> - JVM 분리 이후 **U·S·I·R 단계를 HEAD 7dc89e0에서 모두 실행해 통과했다(R-003).** Contract(T)는 테스트가 없어 실행하지 않았다. 통과는 billing-app **한 앱** 안의 동작이다. JVM 칸이 "경계"·"깨짐"인 항목은 통과해도 분리 운영을 보장하지 않는다(4.1). 항목별 상태는 4장 "상태" 칸, 실행 내용은 [regression-log.md](regression-log.md)에 있다.
> - 2026-10-29 dev da834b1 반영: billing-app 테스트 코드 변경은 없다. `common/test-support`에 Kafka·Redis Testcontainers 설정이 추가됐다(3장). #369 최종 인프라 결정을 Contract 대상(6.2)과 BT-PORT-04·BT-EVT-01·BT-SEC-05에 반영했고, BT-SEC-06을 더했다.
> - 2026-10-30 #428 반영: BT-EVT-01을 Kafka 소비 규칙(#339)에 맞춰 `eventId` inbox에서 자연 키 멱등·소비 그룹 둘·그룹별 DLT로 고쳤다.
> - 2026-10-30 #430 반영: 회원 이벤트 리스너 두 개가 Kafka 소비로 바뀌었다. BT-EVT-01은 `BillingMemberEventKafkaTest`(Kafka Testcontainers)로 검증하고 JVM 칸을 "없음"으로, BT-WAL-02·BT-USR-02는 Kafka 경로 테스트로 옮겨 "없음"으로 바꿨다. 같은 JVM `Scenario.publish` 테스트는 없앴다(리스너 직접 호출 또는 Kafka 테스트로 대체).
> - 2026-10-31 #486 반영: `BillingPortIntegrationTest`를 지우고 BT-PORT-01~03을 내부 API 테스트로 옮겼다(JVM 깨짐 → 없음). 다른 모듈 트랜잭션 참여 검증은 HTTP에 없는 동작이라 요청 단위 롤백으로 대신했다. 충전·환전·환불 테스트의 준비는 `HoldFacade`로 바꿨다(BT-CHG-08·BT-EXC-01 경계 → 없음). 7장은 79개 클래스·792개다.
> - 2026-10-30 #479 반영: 보류 무효화(BT-REL-06)를 더했다. 7장 목록은 80개 클래스·801개다. 회귀 실행 기록은 아직 없다.
> - 2026-10-30 #471 반영: Modulith 이벤트 기록 자동 설정을 뺀 것을 BT-CFG-04(`ModulithEventsDisabledTest`)로 확인한다.
> - 2026-10-30 #336·#453 반영: 플랫폼 지갑·수수료 수입(BT-EXP-08·09), 송금액 0원 환전(BT-EXP-10), 1 HP 단위 환불·환전(BT-RFD-01·BT-EXC-01)을 더했다. 7장 목록은 지금 코드 기준 77개 클래스·786개다. #273·#372에서 빠졌던 4개 클래스(`LockTimeoutsTest`·`BillingFailureCodeContractTest`·`InternalApiPortConfigTest`·`ApiV1BillingInternalControllerTest`)도 넣었다. 새 항목의 회귀 실행 기록은 아직 없다.

## 1. 실행 방법

### 1.1 공통

- Java 25(Gradle Toolchain). Integration·Repository 테스트는 Docker가 필요하다(Testcontainers `postgres:18`, JVM당 컨테이너 1개, reuse 없음).
- 결과는 `apps/billing-app/build/test-results/test/TEST-*.xml`로 확인한다. `UP-TO-DATE`·`FROM-CACHE`로 건너뛴 태스크는 새 실행으로 기록하지 않는다(`docs/dev/testing.md`). 새로 실행하려면 `--rerun-tasks` 또는 `cleanTest`를 쓴다.
- CI는 `./gradlew verifyModuleStructure --no-daemon`을 먼저 돌리고, 이어서 `./gradlew check bootJar --no-daemon --parallel`을 돌린다(`.github/workflows/ci.yml`, `TZ=Asia/Seoul`).

### 1.2 단계별 명령

| 단계 | 명령 | Docker | 비고 |
| --- | --- | --- | --- |
| C. Compile | `./gradlew :billing-app:compileJava :billing-app:compileTestJava` | 불필요 | 모듈 구조 검사는 `./gradlew verifyModuleStructure`(따로 실행, `--parallel` 금지) |
| U. Unit | 1.3의 `--tests` 목록 | 불필요 | 순수 Unit 35개 클래스 |
| S. Slice (선택) | `./gradlew :billing-app:test --tests 'com.hapbang.billing.global.config.*' --tests 'com.hapbang.billing.global.exception.GlobalExceptionHandlerTest' --tests 'com.hapbang.billing.global.internal.InternalApiSecurityConfigTest'` | 불필요 | `@WebMvcTest` 4개 클래스, 보안·오류 응답 |
| I. Integration | `./gradlew :billing-app:test` | **필요** | 전체 실행(Unit 포함). Integration만 따로 고르는 Gradle 설정은 없다 |
| T. Contract | 없음 | — | 6.2 |

### 1.3 Unit만 실행하기 (현재 Gradle 설정 그대로)

**Unit만 실행하는 Gradle 설정은 없다.** 루트 `build.gradle.kts`의 테스트 설정은 `tasks.withType<Test> { useJUnitPlatform() }` 하나다. 태그(`@Tag` 사용 0건), 전용 태스크, 소스셋 분리, 테스트 resources가 모두 없다. 클래스 이름 접미사도 종류와 맞지 않는다(`WalletFacadeTest`·`ExchangeSettlementUseCaseTest`·`ApiV1*ControllerTest`는 Integration). 그래서 지금은 클래스 목록을 명시해야 한다.

```bash
./gradlew :billing-app:test --rerun-tasks \
  --tests 'com.hapbang.billing.app.BillingCommandValidatorTest' \
  --tests 'com.hapbang.billing.app.BillingKeysTest' \
  --tests 'com.hapbang.billing.app.BillingUserBackfillUseCaseTest' \
  --tests 'com.hapbang.billing.app.ChargeUsageHistoryTest' \
  --tests 'com.hapbang.billing.app.EarningQueryUseCaseTest' \
  --tests 'com.hapbang.billing.app.EarningSourceAdapterTest' \
  --tests 'com.hapbang.billing.app.ExchangeAccountCipherTest' \
  --tests 'com.hapbang.billing.app.ExchangeSettlementBatchConditionTest' \
  --tests 'com.hapbang.billing.app.ExchangeSettlementRoundTest' \
  --tests 'com.hapbang.billing.app.HoldSplitTest' \
  --tests 'com.hapbang.billing.app.LedgerOperationTest' \
  --tests 'com.hapbang.billing.app.LedgerRecordUseCaseTest' \
  --tests 'com.hapbang.billing.app.LockTimeoutsTest' \
  --tests 'com.hapbang.billing.app.PayoutSplitTest' \
  --tests 'com.hapbang.billing.app.ReleaseSplitTest' \
  --tests 'com.hapbang.billing.app.WalletCreateUseCaseTest' \
  --tests 'com.hapbang.billing.domain.*' \
  --tests 'com.hapbang.billing.global.auth.LoginUserIdArgumentResolverTest' \
  --tests 'com.hapbang.billing.global.exception.BusinessExceptionTest' \
  --tests 'com.hapbang.billing.global.internal.InternalApiKeyFilterTest' \
  --tests 'com.hapbang.billing.global.internal.InternalApiPortConfigTest' \
  --tests 'com.hapbang.billing.global.internal.InternalApiPropertiesTest' \
  --tests 'com.hapbang.billing.global.scheduling.ConditionalOnSchedulingEnabledTest' \
  --tests 'com.hapbang.billing.in.BillingFailureCodeContractTest' \
  --tests 'com.hapbang.billing.out.MockPayoutActivationTest' \
  --tests 'com.hapbang.billing.out.PaymentKeyCipherTest' \
  --tests 'com.hapbang.billing.out.TossPaymentsClientTest'
```

- `com.hapbang.billing.domain.*`는 9개 클래스이고 모두 Unit이다(`WalletApplyLedgerAccessTest`는 ArchUnit).
- `BillingUserBackfillUseCaseTest`·`EarningSourceAdapterTest`·`TossPaymentsClientTest`는 테스트 안에서 JDK `HttpServer`로 로컬 가짜 서버를 띄운다. `InternalApiPortConfigTest`는 내장 Tomcat 리스너를 직접 띄운다. Docker는 필요 없다.
- 테스트 클래스를 더하거나 종류를 바꾸면 이 목록과 7장을 함께 고친다.

### 1.4 Unit 분리 방법 제안 (Gradle은 아직 바꾸지 않는다)

| 안 | 방법 | 장점 | 단점·영향 | 판단 |
| --- | --- | --- | --- | --- |
| A | `common/test-support`의 `@IntegrationTest`·`@RepositoryTest` 메타 어노테이션에 `@Tag("integration")`을 더한다. 직접 `@SpringBootTest`를 쓰는 3개 클래스(`ExchangePayoutIntegrationTest`, `ExchangeSettlementIntegrationTest`, `MockPayoutHttpIntegrationTest`)에도 태그를 붙인다. 루트 `test` 태스크는 `excludeTags("integration")`, 새 `integrationTest` 태스크는 `includeTags`. `check`가 둘 다 실행 | 37개 중 34개가 메타 어노테이션 한 곳으로 분류된다. 이름 변경 없음 | `test-support`·루트 Gradle 변경 → **모든 앱에 영향**, CI 명령·시간 변화. 테스트 방식은 미정 항목(루트 AGENTS 11장) | 권장. 팀 합의 필요 |
| B | 1.3처럼 `--tests` 목록을 쓰거나, Gradle `test` 태스크 `filter`에 목록을 넣는다 | 코드 변경 없음 | 목록을 손으로 관리해야 한다 | 지금 단계에서 쓴다 |
| C | 클래스 이름 접미사(`*IntegrationTest`)로 나눈다 | 단순 | 기존 클래스 이름을 바꿔야 한다(이번 범위에서 금지) | 비권장 |
| D | `src/integrationTest` 소스셋 분리 | 경계가 가장 명확 | 파일 이동이 크고 모든 앱 규칙이 바뀐다 | 비권장 |

## 2. 단계 정의

| 단계 | 정의 | 이 모듈의 대상 | 성공 기준 |
| --- | --- | --- | --- |
| C. Compile | main·test 컴파일, 앱 간 의존 금지 | `:billing-app` 전체 | 컴파일 오류 0, `verifyModuleStructure` 통과 |
| U. Unit | Spring 컨텍스트·DB 없음. `ApplicationContextRunner` 경량 컨텍스트와 로컬 `HttpServer`는 허용 | 35개 클래스, 메서드 217개 | 실패 0. 건너뜀이 있으면 사유를 기록. 실행 목록이 7장 표와 같다(2.2) |
| S. Slice | `@WebMvcTest` (DB 없음) | 4개 클래스, 41개 | 실패 0 |
| I. Integration | `@IntegrationTest`·`@RepositoryTest`·직접 `@SpringBootTest` + PostgreSQL Testcontainers. billing-app **한 앱** 컨텍스트 | Integration 33개(462개) + Repository 4개(47개) | 실패 0. 같은 JVM 경계를 쓰는 테스트는 결과를 "경계"로 따로 표시 |
| T. Contract | 독립 프로세스 사이 계약(내부 HTTP, 이벤트, 인증)을 제공자·소비자 양쪽에서 같은 정의로 검증 | 없음 | 6.2의 대상이 생기면 정한다 (TBD) |

### 2.1 Integration 테스트가 검증하지 않는 것

- `@IntegrationTest`는 `spring.config.name=hapbang-module-test`로 **앱의 `application.yaml`을 읽지 않는다.** 내부 API 키는 `test-internal-api-key`이고, 배치는 `hapbang.scheduling.enabled`가 없어 꺼진다(추정). 실제 설정으로 기동하는지는 검증하지 않는다(BT-CFG-02).
- `ModuleTestConfiguration`은 `@ComponentScan("com.hapbang")`라 billing 테스트 클래스패스의 `shared` 빈도 함께 뜬다. 다른 앱의 빈은 클래스패스에 없다.
- 로그인은 `TestLogin.as`(`oauth2Login()` 후처리기)로 흉내 낸다. 분리된 앱 사이의 실제 인증은 검증하지 않는다(BT-SEC-05).
- 회원 이벤트는 `BillingMemberEventKafkaTest`가 Kafka Testcontainers의 `member.events.v1`에 member-app과 같은 형식(shared 레코드 JSON, `__TypeId__` 헤더, key 회원 ID)으로 보내 두 소비 그룹의 처리를 검증한다. 리스너 로직은 직접 호출로도 본다(`WalletCreationIntegrationTest`). 실제 member-app 발행(#416)과 이어 보는 검증은 없다(BT-EVT-01, 6.2).

### 2.2 Unit 실행 목록 대조

Unit은 "실패 0"만으로 통과로 보지 않는다. 테스트가 빠지거나 실행되지 않은 채 통과하는 것을 막기 위해, 실행 결과(`TEST-*.xml`)를 7장 표와 대조한다(2026-10-29 결정). 분리 이전 테스트 수는 기준선으로 쓰지 않는다는 결정([regression-log.md](regression-log.md) 5장)은 그대로다.

1. 7장 표에서 종류가 U인 클래스마다 `TEST-com.hapbang.billing.<클래스>.xml`이 있다. 없으면 실행되지 않은 것이다.
2. 소스의 `@Test`·`@ParameterizedTest` 메서드 수가 7장 표의 메서드 수와 같다. 다르면 표를 먼저 고친다(8.1).
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
| `TestLogin.as(userId, role)` | 같은 곳 | `oauth2Login()` 후처리기 |
| `WebSliceTestConfiguration` | 같은 곳 | `@WebMvcTest` 진입점 |
| `KafkaTestcontainersConfig`, `RedisTestcontainersConfig` (#380) | 같은 곳 | `@Import`로 가져온 테스트에서만 켜지는 Kafka(`apache/kafka:4.2.1` 단일 노드 KRaft)·Redis(`redis:8`). JVM당 종류별 1개, 동적 포트를 `spring.kafka.bootstrap-servers`·`spring.data.redis.*`에 주입. `@IntegrationTest` 기본값은 그대로 PostgreSQL만 띄운다. **billing 테스트는 아직 쓰지 않는다**(사용 0건). 쓰는 규칙은 `docs/dev/testing.md` "Kafka·Redis Testcontainers" |
| `BillingFixture` | `fixture/` | 엔티티 빌더 |
| `ChargeFixture` | `fixture/` | 테스트 전용 암호화·HMAC 키, Toss URL·타임아웃 등록 |
| `FakeTossPaymentsServer` | `fixture/` | JDK `HttpServer` 가짜 TossPayments(승인 멱등키, 조회 404, 취소, 장애 재현) |

| 대역 종류 | 쓰는 곳 | 대신하는 대상 |
| --- | --- | --- |
| JDK `HttpServer` 가짜 서버 | `EarningSourceAdapterTest`, `ApiV1SettlementEarningControllerTest`, `BillingUserBackfill*Test` | lecture·appearance·member 앱의 내부 API (**제공자 코드는 검증하지 않는다**) |
| `FakeTossPaymentsServer` | 충전·환불·`TossPaymentsClientTest` | TossPayments |
| Mock 은행 시나리오(`MockPayoutScenarios`) | 송금·정산·Mock HTTP 테스트 | 은행(운영 코드의 Mock 은행 자체를 실제 HTTP로 호출) |
| `@MockitoSpyBean` | `HoldCreateIntegrationTest`(Repository 장애 주입), 송금·정산(Clock·RequestIds·Client·`InternalHoldFacade`), `RefundIntegrationTest`(`RefundClock`), `BillingMemberEventIsolationTest`, `BillingMemberEventKafkaTest`(복제본 반영 실패 주입) | 장애·시각 주입 |
| Kafka Testcontainers (`KafkaTestcontainersConfig`) | `BillingMemberEventKafkaTest` | member-app의 이벤트 발행(테스트가 shared 레코드를 JSON + `__TypeId__`로 직접 보낸다) |
| 가짜 `EarningSource` | `EarningQueryUseCaseTest` | 강의·출연 수익 출처 |
| Mockito `mock` | `LedgerRecordUseCaseTest`, `WalletCreateUseCaseTest`, `BillingUserBackfillUseCaseTest`, `ExchangeSettlementBatchConditionTest` | Repository·UseCase |

## 4. 검증 대상 (`BT-*`)

### 4.1 표 읽는 법

- **ID**: `BT-<영역>-<번호>`. 한 번 붙인 ID는 바꾸거나 재사용하지 않는다. 대상이 없어지면 행을 지우지 않고 "폐기"로 표시한다.
- **근거**: [decisions.md](decisions.md)의 `BD-*`, [module.md](module.md) 4장 불변식 `INV-*`, `docs/policy.md` 장 번호.
- **단계**: U / S / I / R(Repository, I 단계에서 실행) / T.
- **테스트**: `클래스#메서드`. 메서드가 많으면 대표만 적고 `…`로 줄인다. 전체 메서드는 7장 명령으로 뽑는다.
- **JVM**: JVM 분리 영향.

| 값 | 뜻 |
| --- | --- |
| 없음 | Billing 앱 안에서 닫힌다. 분리 전후 의미가 같다 |
| 경계 | 테스트는 같은 JVM 대역(Scenario, TestLogin, 가짜 서버)으로 경계를 흉내 낸다. 통과해도 분리 환경의 동작은 보장하지 않는다 |
| 깨짐 | 검증하는 계약 자체가 독립 JVM에서 성립하지 않는다 |
| 미확인 | 코드로 영향을 판단하지 못했다 |

- **상태**: 최근 실행 결과. 지금은 모두 미실행이다. 실행 후 [regression-log.md](regression-log.md)의 실행 ID(`R-NNN`)로 바꾼다. 테스트가 없으면 "없음"이다.

### 4.2 지갑·원장 (WAL·LED)

| ID | 대상 | 검증 기준 | 근거 | 단계 | 테스트 | JVM | 상태 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| BT-WAL-01 | 지갑 중복 생성 | 회원당 1개. UNIQUE 위반(23505 + 제약 이름)일 때만 기존 지갑을 돌려준다. 언어 설정과 무관 | BD-1, BD-4, BD-5 | U, I | `WalletCreateUseCaseTest`(9), `WalletDuplicateViolationTest`(5), `WalletCreationIntegrationTest#concurrentCreation`, `#uniqueConflict_*` | 없음 | R-003 통과 |
| BT-WAL-02 | 가입 이벤트 → 지갑 | 일반·인플루언서 생성, ADMIN 제외, 재전달·중복 이벤트에도 1개, Kafka 그룹 `billing-app.wallet-creation`(#430) | BD-2, BD-3, BD-123 | I | `WalletCreationIntegrationTest#userJoined_*`, `#walletLookupThenUserJoined`, `#concurrentEventAndLookup`, `BillingMemberEventKafkaTest``#joined_createsWalletAndReplica`, `#duplicateAndStaleEventsAreIgnored`, `#adminJoined_replicaWithoutWallet` | 없음 | 기록 없음 (#430에서 Kafka로 바뀜) |
| BT-WAL-03 | 내 지갑 조회 | 없으면 생성, 본인 것만, ADMIN 403, 비로그인 401, Swagger 응답 일치 | BD-2, BD-3 | I | `ApiV1WalletControllerTest`(7), `WalletFacadeTest`(2) | 경계 (인증) | R-003 통과 (경계) |
| BT-WAL-04 | 거래 내역 | 유형 분류, 지갑별 합계 = 잔액, 페이징, 본인 것만, 지갑 없으면 빈 페이지 | decisions 12장 | I | `WalletTransactionQueryIntegrationTest`(12), `ApiV1WalletTransactionControllerTest`(7) | 경계 (인증) | R-003 통과 (경계) |
| BT-LED-01 | 원장·지갑 원자성 | 원장 실패 = 지갑 롤백, 지갑 실패 = 원장 롤백 | BD-7, INV-1 | I | `LedgerRecordIntegrationTest#ledgerFailure_rollsBackWallet`, `#walletFailure_rollsBackLedger` | 없음 | R-003 통과 |
| BT-LED-02 | 잔액 변경 유일 경로 | `Wallet.applyLedger`는 `LedgerRecordUseCase`만 호출 | BD-7 | U | `WalletApplyLedgerAccessTest#onlyLedgerRecordUseCaseAppliesLedger` | 없음 | R-003 통과 |
| BT-LED-03 | 잔액 규칙 | 버킷 분리, 음수 금지, 오버플로, 0 HP 금지, 같은 원장 재적용 금지 | BD-8, BD-12, INV-3 | U, I | `WalletTest`(11, 지갑 유형·FEE 가드 포함 #336), `LedgerOperationTest`(6), `LedgerRecordIntegrationTest#insufficientBalance`, `#balanceOverflow`, `#loadedLedgerCannotBeReapplied` | 없음 | R-003 통과 |
| BT-LED-04 | 지갑 락 | ID 오름차순, 반대 순서 동시 작업에 교착 없음, 오래된 엔티티에 lost update 없음, 트랜잭션 필수 | BD-10, BD-11 | U, I | `LedgerRecordUseCaseTest#locksWalletsInIdOrder`, `LedgerRecordIntegrationTest#opposingWalletOrder_noDeadlock`, `#staleWalletInCallerTransaction_noLostUpdate`, `#lockWallet_*` | 없음 | R-003 통과 |
| BT-LED-05 | 원장 멱등 | 같은 키·같은 내용 = 재생, 다른 내용 = 충돌, 동시 중복 = 1회 반영, 다른 지갑 같은 키 = 충돌 | BD-14~BD-16 | I | `LedgerRecordIntegrationTest#retry_replays`, `#sameKeyDifferentContent_conflict`, `#concurrentDuplicates_appliedOnce`, `#concurrentSameKeyDifferentWallets` | 없음 | R-003 통과 |
| BT-LED-06 | 역분개 | 누적 한도, 역분개의 역분개 금지, 음수 잔액 롤백, 동시 역분개 한도, 재생 | BD-18 | I | `LedgerReversalIntegrationTest`(8) | 없음 | R-003 통과 |
| BT-LED-07 | 원장 append-only | SQL UPDATE·DELETE 거부(트리거) | INV-2 | R | `BillingConstraintsTest#원장은_SQL로도_수정하거나_삭제할_수_없다` | 없음 | R-003 통과 |
| BT-LED-08 | 잔액 대사 | 일치·캐시 불일치·원장 불일치 탐지, 동시 작업 중 같은 스냅숏, 수수료 수입 칸(FEE 가용, #336) | BD-20, INV-1 | I | `WalletReconciliationIntegrationTest`(6), `ExchangePayoutIntegrationTest`의 플랫폼 지갑 대사(BT-EXP-08) | 없음 | R-003 통과 |
| BT-LED-09 | 원장 기록의 트랜잭션 참여 | 같은 앱 안 호출자 트랜잭션에 참여 | BD-53 | I | `LedgerRecordIntegrationTest#joinsCallerTransaction`, `#multipleRecordsInSameTransaction_keepPendingChanges` | 없음 (Billing 내부 호출) | R-003 통과 |

### 4.3 보류·반환·지급·위약금·확정 차감 (HLD·REL·PAY·PEN·CAP)

| ID | 대상 | 검증 기준 | 근거 | 단계 | 테스트 | JVM | 상태 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| BT-HLD-01 | 보류 배분 | 수익 HP 먼저, 충전 HP는 완료 시각·ID 순 FIFO, 배분 가능한 상태만 사용 | BD-24 | U, I | `HoldSplitTest`(6), `HoldCreateIntegrationTest#revenueFirstThenCharge`, `#fifoByCompletedAt`, `#fifoSameCompletedAt_byId`, `#allocatableStatusesOnly`, `#holdAndUsageRows` | 없음 | R-003 통과 |
| BT-HLD-02 | 보류 멱등 | 참조당 1건, 재요청 = 처음 배분 그대로, 금액·지갑이 다르면 충돌, 동시 요청 1건 | BD-21, BD-23 | I | `HoldCreateIntegrationTest#retry_replays`, `#retryAfterBalanceChange_keepsOriginalSplit`, `#sameReference*_conflict`, `#concurrentSameRequest_oneHold`, `#concurrentSameReferenceDifferentWallets` | 없음 | R-003 통과 |
| BT-HLD-03 | 보류 정합성 오류 | 원금 불일치 `CHARGE_PRINCIPAL_MISMATCH`, 원장만·보류만 있으면 `HOLD_STATE_INCONSISTENT` | BD-23, BD-25, INV-4 | I | `HoldCreateIntegrationTest#chargePrincipalMismatch`, `#ledgerWithoutHold_inconsistent`, `#holdWithoutLedger_inconsistent` | 없음 | R-003 통과 |
| BT-HLD-04 | 조회 순서 경합 | 원장 → 보류 순서로 정상 경쟁은 충돌, 비정상은 불일치로 분류 | BD-27 | I | `HoldCreateIntegrationTest#lookupRace_*`, `#concurrentInsert_uncommittedHold_uniqueViolation` | 없음 | R-003 통과 |
| BT-HLD-05 | 보류 원자성 | 어느 단계가 실패해도 전부 롤백, 동시 보류에 이중 배분 없음 | — | I | `HoldCreateIntegrationTest#*_rollsBackAll`, `#concurrentDistinctHolds_noDoubleAllocation` | 없음 | R-003 통과 |
| BT-HLD-06 | 멱등키 형식 | 결정론적 키, 최장 키 255자 이내 | BD-14, BD-21, BD-30, BD-44, BD-48 | U | `BillingKeysTest`(7) | 없음 | R-003 통과 |
| BT-REL-01 | 반환 순서 | 충전 HP 먼저, 원금 LIFO 복원, 복원 원금은 다시 FIFO 배분 | BD-29 | U, I | `ReleaseSplitTest`(6), `ChargeUsageHistoryTest#releaseLifo`, `HoldReleaseIntegrationTest#partial_chargeOnly_lifo`, `#partial_mixed_chargeFirst`, `#restoredPrincipal_reallocatedFifo` | 없음 | R-003 통과 |
| BT-REL-02 | 반환 범위·상태 | 부분·전체, 초과 거절, 닫힌 보류 거절, 지급분은 반환 불가 | BD-32, BD-34 | U, I | `PointHoldReleaseTest`(12), `HoldReleaseIntegrationTest#partial_exceeds`, `#full_*`, `#closedHold_newRequestRejected`, `#paidAmount_notReleasable` | 없음 | R-003 통과 |
| BT-REL-03 | 반환 멱등 | 같은 ID 재생(닫힌 뒤 포함), 다른 금액 충돌, 다른 보류 같은 ID는 별개, 동시 반환에 초과 없음 | BD-30~BD-32 | I | `HoldReleaseIntegrationTest#sameRequest_*`, `#sameRequestId_*`, `#retryAfterClosed_replays`, `#differentRequests_concurrent_noOverRelease` | 없음 | R-003 통과 |
| BT-REL-04 | 이력 재생 대조 | 상세를 재생한 결과가 배분 누적값과 다르면 `HOLD_STATE_INCONSISTENT` | BD-33, INV-5 | U, I | `ChargeUsageHistoryTest#replay*`, `HoldReleaseIntegrationTest#usageNotMatchingHistory_inconsistent`, `#ledgerWithoutDetail_inconsistent` | 없음 | R-003 통과 |
| BT-REL-05 | 반환 소유·원자성 | 없는 보류·남의 보류 거절, 단계별 실패 전체 롤백 | BD-32 | I | `HoldReleaseIntegrationTest#notFound`, `#otherMembersHold`, `#*_rollsBackAll` | 없음 | R-003 통과 |
| BT-REL-06 | 보류 무효화 (#479) | 남은 보류 전부 반환(`RELEASED`, `RELEASE:{holdId}:VOID`), 끝난 보류는 그대로(`ALREADY_CLOSED`), 보류 없음은 표시만(`VOIDED`). 무효화 뒤 보류·보류 재요청은 `HOLD_VOIDED`, 같은 무효화는 처음 결과 재생, 남의 보류·기록은 `HOLD_OWNER_MISMATCH`, 같은 참조 대상의 보류·무효화 동시 실행에 남은 보류 없음 | BD-125, BD-126 | S, I | `HoldVoidIntegrationTest`(8), `ApiV1BillingInternalControllerTest#voidHold`, shared `BillingApiClientTest#보류_무효화는_hold_voids_경로로_보내고_결과를_돌려준다` | 없음 | 기록 없음 (R-003 이후 추가) |
| BT-PAY-01 | 지급 배분 | 수익 HP 보류 먼저, 충전 원금 FIFO 소진, 받는 쪽 수익 HP 가용, 초과 거절 | BD-39 | U, I | `PayoutSplitTest`(2), `PointHoldPayoutTest`(5), `HoldPayoutIntegrationTest#payout_revenueOnly_full`, `#payout_chargeOnly_fifo`, `#payout_mixed_revenueFirst`, `#payout_exceeds` | 없음 | R-003 통과 |
| BT-PAY-02 | 원천 1회 정산 | 같은 원천은 보류·유형과 무관하게 1회, NORMAL·PENALTY 배타, 동시 요청도 DB UNIQUE로 1건 | BD-42, BD-43, INV-6 | I, R | `HoldPayoutIntegrationTest#sameSource_normalAndPenaltyExclusive`, `#request_contractSettlesOnce`, `#sameContractAcrossHolds_rejected`, `#concurrent_sameContractAcrossHosts_*`, `BillingConstraintsTest#지급_원천은_보류와_지급_유형이_달라도_한_번만_정산한다` | 없음 | R-003 통과 |
| BT-PAY-03 | 지급 멱등 | 같은 요청 ID 재생, 다른 보류의 같은 ID는 별개, 동시 같은 지급 1회 | BD-44 | I | `HoldPayoutIntegrationTest#payout_idempotency`, `#payout_sameRequestIdOtherHold`, `#concurrent_samePayout` | 없음 | R-003 통과 |
| BT-PAY-04 | 중복 지급 오류 순서 | 끝난 보류 `HOLD_ALREADY_CLOSED`가 원천 중복 `PAYOUT_ALREADY_SETTLED`보다 먼저 | BD-69, BD-70 | I | 없음. `HoldPayoutIntegrationTest#payout_invalidRequests`의 `HOLD_ALREADY_CLOSED` 검사는 정산되지 않은 원천으로 해서, 두 조건이 함께 걸리는 경우의 순서를 시험하지 않는다(2026-10-29 코드 확인) | 없음 | 없음 |
| BT-PAY-05 | 혼합 이력·경합 | 반환·지급 혼합 재생, 반환과 지급 동시 실행 시 한쪽만 성공, 양방향 지급에 교착 없음 | BD-34, BD-73 | I | `HoldPayoutIntegrationTest#mixed_*`, `#concurrent_releaseAndPayout`, `#concurrent_bidirectional`, `#concurrent_sameGuestFromManyHosts` | 없음 | R-003 통과 |
| BT-PAY-06 | 지급 요청 검증 | 자기 지급 `PAYOUT_TO_PAYER`, 원천 불일치 `PAYOUT_SOURCE_MISMATCH`, 실패 전체 롤백 | BD-41, BD-45 | I | `HoldPayoutIntegrationTest#payout_invalidRequests`(세부 미확인), `#payout_failures_rollBackAll` | 없음 | R-003 통과 |
| BT-PAY-07 | 판매자 지갑 없음 | 지급 때 `WALLET_NOT_FOUND`로 롤백, 지갑 생성 후 같은 요청 ID로 재지급 | BD-68 | I | 일부: `HoldPayoutIntegrationTest#payout_invalidRequests`(받는 회원 지갑 없음 → `WALLET_NOT_FOUND`, 상태 불변). 지갑을 만든 뒤 같은 요청 ID로 재지급하는 테스트는 없다(2026-10-29 코드 확인) | 없음 | R-003 통과 (일부만 검증, 6.1) |
| BT-PEN-01 | 위약금 처리 | 받은 반환액 + 지급액을 원자적으로 처리, 비율 검증 없음, 기준액이 남은 보류를 넘으면 거절, 합계 overflow 거절 | BD-56 | U, I | `BillingCommandValidatorTest#penaltyAnyRatio`, `#penaltyOverflow`, `HoldPayoutIntegrationTest#penalty_10000`, `#penalty_10001_mixed`, `#penalty_otherRatio`, `#penalty_rejects` | 없음 | R-003 통과 |
| BT-PEN-02 | 0 HP 규칙 | 게스트 지급 0 거절, 호스트 반환 0 허용(반환 상세·원장 없음) | BD-57, BD-58 | U, I | `BillingCommandValidatorTest#penaltyZeroPayout`, `HoldPayoutIntegrationTest#penalty_zeroRejected`, `#penalty_noRelease*` | 없음 | R-003 통과 |
| BT-PEN-03 | 위약금 멱등·원자성 | 같은 취소 ID 재생, 동시 같은 취소 1회, 단계별 실패 전체 롤백 | BD-48, BD-59 | I | `HoldPayoutIntegrationTest#penalty_idempotency`, `#penalty_concurrentSameCancellation`, `#penalty_*Failure_rollsBack*` | 없음 | R-003 통과 |
| BT-PEN-04 | 모집 위약금 | 게스트별 위약금·정상 지급 혼합 | BD-41, BD-43 | I | `HoldPayoutIntegrationTest#penalty_recruitOneGuest`, `#recruit_penaltyForOneGuest_normalForAnother`, `#payout_recruit_multipleGuests` | 없음 | R-003 통과 |
| BT-CAP-01 | 종류별 보류 | 참조 유형별 MIXED·CHARGE_ONLY·REVENUE_ONLY, 종류 잔액 부족 거절, DB 제약 | BD-82, INV-8 | I | `HoldCaptureIntegrationTest#holdByType`, `#holdByType_insufficient`, `#internalHold_idempotencyAndScope`, `#dbConstraints` | 없음 | R-003 통과 |
| BT-CAP-02 | 확정 차감 | 전체·부분, 이어서 반환, 멱등, 거절 조건, 동시 실행, 원장 실패 롤백 | BD-83, BD-86 | I | `HoldCaptureIntegrationTest#capture_*` | 없음 | R-003 통과 |
| BT-CAP-03 | 충전 건 지정 | PG 결과대로 지정 충전 건만 확정 차감·반환, 금액 필수, 정확한 PG 금액 | BD-84, BD-85 | U, I | `ChargeUsageHistoryTest#targetedCharge*`, `HoldCaptureIntegrationTest#capture_byCharge_*` | 없음 | R-003 통과 |
| BT-CAP-04 | MIXED 보호 | MIXED 보류는 확정 차감 불가, 지급과 확정 차감이 한 보류를 나눠 쓰지 않음, MIXED 회귀 | BD-83, BD-86 | I | `HoldCaptureIntegrationTest#mixedHold_regression`, `#captureAndPayout_neverShareHold` | 없음 | R-003 통과 |

### 4.4 내부 API와 모듈 경계 (PORT·EVT)

| ID | 대상 | 검증 기준 | 근거 | 단계 | 테스트 | JVM | 상태 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| BT-PORT-01 | 내부 API 결과 변환 | 보류·반환·지급·위약금 정산이 shared 결과 DTO(RsData `data`)로 바뀐다. 강의 주문 항목별 보류·반환 | BD-51, BD-52, BD-61 | I | `ApiV1BillingInternalControllerTest#hold`, `#release`, `#payout`, `#settlePenalty`, `#lectureOrderItems` (#486에서 `BillingPortIntegrationTest`를 옮김) | 없음 | 기록 없음 (R-003 이후 HTTP로 이동) |
| BT-PORT-02 | 요청 단위 트랜잭션 | 요청 하나 = 지갑 트랜잭션 하나. 처리 중 실패(위약금의 반환 뒤 지급 실패 포함)는 그 요청의 반환·지급·원장 변경을 전부 롤백한다. 지갑 안 UseCase(환불·환전)가 부르는 보류는 그 트랜잭션에 참여한다 | BD-53, BD-64 | I | `ApiV1BillingInternalControllerTest#technicalFailureRollsBack`, `HoldCreateIntegrationTest#joinsCallerTransaction`, `HoldReleaseIntegrationTest#joinsCallerTransaction`, `#staleEntitiesInCallerTransaction_refreshed`, `HoldPayoutIntegrationTest#penalty_joinsCallerTransaction`. 다른 모듈 트랜잭션 참여 검증은 #486에서 삭제(HTTP에서는 없는 동작) | 없음 | 기록 없음 (R-003 이후 HTTP로 이동) |
| BT-PORT-03 | 금융 오류 응답 | 잔액 부족·지갑 없음·보류 없음·금액 오류·멱등 충돌이 `BillingErrorCode`의 HTTP 상태와 `code`로 오고 값이 바뀌지 않는다 | BD-60 | I | `ApiV1BillingInternalControllerTest#businessErrors`, `#amountErrors`, `#idempotencyConflict` | 없음 | 기록 없음 (R-003 이후 HTTP로 이동) |
| BT-PORT-04 | 원격 보류·반환·지급·위약금 계약 | 내부 HTTP 경로·키 인증·요청/응답 DTO·오류 코드·타임아웃·결과 불명 처리·같은 요청 ID 재시도. 호출 쪽 Resilience4j timeout·circuit open일 때 성공으로 해석하지 않음. 보상 처리: 보류 실패 → 주문이 남지 않음, 주문 실패 → 보류 반환 | AGENTS 3장, D62·D63, BD-70, 인프라 문서 3·10장, 강의 D64·D66 | T | Client 쪽만: shared `BillingApiClientTest`(실패 분류, RETRYABLE만 재시도, 회로 OPEN = `CIRCUIT_OPEN`·RETRYABLE, 시간 상한 = 결과 불명, 429·`HP_LOCK_TIMEOUT` 미집계, #411). 소비 앱 보상 처리는 없음 | 깨짐 | 없음 (#280) |
| BT-PORT-05 | 소비 앱 기동 | lecture-app·appearance-app이 Billing 원격 Client로 기동한다 | `docs/architecture/app-modules.md` | T | 없음 (Billing 범위 밖) | 깨짐 | 없음 |
| BT-EVT-01 | 다른 앱에서 온 회원 이벤트 | member-app의 가입·변경 이벤트가 Kafka `member.events.v1`로 billing-app에 전달되어 지갑·복제본이 생긴다. 소비 그룹 `billing-app.member-replica`(복제본)·`billing-app.wallet-creation`(지갑)이 따로 받는다. 같은 회원·같은 버전 재전달은 한 번만 반영(지갑 하나, 복제본 한 번), 순서가 바뀌어도 회원 버전 비교로 최신만 반영, 가입보다 변경이 먼저 와도 복제본 생성, 모르는 타입은 무시, 재시도 3회 초과는 그 그룹 DLT, 역직렬화 실패는 재시도 없이 두 그룹 DLT(`member.events.v1.billing-app.member-replica.DLT`·`member.events.v1.billing-app.wallet-creation.DLT`, partition 3·보존 7일), 한 그룹의 실패가 다른 그룹을 막지 않는다 | BD-2, BD-6, BD-103, BD-123, kafka.md | I | `BillingMemberEventKafkaTest`(9) | 없음 | 기록 없음 (#430에서 추가) |

### 4.5 충전·환불 (CHG·RFD)

| ID | 대상 | 검증 기준 | 근거 | 단계 | 테스트 | JVM | 상태 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| BT-CHG-01 | 충전 주문 | 1,000 단위, 지갑 없으면 거절, 남의 주문·금액 불일치 거절 | BD-81 | I | `ChargeIntegrationTest#createOrder*`, `#walletNotFound`, `#confirm_otherMembersOrder`, `#confirm_amountMismatch` | 없음 | R-003 통과 |
| BT-CHG-02 | 승인 완료 | COMPLETED + 충전 원장 1줄 + 가용 원금 = 충전액. FAILED는 완료되지 않음 | BD-76 | U, I | `PointChargeTest#chargeConfirmFlow`, `ChargeIntegrationTest#confirm_completes`, `#failedNeverCompleted` | 없음 | R-003 통과 |
| BT-CHG-03 | 승인 멱등 | 같은 결제키 재생, 다른 결제키 충돌, 동시 승인 1회, 결제키 해시 UNIQUE 경합 | BD-79 | I | `ChargeIntegrationTest#confirm_replay`, `#confirm_paymentKeyConflict`, `#confirm_concurrent`, `#confirm_paymentKeyHashUniqueRace` | 없음 | R-003 통과 |
| BT-CHG-04 | PG 결과 판정 | 거절 = FAILED, 401·409·429 등 = 불명확 유지, 5xx·타임아웃 = 조회, PARTIAL_CANCELED = 운영자 확인, 승인 결과 불일치 = `CHARGE_STATE_INCONSISTENT` | BD-77 | U, I | `TossPaymentsClientTest`(8), `ChargeIntegrationTest#confirm_rejected`, `#confirm_timeoutButApproved`, `#confirm_unknownThenRetried`, `#confirm_rateLimitOrAuthErrorKept`, `#confirm_approved*Mismatch`, `#recovery_partialCanceledKeptForOperator` | 없음 (외부 장애) | R-003 통과 |
| BT-CHG-05 | 승인 후 복구 | 승인 후 DB 실패·불명확 건을 조회로 확정, 미확정 건 순환, 세션 만료 FAILED | BD-76, BD-78 | I | `ChargeIntegrationTest#confirm_dbFailureAfterApproval`, `#recovery_*` | 없음 | R-003 통과 |
| BT-CHG-06 | 결제키 보호 | 무작위 IV 암호화, HMAC, 변조 감지, 응답·저장에 원문 없음, 설정 오류 시 기동 실패 | BD-80 | U, I | `PaymentKeyCipherTest`(6), `ChargeIntegrationTest#confirm_paymentKeyProtected`, `#recovery_unreadablePaymentKey` | 없음 | R-003 통과 |
| BT-CHG-07 | 결제 설정 없음 | 앱은 뜨고 충전·환불·환전 API만 503 | BD-80 | I | `ApiV1WalletChargeUnavailableTest`(3) | 없음 | R-003 통과 |
| BT-CHG-08 | 충전 HP 사용 | 충전한 HP가 보류·반환에 쓰인다 | BD-24 | I | `ChargeIntegrationTest#chargedHpUsedByHoldAndRelease` | 없음 (#486부터 `HoldFacade`로 보류) | R-003 통과 (경계) |
| BT-CHG-09 | 충전 API | 상태 코드(201·200·202), 요청 검증, 권한 | A10.1 | I | `ApiV1WalletChargeControllerTest`(7) | 경계 (인증) | R-003 통과 (경계) |
| BT-RFD-01 | 환불 접수 | 충전 HP만, FIFO로 여러 충전 건에 나눔, 금액 1 HP 이상·1 HP 단위(#453) | BD-87, BD-119 | I | `RefundIntegrationTest#refund_singleChargePartial`, `#refund_fullCharge`, `#refund_fifoAcrossCharges`, `#refund_chargeHpOnly`, `#refund_invalidAmount`, `#refund_anyAmountFromOne` | 없음 | R-003 통과 |
| BT-RFD-02 | PG 취소 멱등키 | UUID를 한 번 만들어 저장, DB ID와 무관 | BD-88 | U, I | `BillingKeysTest#refundCancelKeyIsUnique`, `RefundIntegrationTest#refund_cancelKeyIsStoredUuid`, `#refund_cancelKeyIndependentOfDbIds` | 없음 | R-003 통과 |
| BT-RFD-03 | PG 결과 분류 | 요청 오류 = 반환, 환급 불가 = 보류 유지·운영자 확인, 중복 취소 = 운영자 확인, 불명확 = 조회 후 확정 | BD-89 | I | `RefundIntegrationTest#refund_firstRejected`, `#refund_secondRejected`, `#refund_pgRefundNotPossibleKeepsClaim`, `#refund_duplicateCancelAtPg`, `#refund_unknown*`, `#refund_canceled*`, `#refund_alreadyCanceled*`, `#refund_lookupNotFound` | 없음 (외부 장애) | R-003 통과 |
| BT-RFD-04 | UNKNOWN 한도 | 24시간 또는 12회 → 운영자 확인, 마지막 확인에서 성공하면 확정 차감 | BD-90 | U, I | `PointRefundTest#unknownLimit`, `RefundIntegrationTest#unknown_*` | 없음 | R-003 통과 |
| BT-RFD-05 | 멱등키 안전 기간 | 최초 요청 + 14일 경계부터 재POST 금지, 이후 조회만 | BD-91 | U, I | `PointRefundTest#idempotencyWindow`, `RefundIntegrationTest#idempotencyWindow_*` | 없음 | R-003 통과 |
| BT-RFD-06 | 취소·경합 | REQUESTED만 회원 취소, 처리와 취소 경합, 처리와 복구 경합, 동시 요청에 과환불 없음 | BD-92 | U, I | `PointRefundTest#cancel_onlyPending`, `RefundIntegrationTest#cancel_*`, `#processing_concurrentWithRecovery`, `#concurrentRequests_noOverRefund`, `#additionalRequestWhileUnknown` | 없음 | R-003 통과 |
| BT-RFD-07 | 접수 멱등·복구 | 같은 키 재생, REQUESTED 재개, 실패 요청 순환 | BD-87, BD-92 | I | `RefundIntegrationTest#refund_idempotentReplay`, `#recovery_*` | 없음 | R-003 통과 |
| BT-RFD-08 | 환불 API | 200·202, 요청 검증, 취소, 권한 | A10.2 | I | `ApiV1WalletRefundControllerTest`(6) | 경계 (인증) | R-003 통과 (경계) |

### 4.6 환전·송금·Mock 은행 (EXC·EXP·MCK)

| ID | 대상 | 검증 기준 | 근거 | 단계 | 테스트 | JVM | 상태 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| BT-EXC-01 | 환전 신청 | 수익 HP만, 금액 1 HP 이상·1 HP 단위와 송금액 버림(송금 = floor(신청 × 90%), #453), REVENUE_ONLY 보류, 계좌 암호화 스냅샷, 계좌 저장 실패 전체 롤백 | BD-94, BD-95, BD-120 | U, I | `ExchangeTest`(14: `#payoutAndFee`·`#tooSmallAmount`·`#anyAmountFromOne` 포함), `ExchangeAccountCipherTest`(3), `ExchangeIntegrationTest#request`, `#accountSaveFailureRollsBackAll`, `#accountSnapshotEncrypted`, `#chargeHpNotExchangeable`, `#insufficientRevenue` | 없음 (#486부터 수익 HP를 `HoldFacade` 지급으로 만든다) | R-003 통과 (경계) |
| BT-EXC-02 | 신청 멱등·동시성 | 같은 키 재생, 복호화 불가 재요청 거절, 동시 신청에 과보류 없음 | BD-95 | I | `ExchangeIntegrationTest#idempotentReplay`, `#concurrentSameKey`, `#concurrentRequestsDoNotOverHold`, `#replayWithUnreadableAccount` | 없음 | R-003 통과 |
| BT-EXC-03 | 회원 취소 | REQUESTED만, 반환 1회, 상태 변경과 직렬화, 승인 뒤 거절 | BD-97, BD-98 | I | `ExchangeIntegrationTest#cancel`, `#concurrentCancel`, `#cancelWaitsForConcurrentStatusChange`, `#notCancelableAfterApproval` | 없음 | R-003 통과 |
| BT-EXC-04 | 정산 회차 | 기준일·주말 보정·잠정 예정 시각·승인 시점 회차 | BD-96 | U | `ExchangeSettlementRoundTest`(7) | 없음 | R-003 통과 |
| BT-EXC-05 | 관리자 승인·거절 | 상태 전이, 멱등(같은 관리자·같은 사유), 승인·거절·취소 경합에 하나만 성공, 보류 정합성 | BD-102 | I | `ExchangeReviewIntegrationTest`(20) | 없음 | R-003 통과 |
| BT-EXC-06 | 신청자 복제본 | 복제본 없음·자격 없음이면 승인 409, 거절은 신청자 상태 무관 | BD-102, BD-103 | I | `ExchangeReviewIntegrationTest#approveWithoutReplica`, `#approveNotEligible`, `#rejectRegardlessOfApplicant` | 경계 (복제본이 이벤트·Backfill로 채워진다는 전제) | R-003 통과 (경계) |
| BT-EXC-07 | 환전 API | 계좌번호 미노출·마스킹, 요청 검증, 권한 | A11 | I | `ApiV1SettlementExchangeControllerTest`(6), `ApiV1SettlementExchangeAdminControllerTest`(4) | 경계 (인증) | R-003 통과 (경계) |
| BT-EXP-01 | 송금 성공 | 확정 차감 1회, 완료 재실행 멱등, 예정 전·미승인 거절 | BD-101 | U, I | `ExchangePayoutTest`(15), `ExchangePayoutIntegrationTest#success`, `#completedIsIdempotent`, `#notDue`, `#notApproved` | 없음 | R-003 통과 |
| BT-EXP-02 | 송금 실패·재시도·최종 실패 | FAILED는 보류 유지, 재시도는 새 요청 ID, 최종 실패 = 반환, 불명확한 상태에서는 최종 실패 금지 | BD-101 | I | `ExchangePayoutIntegrationTest#failedThenRetry`, `#failFinally*`, `#recheckRejectedThenRetry` | 없음 | R-003 통과 |
| BT-EXP-03 | 결과 불명확·복구 | 타임아웃·5xx·처리 중·전송 전 중단을 같은 ID로 확인, 24시간 뒤 운영자 확인, 은행 성공 뒤 반영 실패 복구 | BD-101 | I | `ExchangePayoutIntegrationTest#timeoutThenRecover`, `#serverErrorThenResend`, `#bankProcessing`, `#crashBeforeSend`, `#applyFailsAfterBankSuccess`, `#autoCheckExpires`, `#recheck*` | 없음 (외부 장애) | R-003 통과 |
| BT-EXP-04 | 송금 경합 | 동시 실행·복구·재확인·최종 실패와 재시도 경합 | BD-98, BD-101 | I | `ExchangePayoutIntegrationTest#concurrent*`, `#finalizeVersusRetry`, `#recheckVersusRecovery`, `#concurrentSameFinalize` | 없음 | R-003 통과 |
| BT-EXP-05 | 정산 배치 처리 | 예정 지난 APPROVED만, 커서·상한, 실패 격리·같은 실행에서 반복 없음, Client 없으면 건너뜀, 늦은 승인은 다음 회차 | decisions 12장 | I | `ExchangeSettlementUseCaseTest`(6), `ExchangeSettlementIntegrationTest`(10) | 없음 | R-003 통과 |
| BT-EXP-06 | 배치 등록 조건 | 스케줄링·배치별 스위치 | — | U | `ExchangeSettlementBatchConditionTest`(3) | 없음 | R-003 통과 |
| BT-EXP-07 | 송금 관리자 API | 목록·재확인·재시도·최종 실패, 권한 | A11 | I | `ExchangePayoutIntegrationTest#adminApi`, `#adminApiAuthorization` | 경계 (인증) | R-003 통과 (경계) |
| BT-EXP-08 | 수수료 수입 기록 (#336) | 송금 성공(송금액 0원은 완료) 때 확정 차감과 같은 트랜잭션에 `EXCHANGE_FEE` 1줄, 회원 차감 = 송금 + 수수료, 환전당 1회(재실행·수동 재송금), 최종 실패·결과 대기는 기록 없음, 플랫폼 지갑 캐시 = 원장 합 | BD-114, BD-115, BD-116 | U, I | `ExchangePayoutIntegrationTest#success`, `#completedIsIdempotent`, `#feeIncomeOncePerExchange`, `#zeroPayoutCompletesWithoutTransfer`(`assertConsistent`), `WalletTest#platform_feeIncome` | 없음 | 기록 없음 (R-003 이후 추가) |
| BT-EXP-09 | 플랫폼 지갑 접근 (#336) | 관리자 API 잔액·내역(최신순, 신청 = 송금 + 수수료), 관리자가 아니면 403·비로그인 401, 회원 ID로 조회되지 않음, 지갑 유형·FEE 보류 칸 가드 | BD-114, BD-117 | U, I | `ExchangePayoutIntegrationTest#platformWalletAdminApi`, `#platformWalletAdminApiAuthorization`, `#platformWalletNotReachableByMember`, `WalletTest#walletTypeGuards` | 경계 (인증) | 기록 없음 (R-003 이후 추가) |
| BT-EXP-10 | 송금액 0원 환전 (#453) | 신청 1 HP: 정산 예정 전 NOT_DUE, 지나면 은행 호출·송금 시도 없이 확정 차감 1 + 수수료 수입 1 + COMPLETED, 다시 실행해도 그대로 | BD-121 | U, I | `ExchangeTest#completeWithoutTransfer`, `ExchangePayoutIntegrationTest#zeroPayoutCompletesWithoutTransfer` | 없음 | 기록 없음 (R-003 이후 추가) |
| BT-MCK-01 | Mock 은행 HTTP | 같은 요청 재생·다른 내용 409, 시나리오, 트랜잭션 안 호출 금지, 접근 키, 계좌 미로그, 재시작 후 복구 | BD-99 | U, I | `MockPayoutTransferTest`(2), `MockPayoutHttpIntegrationTest`(19) | 없음 (같은 앱 안의 HTTP) | R-003 통과 |
| BT-MCK-02 | Mock 활성 조건 | 배포 프로필·기본 프로필에서는 빈·엔드포인트·HTTP 없음 | BD-100 | U, I | `MockPayoutActivationTest`(3), `MockPayoutDeployedProfileTest`(2×5), `MockPayoutDisabledTest`(3) | 없음 | R-003 통과 |

### 4.7 수익 조회·회원 복제본 (ERN·USR)

| ID | 대상 | 검증 기준 | 근거 | 단계 | 테스트 | JVM | 상태 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| BT-ERN-01 | 수익 합산 | 상태별 합, 전역 정렬 병합, 페이지 = 전체 병합의 부분, 깊이 1,000 제한, 한쪽 실패 = 전체 실패 | internal-earnings.md | U | `EarningQueryUseCaseTest`(13) | 없음 | R-003 통과 |
| BT-ERN-02 | 출처 Client 변환·장애 분류 | 응답 변환(출연 KST), 연결 실패·5xx·해석 실패 = 503 UNAVAILABLE, 4xx = 503 REJECTED, Client 없음 = 503 | internal-earnings.md 4·7장 | U | `EarningSourceAdapterTest`(9) | 경계 (실제 HTTP지만 응답은 Billing 쪽 가짜 서버) | R-003 통과 (경계) |
| BT-ERN-03 | 수익 API | 역할, 본인 것만, 출처 장애, 깊이, 내부 키 헤더 송신 | internal-earnings.md | I | `ApiV1SettlementEarningControllerTest`(10) | 경계 (인증·가짜 서버) | R-003 통과 (경계) |
| BT-ERN-04 | 제공자 계약 일치 | lecture-app·appearance-app 실제 응답이 Billing Client 기대와 같다 | internal-earnings.md | T | 없음 | 경계 | 없음 |
| BT-USR-01 | 복제본 동기화 | 가입·변경 Upsert, 버전 비교, 버전 없는 정보 무시, 늦은 가입 이벤트가 덮어쓰지 않음, 동시 Upsert | BD-103 | I | `BillingUserSyncIntegrationTest`(8) | 경계 (Scenario.publish) | R-003 통과 (경계) |
| BT-USR-02 | 그룹 격리·재시도 | 복제본 그룹 실패가 지갑 생성 그룹을 막지 않음(재시도 뒤 복제본 DLT만), 복제본 PK 충돌 재시도는 새 트랜잭션 | BD-103, BD-123 | I | `BillingMemberEventKafkaTest``#replicaFailureDoesNotBlockWalletCreation`, `BillingMemberEventIsolationTest`(1) | 없음 | 기록 없음 (#430에서 Kafka로 바뀜) |
| BT-USR-03 | Backfill | 페이지 순회·한도·재개 커서, 실패 분류, 계약 위반, 동시 실행 금지, 이벤트와 경합, 관리자 API | BD-104 (확인 필요) | U, I | `BillingUserBackfillUseCaseTest`(13), `BillingUserBackfillIntegrationTest`(14) | 경계 (MemberApiClient 실제 HTTP, 가짜 member 서버) | R-003 통과 (경계) |
| BT-USR-04 | member 제공자 계약 일치 | member-app 내부 회원 API 응답이 Billing Client 기대와 같다 | `docs/api/internal-members.md` | T | 없음 | 경계 | 없음 |

### 4.8 보안·오류·DB·설정 (SEC·ERR·DB·CFG)

| ID | 대상 | 검증 기준 | 근거 | 단계 | 테스트 | JVM | 상태 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| BT-SEC-01 | URL 인가 | 관리자 경로 두 겹, `@PreAuthorize` 역할, 공개 경로(헬스체크 등), 비로그인 401 | A14, policy.md 역할표 | S | `SecurityConfigTest`(12) | 미확인 (`TestLogin`으로 인증을 흉내 냄) | R-003 통과 (JVM 영향 미확인) |
| BT-SEC-02 | `@LoginUserId` | 로그인 사용자 ID 해석, 익명 처리 | — | U | `LoginUserIdArgumentResolverTest`(4) | 미확인 (principal 이름 = 회원 ID라는 전제) | R-003 통과 (JVM 영향 미확인) |
| BT-SEC-03 | 내부 API 키 | 맞는 키만 통과, 경로 prefix 보호, 세션과 키 분리, 내부 경로만 CSRF 해제, 키 미노출, 키 없으면 기동 실패 | AGENTS 7장 | U, S | `InternalApiKeyFilterTest`(2), `InternalApiPropertiesTest`(4), `InternalApiSecurityConfigTest`(11) | 없음 (체인만 있고 Billing이 노출하는 내부 경로는 없음) | R-003 통과 |
| BT-SEC-04 | 기능별 역할 | Facade `@PreAuthorize` 역할(관리자 지갑 403 등) | policy.md 역할표 | I | `WalletFacadeTest`(2), 각 `ApiV1*ControllerTest#authorization`·`#roles` | 경계 (인증) | R-003 통과 (경계) |
| BT-SEC-05 | 분리 환경 인증 | member-app이 발급한 JWT를 Envoy가 검증해 넘긴 `X-User-Id`·`X-User-Role` 헤더로 billing-app이 사용자·관리자를 인증하고 `@PreAuthorize`로 인가한다. 헤더 없는 요청은 비로그인(401), 외부에서 붙인 `X-User-*`는 앱에 닿지 않는다 | #370~#372, 인프라 문서 3장 | T | 없음 | 깨짐 (현재 방식 미확인) | 없음 |
| BT-SEC-06 | HP 경로의 회원 상태 확인 | 토큰의 `role`만 믿지 않고 Billing 회원 복제본 상태를 함께 확인한다(정지·역할 변경이 토큰 수명만큼 늦게 반영되는 문제 방지). 대상 경로는 TBD(decisions 11장) | 인프라 문서 3장 인증 흐름 7, #371 | I | 없음 (현재 확인은 환전 승인뿐: BT-EXC-06) | 미확인 | 없음 |
| BT-ERR-01 | 오류 응답 | `ProblemDetail` + `code`, 검증 오류 항목, 403·405·500(내부 메시지 미노출) | conventions.md, BD-105, BD-106 | U, S | `GlobalExceptionHandlerTest`(15), `BusinessExceptionTest`(6) | 없음 | R-003 통과 |
| BT-ERR-02 | 페이지 응답 | 형식, 최대 100, 기본 20 | A14 | S | `PageConfigTest`(3) | 없음 | R-003 통과 |
| BT-DB-01 | 스키마 | Flyway 15개 테이블(#479 `hold_void` 포함) + Hibernate validate, TIMESTAMPTZ·BIGINT, FK는 Billing 내부만, 다른 도메인 테이블 없음 | module.md 8.2 | R | `BillingSchemaTest`(6) | 없음 (database 분리 시 다시 확인) | R-003 통과 |
| BT-DB-02 | 제약 | CHECK·UNIQUE·FK·트리거·멱등키 | INV-2~INV-8 | R | `BillingConstraintsTest`(33) | 없음 | R-003 통과 |
| BT-DB-03 | Enum 저장값 | CHECK 허용값 = Enum 상수 이름 | BD-37 | R | `BillingEnumStorageTest`(1) | 없음 | R-003 통과 |
| BT-DB-04 | 엔티티 저장 | 전체 엔티티 저장·조회, Instant 보존, 감사 시각, ID 참조 FK | BD-36 | R | `BillingRepositoryTest`(7) | 없음 | R-003 통과 |
| BT-CFG-01 | 배치 스위치 | `hapbang.scheduling.enabled` 조건 | — | U | `ConditionalOnSchedulingEnabledTest`(3) | 없음 | R-003 통과 |
| BT-CFG-02 | 실제 설정 기동 | 앱 `application.yaml`·프로필(local·dev·prod)과 필수 환경 변수로 기동, 누락 시 기대한 실패 | module.md 9장 | T | 없음 (`@IntegrationTest`는 앱 yaml을 읽지 않는다) | 미확인 | 없음 |
| BT-CFG-03 | 복구 배치 실행 | `ChargeRecoveryBatch`·`RefundRecoveryBatch`가 등록되고 UseCase에 위임 | BD-78, BD-92 | I | 없음 (UseCase만 검증) | 없음 | 없음 |
| BT-CFG-04 | Modulith 이벤트 기록 끔 (#471) | 이벤트 기록 빈(레지스트리·저장소)이 없고 `billing.event_publication`·`event_publication_archive` 테이블이 없다. 앱 설정과 테스트 설정이 같은 자동 설정을 뺀다 | BD-124 | I | `ModulithEventsDisabledTest`(1) | 없음 | 기록 없음 (#471에서 추가) |

## 5. JVM 분리 영향 정리

### 5.1 단일 JVM에서는 동작했지만 독립 JVM에서 동작하지 않을 수 있는 것

| 항목 | 단일 JVM에서의 동작 | 독립 JVM에서의 상태 (코드 기준) | 관련 BT | 관련 결정 |
| --- | --- | --- | --- | --- |
| ~~BillingPort 호출~~ | 강의·출연이 `BillingPort` 빈을 주입받아 Java로 호출 | 해소: 강의(#275~#277)·출연(#274)이 지갑 내부 HTTP API를 부르고, `BillingPort`·`BillingPortAdapter`는 #486에서 지웠다 | BT-PORT-01, 04, 05 | BD-51, BD-52 |
| 모듈 간 트랜잭션 경계 | 주문 저장 + 보류가 한 DB 트랜잭션, 실패하면 함께 롤백, 모듈 간 락 순서 | 원격 호출은 같은 트랜잭션에 참여할 수 없다. 부분 성공(보류만 되거나 주문만 됨)을 복구할 설계가 없다 | BT-PORT-02, 03 | BD-53, BD-60, BD-64, BD-72, BD-107 |
| 회원 이벤트 수신 | #430부터 Kafka `member.events.v1`을 소비 그룹 둘로 받는다. 멱등은 자연 키(BD-123) | 발행 쪽 #416이 병합돼야 실제로 이벤트가 온다. 앱 시작 전 가입자의 지갑은 조회 시 보완, 복제본은 Backfill로 채운다. billing의 Modulith 이벤트 기록 테이블·설정은 #471에서 지웠다 | BT-WAL-02, BT-USR-01·02, BT-EVT-01 | BD-2, BD-6, BD-103, BD-123 |
| 인증·인가 | member의 OAuth2 로그인 세션 principal을 같은 앱이 해석 | billing-app에 oauth2Login·spring-session 의존이 없다. 다른 앱의 로그인 세션을 해석할 방법이 코드에 없다(미확인). 목표는 JWT + `X-User-*` 헤더 인증이며, 그때 CSRF 설정·`@LoginUserId`가 바뀐다 | BT-SEC-01·02·04·05·06 | BD-3, BD-103 (#370~#372) |
| 내부 HTTP API | 수익 조회·Backfill은 이미 HTTP | Client는 분리 구조에 맞다. 대신 base-url 환경 변수가 필수다(기본값 없음). 제공자와의 계약 일치는 검증하지 않는다. Billing이 제공하는 내부 API는 없다 | BT-ERN-02·04, BT-USR-03·04, BT-SEC-03 | — |
| 멱등성·재시도 | 실패하면 호출자 트랜잭션과 함께 롤백 → 같은 요청 ID로 재요청 | Billing 내부 멱등(요청 ID 재생)은 그대로다. 하지만 원격 호출의 타임아웃·결과 불명 시 호출자가 같은 ID로 재시도하고 오류를 성공으로 해석하지 않는 규칙(BD-70)을 검증하는 테스트가 없다 | BT-PAY-03·04, BT-PORT-04 | BD-69, BD-70 |
| 외부 서비스 장애 | TossPayments·Mock 은행 장애는 이미 트랜잭션 밖 호출 + 복구 배치 | 변화 없음. 새로 생기는 장애 지점은 강의·출연·회원 앱 장애다(수익 조회 503은 검증됨, Billing 다운 시 호출자 동작은 Billing 범위 밖) | BT-CHG-04·05, BT-RFD-03, BT-EXP-03, BT-ERN-02 | BD-76, BD-89, BD-101 |
| 배치·단일 실행 | 앱 1개 | 분산 락 없음. Backfill 동시 실행 방지는 인스턴스 안에서만. 목표 배포는 replica 1개지만 rolling update(`maxSurge: 1`) 중 두 pod가 잠깐 겹칠 수 있다(추정, module.md 6.1) | BT-EXP-04·05, BT-USR-03 | — |
| 설정 | 공용 설정 | 앱별 yaml, 필수 환경 변수(내부 키·DB·Client URL). 실제 설정 기동 테스트 없음 | BT-CFG-02 | — |

### 5.2 영향별 개수

| JVM | 개수 | ID |
| --- | --- | --- |
| 없음 | 84 | 아래 셋을 뺀 나머지 |
| 경계 | 15 | BT-WAL-03·04, BT-CHG-09, BT-RFD-08, BT-EXC-06·07, BT-EXP-07·09, BT-ERN-02·03·04, BT-USR-01·03·04, BT-SEC-04 |
| 깨짐 | 3 | BT-PORT-04·05, BT-SEC-05 |
| 미확인 | 4 | BT-SEC-01, BT-SEC-02, BT-SEC-06, BT-CFG-02 |
| 합계 | 106 | |

개수는 4장 표를 기준으로 셌다(2026-10-29, #336·#453·#430·#471 반영 2026-10-30, #486 반영 2026-10-31). 표를 고치면 함께 고친다.

## 6. 빈 곳

### 6.1 테스트가 없거나 부족한 항목 (Billing 안)

| 항목 | 내용 | BT |
| --- | --- | --- |
| 판매자 지갑 없음 재지급 | 지갑 없음 거절·상태 불변은 `payout_invalidRequests`에 있다. 지갑 생성 뒤 같은 요청 ID 재지급은 없다 | BT-PAY-07 |
| 중복 지급 오류 순서 | 끝난 보류와 원천 중복이 함께 걸리는 경우를 시험하는 테스트가 없다 | BT-PAY-04 |
| 복구 배치 빈 | `ChargeRecoveryBatch`·`RefundRecoveryBatch` 등록·위임 테스트 없음(`@IntegrationTest`에서 스케줄링이 꺼짐, 추정) | BT-CFG-03 |
| 실제 설정 기동 | 앱 yaml·프로필로 기동하는 테스트 없음 | BT-CFG-02 |
| 레거시 SettlementStatus | SCHEDULED 등 레거시 행이 있을 때 `PAYOUT_ALREADY_SETTLED`. 테스트가 없다. SCHEDULED 지급 건 픽스처(`BillingFixture.settlement`)는 `BillingConstraintsTest`·`BillingRepositoryTest`에서만 쓴다(2026-10-29 코드 확인) | BD-71 |
| 이름으로 참조되지 않는 클래스 | `HoldCaptureUseCase`·`HoldPayoutUseCase`·`HoldReleaseUseCase`·`HoldProcessing`·`PenaltySettlementUseCase`·Facade·`ExchangeLocks`·`CaptureSplit`·`InternalHoldRequests`는 Facade·컨트롤러를 거쳐 간접 검증된다. 직접 테스트가 필요한지는 TBD | — |

### 6.2 Contract 단계 대상 (현재 테스트 없음)

| BT | 경계 | 제공자 | 소비자 | 선행 조건 |
| --- | --- | --- | --- | --- |
| BT-PORT-04 | 보류·반환·지급·위약금 | billing-app (#273, 서버 쪽은 `ApiV1BillingInternalControllerTest`) | lecture-app, appearance-app | #280 계약 확정 (Resilience4j 설정은 #411, `CONTEXT.md` 2장) |
| BT-PORT-05 | 소비 앱 기동 | — | lecture-app, appearance-app | #280 |
| BT-EVT-01 | 회원 이벤트 (`member.events.v1`) | member-app (#416) | billing-app (#430, 그룹 2개) | 발행 형식 #416(key 회원 ID, `__TypeId__` 헤더, `eventId` 없음). 소비 쪽은 `BillingMemberEventKafkaTest`가 같은 형식을 직접 보내 검증한다. #416 병합 뒤 실제 발행과 이어 보는 계약 검증이 남는다 |
| BT-SEC-05 | 인증 | member-app(JWT 발급), Envoy(검증·헤더 전달) | 모든 앱(헤더 인증 필터) | #370~#372 |
| BT-ERN-04 | 수익 조회 | lecture-app, appearance-app | billing-app | 지금 가능 (`docs/api/internal-earnings.md`) |
| BT-USR-04 | 회원 목록 | member-app | billing-app | 지금 가능 (`docs/api/internal-members.md`) |
| BT-CFG-02 | 실제 설정 기동 | billing-app | — | 지금 가능 |

Contract 테스트 방식(도구, 계약 파일 위치, 제공자·소비자 어느 레포·모듈에서 돌릴지)은 TBD다. 루트 AGENTS 11장의 미정 항목(테스트 방식)이므로 팀이 정한다.

참고로 정해진 것은 두 가지다. 인프라 문서 1장은 Kafka 이벤트 payload를 바꿀 때 최소 한 버전의 하위 호환 consumer contract test를 두기로 했고, 7장은 schema registry 서버를 두지 않기로 했다. 같은 문서 3장 "CI 검증"은 PR에서 앱 사이 JWT 헤더 인증·Kafka 흐름을 검증하는 것을 목표로 한다. 구체적인 도구는 아직 없다.

## 7. 테스트 클래스 목록

전체 메서드 이름은 다음 명령으로 뽑는다.

```bash
grep -rnE "@(Test|ParameterizedTest)" -A3 apps/billing-app/src/test/java | grep -E "void "
```

| 클래스 (`com.hapbang.billing.` 아래) | 종류 | 메서드 | Docker | BT |
| --- | --- | --- | --- | --- |
| app.BillingCommandValidatorTest | U | 6 | — | PEN-01·02 |
| app.BillingKeysTest | U | 7 | — | HLD-06, RFD-02 |
| app.BillingUserBackfillIntegrationTest | I | 14 | 필요 | USR-03 |
| app.BillingUserBackfillUseCaseTest | U (로컬 HTTP) | 13 | — | USR-03 |
| app.BillingUserSyncIntegrationTest | I | 6 | 필요 | USR-01 |
| app.ChargeIntegrationTest | I | 29 | 필요 | CHG-01~06·08 |
| app.ChargeUsageHistoryTest | U | 11 | — | REL-01·04, CAP-03 |
| app.EarningQueryUseCaseTest | U | 13 | — | ERN-01 |
| app.EarningSourceAdapterTest | U (로컬 HTTP) | 9 | — | ERN-02 |
| app.ExchangeAccountCipherTest | U | 3 | — | EXC-01 |
| app.ExchangeIntegrationTest | I | 21 | 필요 | EXC-01~03 |
| app.ExchangePayoutIntegrationTest | I (직접 `@SpringBootTest`) | 38 | 필요 | EXP-01~04·07~10 |
| app.ExchangeReviewIntegrationTest | I | 20 | 필요 | EXC-05·06 |
| app.ExchangeSettlementBatchConditionTest | U (ACR) | 3 | — | EXP-06 |
| app.ExchangeSettlementIntegrationTest | I (직접 `@SpringBootTest`) | 10 | 필요 | EXP-05 |
| app.ExchangeSettlementRoundTest | U | 7 | — | EXC-04 |
| app.ExchangeSettlementUseCaseTest | I | 6 | 필요 | EXP-05 |
| app.HoldCaptureIntegrationTest | I | 21 | 필요 | CAP-01~04 |
| app.HoldCreateIntegrationTest | I | 30 | 필요 | HLD-01~05, PORT-02 |
| app.HoldPayoutIntegrationTest | I | 39 | 필요 | PAY-01~06, PEN-01~04, PORT-02 |
| app.HoldReleaseIntegrationTest | I | 34 | 필요 | REL-01~05, PORT-02 |
| app.HoldVoidIntegrationTest | I | 8 | 필요 | REL-06 |
| app.HoldSplitTest | U | 6 | — | HLD-01 |
| app.LedgerOperationTest | U | 6 | — | LED-03 |
| app.LedgerRecordIntegrationTest | I | 27 | 필요 | LED-01·03·04·05·09 |
| app.LedgerRecordUseCaseTest | U | 2 | — | LED-04 |
| app.LedgerReversalIntegrationTest | I | 8 | 필요 | LED-06 |
| app.LockTimeoutsTest | U | 3 | — | — (#273 락 대기 상한, BT 미연결) |
| app.MockPayoutHttpIntegrationTest | I (직접 `@SpringBootTest`) | 19 | 필요 | MCK-01 |
| app.PayoutSplitTest | U | 2 | — | PAY-01 |
| app.RefundIntegrationTest | I | 39 | 필요 | RFD-01~07 |
| app.ReleaseSplitTest | U | 6 | — | REL-01 |
| app.WalletCreateUseCaseTest | U | 9 | — | WAL-01 |
| app.WalletCreationIntegrationTest | I | 11 | 필요 | WAL-01·02 |
| app.WalletDuplicateViolationTest | I | 5 | 필요 | WAL-01 |
| app.WalletFacadeTest | I | 2 | 필요 | WAL-03, SEC-04 |
| app.WalletReconciliationIntegrationTest | I | 6 | 필요 | LED-08 |
| app.WalletTransactionQueryIntegrationTest | I | 12 | 필요 | WAL-04 |
| domain.ExchangePayoutTest | U | 15 | — | EXP-01 |
| domain.ExchangeTest | U | 14 | — | EXC-01, EXP-10 |
| domain.MockPayoutTransferTest | U | 2 | — | MCK-01 |
| domain.PointChargeTest | U | 6 | — | CHG-02 |
| domain.PointHoldPayoutTest | U | 5 | — | PAY-01 |
| domain.PointHoldReleaseTest | U | 12 | — | REL-02 |
| domain.PointRefundTest | U | 5 | — | RFD-04~06 |
| domain.WalletApplyLedgerAccessTest | U (ArchUnit) | 1 | — | LED-02 |
| domain.WalletTest | U | 11 | — | LED-03, EXP-08·09 |
| global.auth.LoginUserIdArgumentResolverTest | U | 4 | — | SEC-02 |
| global.config.ModulithEventsDisabledTest | I | 1 | 필요 | CFG-04 |
| global.config.PageConfigTest | S | 3 | — | ERR-02 |
| global.config.SecurityConfigTest | S | 12 | — | SEC-01 |
| global.exception.BusinessExceptionTest | U | 6 | — | ERR-01 |
| global.exception.GlobalExceptionHandlerTest | S | 15 | — | ERR-01 |
| global.internal.InternalApiKeyFilterTest | U | 2 | — | SEC-03 |
| global.internal.InternalApiPortConfigTest | U (로컬 HTTP) | 2 | — | — (#372 내부 API 포트, BT 미연결) |
| global.internal.InternalApiPropertiesTest | U (ACR) | 4 | — | SEC-03 |
| global.internal.InternalApiSecurityConfigTest | S | 11 | — | SEC-03 |
| global.scheduling.ConditionalOnSchedulingEnabledTest | U (ACR) | 3 | — | CFG-01 |
| in.ApiV1BillingInternalControllerTest | I | 16 | 필요 | PORT-01~03, REL-06 (`voidHold`) |
| in.ApiV1SettlementEarningControllerTest | I | 10 | 필요 | ERN-03 |
| in.ApiV1SettlementExchangeAdminControllerTest | I | 4 | 필요 | EXC-07 |
| in.ApiV1SettlementExchangeControllerTest | I | 6 | 필요 | EXC-07 |
| in.ApiV1WalletChargeControllerTest | I | 7 | 필요 | CHG-09 |
| in.ApiV1WalletChargeUnavailableTest | I | 3 | 필요 | CHG-07 |
| in.ApiV1WalletControllerTest | I | 7 | 필요 | WAL-03 |
| in.ApiV1WalletRefundControllerTest | I | 6 | 필요 | RFD-08 |
| in.ApiV1WalletTransactionControllerTest | I | 7 | 필요 | WAL-04 |
| in.BillingFailureCodeContractTest | U | 2 | — | — (#273 오류 코드 대조, BT 미연결) |
| in.BillingMemberEventIsolationTest | I | 1 | 필요 | USR-02 |
| in.BillingMemberEventKafkaTest | I (Kafka) | 9 | 필요 | EVT-01, WAL-02, USR-02 |
| in.MockPayoutDeployedProfileTest | I (`@Nested` 5개) | 2 | 필요 | MCK-02 |
| in.MockPayoutDisabledTest | I | 3 | 필요 | MCK-02 |
| out.BillingConstraintsTest | R | 33 | 필요 | DB-02, LED-07, PAY-02 |
| out.BillingEnumStorageTest | R | 1 | 필요 | DB-03 |
| out.BillingRepositoryTest | R | 7 | 필요 | DB-04 |
| out.BillingSchemaTest | R | 6 | 필요 | DB-01 |
| out.MockPayoutActivationTest | U | 3 | — | MCK-02 |
| out.PaymentKeyCipherTest | U | 6 | — | CHG-06 |
| out.TossPaymentsClientTest | U (로컬 HTTP) | 8 | — | CHG-04 |

| 종류 | 클래스 | 메서드 |
| --- | --- | --- |
| U (ACR·로컬 HTTP·ArchUnit 포함) | 35 | 217 |
| S (`@WebMvcTest`) | 4 | 41 |
| R (`@RepositoryTest`) | 4 | 47 |
| I (`@IntegrationTest`·직접 `@SpringBootTest`) | 36 | 487 |
| 합계 | 79 | 792 |

## 8. 운영 규칙

### 8.1 이 문서를 고칠 때

| 상황 | 할 일 |
| --- | --- |
| 테스트 클래스 추가·삭제·종류 변경 | 7장 목록, 1.3 명령, 관련 `BT-*` 행 |
| 새 검증 대상 | 영역의 다음 번호로 `BT-*`를 만든다. 근거(`BD-*`·`INV-*`·policy 장)를 반드시 적는다 |
| 대상이 없어짐 | 행을 지우지 않고 상태를 "폐기"로 바꾼다 |
| 회귀 실행 | 결과는 regression-log에 쓰고, 여기서는 상태 칸만 실행 ID로 바꾼다 |

### 8.2 별도 검토 사항 (이번에는 하지 않음)

- `BT-*` ID를 테스트 메서드 이름이나 `@DisplayName`에 넣을지는 미정이다. 지금은 이 문서에서만 연결하고 기존 테스트 이름은 바꾸지 않는다. 테스트 컨벤션은 루트 AGENTS 11장의 미정 항목이다.
- 1.4 A안(태그 분리)은 팀 합의 뒤 별도 Issue로 진행한다.

### 8.3 Skill과의 역할 분리

| 산출물 | 위치 | 이 문서와의 관계 |
| --- | --- | --- |
| `/grill-with-docs` | `docs/GLOSSARY.md`, `docs/adr/` | 미결 정책·설계를 질문으로 정리한다. 정해진 결과는 decisions.md(`BD-*`)나 ADR로 남는다. 이 문서에는 근거 ID만 적는다 |
| `/to-spec` | GitHub 이슈(`ready-for-agent`) | 확정된 내용을 구현 명세로 만든다. 스펙의 Testing Decisions에서 이 문서의 `BT-*`를 가리키고, 이 문서의 상태 칸에는 이슈 번호를 적는다. 스펙 내용은 복사하지 않는다 |
