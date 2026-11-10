# 지갑(Billing) 모듈 — 회귀 실행 기록

> 문서 탐색: [지갑 문서 INDEX](INDEX.md)

> 문서 지도: [현재 구현](module.md) · [결정 기록](decisions.md) · [테스트 검증 기준](test-spec.md) · **[회귀 실행 기록](regression-log.md)**
>
> - 이 문서는 **실제로 실행한** 테스트 결과만 쌓는다: 언제, 어느 커밋에서, 무슨 명령을 돌렸고, 결과가 어땠고, 실패는 무엇 때문이었나. 검증 대상과 합격 기준은 [test-spec.md](test-spec.md)에 있다.
> - 목표: JVM 분리(#356) 이후 기존 Billing 기능의 회귀 검증. 단계는 Compile → Unit → Integration → Contract 순서다.
> - 분리 이후 기준선은 **R-003**(커밋 7dc89e0, U·S·I·R 전체 1,041건 통과)이다. Contract 단계는 테스트가 없어 실행하지 않았다.
> - **실제 코드 회귀(F-CODE)로 분류한 기록은 0건이다**(2026-11-06 기준). 지금까지의 R 기록 3건은 Docker 환경 확인(R-001)과 분리 이후 기준선 확보(R-002·R-003)다.
> - **2026-10-29(R-003) 뒤로 이 문서 형식의 실행 기록이 없다.** 그 뒤 Issue별 실행은 옛 `PROGRESS.md` 6장(지금은 이 문서 5.1로 옮김)과 당시 test-spec 상태 칸에만 남았다. test-spec 상태 칸의 값은 3.3(H 기록)과 4.2로 옮겼다. R-003 이후 테스트가 크게 늘어 R-003은 지금 코드 전체를 덮지 않는다(4.2).
> - 5a4dc1a → da834b1 사이에 billing-app·`common/shared` 코드는 바뀌지 않았다. `common/test-support`에 Kafka·Redis Testcontainers 설정과 `testcontainers-kafka` 의존(api)이 추가됐다. R-003 당시 billing 테스트는 이 설정을 `@Import`하지 않았고, R-003에서 Kafka·Redis 컨테이너가 뜨지 않은 것을 확인했다(`postgres:18`만 실행). 지금은 Kafka 테스트 2개가 가져온다(test-spec 3장).

## 1. 기록 형식과 작성 규칙

### 1.1 필수 항목

실행 기록 하나는 아래 다섯 항목을 반드시 가진다. 3.1 요약 표에 한 줄, 3장 본문에 상세 블록(3.4 양식)으로 남긴다.

| 항목 | 적는 것 | 예 |
| --- | --- | --- |
| 날짜 | 실행 일시(KST). 모르면 "미기록"이라 적고 아는 근거 날짜를 괄호에 둔다 | 2026-10-29 11:22~11:25 |
| 커밋 | `git rev-parse --short HEAD`. 작업 트리 변경이 있으면 파일 목록도 적는다 | 7dc89e0 |
| 명령 | 실행한 명령 그대로 | `./gradlew :billing-app:test --rerun-tasks` |
| 결과 | 2.3 결과 표기 하나와 단계별 건수(XML 기준) | 통과, 1,041건 |
| 분류 | 실행 단계(2.1), 기록 목적(2.4), 실패마다 실패 원인 분류(2.2) | U·S·I, 기준선, 실패 없음 |

### 1.2 작성 규칙

1. **실행하지 않은 것은 성공으로 적지 않는다.** 건너뛴 단계는 `미실행`으로 적고, 결과 칸을 비우지 않는다.
2. 숫자는 `apps/billing-app/build/test-results/test/TEST-*.xml`에서 센다. 콘솔 요약이나 기억으로 적지 않는다. `UP-TO-DATE`·`FROM-CACHE`로 건너뛴 태스크는 새 실행이 아니다(`--rerun-tasks` 또는 `cleanTest`).
3. 직접 실행한 기록마다 새 ID(`R-001`, `R-002`, …)를 쓴다. 새 기록은 3.1 요약 표와 3.2 맨 위에 추가하고, 지난 기록은 고치지 않는다. 잘못 적었으면 새 기록에 정정을 남긴다.
4. 다른 문서에서 옮긴 기록은 `H-NNN`을 쓰고 "출처"를 적는다. XML·커밋·명령을 확인할 수 없으므로 기준선으로 쓰지 않는다. 새 실행 결과를 H 기록으로 만들지 않는다.
5. 실패마다 원인 분류(2.2)를 하나 붙인다. 분류하지 못했으면 `미분류`로 두고 미해결 표(4장)에 올린다.
6. 실행이 끝나면 4.2의 BT별 마지막 기록을 고친다. [test-spec.md](test-spec.md)는 고치지 않는다(실행 결과를 적지 않는 문서다).
7. 환경 정보에 비밀 값(내부 API 키, 결제 키, DB 비밀번호)을 적지 않는다.
8. 작업 트리에 커밋하지 않은 변경이 있으면 "작업 트리 변경" 칸에 파일 목록을 적는다. 커밋 해시만으로는 재현할 수 없다.

## 2. 분류 기준

### 2.1 검증 단계

| 코드 | 단계 | 대상 | 명령 (test-spec 1.2) |
| --- | --- | --- | --- |
| C | Compile | main·test 컴파일, 모듈 구조 | `./gradlew :billing-app:compileJava :billing-app:compileTestJava`, `./gradlew verifyModuleStructure` |
| U | Unit | test-spec 7.2의 U 행 | test-spec 1.3 `--tests` 목록 |
| S | Slice | test-spec 7.2의 S 행 (선택) | test-spec 1.2 |
| I | Integration | test-spec 7.2의 I·R 행 (Docker 필요) | `./gradlew :billing-app:test --rerun-tasks` |
| T | Contract | test-spec 6.2 | TBD |

클래스·메서드 수는 test-spec 7.2에서만 관리한다. 각 R 기록의 건수는 그때 실행한 값이다.

### 2.2 실패 원인 분류

| 코드 | 분류 | 뜻 | 후속 |
| --- | --- | --- | --- |
| F-CODE | 코드 결함 | 운영 코드가 기대 동작과 다르다 (진짜 회귀) | Issue 생성, 수정 |
| F-JVM | 단일 JVM 전제 | 테스트나 코드가 같은 JVM·같은 트랜잭션·같은 프로세스 이벤트를 전제한다 | test-spec의 JVM 칸 "경계/깨짐" 확인 |
| F-COMM | 미구현 통신 | 서비스 간 통신(내부 API, Kafka, 인증)이 아직 없다 | 해당 통신 Issue에 연결 |
| F-TEST | 테스트 결함 | 테스트 자체의 가정·데이터·타이밍 문제 (불안정 포함) | 테스트 수정 Issue |
| F-ENV | 환경 | Docker 없음, 메모리 부족, 포트 충돌, 네트워크 등 | 환경 기록 후 재실행 |
| F-CFG | 설정 | 필수 환경 변수·프로필·yaml 차이 | module.md 9장과 대조 |
| 미분류 | — | 원인을 아직 모른다 | 4장 미해결에 올린다 |

### 2.3 결과 표기

| 표기 | 뜻 |
| --- | --- |
| 통과 | 실행했고 실패·오류 0 |
| 실패 | 실패 또는 오류가 1건 이상 |
| 부분 | 일부 클래스만 실행했다 (범위 칸에 적는다) |
| 중단 | 빌드·컨텍스트·환경 문제로 끝까지 실행하지 못했다 |
| 미실행 | 계획했지만 실행하지 않았다 |

### 2.4 기록 목적

| 표기 | 뜻 |
| --- | --- |
| 기준선 | 이후 비교의 기준이 되는 전체 실행 |
| 회귀 확인 | 코드 변경 뒤 기존 기능이 그대로인지 본 실행 |
| 환경 확인 | 실행 환경(Docker·메모리 등)이 갖춰졌는지 본 실행 |
| 옮긴 기록 | 다른 문서에서 옮긴 결과(H 기록). 재현·검증할 수 없다 |

## 3. 실행 기록

### 3.1 요약

| ID | 날짜 | 커밋 | 명령 | 단계 | 결과 | 실패 원인 분류 | 기록 목적 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| R-006 | 2026-11-10 | 40c94d7b + 작업 트리(#822, R-005와 같음) | `./gradlew :billing-app:test --rerun-tasks` | U·S·I(R 포함) | 통과, 1,331건 | 실패 없음 | 회귀 확인(#822 커밋 전 전체) |
| R-005 | 2026-11-10 | 40c94d7b + 작업 트리(#822 리뷰 반영) | R-004와 같은 12개 클래스 `--rerun-tasks` | U·I(R 포함) | 통과, 74건 | 실패 없음 | 회귀 확인(#822 리뷰 반영) |
| R-004 | 2026-11-10 | 40c94d7b + 작업 트리(#822) | `./gradlew :billing-app:test --rerun-tasks --tests …`(12개 클래스) | U·I(R 포함) | 통과, 73건 | 실패 없음 | 회귀 확인(#822) |
| R-003 | 2026-10-29 | 7dc89e0 | `./gradlew :billing-app:test --rerun-tasks` | U·S·I(R 포함) | 통과, 1,041건 | 실패 없음 | 기준선 |
| R-002 | 2026-10-29 | 7dc89e0 | test-spec 1.3 Unit 명령(당시 24개 패턴) | U | 통과, 318건 | 실패 없음 | 기준선(Unit) |
| R-001 | 2026-10-29 | 5a4dc1a | `./gradlew check --continue --offline` | U·S·I | 중단, 366 통과·675 실패 | F-ENV 675건 | 환경 확인 |
| H-001~H-006 | 미기록 (2026-11-02~11-03) | 미기록 | 미기록 | 미기록 | 통과(당시 기록) | — | 옮긴 기록 (3.3) |

- F-CODE: 0건. F-ENV: 1회(R-001, 해결 R-003). 나머지 분류는 없다.

### 3.2 직접 실행한 기록 (R)

_새 기록은 3.4 양식을 복사해 이 줄 바로 아래(가장 최근 기록 위)에 붙이고, 3.1 요약 표에도 한 줄 더한다._

#### R-006 · 2026-11-10 · U·S·I(R 포함) · 통과

| 항목 | 값 |
| --- | --- |
| 실행 ID | R-006 |
| 실행 일시 | 2026-11-10 15:35~15:42 (KST) |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-822` |
| 커밋 | 40c94d7b |
| 작업 트리 변경 | R-005와 같다(#822 코드·테스트·문서, 커밋 전) |
| 단계 | U / S / I (R 포함) |
| 범위 | billing-app 전체 |
| 명령 | `./gradlew :billing-app:test --rerun-tasks` |
| 환경 | R-004와 같다 |
| 소요 시간 | 6분 32초 |
| 결과 | 통과 |
| 기록 목적 | 회귀 확인(#822 커밋 전 전체) |

**건수** (TEST-*.xml 107개 기준, `@Nested`는 파일이 따로 생긴다)

| 실행 | 성공 | 실패 | 오류 | 건너뜀 |
| --- | --- | --- | --- | --- |
| 합계 | 1,331 | 0 | 0 | 0 |

**실패 목록**: 없음.

**메모**: test-spec 7.2(102개 클래스·소스 메서드 948개)의 지금 테스트 전체를 덮는 첫 R 기록이다. 단계별 건수는 나누지 않았다. 전체 `./gradlew check`는 미실행이다(PR CI).

#### R-005 · 2026-11-10 · U·I(R 포함) · 통과

| 항목 | 값 |
| --- | --- |
| 실행 ID | R-005 |
| 실행 일시 | 2026-11-10 15:27 (KST) |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-822` |
| 커밋 | 40c94d7b |
| 작업 트리 변경 | R-004 목록 + 코드 리뷰 반영: 엔티티 팩토리 `create`·`updatable = false`·UNIQUE 선언, 복원이 이 인스턴스의 대사 결과를 덮지 않음·마지막 성공 시각은 더 큰 값만, 저장 실패 ERROR 표식 `[BILLING_RECONCILIATION_RECORD_FAILED]`, Flyway 부분 인덱스 제거, `ChangedWalletReconciliationUseCaseTest#restoreDoesNotOverwriteRunInThisInstance` 추가 |
| 단계 | U / I (R 포함) |
| 범위 | R-004와 같은 12개 클래스 |
| 명령 | R-004의 명령과 같다 |
| 환경 | R-004와 같다 |
| 소요 시간 | 55초 |
| 결과 | 통과 |
| 기록 목적 | 회귀 확인(#822 리뷰 반영) |

**건수** (TEST-*.xml 기준)

| 실행 | 성공 | 실패 | 오류 | 건너뜀 |
| --- | --- | --- | --- | --- |
| U (5개 클래스) | 28 | 0 | 0 | 0 |
| I (4개 클래스) | 19 | 0 | 0 | 0 |
| R (3개 클래스) | 27 | 0 | 0 | 0 |
| 합계 | 74 | 0 | 0 | 0 |

**실패 목록**: 없음.

**메모**: 새 테스트 `restoreDoesNotOverwriteRunInThisInstance`가 보호 로직 없이 실패하는 것을 먼저 확인했다(단위 14개 중 1개 실패). billing-app 전체 테스트와 `./gradlew check`는 미실행이다.

#### R-004 · 2026-11-10 · U·I(R 포함) · 통과

| 항목 | 값 |
| --- | --- |
| 실행 ID | R-004 |
| 실행 일시 | 2026-11-10 15:20 (KST) |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-822` |
| 커밋 | 40c94d7b |
| 작업 트리 변경 | 코드: `ChangedWalletReconciliationUseCase`, 새 `WalletReconciliationRunRecorder`·`WalletReconciliationMetricsRestorer`·`WalletReconciliationRun`·`WalletReconciliationRunWallet`·`WalletReconciliationResult`·`WalletReconciliationRunRepository`·`WalletReconciliationRunWalletRepository`, Flyway `V202611101530`. 테스트: `ChangedWalletReconciliationUseCaseTest`, `BillingSchemaTest`, `BillingEnumStorageTest`, 새 `WalletReconciliationRunIntegrationTest`·`WalletReconciliationMetricsRestorerTest`. 문서: decisions, test-spec, module, regression-log, billing AGENTS·PROGRESS, observability |
| 단계 | U / I (R 포함) |
| 범위 | #822 영향 범위 12개 클래스: BT-LED-10·11, BT-DB-01·03과 대사·스키마·저장소·아키텍처·지표 노출 관련 클래스 |
| 명령 | `./gradlew :billing-app:test --rerun-tasks --tests 'com.hapbang.billing.out.BillingSchemaTest' --tests 'com.hapbang.billing.out.BillingEnumStorageTest' --tests 'com.hapbang.billing.out.BillingRepositoryTest' --tests 'com.hapbang.billing.app.WalletReconciliationRunIntegrationTest' --tests 'com.hapbang.billing.app.ChangedWalletReconciliationIntegrationTest' --tests 'com.hapbang.billing.app.ChangedWalletReconciliationUseCaseTest' --tests 'com.hapbang.billing.app.WalletReconciliationMetricsRestorerTest' --tests 'com.hapbang.billing.app.WalletReconciliationIntegrationTest' --tests 'com.hapbang.billing.app.BillingBatchJobLockTest' --tests 'com.hapbang.billing.app.BillingSharedDtoAccessTest' --tests 'com.hapbang.billing.domain.WalletApplyLedgerAccessTest' --tests 'com.hapbang.billing.global.config.ManagementPortTest'` |
| 환경 | Windows 10 Pro, JDK 25.0.4.1, Gradle wrapper, Docker 29.8.0 실행 중(PostgreSQL Testcontainers) |
| 소요 시간 | 54초 |
| 결과 | 통과 |
| 기록 목적 | 회귀 확인(#822) |

**건수** (TEST-*.xml 기준)

| 실행 | 성공 | 실패 | 오류 | 건너뜀 |
| --- | --- | --- | --- | --- |
| U (5개 클래스) | 27 | 0 | 0 | 0 |
| I (4개 클래스) | 19 | 0 | 0 | 0 |
| R (3개 클래스) | 27 | 0 | 0 | 0 |
| 합계 | 73 | 0 | 0 | 0 |

**Unit 실행 목록 대조**: 부분 실행이라 하지 않았다(전체 Unit 명령 미실행).

**실패 목록**: 없음.

**메모**: 처음 쓴 테스트가 기대한 이유로 실패하는 것을 먼저 확인했다(새 타입이 없어 컴파일 실패 → 껍데기에서 단위 13개 중 4개 실패 → 엔티티만 있고 Flyway가 없어 스키마 테스트 실패). 변형 확인 2건: 저장·이월 읽기 예외 처리를 빼면 `ChangedWalletReconciliationUseCaseTest` 2건만 실패하고, Recorder 조회를 틀리게 하면(성공 조건 제거, FAILED → MISMATCHED) `WalletReconciliationRunIntegrationTest` 2건이 실패했다. 모두 되돌린 뒤 이 실행을 했다. billing-app 전체 테스트와 `./gradlew check`는 미실행이다.

#### R-003 · 2026-10-29 · U / S / I · 통과

| 항목 | 값 |
| --- | --- |
| 실행 ID | R-003 |
| 실행 일시 | 2026-10-29 11:22~11:25 (KST) |
| 실행자 | Claude Code |
| 브랜치 | dev |
| 커밋 | 7dc89e0 |
| 작업 트리 변경 | `docs/billing/test-spec.md`, `docs/billing/regression-log.md` (문서만) |
| 단계 | U, S, I (R 포함) |
| 범위 | billing-app 전체 73개 클래스 |
| 명령 | `./gradlew :billing-app:test --rerun-tasks` |
| 환경 | Windows 10 Pro 10.0.19045, JDK 25.0.4.1 (Oracle), Gradle 9.7.1, Docker 29.8.0, 실제로 뜬 컨테이너 `postgres:18`만(Kafka·Redis 없음), 시간대 Korea Standard Time, 메모리 미기록 |
| 소요 시간 | 3분 3초 |
| 결과 | 통과 |

**건수** (TEST-*.xml 77개 기준, 단계 구분은 7장 표)

| 실행 | 성공 | 실패 | 오류 | 건너뜀 |
| --- | --- | --- | --- | --- |
| U (32개 클래스) | 318 | 0 | 0 | 0 |
| S (4개 클래스) | 48 | 0 | 0 | 0 |
| I (33개 클래스, `@Nested` 포함) | 553 | 0 | 0 | 0 |
| R (4개 클래스) | 122 | 0 | 0 | 0 |
| 합계 | 1,041 | 0 | 0 | 0 |

**Unit 실행 목록 대조** (test-spec 2.2): 클래스 32/32, 소스 메서드 206 = 7장 표 206, 실행 318, 대조 문제 0.

**실패 목록**: 없음.

**메모**
- 분리 이후 billing-app 기준선이다. 실행 수(단계별)는 R-001의 실행 수와 같다(R-001에서 실패한 675건이 이번에는 모두 통과).
- 테스트 JVM 종료 때 `DefaultEventPublicationRegistry`가 "publications left unfinished"를 INFO로 9번 남겼다. 모두 `UserJoinedEvent` → `BillingMemberEventListener.on`이고, 각 캐시된 컨텍스트가 닫힐 때 1건씩이다. 실패한 이벤트가 남으면 찍힐 수 있는 로그로 알려져 있다(`docs/dev/testing.md` "이벤트 리스너"). 테스트 실패가 아니므로 분류하지 않았다. 어느 테스트가 남겼는지는 확인하지 않았다(후보: 복제본 실패를 일부러 일으키는 `BillingMemberEventIsolationTest`).
- 통과는 billing-app 한 앱 컨텍스트 안의 결과다. JVM 칸이 "경계"(18개)·"깨짐"(BT-PORT-01~03)인 항목은 통과해도 분리 운영을 보장하지 않는다(4.1). 교착(40P01) 로그는 없었다.

#### R-002 · 2026-10-29 · U · 통과

| 항목 | 값 |
| --- | --- |
| 실행 ID | R-002 |
| 실행 일시 | 2026-10-29 11:19~11:20 (KST) |
| 실행자 | Claude Code |
| 브랜치 | dev |
| 커밋 | 7dc89e0 |
| 작업 트리 변경 | `docs/billing/test-spec.md`, `docs/billing/regression-log.md` (문서만, 이 기록을 쓰는 중) |
| 단계 | U |
| 범위 | test-spec 1.3의 Unit 32개 클래스 |
| 명령 | test-spec 1.3 명령 그대로 (`./gradlew :billing-app:test --rerun-tasks --tests …` 24개 패턴) |
| 환경 | Windows 10 Pro 10.0.19045, JDK 25.0.4.1 (Oracle), Gradle 9.7.1, Docker 29.8.0 실행 중(Unit이라 컨테이너는 뜨지 않음), 시간대 Korea Standard Time, 메모리 미기록 |
| 소요 시간 | 33초 (Gradle 14개 태스크 모두 새로 실행) |
| 결과 | 통과 |

**건수** (TEST-*.xml 기준)

| 실행 | 성공 | 실패 | 오류 | 건너뜀 |
| --- | --- | --- | --- | --- |
| U (32개 클래스, 메서드 206개) | 318 | 0 | 0 | 0 |

**Unit 실행 목록 대조** (test-spec 2.2): XML 32개 = U 클래스 32개, 소스 메서드 206 = 7장 표 206, 실행 318, 대조 문제 0.

**실패 목록**: 없음.

**메모**: HEAD 기준 Unit 기준선이다. 실행 수 318은 R-001(5a4dc1a)과 같다. 대조는 테스트 소스와 XML을 비교하는 스크립트로 했고, 그 스크립트는 레포에 넣지 않았다(규칙은 test-spec 2.2).

#### R-001 · 2026-10-29 · U / S / I · 중단 (U·S 통과, I·R은 F-ENV)

| 항목 | 값 |
| --- | --- |
| 실행 ID | R-001 |
| 실행 일시 | 2026-10-29 09:51~09:52 (KST, XML timestamp 기준) |
| 실행자 | Claude Code |
| 브랜치 | dev |
| 커밋 | 5a4dc1a (**지금 HEAD가 아니다.** 5a4dc1a → 7dc89e0 사이 billing-app·`common/shared` 코드 변경은 없고, `common/test-support`에 Kafka·Redis Testcontainers 설정이 더해졌다) |
| 작업 트리 변경 | `apps/billing-app/AGENTS.md`, `apps/billing-app/PROGRESS.md` (문서만) |
| 단계 | U, S, I (전체 실행) |
| 범위 | 전체 (`check`로 모든 앱·common을 함께 실행, 이 기록은 billing-app만 옮겼다) |
| 명령 | `./gradlew check --continue --offline` |
| 환경 | Windows 10 Pro 10.0.19045, JDK 25.0.4.1 (Oracle), Gradle 9.7.1, **Docker 미실행**(컨테이너 0), 메모리·`TZ` 미기록 |
| 소요 시간 | 2분 51초 (전체 프로젝트 `check`) |
| 결과 | 중단: U·S는 통과, I·R은 Docker가 없어 컨텍스트를 띄우지 못했다 |

**건수** (TEST-*.xml 기준, 단계 구분은 7장 표)

| 실행 | 성공 | 실패 | 오류 | 건너뜀 |
| --- | --- | --- | --- | --- |
| U (32개 클래스, 메서드 206개) | 318 | 0 | 0 | 0 |
| S (4개 클래스, 메서드 41개) | 48 | 0 | 0 | 0 |
| I (33개 클래스) | 0 | 553 | 0 | 0 |
| R (4개 클래스) | 0 | 122 | 0 | 0 |
| 합계 | 366 | 675 | 0 | 0 |

**Unit 실행 목록 대조** (test-spec 2.2): 클래스 32/32, 소스 메서드 206 = 7장 표 206, 실행 318, 대조 문제 0.

**실패 목록**

| 테스트 (클래스#메서드) | BT | 오류 요약 (첫 줄) | 원인 분류 | 근거 | 조치·Issue |
| --- | --- | --- | --- | --- | --- |
| I·R 37개 클래스의 모든 메서드 (675건) | I·R 단계 전체 | `IllegalStateException: Failed to load ApplicationContext` | F-ENV | 25건은 원인이 `Could not find a valid Docker environment`, 나머지 650건은 같은 컨텍스트의 `ApplicationContext failure threshold` 초과 | Docker 실행 후 재실행 (4장 1번) |

**메모**: 코드 결함으로 분류할 실패는 없다. HEAD 기준 Unit 기준선은 R-002다. I·R 단계는 아직 한 번도 끝까지 실행하지 못했다.


### 3.3 옮긴 기록 (H)

2026-11-06 Billing 문서 정리에서 test-spec 4장 "상태" 칸에 있던 Issue 번호 실행 결과를 옮겼다. **출처: 당시 test-spec 상태 칸.** 실행 일시·커밋·명령·건수·환경은 상태 칸에 없었고, 이 정리에서 새로 실행하지 않았다. 날짜 괄호는 test-spec 변경 이력에 그 Issue가 반영된 날이다(실행일이 아니다). 같은 Issue의 실행 수치가 옛 `PROGRESS.md` 6장에 있을 수 있다. 그 기록은 R 형식(XML 기준, R-ID)으로 바꾸지 않고 5.1에 원문으로 둔다.

| ID | 날짜 | 커밋 | 명령 | 당시 상태 칸 값 | BT | 결과 | 분류 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| H-001 | 미기록 (#537 반영 2026-11-02) | 미기록 | 미기록 | `#537 통과`, `#537 통과 (앱 쪽)` | BT-SEC-01, BT-SEC-06 / BT-SEC-05 | 통과(당시 기록) | 옮긴 기록 |
| H-002 | 미기록 (#562 반영 2026-11-02) | 미기록 | 미기록 | `#562 통과` | BT-CFG-05 | 통과(당시 기록) | 옮긴 기록 |
| H-003 | 미기록 (#577 반영 2026-11-03) | 미기록 | 미기록 | `#577 통과` | BT-PORT-07 | 통과(당시 기록) | 옮긴 기록 |
| H-004 | 미기록 (#598 반영 2026-11-03) | 미기록 | 미기록 | `#598 통과` | BT-CFG-06 | 통과(당시 기록) | 옮긴 기록 |
| H-005 | 미기록 (#607 반영 2026-11-03) | 미기록 | 미기록 | `#607 통과` | BT-LED-10 | 통과(당시 기록) | 옮긴 기록 |
| H-006 | 미기록 (#627 반영 2026-11-03) | 미기록 | 미기록 | `#627 통과` | BT-CFG-07 | 통과(당시 기록) | 옮긴 기록 |

### 3.4 양식

<!--
#### R-NNN · YYYY-MM-DD · <단계 코드> · <결과 표기>

| 항목 | 값 |
| --- | --- |
| 실행 ID | R-NNN |
| 실행 일시 | YYYY-MM-DD HH:mm (KST) |
| 실행자 | 사람 / Claude Code / Codex CLI |
| 브랜치 | |
| 커밋 | (git rev-parse --short HEAD) |
| 작업 트리 변경 | 없음 / 파일 목록 |
| 단계 | C / U / S / I / T |
| 범위 | 전체 / 클래스·BT 목록 |
| 명령 | (그대로 복사) |
| 환경 | OS, JDK(`java -version`), Gradle, Docker 버전·실행 여부, 메모리, `TZ`, 실제로 뜬 컨테이너(postgres:18 / kafka / redis) |
| 소요 시간 | |
| 결과 | 통과 / 실패 / 부분 / 중단 / 미실행 |
| 기록 목적 | 기준선 / 회귀 확인 / 환경 확인 |

**건수** (TEST-*.xml 기준)

| 실행 | 성공 | 실패 | 오류 | 건너뜀 |
| --- | --- | --- | --- | --- |
| | | | | |

**Unit 실행 목록 대조** (test-spec 2.2): 클래스 n/n, 소스 메서드 n = test-spec 7장 n, 실행 n, 대조 문제 n.

**실패 목록**

| 테스트 (클래스#메서드) | BT | 오류 요약 (첫 줄) | 원인 분류 | 근거 | 조치·Issue |
| --- | --- | --- | --- | --- | --- |
| | | | | | |

**메모**: 다음 실행에서 확인할 것, 재현 조건 등
-->

## 4. 미해결 항목과 BT별 기록

실행 결과로 생긴 미해결 항목을 이 표에서 관리한다. 해결되면 지우지 않고 "해결" 칸에 실행 ID나 Issue를 적는다.

| 번호 | 처음 발견 | 테스트 / BT | 내용 | 원인 분류 | 관련 Issue | 상태 | 해결 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | R-001 | I·R 단계 전체 (37개 클래스) | Docker가 없어 Integration·Repository를 한 번도 끝까지 실행하지 못했다 | F-ENV | — | 해결 | R-003 |

### 4.1 실행 전 알려진 위험 (분석 기반, 실행 결과 아님)

R-001~R-003 무렵 코드·문서 분석으로 본 위험이다. **테스트 실패가 아니다.** 실행 결과로 확인되면 4장 표로 옮긴다. 2026-11-06에 뒤 Issue·결정과 대조해 상태를 갱신했다(실행으로 확인한 것이 아니다).

| BT | 위험 (2026-10-29 당시) | 예상 분류 | 관련 | 상태 | 해소 근거 |
| --- | --- | --- | --- | --- | --- |
| BT-PORT-01~03 | 같은 JVM의 `BillingPort`·호출자 트랜잭션 참여를 검증하는 테스트는 Billing 단독에서 통과할 수 있다. 하지만 독립 JVM 운영을 보장하지 않는다 | F-JVM | #280 | 해소 | #486: `BillingPort`·`BillingPortAdapter`·`BillingPortIntegrationTest` 삭제, 내부 API 테스트(`ApiV1BillingInternalControllerTest`)로 옮김(BD-128). #655(2026-11-04): 다른 앱 트랜잭션 참여 전제는 요청 단위 트랜잭션으로 바뀜(BD-143, BD-53·60 일부 대체, BD-107 폐기), 모듈 간 락 순서도 폐기(BD-72 → BD-142). test-spec JVM 칸 "없음" |
| BT-PORT-04·05 | 원격 보류·반환·지급 계약과 소비 앱 기동을 검증하는 테스트가 없다 | F-COMM | #280 | 일부 해소 | BT-PORT-05: #280에서 소비 앱이 Java Port 없이 k3s에 단독 기동(자동 테스트는 없음). BT-PORT-04: 호출 쪽 분류·재시도는 shared `BillingApiClientTest`(#411)가 보지만, 두 앱을 잇는 계약 테스트는 아직 없다(test-spec 6.2) |
| BT-EVT-01, BT-WAL-02, BT-USR-01·02 | 회원 이벤트가 JVM을 넘지 않는다. 테스트는 같은 JVM의 `Scenario.publish`로만 확인한다 | F-JVM / F-COMM | #339 | 일부 해소 | #430: Kafka 소비 그룹 둘로 바뀌고 `BillingMemberEventKafkaTest`(Kafka Testcontainers)가 검증(BD-123). `Scenario.publish` 테스트는 없앰. 발행 쪽 #416 병합(PR #465). member-app 실제 발행과 잇는 계약 테스트는 없다(test-spec 6.2) |
| BT-SEC-01·02·05 | 테스트는 `TestLogin`(oauth2Login 후처리기)으로 인증을 흉내 낸다. 분리 환경의 실제 인증 경로는 미확인이다. 목표는 JWT + `X-User-*` 헤더라 전환되면 이 테스트들의 로그인 방식도 바뀐다 | F-COMM | #370~#372 | 앱 쪽 해소 | #537: `GatewayLogin`(사용자 헤더)으로 바꿔 실제 `GatewayUserHeaderFilter`를 거친다. `GatewayUserHeaderFilterTest` 추가(BD-131). Envoy JWT 검증·외부 헤더 제거는 Gateway 테스트 몫이라 앱 테스트로는 남는다(test-spec 6.1) |
| BT-SEC-06 | JWT 전환 후 HP 경로에서 복제본 회원 상태를 확인하는 테스트가 없다(요구 자체가 목표 단계) | F-COMM | #371 | 해소 | #537: 충전·환불·환전 신청의 지갑 회원 확인 구현과 `walletMember_*` 테스트(BD-131) |
| BT-CFG-02 | `@IntegrationTest`는 앱 `application.yaml`을 읽지 않는다. 필수 환경 변수 누락은 테스트로 드러나지 않는다 | F-CFG | — | 남음 | 실제 설정 기동 테스트 없음. 보안 자동 설정 범위만 `DefaultSecurityUserTest`(#733, BT-SEC-08)가 실제 설정 파일을 읽는다 |
| (전체 I 단계) | 이전 기록상 메모리 부족으로 모듈별로 나눠 실행한 적이 있다(옛 PROGRESS 6장 → 5.1, #260) | F-ENV | — | 확인 안 됨 | R-003(billing-app만)에서는 생기지 않았다. 전체 프로젝트 `check`에서 다시 생기는지는 이 문서 형식으로 기록된 실행이 없다 |

### 4.2 BT별 마지막 기록

**출처: 당시 test-spec 상태 칸**(2026-11-06 정리 전 값). 이 정리에서 새로 실행한 것은 없다. 앞으로 R 기록을 남기면 해당 BT를 이 표에서 그 R-ID로 옮긴다.

R-003(7dc89e0)은 그때 있던 73개 클래스의 실행이다. R-003 뒤에 검증 기준이나 테스트가 바뀐 BT(예: #453의 BT-RFD-01·BT-EXC-01, #336의 BT-LED-03·08, #486의 BT-CHG-08, #537의 인증 방식)는 "R-003"이 지금 기준과 지금 테스트를 검증했다는 뜻이 아니다. test-spec 7.2의 지금 테스트 전체를 덮는 R 기록은 없다.

| 마지막 기록 | BT | 당시 상태 칸 값 |
| --- | --- | --- |
| R-005 | BT-LED-10, BT-LED-11, BT-DB-01, BT-DB-03 | (R-005 새 실행, 상태 칸 값 없음) |
| R-003 | BT-WAL-01, BT-LED-01~09, BT-HLD-01~06, BT-REL-01~05, BT-PAY-01·02·03·05·06, BT-PEN-01~04, BT-CAP-01~04, BT-CHG-01~07, BT-RFD-01~07, BT-EXC-02~05, BT-EXP-01~06, BT-MCK-01·02, BT-ERN-01, BT-SEC-03, BT-ERR-01·02, BT-DB-02·04, BT-CFG-01 | `R-003 통과` |
| R-003 | BT-WAL-03·04, BT-CHG-08·09, BT-RFD-08, BT-EXC-01·06·07, BT-EXP-07, BT-ERN-02·03, BT-USR-01·03, BT-SEC-04 | `R-003 통과 (경계)` |
| R-003 | BT-SEC-02 | `R-003 통과 (JVM 영향 미확인)` |
| R-003 | BT-PAY-07 | `R-003 통과 (일부만 검증, 6.1)` |
| H-001 | BT-SEC-01, BT-SEC-06 | `#537 통과` |
| H-001 | BT-SEC-05 | `#537 통과 (앱 쪽)` |
| H-002 | BT-CFG-05 | `#562 통과` |
| H-003 | BT-PORT-07 | `#577 통과` |
| H-004 | BT-CFG-06 | `#598 통과` |
| H-006 | BT-CFG-07 | `#627 통과` |
| 기록 없음 | BT-PORT-01~03 | `기록 없음 (R-003 이후 HTTP로 이동)` |
| 기록 없음 | BT-PORT-06, BT-REL-06, BT-EVT-02, BT-EXP-08~10 | `기록 없음 (R-003 이후 추가)` |
| 기록 없음 | BT-WAL-02, BT-USR-02 | `기록 없음 (#430에서 Kafka로 바뀜)` |
| 기록 없음 | BT-EVT-01 | `기록 없음 (#430에서 추가)` |
| 기록 없음 | BT-CFG-03 | `일부 (#598 위임만)` |
| 기록 없음 (테스트 없음) | BT-PAY-04, BT-PORT-04·05, BT-ERN-04, BT-USR-04, BT-CFG-02 | `없음` |
| — | BT-CFG-04 | `폐기 (#497)` (test-spec에서 대체됨 → BT-EVT-02) |
| 기록 없음 (2026-11-06 새 BT) | BT-WAL-05~08, BT-PORT-08, BT-SEC-07·08, BT-CFG-08~10 | 상태 칸 값 없음 |

## 5. 분리 이전 기록과 다른 문서의 실행 수치 (참고)

- 분리 이전 단일 JVM 구조의 테스트 수는 아래 5.1(옛 PROGRESS 6장)에 있다(분리 이전 마지막은 #260, 2026-10-15, billing 486개·실패 0, 명령 `./gradlew :billing:test`). 그때는 Gradle 프로젝트가 `:billing`이었고 테스트 구성도 지금과 다르다. 따라서 **기준선으로 쓰지 않는다.** 분리 이후의 기준선은 R-003이다.
- 5.1에는 분리 이후 Issue별 실행 수치도 있다(#273 2026-10-29 커밋 29d1ea5 `check` billing 1,125건, #674 2026-11-04 바뀐 부분만 실행 등). R-ID·XML 대조·환경 기록이 없어 R 형식으로 바꾸지 않고 5.1에 원문으로 두며, 기준선으로 쓰지 않는다. 같은 날짜의 R-003(7dc89e0, 1,041건)과 커밋이 다르다.

### 5.1 옮긴 기록: 옛 `PROGRESS.md` 6장 (원문 그대로)

2026-11-06 Billing 문서 정리에서 `apps/billing-app/PROGRESS.md` 6장의 Issue별 실행 수치와 "망가뜨려 실패 확인" 기록을 이 절로 옮겼다. 문장은 원문 그대로다. 명령의 `:billing:test`는 분리 이전 Gradle 프로젝트 이름이고, `TestLogin`·`BillingPort`·강의 지급 예정(`LectureSettlement*`) 등은 당시 코드다. R-ID·XML 대조·환경 기록이 없으므로 **기준선으로 쓰지 않는다.**

- 2026-11-04 기준(#674, `feature/tkt-674`, origin/dev ef093e99): 바뀐 부분만 실행 — `WalletMaskingIntegrationTest` 11, `WalletMaskingStatusesTest` 1, `ExchangeAccountCipherTest` 4(+1), `WalletTest` 14(+3), `WalletWithdrawalIntegrationTest` 17, `BillingMemberEventKafkaTest` 11(+1), `ClosedWalletBalanceMetricsTest` 2, `BillingConstraintsTest` 95, `BillingEnumStorageTest` 1 통과. 전체 `check`는 돌리지 않았다.
- 2026-10-29 기준(#273, `feature/tkt-273`, origin/dev 29d1ea5): `./gradlew check --continue` 성공 — billing 1,125(+84), lecture 567, appearance 570, member 320, chat 247, customer-support 181, shared 82, test-support 4, 실패 0. 첫 `check`에서 테스트 인증 설정 문제(다른 스레드로 인증 미전달 17, 클래스 단위 인증이 비로그인 요청에 섞임 1)로 billing 18건이 실패해 고친 뒤 다시 실행했다.
- 2026-10-15 기준(#260, `feature/tkt-260`, origin/dev 4eb55ea): 메모리 부족으로 `clean check`가 중간에 멈춰 모듈별로 나눠 실행했다 — billing 486, appearance 401, apps 56(ModularityTests·강의·출연 실제 Billing 포함), chat 142(이상 `clean check` 중 완료), shared 35, global 59, member 206, customersupport 112, lecture 421(이후 따로 실행), 전체 1,918개·실패 0. 정리 3건(주석·미사용 코드·배치 로그) 뒤에는 환불 테스트 39개만 다시 돌렸다. 실제 TossPayments 호출 없음.
- 2026-10-15 기준(#241, `feature/tkt-241`, origin/dev 5d58307): `./gradlew clean :billing:test` Billing 437개, `./gradlew clean check --continue` 성공 — shared 35, lecture 421, appearance 372, member 206, chat 142, customersupport 112, apps 56(강의 지급·주문 실제 Billing 29·2, 출연 BillingPort 6, ModularityTests 2), global 59, 전체 1,840개·실패 0. 첫 `check`에서 lecture `OrderPayoutUseCaseTest`(#150, 시각 정밀도, Fake billing) 1건이 실패했고 재실행에서 통과(기존 불안정 테스트, #241과 무관).
- 2026-10-15 기준(#224 2차 검토): `./gradlew clean :billing:test` Billing 410개(`ChargeIntegrationTest` 30, +7), `./gradlew check` 성공, 전체 1,686개·실패 0(`ModularityTests` 포함). 실제 TossPayments 호출 없음.
- 2026-10-15 기준(#224, `feature/tkt-224`, origin/dev 4d91633): `./gradlew clean :billing:test` Billing 403개(+44: `ChargeIntegrationTest` 23, `ApiV1WalletChargeControllerTest` 7, `ApiV1WalletChargeUnavailableTest` 1, `TossPaymentsClientTest` 5, `PaymentKeyCipherTest` 6, `PointChargeTest` +2), `./gradlew check` 성공 — shared 35, lecture 389, appearance 297, member 206, chat 123, customersupport 112, global 59, apps 55, 전체 1,679개·실패 0(`ModularityTests` 포함). 실제 TossPayments 호출 없음(가짜 HTTP 서버).
- 2026-10-15 기준(3.14 2차 검토, `feature/tkt-211`): `./gradlew clean :billing:test` Billing 359개, `./gradlew check` 성공 — apps 55(`LectureOrderPayoutIntegrationTest` 28), 나머지 모듈 수는 아래 1차와 같음, 전체 1,538개·실패 0·로그 40P01 0건(`ModularityTests` 포함).
- 2026-10-15 기준(3.14 강의 지급 연동 검증, origin/dev 70ecb2c): `./gradlew clean :billing:test` Billing 359개, `./gradlew check` 성공 — Billing 359, shared 35, lecture 382, appearance 247, member 166, chat 123, customersupport 112, global 59, apps 50(`LectureOrderPayoutIntegrationTest` 23, +4), 전체 1,533개 통과·실패 0(`ModularityTests` 포함).
- 2026-10-14 기준(#188 2차 검토): Billing 359개, shared 35개, lecture 318개, appearance 247개, apps 40개, 전체 1370개 통과(`ModularityTests` 포함).
- 2026-10-14 기준(#188): Billing 359개, shared 35개, lecture 318개, appearance 247개, apps 37개, 전체 1367개 통과(`./gradlew clean :billing:test`, `./gradlew check`, `ModularityTests` 포함). Billing·shared 수가 줄어든 것은 #142·#182 강의 지급 예정 전용 테스트(Billing 51개, shared 1개)를 지웠기 때문이다. 새 `LectureOrderPayoutIntegrationTest` 13개.
- 2026-10-14 기준(#182 2차 검토): Billing 410개, shared 36개, lecture 315개, appearance 219개, apps 30개, 전체 1381개 통과(`./gradlew clean :billing:test`, `./gradlew check`, `ModularityTests` 포함).
- 2026-10-14 기준(#182): Billing 410개, shared 36개, lecture 315개, appearance 215개, apps 27개, 전체 1374개 통과(`./gradlew clean :billing:test`, `./gradlew check`, `ModularityTests` 포함). #182 추가: Billing `LectureSettlementPortIntegrationTest` 3, shared 1, lecture 2(+기존 테스트에 등록·취소 확인), apps `LectureOrderSettlementIntegrationTest` 5·`AppearanceBillingPortIntegrationTest` 4.
- 2026-10-14 기준(#142 2차 검토): Billing 407개, 전체 1217개 통과(`./gradlew clean :billing:test`, `./gradlew check`). 2차 추가 5개: `holdReferenceMismatch`, `holdWithHistory`, `concurrentResumeAndCancel`, `immediatePayoutBlockedWhenSuspendedOrCancelled`, `cancelOrReleaseFailureRollsBackBoth`(`LectureSettlementIntegrationTest` 21개), `rejects`에 실패 후 금융 상태 불변 확인을 더했다.
- 2026-10-14 기준(#142): Billing 402개, shared 35개, 전체 1212개 통과(`./gradlew clean :billing:test`, `./gradlew check`, `ModularityTests` 포함). #142 추가 43개: `LectureSettlementIntegrationTest` 16(등록·HP 무변동·거절·멱등성·동시 등록·원천 UNIQUE·중단/재개/취소·완료 건 전이 거절·동시 중단/취소·즉시 지급 우회 차단·반환 + 취소 한 트랜잭션), `LectureSettlementScheduleTest` 15(KST 지급 가능 시각), `SettlementTest` 12(상태 전이·지급 가능 판단). 전체 수가 늘어난 데는 dev에 들어온 다른 모듈 테스트도 포함된다.
- 2026-10-14 기준(#145 2차 검토): Billing 359개, shared 35개, 전체 1034개 통과(`ModularityTests` 포함). 1차 구현 때는 Billing 357개, 전체 1032개였다 (`./gradlew clean :billing:test`, `./gradlew check`, Gradle 테스트 결과 XML 합계). Billing 수가 줄어든 것은 요청 DTO의 구조 검사 테스트를 shared(`BillingCommandsTest`)로 옮기고 키·금액 테스트를 `BillingKeysTest`·`BillingCommandValidatorTest`로 나눴기 때문이다.
- 2026-10-14 기준(#141): Billing 367개, 전체 1014개 통과 (`./gradlew clean :billing:test`, `./gradlew check`, Gradle 테스트 결과 XML 합계).
- #141: `HoldCreateIntegrationTest`에 결정적 경쟁 테스트 3개를 더했다(30개). `@MockitoSpyBean`으로 늦은 요청이 원장·보류 조회를 n번째 마친 직후 `CountDownLatch`로 멈추게 하고 그 사이 다른 지갑의 보류를 커밋한다(조회 순서와 무관하게 "첫 조회 뒤"·"두 조회 뒤"를 고른다).
    - `lookupRace_committedBetweenLookups_conflict`: 첫 조회 뒤 커밋 → `IDEMPOTENCY_KEY_CONFLICT`(INSERT 전 `replay()`에서 거절). **수정 전 조회 순서(보류 → 원장)로 되돌리면 `HOLD_STATE_INCONSISTENT`로 실패함을 확인했다(재현).**
    - `lookupRace_committedAfterLookups_uniqueViolation`: 두 조회 뒤 커밋 → 실제 PostgreSQL UNIQUE 위반(23505, `uk_point_hold_idempotency_key`)이 `IDEMPOTENCY_KEY_CONFLICT`로 바뀐다.
    - `concurrentInsert_uncommittedHold_uniqueViolation`: 상대가 보류 INSERT 후 커밋 전 대기 → 늦은 INSERT가 `pg_locks` 대기 후 UNIQUE 위반 → `IDEMPOTENCY_KEY_CONFLICT`.
    - 세 테스트 모두 진 지갑의 지갑·충전 원금·보류·배분·원장이 그대로이고, 이긴 지갑 재요청은 처음 보류(`replayed`), 진 지갑 재요청은 계속 충돌임을 확인한다. UNIQUE 위반 변환을 빼면 UNIQUE 테스트 2개와 `concurrentSameReferenceDifferentWallets`가 실패함을 확인했다.
- #134: `HoldPayoutIntegrationTest`(정상 지급·혼합 이력·위약금·롤백·동시성, 2차 검토에서 모집 게스트별 위약금·정상 지급 혼합과 같은 원천의 NORMAL·PENALTY 상호 배제, 3차 검토에서 위약금 0 거절·15,000 HP 위약금·1:1 계약 1회 정산·보류 간 같은 계약 재정산 거절 추가, 33개). 다른 보류의 같은 계약 확인·1:1 1회 확인·위약금 0 거절을 각각 빼면 실패함을 확인했다. 4차 검토에서 다른 호스트 보류의 같은 계약 동시 지급(결정적·혼합 동시) 테스트와 `BillingConstraintsTest`의 원천 UNIQUE 테스트를 더했고, 마이그레이션의 UNIQUE나 그 오류 변환을 빼면 실패함을 확인했다, `PayoutCommandTest`(지급 배분·위약금 계산·키), `PointHoldPayoutTest`, `ChargeUsageHistoryTest`(반환·지급 혼합 재생). 지급 수익 우선 → 충전 우선, FIFO 소진 → 역순, 자기 지급 확인·기준액 확인·재요청 기준액 비교 제거, 받는 지갑을 잠그지 않음(교착)을 각각 넣으면 실패함을 확인했다. 같은 유형의 지급 원천 중복은 앱 확인을 빼도 DB UNIQUE가 막지만, NORMAL·PENALTY 교차 중복은 앱 확인만 막는다(빼면 `sameSource_normalAndPenaltyExclusive`가 실패, 1차 구현에서 재현).
- #127: `ApiV1WalletControllerTest`가 `RsData` 구조(`statusCode`·`message`·`data` 필드 7개), 403 `ProblemDetail`(`code` FORBIDDEN, `RsData` 필드 없음), 401에 성공 본문 없음, Swagger 200 스키마 `RsDataWalletResponse`를 확인한다.
- #116 추가: `BillingEnumStorageTest`(Enum 상수 = CHECK 허용값), `BillingSchemaTest`의 ID 필드 13개 컬럼 이름·NULL 제약·FK 대조, `BillingRepositoryTest`의 ID 참조 FK 거절(원장 지갑·원본 원장, 충전 건 지갑, 보류 지갑, 배분 충전 건), `WalletTest`의 ID 없는 지갑 반영 거절. `Wallet.applyLedger`의 지갑 ID 검사를 없애면 실패함을 확인했다.
- `lockWallet` 테스트(트랜잭션 필수·refresh·커밋까지 락 유지)는 refresh·`MANDATORY`를 제거하면 실패함을 확인했다.
- 보류 테스트: `HoldCreateIntegrationTest`(배분·멱등성·롤백·동시성 30개, #141에서 3개 추가), `HoldCommandTest`, `HoldSplitTest`, `PointChargeTest`.
- 반환 테스트: `HoldReleaseIntegrationTest`(부분·전체·멱등성·동시성·비정상 상태·정합성·롤백), `ReleaseCommandTest`, `ReleaseSplitTest`, `ChargeUsageHistoryTest`, `PointHoldReleaseTest`. 지급 기능이 없어 지급된 보류는 JDBC(상세·보류) + `record()`(원장)로 만든다. 역순→FIFO, 원금 대조·재생 대조·지갑 락·보류/배분/충전 건 refresh를 각각 제거하면 실패함을 확인했다. 저장 실패 롤백은 테스트 안에서 행 트리거(`RAISE EXCEPTION`)를 잠시 걸어 만든다. 충전 API가 없어 충전 건은 JDBC INSERT + 충전 원장 `record()`로 만든다.
