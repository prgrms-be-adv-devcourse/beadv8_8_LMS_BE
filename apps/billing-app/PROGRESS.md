# Billing 모듈 진행 기록

Billing 작업을 이어받는 사람과 AI 에이전트(Claude Code, Codex 등)가 작업 전에 읽는 문서다.
규칙은 루트 `AGENTS.md`와 `boundedContext/billing/AGENTS.md`가 우선이고, 이 문서는 지금까지 **무엇을 만들었고 그때 무엇을 검토했는지**를 기록한다. 결정과 그 상태(유효·대체됨·폐기·미결)의 원본은 [decisions.md](../../docs/billing/decisions.md)(`BD-*`)다.

- 작업을 시작하기 전에 이 문서를 읽는다.
- Issue 하나를 끝내면 "1. 진행 현황"과 "7. 남은 일"을 갱신한다. 새 결정은 `decisions.md`에 `BD-*`로 먼저 적고, 3장에는 그 번호와 배경만 더한다.
- 코드와 이 문서가 다르면 코드가 맞다. 확인 후 이 문서를 고친다.

마지막 갱신: 2026-10-30 (#428 Kafka 소비 문서 정리, #453 환불·환전 1원 단위 3.22)

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
| #116 | 멘토 리뷰 반영: Aggregate 간 JPA 연관관계 → ID 참조(13개), Enum 상수 Javadoc(18개), QueryDSL 도입 검토(보류) | `feature/tkt-116` | #124 | 완료 |
| #127 | 지갑 조회 API 성공 응답을 팀 공통 `RsData`로 감쌈(루트 AGENTS.md 7장, #118) | `feature/tkt-127` | #132 | 완료 |
| #134 | Hold 부분·전체 지급(Payout)·정상 지급 건(Settlement)·호스트 일방 취소 위약금 정산(70% 반환 + 30% 지급, #145에서 금액 계산은 출연으로 이동), 반환·지급 혼합 이력 재생, 지급 원천 전역 UNIQUE(Flyway `V202610131930`) | `feature/tkt-134` | #139 | 완료 |
| #141 | 다른 지갑의 같은 참조 대상 동시 보류에서 정상 멱등키 경쟁을 `HOLD_STATE_INCONSISTENT`로 잘못 분류하던 Race Condition 수정(조회 순서 원장 → 보류) | `feature/tkt-141` | #148 | 완료 |
| #145 | HP 보류·반환·지급·위약금 연동 계약을 `shared`(`com.hapbang.shared.billing`, `BillingPort`)로 이동, 금액 검증·멱등키 생성을 Billing으로, 위약금 비율 계산을 출연 책임으로 변경(Billing은 받은 반환액·지급액을 원자적으로 처리) | `feature/tkt-145` | #166 | 완료 |
| #182 | 강의 구매·취소에 지급 예정 등록·취소 연결(shared `LectureSettlementPort`), 출연 `BillingPortClient` Adapter 준비(기본 fake 유지), 실제 Billing 연동 테스트 | `feature/tkt-182` | #190 | 완료 (강의 지급 예정 연동은 #188에서 정리) |
| #188 | 강의 지급 정책 변경: 강의가 `BillingPort.payout`을 직접 호출, Billing 강의 지급 예정(SCHEDULED) 관리·shared `LectureSettlementPort` 제거, 구매·취소는 보류·반환만 | `feature/tkt-188` | - | 구현·테스트 완료 (PR 전) |
| #211 | 강의 자동 지급(#150)·이의 제기(#156)와 Billing 연동 정합성·잠금 순서 검증. 실제 지급 배치 + 실제 Billing 경합 테스트, 코드 결함 없음(3.14) | `feature/tkt-211` | #220 | 완료 |
| #224 | TossPayments 테스트 결제 HP 충전: 주문 생성·승인·조회 API, PROCESSING 상태·결제키 암호화·HMAC UNIQUE(Flyway `V202610151830`), PG 결과 불명확·승인 후 DB 실패 복구(재요청·결과 확인 배치) | `feature/tkt-224` | - | 구현·테스트 완료 (PR 전) |
| #241 | 종류별 보류(MIXED·CHARGE_ONLY·REVENUE_ONLY)와 확정 차감(Capture), 충전 건 지정 확정 차감·반환, Billing 내부 경로 `InternalHoldFacade`(Flyway `V202610152000`) | `feature/tkt-241` | - | 구현·테스트 완료 (커밋 전) |
| #142 | 강의 주문 항목별 판매 수익 지급 예정 건(`SCHEDULED`) 등록, KST 지급 가능 시각(구매일 + 31일 00:00), 이의 제기 중단·재개, 구매 취소 `CANCELLED`(Flyway `V202610141530`), 지급 가능 판단. 실제 지급·연동은 제외 | `feature/tkt-142` | - | 구현·테스트 완료 (PR 전) |
| #260 | TossPayments FIFO 부분 환불·PG 취소 결과 복구, PG 취소 멱등키 UUID(Flyway `V202610152100`), 실제 PG E2E 통과 | `feature/tkt-260` | #283 | 완료 |
| #285 | 수익 HP 환전 신청·테스트 계좌 스냅샷(암호화·마스킹)·REVENUE_ONLY 보류·승인 전 취소·잠정 정산 예정일(주말 보정)(Flyway `V202610161300`) | `feature/tkt-285` | #287 | 완료 |
| #288 | 가상 송금 Mock 은행 API·HTTP Client(실제 HTTP, 트랜잭션 밖 호출 강제, 멱등 기록 `mock_payout_transfer` Flyway `V202610161700`, local·test 프로필 전용) | `feature/tkt-288` | #290 | 완료 |
| #294 | 관리자 환전 승인·거절(REQUESTED → APPROVED·REJECTED, 거절 반환), Billing 회원 복제본(가입·변경 Upsert + 회원 버전 비교), Flyway `V202610162000`·`V202610162010`. 기존 회원 Backfill 제외 | `feature/tkt-294` | - | 구현·테스트 완료 (커밋 전) |
| #273 | HP 내부 HTTP API(`POST /api/v1/internal/billing/{holds,releases,payouts,penalty-settlements}`), `HoldFacade` `ROLE_INTERNAL` 인가, 요청 로그, 오류 코드 전체 대조, Billing 전체 `lock_timeout` 3초 → 503 `HP_LOCK_TIMEOUT`. `BillingPort` 유지 | `feature/tkt-273` | - | 구현·테스트 완료 (PR 전) |
| #453 | 환불·환전 1 HP 단위, 송금액 버림(송금 = floor(신청 × 90%)), 송금액 0원 환전은 은행 없이 완료, 오류 코드 `*_AMOUNT_TOO_SMALL`(Flyway `V202610301200`) | `feature/tkt-453` | (PR 전) | 구현 완료 |
| #428 | Kafka 소비 규칙(#339)에 맞춰 회원 이벤트 멱등 문서 정리: `eventId` inbox → 자연 키, 소비 그룹 `billing-app.member-replica`·`billing-app.wallet-creation`, 그룹별 DLT, 별도 멱등 기록 없음(BD-123) | `feature/tkt-428` | (PR 전) | 문서 완료 |

다음 후보는 7장, 미결 항목은 `decisions.md` 10장에 있다.

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
| app | `HoldFacade`, `HoldCreateUseCase`, `HoldReleaseUseCase`, `HoldPayoutUseCase`, `PenaltySettlementUseCase` | 보류 생성(수익 HP 우선·충전 HP FIFO)·부분/전체 반환(충전 HP 우선·충전 원금 역순 복원)·부분/전체 지급(수익 HP 우선·충전 원금 FIFO 소진)·위약금 정산(출연이 정한 반환액 + 지급액을 한 트랜잭션). 역할 검사 없는 내부 금융 기능이며 요청은 shared DTO, 결과는 Billing 내부 결과(충전 건별 배분 등)다 |
| app | `BillingKeys`, `BillingCommandValidator` | 요청으로 보류·반환·지급·위약금 멱등키를 만든다(#145에서 요청 DTO에서 옮김, 형식 그대로). shared DTO가 검사하지 않는 금액을 UseCase 시작 시 `INVALID_POINT_AMOUNT`·`PENALTY_AMOUNT_ZERO`로 거절한다 |
| app | `HoldProcessing` | 반환·지급·위약금이 함께 쓰는 보류 처리 단계(보류 락·이력 재생 대조·반환/지급 적용·지급 건 저장). 트랜잭션을 열지 않고, 호출한 UseCase가 지갑을 모두 잠근 뒤 쓴다 |
| app | `PayoutResult`, `PayoutSplit`, `PenaltyResult` | 지급 결과(충전 건별 소진 포함)·수익/충전 지급 배분, 위약금 결과(정산 기준액·반환액·지급액, 반환액 0이면 반환 결과 없음) |
| app | `ReleaseResult`, `ReleaseSplit` | 반환 결과(충전 건별 복원 포함)·충전/수익 반환 배분 |
| app | `ChargeUsageHistory` | 반환(LIFO)·지급(FIFO) 상세를 처리 순서대로 재생해 처리 건별 충전 건 복원·소진을 재구성하고 DB 누적값과 대조(스키마 A안) |
| app | `HoldResult`, `HoldSplit` | 보류 결과(충전 건별 배분 포함)·수익/충전 배분 계산 |
| app | `LedgerOperation`, `LedgerEntry`, `LedgerOperationResult` | 원장 기록 요청·결과 |
| app | `WalletReconciliationUseCase`, `WalletReconciliation` | 지갑 캐시 ↔ 원장 합계 대사 |
| in | `UserJoinedEventListener` | 가입 이벤트로 지갑 생성 (ADMIN 제외) |
| (삭제) | `LectureSettlementFacade`·`UseCase`·`Result`·`Schedule`, `in.LectureSettlementPortAdapter`, shared `LectureSettlementPort`·`LectureSettlementCommand`·`LectureSettlementResultDto`, `Settlement.scheduledLecture`·`suspend`·`resume`·`cancel`·`isPayableAt` | #142·#182에서 만든 강의 지급 예정 관리 경로. #188에서 없앴다(3.13) |
| domain | `Settlement` (#142 추가) | `scheduledLecture`(예정 건 생성), `suspend`·`resume`·`cancel`(상태 전이, 같은 상태면 false), `isPayableAt`(SCHEDULED + 예정 시각 경과) |
| in | `BillingPortAdapter` | shared `BillingPort` 구현(package-private 빈). `HoldFacade`에 위임하고 내부 결과를 shared 결과 DTO로 바꾼다. 트랜잭션을 열지 않고 예외를 바꾸지 않는다(#145) |
| shared | `shared.billing.port.BillingPort` | 강의·출연용 연동 포트: `hold`·`release`·`payout`·`settlePenalty` (#145) |
| shared | `shared.billing.dto` `HoldCommand`·`ReleaseCommand`·`PayoutCommand`·`PenaltyCommand` | 요청(구조 검사만: 필수 값·보류 가능 참조 유형·요청 ID 형식). `partial`·`full` 팩터리 유지. 키 생성·금액 검증 없음 |
| shared | `shared.billing.dto` `HoldResultDto`·`ReleaseResultDto`·`PayoutResultDto`·`PenaltyResultDto` | 외부 결과(참조 대상·요청 ID·처리 HP·남은 보류액·받는 회원·원천·`replayed`). 지갑·보류·상세·원장 ID, HP 종류별 금액, 충전 건별 배분, 보류 상태, 지급 유형은 담지 않는다 |
| shared | `shared.billing.domain` `BillingReferenceType`·`SettlementSourceType` | #145에서 `billing.domain`에서 옮김. 상수·Javadoc·저장값 그대로(Billing 엔티티가 그대로 `EnumType.STRING`으로 쓴다) |
| app | `ChargeFacade`, `ChargeOrderUseCase`, `ChargeConfirmUseCase`, `ChargeProcessing`, `ChargeRecoveryUseCase`, `ChargeRecoveryBatch`, `ChargeResult`, `ChargeRejectedException` (#224) | 충전 주문·승인·조회. Facade는 역할 검사·설정 확인만 하고 트랜잭션을 열지 않는다. `ChargeProcessing`은 DB 단계(claim·complete·fail·noteUnresolved)를 각각 짧은 트랜잭션으로, `ChargeConfirmUseCase`는 그 사이에 TossPayments를 부르고 결과를 판정한다. 배치는 오래된 PROCESSING을 조회로 확정 |
| out | `TossPaymentsClient`, `TossPaymentsProperties`, `TossPayment`, `TossResult` (#224) | 승인·주문 ID 조회 호출(Basic 인증, Idempotency-Key, 연결 3초·읽기 10초). 예외 없이 `Paid`·`Rejected`·`Unknown`으로 돌려준다 |
| out | `PaymentKeyCipher`, `PaymentKeyProperties` (#224) | 결제키 AES-256-GCM 암호화(무작위 IV)·복호화, HMAC-SHA256 중복 식별 해시 |
| in | `ApiV1WalletChargeController`, `ApiV1WalletChargeSpec`, `ChargeOrderRequest`, `ChargeConfirmRequest`, `ChargeResponse` (#224) | `POST /api/v1/wallets/charges`(201), `POST /api/v1/wallets/charges/confirm`(200 COMPLETED·202 PROCESSING), `GET /api/v1/wallets/charges/{orderId}`(200) |
| in | `ApiV1WalletController`, `ApiV1WalletSpec`, `WalletResponse` | `GET /api/v1/wallets/me` → `RsData<WalletResponse>`(200, "내 지갑을 조회했습니다."). Swagger `@Tag`·`@Operation`은 Spec 인터페이스에 두고 컨트롤러가 구현한다 |

Gradle 의존성(`build.gradle.kts`): `:shared`, `:global`(`@LoginUserId`), data-jpa, webmvc, validation·restclient(#224), `spring-security-core`(`@PreAuthorize`), `swagger-annotations-jakarta`(Spec 인터페이스), `compileOnly postgresql`(제약 이름 판별), `testImplementation security-test`.

---

## 3. 결정 배경

결정 목록·이유·현재 상태의 원본은 [decisions.md](../../docs/billing/decisions.md)다. 이 장은 Issue 당시의 분석·검토 경위·검증 기록이고, 각 절 첫 줄의 `BD-*`가 그 절에서 나온 결정이다. 이 장의 문장과 `decisions.md`가 다르면 `decisions.md`를 따른다(대체·폐기된 결정도 이 장에는 당시 문장 그대로 남아 있다). 결정을 바꾸려면 팀과 먼저 합의한다.

### 3.1 지갑 생성 (#42)

> 결정: BD-1~BD-6 (1장) — [decisions.md](../../docs/billing/decisions.md)

- **회원당 지갑 1개.** `uk_wallet_user_id` UNIQUE로 최종 보장한다.
- **가입 이벤트로 생성하고, `GET /wallets/me`에서 없으면 생성한다.** — 이벤트는 커밋 후 비동기라 늦을 수 있고, Billing 이전 가입 회원에게는 이벤트가 없다. 두 경로 모두 `WalletCreateUseCase.getOrCreate`를 써서 결과가 같다.
- **ADMIN은 지갑을 만들지 않는다.** — HP를 쓰지 않는다(루트 AGENTS 6장). 이벤트에서 건너뛰고, 조회 API는 `WalletFacade.getMyWallet`의 `@PreAuthorize("hasAnyRole('USER', 'INFLUENCER', 'VERIFIED_INFLUENCER')")`로 생성 전에 403을 낸다(2026-10-12 회의: 관리자 API 외 역할 검사는 서비스 레이어 `@PreAuthorize`). 가입 리스너는 로그인 정보가 없으므로 역할 검사 없는 `getOrCreateWallet`을 쓴다.
- **생성은 `REQUIRES_NEW` 별도 트랜잭션에서 한다.** — INSERT 실패로 오류 상태가 된 PostgreSQL 트랜잭션에서 재조회하지 않기 위해서다. 롤백이 끝난 뒤 바깥에서 재조회한다.
- **중복 판별은 PostgreSQL 드라이버 오류 정보로 한다.** — `PSQLException.getServerErrorMessage()`의 SQLState `23505` + 제약 이름 `uk_wallet_user_id`가 모두 맞을 때만 기존 지갑을 재조회한다. Hibernate는 메시지 문자열(`constraint "..."`)을 파싱해 서버 언어(`lc_messages`)가 바뀌면 깨지므로 쓰지 않는다. 다른 오류는 그대로 던진다.
- **리스너는 `@ApplicationModuleListener(propagation = NOT_SUPPORTED)`.** — 생성이 자체 트랜잭션을 열기 때문에, 리스너 트랜잭션까지 있으면 이벤트 하나가 커넥션 2개를 잡는다. 실패하면 이벤트 발행 기록이 미완료로 남아 재시작 시 재처리된다.

### 3.2 원장과 잔액 (#57)

> 결정: BD-7~BD-13 (2장) — [decisions.md](../../docs/billing/decisions.md)

- **잔액 변경은 `LedgerRecordUseCase.record()`만 한다.** — 원장 INSERT와 지갑 변경을 한 트랜잭션에서 처리한다. ArchUnit 테스트(`WalletApplyLedgerAccessTest`)가 다른 실행 코드의 `Wallet.applyLedger` 호출을 막는다.
- **재반영 방지는 원장 ID가 아니라 `pendingApplication`으로 한다.** — IDENTITY 전략은 INSERT 시점에 ID가 생기므로 ID로는 판단할 수 없다. 빌더로 만든 항목만 true이고, 반영하면 false, DB에서 읽은 항목은 기본 생성자라 false다.
- **금융 거래에서는 지갑을 자동 생성하지 않는다.** — 없으면 `WALLET_NOT_FOUND`. `getOrCreateWallet()`은 가입·내 지갑 조회 전용이다.
- **락: 지갑 ID 오름차순으로 `PESSIMISTIC_WRITE`.** — 원장 줄 순서와 무관하게 `LedgerOperation.walletIds()`(TreeSet) 순서로 잠근다. 교착 방지.
- **락 전 flush, 락 후 refresh.** — 락 조회는 영속성 컨텍스트에 이미 있던 지갑 상태를 갱신하지 않아 lost update가 생긴다(테스트로 확인). refresh가 같은 트랜잭션의 미반영 변경을 지우지 않도록 먼저 flush한다.
- **원장 금액은 부호 있는 값, 0은 금지.** 증감은 `Math.addExact`로 오버플로를 막고(`BALANCE_OVERFLOW`), 음수가 되면 `INSUFFICIENT_BALANCE`.
- **버킷 이동은 같은 작업 키의 두 줄.** `LedgerEntry.move()`가 `from -금액`, `to +금액`을 만든다. HP 총량은 변하지 않는다.

### 3.3 멱등성 (#57)

> 결정: BD-14~BD-17 (2장) — [decisions.md](../../docs/billing/decisions.md)

- **작업 키(`operationKey`) = 비즈니스 작업 하나, 원장 멱등키 = `{operationKey}:{줄번호}`.** 줄 번호는 요청의 원장 줄 순서(1부터)다.
- **지갑 락을 잡은 뒤 작업 키로 기존 원장을 조회한다.** 줄마다 멱등키·지갑·HP 종류·버킷·금액·거래 유형·참조 대상·역분개 원본이 같으면 잔액을 바꾸지 않고 기존 원장 ID를 돌려준다(`replayed=true`). 다르면 `IDEMPOTENCY_KEY_CONFLICT`. 발생 시각·설명은 비교하지 않는다.
- **같은 키가 겹치지 않는 지갑에 동시에 오면** 락으로 직렬화되지 않으므로 `uk_point_ledger_idempotency_key` UNIQUE(23505 + 제약 이름)로 막고 `IDEMPOTENCY_KEY_CONFLICT`로 바꾼다.
- **새 작업 상태 테이블은 만들지 않았다.** — 업무 요청 전체의 멱등성은 각 업무 테이블(`POINT_HOLD`, `POINT_CHARGE`, `SETTLEMENT`, `EXCHANGE`)의 멱등키와 호출하는 쪽의 결정론적 작업 키로 맡는다.

### 3.4 역분개 (#57, 원장 단위 검증까지만)

> 결정: BD-18·BD-19 (2장) — [decisions.md](../../docs/billing/decisions.md)

- 거래 유형 `REVERSAL` ⇔ `originalLedgerId` 함께 지정. RELEASE·REFUND 등 정상 거래는 원본을 가질 수 없다.
- 원본과 같은 지갑·HP 종류·버킷, 원본과 반대 부호.
- 부분·여러 번 역분개 허용. 원본별 누적 합계의 절댓값 ≤ 원본 절댓값. 같은 작업 안의 앞 줄도 함께 센다.
- 역분개의 역분개 금지. 여러 줄 원장은 줄 단위로 역분개할 수 있다.
- 역분개로 잔액이 음수가 되면 전체 롤백.
- 위반은 `INVALID_REVERSAL`, 원본이 없으면 `ORIGINAL_LEDGER_NOT_FOUND`.
- 동시 역분개는 역분개 지갑(= 원본 지갑) 락으로 직렬화되어 누적 한도를 넘지 않는다. Flyway 변경 없음.
- **실제 거래의 취소·환불·보상 처리는 각 비즈니스 서비스가** 여러 원장과 업무 상태를 함께 조정한다. Billing 원장 계층은 줄 단위 정합성만 책임진다.

### 3.5 잔액 대사 (#57)

> 결정: BD-20 (2장) — [decisions.md](../../docs/billing/decisions.md)

- `reconcile(walletId)`는 `@Transactional(readOnly = true, isolation = REPEATABLE_READ)` — 지갑과 원장 합계를 같은 스냅숏에서 읽어 동시 거래 중 오탐을 막는다(READ COMMITTED로 바꾸면 테스트가 실패함을 확인).
- 결과: 버킷 4개마다 `walletHp`, `ledgerHp`, `differenceHp`(캐시 − 원장)와 전체 `matched`. 원장이 없는 버킷은 0.
- 불일치가 있어도 원장·지갑을 고치지 않는다. 관리자 API·Scheduler는 아직 없다.

### 3.6 HP 보류 생성 (#78)

> 결정: BD-21~BD-28 (3장). BD-28의 "`@PreAuthorize` 없음"은 BD-112로 대체됐다 — [decisions.md](../../docs/billing/decisions.md)

- **참조 대상당 보류 1건, 키는 Billing이 만든다 (B안, 2026-10-13 확정).** 보류 멱등키 = 원장 작업 키 = `HOLD:{referenceType}:{referenceId}`. 호출한 쪽은 보류 멱등키를 넘기지 않는다. 참조 유형은 Enum 이름으로만 표기해 같은 대상이 다른 문자열로 중복 보류되지 않는다. 기존 `uk_point_hold_idempotency_key`가 참조당 1건을 DB에서 보장한다(스키마 변경 없음).
- **보류 단위**: 강의는 주문 항목(`LECTURE_ORDER_ITEM`, #100에서 주문 `LECTURE_ORDER`에서 바꿈), 출연 요청은 요청(`APPEARANCE_REQUEST`), 출연 모집은 모집글(`APPEARANCE_RECRUIT`). 다른 참조 유형은 `HoldCommand`에서 거절한다.
- **재요청**: 같은 참조·같은 지갑·같은 총액이면 보류·배분·원장·지갑·충전 원금을 바꾸지 않고 처음 결과(`replayed=true`)를 돌려준다. 잔액이 바뀐 뒤 재요청해도 다시 배분하지 않는다. 같은 참조에 다른 지갑·다른 금액이면 `IDEMPOTENCY_KEY_CONFLICT`.
- **비정상 상태는 재요청으로 보지 않는다.** 보류 없이 같은 작업 키의 원장만 있거나, 보류는 있는데 원장 줄 수·충전 원금 배분 합계가 맞지 않으면 `HOLD_STATE_INCONSISTENT`.
- **배분**: 가용 수익 HP를 전부 먼저 쓰고 부족분만 충전 HP. 충전 HP는 `COMPLETED`·`PARTIALLY_REFUNDED` 상태이고 가용 원금 > 0인 충전 건을 `completed_at, id` 오름차순으로 앞에서부터 채운다. 충전 건마다 `remaining_principal_hp`를 줄이고 `POINT_CHARGE_USAGE`(amount = remaining_held, consumed·released 0, `HELD`)를 남긴다. `POINT_HOLD_DETAIL`은 최초 보류에 쓰지 않는다.
- **원금 대조**: 배분 전에 배분 가능 충전 건의 가용 원금 합계가 지갑 `charge_balance_hp`와 같은지 확인하고, 다르면(수익 HP만 쓰는 보류도) `CHARGE_PRINCIPAL_MISMATCH`로 전체 롤백한다.
- **처리 순서 (한 트랜잭션)**: 회원 ID로 지갑 조회(없으면 `WALLET_NOT_FOUND`) → `LedgerRecordUseCase.lockWallet` → 기존 원장 확인 → 기존 보류 확인(#141에서 순서를 바꿈, 아래) → 수익/충전 배분 계산(부족하면 `INSUFFICIENT_BALANCE`) → 충전 건 `FOR UPDATE` 조회·원금 대조 → 보류 INSERT → 충전 원금 감소·배분 INSERT → `record()`(수익 이동 2줄, 충전 이동 2줄, 거래 유형 `HOLD`, 참조 = 업무 대상).
- **락 순서: 지갑 → 충전 건.** 같은 지갑의 보류는 지갑 락으로 직렬화되고, 충전 건 락은 방어용이다. 앞으로 충전·환불도 지갑 락을 먼저 잡아야 교착이 없다.
- **flush·refresh**: `record()`의 시작 flush가 보류·배분·충전 원금 변경을 원장보다 먼저 같은 트랜잭션에서 반영한다. 보류는 `record()` 전까지 지갑을 바꾸지 않으므로 refresh가 지울 변경이 없다. 같은 트랜잭션의 연속 보류는 `lockWallet`의 flush가 앞 보류의 지갑 변경을 지킨다(테스트로 확인).
- **다른 지갑의 같은 참조 동시 요청**은 지갑 락으로 직렬화되지 않으므로 보류 INSERT의 UNIQUE 충돌(23505 + 제약 이름)을 `IDEMPOTENCY_KEY_CONFLICT`로 바꾼다.
- **기존 기록 조회 순서 = 원장 → 보류 (#141, 2026-10-14)**: 같은 지갑 요청은 지갑 락으로 직렬화되고, 다른 지갑의 같은 참조 요청은 `uk_point_hold_idempotency_key`가 최종으로 막는다.
    - **원인**: #78 구현은 보류 → 원장 순서로 읽었다. READ COMMITTED는 SELECT마다 그 시점에 커밋된 데이터를 보므로, 두 조회 사이에 다른 지갑의 같은 참조 보류가 커밋되면 첫 조회(보류)는 비어 있고 둘째 조회(원장)에만 상대 원장이 보였다. 실제 불일치가 아닌데 "보류 없이 원장만 있음"으로 판단해 `HOLD_STATE_INCONSISTENT`(500)가 났다. 랜덤 시점 동시성 테스트 `concurrentSameReferenceDifferentWallets`도 이 순서에 걸리면 실패할 수 있었다.
    - **수정**: 원장을 먼저, 보류를 나중에 읽는다. 보류와 원장은 한 트랜잭션에서 함께 커밋되므로 먼저 읽은 원장이 보이면 나중에 읽는 보류도 반드시 보인다. 판정은 ① 보류가 있으면 `replay()`(소유 지갑·금액이 다르면 `IDEMPOTENCY_KEY_CONFLICT`) ② 보류 없이 원장만 있으면 `HOLD_STATE_INCONSISTENT`(실제 불일치) ③ 둘 다 없으면 새 보류 생성. 새 보류 INSERT가 커밋된(또는 커밋 전) 상대 보류와 부딪히면 UNIQUE 위반을 `IDEMPOTENCY_KEY_CONFLICT`로 바꾼다(기존 그대로).
    - **바꾸지 않은 것**: 지갑 락, 보류 멱등키 UNIQUE, 수익 HP 우선·충전 HP FIFO 배분, 반환·지급·위약금, 원장 규칙, 트랜잭션 경계, 스키마·Flyway, 공개 API. 새 락은 더하지 않았다.
- **잔액 부족은 예외**(`INSUFFICIENT_BALANCE`)로 알린다. 출연 쪽 `BillingClient`의 `HoldStatus.INSUFFICIENT_BALANCE` 결과로 바꾸는 것은 연동 어댑터가 한다.
- **역할 검사 없음**: `HoldFacade`는 강의·출연 서비스가 결제자 확인 후 호출하는 내부 기능이라 `@PreAuthorize`를 두지 않는다. 공개 HTTP API도 만들지 않았다.

### 3.7 HP 보류 반환 (#100)

> 결정: BD-22, BD-29~BD-35 (3장) — [decisions.md](../../docs/billing/decisions.md)

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
    - 지급(PAYOUT)의 충전 원금 소진 순서는 #134에서 FIFO로 정해 재생에 더했다(3.9).
- **보류 상태 전이**: `HELD` → (일부 처리) `PARTIALLY_PROCESSED` → 남은 보류 0이면 지급 이력 없음 `RELEASED`, 있음 `MIXED`(`released_at` 기록). 끝난 보류(남은 보류 0 또는 `RELEASED`·`PAID`·`MIXED`)에 새 요청이 오면 `HOLD_ALREADY_CLOSED`. 지급된 금액은 남은 보류에서 이미 빠져 있어 다시 반환할 수 없다. 배분 상태는 남은 보류·소진·반환으로 `HELD`/`PARTIALLY_PROCESSED`/`RELEASED`/`CONSUMED`/`MIXED`.
- **처리 순서 (한 트랜잭션)**: 회원 지갑 조회 → `lockWallet` → 보류 조회·소유 확인·`refresh(PESSIMISTIC_WRITE)` → 상세·배분 조회(배분 refresh) → 금액·재생 대조 → (같은 키 상세가 있으면 재요청 결과) → 상세 없이 원장만 있으면 `HOLD_STATE_INCONSISTENT` → 끝난 보류 거절 → 반환 배분 계산 → 충전 원금 합계 대조(`CHARGE_PRINCIPAL_MISMATCH`, 수익 HP만 반환해도 확인) → 복원 대상 충전 건 `FOR UPDATE`·refresh → 배분 반환·충전 원금 복원 → 보류 감소 → 상세 INSERT → `record()`(충전 이동 2줄 HELD→AVAILABLE, 수익 이동 2줄, 거래 유형 `RELEASE`, 참조 = 보류의 업무 대상).
- **락 순서: 지갑 → 보류 → 충전 건(ID 오름차순)**. 보류 생성의 지갑 → 충전 건과 충돌하지 않는다. 보류·배분·충전 원금은 소유 지갑 락 아래에서만 바뀌므로 지갑 락이 실제 직렬화를 맡고, 보류·충전 건 락은 방어용이다. 배분 행은 잠그지 않는다.
- **flush·refresh**: `lockWallet`이 먼저 flush하므로 이후 보류·배분·충전 건 refresh가 같은 트랜잭션의 미반영 변경을 지우지 않는다. 호출한 쪽 트랜잭션이 락 없이 미리 읽어 둔 엔티티는 refresh로 최신 상태가 된다(테스트로 확인). 반환은 `record()` 전까지 지갑을 바꾸지 않고, `record()`의 시작 flush가 보류·배분·충전 원금·상세 변경을 원장보다 먼저 반영한다.
- **`RELEASE`는 정상 보류 해제**다. 원장 정정용 `REVERSAL`(원본 원장 참조)과 구분한다.
- **강의 보류 단위 = 주문 항목 (2026-10-13 강의 담당 합의, 적용)**: `HoldCommand.HOLDABLE_REFERENCE_TYPES`의 `LECTURE_ORDER`를 `LECTURE_ORDER_ITEM`으로 바꿨다. 여러 강의를 함께 사면 항목마다 보류하고, 개별 취소는 그 항목의 보류만 반환한다. Enum 값과 DB CHECK에 이미 있어 **마이그레이션은 필요 없다**. `LECTURE_ORDER` Enum 값·CHECK는 남긴다(원장 참조 등 다른 용도, 기존 Flyway 수정 금지). 운영 코드에 보류 호출자가 아직 없어 `LECTURE_ORDER`로 만든 기존 보류는 없다. 있었다면 `ReleaseCommand`가 거절해 반환할 수 없으므로, 강의 연동 전에 보류가 만들어졌다면 확인이 필요하다. 강의 모듈 API는 이미 `orderItemId`를 쓴다(강의 코드는 바꾸지 않았다).

### 3.8 JPA 연관관계·Enum·QueryDSL (#116, 멘토 리뷰)

> 결정: BD-36~BD-38 (4장) — [decisions.md](../../docs/billing/decisions.md)

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

### 3.9 HP 지급·위약금 정산 (#134)

> 결정: BD-34, BD-39~BD-50 (5장). BD-46·BD-47(30% 계산, 위약금 0 거절)은 BD-56·BD-57로 대체됐다 — [decisions.md](../../docs/billing/decisions.md)

Issue #134에서 확정한 정책을 구현 기준으로 적용했다. 지급 가능 여부·금액·정산 기준액은 출연(이후 강의)이 업무 상태로 정하고, Billing은 지갑·보류·지급 원천·금액·멱등성·금융 정합성만 검증한다. 공개 HTTP API는 만들지 않았고 `HoldFacade.payout`·`settlePenalty`가 내부 계약이다.

- **지급(Payout) 정책**: 수익 HP 보류를 먼저, 부족분만 충전 HP 보류(`PayoutSplit`). 충전 HP는 최초 배분 순서(FIFO, 배분 ID 오름차순)로 남은 보류가 있는 배분부터 소진하고(`usage.consumed_hp` 증가), 충전 건 원금(`remaining_principal_hp`)은 되돌리지 않는다. 받는 쪽에는 출처와 관계없이 `REVENUE AVAILABLE`로 들어간다. 부분(금액)·전체(남은 보류 전부) 지급을 지원한다. 남은 보류를 넘으면 `PAYOUT_AMOUNT_EXCEEDS_HELD`.
- **보류 상태**: 지급으로 남은 보류가 0이 되면 반환 이력 없음 `PAID`, 있음 `MIXED`. `released_at`은 반환으로 끝날 때만 남긴다(지급으로 끝나면 비워 둔다). 배분은 소진만으로 끝나면 `CONSUMED`, 소진·반환이 섞이면 `MIXED`.
- **지급 건(`SETTLEMENT`) 재사용**: 기존 테이블이 내부 HP 지급(받는 회원·지갑, 원천 보류, 지급 원천, `NORMAL`/`PENALTY`, 총액·완료액, 상태, 멱등키)을 표현한다(현금 송금은 `EXCHANGE_PAYOUT`). 지급자 지갑은 보류의 `wallet_id`로 안다. 즉시 지급이라 `COMPLETED`, 총액 = 완료액, 예정·완료 시각 = 처리 시각으로 만든다(`Settlement.completed`). **스키마·Flyway 변경 없음.**
    - **지급 원천(source) = 출연 계약·주문 항목 (3차 검토 확정)**: 출연 요청 보류 → `APPEARANCE_REQUEST` + **출연 요청에서 성립한 출연 계약 ID**(요청서 ID가 아니다, 출연 `BillingClient`의 `appearanceId`), 출연 모집 보류 → `RECRUIT_APPEARANCE` + 게스트별 출연 계약 ID, 강의 주문 항목 보류 → `LECTURE_ORDER_ITEM` + 그 항목 ID(보류 참조 ID와 같아야 함). 원천 유형이 보류와 맞지 않으면 `PAYOUT_SOURCE_MISMATCH`. 게스트 회원 ID는 받는 지갑을 찾는 데만 쓰고 원천 ID로 쓰지 않는다. 출연 계약이 그 보류에 속하는지는 Billing이 알 수 없어 출연이 검증한다.
        - 2차 검토까지는 1:1 원천 ID를 요청서 ID와 같게 요구했는데, 확정 계약(출연 계약 ID)과 맞지 않아 원천 유형만 확인하도록 바꿨다.
    - **한 원천(출연 계약·주문 항목)은 어느 보류에서든 한 번만 정산** (3차 검토에서 확장): `rejectSettledSource`가 같은 원천 유형·ID의 지급 건이 **보류·지급 유형과 관계없이** 있으면 `PAYOUT_ALREADY_SETTLED`로 거절한다. 같은 계약 ID가 다른 보류(같은 호스트·다른 호스트)로 다시 와도 막는다(테스트). 출연 요청(1:1) 보류는 계약이 하나뿐이라 원천 ID가 달라도 지급 건이 이미 있으면 거절한다(테스트).
        - **DB 전역 UNIQUE (4차 검토, A안 적용)**: 새 Flyway `V202610131930__add_settlement_source_unique.sql`로 `uk_settlement_source_type_source_id UNIQUE (source_type, source_id)`를 더했다. 기존 Flyway·UNIQUE(`uk_settlement_point_hold_id_source_type_source_id_payout_type`, `uk_settlement_idempotency_key`)·FK·CHECK·인덱스는 그대로이고, `Settlement` `@Table`에도 같은 이름으로 선언했다.
            - **충돌 없음**: 1:1 출연 계약당 정상 지급 또는 위약금 하나, 1:N 게스트별 출연 계약당 하나, 강의 주문 항목당 판매자 정산 하나라는 정책과 같다. 원천 ID는 원천 유형마다 다른 모듈 테이블의 PK(출연 계약 ID·주문 항목 ID)라 유형 안에서 겹치지 않는다. 강의 정산의 예정 건(`SCHEDULED` → `COMPLETED`/`SUSPENDED`/`FAILED`)도 항목당 행 하나의 상태 전이라 호환된다. 단, 실패 후 **새 행**으로 재지급하는 설계는 이 제약과 맞지 않으므로 같은 행의 상태를 바꿔 재처리한다.
            - **기존 데이터**: 지급 건을 만드는 운영 코드는 #134가 처음이라 배포된 DB에 지급 건이 없어 중복으로 마이그레이션이 실패할 일은 없다. 배포 전 확인 쿼리는 마이그레이션 파일 주석에 있다(같은 원천 행이 있으면 `ALTER TABLE`이 실패하고 앱이 시작되지 않는다).
            - **동시성**: 받는 지갑이 같으면 받는 지갑 락으로 이미 직렬화되어 앱 확인이 막는다. 받는 지갑이 다른 경우(출연 쪽 오류로 같은 계약 ID가 다른 게스트·다른 호스트 보류로 온 경우)는 늦은 쪽이 앱 확인을 통과해도 지급 건 INSERT에서 먼저 쓴 쪽의 커밋을 기다렸다가 UNIQUE 위반(23505)이 나고, `saveSettlement`가 `PAYOUT_ALREADY_SETTLED`(409, global ProblemDetail)로 바꿔 그 트랜잭션의 보류·배분·상세·원장·지갑 변경을 모두 롤백한다(결정적 테스트: 커밋 전 대기 + `pg_locks` 대기 확인). 이긴 쪽 재요청은 처음 결과, 진 쪽 재요청은 앱 확인으로 거절된다.
    - **같은 보류·원천의 지급은 유형과 관계없이 1건** (2차 검토에서 수정): 한 출연 계약의 정상 지급(`NORMAL`)과 위약금(`PENALTY`)은 함께 성립할 수 없다. DB `UNIQUE(point_hold_id, source_type, source_id, payout_type)`는 지급 유형까지 포함해 같은 유형 두 번만 막고 NORMAL + PENALTY는 허용한다. 1차 구현의 앱 확인도 같은 유형만 봐서 **같은 게스트 출연에 정상 지급 후 위약금(또는 반대)이 둘 다 지급될 수 있었다**(테스트로 재현). 이제 `HoldProcessing.rejectSettledSource`가 같은 보류·원천의 지급이 유형과 관계없이 있으면 `PAYOUT_ALREADY_SETTLED`로 거절한다. 같은 보류의 지급은 보류 소유 지갑·보류 락으로 직렬화되므로 확인과 저장 사이에 끼어들 수 없다(스키마 변경 없음). 같은 유형 중복은 DB UNIQUE도 막는다.
    - 그래서 1:1 출연 요청은 지급(정상 또는 위약금)이 1건이고, "지급 → 추가 지급"은 모집의 다른 게스트(다른 원천)에 대해서만 된다. 지급 요청 ID·취소 ID만 바꿔 같은 원천에 다시 보내도 막힌다.
- **멱등키**: 지급 키 = 지급 상세·지급 건 멱등키 = 원장 작업 키 = `PAYOUT:{holdId}:{payoutRequestId}`. 지급 요청 ID는 보류 안에서 고유(반환 요청 ID와 같은 형식 `[A-Za-z0-9_-]{1,200}`). 같은 키 재요청은 현재 상태와 관계없이 처음 결과(`replayed=true`)를 돌려주고, 받는 지갑·원천·(부분 지급이면) 금액이 다르면 `IDEMPOTENCY_KEY_CONFLICT`.
- **원장**: 지급 한 건 = 작업 하나(`PAYOUT`, 참조 = 보류의 업무 대상). 보낸 쪽 `REVENUE HELD -수익분`, `CHARGE HELD -충전분`, 받는 쪽 `REVENUE AVAILABLE +총액`. 작업마다 줄 합계가 0(보낸 쪽 감소 = 받는 쪽 증가)이고 테스트가 확인한다.
- **보류한 회원 자신에게 지급 금지**(`PAYOUT_TO_PAYER`): 같은 지갑 안에서 충전 HP 보류가 수익 HP 가용으로 바뀌면 HP 간 전환이 된다.
- **위약금 정산**(`PenaltySettlementUseCase`): **#145에서 금액 계산 책임이 출연으로 바뀌었다(3.10). 아래 30% 계산·기준액 설명은 #134 당시 기록이다.** 위약금 = floor(기준액 × 30 / 100), 반환 = 기준액 - 위약금(30%는 루트 AGENTS.md 5장 수치, `PenaltyCommand.PENALTY_RATE_PERCENT` — #145에서 삭제). 한 트랜잭션에서 반환(기존 반환 정책) → 지급(지급 정책, `PENALTY` 지급 건) 순으로 적용한다. 반환은 충전 HP·나중 배분부터, 지급은 수익 HP·먼저 배분부터 쓰므로 기준액이 남은 보류 이하면 두 순서의 결과가 같다. 위약금이 0(기준액 1~3)이면 반환만 하고 지급 상세·지급 건을 만들지 않는다.
    - **키**: 정산 키 `PENALTY:{holdId}:{cancellationId}`(위약금 지급 건 멱등키), 반환 상세·원장 작업 `…:RELEASE`, 지급 상세·원장 작업 `…:PAYOUT`. 세 기록이 취소 ID로 묶여 재요청 때 함께 찾는다. 원장은 거래 유형이 달라(`RELEASE`·`PAYOUT`) 작업 두 개로 기록한다.
    - **재요청**: 반환 상세(+ 위약금 > 0이면 지급 상세·지급 건)가 있으면 처음 결과를 돌려준다. 원래 기준액(반환 + 지급)·받는 지갑·원천이 다르면 `IDEMPOTENCY_KEY_CONFLICT`, 일부만 있으면(한 트랜잭션이라 생길 수 없음) `HOLD_STATE_INCONSISTENT`. 다른 취소 ID로 같은 게스트 출연에 다시 정산하면 지급 건 UNIQUE로 `PAYOUT_ALREADY_SETTLED`.
    - **정산 기준액 = 취소 대상 게스트의 계약 출연료 (2026-10-13 확정)**: 출연이 취소 대상 게스트·계약 출연료·취소 가능 여부(최소 출연료 포함)를 검증하고 보낸다. 1:N은 보류 전체가 아니라 그 게스트 몫만 기준이다. Billing은 기준액을 그대로 계산에 쓰고 남은 보류 이하·받는 지갑·지급 원천 중복·멱등성만 검증한다.
    - **이전 분석 (2026-10-13)**: 정책은 "계약한 출연료의 30%"다. Billing은 기준액을 정하지 않고 명령으로 받으며 **남은 보류 이하**만 허용한다(`PENALTY_BASE_EXCEEDS_HELD`).
        - 원래 계약 금액 기준: 이미 일부 반환·지급된 보류에서 쓰면 처리된 금액까지 다시 정산해 남은 보류를 넘거나(거절) 다른 게스트·자리 몫을 침범한다(중복 정산 위험).
        - 미처리 보류액 기준: 그 출연 몫이 아직 보류에 그대로 남아 있을 때 계약 금액과 같다. 이미 일부를 지급·반환한 출연이면 남은 금액만 기준이 되어 30%가 계약보다 작아진다.
        - **권장**: 출연이 "취소된 출연의 계약 출연료"를 기준액으로 보내되, 그 출연 몫은 취소 전까지 반환·지급하지 않는다(1:1 요청은 보류 전체, 모집은 게스트 한 명 출연료). 이러면 두 기준이 같아지고 Billing의 남은 보류 이하 검사가 중복 정산을 막는다. 이미 일부 처리된 출연의 위약금 기준은 업무 정책이 없어 출연 담당과 정해야 한다.
- **게스트 식별과 이력 확인 (2차 검토)**:
    - Billing이 아는 것: 보류(지갑·참조·최초/남은 HP 종류별 금액), 상세(처리 순서·HP 종류별 금액, 키로 요청 ID·취소 ID), 지급 건(받는 회원·지갑, 원천 유형·ID, 지급 유형, 금액). 그래서 **이미 지급·위약금이 나간 게스트 출연**은 지급 건의 원천(`RECRUIT_APPEARANCE` + 출연 ID, 1:1은 `APPEARANCE_REQUEST` + 요청서 ID)으로 확인할 수 있다.
    - Billing이 모르는 것: 아직 정산 이력이 없는 게스트의 계약 출연료, 모집 인원·자리별 금액, 출연 상태(성립·취소·확인). 반환 상세에는 받는 사람·원천이 없어 "어느 게스트 몫을 반환했는지"도 모른다. 이 검증은 출연 책임이다(스키마에 게스트별 계약 금액 테이블을 만들지 않았다).
    - **연동 계약**: 게스트 회원 ID만으로는 부족하다. 지급 건 중복 방지가 원천 ID 기준이므로 **게스트별 출연(계약) ID**(`sourceId`, 모집은 `RECRUIT_APPEARANCE` 출연 ID)와 **계약 출연료**(위약금 기준액), 안정적인 취소 ID·지급 요청 ID를 함께 보내야 한다. 게스트 회원 ID는 받는 지갑을 찾는 데 쓴다.
    - Billing 금융 검증 범위: 받는 회원 지갑 존재·보낸 쪽과 다름, 보류 소유, 원천이 보류 거래 대상과 맞음, 같은 원천에 지급 이력 없음(유형 무관), 금액·기준액 ≤ 남은 보류, 키별 멱등성, 반환·지급 이력 재생 대조.
    - 같은 모집에서 한 게스트가 서로 다른 출연 ID로 두 번 지급받는 경우(1인 1회 지원 정책 위반)는 출연 정책이라 Billing이 막지 않는다.
- **위약금 0 HP는 거절 (3차 검토 확정, #145에서 "게스트 지급액 0 거절"로 바뀜 → 3.10)**: 위약금 = floor(기준액 × 30 / 100)이 0이면(기준액 1~3 HP) `PenaltyCommand`가 `PENALTY_AMOUNT_ZERO`(400)로 거절해 금융 변경이 없다. 출연의 최소 출연료(15,000 HP → 위약금 4,500·반환 10,500)는 출연이 검증하고 Billing에는 그 수치를 두지 않는다(강의도 같은 지급 엔진을 쓴다). 2차 검토까지의 "0이면 반환만" 경로는 없앴다. 그래서 위약금 정산은 항상 반환 상세 + 지급 상세 + `PENALTY` 지급 건이 함께 남고, 재요청 때 받는 회원·원천·기준액을 모두 지급 건으로 대조한다(2차 검토의 받는 회원 변경 한계가 사라졌다).
- **보류 중복 생성 (3차 검토)**: 보류 멱등키 `HOLD:{referenceType}:{referenceId}` + `uk_point_hold_idempotency_key`로 참조 대상당 보류 1건이다. 같은 지갑 동시 요청은 지갑 락으로 직렬화되어 재요청 결과를 받고, 다른 지갑 동시 요청은 UNIQUE 충돌을 `IDEMPOTENCY_KEY_CONFLICT`로 바꾼다(#78 `HoldCreateIntegrationTest`의 동시성 테스트가 이미 확인). 조회 사이에 상대 보류가 커밋되면 `HOLD_STATE_INCONSISTENT`로 잘못 분류되던 문제는 #141에서 고쳤다(3.6). 1:N 모집은 모집글 보류 하나에서 게스트별로 지급하고, 게스트별 보류는 만들지 않는다.
- **락·트랜잭션**: 지급·위약금은 보낸 쪽·받는 쪽 지갑을 `LedgerRecordUseCase.lockWallets`(flush → ID 오름차순 락 → refresh, `MANDATORY`)로 먼저 잡고 보류 → (반환할 때) 충전 건 순서로 잠근다. 반대 방향 지급·같은 게스트로의 동시 지급도 같은 순서라 교착이 없다(테스트). 기존 `HoldReleaseUseCase.release()`를 위약금에서 부르지 않는다(보낸 쪽만 잠그므로 받는 쪽을 나중에 잡으면 순서가 뒤집힌다). 대신 반환·지급 적용을 트랜잭션 없는 `HoldProcessing`으로 나눠 세 UseCase가 함께 쓴다(같은 빈 내부 호출이 아니라 self-invocation 문제 없음). 위약금의 반환과 지급은 한 트랜잭션이고, 반환을 따로 커밋하지 않는다.
- **이력 재구성**: `ChargeUsageHistory`가 `RELEASE`(역순 복원)·`PAYOUT`(FIFO 소진)을 상세 ID 순서대로 재생한다. 같은 보류의 처리는 지갑·보류 락으로 직렬화되고 락은 커밋까지 유지되므로 상세 ID 순서 = 처리 순서다(위약금은 반환 상세를 지급 상세보다 먼저 INSERT). 동시 반환·지급 뒤 재생 대조가 통과하는 것을 테스트로 확인했다.


### 3.10 연동 계약 shared 이동·위약금 계산 책임 변경 (#145, 2026-10-14)

> 결정: BD-51~BD-61 (5장). BD-54의 "shared DTO를 Controller 요청 DTO로 쓰지 않는다"는 내부 HTTP API에서 BD-111로 대체됐다 — [decisions.md](../../docs/billing/decisions.md)

Issue #145에서 확정한 정책을 적용했다. Lecture·Appearance는 `:shared`에만 의존하고 Billing은 shared 포트를 구현한다. **DB 스키마·Flyway 변경 없음.**

- **패키지** (`shared/member/{domain,dto,event}`와 같은 구조): `com.hapbang.shared.billing.port.BillingPort`, `…dto`(요청 4개·결과 4개, 구조 검사 도우미 `BillingRequests`는 package-private), `…domain`(Enum 2개).
- **연결 방식 = 별도 Adapter**: `billing.in.BillingPortAdapter`(package-private `@Component`)가 `BillingPort`를 구현해 `HoldFacade`에 위임하고 결과를 변환한다. `HoldFacade`가 직접 구현하면 반환형이 shared 결과로 바뀌어 충전 건별 배분·복원·소진·보류 상태를 확인하는 기존 통합 테스트의 검증을 잃는다. Adapter는 트랜잭션을 열지 않고 예외를 잡지 않는다. 트랜잭션은 Facade가 부르는 UseCase 프록시의 `@Transactional`(REQUIRED)이 연다(호출한 쪽이 열었으면 참여). self-invocation 없음(Adapter → Facade → UseCase는 서로 다른 빈).
- **Enum 이동**: `BillingReferenceType`·`SettlementSourceType`만 옮겼다(상수·순서·Javadoc 그대로). 엔티티(`PointLedger`·`PointHold`·`PointHoldDetail`·`Settlement`)가 shared Enum을 그대로 `EnumType.STRING`으로 쓰므로 저장 문자열·CHECK 허용값이 같다(`BillingEnumStorageTest`·`BillingSchemaTest`·저장소 테스트 통과). 내부 Enum(`BalanceBucket`·`PointType`·`PointHoldStatus`·`PayoutType`·`LedgerTransactionType` 등)은 옮기지 않았다. `BillingReferenceType`에는 Billing 내부용 값(`POINT_CHARGE`·`EXCHANGE`·`SETTLEMENT` 등)도 있지만 엔티티가 한 Enum을 쓰므로 나누지 않았고, 요청은 `HoldCommand.HOLDABLE_REFERENCE_TYPES`(강의 주문 항목·출연 요청·출연 모집)만 받는다.
- **검증 경계**:
    - shared(구조): 필수 식별자 null(`NullPointerException`), 보류할 수 없는 참조 유형·요청 ID 형식 `[A-Za-z0-9_-]{1,200}`(`IllegalArgumentException`). 이동 전에도 같은 예외였고 global 핸들러가 처리하지 못한 예외로 500 `INTERNAL_ERROR`를 응답한다(호출한 쪽 코드 오류). 응답 계약 변화 없음.
    - Billing(금융): `BillingCommandValidator`가 UseCase 트랜잭션 시작 직후(지갑 조회 전) 금액을 이동 전과 같은 코드로 거절한다(`INVALID_POINT_AMOUNT` 400, `PENALTY_AMOUNT_ZERO` 400). 이동 전에는 DTO 생성 시점이었지만, 어느 쪽이든 금융 변경 전에 예외가 나고 호출한 쪽 트랜잭션이 롤백된다. 잔액·소유·초과·원천·자기 지급·중복 정산·멱등성·원장/보류/배분 정합성은 기존 UseCase 검증 그대로다.
- **멱등키**: 키 생성(`holdKey()`·`idempotencyKey(..)`·`settlementKey(..)` 등)을 요청 DTO에서 `BillingKeys`로 옮겼다. 형식은 그대로(`HOLD:{type}:{id}`, `RELEASE:{holdId}:{id}`, `PAYOUT:{holdId}:{id}`, `PENALTY:{holdId}:{id}[:RELEASE|:PAYOUT]`)라 기존 데이터를 그대로 찾는다. 호출한 쪽은 보류 멱등키를 만들거나 넘기지 않는다. #141의 원장 → 보류 조회 순서·UNIQUE 위반 변환은 그대로다.
- **위약금 계산 책임 변경 (핵심)**: 출연이 취소 가능 여부·대상 게스트·출연 계약·계약 출연료·위약금 비율·금액(반올림)·호스트 반환액을 정한다. Billing은 비율을 계산·검증하지 않는다(`PENALTY_RATE_PERCENT`·`penaltyHp()`·`releaseHp()` 삭제).
    - `PenaltyCommand(payerUserId, referenceType, referenceId, cancellationId, receiverUserId, sourceType, sourceId, releaseAmountHp, payoutAmountHp)`. 정산 기준액 = 반환액 + 지급액(계약으로 정의, 별도 필드 없음).
    - Billing 검증: 반환액 ≥ 0·지급액 ≥ 0(음수 `INVALID_POINT_AMOUNT`), 지급액 0 `PENALTY_AMOUNT_ZERO`, 합계 overflow `INVALID_POINT_AMOUNT`, 합계 ≤ 남은 보류(`PENALTY_BASE_EXCEEDS_HELD`), 보낸 쪽 ≠ 받는 쪽(`PAYOUT_TO_PAYER`), 원천이 보류와 맞음, 같은 원천 정산 이력 없음(NORMAL·PENALTY 상호 배제, DB `UNIQUE(source_type, source_id)`), 같은 취소 ID 재요청 대조.
    - **반환액 0 허용 (1차 검토 결과 → 2차 검토에서 팀 확정)**: 위약금이 정산 기준액 전부인 비율(예: 100%)도 출연이 정할 수 있게, 반환액 0이면 반환 상세·반환 원장을 만들지 않고 지급만 기록한다(0 HP 원장 금지 유지, 보류는 반환 이력이 없으면 `PAID`). 재요청은 반환 상세가 없으면 반환액 0으로 보고 대조하며, 반환 상세 없이 반환 원장만 있으면 `HOLD_STATE_INCONSISTENT`다.
    - **재요청 대조**: 같은 취소 ID면 반환액·지급액을 **각각** 비교한다(#134는 합계 기준액만 비교했다). 합계가 같아도 배분이 다르면 `IDEMPOTENCY_KEY_CONFLICT`. 받는 지갑·원천·지급 유형도 그대로 비교한다.
    - 원자성·락: 기존 `PenaltySettlementUseCase`·`HoldProcessing`을 그대로 쓴다. 한 트랜잭션, 지갑 ID 오름차순 → 보류 → 충전 건, 반환 상세 → 지급 상세 순서(재생 순서), 중간 커밋·`REQUIRES_NEW` 없음.
    - 70:30 통합 테스트는 "출연이 계산한 7,000·3,000"을 입력으로 바꿨고, 50:50·반환 0 입력도 같은 금융 규칙으로 처리됨을 테스트한다. Billing에 70:30 전용 규칙을 만들지 않았다.
- **결과 DTO**: 호출한 쪽이 쓰는 값만 담는다. 보류 `(referenceType, referenceId, heldHp, replayed)`, 반환 `(…, releaseRequestId, releasedHp, remainingHeldHp, replayed)`, 지급 `(…, payoutRequestId, receiverUserId, sourceType, sourceId, paidHp, remainingHeldHp, replayed)`, 위약금 `(…, cancellationId, receiverUserId, sourceType, sourceId, releasedHp, paidHp, remainingHeldHp, replayed)`. 처리 금액은 처리 당시 값, 남은 보류액은 응답 시점 값(출연 초안 `Result.remainingHeldAmount`에 해당). 받는 회원은 요청 값이다(재요청에서 다르면 충돌이므로 결과와 같다).
- **잔액 부족**: 결과값이 아니라 `BillingException(INSUFFICIENT_BALANCE)`(409, `BusinessException` → global `ProblemDetail`). 같은 트랜잭션의 호출한 쪽 변경도 롤백된다. **호출한 쪽은 Billing 예외를 잡아 정상 결과로 바꾸지 않는다**(트랜잭션이 이미 롤백 전용이라 이어 쓰면 커밋 때 `UnexpectedRollbackException`이 나거나 원자성이 깨진다). 업무상 "잔액 부족 화면"이 필요하면 예외를 그대로 전파해 API 오류(409 `INSUFFICIENT_BALANCE`)로 응답한다.
- **강의 #146이 쓸 계약**: 결제 `billingPort.hold(new HoldCommand(구매자 ID, LECTURE_ORDER_ITEM, 주문 항목 ID, 결제 HP))` — 주문 항목마다 1건, 같은 요청 재시도는 `replayed=true`. 구매 취소 `billingPort.release(ReleaseCommand.full(구매자 ID, LECTURE_ORDER_ITEM, 주문 항목 ID, 안정적인 취소 요청 ID))`(예: `ORDER-ITEM-{id}-CANCEL`), 부분 반환은 `ReleaseCommand.partial`. 주문 상태 변경과 같은 `@Transactional` 메서드에서 호출한다.
- **출연 `BillingClient` 초안과의 차이** (출연 코드는 바꾸지 않았다. 교체는 출연 담당이 한다):

| 항목 | 출연 `BillingClient` 초안 | shared `BillingPort` (#145) | 출연 쪽 후속 변경 |
| --- | --- | --- | --- |
| 의존 | 출연 내부 인터페이스 + `FakeBillingClient` | `shared.billing.port.BillingPort` 주입(구현은 Billing 빈) | `BillingClient`·Fake 대신 `BillingPort` 사용(테스트 대역은 출연이 정함) |
| 참조 유형 | 출연 `RefType`(요청서·모집글) | shared `BillingReferenceType.APPEARANCE_REQUEST`·`APPEARANCE_RECRUIT` | Enum 교체 |
| 보류 멱등키 | 출연이 `{refType}-{refId}-HOLD-{n}` 생성 | Billing이 `HOLD:{type}:{id}` 생성, 대상당 1건 | 키 생성 제거 |
| 반환·지급 키 | 출연 멱등 키 | 반환 요청 ID·지급 요청 ID(보류 안에서 고유, `[A-Za-z0-9_-]{1,200}`) | 기존 키 형식(`RECRUIT-7-RELEASE-UNDERFILLED-1`)은 그대로 요청 ID로 쓸 수 있다 |
| 잔액 부족 | `HoldResult(INSUFFICIENT_BALANCE, 0)` 결과값 | `BillingException(INSUFFICIENT_BALANCE)` 409 | 결과 분기 대신 예외 전파(잡지 않는다) |
| 반환 금액 | 금액 필수 | 부분(`partial`) 또는 전체(`full`, 남은 보류 전부) | 필요 시 전체 반환 사용 |
| 사유(`Reason`) | 반환·지급에 필수 | 없음 | 출연 업무 상태로 관리(원장 기록 여부는 후속 결정) |
| 지급 | 출연 ID·게스트 ID·금액 | 받는 회원 ID + 원천(`SettlementSourceType` + 출연 계약 ID) + 부분/전체 | 1:1은 `APPEARANCE_REQUEST` + 출연 계약 ID, 1:N은 `RECRUIT_APPEARANCE` + 게스트별 출연 계약 ID |
| 위약금 | 범용 `settle(items)`(지급·반환 항목 목록) | `settlePenalty(PenaltyCommand)` 게스트 1명 = 반환액 + 지급액 한 건(한 트랜잭션) | 출연이 계산한 두 금액과 취소 ID를 보낸다. 모집 전체 취소는 게스트별 `settlePenalty` + 빈자리 `release`를 같은 트랜잭션에서 호출 |
| 결과 | `Result(remainingHeldAmount)` | 처리 금액·남은 보류액·`replayed` | `remainingHeldHp` 사용 |
| 무응답 | `BillingUnavailableException`(HTTP 가정) | 같은 프로세스 동기 호출이라 없음 | 재시도 경로 정리 |

- **2차 검토 확정 (2026-10-14)**: 1차 구조(BillingPort·DTO 8개·Enum 2개 이동·Adapter 위임·검증 분리·출연 금액 계산)를 유지하고 아래를 확정했다. 금융 정책은 새로 바꾸지 않았다.
    - **호스트 반환액 0 HP 허용**: `releaseAmountHp >= 0`, `payoutAmountHp > 0`, 합계 ≤ 남은 보류(overflow 거절). 반환 0이면 반환하지 않고 RELEASE 상세·원장을 만들지 않으며, PAYOUT 상세·지급 건·원장(호스트 `HELD` 감소, 게스트 `REVENUE AVAILABLE` 증가)만 한 트랜잭션에 기록한다. 보류 남은 금액·배분(`POINT_CHARGE_USAGE`)은 지급 규칙(수익 우선·충전 FIFO 소진)대로 바뀌고, 이후 처리는 `ChargeUsageHistory` 재생 대조를 그대로 통과한다. 재요청은 반환 상세가 없으면 반환액 0으로 보고 대조하므로 신규 거래로 처리되지 않는다(추가 지급 없음).
    - **shared 구조 검증 예외 유지**: `NullPointerException`·`IllegalArgumentException`을 그대로 쓴다. 내부 Java 계약을 잘못 쓴 프로그래밍 오류이며 global 핸들러에서 500 `INTERNAL_ERROR`가 된다(핸들러 변경 없음). Billing 비즈니스 예외(`BillingException`, 400·409 코드)와 구분한다. shared DTO를 Controller 요청 DTO로 쓰지 않는다(사용자 입력 검증은 각 모듈 요청 DTO의 `@Valid` → 400 `INVALID_REQUEST`).
    - **모듈 의존 근거**: lecture·appearance `build.gradle.kts`는 `:shared`만 의존하고 `:billing`을 의존하지 않으므로 Billing 클래스를 컴파일 경로에서 볼 수 없다(Gradle 순환 없음: billing → shared, shared → 의존 모듈 없음). `ModularityTests.verifiesModularStructure`, shared `SharedBillingDependencyTest`(ArchUnit)가 통과한다. 이전 패키지(`billing.app.*Command`, `billing.domain.BillingReferenceType`·`SettlementSourceType`)를 참조하는 코드는 없다(git mv로 옮겨 중복 클래스 없음).
    - 추가 테스트(2차): `penalty_noRelease_mixedHoldHistory`(수익·충전 혼합 보류 + 앞선 부분 반환 뒤 반환 0 위약금 → 재요청 같은 소진 내역 → 남은 보류 전체 반환이 반환·지급 혼합 이력을 재생해 역순 복원), `penalty_noRelease_payoutFailure_rollsBackAll`(지급 원장 실패 시 지급 상세·지급 건·두 지갑 롤백 후 같은 취소 ID로 정산 가능), `penalty_noRelease`에 지급액만 바꾼 재요청 충돌을 더했다.
- **검증**: `shared` `BillingCommandsTest`(구조 검사·금액 미검사), `SharedBillingDependencyTest`(ArchUnit: shared.billing → billing·다른 업무 모듈·global 의존 없음), Billing `BillingKeysTest`·`BillingCommandValidatorTest`, `BillingPortIntegrationTest`(실제 `BillingPort` 빈 주입, 결과 변환, 잔액 부족 예외, 호출한 쪽 트랜잭션 커밋·Billing 실패 시 호출한 쪽 롤백·호출한 쪽 실패 시 Billing 롤백·위약금 지급 실패 시 반환과 호출한 쪽 상태까지 롤백), `HoldPayoutIntegrationTest` 위약금 테스트 갱신(다른 비율·반환 0·반환/지급 각각 충돌), `ModularityTests`.


### 3.11 강의 판매 수익 지급 예정 건 관리 (#142, 2026-10-14) — #188에서 제거됨(3.13), 아래는 당시 기록

> 결정: BD-62 (6장, 폐기) — [decisions.md](../../docs/billing/decisions.md)

Issue #142의 확정 정책을 적용했다. 실제 지급(HP 이동)·스케줄러·강의/고객센터/회원 연동은 제외했다. 진입점은 Billing 내부 `LectureSettlementFacade`이고 shared 포트에는 추가하지 않았다(강의 연동 때 결정).

- **기존 구조 분석**: `SETTLEMENT`에는 원천(`source_type`·`source_id`)·받는 회원·받는 지갑·보류 ID·지급 유형·총액(`gross_amount_hp`)·지급액(`paid_amount_hp`)·상태·멱등키·`scheduled_at`·`settled_at`·`failed_at`·`failure_reason`이 있다. UNIQUE는 멱등키, (보류, 원천, 지급 유형), 원천(#134). 상태 CHECK에 `CANCELLED`가 없었고 취소 시각 컬럼도 없다. 지급 건을 만드는 경로는 즉시 지급의 `Settlement.completed`(바로 `COMPLETED` INSERT)뿐이었다. 반환 경로는 지급 건을 보지 않고, 지급 경로는 `rejectSettledSource`로 같은 원천의 지급 건이 있으면 거절한다.
- **CANCELLED 도입**: `SettlementStatus.CANCELLED` + 새 Flyway `V202610141530__add_settlement_cancelled_status.sql`(`ck_settlement_status`를 지우고 같은 이름으로 6개 값 허용). 기존 Flyway는 바꾸지 않았다. 취소 시각은 새 컬럼 없이 `updated_at`으로 남는다(ERD에 없는 컬럼을 더하지 않았다). `BillingEnumStorageTest`가 Enum = CHECK 허용값을 확인한다.
- **등록(`schedule`)**: 금액 > 0 확인(`INVALID_POINT_AMOUNT`) → 구매자·판매자 지갑(`WALLET_NOT_FOUND`, 자동 생성 없음) → 같은 지갑 거절(`PAYOUT_TO_PAYER`) → 구매자 지갑 락 → 주문 항목 보류 락·소유 확인(`HOLD_NOT_FOUND`·`HOLD_OWNER_MISMATCH`) → 같은 원천 지급 건이 있으면 재요청·충돌 판단 → 보류가 열려 있고 반환·지급이 없어야 함(`HOLD_ALREADY_CLOSED`) → 금액 = 보류의 최초·남은 보류액(`SETTLEMENT_AMOUNT_MISMATCH`) → `Settlement.scheduledLecture` INSERT(`HoldProcessing.saveSettlement`로 UNIQUE 위반 변환 재사용). 지갑·보류·배분·상세·원장은 바꾸지 않는다(테스트로 스냅숏 비교).
- **지급 가능 시각**: `LectureSettlementSchedule.payableAt(purchasedAt)` = 구매 시각의 KST 날짜 + 31일의 00:00 KST(Instant). 구매 시각을 입력으로 받아 현재 시각·시스템 시간대와 무관하다. 단위 테스트: 일반·새벽·자정 직전/정각·월말·연말·윤년·평년·KST/UTC 날짜 차이·보호 마지막 순간.
- **상태 전이**: `suspend`(SCHEDULED → SUSPENDED), `resume`(SUSPENDED → SCHEDULED, 예정 시각 유지), `cancel`(SCHEDULED·SUSPENDED → CANCELLED). 이미 목표 상태면 바꾸지 않고 `replayed=true`. 그 밖은 `SETTLEMENT_INVALID_STATE`(CANCELLED 재활성화, COMPLETED·PROCESSING·FAILED 전이 모두). `isPayable`/`isPayableAt` = SCHEDULED이고 예정 시각 경과.
    - FAILED는 기존 의미(같은 행 재처리 대상)를 그대로 두고 이번에 전이를 열지 않았다. 재처리 규칙(FAILED → PROCESSING → COMPLETED 등)은 지급 Issue에서 정한다.
- **멱등성·동시성**: 원천당 1행(DB UNIQUE 그대로). 재등록은 보류·판매자·받는 지갑·금액·예정 시각·지급 유형이 같으면 현재 상태 그대로 돌려주고(완료 건도 그대로), 다르면 `IDEMPOTENCY_KEY_CONFLICT`, 취소된 건이면 `SETTLEMENT_INVALID_STATE`. 같은 주문 항목 등록은 구매자 지갑·보류 락으로 직렬화되어 READ COMMITTED의 "조회 후 INSERT" 경쟁이 생기지 않고(락 이후 조회는 앞 등록의 커밋을 본다), 원천 UNIQUE가 최종 방어선이다. 상태 전이는 지급 건 행 `PESSIMISTIC_WRITE`(조회 후 refresh)로 직렬화한다. 동시 등록 8개 → 1건(나머지 재요청), 다른 판매자 동시 등록 → 1건 성공·나머지 충돌, 중단·취소 동시 → 최종 CANCELLED를 테스트했다.
- **트랜잭션**: 모든 메서드가 `@Transactional`(REQUIRED). 구매 취소는 강의가 한 트랜잭션에서 `BillingPort.release(ReleaseCommand.full(구매자, LECTURE_ORDER_ITEM, 항목 ID, 취소 요청 ID))` + `LectureSettlementFacade.cancel(항목 ID)`를 부르면 함께 커밋·롤백된다(테스트). 순서는 반환(지갑 → 보류) 뒤 취소(지급 건)로 락 순서와 같다.
- **기존 지급과의 관계**: 예정 건이 있는 주문 항목에 `BillingPort.payout`을 부르면 `rejectSettledSource`가 `PAYOUT_ALREADY_SETTLED`로 막는다(우회 금지, 테스트). 출연 NORMAL·PENALTY 경로는 바꾸지 않았다.
- **후속 지급 연계 설계 (SCHEDULED → COMPLETED, 다음 Issue)**:
    1. 판매자·구매자 지갑 ID 오름차순 락 → 주문 항목 보류 락 → 지급 건 행 락(지금 상태 전이와 같은 마지막 단계).
    2. 지급 건이 `isPayableAt(now)`(SCHEDULED + 예정 시각 경과)인지, 판매자 지급 자격(탈퇴 아님)인지 확인한다. 아니면 지급하지 않는다.
    3. 기존 `HoldProcessing.payout`(수익 우선·충전 FIFO 소진, 지급 상세) + 지급 원장을 같은 트랜잭션에서 적용하고, **새 지급 건을 INSERT하지 않고** 이 행을 `COMPLETED`(`paid_amount_hp = gross_amount_hp`, `settled_at`)로 바꾼다. 지급 상세·원장 작업 키는 이 행에서 결정론적으로 만든다(예: `PAYOUT:{holdId}:SETTLEMENT-{settlementId}`).
    4. 이 경로는 자기 행만 허용하도록 `rejectSettledSource` 대신 "같은 원천의 다른 행·완료 건이 없음"을 확인한다(즉시 지급 경로의 원천 중복 검사는 그대로 둔다).
    5. 실패하면 같은 행을 `FAILED`(`failed_at`·`failure_reason`)로 바꾸고 재처리도 같은 행을 다시 잠가 처리한다(새 행 금지).
- **판매자 탈퇴**: Billing에는 회원 상태가 없어(회원 테이블·복제 없음) 이번에는 구현하지 않았다. 탈퇴 판매자 자동 지급 금지는 회원 이벤트(`UserModifiedEvent` 등)나 지급 시 자격 확인 연동으로 후속 처리한다. `SUSPENDED`는 이의 제기 전용이며 탈퇴를 같은 상태로 표현하지 않는다(지금 모델에는 중단 사유 컬럼이 없어, 탈퇴 차단을 상태·사유 중 무엇으로 둘지 그때 정한다). 정산 금액을 소멸시키거나 구매자에게 돌려주지 않는다.
- **이번에 하지 않은 것**: 실제 지급·스케줄러·실패 재처리, 지급 완료 후 환수, 강의 #146·고객센터·회원 연동, shared 포트 확장, 공개 HTTP API.
- **2차 검토 (2026-10-14)**: 1차 설계를 유지하고 확정 정책(취소 건 재등록·재활성화 금지, FAILED 재처리는 지급 Issue, 같은 KST 날짜 재등록은 멱등, 판매자 탈퇴는 SUSPENDED와 별개)을 그대로 두었다. 결함 수정은 없고 계약 명시 2건과 방어 확인 1건만 바꿨다.
    - **보류 정합성**: 보류는 멱등키로 찾고 키와 참조 필드는 같은 요청에서 만들어지지만, 등록이 보류 필드를 직접 대조하지 않았다. `HoldProcessing.verifySource`(지급과 같은 규칙)를 한 줄 더해 찾은 보류의 참조 유형·ID가 그 주문 항목인지 확인한다(`PAYOUT_SOURCE_MISMATCH`, 키·참조 불일치 행으로 테스트, 확인을 빼면 실패함을 확인). 소유 지갑은 `lockHold`(`HOLD_OWNER_MISMATCH`), 금액은 최초·남은 보류액 모두 비교라 부분 반환·즉시 지급 이력이 있는 보류는 거절된다(테스트 추가). 실패한 등록은 지갑·충전 원금·보류·배분·상세·원장을 바꾸지 않는다(스냅숏 비교 추가).
    - **상태 전이 동시성**: 전이는 지급 건 행을 조회 후 `refresh(PESSIMISTIC_WRITE)`로 잠가 최신 상태로 판단하므로 SCHEDULED의 중단·취소, SUSPENDED의 재개·취소 동시 실행 모두 최종 CANCELLED이고 취소 뒤 요청만 `SETTLEMENT_INVALID_STATE`로 거절된다(재개·취소 동시 테스트 추가). 같은 상태 재요청은 `replayed=true`. 락 순서: 등록 지갑 → 보류, 전이 지급 건만, 이후 지급 지갑 → 보류 → 지급 건 — 전이가 지급 건 하나만 잡으므로 지금 순서 역전은 없다.
    - **순서 계약 명시**: 같은 트랜잭션에서 `cancel`을 `release`보다 먼저 부르면 지급 건 → 지갑·보류 순서가 되어 이후 지급 경로와 교착할 수 있다. 지금은 지급 건을 잠그는 다른 경로가 없어 실제 교착은 없지만, "반환 → 취소" 순서를 계약으로 UseCase·Facade 주석과 AGENTS 8.1에 적었다.
    - **지급 승인 아님 명시**: `isPayable`/`isPayableAt`은 지급 건 상태 조건(필요조건)이며 지급 승인이 아님을 주석·문서에 적었다.
    - **Payout 우회 차단**: `rejectSettledSource`가 상태와 관계없이 같은 원천의 지급 건이 있으면 거절하므로 SCHEDULED·SUSPENDED·CANCELLED(반환 없이 취소만 한 보류 포함) 모두 `BillingPort.payout`·`settlePenalty`가 `PAYOUT_ALREADY_SETTLED`이고 새 지급 건 행이 생기지 않는다(테스트 추가). DB `UNIQUE(source_type, source_id)`가 최종 방어선이다. 이후 강의 지급은 이 행을 재사용해 COMPLETED로 바꾼다.
    - **취소·반환 원자성**: 반환 → 취소 순서 트랜잭션에서 둘 다 성공하면 함께 커밋, 뒤 처리 실패면 둘 다 롤백(1차), 반환 실패(반환 상세 INSERT 실패)면 취소가 실행되지 않고 전체 롤백, 취소 실패(FAILED 건 `SETTLEMENT_INVALID_STATE`)면 먼저 한 반환까지 롤백됨을 테스트했다.
    - **후속 실제 지급 Issue에 반드시 반영할 것**: ① 지갑(ID 오름차순) → 보류 → 지급 건 락 후 `SCHEDULED`·예정 시각 재확인(락 없는 `isPayable` 결과로 지급하지 않는다) ② 강의 구매 상태(취소·환불 진행 없음)·고객센터 이의 제기 없음 확인 ③ 회원 연동으로 판매자 탈퇴 여부 확인(탈퇴면 자동 지급 금지, SUSPENDED와 다른 표현) ④ 새 행 INSERT 없이 같은 행 COMPLETED 전이(`paid_amount_hp`, `settled_at`), 즉시 지급용 `rejectSettledSource`는 자기 행을 허용하도록 분리 ⑤ 지급 상세·원장 키를 이 행에서 결정론적으로 생성 ⑥ 실패 시 같은 행 FAILED와 재처리 규칙 ⑦ 동시 지급 실행(스케줄러 중복) 테스트 ⑧ 강의 구매 취소 연동에서 반환 → 취소 순서·같은 트랜잭션 유지.


### 3.12 강의·출연 동기 연동과 실패 복구 (#182, 2026-10-14) — 강의 지급 예정 연동은 #188에서 제거됨(3.13), 출연 부분은 유효

> 결정: BD-63(폐기)~BD-66 (6장). BD-66은 확인 필요 — [decisions.md](../../docs/billing/decisions.md)

최신 `origin/dev`(`45e2d14`)를 먼저 조사해 실제로 있는 업무 기능만 연결했다. PR 병합 여부가 아니라 코드로 판단했다.

- **조사 결과**

| 모듈·기능 | 상태 | 확인한 위치 |
| --- | --- | --- |
| 강의 주문(장바구니·바로 구매) 보류 | 구현 + Billing 연동 완료(#146) | `lecture.app.OrderCreateUseCase.create`·`hold`(BillingPort.hold, 항목마다, LECTURE_ORDER_ITEM) |
| 강의 구매 취소 반환 | 구현 + Billing 연동 완료(#146) | `lecture.app.OrderCancelUseCase.cancel`(BillingPort.release full, `LECTURE-CANCEL-{id}`) |
| 강의 지급 예정 등록·취소(#142) | **업무 기능 완료 + 연동 미완료 → 이번에 연결** | 위 두 UseCase |
| 강의 직접·자동 구매 확정 | 구현, HP 이동 없음(연동 대상 아님) | `OrderConfirmUseCase.confirm`, `OrderAutoConfirmUseCase` |
| 강의 지급(#150)·판매 내역 | 미구현(`/sales` 501) | `ApiV1LectureOrderController` |
| 출연 1:1 요청 보류 | 부분 구현·Fake | `AppearanceRequestHoldUseCase.requestHold` → `BillingClient`(기본 `FakeBillingClient`), 저장 커밋 후 트랜잭션 밖 호출 |
| 출연 요청 거절·응답 전 취소 반환 | 부분 구현·Fake | `AppearanceRequestReleaseRecorder`(HpTask) → `HpTaskExecuteUseCase.execute` 커밋 후 실행, 재시도 스케줄러 없음 |
| 출연 요청 만료 반환 | 미구현 | 상태·오류 코드만 있음 |
| 출연 1:N 모집 보류 | 부분 구현·Fake(지원·선택·마감·취소 미구현) | `AppearanceRecruitHoldUseCase` |
| 출연 확인 정상 지급 | 미구현 | `AppearanceStatus.CONFIRMED` 미사용 |
| 호스트 일방 취소 위약금 | 미구현 | `AppearancePolicy.calculatePenalty`(30%)만 있음 |

- **Shared 신규 계약**: `port.LectureSettlementPort`(`schedule`, `cancel`), `dto.LectureSettlementCommand`(Billing app에서 shared로 이동, 필수 값만 검사), `dto.LectureSettlementResultDto`(주문 항목·판매자·금액·지급 가능 시각·재요청 여부, 지급 건·지갑 ID와 내부 상태는 노출하지 않음). 중단·재개는 고객센터 연동 요구가 없어 넣지 않았다. 기존 `BillingPort`는 그대로다.
- **Billing Adapter**: `in.LectureSettlementPortAdapter`(package-private `@Component`) → `LectureSettlementFacade`. 트랜잭션을 열지 않고 예외를 바꾸지 않는다.
- **강의 연결 (강의 코드 최소 변경, API·업무 규칙 변경 없음)**:
    - `OrderCreateUseCase`: `stampPaidAt` 뒤 항목마다 `LectureSettlementPort.schedule(구매자, 항목 sellerUserId, 항목 ID, priceHp, purchasedAt(KST LocalDateTime → Instant))`. 결과가 재요청이거나 금액·지급 가능 시각이 항목 값(`payoutScheduledAt`)과 다르면 `IllegalStateException`으로 롤백(기존 보류 결과 검증 D43과 같은 방식).
    - `OrderCancelUseCase`: 반환 결과 검증 뒤 `LectureSettlementPort.cancel(항목 ID)`.
    - 영향: 판매자 지갑이 없으면 주문이 `WALLET_NOT_FOUND`(404)로 실패한다(이전에는 주문만 성공했다). 판매자는 인증된 인플루언서라 가입 때 지갑이 생긴다.
    - 강의 모듈 테스트용 `fake.FakeLectureSettlementPort`(커밋된 호출만 기록, 실패 주입)를 더했다.
- **출연 Adapter 준비(연동 전환은 담당자 결정)**: `appearance.out.billing.BillingPortClient`(BillingPort 위임, `appearance.billing.mode=billing-port`일 때만 빈). 기본값 `fake`, 출연 업무·트랜잭션 코드는 바꾸지 않았다. `BillingClient` Javadoc·`appearance.yml` 주석만 갱신.
    - **팀 정책과의 차이(결정 필요)**: 이슈는 "출연 상태 변경과 보류 동일 트랜잭션"이지만 출연은 "저장 커밋 → 트랜잭션 밖 보류, 반환은 HpTask 커밋 후 실행" 설계다. 실패 복구는 요청 ID 재시도(보류는 참조당 1건 멱등, 반환은 `APPEARANCE_REQUEST-{id}-RELEASE-1`)에 기대며, `HOLD_PENDING` 재확인·HpTask 재시도 스케줄러가 아직 없다. 바꾸려면 출연 담당과 정해야 한다.
- **트랜잭션·락**: 강의 Facade `@Transactional` 하나에 강의 주문 변경·보류·지급 예정이 참여(REQUIRES_NEW 없음, 같은 DataSource·JpaTransactionManager). 락 순서: 구매 = 강의 주문 저장 → 지갑 → 보류 → (등록) 지갑 → 보류 → 지급 건 INSERT, 취소 = 강의 주문 `FOR UPDATE` → 지갑 → 보류 → 지급 건. Billing 쪽은 항상 지갑 → 보류 → 지급 건이라 역전이 없다.
- **멱등·재시도**: 주문 생성에는 멱등 키가 없고 같은 강의 중복 주문은 부분 유일 인덱스(`uk_order_item_buyer_lecture_owned`)가 보류 전에 막는다(새 주문 = 새 항목 ID라 Billing 키도 새로 생긴다). 구매 취소 재요청은 항목이 이미 CANCELED라 강의가 먼저 거절한다. Billing 쪽 보류·반환·등록·취소는 각자 멱등이다.
- **2차 검토 (2026-10-14)**: 1차 구현을 유지하고 두 정책을 확정했다(출연은 분리 트랜잭션·HpTask 유지, 강의 판매자 지갑 없으면 주문 전체 실패). 결함 수정은 없고 검증 공백 테스트 5개만 더했다.
    - **강의 롤백 매트릭스 (실제 Billing, `LectureOrderSettlementIntegrationTest` 6개)**: 보류 실패(잔액 부족, 기존 `LectureOrderBillingIntegrationTest`) → 주문 없음 / 지급 예정 등록 실패(판매자 지갑 없음 404) → 주문·보류 롤백, 구매자 잔액 그대로, 판매자 지갑 미생성 / 반환 실패(반환 상세 INSERT 트리거) → 500, 항목 PURCHASED·보류 HELD·지급 건 SCHEDULED 그대로(**2차 추가**) / 지급 건 취소 실패(FAILED) → 반환·구매 취소 롤백. 중복 구매는 부분 유일 인덱스, 중복 취소는 항목 상태(PURCHASED 아님)로 강의가 Billing 호출 전에 막는다(강의 모듈 테스트). 지급 가능 시각 = 강의 `payoutScheduledAt`(KST) 일치를 강의가 검증한다.
    - **출연 트랜잭션 경계 (코드 확인)**: `AppearanceRequestFacade.create`(트랜잭션 없음) → `createPending`(@Transactional, HOLD_PENDING 커밋) → `requestHold`(트랜잭션 없음, Billing 호출) → `markRequested`(@Transactional). 거절·취소는 Facade @Transactional 안에서 상태 + HpTask 저장, 커밋 후 `HpTaskExecuteUseCase.execute`(트랜잭션 없음, 결과 기록만 TransactionTemplate). 요청서 수정은 출연료를 바꿀 수 없어 보류액과 반환액이 어긋나지 않는다.
    - **출연 복구 보장 범위**

| 질문 | 현재 보장 | 근거·남은 일 |
| --- | --- | --- |
| 1. 보류 성공 후 REQUESTED 반영 실패 | 수동·스케줄러 재시도는 안전, **자동 복구 없음** | 같은 요청서로 `requestHold` 재호출 시 Billing 재요청(이중 보류 없음) → `markRequested` 가능(테스트). HP는 보류된 채 HOLD_PENDING으로 남는다 |
| 2. 보류 중 서버 종료 → HOLD_PENDING 재발견 | 상태는 DB에 남음, **찾는 조회·스케줄러 없음** | `AppearanceRequestRepository`에 상태만으로 찾는 메서드·인덱스 없음(TODO(scheduler)) |
| 3. 보류 실패 시 HOLD_FAILED | Billing 거절이면 별도 트랜잭션으로 기록 후 오류 전달(테스트) | 기록 자체가 실패하면 HOLD_PENDING으로 남는다. Billing 500 계열(`HOLD_STATE_INCONSISTENT` 등)도 `BusinessException`이라 HOLD_FAILED로 확정된다(영구 거절로 분류됨) |
| 4. 보류 재요청 참조 ID | 안정적: 요청서 ID → `HOLD:APPEARANCE_REQUEST:{id}` | Billing 멱등(참조당 1건) |
| 5. HpTask 생성 후 서버 종료 | 작업 행은 상태 변경과 같은 트랜잭션에 저장되어 유실되지 않음, **다시 실행할 스케줄러 없음** | 인덱스 `(status, next_attempt_at)`만 있고 조회 메서드·스케줄러 없음 |
| 6. HpTask 중복 실행 | 같은 `idempotency_key`를 반환 요청 ID로 써서 Billing이 재요청 처리 | 이중 반환 없음(테스트) |
| 7. 반환 성공 후 완료 기록 실패 | 작업이 PENDING으로 남고 재실행해도 Billing 재요청이라 안전(테스트) | 재실행 트리거는 스케줄러 필요 |
| 8. 영구 오류 vs 일시 실패 | **구분 안 됨**: 둘 다 PENDING + 1분 뒤 재시도, 오류 문구에만 Billing 코드/예외 종류 | `DEAD` 격리·재시도 한도·간격 정책 없음(TODO(scheduler)) |
| 9. fake / billing-port 모드 | 기본 fake, `billing-port`만 실제 Billing, 오타·빈 값은 빈 없음(기동 실패), 대소문자 무시 | `BillingClientModeTest`, `AppearanceBillingPortIntegrationTest` |

    - **추가 테스트(2차)**: appearance `BillingClientModeTest` 4(모드 선택), apps `AppearanceBillingPortIntegrationTest` +2(HpTask 재실행 멱등, HOLD_PENDING 보류 재요청 멱등), `LectureOrderSettlementIntegrationTest` +1(반환 실패 롤백).
- **검증(1차)**: `apps` `LectureOrderSettlementIntegrationTest`(실제 Billing: 포트 DI, 주문 → 보류 + SCHEDULED 커밋·지급 시각 일치, 판매자 지갑 없음 → 주문·보류 롤백, 구매 취소 → 반환 + CANCELLED, 지급 건 취소 실패 → 반환·구매 취소 롤백. 두 호출을 빼면 4개 실패를 확인), `AppearanceBillingPortIntegrationTest`(billing-port 모드: Adapter 주입, 요청 → 실제 보류, 잔액 부족 → `INSUFFICIENT_BALANCE`·`HOLD_FAILED`, 거절 → HpTask로 실제 반환), Billing `in.LectureSettlementPortIntegrationTest`(Adapter 결과 변환·재요청·예외 전파), 강의 `OrderCreateUseCaseTest`·`OrderCancelUseCaseTest`(Fake: 등록·취소 호출, 실패 시 롤백), shared `BillingCommandsTest`.


### 3.13 강의 지급 정책 변경 — 강의 직접 Payout (#188, 2026-10-14)

> 결정: BD-67~BD-69 (6장), 미결 BD-71 (10장) — [decisions.md](../../docs/billing/decisions.md)

팀 요청으로 강의 판매 수익 지급의 책임 경계를 바꿨다. 강의(#150)가 지급 시점·이의 제기·판매자 상태를 판단하고 shared `BillingPort.payout`을 직접 부른다. Billing은 보류·반환·지급·원장·멱등성만 맡는다(출연과 같은 구조). #142의 강의 전용 지급 예정 관리는 정리했다.

- **참조 조사**: 제거 대상(`LectureSettlementFacade`·`UseCase`·`Schedule`·`Result`, `LectureSettlementPortAdapter`, shared `LectureSettlementPort`·`Command`·`ResultDto`, `FakeLectureSettlementPort`, `Settlement.scheduledLecture`·상태 전이·`isPayableAt`, `findBySourceTypeAndSourceId`, `BillingKeys.lectureSettlement`, 오류 코드 `SETTLEMENT_NOT_FOUND`·`SETTLEMENT_AMOUNT_MISMATCH`·`SETTLEMENT_INVALID_STATE`)는 모두 #142·#182 강의 경로에서만 쓰였다. 출연·위약금·즉시 지급 경로와 작업 중인 `origin/feature/tkt-150`(강의 지급 배치, `BillingPort.payout`만 사용)은 이 클래스들을 쓰지 않는다.
- **제거·원복**: 위 클래스와 전용 테스트(`LectureSettlementIntegrationTest`·`ScheduleTest`·`PortIntegrationTest`, `SettlementTest`)를 지웠다. #142 이후 다른 커밋이 바꾸지 않은 `Settlement`·`BillingErrorCode`·`SettlementRepository`·`BillingKeys`는 #142 직전 내용으로 되돌렸다. 강의 `OrderCancelUseCase`·두 UseCase 테스트·shared `BillingCommandsTest`는 #182 직전 내용으로, `OrderCreateUseCase`는 #184(유일 인덱스 판별) 변경을 살린 채 지급 예정 호출만 뺐다(#184 시점과 같은 내용).
- **유지**: shared `BillingPort` 4개 메서드, `BillingPortAdapter`, 출연 `BillingPortClient`·fake 기본값, 지급·위약금 엔진(`HoldPayoutUseCase`·`PenaltySettlementUseCase`·`HoldProcessing`), #141 보류 동시성, `SettlementStatus`의 모든 상수(`CANCELLED` 포함, 주석만 갱신), Flyway 전부(`V202610141530` 포함). **DB 제약 변경·새 Flyway 없음.**
- **강의 경로**: 구매 = 주문 저장 → 항목마다 `hold` → 구매 시각 확정(한 트랜잭션), 구매 취소 = 주문 잠금·항목 취소 → `release(full, "LECTURE-CANCEL-{id}")`(한 트랜잭션). 구매 시 지급 건을 만들지 않는다. 지급은 #150이 `PayoutCommand.full(구매자, LECTURE_ORDER_ITEM, 항목 ID, "LECTURE-PAYOUT-{id}", 판매자, LECTURE_ORDER_ITEM, 항목 ID)`로 부르고, 성공하면 기존 지급 경로가 `COMPLETED` 지급 건을 만든다.
    - **#182 2차 정책 변화**: 구매 시점에 판매자 지갑을 보지 않으므로 판매자 지갑이 없어도 주문은 성공한다(지갑은 만들지 않는다). 판매자 지갑이 없으면 지급 때 `WALLET_NOT_FOUND`로 실패하고 지급 변경이 모두 롤백된다(#150은 항목을 CONFIRMED로 두고 다음 실행에서 다시 처리).
- **지급 중복 차단의 실제 오류 코드**: 같은 지급 요청 ID는 재요청(replay, 변경 없음). 전액 지급한 항목에 다른 요청 ID → 보류가 끝나 `HOLD_ALREADY_CLOSED`(엔진이 끝난 보류 확인을 원천 중복 확인보다 먼저 한다). 보류가 남은 항목(부분 지급 후)에 다른 요청 ID → `PAYOUT_ALREADY_SETTLED`. 어느 쪽도 HP·원장·지급 건이 늘지 않는다. 엔진 순서는 바꾸지 않았다.
- **기존 데이터 전환 (팀 결정 필요)**: PR #190(#182)이 dev에 병합된 뒤 #188 배포 전까지 dev 서버에서 강의를 구매하면 주문 항목마다 `SCHEDULED` 지급 건이, 구매 취소하면 `CANCELLED`로 바뀐 건이 남았을 수 있다(main·운영에는 #142·#182가 없다). 이런 행이 있는 주문 항목은 직접 지급이 원천 중복(`PAYOUT_ALREADY_SETTLED`)으로 막힌다(테스트로 확인). 지급 건은 삭제하지 않는다.
    - 확인 쿼리: `SELECT status, count(*) FROM billing.settlement WHERE source_type = 'LECTURE_ORDER_ITEM' AND status IN ('SCHEDULED','SUSPENDED','CANCELLED') GROUP BY status;`
    - 방안(택1, 이번에 구현하지 않음): ① dev 데이터라 DB 초기화(운영 데이터 아님, 팀 합의 필요) ② 지급 엔진이 같은 원천의 `SCHEDULED` 강의 행을 재사용해 `COMPLETED`로 바꾸는 호환 경로(복잡도 증가) ③ 해당 항목은 관리자 수동 처리. `CANCELLED` 행은 반환된 항목이라 지급 대상이 아니므로 그대로 둬도 된다.
- **2차 검토 (2026-10-14)**: 1차 방향을 유지하고 정책을 확정했다(구매 때 판매자 지갑 미확인, 지급 때 없으면 `WALLET_NOT_FOUND` 전체 롤백·자동 생성 없음, Flyway·지급 엔진 그대로, 기존 dev 데이터는 확인 후 결정·삭제/초기화 없음). 코드 결함은 없고 검증 테스트 4개만 더했다.
    - **기존 데이터 확인 범위**: 이 작업 환경에는 실행 중인 DB가 없고 dev DB는 EC2(`docker/compose.dev.yaml`)에 있어 접속하지 않았다 → **미확인**. 확인 쿼리(읽기 전용):
      `SELECT status, count(*) FROM billing.settlement WHERE source_type = 'LECTURE_ORDER_ITEM' GROUP BY status;`
      강의 지급 건을 만들 수 있었던 코드는 #182(`OrderCreateUseCase` → schedule, PR #190) 하나이고, #142 단독으로는 호출자가 없었다. 따라서 PR #190 병합 ~ #188 배포 사이 dev 서버 구매분에만 생길 수 있다(main·운영에는 #142·#182 없음). `COMPLETED` 강의 지급 건은 지급 코드(#150)가 아직 dev에 없어 생길 수 없다.
    - **기존 행이 직접 지급에 미치는 영향 (테스트로 확인)**: `SCHEDULED`·`SUSPENDED`(보류 HELD) → `PAYOUT_ALREADY_SETTLED`로 영구 차단(재시도해도 같음), `CANCELLED`(구매 취소로 보류 반환됨) → 지급 대상이 아니며 `HOLD_ALREADY_CLOSED`(행과 무관), `COMPLETED`(직접 지급 결과) → 같은 요청 replay, 다른 요청 `HOLD_ALREADY_CLOSED`.
    - **전환 추천**: 확인 쿼리 결과가 0건이면 할 일이 없다. `SCHEDULED`·`SUSPENDED`가 있으면 그 주문 항목만 대상이고, 추천은 **지급 건을 지우지 않고 별도 보정 Issue**로 처리하는 것이다. 보정은 (a) 해당 행을 그대로 두고 그 항목 지급만 관리자 보정 작업으로 처리하거나, (b) dev 데이터라면 팀 합의로 dev DB를 재생성하는 방안 중 고른다. 지급 엔진에 레거시 행 재사용 분기를 넣는 방안은 엔진 변경이라 권하지 않는다. `CANCELLED` 행은 그대로 둔다.
    - **중복 지급 오류 코드 원인 (`HoldPayoutUseCase.payout`)**: `:45-46` 두 지갑 조회(`WALLET_NOT_FOUND`) → `:49` 자기 지급 → `:52` 지갑 락 → `:53-54` 보류 락·소유·원천 → **`:59-61` 같은 지급 키 상세가 있으면 replay** → `:64` 상세 없이 원장·지급 건만 있으면 불일치 → **`:67-68` 남은 보류 0이면 `HOLD_ALREADY_CLOSED`** → **`:70` 같은 원천 지급 건이 있으면 `PAYOUT_ALREADY_SETTLED`**. 세 경우 모두 HP 이동·원장·지급 건 INSERT 전에 끝나 중복이 없고(테스트: 스냅숏 동일, 지급 건 1건), 원천 UNIQUE도 그대로다. 출연 `FakeBillingClient.payout`도 같은 순서(replay → `requireOpen` → `requireNotSettled`)라 모드 전환 때 오류 코드가 달라지지 않는다. 오류 코드·순서는 바꾸지 않았다.
    - **판매자 지갑**: 지갑 없이 구매 → 보류 HELD, 지갑 미생성. 지급 → `WALLET_NOT_FOUND`, 금융 변경 없음·보류 HELD 유지. 지갑 생성 후 같은 명령 → 지급 성공(replay 아님), 지급 건 1건(테스트).
    - **#150 호환**: `feature/tkt-150`의 `OrderPayoutUseCase.requestPayout`이 `PayoutCommand.full(item.getBuyerUserId(), LECTURE_ORDER_ITEM, item.getId(), "LECTURE-PAYOUT-" + item.getId(), item.getSellerUserId(), LECTURE_ORDER_ITEM, item.getId())`로 부르며, 테스트의 명령과 같다. 항목마다 새 트랜잭션에서 항목 상태 변경과 지급을 함께 하고 실패하면 항목을 CONFIRMED로 두어 다음 실행에서 다시 처리한다 → 판매자 지갑 누락도 지갑이 생긴 뒤 다음 배치에서 같은 요청 ID로 처리된다. 다만 `PAYOUT_ALREADY_SETTLED`(레거시 행)·`HOLD_ALREADY_CLOSED`는 재시도해도 같으므로 #150이 무한 재시도하지 않게 구분할지 정해야 한다.
    - **추가 테스트(2차)**: `LectureOrderPayoutIntegrationTest` +3 메서드(16 실행): SCHEDULED·SUSPENDED 레거시 행 차단(파라미터 2), CANCELLED 레거시 행 = `HOLD_ALREADY_CLOSED`, 판매자 지갑 없음 실패 → 지갑 생성 후 재지급.
- **PR #196 충돌 해결 (dev 병합, 2026-10-14)**: #194(#156 이의 제기)가 먼저 dev에 들어오며 `LectureSettlementPort`를 쓰기 시작해 충돌했다(apps 테스트 modify/delete 1건 + #156 코드 3곳 컴파일). #156 담당과 협의해 #188 정책으로 정리했다: `OrderObjectionResolveUseCase`는 인정 시 `release(full, "LECTURE-RETURN-{id}")`만(지급 예정 취소 호출·의존성 제거, 주문 잠금·반환액 검증 등 #156 규칙 유지), `OrderCreateViolationTest` 생성자 인자에서 포트 제거, `LectureObjectionPortTest`는 지급 예정 취소 확인만 걷어내고 "취소 실패 → 롤백" 1개는 의도(반환 단계 billing 업무 예외 → 판단 롤백)를 apps 실제 billing 테스트로 옮겼다. 충돌한 apps `LectureOrderSettlementIntegrationTest`는 삭제 유지, #156이 더한 이의 제기 시나리오는 `LectureOrderPayoutIntegrationTest`로 옮겼다(인정 → 반환만·지급 건 없음, 지급 완료 뒤 인정 → `HOLD_ALREADY_CLOSED`로 판단 롤백, 인정·지급 동시 → 한쪽만 성공·HP 중복 없음). 정책 확인: 강의는 지급된 항목·지급 예정 시각 이후의 이의 제기 접수를 막고, #150 지급 대상은 이의 제기 NONE·REJECTED만이다.
- **검증(1차)**: `apps` `LectureOrderPayoutIntegrationTest` 13개(실제 Billing): 포트 DI, 구매 → 보류만·지급 건 없음, 판매자 지갑 없어도 구매되고 지갑 미생성, 구매 취소 → 반환·지급 건 없음, 반환 실패 → 구매 취소 롤백, 전액 지급(수익 3,000 + 충전 7,000 → 지급 상세 수익 우선, 원장 3줄, 지급 건 COMPLETED, 보류 PAID, 판매자 수익 HP 증가), 같은 요청 replay(변경 없음), 다른 요청 ID 중복(전액 후 `HOLD_ALREADY_CLOSED`, 부분 후 `PAYOUT_ALREADY_SETTLED`), 잘못된 수령자·원천·보류·지갑·금액 거절, 반환된 항목 지급 거절, 지급 건 저장 실패 → 전체 롤백 후 같은 요청 재지급, 이전 방식 `SCHEDULED` 행이 있으면 지급 차단. 기존 Billing 지급·위약금(`HoldPayoutIntegrationTest` 39)·보류(30)·반환(34)·`BillingPortIntegrationTest`(11)·출연 `AppearanceBillingPortIntegrationTest`(6) 통과.

### 3.14 강의 자동 지급·이의 제기 연동 검증 (2026-10-15, 기준 `origin/dev` 70ecb2c)

> 결정: BD-70, BD-72·BD-73 (6장), 미결 BD-74 (10장) — [decisions.md](../../docs/billing/decisions.md)

#150(강의 지급 배치·판매 내역, 03e17a2 등)·#156(이의 제기 접수·판단, 18b0269 등)·#203/#209(이의 제기 철회)·#188이 모두 dev에 있다. 목표는 강의 지급을 Billing에 다시 만드는 것이 아니라, 강의가 직접 지급·반환할 때 금융 정합성과 재시도 안전성을 확인하는 것이다. **Billing·Lecture 운영 코드는 바꾸지 않았다**(결함 없음).

- **실제 호출 방식 (#150 `OrderPayoutUseCase`)**: `OrderPayoutBatch`(`@Scheduled` 기본 매일 01:00 KST, `@ConditionalOnSchedulingEnabled`) → `findPayoutDueItemIds(now)`(CONFIRMED·`payout_scheduled_at <= now`·이의 제기 NONE/REJECTED) → 항목마다 `TransactionTemplate` 새 트랜잭션: 주문 조회(잠금 없음) → `LectureOrder.payOutItem`이 `isPayable` 재검증 후 PAID_OUT → **flush(`order_item.version` 낙관적 락 확인)** → `BillingPort.payout(PayoutCommand.full(구매자, LECTURE_ORDER_ITEM, 항목 ID, "LECTURE-PAYOUT-{id}", 판매자, LECTURE_ORDER_ITEM, 항목 ID))` → `paidHp == priceHp` 확인 → 커밋. 예외는 트랜잭션 밖에서 잡아 `warn` 로그 후 다음 항목(항목은 CONFIRMED로 롤백, 다음 실행에서 같은 요청 ID로 재시도).
- **이의 제기 (#156)**: 접수(`FILED`)는 지급 예정 시각 전·PAID_OUT 아님일 때만, 항목 낙관적 락. 인정은 주문 `PESSIMISTIC_WRITE` → 항목 RETURNED → flush → `release(full, "LECTURE-RETURN-{id}")` → `releasedHp == priceHp` 확인. 기각은 항목만 REJECTED(지급 예정 시각 그대로, 다음 배치에서 지급). 철회(#203)는 NONE으로 되돌린다. Billing은 이의 제기 상태를 모른다(SUSPENDED·CANCELLED 지급 건을 쓰지 않는다).
- **잠금 순서와 교착 분석**: 지급 = `order_item` 행(UPDATE) → 지갑 2개 ID 오름차순 → 보류. 인정·구매 취소 = `lecture_order` 행 → `order_item` 행 → 구매자 지갑 → 보류. 강의 쪽에서 지갑을 먼저 잡고 주문·항목 행을 나중에 잡는 경로가 없어(주문 생성은 새 항목 INSERT 뒤 보류) 강의 행 락과 금융 락 사이에 역전이 없다. 지급은 주문 행을 잠그지 않으므로 인정과 주문 행에서 엇갈리지 않는다. Billing 엔진의 잠금 순서는 바꾸지 않았다.
- **지급·반환 경합 결과**:
    - 업무 상태: 배치가 항목을 읽은 뒤 접수·인정이 커밋되면 배치의 flush가 version 충돌로 실패해 **Billing을 부르지 않는다**(새 테스트). 배치 flush가 먼저면 늦은 쪽 UPDATE가 행 락을 기다렸다가 version 불일치로 실패한다. 지급 대상(NONE/REJECTED)과 인정 대상(FILED)은 상태로 배타적이고, 접수는 지급 예정 시각 전·지급은 그 뒤라 실제 겹침은 기한 경계뿐이다.
    - 금융: 강의 검증을 우회해 같은 보류에 `payout`·`release`가 동시에 와도 지갑 → 보류 락으로 한쪽만 성공, 늦은 쪽 `HOLD_ALREADY_CLOSED`, 상세 1건·지급 건 0/1건, 구매자 + 판매자 합 = 항목 가격(#188 충돌 해결 때의 기존 테스트). 실패한 쪽 강의 상태도 롤백된다(같은 트랜잭션).
- **오류 분류 (현재 #150은 모든 예외를 같은 방식으로 다음 실행에 재시도)**: 지급 성공으로 잘못 처리하는 경로는 없다(테스트: 레거시 SCHEDULED 행 → 두 번 실행해도 항목 CONFIRMED·금융 스냅숏 동일). 다만 재시도로 풀리지 않는 충돌(`PAYOUT_ALREADY_SETTLED`·`HOLD_ALREADY_CLOSED`·`HOLD_STATE_INCONSISTENT`·`IDEMPOTENCY_KEY_CONFLICT`)도 매일 `warn`만 남기고 영구 재시도한다 → 강의(#150) 후속: 오류 코드로 구분해 확인 필요 상태·알림으로 격리(배치 재시도 스케줄러 자체는 이번에 만들지 않음).
- **판매자 지갑·탈퇴**: 지갑 없음 → 배치가 `WALLET_NOT_FOUND`로 항목 롤백·지갑 미생성 → 지갑 생성 후 다음 실행에서 같은 요청 ID로 지급(새 테스트, 실제 배치). **판매자 탈퇴 확인은 #150 코드에 없다**(지급 대상 조회·`isPayable`에 판매자 상태 조건 없음). 탈퇴 후 최종 수익 정책이 미정이라 추가하지 않았다(팀 결정).
- **레거시 지급 건**: dev DB(EC2)에 접속할 수단이 이 환경에 없어 **미확인**(3.13 확인 쿼리 그대로). 남아 있으면 그 항목은 배치가 매일 `PAYOUT_ALREADY_SETTLED`로 실패하며 HP는 보류 그대로다(손실·중복 없음). 데이터 삭제·상태 변경·Flyway 변경은 하지 않았다.
- **추가 테스트** (`apps` `LectureOrderPayoutIntegrationTest`, 실제 지급 배치 `OrderPayoutUseCase` + 실제 Billing·PostgreSQL, `@MockitoSpyBean LectureOrderSupport`로 배치를 멈춤):
    1. 판매자 지갑 없음 → 항목 CONFIRMED·금융 무변화 → 지갑 생성 후 다음 실행에서 지급(COMPLETED 1건·원장 2줄·키 `PAYOUT:{holdId}:LECTURE-PAYOUT-{id}`) → 그다음 실행은 대상 아님.
    2. 이의 제기 접수 중엔 지급 예정 시각이 지나도 지급 안 함 → 기각 후 배치가 실제 지급(PAID_OUT REJECTED, 보류 PAID).
    3. 배치가 항목을 읽은 뒤 접수·인정(실제 Release) 커밋 → 배치 version 충돌, PAYOUT 상세·지급 건 없음, RELEASE 상세 1건, 원장 합 보존.
    4. 레거시 SCHEDULED 행 → 배치 2회 실행해도 항목 CONFIRMED, 금융 스냅숏 동일(오류를 지급 완료로 처리하지 않음).
    - 같은 클래스 javadoc의 "강의 지급 코드는 아직 dev에 없으므로" 문구를 실제 배치 검증 설명으로 고쳤다.
- **2차 검토 — 잠금 순서·데드락 (2026-10-15, `feature/tkt-211`)**: 운영 코드 변경 없음. 코드·SQL 기준 분석과 잠금 경합 재현 테스트.
    - **SQL 근거**: Hibernate 7.4.5 `PostgreSQLDialect`의 `PESSIMISTIC_WRITE` = `FOR NO KEY UPDATE`(바이트코드 확인). FK 검사의 `FOR KEY SHARE`와 충돌하지 않아 원장·상세·지급 건 INSERT가 다른 트랜잭션의 지갑·보류 락에 막히지 않는다. 낙관적 락 UPDATE(`... WHERE id = ? AND version = ?`)는 **커밋된 행의 version이 이미 다르면 대기 없이 0행**, 같으면 상대의 미커밋 행 락을 기다린 뒤 0행이 된다. `lock_timeout` 미설정(무한 대기), 교착은 PostgreSQL `deadlock_timeout`(1s) 뒤 40P01로 한쪽이 중단된다.
    - **경로별 잠금 순서** (→는 획득 순서, 모두 한 트랜잭션·커밋 시 해제):
        | 경로 | 트랜잭션 경계 | 잠금 순서 | flush 시점 |
        | --- | --- | --- | --- |
        | 자동 지급 | 항목마다 `TransactionTemplate` | `order_item`(UPDATE, version) → 지갑 2개 ID 오름차순(`FOR NO KEY UPDATE`) → `point_hold` → `point_charge_usage`(UPDATE) → INSERT 상세·지급 건·원장 | billing 호출 전 명시 flush |
        | 이의 제기 접수 | 고객센터 `InquiryUseCase.create` 트랜잭션 | `inquiry` INSERT → `order_item`(UPDATE, version) | 명시 flush |
        | 이의 제기 인정 | 고객센터 `resolveObjection` 트랜잭션 | `lecture_order`(`FOR NO KEY UPDATE`) → `inquiry`·`order_item`(·`lecture_order`) UPDATE → 구매자 지갑 → 보류 → 충전 건(ID 집합) → INSERT | 명시 flush(문의 변경도 함께 나간다) |
        | 이의 제기 기각 | 같은 트랜잭션 | `inquiry`·`order_item` UPDATE | 명시 flush |
        | 이의 제기 철회 | 고객센터 `delete` 트랜잭션 | `inquiry` UPDATE → `order_item` UPDATE | 명시 flush |
        | 구매 취소 | `LectureOrderFacade.cancel` | `lecture_order` → `order_item`(·`lecture_order`) UPDATE → 구매자 지갑 → 보류 → 충전 건 | 명시 flush |
        | 구매(보류) | `LectureOrderFacade.createOrder` | 새 `lecture_order`·`order_item` INSERT(유일 인덱스 키) → 구매자 지갑 → 충전 건 → INSERT 보류·배분·원장 → 장바구니 UPDATE | `saveAndFlush` |
        | Billing 반환·지급·위약금 | 호출한 쪽 트랜잭션 참여 | 지갑(ID 오름차순) → 보류 → 충전 건 | `lockWallet(s)`가 먼저 flush |
        | 중복 지급 배치 | 배치 2개 각자 | 늦은 쪽은 `order_item` 행 락 대기 또는 즉시 0행 → 롤백 | — |
    - **순환 여부 (검토한 경로 한정)**: 전역 순서 `lecture_order → inquiry → order_item → wallet(ID 오름차순) → point_hold → point_charge`를 모든 경로가 따른다(일부만 건너뜀). 지갑을 잡은 뒤 강의·고객센터 행을 잠그는 경로는 구매(보류 후 장바구니 UPDATE)뿐이고, 장바구니는 위 경로들이 잠그지 않는다. 지급은 `lecture_order`를 잠그지 않는다. 충전 건 락 순서는 경로마다 다르지만(보류 FIFO, 반환 ID 집합) 항상 소유 지갑 락 안이라 지갑 락이 직렬화한다. → **순환 없음**.
    - **확인된 교착 (지급·이의 제기 범위 밖)**: 같은 구매자가 같은 두 강의를 반대 순서(`[A,B]`·`[B,A]`)로 동시에 주문하면 `uk_order_item_buyer_lecture_owned` 유일 키 대기가 서로 엇갈린다. 임시 재현 테스트(행 트리거 `pg_sleep`으로 끼어들기 강제, 커밋 안 함, 삭제함)에서 **40P01 교착 → 한쪽 500 `INTERNAL_ERROR`**(기대값 409 `ALREADY_OWNED_LECTURE`), 다른 쪽 201. 교착은 보류 전 INSERT 단계라 금융 데이터는 일관됐다(항목 3·보류 3). 수정 대안(강의 담당): 주문 항목을 강의 ID 순으로 INSERT(응답 순서 영향 확인), 또는 40P01을 409·재시도로 변환. 이번에 고치지 않았다.
    - **2차 추가·보강 테스트** (`LectureOrderPayoutIntegrationTest`, 28개): 지급 vs 접수(접수 미커밋 동안 배치가 `order_item` 락 대기 → 커밋 후 0행 롤백, `pg_locks` 대기 확인), 지급 vs 인정(인정이 반환까지 하고 미커밋이어도 배치는 version이 이미 달라 대기 없이 롤백 + 이후 배치 재실행 무변화), 지급 vs 같은 주문 다른 항목 구매 취소(외부 트랜잭션이 구매자 지갑을 잡아 두 경로가 지갑에서 만나게 한 뒤 해제 → 둘 다 성공), 중복 지급 배치 2개(같은 version을 읽게 맞춤 → 지급 1회), 인정 vs 같은 주문 다른 항목 구매 취소(주문 락 대기 → 주문 CANCELED), 같은 항목 인정 2회(주문 락 대기 → 반환 1회, 늦은 쪽 `NOT_FILED`). 클래스 3회 반복 실행 모두 통과, 로그에 40P01·55P03 없음.
    - **테스트로 확인할 수 없는 잔여 위험**: 다중 인스턴스 배치 동시 실행(현재 EC2 1대, 분석상 중복 이동 없음), 출연·환전·충전 등 다른 Billing 경로와 강의 행의 조합(출연 행은 강의 행을 잠그지 않으므로 검토 대상 외), 고객센터 쪽 `Inquiry` 엔티티 버전 없음(같은 문의를 두 관리자가 동시 판단하면 강의 주문 락으로 한쪽만 성공, 고객센터 단독 동작은 미검증), Hibernate flush 시 UPDATE 순서(추정: 영속성 컨텍스트 등록 순서 `inquiry → order_item`, `hibernate.order_updates`를 켜도 엔티티 이름순이 같음 — SQL 로그로 확인하지 않았다).

### 3.15 TossPayments 테스트 결제 HP 충전 (#224, 2026-10-15, 기준 `origin/dev` 4d91633)

> 결정: BD-75~BD-81 (7장) — [decisions.md](../../docs/billing/decisions.md)

충전 HP가 시스템에 들어오는 첫 경로다. 기존 보류·반환·지급 엔진과 shared `BillingPort`는 바꾸지 않았고, 충전 확정은 기존 `LedgerRecordUseCase.record()`로만 지갑을 바꾼다.

- **기존 상태 분석**: `PointCharge`는 builder·원금 배분/복원만 있었고 UseCase·API·PG 연동이 없었다. `PointChargeStatus`(PENDING·COMPLETED·PARTIALLY_REFUNDED·REFUNDED·FAILED)로는 "승인 요청을 보냈지만 결과를 모름"을 표현할 수 없었다 → PENDING으로 두면 같은 주문의 동시 승인 요청을 막을 수 없고, FAILED로 두면 실제 승인된 결제를 잃는다. `pg_payment_key` UNIQUE는 무작위 IV 암호문에는 의미가 없다.
- **Flyway `V202610151830__alter_billing_point_charge_for_toss_confirm.sql` (새 파일, 기존 파일 수정 없음)**: 상태 CHECK에 PROCESSING 추가, `pg_payment_key` VARCHAR(255→512, 암호문 길이), `pg_payment_key_hash` VARCHAR(64) + `uk_point_charge_pg_payment_key_hash`, `failure_reason` TEXT, `idx_point_charge_2 (status, updated_at)`. 기존 UNIQUE(`pg_order_id`·`pg_payment_key`)는 유지. 기존 데이터: 충전 기능이 없어 운영·개발 DB에 충전 행이 생길 경로가 없었다(테스트만 직접 INSERT) → 새 컬럼은 NULL 허용이라 기존 행과 호환. **ERD에 없는 컬럼 2개(`pg_payment_key_hash`, `failure_reason`)를 더했다** — 결제키 암호화 규칙과 중복 식별·운영자 확인 요구 때문이며 ERD 갱신은 팀 공유 필요.
- **상태 전이**: PENDING(주문) → PROCESSING(승인 요청 시작, 결제키 저장) → COMPLETED(승인 확인 + 원장) | FAILED(승인 안 됨 확정). PROCESSING은 `failure_reason`에 마지막 미확정 사유를 남기며 유지된다.
- **트랜잭션 경계**: Facade·`ChargeConfirmUseCase`는 트랜잭션을 열지 않는다. `ChargeProcessing.claim`(충전 건 락만, 지갑 락 없음) 커밋 → TossPayments 승인(트랜잭션 밖) → `complete`(지갑 → 충전 건 락, 보류 생성의 지갑 → 충전 건과 같은 순서) 또는 `fail`·`noteUnresolved`. claim이 지갑을 잠그지 않아 보류·반환과 순환 대기가 없다. 충전 건 PENDING·PROCESSING은 `findAllocatableForUpdate`(COMPLETED·PARTIALLY_REFUNDED만) 대상이 아니라 보류 배분과 락이 겹치지 않는다.
- **결과 판정·복구**: 4xx(ALREADY_PROCESSED_PAYMENT·IDEMPOTENT_REQUEST_PROCESSING 제외) = FAILED. 5xx·시간 초과·연결 끊김·이미 처리됨 = 조회로 판단(DONE 확정, ABORTED·EXPIRED·CANCELED·PARTIAL_CANCELED 실패, READY·IN_PROGRESS는 결과 확인에서만 같은 멱등키로 승인 재요청, 나머지는 PROCESSING 유지). 승인 후 DB 반영 실패는 PROCESSING + 결제키가 이미 커밋되어 있으므로 같은 결제의 재요청 또는 `ChargeRecoveryBatch`(기본 5분, 마지막 변경 1분 경과, 100건)가 조회로 확정한다. 승인 결과가 주문과 다르면 충전하지 않고 `CHARGE_STATE_INCONSISTENT` + 사유(운영자 확인).
- **멱등성·동시성**: 같은 주문 동시 승인 → 첫 요청만 claim(행 락), 나머지는 PROCESSING을 보고 조회 경로(Recover)로 간다. TossPayments `Idempotency-Key: hapbang-charge-confirm-{orderId}`로 PG 중복 승인 방지. 충전 원장 작업 키 `CHARGE:{chargeId}` + complete의 상태 재확인으로 HP 한 번. 결제키 HMAC UNIQUE가 다른 주문의 같은 결제키를 최종 차단(`CHARGE_PAYMENT_KEY_CONFLICT`).
- **보안**: 결제키 AES-256-GCM(무작위 12바이트 IV, 128비트 태그, "v1:" + Base64) + HMAC-SHA256(별도 키). 응답·로그에 결제키·시크릿 키 없음(`TossPayment`·`ChargeConfirmRequest`·`RecoveryTarget`·`TossPaymentsProperties`의 `toString` 마스킹). 환경 변수 3개, compose dev·prod에 빈 기본값으로 전달(없으면 503, 앱 기동은 됨), `.env.example`에 생성 방법.
- **API (프론트 연동)**: ① `POST /api/v1/wallets/charges {amount}` → 201 `{orderId, orderName, amount, status: PENDING}` ② 결제창(클라이언트 키는 프론트 보관) `requestPayment({orderId, orderName, amount, successUrl, failUrl})` ③ successUrl에서 쿼리 `paymentKey·orderId·amount`를 `POST /api/v1/wallets/charges/confirm`으로 → 200 COMPLETED / 202 PROCESSING(같은 요청 재전송 또는 `GET /api/v1/wallets/charges/{orderId}`로 확인) / 400 `CHARGE_PAYMENT_REJECTED`(detail·pgCode) ④ 잔액은 `GET /api/v1/wallets/me`.
- **검증 범위**: 실제 TossPayments 테스트 API는 **호출하지 않았다**(이 환경에 테스트 키 없음). PG 응답은 JDK `HttpServer` 기반 `FakeTossPaymentsServer`가 TossPayments 문서의 승인·조회·오류 코드·멱등키 동작을 재현한다. 실제 결제창 → 승인 연동은 키를 넣은 dev 서버에서 따로 확인해야 한다.
- **2차 검토 (2026-10-15)**: 실제 코드에서 결함 4건을 찾아 테스트로 재현(수정 전 실패 확인)한 뒤 최소 수정했다. BillingPort·보류 엔진·Flyway는 그대로.
    1. HMAC UNIQUE 경합이 409가 아니라 500: `claim`이 `EntityManager.flush()`를 직접 불러 Hibernate `ConstraintViolationException`이 Spring 예외로 바뀌지 않았다 → `PointChargeRepository.saveAndFlush`로 바꿨다(롤백·PG 미호출은 원래도 맞았다).
    2. PG `PARTIAL_CANCELED`를 FAILED로 끝냄: 승인 후 일부만 취소되어 돈 일부가 PG에 남는데 추적에서 빠진다 → PROCESSING + 사유(운영자 확인). CANCELED(전액 취소)는 FAILED 유지.
    3. 결과 확인 배치 기아: 같은 사유로 미확정이면 엔티티 변경이 없어 `updated_at`이 그대로 → 오래된 순 100건이 계속 같은 미확정 건 → 100건이 넘으면 새 건을 복구하지 못한다 → 미확정 기록 때 `touchUpdatedAt`으로 확인 시각을 항상 갱신.
    4. 복호화 실패가 복구 전체를 막음: `recoveryTarget`이 먼저 복호화해 예외 → 결제키가 필요 없는 조회 확정(DONE)도 실패 → 복호화 실패 시 결제키 null, 조회 확정은 진행, 재승인만 `PAYMENT_KEY_UNREADABLE`로 미확정.
    - 추가 테스트(`ChargeIntegrationTest` 23 → 30): HMAC UNIQUE 경합(상대 미커밋 INSERT + `pg_locks` 대기 확인), 재요청 1 + 배치 2 동시 복구(원장 1·HP 1), FAILED 주문은 PG DONE이어도 COMPLETED 불가(재요청·배치·확정 단계), 다른 결제키로 승인된 결제 미충전, PARTIAL_CANCELED 미확정, 미확정 반복 시 `updated_at` 갱신, 복호화 불가 시 DONE 확정·재승인 보류.
    - 분석으로 확인(테스트 없음): PG 멱등키 15일 이후 재요청은 결제 세션(인증 후 10분)이 먼저 만료되어 READY 재승인 경로가 생기지 않는다. `SimpleClientHttpRequestFactory`(HttpURLConnection)는 재사용 연결에서 POST를 한 번 재전송할 수 있지만 Idempotency-Key가 PG 중복 승인을 막는다. 4xx = PG가 요청을 처리하지 않음 = 승인 안 됨이므로 FAILED는 돈을 잃지 않는다(설정 오류 401도 같은 처리 — 사용자는 새 주문 필요).
- **실제 TossPayments 테스트 결제 검증 (2026-10-15, 로컬)**: `local` 프로필 + 테스트 키(`test_sk_`, API 개별 연동)로 앱 실행 → 결제창(v2 SDK `payment().requestPayment`, 레포 밖 임시 페이지) 카드 인증 → `confirm` 200 COMPLETED, TossPayments 조회 `DONE`·10,000. 충전 건 COMPLETED·원금 10,000, 지갑 충전 HP 10,000, 원장 `CHARGE:1` 1줄(원장 합 = 지갑 = 원금 합), 같은 결제 재요청 2회 모두 처음 결과·PG 재호출 없음·원장 1줄. 가짜 결제키 승인은 실제 PG가 404 `NOT_FOUND_PAYMENT_SESSION` → 400 `CHARGE_PAYMENT_REJECTED`·FAILED.
    - **실제와 가짜 서버의 차이**: 인증만 하고 승인 전인 주문을 실제 TossPayments로 조회하면 **404 `NOT_FOUND_PAYMENT`**다(가짜 서버는 READY). 그래서 "승인 요청이 PG에 닿지 않음 → 조회 READY → 같은 멱등키로 재승인" 복구는 실제로 일어나지 않고 PROCESSING(운영자 확인)으로 남는다. 금융 위험은 없으나 후속 보완 필요(예: PROCESSING 후 10분 안이면 조회 404에도 같은 멱등키로 재승인).
- **PR 전 PG 오류 처리 보완 (2026-10-15, 커밋 `92017c1`)**: ① 조회 404 → `TossResult.NotFound`, 결과 확인(배치·같은 결제 재요청)에서만 같은 멱등키로 재승인(승인 요청 직후 경로는 재승인하지 않음). 재승인이 거절(세션 만료 등)이면 FAILED, 승인이면 확정, 모르면 PROCESSING. ② 4xx 분류를 상태 + 코드 기준으로: 401·409·429와 ALREADY_PROCESSED_PAYMENT·IDEMPOTENT_REQUEST_PROCESSING은 결과 불명확(PROCESSING 유지), 나머지 4xx는 FAILED. 가짜 서버도 실제처럼 승인 전 결제 조회를 404로 바꾸고 409·429·401·세션 만료 장애를 더했다(`ChargeIntegrationTest` 34, `TossPaymentsClientTest` 6, Billing 415).
    - 자동 복구 범위: 승인 요청이 PG에 닿지 않았고 결제 세션(인증 후 10분)이 남아 있으면 배치(마지막 변경 1분 경과, 5분 주기)가 재승인해 충전. 세션 만료면 FAILED(돈 안 나감). 운영자 범위: PG가 첫 요청을 5xx로 처리해 같은 멱등키 응답이 5xx로 굳은 경우(재승인도 같은 5xx), 조회 5xx 지속, 결제키 복호화 불가 + 404, PARTIAL_CANCELED·WAITING_FOR_DEPOSIT·승인 결과 불일치.
- **정하지 않은 것(팀·프론트)**: 결제창 방식(결제위젯 vs 결제창 SDK, 키 종류가 다름), successUrl·failUrl, 최대 충전 금액, PENDING 주문 만료(현재 영구 PENDING, PG 세션은 인증 후 10분에 만료), 결제 후 화면 이동, 가상계좌 등 비동기 결제수단(웹훅 필요, 현재 PROCESSING 유지·운영자 확인).

### 3.16 종류별 보류와 확정 차감 (#241, 2026-10-15, 기준 `origin/dev` 5d58307)

> 결정: BD-82~BD-86 (7장) — [decisions.md](../../docs/billing/decisions.md)

- **기존 구조**: `point_hold`(HP 종류별 최초·남은 보류, 상태) + 별도 테이블 `point_hold_detail`(반환·지급 한 건씩, 충전 건 정보 없음) + `point_charge_usage`(충전 건별 배분: 남은 보류·소진·반환). 상세가 어느 충전 건에서 처리됐는지는 규칙(반환 역순·지급 순서)으로 재생해 배분 누적값과 대조한다(`ChargeUsageHistory`).
- **재사용**: 새 테이블 없음. 보류 종류는 `point_hold.hold_type`(기본 MIXED), 확정 차감은 `point_hold_detail.action_type = CAPTURE`, 충전 건 지정은 nullable `target_charge_id`(FK → point_charge). 소진은 `point_charge_usage.consumed_hp`(지급과 공유)라 배분 불변식·재생 대조가 그대로 동작한다. 보류 상태에 `CAPTURED` 추가.
- **왜 충전 건 지정인가**: 환불은 PG 결제(충전 건)마다 취소 결과가 다르다. 규칙(FIFO)으로 확정 차감하면 실제로 취소된 결제와 다른 충전 건의 원금을 차감해, 남은 원금이 실제 PG 잔액과 어긋난다(이후 환불이 이미 취소된 결제를 다시 취소하려 함). 그래서 CHARGE_ONLY 보류는 충전 건을 지정해 확정 차감·반환할 수 있게 했다.
- **동시성·정합성**: 확정 차감도 지갑 락 → 보류 락 → (반환 시) 충전 건 락 순서, 상세 UNIQUE, 상태 재확인. 보류·배분·상세·원장·지갑이 한 트랜잭션. 기존 반환·지급의 최종 상태 판단에 CAPTURE 이력을 더했다(기존 보류는 CAPTURE가 없어 결과 불변).
- **Migration** `V202610152000__alter_billing_hold_for_capture.sql`: hold_type + CHECK 2개, 상태 CHECK에 CAPTURED, 상세 동작 CHECK에 CAPTURE, target_charge_id + CHECK(지정이면 충전 HP만) + FK. 기존 행은 MIXED·NULL로 그대로 호환.
- **테스트**: `HoldCaptureIntegrationTest` 18(종류별 보류·잔액 부족·전체/부분/추가 확정 차감·확정 후 반환·재요청·동시 서로 다른 요청 8·같은 요청 동시·확정 차감 vs 반환 동시·지급과 보류 공유 불가·충전 건별 환불 부분 성공·지정 거절·원장 실패 롤백·DB 제약·MIXED 회귀), `ChargeUsageHistoryTest` +3, `BillingSchemaTest`(FK 19, 지정 충전 건 ID 참조), `BillingEnumStorageTest`(hold_type, 구성 CHECK 제외).
- **2차 검토 (2026-10-15)**: 충전 건 지정 확정 차감·반환에서 금액을 생략(null)하면 그 배분의 남은 보류 전부를 처리하던 계약을 금액 필수로 좁혔다(PG가 20,000 중 10,000만 취소했는데 20,000을 차감하는 실수 방지). 금액 관계 확인: 상세 충전 금액 = 지정 배분의 소진(확정 차감)·반환 증가분, 확정 차감은 `point_charge.remaining_principal_hp`를 바꾸지 않음(보류 때 이미 예약), 반환만 지정 충전 건 원금을 복원, 원장은 확정 차감 = 보류 감소 1줄(REFUND)·반환 = 보류→가용 2줄. 추가 테스트 3개(`HoldCaptureIntegrationTest` 21): PG 부분 취소 금액만 차감·금액 없는 지정 거절, FIFO 환불 부분 성공·실패·불명확 혼합의 충전 건별 금액, 서로 다른 충전 건 확정 차감·반환 + 같은 요청 재전송 동시 실행(3회 반복, 교착 없음).
- **남은 일(후속 환불·환전 Issue)**: 환불 확정 차감 후 `point_charge` 상태(PARTIALLY_REFUNDED·REFUNDED)·`point_refund` 기록, 환전 `exchange`·`exchange_payout` 연결은 각 Issue에서. 확정 차감은 HP 이동만 한다. → 환불은 #260(3.17)에서 했다.

### 3.17 TossPayments FIFO 부분 환불과 PG 취소 결과 복구 (#260, 2026-10-15, 기준 `origin/dev` 4eb55ea, `feature/tkt-260`)

> 결정: BD-87~BD-93 (7장) — [decisions.md](../../docs/billing/decisions.md)

규칙 전체는 billing AGENTS.md 10.2. 여기에는 결정 배경과 검증 기록만 둔다.

- **기존 상태**: `point_refund_request`·`point_refund` 테이블과 엔티티는 있었지만 동작이 없었고, 상태 값(PENDING·UNKNOWN 등)이 이번 정책과 달랐다. 충전 건에는 환불액을 기록할 곳이 없어 PARTIALLY_REFUNDED·REFUNDED를 판단할 수 없었다.
- **Flyway `V202610152100__alter_billing_refund_for_toss_cancel.sql` (새 파일)**: 두 테이블의 상태 CHECK 교체(기존 값은 UPDATE로 옮긴다. 기능이 없어 실제 행은 없다), 요청 `failure_reason`·`canceled_at`·`idx_point_refund_request_3 (status, updated_at)`, 개별 환불 `failure_reason`·`attempt_count`(CHECK ≥ 0)·`last_attempted_at`·`uk_point_refund_refund_request_id_charge_id`, 충전 건 `refunded_amount_hp`(기본 0) + `ck_point_charge_4`. **ERD에 없는 컬럼 6개를 더했다**(팀 공유 필요).
- **설계 선택**
    - 접수와 처리를 나누되 API는 접수 직후 같은 요청에서 처리까지 한다(결과를 바로 돌려주기 위해). 그래서 회원 취소는 사실상 "접수 후 처리 전에 멈춘 요청"에만 쓰인다. 회원이 취소할 수 있도록 일부러 처리를 미루는 정책은 없어서 두지 않았다.
    - 우리 취소를 PG 이력에서 찾는 방법: TossPayments 취소 이력(`cancels[]`)에는 우리 ID를 넣을 필드가 없다. 그래서 `cancelReason`에 멱등키를 대괄호로 감싸 넣고(`HP 환불 [hapbang-refund-cancel-{UUID}]`), 대괄호까지 포함해 비교한다(부분 일치 방지). 처음에는 `{요청ID}-{충전ID}`였으나 실제 PG 테스트 전 점검에서 DB 초기화·환경 간 ID 중복으로 같은 상점에 키가 재사용될 위험을 찾아 UUID로 바꿨다(3.17 4차).
    - 결과 확인에서만 같은 멱등키로 재요청한다. 조회에서 우리 취소가 안 보이면 재요청하고, 첫 요청이 PG에서 처리됐다면 그 응답이, 처리 중이면 409가 온다. 같은 실행에서 결과 확인은 1회만 한다(테스트에서 기준 시각을 미래로 주면 PG를 연달아 부르던 문제를 발견해 고쳤다).
    - 취소 금액이 요청과 다르면 차감하지 않고 운영자 확인. 돈은 이미 나갔을 수 있지만 얼마를 차감할지 자동으로 정하지 않는다.
    - `PointCharge.restorePrincipal`의 복원 한도를 `충전액 − 가용 원금 − 환불 누계`로 좁혔다(환불한 원금이 되돌아오지 않게).
- **TossPayments 확인 내용(문서, 2026-10-15)**: 취소 `POST /v1/payments/{paymentKey}/cancel` — `cancelReason`(필수, 200자), `cancelAmount`(없으면 전액), Idempotency-Key 지원. 응답은 Payment(`balanceAmount`, `status` PARTIAL_CANCELED·CANCELED, `cancels[]`: transactionKey·cancelAmount·cancelReason·canceledAt·cancelStatus DONE). 오류: 400 ALREADY_CANCELED_PAYMENT·PROVIDER_ERROR(일시)·INVALID_REQUEST 등, 403 NOT_CANCELABLE_AMOUNT·NOT_CANCELABLE_PAYMENT·FORBIDDEN_CONSECUTIVE_REQUEST(일시)·EXCEED_MAX_REFUND_DUE 등, 404 NOT_FOUND_PAYMENT, 5xx는 재시도 가능.
- **알려진 위험**: 실제 TossPayments가 5xx·시간 초과 응답을 멱등키 응답으로 기억하는지 확인하지 못했다. 기억한다면 같은 키 재요청도 같은 오류라 그 건은 UNKNOWN·보류로 계속 남는다(이중 환불은 없음, 테스트 `refund_unknownCachedErrorKeepsHold`). 몇 번 뒤 운영자 확인으로 넘길지는 정책 값이라 정하지 않았다. 운영자용 처리 API(MANUAL_REVIEW 해소)도 없다.
- **테스트**: `RefundIntegrationTest` 33, `ApiV1WalletRefundControllerTest` 6, `ApiV1WalletChargeUnavailableTest` +1, `TossPaymentsClientTest` +2, `BillingConstraintsTest` +4. `FakeTossPaymentsServer`에 취소·결제키 조회·취소 이력과 장애(CANCEL_REJECTED·PROVIDER_ERROR·ALREADY_CANCELED·CANCEL_DIFFERENT_AMOUNT·CANCEL_WITHOUT_MARKER·HIDE_CANCELS, 기존 5xx·시간 초과·응답 유실·연결 끊김·409·429)를 더했다. 모든 시나리오 끝에 위 불변식을 확인한다(`assertConsistent`). 동시성: 회원 취소 vs 처리 6회, API 처리 1 + 결과 확인 2 동시, 같은 충전 HP로 3건 동시 요청.
- **2차 검토 (2026-10-15)**: 결함 4건을 찾아 최소 수정했다(BillingPort·다른 모듈·기존 Flyway 불변, 새 Flyway 없음).
    1. 같은 표식의 PG 취소가 여러 건이어도 첫 건만 보고 차감 → 중복 취소(PG에서 돈이 두 번 나감)를 숨김. 여러 건이면 차감하지 않고 운영자 확인(`DUPLICATE_CANCEL:n`). transactionKey 없는 취소도 운영자 확인.
    2. 결과 확인의 같은 키 재요청이 4xx로 거절되면 취소 이력을 다시 보지 않고 반환 → 앞선 요청이 늦게 PG에 반영된 경우 HP 반환 + 현금 환불(이중). 재요청 거절은 조회 후 판단.
    3. "그 밖의 4xx = FAILED(반환)"가 취소 기간 경과·부분 취소 불가 등 PG 환급만 불가능한 경우도 환불 청구를 닫음 → 요청 자체 오류(INVALID_REQUEST·FORBIDDEN_REQUEST·INCORRECT_BASIC_AUTH_FORMAT)만 FAILED, 나머지(문서에 없는 코드 포함)는 보류 유지 + 운영자 확인. NOT_AVAILABLE_BANK는 일시 오류로 분류.
    4. 처리 중 예외(기록 불일치 등)로 상태를 못 바꾼 요청은 `updated_at`이 그대로라 배치 목록 앞에 계속 남음(100건 넘으면 기아) → 예외 시 확인 시각 갱신.
    - 각 수정의 보호 로직을 되돌리면 새 테스트가 실패함을 확인했다(재요청 거절·중복 취소). 추가 테스트: `RefundIntegrationTest` 33 → 42.
    - TossPayments 문서(인증 가이드 멱등키 절): 모든 POST API에 Idempotency-Key 사용 가능(취소 포함), 최대 300자, 15일 유효, 같은 키는 첫 응답과 같은 응답, 처리 중이면 409 IDEMPOTENT_REQUEST_PROCESSING. **오류 응답도 같은 응답으로 돌려주는지는 문서에 명시가 없다**(미검증).
- **확정 정책 반영 (2026-10-15, 3차)**: ① PG 환급 불가 = 개별 건 MANUAL_REVIEW_REQUIRED + 보류 유지 + 사유 보존 + 뒤 건 PG 요청 안 함(요청하지 않은 뒤 건은 개별 CANCELED로 반환), 요청 자체 오류만 FAILED. ② UNKNOWN 자동 복구 한도 24시간·재확인 12회(먼저 도달), 조회 실패 포함, 건별, DB 저장, 성공 확인 시 횟수 무관 확정 차감, 한도 도달 시 보류 유지·자동 반환 없음·배치 제외, 원인 구분(`UNKNOWN_TIME_LIMIT`·`UNKNOWN_CHECK_LIMIT`). ③ CANCELED 공식화(요청 = PG 요청 전 회원 취소, 개별 = PG 요청을 보내지 않고 제외).
    - **스키마(이번 Issue의 미커밋 Flyway `V202610152100` 수정)**: `attempt_count`·`last_attempted_at`을 `cancel_request_count`·`first_cancel_requested_at`·`last_cancel_requested_at`(POST)과 `result_check_count`·`last_result_checked_at`(GET)로 나누고 `unknown_since`, `manual_review_reason`(CHECK `ck_point_refund_manual_review_reason`)·`manual_review_at`, `ck_point_refund_4`(요청했으면 최초 요청 시각 있음), `ck_point_refund_5`(운영자 확인이면 원인·시각 있음), `idx_point_refund_4 (status, updated_at)`를 더했다. **로컬 DB에 이 마이그레이션의 이전 버전을 이미 적용했다면 Flyway 체크섬이 맞지 않는다**(테스트 컨테이너는 새로 만든다).
    - **멱등키 15일 (검토 4)**: 최초 PG 요청 시각을 따로 저장하고, 결과 확인 재요청 전에 `reserveRetry`가 요청 락 아래에서 최초 요청 + 14일(경계 포함) 지남을 확인하면 POST하지 않는다. 그 뒤 조회로 성공이 보이면 확정 차감, 아니면 보류 유지 + `IDEMPOTENCY_KEY_EXPIRED`. UNKNOWN 건은 24시간 한도가 먼저 오므로 이 기간은 주로 PROCESSING(PG 요청 후 서버 중단 등) 건에 걸린다.
    - **시간 판단**: `RefundClock`(빈)으로 판단해 테스트가 실제로 기다리지 않고 경계(23:59:59 허용·24:00 차단, 14일 - 1초 재요청·14일 차단)를 검증한다. `updated_at`(배치 대상 선정)은 기존대로 실제 시각.
    - **테스트**: `RefundIntegrationTest` 42 → 50, `PointRefundTest` 5(새), `BillingConstraintsTest` +3, `BillingEnumStorageTest`(운영자 확인 원인 CHECK). 한도·기간 판단을 끄면 해당 테스트 4개가 실패함을 확인했다.
- **PG 취소 멱등키 UUID화 (2026-10-16, 4차, 실제 PG 테스트 전 점검)**: `hapbang-refund-cancel-{요청ID}-{충전ID}`는 DB 초기화·같은 TossPayments 상점을 쓰는 다른 환경(dev 등)에서 같은 ID가 다시 나오면 키가 재사용된다. TossPayments 멱등키는 상점 단위·15일이라 다른 결제의 이전 응답이 돌아오고, 표식도 일치해 금액까지 같으면 **취소되지 않은 우리 결제를 SUCCEEDED로 차감**할 수 있다(금액이 다르면 운영자 확인에 멈춤 — 수정 전 코드로 테스트 재현).
    - 변경: 개별 환불 생성 시 `hapbang-refund-cancel-{UUID}`를 한 번 만들어 기존 `point_refund.idempotency_key`(초기 마이그레이션의 `uk_point_refund_idempotency_key` UNIQUE)에 저장. 최초 요청·조회 복구·같은 키 재요청·표식은 원래부터 저장값(`CancelTarget.idempotencyKey`)을 써서 바뀌지 않았다. 14일 안전 기간·24시간/12회 한도 그대로.
    - Flyway 변경 없음(컬럼·UNIQUE가 이미 있다). 기존 행의 ID 기반 키는 그대로 두고 계속 그 값으로 처리한다(로컬·dev에는 환불 행이 없다).
    - 테스트: `BillingKeysTest` +1(형식·1만 건 고유·길이), `RefundIntegrationTest` +2(저장값 = 헤더 = 표식, ID 기반 키가 상점에 이미 있어도 섞이지 않음), `BillingConstraintsTest` +1(키 UNIQUE). ID 기반으로 되돌리면 새 통합 테스트 2개가 실패함을 확인.
- **실제 TossPayments 테스트 결제 E2E (2026-10-16, 로컬, 사용자 승인 후 실행)**: `local` 프로필 + 테스트 키(`test_sk_`·`test_ck_`) + `HAPBANG_SCHEDULING_ENABLED=false`(배치가 PG를 부르지 않게), 깨끗한 계정 user 2(wallet 3). 기존 user 1 충전 #1(#224 테스트)은 FIFO에 걸리지 않게 쓰지 않았다. 앱 시작 시 로컬 DB에 `V202610152000`·`V202610152100` 적용.
    - A 충전 10,000(충전 #3) → 3,000 환불(`e2e260-a`): 200 COMPLETED, PG `PARTIAL_CANCELED`·balance 7,000·cancels 1건(DONE·transactionKey·3,000·`HP 환불 [hapbang-refund-cancel-{UUID}]`). #3 PARTIALLY_REFUNDED·누계 3,000·원금 7,000, CAPTURE `target_charge_id` 3, REFUND −3,000.
    - B 충전 10,000(충전 #4) → 12,000 환불(`e2e260-b`): A 7,000 → B 5,000 순서, 둘 다 SUCCEEDED. #3 REFUNDED·누계 10,000, #4 PARTIALLY_REFUNDED·누계 5,000, 지갑 충전 HP 5,000. PG 취소 합(15,000) = 충전 건 누계 합 = REFUND 원장 합, transactionKey는 PG와 DB `pg_refund_key`가 해시로 일치. 개별 환불마다 `cancel_request_count` 1·`result_check_count` 0.
    - 멱등성: 같은 키·같은 금액 재전송은 같은 요청 ID(1·2), PG 취소 이력 그대로(추가 취소 없음). 같은 키·다른 금액은 409 `IDEMPOTENCY_KEY_CONFLICT`.
    - 다른 지갑·충전 #1·#2·지급·환전·다른 보류 변화 없음. 지갑 = 원장 합.
    - **실제와 가짜 서버의 차이**: 부분 취소를 여러 번 해서 남은 금액이 0이 되어도 실제 TossPayments status는 `PARTIAL_CANCELED`다(가짜 서버는 `CANCELED`). 환불 판정은 상태 값이 아니라 우리 표식 취소 건(DONE·거래 키·금액)으로 하므로 영향 없음. 상태 값은 운영자 확인 사유 문자열에만 쓴다.
- **실제 PG 수동 테스트 절차 (위 E2E에서 user 2로 실행했다)**
    1. 준비: 루트 `.env`의 테스트 키(`test_sk_`)만 쓴다(라이브 키 금지). `local` 프로필로 앱 실행, 로그인한 USER 세션.
    2. 충전 2건: 3.15 절차로 10,000 HP를 두 번 충전한다(결제 A, B). `GET /api/v1/wallets/me`로 충전 HP 20,000 확인.
    3. 부분 환불: `POST /api/v1/wallets/refunds {"amount":3000}`, `Idempotency-Key: manual-1` → 200 COMPLETED, 항목 1건(결제 A, 3,000). 토스 개발자센터 테스트 결제 내역에서 A가 부분 취소 3,000, 취소 사유 `HP 환불 [hapbang-refund-cancel-…]`인지 확인. DB: `point_charge`(A) `refunded_amount_hp` 3000·PARTIALLY_REFUNDED, 지갑 충전 HP 17,000, `point_ledger` REFUND −3,000.
    4. 재요청: 같은 헤더·금액으로 다시 보내 같은 `refundRequestId`·PG 취소 추가 없음 확인. 금액을 바꾸면 409.
    5. 여러 결제 건: `{"amount":12000}`, `Idempotency-Key: manual-2` → A 7,000 + B 5,000 순서로 취소, A는 REFUNDED(PG는 balanceAmount 0이지만 status `PARTIAL_CANCELED` — 아래 실제 결과 참고), B는 PARTIAL_CANCELED.
    6. 정합성: 지갑 충전 HP 5,000, 원장 합 = 지갑, 각 결제의 PG 취소 합 = `refunded_amount_hp`.
    7. 실행하지 않는 것: 실제 PG에서 5xx·시간 초과·이미 취소됨을 일부러 만드는 시험(가짜 서버로만 검증). 5xx 멱등 응답 기억 여부는 TossPayments에 문의하거나 문서로 확인.

### 3.18 환전 신청·테스트 계좌 스냅샷·수익 HP 보류 (#285, 2026-10-16, 기준 `origin/dev` ef0d200, `feature/tkt-285`)

> 결정: BD-94~BD-98 (8장) — [decisions.md](../../docs/billing/decisions.md)

규칙 전체는 billing AGENTS.md 11장. 여기에는 결정 배경만 둔다.

- **기존 상태**: `exchange`·`exchange_account`·`exchange_payout` 테이블·엔티티만 있고 동작이 없었다(행 없음). 상태 CHECK에 취소가 없고, 계좌는 "구조만 준비"였다.
- **사용자 결정 (2026-10-16)**: ① 계좌 A안(신청마다 테스트 계좌 입력, 사전 등록 테이블 없음, 실제 확인 없음) ② 정산 회차 = 신청일 이후 첫 10일, **공휴일 미반영 날짜를 확정 정산일로 취급하지 않는다**(주말 보정도 하지 않고 잠정 회차값으로만 저장·응답, 실제 송금일은 Scheduler Issue) ③ 이번에는 CANCELED만 추가, APPROVED는 승인 Issue에서 ④ 계좌번호 암호화는 결제키 키 재사용(환경 변수·compose 변경 없음) ⑤ 형식: 은행 숫자 3자리·계좌 숫자 8~20자리·예금주 1~50자.
- **Flyway `V202610161300` (새 파일)**: `ck_exchange_status`에 CANCELED, `canceled_at` + `ck_exchange_5`(CANCELED ⇔ 취소 시각). ERD에 없는 컬럼 1개(`canceled_at`) — 팀 공유 필요. 기존 컬럼(수수료율·수수료·예상 수령액·`point_hold_id`·`exchange_account` 전 컬럼)은 그대로 재사용.
- **설계 선택**
    - 신청은 환불(#260)과 같은 모양: 지갑 락 → 멱등키 조회 → 신청 저장 → `InternalHoldFacade.hold(EXCHANGE)`(REVENUE_ONLY) → 보류 연결 → 계좌 저장. 보류 실패 시 전체 롤백.
    - 같은 키 재요청 비교: 금액·은행·예금주·계좌번호(암호문은 무작위 IV라 복호화해 비교).
    - 취소는 이미 CANCELED면 반환 없이 결과만(반환 요청 ID도 고정이라 중복 반환 없음).
    - 응답에 실제 송금일 필드를 두지 않는다(`settlementRoundDate`만). 계좌번호 복호화 실패 시 조회는 막지 않고 전부 가린다.
    - 경로 `/api/v1/settlements/exchanges`(정산 영역), 컨트롤러 `ApiV1SettlementExchangeController`.
- **테스트**: `ExchangeIntegrationTest` 24(실제 강의 결제 보류 → 지급으로 수익 HP 생성, 동시 신청 6건·같은 키 5건·동시 취소 4건, 강의 보류 회귀), `ApiV1SettlementExchangeControllerTest` 14(역할·검증·마스킹), `ExchangeTest` 17, `ExchangeSettlementRoundTest` 10, `BillingConstraintsTest` +3, `ApiV1WalletChargeUnavailableTest` +1.
- **2차 검토 (2026-10-16)**
    - 정책 변경: 잠정 정산 예정일에 주말 보정 추가(토·일 → 다음 월요일, 공휴일 미반영). `ExchangeSettlementRound`를 기준일 선택(`baseDate`)·주말 보정(`adjustForWeekend`)으로 나눠 따로 테스트. 새 Flyway `V202610161300`은 주석만 고쳤다(dev·로컬 DB 미적용).
    - 결함(개선) 1건: 같은 키 재요청에서 저장된 계좌번호를 복호화하지 못하면 `IllegalStateException`이 그대로 500 `INTERNAL_ERROR`가 됐다(새 신청·이중 보류는 없었음). 원인을 구분할 수 있도록 `EXCHANGE_ACCOUNT_UNREADABLE`(500)로 명시적으로 거절하고, 원문 비교를 `MessageDigest.isEqual`로 바꿨다.
    - 검증 추가: 같은 계좌 20회 암호화(암호문 모두 다름)·원문 비교, 재시작(같은 키 새 인스턴스) 복호화, 다른 키·손상·키 없음, 계좌 저장 실패 시 신청·보류·원장 전부 롤백, 재전송 시 보류·원장 1건 유지, 응답·로그에 계좌번호 없음(OutputCapture), 지갑 → 환전 순서로 잠근 다른 트랜잭션과 취소의 직렬화(최신 상태 재확인·반환 없음).
    - 후속 관리자 승인 잠금·상태 계약은 AGENTS.md 11장에 적었다.
- **후속 연동 지점**: 관리자 승인(REQUESTED → APPROVED, Flyway로 상태 추가, 승인 후 취소 차단은 이미 됨) / 정산 Scheduler(영업일·공휴일로 실제 송금일 계산, `payout_scheduled_at`은 회차 선택용) / MockPayout(`exchange_payout`, 성공 → `InternalHoldFacade.capture(EXCHANGE, full)`, 최종 실패 → release, 불명확 → 보류 유지). 잠금 순서 지갑 → 환전 → 보류를 지킨다.

### 3.19 가상 송금 Mock 은행 API·HTTP Client (#288, 2026-10-16, 기준 `origin/dev` 04e9c23, `feature/tkt-288`)

> 결정: BD-99~BD-101 (8장) — [decisions.md](../../docs/billing/decisions.md)

규칙은 billing AGENTS.md 11장 "가상 송금". 결정 배경만 둔다.

- **기존 상태**: `exchange_payout`(환전 1건당 1행, `exchange_id` UNIQUE·FK, `payout_request_id` UNIQUE, 공급자 MOCK)은 우리 쪽 송금 이력용 구조이고 코드가 없었다. HTTP 테스트 도구는 WireMock 없이 JDK `HttpServer` 가짜 서버(Toss)와 채팅 RANDOM_PORT E2E만 있었다.
- **왜 `exchange_payout`을 Mock 저장소로 쓰지 않았나**: 그 표는 "우리가 보낸 송금"이고 후속 송금 UseCase가 쓴다. Mock 은행이 같은 표를 쓰면 은행 쪽 기록과 우리 쪽 이력이 섞인다. 은행 쪽 멱등 기록은 FK 없는 `mock_payout_transfer`로 분리했고, 이번 Issue에서 `exchange_payout`은 쓰지 않는다. 운영 DB에도 표는 생기지만(사용자 결정 유지) Mock이 꺼져 행은 생기지 않는다.
- **같은 애플리케이션 HTTP 호출 위험과 대응**: ① 트랜잭션 안 호출 금지(Client가 HTTP·소켓 연결 전에 거절) ② Mock 쪽은 접수·결과 확정을 각각 짧은 트랜잭션으로 커밋, 지연은 트랜잭션 밖 ③ 응답 시간 제한(기본 5초)과 TIMEOUT 지연(기본 8초) 분리 ④ 포트는 `local.server.port`를 호출 때 읽는다 ⑤ 보안은 Mock 경로 전용 체인 + 기동 시 무작위 접근 키(global SecurityConfig 변경 없음, billing에 `spring-security-config`·`-web` 의존 추가).
- **2차 검토 결함 1건**: 활성 조건이 설정(`enabled`)뿐이라 운영·dev에 `HAPBANG_BILLING_MOCK_PAYOUT_ENABLED=true`가 잘못 들어가면 Mock API·시나리오 제어·세션 없는 보안 체인이 켜질 수 있었다 → 설정 + 프로필 허용 목록(local·test·default) + dev·prod 차단(`MockPayoutActivation`·`@ConditionalOnMockPayout`), Client도 같은 판단. 새 Flyway 없음.
- **3차 확정 (2026-10-16)**: 활성 조건에서 "프로필 없음(default)"을 뺐다 → `enabled=true AND 활성 프로필 (local OR test) AND NOT (dev OR prod)`. 실제 활성 프로필만 보므로 `spring.profiles.default=local`로 뜬 로컬 앱(bootRun·IntelliJ 기본 실행)은 Mock이 꺼진다 — 로컬에서 쓰려면 `SPRING_PROFILES_ACTIVE=local`. 실제 서버 HTTP 테스트는 `@ActiveProfiles("test")`. 테스트: 활성 조건 16(요청한 12 조합 + 기본 프로필 local·test, 설정 없음), 프로필 통합 8(prod·dev·프로필 없음·기본 프로필 local, 각 2).
- **4차 확정 (2026-10-16)**: 활성 프로필이 전부 local·test여야 한다(허용 목록 밖 프로필이 섞이면 꺼짐, local,staging 차단). 로컬 기본 실행에서 Mock이 꺼지는 것은 의도한 동작(필요하면 `SPRING_PROFILES_ACTIVE=local`). 테스트: 활성 조건 20, 프로필 통합 10(LocalWithStaging 추가).
- **테스트 격리**: 전체 시나리오를 지우던 `@BeforeEach clear()`를 없앴다(병렬 실행 시 다른 테스트 시나리오를 지울 수 있음). 지급 요청 ID가 테스트마다 새 UUID라 섞이지 않고, 실제 서버 컨텍스트는 클래스 끝에 닫는다(`@DirtiesContext`, 풀 5 — 전체 실행에서 DB 최대 연결 수 초과를 겪고 넣었다).
- **테스트**: `MockPayoutHttpIntegrationTest` 19(RANDOM_PORT 실제 HTTP: 시나리오 5종, 반복·충돌 5종·동시 8건, ID별 시나리오 동시 격리, 확정 뒤 시나리오 재지정 무효, 트랜잭션 안 소켓 연결 0·호출 1번 = 연결 1번, 대기 중 열린 트랜잭션 0·접수/결과 선커밋, 접근 키 단일 빈·403·응답/로그 비노출·다른 API 401, 로그 계좌 비노출, DB 기록만으로 재시작 복구, 환전 예상 수령액 18,000 송금 후 환전·지갑·보류·원장·exchange_payout 불변), `MockPayoutDisabledTest` 3, `MockPayoutDeployedProfileTest` 4(prod·dev에서 enabled=true여도 꺼짐), `MockPayoutActivationTest` 10, `MockPayoutTransferTest` 2, `BillingEnumStorageTest`·`BillingSchemaTest`(13개 표) 갱신.
- **계약(AGENTS.md 11장)**: Failed는 Release하지 않는다(재처리 가능하게 보류 유지), 확정 실패 뒤 재송금은 새 지급 시도 ID, 결과 불명확·처리 중은 같은 ID로 조회.


### 3.20 관리자 환전 승인·거절과 Billing 회원 복제본 (#294, 2026-10-16, 기준 `origin/dev` 586ec8a, `feature/tkt-294`)

> 결정: BD-102~BD-104 (8장). BD-104는 확인 필요 — [decisions.md](../../docs/billing/decisions.md)

규칙은 billing AGENTS.md 11장. 결정 배경만 둔다.

- **사전 분석**: #292가 `UserDto.version`(회원 @Version, 가입 0, 수정마다 +1)과 `ReplicaUser.memberVersion`·`syncFrom`(이하 버전 무시)을 넣었다. 다른 모듈(강의·출연·채팅)은 가입 이벤트로만 만들고 변경 이벤트는 기존 복제본만 고친다(없으면 실패 후 재시작 대기). 회원 복제본을 Backfill한 모듈은 없다(10-12 이벤트 도입 직후 리스너를 붙여 필요가 없었다).
- **사용자 결정**: Backfill 제외(후속), Member·Shared 변경 없음, 가입·변경 모두 Upsert, 복제본 없는 신청자는 승인 409, 거절은 신청자 상태 무관, 같은 관리자(거절은 같은 사유) 재요청만 멱등.
- **설계 선택**: 복제본 갱신은 행을 비관적 락으로 잡고 버전 비교(처음엔 낙관적 락 + 재시도 3회였으나 같은 회원 10건 동시 반영 테스트에서 재시도가 모자라 실패 → 락으로 직렬화, PK 충돌만 재시도). 버전 없는 정보는 반영하지 않는다(회원 모듈은 항상 버전을 담으므로 테스트용 정보만 해당). 관리자 처리 전 지갑 ID는 엔티티를 올리지 않는 쿼리로 읽는다(영속성 컨텍스트에 잠금 전 상태가 남지 않게).
- **구현 중 발견한 결함 1건(수정)**: 보류 정합성 검사에서 `Long` 두 값을 `==`로 비교해 128 이상 금액에서 항상 불일치 → `longValue()` 비교. 테스트로 발견.
- **ERD에 없는 것(팀 공유)**: 테이블 `billing_user`, `exchange`의 `approved_by`·`approved_at`·`rejected_by`·`rejected_at`·`rejection_reason`, 상태 값 APPROVED·REJECTED.
- **테스트**: `BillingUserSyncIntegrationTest` 8, `BillingMemberEventIsolationTest` 1, `ExchangeReviewIntegrationTest` 22, `ApiV1SettlementExchangeAdminControllerTest` 5, `ExchangeTest` +6, `BillingConstraintsTest` +8, `BillingEnumStorageTest`·`BillingSchemaTest`(14개 표) 갱신.
- **후속**: 기존 회원 Backfill(Member 조회 계약 협의), 송금 UseCase(승인 건 → exchange_payout → Mock 송금 → Capture), 정산 Scheduler(공휴일·회차), 복제본 지연(이벤트 실패는 재시작 때만 재처리).


### 3.21 HP 내부 HTTP API (#273, 2026-10-29, 기준 `origin/dev` 29d1ea5, `feature/tkt-273`)

> 결정: BD-108, BD-111~BD-113 (9장) — [decisions.md](../../docs/billing/decisions.md)

정책 원문은 `docs/billing/CONTEXT.md` 1장(2026-10-29 `/grill-with-docs`). 결정 배경만 둔다.

- **경로**: `ApiV1BillingInternalController`(Swagger `ApiV1BillingInternalSpec`)가 네 경로를 받고 `HoldFacade`를 부른다. 요청 하나 = Billing 로컬 트랜잭션 하나. 결과 변환은 `BillingResultDtos`로 옮겨 `BillingPortAdapter`와 같이 쓴다. `BillingPort`는 #280까지 유지한다.
- **요청 본문 = shared Command**: BD-54("shared DTO를 Controller 요청 DTO로 쓰지 않는다")는 같은 JVM 전제였다. HTTP에서는 shared Command가 계약 자체이고, 구조 오류는 Jackson을 거쳐 400 `INVALID_REQUEST`다. 이슈의 "billing 내부 Command 변환"은 `HoldFacade` 시그니처·Hold 통합 테스트가 함께 바뀌므로 #280에서 한다.
- **인가**: `HoldFacade`에 `@PreAuthorize("hasRole('INTERNAL')")`(D63). #280 뒤 최종 위치를 지금 정했다. `HoldFacade`·`BillingPort`를 직접 부르는 테스트 10개 클래스는 `@WithMockUser(roles = "INTERNAL")`, 다른 스레드에서 부르는 동시성 테스트는 `DelegatingSecurityContextExecutorService`로 인증을 넘긴다(검증 조건 불변).
- **동시 재요청**: 같은 회원·같은 키 동시 요청은 지갑 락으로 직렬화되어 replay된다(코드 변경 없음, HTTP 테스트로 고정). 409는 다른 회원이 같은 참조 대상을 보류할 때만이다. 처음 분석에서 "내용이 같아도 409"라고 잘못 판단했다가 `HoldCreateUseCase`의 지갑 락 위치를 보고 정정했다.
- **락 대기 상한(BD-108 결정, 이슈 범위 확장)**: Client 응답 timeout 5초와 무제한 락 대기가 맞지 않아 Billing 전체에 `lock_timeout = 3s`(Hikari `connection-init-sql`, `billing.yml`을 운영 설정·테스트 설정이 함께 import)를 둔다. 55P03은 Facade 진입점에서 `LockTimeouts`가 `HP_LOCK_TIMEOUT`(503)으로 바꾼다(보류·반환·지급·위약금, 충전, 환불, 환전, 관리자 환전 처리, 내 지갑 조회). 공통 `GlobalExceptionHandler`는 6개 앱이 같은 사본이라 바꾸지 않았다. shared `BillingFailureCode`에도 추가.
- **오류 코드 대조**: `BillingFailureCodeContractTest`가 `BillingErrorCode` 전체를 `default` 없는 `switch`로 `BillingFailureCode` 또는 `null`(내부 API에서 나오지 않음)로 분류한다. Billing 코드가 늘면 컴파일되지 않는다.
- **로그**: 요청마다 `operation`·회원·참조·요청 ID·금액과 `result=OK replayed=…`/`FAILED code=…`/`ERROR exception=…` 한 줄. correlation ID는 계약(`internal-billing.md` 3장)대로 tracing 도입 때 정한다.
- **테스트**: `ApiV1BillingInternalControllerTest` 13(성공·replay·409·업무 오류 detail·400 구조 오류·401 키 없음/틀림/세션만·기술 실패 500 + 전체 롤백·Facade 인가·요청 로그·동시 같은 요청·락 대기 초과 503), `BillingFailureCodeContractTest` 68, `LockTimeoutsTest` 3.
- **후속**: `docs/api/internal-billing.md` 2장 표에 `HP_LOCK_TIMEOUT` 반영, GLOSSARY("위약금 정산") 반영, #280(내부 Command 변환·`BillingPort` 삭제). `decisions.md` 반영(BD-28·BD-54 일부 대체, BD-108 결정, BD-111~BD-113)은 2026-10-30에 했다.

### 3.22 환불·환전 1원 단위 (#453, 2026-10-30, 기준 `origin/dev` 3d6b61c, `feature/tkt-453`)

정책 원문은 `docs/billing/CONTEXT.md` 4장(2026-10-30 `/grill-with-docs`)과 `docs/policy.md` 2·3장. 결정 배경만 둔다.

- **배경**: 충전은 1,000 단위지만 사용 금액은 1,000 단위가 아니라, 1,000 미만 끝자리 충전 HP·수익 HP를 현금화할 수 없었다. `policy.md` 2장 환불 규칙은 구현 전에 먼저 "0원 초과·잔액만큼 부분 환불"로 바뀌어 있었다.
- **금액**: 환불·환전 모두 1 HP 이상·1 HP 단위. 충전은 그대로 1,000 단위. 환전 최소 1,000 유지(단위만 1원)·최소 2원 안도 있었지만 잔액 전부를 현금화하도록 1 HP로 정했다.
- **계산**: 송금액을 버린다(송금 = floor(신청 × 90%), 수수료 = 나머지). 이전에는 수수료를 버렸다. 수수료가 항상 1 HP 이상이라 #336의 "수수료 0원 원장 없음" 전제가 유지된다.
- **송금액 0원(신청 1 HP)**: 허용. `ExchangePayoutProcessing.start`가 APPROVED 건이 송금액 0이면 은행을 부르지 않고 같은 트랜잭션에서 확정 차감 + 수수료 수입 + COMPLETED. 송금 시도 기록 없음. dev·prod(Mock 꺼짐)는 다른 환전처럼 건너뛴다. 그래서 `start`도 회원·플랫폼 지갑을 함께 잠근다(`lockForPayout`).
- **오류 코드**: `REFUND_AMOUNT_INVALID` → `REFUND_AMOUNT_TOO_SMALL`, `EXCHANGE_AMOUNT_INVALID` → `EXCHANGE_AMOUNT_TOO_SMALL`. HTTP에서는 0 이하를 `@Positive`가 먼저 400 `INVALID_REQUEST`로 막으므로 새 코드는 내부 호출 보호용이다. 프론트와 맞춘다.
- **DB**: Flyway `V202610301200`이 `ck_exchange_1`을 `requested_amount_hp >= 1`로 바꾼다. 기존 환전의 수수료·송금액은 바뀌지 않는다.
- **PG**: TossPayments 문서에 부분 취소 최소 금액 제한이 없다. 부분 취소가 안 되는 결제는 기존대로 거절 → 보류 유지 → 운영자 확인.
- **테스트**: `ExchangeTest`(송금액·수수료 계산표, 1 HP 단위 신청, 0원 완료 조건), `ExchangeIntegrationTest`·`RefundIntegrationTest`(1 HP 미만 거절, 1·999·1,234 환불), `ExchangePayoutIntegrationTest`(0원 환전: NOT_DUE, 은행 호출·시도 없음, 원장 4줄, 플랫폼 대사), 컨트롤러·오류 코드 대조. billing-app 1,141개 통과.

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
| `INVALID_POINT_AMOUNT` | 400 | 0 HP 줄, 0 이하 이동 금액, 0 이하 보류·부분 반환·부분 지급 금액, 음수 위약금 반환액·지급액, 반환액 + 지급액 long 초과 |
| `INSUFFICIENT_BALANCE` | 409 | 잔액이 음수가 되는 감소 |
| `BALANCE_OVERFLOW` | 409 | Long 범위 초과 |
| `IDEMPOTENCY_KEY_CONFLICT` | 409 | 같은 작업 키에 다른 내용, 같은 참조 대상 보류에 다른 지갑·금액, 같은 보류·같은 반환 요청 ID에 다른 금액, 같은 취소 ID에 다른 반환액·지급액·받는 회원·원천 |
| `HOLD_NOT_FOUND` | 404 | 반환할 참조 대상의 보류 없음 |
| `HOLD_OWNER_MISMATCH` | 409 | 요청 회원의 지갑 보류가 아님 |
| `HOLD_ALREADY_CLOSED` | 409 | 남은 보류가 없는(끝난) 보류에 새 반환 요청 |
| `RELEASE_AMOUNT_EXCEEDS_HELD` | 409 | 남은 보류보다 큰 반환 |
| `PAYOUT_AMOUNT_EXCEEDS_HELD` | 409 | 남은 보류보다 큰 지급 |
| `PENALTY_BASE_EXCEEDS_HELD` | 409 | 위약금 정산 기준액(호스트 반환액 + 게스트 지급액)이 남은 보류보다 큼 |
| `PENALTY_AMOUNT_ZERO` | 400 | 위약금 정산의 게스트 지급액이 0 HP (#145부터 출연이 보낸 지급액 기준) |
| `PAYOUT_TO_PAYER` | 400 | 보류한 회원 자신에게 지급 |
| `PAYOUT_SOURCE_MISMATCH` | 400 | 지급 원천이 보류의 거래 대상과 맞지 않음 |
| `SETTLEMENT_NOT_FOUND` | 404 | 상태를 바꿀 주문 항목의 지급 건 없음 (#142) |
| `SETTLEMENT_AMOUNT_MISMATCH` | 409 | 강의 정산 금액 ≠ 주문 항목 보류의 최초·남은 보류액 (#142) |
| `SETTLEMENT_INVALID_STATE` | 409 | 현재 상태에서 할 수 없는 전이(취소·완료·처리 중·실패 건의 중단·재개·취소), 취소된 주문 항목 재등록 (#142) |
| `PAYOUT_ALREADY_SETTLED` | 409 | 같은 지급 원천(출연 계약·주문 항목)에 어느 보류에서든 지급이 이미 있음(유형 무관), 출연 요청(1:1) 보류에 지급이 이미 있음 |
| `HP_LOCK_TIMEOUT` | 503 | 지갑·보류·충전 건 등의 락을 `lock_timeout`(3초) 안에 얻지 못함(PostgreSQL 55P03). 롤백되어 HP가 움직이지 않았으므로 같은 요청을 다시 보낸다 (#273) |
| `CHARGE_PRINCIPAL_MISMATCH` | 500 | 배분 가능 충전 원금 합계 ≠ 지갑 충전 HP 가용 잔액, 가용 원금을 넘는 배분, 충전 금액을 넘는 복원·가용 원금 상태가 아닌 충전 건에 복원 |
| `CHARGE_UNAVAILABLE` | 503 | TossPayments 시크릿 키·결제키 암호화 키가 설정되지 않음 (#224) |
| `CHARGE_AMOUNT_INVALID` | 400 | 충전 금액이 1,000 미만이거나 1,000 단위가 아님 (#224) |
| `CHARGE_NOT_FOUND` | 404 | 충전 주문이 없거나 다른 회원의 주문 (#224) |
| `CHARGE_AMOUNT_MISMATCH` | 400 | 승인 요청 금액 ≠ 저장한 주문 금액 (PG 호출 전 거절, #224) |
| `CHARGE_PAYMENT_KEY_CONFLICT` | 409 | 다른 주문에 이미 쓴 결제키, 처리 중·끝난 주문에 다른 결제키 (#224) |
| `CHARGE_ALREADY_FAILED` | 409 | 승인 실패로 끝난 주문에 다시 승인 요청 (#224) |
| `CHARGE_PAYMENT_REJECTED` | 400 | PG가 승인하지 않음(4xx·만료·취소). detail = PG 메시지, 속성 `pgCode` (#224) |
| `CHARGE_STATE_INCONSISTENT` | 500 | PG 승인 결과가 주문과 다름(주문·결제키·금액). 충전하지 않고 PROCESSING + 사유 (#224) |
| `CAPTURE_AMOUNT_EXCEEDS_HELD` | 409 | 확정 차감 금액이 남은 보류(충전 건 지정이면 그 배분의 남은 보류)보다 큼 (#241) |
| `HOLD_TYPE_NOT_SUPPORTED` | 400 | MIXED 보류 확정 차감, CHARGE_ONLY가 아닌 보류에 충전 건 지정 (#241) |
| `HOLD_CHARGE_NOT_FOUND` | 400 | 지정한 충전 건이 보류에 배분되지 않음 (#241) |
| `REFUND_UNAVAILABLE` | 503 | TossPayments 시크릿 키·결제키 암호화 키가 없어 환불 접수 불가 (#260) |
| `REFUND_AMOUNT_TOO_SMALL` | 400 | 환불 금액이 1 HP보다 작음 (#453, 이전 `REFUND_AMOUNT_INVALID`: 1,000 미만·1,000 단위 아님, #260) |
| `REFUND_IDEMPOTENCY_KEY_INVALID` | 400 | `Idempotency-Key` 헤더 형식 오류(영문·숫자·-·_ 1~64자) (#260) |
| `REFUND_NOT_FOUND` | 404 | 환불 요청이 없거나 다른 회원의 요청 (#260) |
| `REFUND_NOT_CANCELABLE` | 409 | PG 취소를 시작했거나 끝난 요청을 회원이 취소 (#260) |
| `REFUND_STATE_INCONSISTENT` | 500 | 요청·개별 환불·보류 기록 불일치(완료액 초과, 요청 진행 중인데 앞 건이 끝난 실패 상태, 반환한 건의 PG 성공 등) (#260) |
| `HOLD_STATE_INCONSISTENT` | 500 | 보류(반환 상세) 없이 원장만 있음, 보류와 원장 줄 수·충전 원금 배분·상세 합계 불일치, 상세 재생 결과 ≠ 배분 누적값, 지급 상세 없이 지급 건·원장만 있음, 위약금 기록 일부만 있음 |

역할 검사(`@PreAuthorize`) 실패는 `BillingErrorCode`가 아니라 global 공통 코드로 응답한다. 로그인했으면 403 `FORBIDDEN`, 비로그인이면 401 `UNAUTHORIZED`.

응답 형식: RFC 9457 ProblemDetail + `code` 속성. #71(2026-10-13)부터 모듈 핸들러(`BillingExceptionHandler`)를 없애고 global `GlobalExceptionHandler` 하나가 응답한다. `BillingErrorCode implements BaseErrorCode`, `BillingException extends BusinessException`이며 `code`는 enum 이름 그대로다(루트 AGENTS.md 7장 "오류 응답"). 정상 응답은 #127부터 global `RsData`(`statusCode`·`message`·`data`)로 감싼다. `WalletFacade`는 응답 형식과 무관하게 지갑을 돌려주고 컨트롤러가 `WalletResponse`를 만들어 `RsData.ok`로 감싼다. 지갑을 새로 만든 조회도 조회 API이므로 200이다(#127 이전과 같은 상태 코드).

500 코드(`CHARGE_PRINCIPAL_MISMATCH`, `HOLD_STATE_INCONSISTENT`)는 불변식이 깨진 상황이다. 공통 규칙(500은 내부 메시지를 노출하지 않는다)에 맞춰 응답 `detail`은 내부 상태를 드러내지 않는 일반 문구로 두고, 원인은 `code`와 공통 핸들러의 error 로그(스택 포함)로 구분한다. 일반 예외(500 `INTERNAL_ERROR`)로 바꾸지 않고 billing 코드를 유지한 것은 테스트·로그에서 원인을 코드로 구분하기 위해서다(2026-10-13 결정, a안).

---

## 6. 테스트

- 실행: `./gradlew :billing:test --no-daemon`, 전체 `./gradlew check --no-daemon` (`ModularityTests` 포함).
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
- **Docker가 켜져 있어야 한다.** `test-support`의 `PostgresTestcontainersConfig`가 `postgres:18` 컨테이너를 띄운다. 로컬에 PostgreSQL 설치는 필요 없다.
- `@RepositoryTest`(JPA만, 롤백) / `@IntegrationTest`(전체 컨텍스트, 테스트마다 TRUNCATE) / `TestLogin.as(userId, role)`(MockMvc 로그인).
- `@ApplicationModuleListener` 빈은 `@Async` 프록시라 직접 호출하면 바로 반환된다. 동기 검증은 `new UserJoinedEventListener(...)`, 실제 비동기 경로는 `Scenario`로 한다.
- 동시성 테스트는 실제 DB로 한다. 충돌 경로를 결정적으로 만들 때는 상대 트랜잭션이 INSERT 후 대기하게 하고 `pg_locks`의 `NOT granted`로 대기 상태를 확인한다.
- PostgreSQL 드라이버는 Billing에서 `compileOnly`라 테스트 컴파일 경로에 없다. 드라이버 예외가 필요한 단위 테스트는 리플렉션으로 만든다(`WalletCreateUseCaseTest`). 서버 언어와 무관한 판별은 `RAISE ... USING ERRCODE, CONSTRAINT`로 검증한다(`WalletDuplicateViolationTest`).
- 처음부터 통과한 핵심 테스트는 코드를 일부러 망가뜨려 실패하는지 확인했다(락 제거·락 순서·refresh·flush·재반영 방어·누적 한도·대사 격리 수준).

---

## 7. 남은 일과 열린 결정

### 다음 Issue 후보

> 2026-10-16까지 적은 후보다. 충전·환불·환전은 그 뒤 구현됐고(1장), #142의 강의 지급 예정 건 설계는 #188로 폐기됐다(BD-62·BD-63, 당시 기록은 3.11). 열린 결정은 아래 "팀과 정할 것"이 가리키는 `decisions.md` 10장을 본다.
- **#224 후속**: 실제 TossPayments 테스트 키로 dev 서버에서 결제창 → 승인 수동 확인, 프론트 결제 계약 확정(결제창 방식·successUrl·failUrl), PENDING 주문 만료 정책, 최대 충전 금액, 가상계좌·웹훅(필요 시), PROCESSING 장기 미확정 건 운영자 조회·알림(관리자 Issue), ERD에 `pg_payment_key_hash`·`failure_reason` 반영.
- **강의 지급 연동 검증 후속 (3.14, 각 담당)**:
    - 강의 #150: 지급 배치 실패를 오류 코드로 구분(정상 재시도 `WALLET_NOT_FOUND`·일시 장애 / 확인 필요 `HOLD_ALREADY_CLOSED`·`PAYOUT_ALREADY_SETTLED`·`HOLD_STATE_INCONSISTENT`·`IDEMPOTENCY_KEY_CONFLICT`), 확인 필요 항목 격리·알림·관리자 조회. 어느 오류도 PAID_OUT으로 바꾸지 않는다.
    - 강의 #150: 판매자 탈퇴 확인(팀이 탈퇴 후 최종 수익 정책을 정한 뒤).
    - 팀: dev DB 레거시 강의 지급 건 확인 쿼리 실행, 남아 있으면 보정 Issue.
    - 운영: 지급 배치 다중 인스턴스 실행 시에도 항목 낙관적 락 + 같은 요청 ID replay로 중복 이동은 없지만 경고 로그가 늘어난다(현재 EC2 1대라 해당 없음).
- **#188 후속 (각 담당)**:
    - 강의 #150: `BillingPort.payout` 직접 지급(이미 작업 중인 `feature/tkt-150`의 호출 형태와 같다), 지급 적격성(구매 확정·지급 예정일 경과·판매자 탈퇴 아님) 판단, 지급 예정 금액 조회는 강의 주문 항목(가격·`payout_scheduled_at`·지급 상태) 기준으로 제공. `feature/tkt-150`은 #182 이전 dev 기준이라 dev를 병합할 때 `OrderCreateUseCase`·`OrderCancelUseCase`의 #182 변경과 겹칠 수 있다(#188 뒤면 겹침이 없다).
    - 강의 #156: 이의 제기 중 지급 차단(강의 상태로 판단), 인정 시 `release(full, "LECTURE-RETURN-{id}")`.
    - 강의 #150: 판매자 지갑 누락(`WALLET_NOT_FOUND`)은 항목을 CONFIRMED로 두고 다음 배치에서 같은 요청 ID로 재시도(지갑은 가입·지갑 조회 경로로만 생긴다). 재시도해도 결과가 같은 `PAYOUT_ALREADY_SETTLED`·`HOLD_ALREADY_CLOSED`는 계속 재시도하지 않도록 구분·알림할지 결정.
    - 팀: 판매자 탈퇴 후 최종 수익 정산 정책, dev DB 강의 지급 건 확인 쿼리 실행과 결과에 따른 보정 Issue(3.13 2차).
- **#182 후속 (각 담당, 강의 부분은 #188로 대체)**:
    - 출연(2차 확정: 분리 트랜잭션·HpTask 유지): `billing-port` 운영 전환 시점, **`HOLD_PENDING` 재확인 스케줄러**(상태 조회 메서드·인덱스 포함, 보류 재요청 → REQUESTED), **HpTask 재시도 스케줄러**(PENDING·next_attempt_at 조회, 재시도 한도·간격, `DEAD` 격리, Billing 영구 거절과 일시 실패 구분), Billing 500 계열을 HOLD_FAILED로 확정하지 않을지 검토, 지급·위약금도 HpTask 같은 상태 기반 복구로 설계, 요청 만료 반환, 1:N 모집 지원·선택·마감·취소 반환, 출연 확인 정상 지급(원천 = 출연 계약 ID), 호스트 일방 취소 `settlePenalty`(출연 계산 반환액·지급액).
    - 강의: 지급(#150)은 Billing의 SCHEDULED → COMPLETED 지급 경로(3.11 후속)와 함께 설계한다. 이의 제기(#156) 인정 반환은 `release(full, "LECTURE-RETURN-{id}")` 뒤 지급 건 취소가 필요하고, 이의 제기 중 지급 중단에는 `suspend`·`resume`을 포트에 더해야 한다.
    - 문서: 강의 설계서(`docs/design/lecture-module.md`)의 Billing 호출 표에 지급 예정 등록·취소를 더하는 것은 강의 담당이 한다(이번에 바꾸지 않았다).
- **#145 후속 (각 담당)**:
    - 강의 #146: 주문 항목별 `BillingPort.hold`, 구매 취소 `release`(3.10 "강의 #146이 쓸 계약"). 주문 상태 변경과 같은 트랜잭션에서 호출하고 Billing 예외를 잡지 않는다.
    - 출연: `BillingClient`·`FakeBillingClient`를 shared `BillingPort`로 교체(3.10 비교표). 위약금은 출연이 계산한 반환액·지급액으로 `settlePenalty`, 모집 전체 취소는 게스트별 `settlePenalty` + 빈자리 `release`를 같은 트랜잭션에서 호출. 출연 테스트 대역(Fake)을 어떻게 둘지는 출연이 정한다.
    - 강의 정산 #142: 아래 "강의 정산"의 예정 지급 건 방식. 지급은 shared 계약이 아니라 Billing 스케줄러 내부 경로가 될 수 있어 공개 범위는 그때 정한다.
    - 반환·지급 사유를 원장 `description`에 남길지(지금 계약에 사유 필드 없음).
    - 루트 AGENTS.md 3장은 "모듈 간 통신은 Spring Event 기본"이고 Billing 연동은 팀 확정에 따라 동기 `BillingPort` 호출이다. 루트 문서에 예외로 적을지는 팀이 정한다(이번 Issue에서 루트 문서는 바꾸지 않았다).
- **출연 연동**: 계약은 #145의 shared `BillingPort`로 정했다(3.10의 비교표). 출연 쪽 어댑터 교체는 출연 담당이 한다. 모집 전체 취소(선택된 게스트별 위약금 + 빈자리 반환)를 한 번에 처리하는 일괄 진입점이 필요하면 `HoldProcessing`으로 만든다(지금은 게스트별 위약금 정산 + 반환을 각각 호출).
- **Charge·Refund**: TossPayments 테스트 API, PG 승인과 DB 커밋 사이 상태 관리(`POINT_CHARGE` 상태·멱등키), 타임아웃을 성공·실패로 단정하지 않기.
    - 반환은 충전 원금을 역순으로 복원하므로, 환불(FIFO)이 어느 충전 건(PG 결제)을 취소할지가 반환 이력에 따라 달라진다. 환불 가능 총액은 같다.
    - 보류에 배분된 원금이 남은 충전 건은 `REFUNDED`로 바꾸면 안 된다(반환 때 원금을 되돌릴 곳이 없어 `CHARGE_PRINCIPAL_MISMATCH`). 일부 환불이면 `PARTIALLY_REFUNDED`로 둔다.
- **Exchange**: 수익 HP 1 HP 단위(#453, 이전 1,000 단위), 송금액 = floor(신청 × 90%)·수수료 = 신청 − 송금액, 정산일(매월 10일, 주말이면 다음 월요일), Mock 송금. 송금액 0원은 은행 없이 완료.
- **다른 모듈 연동**: #145에서 공개 계약을 shared `com.hapbang.shared.billing`(`BillingPort`·DTO·Enum)으로 정했다(3.10). 남은 일: 강의 #146이 `hold`·`release`를, 출연이 `BillingClient` 대신 `BillingPort`를 쓰도록 각 담당이 바꾼다. 반환·지급 사유(`reason`)를 원장 `description`에 남길지는 연동 때 정한다(지금 계약에는 없다).

### 팀과 정할 것
- 미결 항목은 [decisions.md](../../docs/billing/decisions.md) 10장에 모았다(QueryDSL 도입·버전, `UserModifiedEvent`로 ADMIN이 될 때 지갑 처리, 반환·지급 사유 기록 등). 새 미결 항목도 그곳에 적는다.
- 락 대기 시간 상한(`lock_timeout`)은 #273에서 3초로 정했다(BD-108).
- Billing 관리자 API 경로는 `AGENTS.md` 14장 규칙(`/api/v1/{module}/admin/**`)을 따른다.

---

## 8. 작업 방식 메모

- 브랜치: `dev`에서 `feature/tkt-{Issue번호}`를 만들고 PR은 **반드시 base `dev`**. (#39, #42 때 PR이 main으로 열린 적이 있다. #39는 main을 강제 리셋해 되돌렸다.)
- PR 전에 최신 `origin/dev`를 작업 브랜치에 병합하고 `check`를 다시 돌린다. main을 feature 브랜치에 병합하지 않는다.
- 커밋 메시지: `feat(billing): #{Issue번호} {내용}`.
- 범위 밖 변경 금지: 기존 Flyway 파일, `shared`·`global`·다른 모듈, Gradle 설정은 승인 없이 바꾸지 않는다. 새 테이블은 근거와 대안을 먼저 보고한다.
- 개인정보·DB 접속 정보는 커밋하지 않는다. 루트 `.env`는 `.gitignore` 대상이다.


## #372 Gateway 전환 준비 (2026-10-29)

- k8s에서 `HAPBANG_INTERNAL_API_PORT=8081`을 주면 내부 API listener를 별도로 연다. 외부 8080의 내부 경로와 내부 8081의 일반 경로는 404로 차단한다. 미설정 Compose는 기존 단일 포트 동작을 유지한다.
- Billing의 회원 backfill·강의/출연 수익 조회 Client base URL은 k8s에서 제공 앱의 8081을 사용한다. 경로·키 인증·HP 정책·DB·원장 처리는 변경하지 않았다.
- NetworkPolicy는 외부 8080을 Envoy만, 내부 8081을 공통 label(`hapbang.io/internal-api-client`)을 가진 앱 pod만 허용한다. Gateway와 실제 앱 인증 전환은 #370·#371, 운영 배포는 #374 이후다.
- 실제 listener 경계·잘못된 포트 기동 거절 테스트를 추가했고 기존 내부 키·세션·CSRF 회귀 검증과 전체 check를 통과했다.
