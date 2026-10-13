# Billing 모듈 진행 기록

Billing 작업을 이어받는 사람과 AI 에이전트(Claude Code, Codex 등)가 작업 전에 읽는 문서다.
규칙은 루트 `AGENTS.md`와 `boundedContext/billing/AGENTS.md`가 우선이고, 이 문서는 지금까지 **무엇을 만들었고 왜 그렇게 정했는지**를 기록한다.

- 작업을 시작하기 전에 이 문서를 읽는다.
- Issue 하나를 끝내면 "1. 진행 현황", "3. 결정 기록", "7. 남은 일"을 갱신한다.
- 코드와 이 문서가 다르면 코드가 맞다. 확인 후 이 문서를 고친다.

마지막 갱신: 2026-10-13 (Issue #116 구현, PR 전)

---

## 1. 진행 현황

| Issue | 내용 | 브랜치 | PR (dev 병합) | 상태 |
| --- | --- | --- | --- | --- |
| #39 | Entity 12개·Enum 18개·Repository 12개, Flyway 초기 스키마, 원장 UPDATE·DELETE 차단 트리거 | `feature/tkt-39` | #41 | 완료 |
| #42 | 회원 지갑 생성(가입 이벤트 + 조회 시 보완)·`GET /api/v1/wallets/me` | `feature/tkt-42` | #54 | 완료 |
| #57 | HP 원장 기록·잔액 버킷 이동·멱등성·역분개 검증·잔액 대사 | `feature/tkt-57` | #67 | 완료 |
| #81 | 팀 공통 API 컨벤션 적용(지갑 역할 검사 Facade `@PreAuthorize`, Swagger Spec 인터페이스) | `feature/tkt-81` | #82 | 완료 |
| #78 | Hold 최초 생성: 수익 HP 우선·충전 HP FIFO 배분, `POINT_CHARGE_USAGE` 배분 기록, 참조당 보류 1건 멱등성 | `feature/tkt-78` | #99 | 완료 |
| #100 | Hold 부분·전체 반환: 충전 HP 우선·충전 원금 역순 복원, 반환 요청별 멱등성, 처리 이력 재생 대조. #71 공통 예외 처리 반영 | `feature/tkt-100` | #114 | 완료 |
| #116 | 멘토 리뷰 반영: Aggregate 간 JPA 연관관계 → ID 참조(13개), Enum 상수 Javadoc(18개), QueryDSL 도입 검토(보류) | `feature/tkt-116` | - | 구현·테스트 완료 (PR 전) |

다음 후보: Hold Payout(지급), Charge(TossPayments 충전), Refund, Settlement(지급), Exchange(환전). 자세한 내용은 7장.

---

## 2. 현재 구조

```
boundedContext/billing/src/main/java/com/hapbang/billing/
├── domain/   Entity·Enum·오류 타입
├── out/      Repository (삭제 메서드 없음), 조회 결과 record
├── app/      UseCase·Facade·요청/결과 record
└── in/       Controller·이벤트 리스너·응답 DTO (오류 응답은 global `GlobalExceptionHandler`)
```

| 패키지 | 클래스 | 역할 |
| --- | --- | --- |
| domain | `Wallet` | 잔액 4개(충전·수익 × 가용·보류) 캐시. 잔액 변경은 `applyLedger(PointLedger)`로만 한다 |
| domain | `PointLedger` | append-only 원장. `@Transient pendingApplication`으로 재반영을 막는다 |
| domain | `BillingCreatedEntity`, `BillingUpdatedEntity` | Instant 감사 시각, `@PreRemove`로 물리 삭제 차단 |
| domain | `BillingErrorCode`, `BillingException` | 오류 코드(5장) |
| domain | `PointHold`, `PointCharge`, `PointChargeUsage` | `PointHold.held`(최초 보류)·`release`(남은 보류 감소·상태 전이), `PointCharge.reservePrincipal`(가용 원금 감소)·`restorePrincipal`(복원), `PointChargeUsage.held`(최초 배분)·`release`(남은 보류 → 반환) |
| domain | `PointHoldDetail` | `released`(반환 한 건 기록. 참조 대상 = 보류의 업무 대상) |
| domain | (참조 방식) | Aggregate 간은 ID 필드(`walletId`, `chargeId`, `pointHoldId`, `originalLedgerId` 등), Aggregate 안만 `@ManyToOne`·`@OneToOne`(3.8) |
| out | `WalletRepository` | `findByUserId`, `findByIdForUpdate`(PESSIMISTIC_WRITE) |
| out | `PointChargeRepository` | `findAllocatableForUpdate`(배분 가능 충전 건을 완료 시각·ID 순으로 PESSIMISTIC_WRITE), `findAllByIdInForUpdate`(복원 대상을 ID 순으로 PESSIMISTIC_WRITE), `sumRemainingPrincipal` |
| out | `PointHoldDetailRepository` | `findAllByPointHoldIdOrderByIdAsc`(재생 순서) |
| out | `PointChargeUsageRepository` | `findAllByPointHoldIdOrderByIdAsc`(보류의 충전 건별 배분) |
| out | `PointLedgerRepository` | `findAllByOperationKeyOrderByIdAsc`, `sumAmountByWalletId`, `sumAmountByOriginalLedgerId` |
| app | `WalletFacade`, `WalletCreateUseCase` | 지갑 조회·없으면 생성. `getMyWallet`(내 지갑 조회, `@PreAuthorize`)·`getOrCreateWallet`(가입 리스너, 역할 검사 없음) |
| app | `LedgerRecordUseCase` | 원장 기록 + 지갑 잔액 변경의 **유일한 경로**. `lockWallet`(record()와 같은 flush → 락 → refresh, 트랜잭션 필수)을 다른 금융 작업에 제공 |
| app | `HoldFacade`, `HoldCreateUseCase`, `HoldReleaseUseCase` | 최초 HP 보류 생성(수익 HP 우선·충전 HP FIFO)·부분/전체 반환(충전 HP 우선·충전 원금 역순 복원). 역할 검사 없는 내부 금융 기능 |
| app | `ReleaseCommand`, `ReleaseResult`, `ReleaseSplit` | 반환 요청(부분/전체, 멱등키 `RELEASE:{holdId}:{releaseRequestId}`)·결과(충전 건별 복원 포함)·충전/수익 반환 배분 |
| app | `ChargeUsageHistory` | 반환 상세를 순서대로 재생해 처리 건별 충전 건 복원을 재구성하고 DB 누적값과 대조(스키마 A안) |
| app | `HoldCommand`, `HoldResult`, `HoldSplit` | 보류 요청(멱등키 `HOLD:{referenceType}:{referenceId}` 생성)·결과(충전 건별 배분 포함)·수익/충전 배분 계산 |
| app | `LedgerOperation`, `LedgerEntry`, `LedgerOperationResult` | 원장 기록 요청·결과 |
| app | `WalletReconciliationUseCase`, `WalletReconciliation` | 지갑 캐시 ↔ 원장 합계 대사 |
| in | `UserJoinedEventListener` | 가입 이벤트로 지갑 생성 (ADMIN 제외) |
| in | `ApiV1WalletController`, `ApiV1WalletSpec`, `WalletResponse` | `GET /api/v1/wallets/me`. Swagger `@Tag`·`@Operation`은 Spec 인터페이스에 두고 컨트롤러가 구현한다 |

Gradle 의존성(`build.gradle.kts`): `:shared`, `:global`(`@LoginUserId`), data-jpa, webmvc, `spring-security-core`(`@PreAuthorize`), `swagger-annotations-jakarta`(Spec 인터페이스), `compileOnly postgresql`(제약 이름 판별), `testImplementation security-test`.

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

### 3.6 HP 보류 생성 (#78)

- **참조 대상당 보류 1건, 키는 Billing이 만든다 (B안, 2026-10-13 확정).** 보류 멱등키 = 원장 작업 키 = `HOLD:{referenceType}:{referenceId}`. 호출한 쪽은 보류 멱등키를 넘기지 않는다. 참조 유형은 Enum 이름으로만 표기해 같은 대상이 다른 문자열로 중복 보류되지 않는다. 기존 `uk_point_hold_idempotency_key`가 참조당 1건을 DB에서 보장한다(스키마 변경 없음).
- **보류 단위**: 강의는 주문 항목(`LECTURE_ORDER_ITEM`, #100에서 주문 `LECTURE_ORDER`에서 바꿈), 출연 요청은 요청(`APPEARANCE_REQUEST`), 출연 모집은 모집글(`APPEARANCE_RECRUIT`). 다른 참조 유형은 `HoldCommand`에서 거절한다.
- **재요청**: 같은 참조·같은 지갑·같은 총액이면 보류·배분·원장·지갑·충전 원금을 바꾸지 않고 처음 결과(`replayed=true`)를 돌려준다. 잔액이 바뀐 뒤 재요청해도 다시 배분하지 않는다. 같은 참조에 다른 지갑·다른 금액이면 `IDEMPOTENCY_KEY_CONFLICT`.
- **비정상 상태는 재요청으로 보지 않는다.** 보류 없이 같은 작업 키의 원장만 있거나, 보류는 있는데 원장 줄 수·충전 원금 배분 합계가 맞지 않으면 `HOLD_STATE_INCONSISTENT`.
- **배분**: 가용 수익 HP를 전부 먼저 쓰고 부족분만 충전 HP. 충전 HP는 `COMPLETED`·`PARTIALLY_REFUNDED` 상태이고 가용 원금 > 0인 충전 건을 `completed_at, id` 오름차순으로 앞에서부터 채운다. 충전 건마다 `remaining_principal_hp`를 줄이고 `POINT_CHARGE_USAGE`(amount = remaining_held, consumed·released 0, `HELD`)를 남긴다. `POINT_HOLD_DETAIL`은 최초 보류에 쓰지 않는다.
- **원금 대조**: 배분 전에 배분 가능 충전 건의 가용 원금 합계가 지갑 `charge_balance_hp`와 같은지 확인하고, 다르면(수익 HP만 쓰는 보류도) `CHARGE_PRINCIPAL_MISMATCH`로 전체 롤백한다.
- **처리 순서 (한 트랜잭션)**: 회원 ID로 지갑 조회(없으면 `WALLET_NOT_FOUND`) → `LedgerRecordUseCase.lockWallet` → 기존 보류·원장 확인 → 수익/충전 배분 계산(부족하면 `INSUFFICIENT_BALANCE`) → 충전 건 `FOR UPDATE` 조회·원금 대조 → 보류 INSERT → 충전 원금 감소·배분 INSERT → `record()`(수익 이동 2줄, 충전 이동 2줄, 거래 유형 `HOLD`, 참조 = 업무 대상).
- **락 순서: 지갑 → 충전 건.** 같은 지갑의 보류는 지갑 락으로 직렬화되고, 충전 건 락은 방어용이다. 앞으로 충전·환불도 지갑 락을 먼저 잡아야 교착이 없다.
- **flush·refresh**: `record()`의 시작 flush가 보류·배분·충전 원금 변경을 원장보다 먼저 같은 트랜잭션에서 반영한다. 보류는 `record()` 전까지 지갑을 바꾸지 않으므로 refresh가 지울 변경이 없다. 같은 트랜잭션의 연속 보류는 `lockWallet`의 flush가 앞 보류의 지갑 변경을 지킨다(테스트로 확인).
- **다른 지갑의 같은 참조 동시 요청**은 지갑 락으로 직렬화되지 않으므로 보류 INSERT의 UNIQUE 충돌(23505 + 제약 이름)을 `IDEMPOTENCY_KEY_CONFLICT`로 바꾼다.
- **잔액 부족은 예외**(`INSUFFICIENT_BALANCE`)로 알린다. 출연 쪽 `BillingClient`의 `HoldStatus.INSUFFICIENT_BALANCE` 결과로 바꾸는 것은 연동 어댑터가 한다.
- **역할 검사 없음**: `HoldFacade`는 강의·출연 서비스가 결제자 확인 후 호출하는 내부 기능이라 `@PreAuthorize`를 두지 않는다. 공개 HTTP API도 만들지 않았다.

### 3.7 HP 보류 반환 (#100)

강의·출연 담당과 합의한 정책(2026-10-13 확정, Issue #100 본문)을 구현 기준으로 적용했다. 반환 여부·금액은 강의·출연이 업무 상태로 정하고, Billing은 금액 한도·보류 소유·멱등성·금융 정합성만 검증한다.

- **HP 종류 순서: 충전 HP 먼저, 부족분만 수익 HP** (`ReleaseSplit`). 전체 반환은 결과적으로 HP 종류별 남은 보류 전부를 되돌린다.
- **충전 원금 복원 순서: 최초 FIFO 배분의 역순(LIFO)**. 배분(`POINT_CHARGE_USAGE`) ID 내림차순으로 남은 보류가 있는 배분부터 채우고, 반드시 그 배분의 원래 충전 건(`remaining_principal_hp`)에 되돌린다. 예: #101에서 2,000, #102에서 3,000 보류 → 충전 HP 4,000 반환 = #102에 3,000, #101에 1,000.
- **반환 멱등키 = 원장 작업 키 = `RELEASE:{holdId}:{releaseRequestId}`**. 반환 요청 ID는 강의·출연이 업무 요청마다 안정적으로 만든다(재전송 때 같은 값, 호출마다 UUID 금지). 형식은 `[A-Za-z0-9_-]{1,200}`(':'는 키 구분자라 금지, 길이는 원장 멱등키 255자 제한). 출연 `BillingClient` 초안의 `RECRUIT-7-RELEASE-UNDERFILLED-1` 형식은 그대로 쓸 수 있다. `uk_point_hold_detail_idempotency_key`·`uk_point_ledger_idempotency_key`가 DB에서 중복을 막는다(스키마 변경 없음).
- **보류 식별**: 호출한 쪽은 회원 ID·참조 유형·참조 ID를 넘기고 Billing이 `HOLD:{referenceType}:{referenceId}`로 보류를 찾는다(참조당 보류 1건). 없으면 `HOLD_NOT_FOUND`, 요청 회원의 지갑 것이 아니면 `HOLD_OWNER_MISMATCH`.
- **부분 반환**: 금액 지정. 남은 보류를 넘으면 `RELEASE_AMOUNT_EXCEEDS_HELD`. 여러 번 할 수 있다. **전체 반환**: 금액 없이(`ReleaseCommand.full`) 처리 시점의 남은 보류 전부. 최초 보류액이 아니다.
- **재요청**: 같은 반환 키의 상세가 있으면 현재 보류 상태(이미 끝났어도)와 관계없이 바꾸지 않고 그때 결과(`replayed=true`)를 돌려준다. 전체 반환 재요청은 남은 보류를 다시 계산하지 않는다. 부분 반환 재요청 금액이 처음과 다르면 `IDEMPOTENCY_KEY_CONFLICT`. 결과의 반환액·충전 건별 복원은 처리 당시 값이고 `remainingHeldHp`·`holdStatus`는 응답 시점 값이다.
    - 상세에는 부분/전체 구분이 없어, 부분 반환으로 처리된 키에 전체 반환으로 재요청하면 금액을 비교하지 않고 처음 결과를 돌려준다.
- **반환 요청 ID는 보류 하나 안에서 고유하다 (2026-10-13 결정).** 같은 보류·같은 ID는 재요청, 같은 보류·다른 ID는 별도 반환, 다른 보류·같은 ID는 반환 키가 달라 별개 요청으로 처리한다. 키에 holdId가 들어가므로 billing AGENTS.md 7장(같은 멱등키 = 같은 요청, 다른 내용이면 충돌)과 충돌하지 않고, 전역 고유성을 요구하는 업무 요구가 없어 스키마를 바꾸지 않는다.
    - 1차 구현은 다른 보류에 쓴 같은 ID를 `IDEMPOTENCY_KEY_CONFLICT`로 거절했다(멱등키 접미사 LIKE 조회). 위 결정으로 이 확인을 없앴다. 다른 지갑 동시 요청을 막지 못하던 한계와 접두사 없는 LIKE 조회(전체 스캔)도 함께 사라졌다.
- **상세 ID 순서 = 처리 순서**: 같은 보류의 반환은 소유 지갑 락(+ 보류 락)으로 직렬화되고 락은 커밋까지 유지되므로, 뒤 요청의 상세 INSERT(IDENTITY 발급)는 앞 요청이 커밋된 뒤에만 일어난다. 롤백된 요청은 번호만 비우고 행을 남기지 않는다. 배분(usage) ID도 한 트랜잭션에서 FIFO 순서로 INSERT되어 배분 순서와 같다. 동시 반환 뒤 재생 대조가 통과하는 것을 테스트로 확인했다.
- **처리 이력 추적 = A안(스키마 변경 없음)**. `POINT_HOLD_DETAIL`과 `POINT_CHARGE_USAGE` 사이 연결이 없으므로, 반환할 때마다 그 보류의 상세를 ID 순서대로 위 역순 규칙으로 재생(`ChargeUsageHistory`)해 배분별 (남은 보류, 소진, 반환)이 DB 누적값과 같은지 대조한다. 다르면 `HOLD_STATE_INCONSISTENT`로 거절한다. 재요청 결과의 충전 건별 복원도 이 재생으로 재구성한다. 상세와 원장은 append-only이고 규칙이 고정되어 있어 처리 건 하나가 어느 충전 건에 얼마를 되돌렸는지 정확히 재구성된다.
    - **역순 규칙은 바꾸지 않는다.** 바꾸면 기존 상세를 재구성할 수 없다.
    - **지급(PAYOUT)이 충전 원금을 소진하는 순서는 아직 없다.** 지금은 충전 HP를 지급한 상세가 있으면 재생을 거절한다(수익 HP만 지급한 상세는 영향 없음). 지급 구현 때 소진 순서를 정해 `ChargeUsageHistory`에 더해야 한다.
- **보류 상태 전이**: `HELD` → (일부 처리) `PARTIALLY_PROCESSED` → 남은 보류 0이면 지급 이력 없음 `RELEASED`, 있음 `MIXED`(`released_at` 기록). 끝난 보류(남은 보류 0 또는 `RELEASED`·`PAID`·`MIXED`)에 새 요청이 오면 `HOLD_ALREADY_CLOSED`. 지급된 금액은 남은 보류에서 이미 빠져 있어 다시 반환할 수 없다. 배분 상태는 남은 보류·소진·반환으로 `HELD`/`PARTIALLY_PROCESSED`/`RELEASED`/`CONSUMED`/`MIXED`.
- **처리 순서 (한 트랜잭션)**: 회원 지갑 조회 → `lockWallet` → 보류 조회·소유 확인·`refresh(PESSIMISTIC_WRITE)` → 상세·배분 조회(배분 refresh) → 금액·재생 대조 → (같은 키 상세가 있으면 재요청 결과) → 상세 없이 원장만 있으면 `HOLD_STATE_INCONSISTENT` → 끝난 보류 거절 → 반환 배분 계산 → 충전 원금 합계 대조(`CHARGE_PRINCIPAL_MISMATCH`, 수익 HP만 반환해도 확인) → 복원 대상 충전 건 `FOR UPDATE`·refresh → 배분 반환·충전 원금 복원 → 보류 감소 → 상세 INSERT → `record()`(충전 이동 2줄 HELD→AVAILABLE, 수익 이동 2줄, 거래 유형 `RELEASE`, 참조 = 보류의 업무 대상).
- **락 순서: 지갑 → 보류 → 충전 건(ID 오름차순)**. 보류 생성의 지갑 → 충전 건과 충돌하지 않는다. 보류·배분·충전 원금은 소유 지갑 락 아래에서만 바뀌므로 지갑 락이 실제 직렬화를 맡고, 보류·충전 건 락은 방어용이다. 배분 행은 잠그지 않는다.
- **flush·refresh**: `lockWallet`이 먼저 flush하므로 이후 보류·배분·충전 건 refresh가 같은 트랜잭션의 미반영 변경을 지우지 않는다. 호출한 쪽 트랜잭션이 락 없이 미리 읽어 둔 엔티티는 refresh로 최신 상태가 된다(테스트로 확인). 반환은 `record()` 전까지 지갑을 바꾸지 않고, `record()`의 시작 flush가 보류·배분·충전 원금·상세 변경을 원장보다 먼저 반영한다.
- **`RELEASE`는 정상 보류 해제**다. 원장 정정용 `REVERSAL`(원본 원장 참조)과 구분한다.
- **강의 보류 단위 = 주문 항목 (2026-10-13 강의 담당 합의, 적용)**: `HoldCommand.HOLDABLE_REFERENCE_TYPES`의 `LECTURE_ORDER`를 `LECTURE_ORDER_ITEM`으로 바꿨다. 여러 강의를 함께 사면 항목마다 보류하고, 개별 취소는 그 항목의 보류만 반환한다. Enum 값과 DB CHECK에 이미 있어 **마이그레이션은 필요 없다**. `LECTURE_ORDER` Enum 값·CHECK는 남긴다(원장 참조 등 다른 용도, 기존 Flyway 수정 금지). 운영 코드에 보류 호출자가 아직 없어 `LECTURE_ORDER`로 만든 기존 보류는 없다. 있었다면 `ReleaseCommand`가 거절해 반환할 수 없으므로, 강의 연동 전에 보류가 만들어졌다면 확인이 필요하다. 강의 모듈 API는 이미 `orderItemId`를 쓴다(강의 코드는 바꾸지 않았다).

### 3.8 JPA 연관관계·Enum·QueryDSL (#116, 멘토 리뷰)

- **Aggregate 간 참조는 ID 필드**: 엔티티 필드만 `@ManyToOne` → `@Column Long xxxId`로 바꿨다. 컬럼 이름·타입·NOT NULL, DB FK 18개·UNIQUE·CHECK·인덱스는 그대로다(Flyway 변경 없음, `BillingSchemaTest`가 FK 18개 확인).
    - 바꾼 13개: `PointLedger.walletId`·`originalLedgerId`, `PointHold.walletId`, `PointCharge.walletId`, `PointChargeUsage.chargeId`, `PointRefundRequest.walletId`·`pointHoldId`, `PointRefund.walletId`·`chargeId`, `Settlement.receiverWalletId`·`pointHoldId`, `Exchange.walletId`·`pointHoldId`.
    - 유지한 5개(Aggregate 안): `PointHoldDetail.pointHold`, `PointChargeUsage.pointHold`(보류 Aggregate), `PointRefund.refundRequest`(환불 요청 Aggregate), `ExchangeAccount.exchange`, `ExchangePayout.exchange`(환전 Aggregate).
    - Aggregate 구분: 지갑, 원장 항목(append-only, 행마다 독립), 충전 건, 보류(+상세·배분), 환불 요청(+환불), 지급, 환전(+계좌·송금).
- **배분 생성도 ID**: `PointChargeUsage.held(chargeId, hold, amount)`는 충전 건 객체 대신 ID를 받는다. 배분 생성(`HoldCreateUseCase`)·반환(`HoldReleaseUseCase`)과 충전 원금 감소·복원은 두 Hold UseCase에서만 일어나고, 언제나 지갑 → (보류) → 충전 건 순서로 잠근 뒤 바뀐다.
- **원장 반영 검사**: `Wallet.applyLedger`는 객체 동일성(`ledger.getWallet() == this`) 대신 `ledger.walletId == wallet.id`를 확인한다. ID가 없는(저장 전) 지갑에는 반영하지 않는다. 반영 경로는 그대로 `LedgerRecordUseCase` 하나(ArchUnit)이고, 지갑은 이미 ID로 잠근 뒤 반영한다.
- **영향 없음을 확인한 것**: 지갑 ID 오름차순 락, 지갑 → 보류 → 충전 건 락 순서, `lockWallet`의 flush → 락 → refresh, 보류·반환 멱등성, `ChargeUsageHistory` 재생(배분의 `chargeId`만 쓴다), 원장 대사, 단일 트랜잭션. 기존 테스트를 고치지 않고 통과했다(엔티티 생성·조회 방식만 바꾼 테스트 제외).
    - IDENTITY 전략이라 부모 INSERT가 `save` 시점에 끝나 ID로 자식을 만들 수 있다(연관관계가 하던 INSERT 순서 보장이 필요 없다).
    - 지연 로딩 프록시로 지갑·충전 건을 읽던 곳(`hold.getWallet().getId()` 등)이 필드 읽기로 바뀌어 추가 SELECT·N+1 여지가 줄었다.
- **JPQL**: `c.wallet.id`·`l.wallet.id`·`l.originalLedger.id`를 `c.walletId`·`l.walletId`·`l.originalLedgerId`로 바꿨다(JOIN 없음, 결과 같음). 파생 쿼리 `findByChargeIdAndPointHoldId`는 새 필드 `chargeId`로 그대로 해석된다.
- **Enum**: 18개 Enum의 모든 상수에 의미를 Javadoc으로 적었다. `description` 필드는 읽는 곳(API 응답·화면)이 없어 만들지 않았고(Billing Enum은 응답 DTO에 없다), 강의 모듈 #112의 상수 Javadoc 방식을 따랐다. 상수 이름·순서·값은 그대로이고 DB 컬럼도 없다. `BillingEnumStorageTest`가 Enum 컬럼 CHECK 20개의 허용값과 상수 이름을 대조한다.
- **QueryDSL은 도입하지 않았다(팀 결정 필요)**.
    - Billing JPQL은 4개뿐이고 모두 조건이 고정된 단순 조회·합계다(동적 조건 없음, JOIN 없음). 멘토가 짚은 `sumAmountByWalletId`도 ID 필드로 바뀌어 경로 탐색이 없다. 전환 이점이 작다.
    - 원본 `com.querydsl:querydsl-jpa`는 5.1.0(2024-01)에서 멈춰 Hibernate 7.4·Jakarta Persistence 3.2(Boot 4.1) 지원이 확인되지 않는다. 관리되는 포크 `io.github.openfeign.querydsl` 7.7이 있으나 팀이 검증한 적이 없다.
    - 저장소 어느 모듈도 QueryDSL을 쓰지 않는다. 도입하면 Gradle annotationProcessor·Q 타입 생성 경로(`build/generated`, Git 제외)·CI 설정이 모든 모듈 규칙이 되므로 팀 합의 후 별도 Issue로 한다.
    - 기존 JPQL 4개와 파생 쿼리는 그대로 쓴다(#116에서는 연관 경로 `x.wallet.id`를 ID 필드 `x.walletId`로만 바꿨다).
    - **재검토 기준** (하나라도 생기면 다시 검토한다)
        1. 조건을 골라 조합하는 동적 조회가 생길 때 (예: 관리자 원장·대사·지급·환전 목록의 기간·상태·회원 필터 + `Pageable`)
        2. Billing 테이블끼리 JOIN하거나 DTO 프로젝션·집계가 여러 개로 늘어 문자열 JPQL의 오타·필드명 변경을 컴파일 시점에 잡아야 할 때
        3. 다른 모듈이 먼저 도입해 팀 공통 버전·Gradle 설정이 정해졌을 때
        4. 도입 전 확인: Hibernate 7.x·Jakarta Persistence 3.2 지원 버전, Gradle 9 annotationProcessor·멀티모듈 컴파일, IntelliJ·CI의 Q 타입 생성, 기존 비관적 락 쿼리(`@Lock`) 대체 여부

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
| `IDEMPOTENCY_KEY_CONFLICT` | 409 | 같은 작업 키에 다른 내용, 같은 참조 대상 보류에 다른 지갑·금액, 같은 보류·같은 반환 요청 ID에 다른 금액 |
| `HOLD_NOT_FOUND` | 404 | 반환할 참조 대상의 보류 없음 |
| `HOLD_OWNER_MISMATCH` | 409 | 요청 회원의 지갑 보류가 아님 |
| `HOLD_ALREADY_CLOSED` | 409 | 남은 보류가 없는(끝난) 보류에 새 반환 요청 |
| `RELEASE_AMOUNT_EXCEEDS_HELD` | 409 | 남은 보류보다 큰 반환 |
| `CHARGE_PRINCIPAL_MISMATCH` | 500 | 배분 가능 충전 원금 합계 ≠ 지갑 충전 HP 가용 잔액, 가용 원금을 넘는 배분, 충전 금액을 넘는 복원·가용 원금 상태가 아닌 충전 건에 복원 |
| `HOLD_STATE_INCONSISTENT` | 500 | 보류(반환 상세) 없이 원장만 있음, 보류와 원장 줄 수·충전 원금 배분·상세 합계 불일치, 상세 재생 결과 ≠ 배분 누적값, 충전 HP 지급 상세(소진 순서 미정) |

역할 검사(`@PreAuthorize`) 실패는 `BillingErrorCode`가 아니라 global 공통 코드로 응답한다. 로그인했으면 403 `FORBIDDEN`, 비로그인이면 401 `UNAUTHORIZED`.

응답 형식: RFC 9457 ProblemDetail + `code` 속성. #71(2026-10-13)부터 모듈 핸들러(`BillingExceptionHandler`)를 없애고 global `GlobalExceptionHandler` 하나가 응답한다. `BillingErrorCode implements BaseErrorCode`, `BillingException extends BusinessException`이며 `code`는 enum 이름 그대로다(루트 AGENTS.md 7장 "오류 응답"). 정상 응답은 record를 그대로 반환한다.

500 코드(`CHARGE_PRINCIPAL_MISMATCH`, `HOLD_STATE_INCONSISTENT`)는 불변식이 깨진 상황이다. 공통 규칙(500은 내부 메시지를 노출하지 않는다)에 맞춰 응답 `detail`은 내부 상태를 드러내지 않는 일반 문구로 두고, 원인은 `code`와 공통 핸들러의 error 로그(스택 포함)로 구분한다. 일반 예외(500 `INTERNAL_ERROR`)로 바꾸지 않고 billing 코드를 유지한 것은 테스트·로그에서 원인을 코드로 구분하기 위해서다(2026-10-13 결정, a안).

---

## 6. 테스트

- 실행: `./gradlew :billing:test --no-daemon`, 전체 `./gradlew check --no-daemon` (`ModularityTests` 포함).
- 2026-10-13 기준(#116): Billing 300개, 전체 724개 통과 (`./gradlew clean :billing:test`, `./gradlew check`, Gradle 테스트 결과 XML 합계).
- #116 추가: `BillingEnumStorageTest`(Enum 상수 = CHECK 허용값), `BillingSchemaTest`의 ID 필드 13개 컬럼 이름·NULL 제약·FK 대조, `BillingRepositoryTest`의 ID 참조 FK 거절(원장 지갑·원본 원장, 충전 건 지갑, 보류 지갑, 배분 충전 건), `WalletTest`의 ID 없는 지갑 반영 거절. `Wallet.applyLedger`의 지갑 ID 검사를 없애면 실패함을 확인했다.
- `lockWallet` 테스트(트랜잭션 필수·refresh·커밋까지 락 유지)는 refresh·`MANDATORY`를 제거하면 실패함을 확인했다.
- 보류 테스트: `HoldCreateIntegrationTest`(배분·멱등성·롤백·동시성 27개), `HoldCommandTest`, `HoldSplitTest`, `PointChargeTest`.
- 반환 테스트: `HoldReleaseIntegrationTest`(부분·전체·멱등성·동시성·비정상 상태·정합성·롤백), `ReleaseCommandTest`, `ReleaseSplitTest`, `ChargeUsageHistoryTest`, `PointHoldReleaseTest`. 지급 기능이 없어 지급된 보류는 JDBC(상세·보류) + `record()`(원장)로 만든다. 역순→FIFO, 원금 대조·재생 대조·지갑 락·보류/배분/충전 건 refresh를 각각 제거하면 실패함을 확인했다. 저장 실패 롤백은 테스트 안에서 행 트리거(`RAISE EXCEPTION`)를 잠시 걸어 만든다. 충전 API가 없어 충전 건은 JDBC INSERT + 충전 원장 `record()`로 만든다.
- **Docker가 켜져 있어야 한다.** `test-support`의 `PostgresTestcontainersConfig`가 `postgres:18` 컨테이너를 띄운다. 로컬에 PostgreSQL 설치는 필요 없다.
- `@RepositoryTest`(JPA만, 롤백) / `@IntegrationTest`(전체 컨텍스트, 테스트마다 TRUNCATE) / `TestLogin.as(userId, role)`(MockMvc 로그인).
- `@ApplicationModuleListener` 빈은 `@Async` 프록시라 직접 호출하면 바로 반환된다. 동기 검증은 `new UserJoinedEventListener(...)`, 실제 비동기 경로는 `Scenario`로 한다.
- 동시성 테스트는 실제 DB로 한다. 충돌 경로를 결정적으로 만들 때는 상대 트랜잭션이 INSERT 후 대기하게 하고 `pg_locks`의 `NOT granted`로 대기 상태를 확인한다.
- PostgreSQL 드라이버는 Billing에서 `compileOnly`라 테스트 컴파일 경로에 없다. 드라이버 예외가 필요한 단위 테스트는 리플렉션으로 만든다(`WalletCreateUseCaseTest`). 서버 언어와 무관한 판별은 `RAISE ... USING ERRCODE, CONSTRAINT`로 검증한다(`WalletDuplicateViolationTest`).
- 처음부터 통과한 핵심 테스트는 코드를 일부러 망가뜨려 실패하는지 확인했다(락 제거·락 순서·refresh·flush·재반영 방어·누적 한도·대사 격리 수준).

---

## 7. 남은 일과 열린 결정

### 다음 Issue 후보
- **Hold Payout**: 보류 HP를 받는 쪽 수익 HP로 지급. 보내는 지갑·받는 지갑을 ID 순으로 잠그고 한 트랜잭션에서 처리한다.
    - **결정 필요**: 지급이 충전 원금을 소진하는 HP 종류 순서와 배분(usage) 순서. 정한 순서를 `ChargeUsageHistory` 재생에 더해야 지급이 섞인 보류도 반환할 수 있다(지금은 충전 HP 지급 상세가 있으면 `HOLD_STATE_INCONSISTENT`).
    - 출연 호스트 일방 취소(70% 반환 + 30% 게스트 위약금)와 모집 취소는 반환과 지급이 **하나의 업무 단위**여야 한다(반환만 되고 위약금 지급이 빠지면 안 된다). 출연 `BillingClient.settle`처럼 같은 보류의 반환·지급 여러 건을 한 트랜잭션에서 처리하는 진입점을 지급 구현 때 설계한다.
- **Charge·Refund**: TossPayments 테스트 API, PG 승인과 DB 커밋 사이 상태 관리(`POINT_CHARGE` 상태·멱등키), 타임아웃을 성공·실패로 단정하지 않기.
    - 반환은 충전 원금을 역순으로 복원하므로, 환불(FIFO)이 어느 충전 건(PG 결제)을 취소할지가 반환 이력에 따라 달라진다. 환불 가능 총액은 같다.
    - 보류에 배분된 원금이 남은 충전 건은 `REFUNDED`로 바꾸면 안 된다(반환 때 원금을 되돌릴 곳이 없어 `CHARGE_PRINCIPAL_MISMATCH`). 일부 환불이면 `PARTIALLY_REFUNDED`로 둔다.
- **Settlement**: 강의 D+30·출연 확인 시 지급, 지급 대상 지갑이 없을 때의 재처리 상태(지금은 `WALLET_NOT_FOUND`로만 거절).
- **Exchange**: 수익 HP 1,000 단위, 수수료 10%, 정산일(매월 10일, 주말·공휴일이면 다음 영업일), Mock 송금.
- **다른 모듈 연동**: appearance의 `out/billing/BillingClient`가 계약 초안이다(보류·반환·지급·일괄 정산, 멱등키). Billing 쪽 공개 API·DTO가 필요하다. `billing.app`은 Modulith상 다른 모듈에 공개된 패키지가 아니다. 보류는 B안이 확정되어 출연 쪽 `HoldCommand.idempotencyKey`(호출 측 키)와 맞지 않으므로 연동 시 출연 담당과 계약을 맞춘다. `HoldCommand`·`ReleaseCommand`가 Billing Enum(`BillingReferenceType`)을 쓰므로 공개 DTO는 별도로 정해야 한다. 반환은 출연 초안의 `ReleaseCommand(idempotencyKey, ..., amount, reason)`과 달리 금액 없는 전체 반환이 있고, 사유(`reason`)는 받지 않는다(원장 `description`에 남길지 연동 때 정한다). 반환 거절도 결과값이 아니라 예외로 알린다.

### 팀과 정할 것
- QueryDSL 도입 여부와 버전(`io.github.openfeign.querydsl` 등), Q 타입 생성 규칙 (3.8).
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
