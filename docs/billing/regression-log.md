# 지갑(Billing) 모듈 — 회귀 실행 기록

> 문서 지도: [현재 구현](module.md) · [결정 기록](decisions.md) · [테스트 검증 기준](test-spec.md) · **[회귀 실행 기록](regression-log.md)**
>
> - 이 문서는 **실제로 실행한** 테스트 결과만 쌓는다. 검증 대상과 기준은 [test-spec.md](test-spec.md)에 있다.
> - 목표: JVM 분리(#356) 이후 기존 Billing 기능의 회귀 검증. 단계는 Compile → Unit → Integration → Contract 순서다.
> - **실행 기록은 3장에 있다(R-001부터).** 분리 이후 기준선은 **R-003**(HEAD 7dc89e0, U·S·I·R 전체 1,041건 통과)이다. Contract 단계는 테스트가 없어 아직 실행하지 않았다.
> - 5a4dc1a → da834b1 사이에 billing-app·`common/shared` 코드는 바뀌지 않았다. `common/test-support`에 Kafka·Redis Testcontainers 설정과 `testcontainers-kafka` 의존(api)이 추가됐다. billing 테스트는 이 설정을 `@Import`하지 않으며, R-003에서 Kafka·Redis 컨테이너가 뜨지 않은 것을 확인했다(`postgres:18`만 실행).

## 1. 작성 규칙

1. **실행하지 않은 것은 성공으로 적지 않는다.** 건너뛴 단계는 `미실행`으로 적고, 결과 칸을 비우지 않는다.
2. 숫자는 `apps/billing-app/build/test-results/test/TEST-*.xml`에서 센다. 콘솔 요약이나 기억으로 적지 않는다. `UP-TO-DATE`·`FROM-CACHE`로 건너뛴 태스크는 새 실행이 아니다(`--rerun-tasks` 또는 `cleanTest`).
3. 실행 하나마다 새 ID(`R-001`, `R-002`, …)를 쓴다. 새 기록은 3장 맨 위에 추가하고, 지난 기록은 고치지 않는다. 잘못 적었으면 새 기록에 정정을 남긴다.
4. 실패마다 원인 분류(2.2)를 하나 붙인다. 분류하지 못했으면 `미분류`로 두고 미해결 표(4장)에 올린다.
5. 실행이 끝나면 [test-spec.md](test-spec.md)의 해당 `BT-*` 상태 칸을 실행 ID로 바꾼다.
6. 환경 정보에 비밀 값(내부 API 키, 결제 키, DB 비밀번호)을 적지 않는다.
7. 작업 트리에 커밋하지 않은 변경이 있으면 "작업 트리 변경" 칸에 파일 목록을 적는다. 커밋 해시만으로는 재현할 수 없다.

## 2. 분류 기준

### 2.1 검증 단계

| 코드 | 단계 | 대상 (test-spec 2장) | 명령 (test-spec 1.2) |
| --- | --- | --- | --- |
| C | Compile | main·test 컴파일, 모듈 구조 | `./gradlew :billing-app:compileJava :billing-app:compileTestJava`, `./gradlew verifyModuleStructure` |
| U | Unit | Unit 32개 클래스 | test-spec 1.3 `--tests` 목록 |
| S | Slice | `@WebMvcTest` 4개 클래스 (선택) | test-spec 1.2 |
| I | Integration | Integration 33 + Repository 4 (Docker 필요) | `./gradlew :billing-app:test --rerun-tasks` |
| T | Contract | test-spec 6.2 | TBD |

### 2.2 실패 원인 분류

| 코드 | 분류 | 뜻 | 후속 |
| --- | --- | --- | --- |
| F-CODE | 코드 결함 | 운영 코드가 기대 동작과 다르다 (진짜 회귀) | Issue 생성, 수정 |
| F-JVM | 단일 JVM 전제 | 테스트나 코드가 같은 JVM·같은 트랜잭션·같은 프로세스 이벤트를 전제한다 | test-spec의 JVM 칸 "경계/깨짐" 확인, #280·#339·#370~#372 연결 |
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

## 3. 실행 기록

_새 기록은 아래 양식을 복사해 이 줄 바로 아래(가장 최근 기록 위)에 붙인다._

### R-003 · 2026-10-29 · U / S / I · 통과

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

### R-002 · 2026-10-29 · U · 통과

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

### R-001 · 2026-10-29 · U / S / I · 중단 (U·S 통과, I·R은 F-ENV)

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

<!--
### R-NNN · YYYY-MM-DD · <단계 코드> · <결과 표기>

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

**건수** (TEST-*.xml 기준)

| 실행 | 성공 | 실패 | 오류 | 건너뜀 |
| --- | --- | --- | --- | --- |
| | | | | |

**실패 목록**

| 테스트 (클래스#메서드) | BT | 오류 요약 (첫 줄) | 원인 분류 | 근거 | 조치·Issue |
| --- | --- | --- | --- | --- | --- |
| | | | | | |

**메모**: 다음 실행에서 확인할 것, 재현 조건 등
-->

## 4. 미해결 항목

실행 결과로 생긴 미해결 항목을 이 표에서 관리한다. 해결되면 지우지 않고 "해결" 칸에 실행 ID나 Issue를 적는다.

| 번호 | 처음 발견 | 테스트 / BT | 내용 | 원인 분류 | 관련 Issue | 상태 | 해결 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | R-001 | I·R 단계 전체 (37개 클래스) | Docker가 없어 Integration·Repository를 한 번도 끝까지 실행하지 못했다 | F-ENV | — | 해결 | R-003 |

### 4.1 실행 전 알려진 위험 (분석 기반, 실행 결과 아님)

아래는 코드·문서 분석으로 확인한 위험이다. **테스트 실패가 아니다.** 실행 결과로 확인되면 4장 표로 옮긴다.

| BT | 위험 | 예상 분류 | 관련 |
| --- | --- | --- | --- |
| BT-PORT-01~03 | 같은 JVM의 `BillingPort`·호출자 트랜잭션 참여를 검증하는 테스트는 Billing 단독에서 통과할 수 있다. 하지만 독립 JVM 운영을 보장하지 않는다 | F-JVM | #280 |
| BT-PORT-04·05 | 원격 보류·반환·지급 계약과 소비 앱 기동을 검증하는 테스트가 없다 | F-COMM | #280 |
| BT-EVT-01, BT-WAL-02, BT-USR-01·02 | 회원 이벤트가 JVM을 넘지 않는다. 테스트는 같은 JVM의 `Scenario.publish`로만 확인한다 | F-JVM / F-COMM | #339 |
| BT-SEC-01·02·05 | 테스트는 `TestLogin`(oauth2Login 후처리기)으로 인증을 흉내 낸다. 분리 환경의 실제 인증 경로는 미확인이다. 목표는 JWT + `X-User-*` 헤더라 전환되면 이 테스트들의 로그인 방식도 바뀐다 | F-COMM | #370~#372 |
| BT-SEC-06 | JWT 전환 후 HP 경로에서 복제본 회원 상태를 확인하는 테스트가 없다(요구 자체가 목표 단계) | F-COMM | #371 |
| BT-CFG-02 | `@IntegrationTest`는 앱 `application.yaml`을 읽지 않는다. 필수 환경 변수 누락은 테스트로 드러나지 않는다 | F-CFG | — |
| (전체 I 단계) | 이전 기록상 메모리 부족으로 모듈별로 나눠 실행한 적이 있다(PROGRESS 6장, #260) | F-ENV | — |

## 5. 분리 이전 기록 (참고)

분리 이전 단일 JVM 구조의 테스트 수는 `apps/billing-app/PROGRESS.md` 6장에 있다(마지막 #260, 2026-10-15, billing 486개·실패 0, 명령 `./gradlew :billing:test`). 그때는 Gradle 프로젝트가 `:billing`이었고 테스트 구성도 지금과 다르다. 따라서 **기준선으로 쓰지 않는다.** 분리 이후의 기준선은 첫 Integration 실행(`R-00N`)이다.
