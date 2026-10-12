# Billing 모듈 진행 기록

Billing 작업을 이어받는 사람과 AI 에이전트(Claude Code, Codex 등)가 작업 전에 읽는 문서다.
규칙은 루트 `AGENTS.md`와 `boundedContext/billing/AGENTS.md`가 우선이고, 이 문서는 지금까지 **무엇을 만들었고 왜 그렇게 정했는지**를 기록한다.

- 작업을 시작하기 전에 이 문서를 읽는다.
- Issue 하나를 끝내면 "1. 진행 현황", "3. 결정 기록", "7. 남은 일"을 갱신한다.
- 코드와 이 문서가 다르면 코드가 맞다. 확인 후 이 문서를 고친다.

마지막 갱신: 2026-10-12 (Issue #57 dev 병합 후)

---

## 1. 진행 현황

| Issue | 내용 | 브랜치 | PR (dev 병합) | 상태 |
| --- | --- | --- | --- | --- |
| #39 | Entity 12개·Enum 18개·Repository 12개, Flyway 초기 스키마, 원장 UPDATE·DELETE 차단 트리거 | `feature/tkt-39` | #41 | 완료 |
| #42 | 회원 지갑 생성(가입 이벤트 + 조회 시 보완)·`GET /api/v1/wallets/me` | `feature/tkt-42` | #54 | 완료 |
| #57 | HP 원장 기록·잔액 버킷 이동·멱등성·역분개 검증·잔액 대사 | `feature/tkt-57` | #67 | 완료 |

다음 후보: Hold(보류·FIFO), Charge(TossPayments 충전), Refund, Settlement(지급), Exchange(환전). 자세한 내용은 7장.

---

## 2. 현재 구조

```
boundedContext/billing/src/main/java/com/hapbang/billing/
├── domain/   Entity·Enum·오류 타입
├── out/      Repository (삭제 메서드 없음), 조회 결과 record
├── app/      UseCase·Facade·요청/결과 record
└── in/       Controller·이벤트 리스너·응답 DTO·예외 핸들러
```

| 패키지 | 클래스 | 역할 |
| --- | --- | --- |
| domain | `Wallet` | 잔액 4개(충전·수익 × 가용·보류) 캐시. 잔액 변경은 `applyLedger(PointLedger)`로만 한다 |
| domain | `PointLedger` | append-only 원장. `@Transient pendingApplication`으로 재반영을 막는다 |
| domain | `BillingCreatedEntity`, `BillingUpdatedEntity` | Instant 감사 시각, `@PreRemove`로 물리 삭제 차단 |
| domain | `BillingErrorCode`, `BillingException` | 오류 코드(5장) |
| out | `WalletRepository` | `findByUserId`, `findByIdForUpdate`(PESSIMISTIC_WRITE) |
| out | `PointLedgerRepository` | `findAllByOperationKeyOrderByIdAsc`, `sumAmountByWalletId`, `sumAmountByOriginalLedgerId` |
| app | `WalletFacade`, `WalletCreateUseCase` | 지갑 조회·없으면 생성. `getMyWallet`(내 지갑 조회, `@PreAuthorize`)·`getOrCreateWallet`(가입 리스너, 역할 검사 없음) |
| app | `LedgerRecordUseCase` | 원장 기록 + 지갑 잔액 변경의 **유일한 경로** |
| app | `LedgerOperation`, `LedgerEntry`, `LedgerOperationResult` | 원장 기록 요청·결과 |
| app | `WalletReconciliationUseCase`, `WalletReconciliation` | 지갑 캐시 ↔ 원장 합계 대사 |
| in | `UserJoinedEventListener` | 가입 이벤트로 지갑 생성 (ADMIN 제외) |
| in | `ApiV1WalletController`, `ApiV1WalletControllerDocs`, `WalletResponse` | `GET /api/v1/wallets/me`. Swagger `@Tag`·`@Operation`은 Docs 인터페이스에 두고 컨트롤러가 구현한다 |
| in | `BillingExceptionHandler` | Billing 컨트롤러 오류를 ProblemDetail + `code`로 응답 |

Gradle 의존성(`build.gradle.kts`): `:shared`, `:global`(`@LoginUserId`), data-jpa, webmvc, `spring-security-core`(`@PreAuthorize`), `swagger-annotations-jakarta`(Docs 인터페이스), `compileOnly postgresql`(제약 이름 판별), `testImplementation security-test`.

---

## 3. 결정 기록

각 항목은 "결정 — 이유" 형식이다. 바꾸려면 팀과 먼저 합의한다.

### 3.1 지갑 생성 (#42)

- **회원당 지갑 1개.** `uk_wallet_user_id` UNIQUE로 최종 보장한다.
- **가입 이벤트로 생성하고, `GET /wallets/me`에서 없으면 생성한다.** — 이벤트는 커밋 후 비동기라 늦을 수 있고, Billing 이전 가입 회원에게는 이벤트가 없다. 두 경로 모두 `WalletCreateUseCase.getOrCreate`를 써서 결과가 같다.
- **ADMIN은 지갑을 만들지 않는다.** — HP를 쓰지 않는다(루트 AGENTS 6장). 이벤트에서 건너뛰고, 조회 API는 `WalletFacade.getMyWallet`의 `@PreAuthorize("hasAnyRole('USER', 'INFLUENCER', 'VERIFIED_INFLUENCER')")`로 생성 전에 403을 낸다(2026-10-12 회의: 관리자 API 외 역할 검사는 서비스 레이어 `@PreAuthorize`). 가입 리스너는 로그인 정보가 없으므로 역할 검사 없는 `getOrCreateWallet`을 쓴다.
- **생성은 `REQUIRES_NEW` 별도 트랜잭션에서 한다.** — INSERT 실패로 오류 상태가 된 PostgreSQL 트랜잭션에서 재조회하지 않기 위해서다. 롤백이 끝난 뒤 바깥에서 재조회한다.
- **중복 판별은 PostgreSQL 드라이버 오류 정보로 한다.** — `PSQLException.getServerErrorMessage()`의 SQLState `23505` + 제약 이름 `uk_wallet_user_id`가 모두 맞을 때만 기존 지갑을 재조회한다. Hibernate는 메시지 문자열(`constraint "..."`)을 파싱해 서버 언어(`lc_messages`)가 바뀌면 깨지므로 쓰지 않는다. 다른 오류는 그대로 던진다.
- **리스너는 `@ApplicationModuleListener(propagation = NOT_SUPPORTED)`.** — 생성이 자체 트랜잭션을 열기 때문에, 리스너 트랜잭션까지 있으면 이벤트 하나가 커넥션 2개를 잡는다. 실패하면 이벤트 발행 기록이 미완료로 남아 재시작 시 재처리된다.

### 3.2 원장과 잔액 (#57)

- **잔액 변경은 `LedgerRecordUseCase.record()`만 한다.** — 원장 INSERT와 지갑 변경을 한 트랜잭션에서 처리한다. ArchUnit 테스트(`WalletApplyLedgerAccessTest`)가 다른 실행 코드의 `Wallet.applyLedger` 호출을 막는다.
- **재반영 방지는 원장 ID가 아니라 `pendingApplication`으로 한다.** — IDENTITY 전략은 INSERT 시점에 ID가 생기므로 ID로는 판단할 수 없다. 빌더로 만든 항목만 true이고, 반영하면 false, DB에서 읽은 항목은 기본 생성자라 false다.
- **금융 거래에서는 지갑을 자동 생성하지 않는다.** — 없으면 `WALLET_NOT_FOUND`. `getOrCreateWallet()`은 가입·내 지갑 조회 전용이다.
- **락: 지갑 ID 오름차순으로 `PESSIMISTIC_WRITE`.** — 원장 줄 순서와 무관하게 `LedgerOperation.walletIds()`(TreeSet) 순서로 잠근다. 교착 방지.
- **락 전 flush, 락 후 refresh.** — 락 조회는 영속성 컨텍스트에 이미 있던 지갑 상태를 갱신하지 않아 lost update가 생긴다(테스트로 확인). refresh가 같은 트랜잭션의 미반영 변경을 지우지 않도록 먼저 flush한다.
- **원장 금액은 부호 있는 값, 0은 금지.** 증감은 `Math.addExact`로 오버플로를 막고(`BALANCE_OVERFLOW`), 음수가 되면 `INSUFFICIENT_BALANCE`.
- **버킷 이동은 같은 작업 키의 두 줄.** `LedgerEntry.move()`가 `from -금액`, `to +금액`을 만든다. HP 총량은 변하지 않는다.

### 3.3 멱등성 (#57)

- **작업 키(`operationKey`) = 비즈니스 작업 하나, 원장 멱등키 = `{operationKey}:{줄번호}`.** 줄 번호는 요청의 원장 줄 순서(1부터)다.
- **지갑 락을 잡은 뒤 작업 키로 기존 원장을 조회한다.** 줄마다 멱등키·지갑·HP 종류·버킷·금액·거래 유형·참조 대상·역분개 원본이 같으면 잔액을 바꾸지 않고 기존 원장 ID를 돌려준다(`replayed=true`). 다르면 `IDEMPOTENCY_KEY_CONFLICT`. 발생 시각·설명은 비교하지 않는다.
- **같은 키가 겹치지 않는 지갑에 동시에 오면** 락으로 직렬화되지 않으므로 `uk_point_ledger_idempotency_key` UNIQUE(23505 + 제약 이름)로 막고 `IDEMPOTENCY_KEY_CONFLICT`로 바꾼다.
- **새 작업 상태 테이블은 만들지 않았다.** — 업무 요청 전체의 멱등성은 각 업무 테이블(`POINT_HOLD`, `POINT_CHARGE`, `SETTLEMENT`, `EXCHANGE`)의 멱등키와 호출하는 쪽의 결정론적 작업 키로 맡는다.

### 3.4 역분개 (#57, 원장 단위 검증까지만)

- 거래 유형 `REVERSAL` ⇔ `originalLedgerId` 함께 지정. RELEASE·REFUND 등 정상 거래는 원본을 가질 수 없다.
- 원본과 같은 지갑·HP 종류·버킷, 원본과 반대 부호.
- 부분·여러 번 역분개 허용. 원본별 누적 합계의 절댓값 ≤ 원본 절댓값. 같은 작업 안의 앞 줄도 함께 센다.
- 역분개의 역분개 금지. 여러 줄 원장은 줄 단위로 역분개할 수 있다.
- 역분개로 잔액이 음수가 되면 전체 롤백.
- 위반은 `INVALID_REVERSAL`, 원본이 없으면 `ORIGINAL_LEDGER_NOT_FOUND`.
- 동시 역분개는 역분개 지갑(= 원본 지갑) 락으로 직렬화되어 누적 한도를 넘지 않는다. Flyway 변경 없음.
- **실제 거래의 취소·환불·보상 처리는 각 비즈니스 서비스가** 여러 원장과 업무 상태를 함께 조정한다. Billing 원장 계층은 줄 단위 정합성만 책임진다.

### 3.5 잔액 대사 (#57)

- `reconcile(walletId)`는 `@Transactional(readOnly = true, isolation = REPEATABLE_READ)` — 지갑과 원장 합계를 같은 스냅숏에서 읽어 동시 거래 중 오탐을 막는다(READ COMMITTED로 바꾸면 테스트가 실패함을 확인).
- 결과: 버킷 4개마다 `walletHp`, `ledgerHp`, `differenceHp`(캐시 − 원장)와 전체 `matched`. 원장이 없는 버킷은 0.
- 불일치가 있어도 원장·지갑을 고치지 않는다. 관리자 API·Scheduler는 아직 없다.

---

## 4. 다른 코드가 원장 기록을 호출할 때의 계약

향후 Hold·Charge·Settlement 서비스는 아래를 지킨다.

1. `LedgerRecordUseCase.record(LedgerOperation)`을 호출한다. `@Transactional(REQUIRED)`라 호출한 쪽 트랜잭션에 참여하고, 실패하면 전체가 롤백된다.
2. 작업 키는 업무 요청에서 **결정론적으로** 만든다(예: `HOLD:LECTURE_ORDER:1001`). 재시도할 때 같은 키를 써야 중복 반영이 없다. 키는 `{키}:{줄수}`가 255자를 넘지 않아야 한다.
3. 대상 지갑은 `walletId`로 넘긴다. 회원 ID만 있으면 `WalletRepository.findByUserId`로 찾고, 없으면 거래 실패로 처리한다(`getOrCreate` 금지).
4. 호출한 쪽 트랜잭션은 기본 격리 수준(READ COMMITTED)을 쓴다. REPEATABLE READ 이상이면 락 시점에 직렬화 오류가 날 수 있다.
5. `record()`는 시작할 때 flush하므로 호출한 쪽의 다른 미반영 변경도 함께 DB에 반영된다.
6. `IDEMPOTENCY_KEY_CONFLICT`가 UNIQUE 충돌로 난 경우 바깥 트랜잭션은 롤백 전용이 된다. 그 트랜잭션으로 작업을 계속하지 않는다.
7. 결과는 `LedgerOperationResult(operationKey, replayed, ledgerIds)`다. Billing 엔티티를 다른 모듈에 넘기지 않는다.

---

## 5. 오류 코드 (`BillingErrorCode`)

| 코드 | HTTP | 상황 |
| --- | --- | --- |
| `WALLET_NOT_FOUND` | 404 | 거래·대사 대상 지갑 없음 |
| `ORIGINAL_LEDGER_NOT_FOUND` | 404 | 역분개 원본 원장 없음 |
| `INVALID_REVERSAL` | 400 | 역분개 정책 위반 |
| `INVALID_POINT_AMOUNT` | 400 | 0 HP 줄, 0 이하 이동 금액 |
| `INSUFFICIENT_BALANCE` | 409 | 잔액이 음수가 되는 감소 |
| `BALANCE_OVERFLOW` | 409 | Long 범위 초과 |
| `IDEMPOTENCY_KEY_CONFLICT` | 409 | 같은 작업 키에 다른 내용 |

역할 검사(`@PreAuthorize`) 실패는 `BillingErrorCode`가 아니라 Spring Security 기본 403으로 응답한다(강의 모듈과 같음).

응답 형식: RFC 9457 ProblemDetail + `code` 속성 (`BillingExceptionHandler`, `basePackages = "com.hapbang.billing"`). 정상 응답은 record를 그대로 반환한다.

---

## 6. 테스트

- 실행: `./gradlew :billing:test --no-daemon`, 전체 `./gradlew check --no-daemon` (`ModularityTests` 포함).
- 2026-10-12 기준: Billing 157개, 전체 420개 통과.
- **Docker가 켜져 있어야 한다.** `test-support`의 `PostgresTestcontainersConfig`가 `postgres:18` 컨테이너를 띄운다. 로컬에 PostgreSQL 설치는 필요 없다.
- `@RepositoryTest`(JPA만, 롤백) / `@IntegrationTest`(전체 컨텍스트, 테스트마다 TRUNCATE) / `TestLogin.as(userId, role)`(MockMvc 로그인).
- `@ApplicationModuleListener` 빈은 `@Async` 프록시라 직접 호출하면 바로 반환된다. 동기 검증은 `new UserJoinedEventListener(...)`, 실제 비동기 경로는 `Scenario`로 한다.
- 동시성 테스트는 실제 DB로 한다. 충돌 경로를 결정적으로 만들 때는 상대 트랜잭션이 INSERT 후 대기하게 하고 `pg_locks`의 `NOT granted`로 대기 상태를 확인한다.
- PostgreSQL 드라이버는 Billing에서 `compileOnly`라 테스트 컴파일 경로에 없다. 드라이버 예외가 필요한 단위 테스트는 리플렉션으로 만든다(`WalletCreateUseCaseTest`). 서버 언어와 무관한 판별은 `RAISE ... USING ERRCODE, CONSTRAINT`로 검증한다(`WalletDuplicateViolationTest`).
- 처음부터 통과한 핵심 테스트는 코드를 일부러 망가뜨려 실패하는지 확인했다(락 제거·락 순서·refresh·flush·재반영 방어·누적 한도·대사 격리 수준).

---

## 7. 남은 일과 열린 결정

### 다음 Issue 후보
- **Hold**: `POINT_HOLD` 생성·상태 전이, 수익 HP 먼저·충전 HP는 `POINT_CHARGE_USAGE` FIFO 배분, 원장은 `LedgerEntry.move` + `record()`를 같은 트랜잭션에서 호출. 작업 키 규칙 확정 필요.
- **Charge·Refund**: TossPayments 테스트 API, PG 승인과 DB 커밋 사이 상태 관리(`POINT_CHARGE` 상태·멱등키), 타임아웃을 성공·실패로 단정하지 않기.
- **Settlement**: 강의 D+30·출연 확인 시 지급, 지급 대상 지갑이 없을 때의 재처리 상태(지금은 `WALLET_NOT_FOUND`로만 거절).
- **Exchange**: 수익 HP 1,000 단위, 수수료 10%, 정산일(매월 10일, 주말·공휴일이면 다음 영업일), Mock 송금.
- **다른 모듈 연동**: appearance의 `out/billing/BillingClient`가 계약 초안이다(보류·반환·지급·일괄 정산, 멱등키). Billing 쪽 공개 API·DTO가 필요하다. `billing.app`은 Modulith상 다른 모듈에 공개된 패키지가 아니다.

### 팀과 정할 것
- Billing 관리자 API는 `/api/v1/{module}/admin/**` 규칙(`SecurityConfig`)에 따라 둔다(대사 API 등).
- 락 대기 시간 상한(`lock_timeout`). 지금은 PostgreSQL 기본값(무제한 대기).
- `UserModifiedEvent`(역할 변경)로 회원이 ADMIN이 될 때 지갑 처리.

---

## 8. 작업 방식 메모

- 브랜치: `dev`에서 `feature/tkt-{Issue번호}`를 만들고 PR은 **반드시 base `dev`**. (#39, #42 때 PR이 main으로 열린 적이 있다. #39는 main을 강제 리셋해 되돌렸다.)
- PR 전에 최신 `origin/dev`를 작업 브랜치에 병합하고 `check`를 다시 돌린다. main을 feature 브랜치에 병합하지 않는다.
- 커밋 메시지: `feat(billing): #{Issue번호} {내용}`.
- 범위 밖 변경 금지: 기존 Flyway 파일, `shared`·`global`·다른 모듈, Gradle 설정은 승인 없이 바꾸지 않는다. 새 테이블은 근거와 대안을 먼저 보고한다.
- 개인정보·DB 접속 정보는 커밋하지 않는다. 루트 `.env`는 `.gitignore` 대상이다.
