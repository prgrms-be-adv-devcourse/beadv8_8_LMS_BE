# Billing 모듈 진행 기록

> 문서 탐색: [지갑 문서 INDEX](../../docs/billing/INDEX.md)

Billing 작업을 이어받는 사람과 AI 에이전트(Claude Code, Codex 등)가 작업 전에 읽는 **작업 진행 상태** 문서다. 무엇을 언제 만들었고, 그때 무엇을 검토했는지를 남긴다.

| 알고 싶은 것 | 원본 |
| --- | --- |
| 업무 규칙(HP·충전·환불·환전·지급) | [`docs/policy.md`](../../docs/policy.md) |
| 결정·이유·현재 상태(유효·대체됨·폐기·미결) | [`docs/billing/decisions.md`](../../docs/billing/decisions.md) (`BD-*`) |
| 구현 규칙 | 루트 `AGENTS.md`, [`apps/billing-app/AGENTS.md`](AGENTS.md) |
| 현재 구조(패키지·클래스·API·DB) | [`docs/billing/module.md`](../../docs/billing/module.md) |
| 테스트 검증 기준·테스트 목록 | [`docs/billing/test-spec.md`](../../docs/billing/test-spec.md) |
| 테스트 실행 기록 | [`docs/billing/regression-log.md`](../../docs/billing/regression-log.md) |

- 이 문서는 결정을 따로 관리하지 않는다. 결정은 `decisions.md`에 `BD-*`로 먼저 적고, 이 문서에는 Issue 진행 상태(1장)와 그 결정의 배경(3장)만 둔다.
- Issue 하나를 끝내면 1장 행과 7장을 갱신한다. 3장에는 새 절을 쓰지 않고 3.23 목록에 Issue와 `BD-*`만 더한다(배경은 `decisions.md`의 "이유" 칸과 `CONTEXT.md`에 있다).
- 이 문서와 코드·`decisions.md`가 다르면 코드와 `decisions.md`가 맞다. 확인한 뒤 이 문서를 고친다.

마지막 갱신: 2026-11-11 (#842 프로모션 보류·지급·반환·조회 구현·전체 회귀 통과). 2026-11-06 문서 정리의 기존 이력은 Git 기록을 따른다.

---

## 1. 진행 현황

PR 칸은 `origin/dev`에 병합된 PR 번호다(2026-11-06 `git log origin/dev`로 확인). 상세 구현은 `module.md`, 결정은 `BD-*`를 본다.

| Issue | 내용 | 결정 | PR (dev 병합) | 상태 |
| --- | --- | --- | --- | --- |
| #866 | 강의·쿠폰 통합 검증을 별도 실제 billing JVM·DB·운영 HTTP로 전환, 테스트 전용 seed·원장/요청 관측 fixture | 금융 정책 변경 없음, 검증 경계는 decisions.md 13장 | — | 구현·전체 검사 완료, 리뷰 진행 중 |
| #39 | Entity·Enum·Repository, Flyway 초기 스키마, 원장 UPDATE·DELETE 차단 트리거 | — | #41 | 완료 |
| #42 | 회원 지갑 생성(가입 이벤트 + 조회 시 보완), `GET /api/v1/wallets/me` | BD-1~BD-6 | #54 | 완료 |
| #57 | HP 원장·잔액 버킷 이동·멱등성·역분개 검증·잔액 대사 | BD-7~BD-20 | #67 | 완료 |
| #81 | 팀 공통 API 컨벤션 적용(Facade `@PreAuthorize`, Swagger Spec 인터페이스) | — | #82 | 완료 |
| #78 | 보류 생성: 수익 HP 우선·충전 HP FIFO 배분, 참조 대상당 보류 1건 | BD-21~BD-28 | #99 | 완료 |
| #100 | 보류 부분·전체 반환: 충전 HP 우선·충전 원금 역순 복원, 처리 이력 재생 대조 | BD-29~BD-35 | #114 | 완료 |
| #116 | 애그리거트 간 ID 참조, Enum Javadoc, QueryDSL 도입 보류 | BD-36~BD-38 | #124 | 완료 |
| #127 | 지갑 조회 API 성공 응답 `RsData` | BD-106 | #132 | 완료 |
| #134 | 보류 부분·전체 지급, 지급 건, 호스트 일방 취소 위약금, 지급 원천 전역 UNIQUE | BD-39~BD-50 | #139 | 완료 |
| #141 | 다른 지갑의 같은 참조 대상 동시 보류 경쟁 수정(조회 순서 원장 → 보류) | BD-27 | #148 | 완료 |
| #145 | 연동 계약 `shared` 이동, 멱등키 생성 Billing으로, 위약금 금액 계산을 출연으로 | BD-51~BD-61 | #166 | 완료 (`BillingPort`는 #486에서 삭제) |
| #142 | 강의 판매 수익 지급 예정 건(SCHEDULED) 관리 | BD-62 | #185 | 완료 → #188에서 제거 |
| #182 | 강의 구매·취소에 지급 예정 연결, 출연 `BillingPortClient` 준비 | BD-63~BD-66 | #190 | 완료 → 강의 부분 #188에서 제거, 출연 Fake는 #289에서 제거 |
| #188 | 강의 직접 지급으로 전환, Billing 강의 지급 예정 관리 제거 | BD-67~BD-69, BD-71 | #196 | 완료 |
| #211 | 강의 자동 지급·이의 제기와 지갑 연동 정합성 검증(운영 코드 변경 없음) | BD-70, BD-72~BD-74 | #220 | 완료 |
| #224 | TossPayments 테스트 결제 HP 충전, 결과 불명확·승인 후 DB 실패 복구 | BD-75~BD-81 | #238 | 완료 |
| #241 | 종류별 보류(MIXED·CHARGE_ONLY·REVENUE_ONLY)와 확정 차감, 충전 건 지정 | BD-82~BD-86 | #259 | 완료 |
| #260 | TossPayments FIFO 부분 환불, PG 취소 결과 복구, 실제 PG E2E | BD-87~BD-93 | #283 | 완료 |
| #285 | 수익 HP 환전 신청, 테스트 계좌 스냅샷, 잠정 정산 예정일 | BD-94~BD-98 | #287 | 완료 |
| #288 | 환전 송금 Mock 은행 API·HTTP Client | BD-99~BD-101 | #290 | 완료 |
| #294 | 관리자 환전 승인·거절, 지갑 회원(`billing_user`) 동기화 | BD-102~BD-104 | #298 | 완료 |
| #295 | 지갑 거래 내역·수익 조회 API | — (`decisions.md` 12장 "결정 기록이 없는 구현") | #323 | 완료 |
| #304 | Mock 환전 송금 실행·결과 복구 | BD-101 | #309 | 완료 |
| #305 | 환전 정산일 배치와 송금 복구 자동화 | — (`decisions.md` 12장 "결정 기록이 없는 구현") | #320 | 완료 |
| #324 | 관리자 회원 Backfill API | BD-104 → BD-132 | #325 | 완료 |
| #384 | (문서) JVM 분리 회귀 검증용 `docs/billing` 문서 구성(module·decisions·test-spec·regression-log), 운영 코드 변경 없음 | BD-1~BD-110 정리 | #385 | 완료 (문서) |
| #398 | (문서) JVM 분리 회귀 검증 실행 기록(R-001~R-003)과 Unit 통과 기준 보강, 운영 코드 변경 없음 | — | #399 | 완료 (문서, Issue 종료 확인 2026-11-06) |
| #272 | shared Billing 공통 HTTP Client(`BillingApiClient`)와 계약 문서 `docs/api/internal-billing.md` | — (강의 D64, `docs/api/internal-billing.md`) | #400 | 완료 |
| #273 | HP 내부 HTTP API, `HoldFacade` `ROLE_INTERNAL`, `lock_timeout` 3초 → 503 `HP_LOCK_TIMEOUT` | BD-108, BD-111~BD-113 | #410 | 완료 |
| #372 | (Infra, 지갑 부분) 내부 API 포트 8081 분리, NetworkPolicy | — | #407 | 완료 |
| #411 | shared `BillingApiClient`에 Resilience4j(지갑을 부르는 쪽) | — (`docs/api/internal-billing.md`) | #413 | 완료 |
| #336 | 플랫폼 지갑, 환전 수수료 수입 원장 | BD-114~BD-118 | #445 | 완료 |
| #447 | (문서) 결정 기록(`decisions.md`)과 진행 기록(이 문서) 중복 정리, #273 결정 반영 | BD-108, BD-111~BD-113 반영 | #451 | 완료 (문서) |
| #453 | 환불·환전 1 HP 단위, 송금액 버림, 송금액 0원 환전 | BD-119~BD-122 | #456 | 완료 |
| #428 | Kafka 소비 규칙에 맞춘 회원 이벤트 멱등 문서 정리 | BD-123 | #459 | 완료 (문서) |
| #430 | 회원 이벤트 Kafka 소비(소비 그룹 둘, 그룹별 DLT) | BD-123 | #467 | 완료 |
| #471 | Modulith 이벤트 기록 설정·테이블 제거 | BD-124 | #473 | 완료 (#497에서 다시 켬) |
| #479 | 보류 무효화 내부 API | BD-125~BD-127 | #483 | 완료 |
| #486 | `BillingPort` 테스트를 HTTP 계약 테스트로 전환, `BillingPort`·`BillingPortAdapter` 삭제 | BD-128 | #493 | 완료 |
| #494 | shared Command → billing 내부 Command 변환 | BD-129 | #495 | 완료 |
| #497 | 지갑 이벤트(`billing.events.v1`) 발행 | BD-130 | #505 | 완료 |
| #537 | Gateway 사용자 헤더 인증, 지갑 회원 확인 | BD-131 | #550 | 완료 |
| #552 | prod 첫 배포 준비(관리 포트 8082, Prometheus, Hikari pool, Backfill 지갑 생성) | BD-132, BD-133 | #560 | 완료 |
| #562 | 나가는 호출 circuit breaker | BD-134 | #563 | 완료 |
| #564 | 배포 전 정리(회원 이벤트 DLT 복구 절차, capture 내부 API 비노출) | BD-135, BD-136 | #565 | 완료 (문서) |
| #577 | 서비스 간 HP 대사용 보류·지급 조회 내부 API | BD-137 | #587 | 완료 |
| #598 | 배치·Backfill 실행 락(PostgreSQL advisory lock) | BD-138 | #600 | 완료 |
| #607 | 변경된 지갑의 잔액 대사 매일 실행 | BD-139 | #611 | 완료 |
| #623 | 회원 탈퇴용 지갑 닫기·다시 열기 내부 API | BD-141 | #638 | 완료 |
| #627 | 지갑 사용자 API 회원별 rate limit | BD-140 | #635 | 완료 |
| #655 | JVM 분리 재검토 결정 마무리, 모놀리스 시절 문서 정리(운영 코드 변경 없음) | BD-142, BD-143 | #657 | 완료 |
| #660 | 지갑 테스트의 옛 공용 설정 제거, 테스트 목록 정리 | — | #661 | 완료 |
| #674 | 회원 정보 파기(`UserMaskedEvent`) 때 환전 계좌 지우기·열린 지갑 닫기 | BD-144, BD-145 | #678 | 완료 |
| #822 | 잔액 대사 실행 기록, 기동 시 대사 지표 복원, 실패 지갑 이월 DB화 | BD-146 | — | 진행 중 (구현·범위 테스트 R-004 통과, 리뷰·PR 전) |
| #841 | 프로모션 지갑(관리자 충전·회수·조회), 쿠폰 예산 예약·해제·조회 내부 API, 대사 프로모션 불변식 | BD-147~BD-156 | #846 | 완료 (`a41d7523`) |
| #842 | 주문 항목 프로모션 보류·재원별 지급·반환·무효화·조회, 구매자 0원 앵커 | BD-157~BD-161 | — | 구현·전체 회귀·자체 리뷰 완료, PR 검토 대기 |

- 지갑 결정 없이 지갑 코드·설정을 함께 바꾼 공통 Issue: #582(운영 JSON 로그·MDC, PR #695), #608(메모리 limit 512Mi, PR #612), #630·#631(trace 계측, PR #667·#650), #726(heap 상한, PR #731), #733(Spring Boot 기본 사용자 제거, PR #737).
- 다음 후보는 7장, 미결 항목은 `decisions.md` 10장에 있다.

---

## 2. 현재 구조

현재 패키지·클래스·API·DB는 [`docs/billing/module.md`](../../docs/billing/module.md) 2·3·8장이 원본이다. 이 문서는 클래스 표를 따로 두지 않는다(2026-11-06까지 있던 표는 #486에서 지운 `BillingPortAdapter`·shared `BillingPort`, #188에서 지운 강의 지급 예정 클래스를 현재 구조처럼 적고 있었다).

---

## 3. 결정 배경

결정 목록·이유·현재 상태의 원본은 [decisions.md](../../docs/billing/decisions.md)다. 이 장은 Issue 당시의 분석·검토 경위·검증 기록이다.

- 각 절 머리의 `> 결정:` 줄에 그 절에서 나온 `BD-*`와 `decisions.md`의 현재 상태를 적었다(2026-11-06 기준).
- 머리에 **역사 기록**이라고 적은 절은 폐기·대체된 전제(같은 JVM `BillingPort` 호출, 호출한 쪽 트랜잭션 참여, 모듈 간 잠금 순서 등) 위에서 쓴 기록이다. 지금 지킬 것은 머리에 적은 현재 결정이다.
- 같은 절 안에서 검토 차수에 따라 바뀐 문장은 확정 문장을 남기고, 옛 문장은 "N차에서 바뀜"으로 표시했다.
- 규칙 문장은 되도록 `BD-*` 참조로 줄였다. 남긴 것은 왜 그렇게 정했는지, 검토 차수에서 무엇이 바뀌었는지다.
- #453 이후 Issue는 절을 따로 쓰지 않고 3.23에 `BD-*` 목록으로 둔다.

### 3.1 지갑 생성 (#42)

> 결정: BD-1~BD-6 (1장). 상태: BD-1~BD-5 유효, BD-6 대체됨 → BD-123(#430: Kafka `@KafkaListener`, 실패는 블로킹 재시도 뒤 그룹별 DLT) — [decisions.md](../../docs/billing/decisions.md)

- **회원당 지갑 1개.** `uk_wallet_user_id` UNIQUE로 최종 보장한다.
- **가입 이벤트로 생성하고, `GET /wallets/me`에서 없으면 생성한다.** — 이벤트는 커밋 후 비동기라 늦을 수 있고, Billing 이전 가입 회원에게는 이벤트가 없다. 두 경로 모두 `WalletCreateUseCase.getOrCreate`를 써서 결과가 같다.
- **ADMIN은 지갑을 만들지 않는다.** — HP를 쓰지 않는다(루트 AGENTS 6장). 이벤트에서 건너뛰고, 조회 API는 `WalletFacade.getMyWallet`의 `@PreAuthorize("hasAnyRole('USER', 'INFLUENCER', 'VERIFIED_INFLUENCER')")`로 생성 전에 403을 낸다(2026-10-12 회의: 관리자 API 외 역할 검사는 서비스 레이어 `@PreAuthorize`). 가입 리스너는 로그인 정보가 없으므로 역할 검사 없는 `getOrCreateWallet`을 쓴다.
- **생성은 `REQUIRES_NEW` 별도 트랜잭션에서 한다.** — INSERT 실패로 오류 상태가 된 PostgreSQL 트랜잭션에서 재조회하지 않기 위해서다. 롤백이 끝난 뒤 바깥에서 재조회한다.
- **중복 판별은 PostgreSQL 드라이버 오류 정보로 한다.** — `PSQLException.getServerErrorMessage()`의 SQLState `23505` + 제약 이름 `uk_wallet_user_id`가 모두 맞을 때만 기존 지갑을 재조회한다. Hibernate는 메시지 문자열(`constraint "..."`)을 파싱해 서버 언어(`lc_messages`)가 바뀌면 깨지므로 쓰지 않는다. 다른 오류는 그대로 던진다.
- **(#430에서 바뀜, BD-6 → BD-123)** 당시 **리스너는 `@ApplicationModuleListener(propagation = NOT_SUPPORTED)`.** — 생성이 자체 트랜잭션을 열기 때문에, 리스너 트랜잭션까지 있으면 이벤트 하나가 커넥션 2개를 잡는다. 실패하면 이벤트 발행 기록이 미완료로 남아 재시작 시 재처리된다.

### 3.2 원장과 잔액 (#57)

> 결정: BD-7~BD-13 (2장) — 유효 — [decisions.md](../../docs/billing/decisions.md)

- **잔액 변경은 `LedgerRecordUseCase.record()`만 한다.** — 원장 INSERT와 지갑 변경을 한 트랜잭션에서 처리한다. ArchUnit 테스트(`WalletApplyLedgerAccessTest`)가 다른 실행 코드의 `Wallet.applyLedger` 호출을 막는다.
- **재반영 방지는 원장 ID가 아니라 `pendingApplication`으로 한다.** — IDENTITY 전략은 INSERT 시점에 ID가 생기므로 ID로는 판단할 수 없다. 빌더로 만든 항목만 true이고, 반영하면 false, DB에서 읽은 항목은 기본 생성자라 false다.
- **금융 거래에서는 지갑을 자동 생성하지 않는다.** — 없으면 `WALLET_NOT_FOUND`. `getOrCreateWallet()`은 가입·내 지갑 조회 전용이다.
- **락: 지갑 ID 오름차순으로 `PESSIMISTIC_WRITE`.** — 원장 줄 순서와 무관하게 `LedgerOperation.walletIds()`(TreeSet) 순서로 잠근다. 교착 방지.
- **락 전 flush, 락 후 refresh.** — 락 조회는 영속성 컨텍스트에 이미 있던 지갑 상태를 갱신하지 않아 lost update가 생긴다(테스트로 확인). refresh가 같은 트랜잭션의 미반영 변경을 지우지 않도록 먼저 flush한다.
- **원장 금액은 부호 있는 값, 0은 금지.** 증감은 `Math.addExact`로 오버플로를 막고(`BALANCE_OVERFLOW`), 음수가 되면 `INSUFFICIENT_BALANCE`.
- **버킷 이동은 같은 작업 키의 두 줄.** `LedgerEntry.move()`가 `from -금액`, `to +금액`을 만든다. HP 총량은 변하지 않는다.

### 3.3 멱등성 (#57)

> 결정: BD-14~BD-17 (2장) — 유효 — [decisions.md](../../docs/billing/decisions.md)

- **작업 키(`operationKey`) = 비즈니스 작업 하나, 원장 멱등키 = `{operationKey}:{줄번호}`.** 줄 번호는 요청의 원장 줄 순서(1부터)다.
- **지갑 락을 잡은 뒤 작업 키로 기존 원장을 조회한다.** 줄마다 멱등키·지갑·HP 종류·버킷·금액·거래 유형·참조 대상·역분개 원본이 같으면 잔액을 바꾸지 않고 기존 원장 ID를 돌려준다(`replayed=true`). 다르면 `IDEMPOTENCY_KEY_CONFLICT`. 발생 시각·설명은 비교하지 않는다.
- **같은 키가 겹치지 않는 지갑에 동시에 오면** 락으로 직렬화되지 않으므로 `uk_point_ledger_idempotency_key` UNIQUE(23505 + 제약 이름)로 막고 `IDEMPOTENCY_KEY_CONFLICT`로 바꾼다.
- **새 작업 상태 테이블은 만들지 않았다.** — 업무 요청 전체의 멱등성은 각 업무 테이블(`POINT_HOLD`, `POINT_CHARGE`, `SETTLEMENT`, `EXCHANGE`)의 멱등키와 호출하는 쪽의 결정론적 작업 키로 맡는다.

### 3.4 역분개 (#57, 원장 단위 검증까지만)

> 결정: BD-18·BD-19 (2장) — 유효 — [decisions.md](../../docs/billing/decisions.md)

- 거래 유형 `REVERSAL` ⇔ `originalLedgerId` 함께 지정. RELEASE·REFUND 등 정상 거래는 원본을 가질 수 없다.
- 원본과 같은 지갑·HP 종류·버킷, 원본과 반대 부호.
- 부분·여러 번 역분개 허용. 원본별 누적 합계의 절댓값 ≤ 원본 절댓값. 같은 작업 안의 앞 줄도 함께 센다.
- 역분개의 역분개 금지. 여러 줄 원장은 줄 단위로 역분개할 수 있다.
- 역분개로 잔액이 음수가 되면 전체 롤백.
- 위반은 `INVALID_REVERSAL`, 원본이 없으면 `ORIGINAL_LEDGER_NOT_FOUND`.
- 동시 역분개는 역분개 지갑(= 원본 지갑) 락으로 직렬화되어 누적 한도를 넘지 않는다. Flyway 변경 없음.
- **실제 거래의 취소·환불·보상 처리는 각 비즈니스 서비스가** 여러 원장과 업무 상태를 함께 조정한다. Billing 원장 계층은 줄 단위 정합성만 책임진다.

### 3.5 잔액 대사 (#57)

> 결정: BD-20 (2장) — 일부 대체됨 → BD-139(Scheduler 없음 부분. 고치지 않음·`REPEATABLE_READ`·관리자 API 없음은 유효) — [decisions.md](../../docs/billing/decisions.md)

- `reconcile(walletId)`는 `@Transactional(readOnly = true, isolation = REPEATABLE_READ)` — 지갑과 원장 합계를 같은 스냅숏에서 읽어 동시 거래 중 오탐을 막는다(READ COMMITTED로 바꾸면 테스트가 실패함을 확인).
- 결과: 버킷 4개마다 `walletHp`, `ledgerHp`, `differenceHp`(캐시 − 원장)와 전체 `matched`. 원장이 없는 버킷은 0.
- 불일치가 있어도 원장·지갑을 고치지 않는다. 관리자 API는 없다. #607부터 최근 72시간 안에 바뀐 지갑을 매일 자동으로 대사한다(BD-139).

### 3.6 HP 보류 생성 (#78)

> 결정: BD-21~BD-28 (3장). 상태: BD-21~BD-27 유효, BD-28 일부 대체됨 → BD-112(`@PreAuthorize`·공개 HTTP API 없음 부분, 잔액 부족 예외는 유효) — [decisions.md](../../docs/billing/decisions.md)

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
- **잔액 부족은 예외**(`INSUFFICIENT_BALANCE`)로 알린다. 출연 쪽 `BillingClient`의 `HoldStatus.INSUFFICIENT_BALANCE` 결과로 바꾸는 것은 연동 어댑터가 한다(당시 계획. #145에서 결과값 대신 예외를 그대로 전파하기로 정했다, 3.10).
- **(#273에서 바뀜, BD-112: 지금은 내부 HTTP API + `@PreAuthorize("hasRole('INTERNAL')")`)** 당시 **역할 검사 없음**: `HoldFacade`는 강의·출연 서비스가 결제자 확인 후 호출하는 내부 기능이라 `@PreAuthorize`를 두지 않는다. 공개 HTTP API도 만들지 않았다.

### 3.7 HP 보류 반환 (#100)

> 결정: BD-22, BD-29~BD-35 (3장) — 유효 — [decisions.md](../../docs/billing/decisions.md)

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
- **flush·refresh**: `lockWallet`이 먼저 flush하므로 이후 보류·배분·충전 건 refresh가 같은 트랜잭션의 미반영 변경을 지우지 않는다. 호출한 쪽 트랜잭션이 락 없이 미리 읽어 둔 엔티티는 refresh로 최신 상태가 된다(테스트로 확인. 당시 같은 JVM 호출 기준이고, 지금은 지갑 트랜잭션 안에서 미리 읽은 엔티티에만 해당한다, BD-143). 반환은 `record()` 전까지 지갑을 바꾸지 않고, `record()`의 시작 flush가 보류·배분·충전 원금·상세 변경을 원장보다 먼저 반영한다.
- **`RELEASE`는 정상 보류 해제**다. 원장 정정용 `REVERSAL`(원본 원장 참조)과 구분한다.
- **강의 보류 단위 = 주문 항목 (2026-10-13 강의 담당 합의, 적용)**: `HoldCommand.HOLDABLE_REFERENCE_TYPES`의 `LECTURE_ORDER`를 `LECTURE_ORDER_ITEM`으로 바꿨다. 여러 강의를 함께 사면 항목마다 보류하고, 개별 취소는 그 항목의 보류만 반환한다. Enum 값과 DB CHECK에 이미 있어 **마이그레이션은 필요 없다**. `LECTURE_ORDER` Enum 값·CHECK는 남긴다(원장 참조 등 다른 용도, 기존 Flyway 수정 금지). 운영 코드에 보류 호출자가 아직 없어 `LECTURE_ORDER`로 만든 기존 보류는 없다. 있었다면 `ReleaseCommand`가 거절해 반환할 수 없으므로, 강의 연동 전에 보류가 만들어졌다면 확인이 필요하다. 강의 모듈 API는 이미 `orderItemId`를 쓴다(강의 코드는 바꾸지 않았다).

### 3.8 JPA 연관관계·Enum·QueryDSL (#116, 멘토 리뷰)

> 결정: BD-36~BD-38 (4장) — 유효(BD-38은 팀 결정 대기) — [decisions.md](../../docs/billing/decisions.md)

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

### 3.9 HP 지급·위약금 (#134)

> 결정: BD-34, BD-39~BD-50 (5장). 상태: BD-46·BD-47(Billing이 30% 계산, 위약금 0 거절)은 대체됨 → BD-56·BD-57, 나머지 유효 — [decisions.md](../../docs/billing/decisions.md). 규칙은 AGENTS 8장.

당시에는 공개 HTTP API 없이 `HoldFacade.payout`·`settlePenalty`를 같은 JVM의 내부 계약으로 만들었다(내부 HTTP API는 #273). 지급 가능 여부·금액은 출연(이후 강의)이 업무 상태로 정하고, Billing은 지갑·보류·지급 원천·금액·멱등성·금융 정합성만 검증한다는 경계는 지금도 같다.

- **규칙 요약 (원본은 BD)**: 수익 HP 보류 먼저·충전 원금 FIFO 소진·받는 쪽은 수익 HP 가용(BD-39), 보류 상태 `PAID`·`MIXED`(BD-34), 지급 건은 기존 `SETTLEMENT`에 COMPLETED로 즉시 INSERT·스키마 변경 없음(BD-40), 지급 키 `PAYOUT:{holdId}:{payoutRequestId}`(BD-44), 자기 지급 금지(BD-45), 위약금 키(BD-48), 지갑 ID 오름차순 → 보류 → 충전 건 락(BD-49).
- **검토 차수에서 바뀐 것**
    - 1차 → 2차: 앱 확인이 같은 지급 유형만 봐서 **같은 게스트 출연에 정상 지급과 위약금이 둘 다 지급될 수 있었다**(테스트로 재현). DB `UNIQUE(point_hold_id, source_type, source_id, payout_type)`는 유형이 달라 막지 못한다. 2차에서 같은 보류·원천의 지급은 유형과 관계없이 1건으로 바꿨다(BD-43). 같은 보류의 지급은 지갑·보류 락으로 직렬화되므로 확인과 저장 사이에 끼어들 수 없다.
    - 2차 → 3차: 1:1 지급 원천 ID를 요청서 ID와 같게 요구했는데, 확정 계약(출연 계약 ID)과 맞지 않아 원천 유형만 확인하도록 바꿨다(BD-41). 출연 계약이 그 보류에 속하는지는 Billing이 알 수 없어 출연이 검증한다.
    - 3차: 한 원천(출연 계약·주문 항목)은 **어느 보류에서든** 한 번만 지급한다(같은 계약 ID가 다른 호스트 보류로 와도 막음, BD-42). 계산된 위약금 0이면 거절하도록 바꿨다(BD-47, 지금은 BD-57 "게스트 지급액 0 거절"). 2차까지 있던 "0이면 반환만" 경로를 없앤 이유: 위약금 처리마다 지급 건이 남아야 재요청 때 받는 회원·원천·기준액을 지급 건으로 대조할 수 있다.
    - 4차 (A안): Flyway `V202610131930`으로 `UNIQUE(source_type, source_id)`를 더했다. 받는 지갑이 다른 두 요청(출연 쪽 오류로 같은 계약 ID가 다른 게스트·호스트 보류로 온 경우)은 지갑 락으로 직렬화되지 않아 앱 확인만으로는 막지 못한다. 늦은 쪽은 INSERT에서 UNIQUE 위반(23505)이 나고 `PAYOUT_ALREADY_SETTLED`로 바꿔 전부 롤백한다. 지급 건을 만드는 코드가 #134가 처음이라 기존 데이터 충돌은 없었다. 이 제약 때문에 실패 뒤 **새 행**으로 재지급하는 설계는 쓸 수 없고 같은 행을 재처리해야 한다.
- **위약금 기준액 분석 (2026-10-13, #145에서 계산 책임이 출연으로 옮겨감)**: 원래 계약 금액을 기준으로 하면 일부 처리된 보류에서 다른 게스트 몫을 침범할 수 있고, 남은 보류액을 기준으로 하면 이미 일부 처리된 출연의 위약금이 계약보다 작아진다. 권장안은 "출연이 취소된 출연의 계약 출연료를 보내되, 그 몫은 취소 전까지 반환·지급하지 않는다"였다. 이러면 두 기준이 같아지고 Billing의 "남은 보류 이하" 검사가 중복 처리를 막는다.
- **위약금에서 `HoldReleaseUseCase.release()`를 부르지 않은 이유**: 반환은 보낸 쪽 지갑만 잠그므로, 받는 쪽 지갑을 나중에 잠그면 락 순서가 뒤집힌다. 그래서 반환·지급 적용을 트랜잭션 없는 `HoldProcessing`으로 나눠 세 UseCase가 지갑을 모두 잠근 뒤 쓰게 했다. 반환은 충전 HP·나중 배분부터, 지급은 수익 HP·먼저 배분부터 쓰므로 기준액이 남은 보류 이하면 두 순서의 결과가 같다.
- **Billing이 아는 것과 모르는 것 (2차)**: Billing은 보류·상세·지급 건으로 이미 지급된 게스트 출연을 확인할 수 있다. 아직 처리 이력이 없는 게스트의 계약 출연료, 모집 인원·자리별 금액, 출연 상태는 모르고 반환 상세에는 받는 사람·원천이 없다. 게스트별 계약 금액 테이블을 만들지 않고 이 검증을 출연 책임으로 두었다. 같은 모집에서 한 게스트가 다른 출연 ID로 두 번 지급받는 경우(1인 1회 지원 위반)도 출연 정책이라 Billing이 막지 않는다.
- **검증**: `HoldPayoutIntegrationTest`(정상 지급·혼합 이력·위약금·롤백·동시성), `ChargeUsageHistoryTest`(반환·지급 혼합 재생). 다른 보류의 같은 계약 확인·1:1 1회 확인·위약금 0 거절·UNIQUE 오류 변환을 각각 빼면 실패함을 확인했다. 같은 유형의 원천 중복은 앱 확인을 빼도 DB UNIQUE가 막지만, NORMAL·PENALTY 교차 중복은 앱 확인만 막는다(`sameSource_normalAndPenaltyExclusive`). (당시 서술. 4차에서 더한 `UNIQUE(source_type, source_id)`는 지급 유형을 포함하지 않아 지금은 교차 중복도 DB가 막는다)

### 3.10 연동 계약 shared 이동·위약금 계산 책임 변경 (#145, 2026-10-14)

> 결정: BD-51~BD-61 (5장). 상태: BD-51 일부 대체됨 → BD-128(`BillingPort` 부분), BD-52 폐기 → BD-128, BD-53 일부 대체됨 → BD-143(호출한 쪽 트랜잭션 참여 부분), BD-60 일부 대체됨 → BD-143(롤백 전용 트랜잭션 공유 부분. 오류를 성공으로 해석하지 않는 것은 유효, BD-70), BD-54 일부 대체됨 → BD-111(내부 HTTP API에서는 shared Command가 요청 본문, 구조 오류 400), BD-55~BD-59·BD-61 유효 — [decisions.md](../../docs/billing/decisions.md)
>
> **역사 기록**: 강의·출연이 같은 JVM에서 shared `BillingPort`를 부르고 Billing이 호출한 쪽 트랜잭션에 참여하던 때의 절이다. 지금은 내부 HTTP API(#273)로 부르고, `BillingPort`·`BillingPortAdapter`는 #486에서 지웠고(BD-128), 요청 하나가 지갑 트랜잭션 하나다(BD-143). 위약금 계산 책임(BD-56~BD-59)과 검증 경계는 지금도 유효하다.

- **왜 shared로 옮겼나**: 강의·출연 `build.gradle.kts`는 `:shared`만 의존해 Billing 클래스를 볼 수 없다. 연동 요청·결과·Enum을 `com.hapbang.shared.billing`에 두면 두 모듈이 Billing을 의존하지 않는다. 이 구조는 지금도 같고(BD-51 유효 부분), Java Port만 없어졌다.
- **당시 Adapter를 따로 둔 이유**: `HoldFacade`가 shared 포트를 직접 구현하면 반환형이 shared 결과로 바뀌어, 충전 건별 배분·복원·소진·보류 상태를 확인하던 기존 통합 테스트의 검증을 잃는다. 내부 결과와 외부 결과를 나누는 이 생각은 지금 `BillingResultDtos`(외부 결과 변환)와 billing 내부 `Payment*Command`(BD-129)로 이어진다.
- **Enum 이동**: `BillingReferenceType`·`SettlementSourceType`만 옮겼다. 엔티티가 shared Enum을 그대로 `EnumType.STRING`으로 쓰므로 저장 문자열·CHECK가 같다. `BillingReferenceType`에는 Billing 내부용 값(`POINT_CHARGE`·`EXCHANGE` 등)도 있지만 엔티티가 한 Enum을 쓰므로 나누지 않았다.
- **검증 경계 (BD-54·BD-55)**: shared는 구조(필수 값·보류 가능 참조 유형·요청 ID 형식)만, Billing은 금액·잔액·소유·원천·멱등성을 검사한다. 당시 shared 구조 오류는 같은 JVM의 프로그래밍 오류라 500 `INTERNAL_ERROR`였다. 내부 HTTP API에서는 요청 본문 역직렬화 중에 나므로 400 `INVALID_REQUEST`다(BD-111).
- **위약금 계산 책임 변경 (핵심, BD-56~BD-59)**: 취소 가능 여부·대상 게스트·계약 출연료·비율·반올림·호스트 반환액은 출연이 정하고, Billing은 받은 반환액과 지급액을 한 트랜잭션에서 처리한다. 30% 같은 비율을 Billing에 두지 않는다.
    - **호스트 반환액 0 허용 (1차 검토 → 2차 팀 확정)**: 위약금이 기준액 전부인 비율(예: 100%)도 출연이 정할 수 있게 했다. 0 HP 원장 금지는 유지하므로 반환 상세·원장을 만들지 않고 지급만 남긴다. 재요청은 반환 상세가 없으면 반환액 0으로 보고 대조한다.
    - **재요청 대조를 반환액·지급액 각각으로**: #134는 합계(기준액)만 비교했다. 합계가 같아도 배분이 다르면 다른 요청이므로 `IDEMPOTENCY_KEY_CONFLICT`로 바꿨다.
- **잔액 부족은 결과값이 아니라 예외**: 출연 초안의 `HoldResult(INSUFFICIENT_BALANCE)` 결과값 대신 `BillingException(INSUFFICIENT_BALANCE)` 409를 쓴다. 당시 이유("호출한 쪽 트랜잭션이 이미 롤백 전용")는 BD-143으로 사라졌고, 지금 이유는 호출한 쪽이 Billing 오류를 성공으로 해석하지 않는다는 BD-70이다.
- 당시 출연 `BillingClient` 초안과의 차이(보류 키 생성 제거, 반환·지급 요청 ID, 위약금을 게스트 1명 단위로)는 출연이 #289에서 반영했고, 지금 연동 계약은 `docs/api/internal-billing.md`다.
- **검증 (당시)**: shared `BillingCommandsTest`·`SharedBillingDependencyTest`, `BillingKeysTest`·`BillingCommandValidatorTest`, `BillingPortIntegrationTest`(#486에서 삭제, 결과 변환·금액 오류는 `ApiV1BillingInternalControllerTest`로 옮김), `HoldPayoutIntegrationTest` 위약금 테스트(다른 비율·반환 0·반환/지급 각각 충돌, 반환 0 + 혼합 이력 재생, 지급 원장 실패 롤백).

### 3.11 강의 판매 수익 지급 예정 건 관리 (#142, 2026-10-14)

> 결정: BD-62 (6장) — 폐기(#188) — [decisions.md](../../docs/billing/decisions.md)
>
> **역사 기록**: 이 절에서 만든 구현은 #188에서 모두 지웠다(3.13). 강의 지급은 지금 강의가 판단하고 내부 API로 지급한다(BD-67~BD-70).

- **무엇을 만들었나**: 주문 항목마다 `SETTLEMENT`에 SCHEDULED 지급 예정 건을 만들고(`LectureSettlementFacade`), 지급 가능 시각(구매 KST 날짜 + 31일 00:00), 이의 제기 중단·재개(SUSPENDED), 구매 취소(CANCELLED)를 관리했다. 실제 지급·스케줄러는 범위 밖이었다.
- **남은 흔적**: `SettlementStatus.CANCELLED`와 Flyway `V202610141530__add_settlement_cancelled_status.sql`. 적용된 Flyway와 DB CHECK·기존 행 호환 때문에 남긴다(만드는 코드는 없다, BD-62).
- **당시 검토에서 남길 만한 것**
    - 취소 시각은 ERD에 없는 컬럼을 더하지 않으려고 `updated_at`으로 남겼다.
    - 같은 트랜잭션에서 지급 예정 취소를 반환보다 먼저 부르면 지급 건 → 지갑·보류 순서가 되어 이후 지급 경로와 교착할 수 있었다. 그래서 "반환 → 취소" 순서를 계약으로 적었다. #188에서 이 경로가 사라져 지금은 의미가 없다.
    - 판매자 탈퇴는 당시 Billing에 회원 정보가 없어 구현하지 않았다. 지금 강의 판매자 탈퇴 뒤 수익 처리는 강의가 판단한다(policy.md 6장, BD-74 탈퇴 부분 해소).
- 당시 적은 "후속 실제 지급 Issue에 반영할 것 ①~⑧"과 "후속 지급 연계 설계"는 #188에서 방향이 바뀌어 쓰지 않았다.

### 3.12 강의·출연 동기 연동과 실패 복구 (#182, 2026-10-14)

> 결정: BD-63~BD-66 (6장). 상태: BD-63 폐기(#188), BD-64 대체됨 → 강의 D62·D65(#275), BD-65 유효, BD-66 폐기(#289에서 출연 Fake 모드 삭제, #655에서 확인) — [decisions.md](../../docs/billing/decisions.md)
>
> **역사 기록**: 강의가 같은 JVM의 `BillingPort`·`LectureSettlementPort`를 한 트랜잭션에서 부르고, 출연은 `appearance.billing.mode=fake`가 기본이던 때의 절이다. 지금 강의·출연은 로컬 상태를 커밋한 뒤 트랜잭션 밖에서 내부 API를 부르고, 실패는 각자의 HP 작업으로 복구한다(BD-143, 강의 D62·D65, `docs/appearance/module.md`).

- **무엇을 했나**: 최신 `origin/dev`(`45e2d14`)를 코드로 조사해 실제로 있던 강의 주문 보류·구매 취소 반환(#146)에 #142 지급 예정 등록·취소를 연결했다(#188에서 제거). 출연에는 `BillingPortClient` Adapter만 준비하고 기본값은 Fake로 두었다(출연 담당 결정).
- **2차 검토에서 확정한 것**
    - 출연은 분리 트랜잭션 + HpTask를 유지한다(BD-65 유효). 이슈 본문은 "출연 상태 변경과 보류 동일 트랜잭션"이었지만 출연 설계는 "저장 커밋 → 트랜잭션 밖 보류, 반환은 HpTask 커밋 후 실행"이었다. 당시 출연에는 HOLD_PENDING 재확인·HpTask 재시도 스케줄러가 없었고, 이후 출연이 HP 작업 재시도·보류 재확인 배치를 만들었다(#439 등, 출연 문서).
    - 강의는 판매자 지갑이 없으면 주문 전체를 실패시켰다. #188에서 "구매 때 판매자 지갑을 보지 않는다"(BD-68)로 바뀌었다.
- **당시 강의 롤백 매트릭스**(보류 실패 → 주문 없음, 지급 예정 등록 실패 → 주문·보류 롤백, 반환 실패 → 항목·보류 그대로)는 한 트랜잭션을 전제로 한 검증이라 지금 구조에는 맞지 않는다(BD-64 대체).

### 3.13 강의 지급 정책 변경 — 강의 직접 지급 (#188, 2026-10-14)

> 결정: BD-67~BD-69 (6장, 유효. BD-67의 호출 수단은 지금 내부 API `POST /api/v1/internal/billing/payouts`), 미결 BD-71 (10장) — [decisions.md](../../docs/billing/decisions.md)
>
> 이 절의 정책(강의가 지급을 판단하고 지갑은 지급 엔진만 제공)은 유효하다. 호출 수단으로 적힌 `BillingPort.payout`은 **역사 기록**이다.

- **왜 바꿨나**: 팀 요청으로 강의 판매 수익 지급의 책임 경계를 출연과 같게 맞췄다. 지급 원천 모듈(강의)이 지급 시점·이의 제기·판매자 상태를 판단하고, Billing은 보류·반환·지급·원장·멱등성만 맡는다. Billing이 강의 업무 상태(이의 제기·구매 확정)를 따라가는 지급 예정 관리는 결합만 늘린다.
- **지운 것과 남긴 것**: #142·#182의 강의 지급 예정 경로(`LectureSettlement*`, shared `LectureSettlementPort`, `Settlement.scheduledLecture`·상태 전이, 오류 코드 `SETTLEMENT_NOT_FOUND`·`SETTLEMENT_AMOUNT_MISMATCH`·`SETTLEMENT_INVALID_STATE`)를 지웠다. 출연·위약금·즉시 지급 엔진, `SettlementStatus.CANCELLED`, 모든 Flyway는 그대로 두었다(DB 제약 변경·새 Flyway 없음).
- **판매자 지갑 (2차 확정, BD-68)**: 구매 때 판매자 지갑을 보지 않는다. 지급 때 없으면 `WALLET_NOT_FOUND`로 전부 롤백하고 지갑을 자동 생성하지 않는다. 지갑이 생긴 뒤 같은 지급 요청 ID로 다시 지급한다.
- **중복 지급 오류 코드의 순서 (BD-69)**: 지급 엔진은 같은 지급 키 상세(replay)를 먼저, 끝난 보류(`HOLD_ALREADY_CLOSED`)를 그다음, 같은 원천 지급 건(`PAYOUT_ALREADY_SETTLED`)을 마지막에 본다. 그래서 전액 지급한 항목에 다른 요청 ID가 오면 `HOLD_ALREADY_CLOSED`, 부분 지급 뒤면 `PAYOUT_ALREADY_SETTLED`다. 세 경우 모두 HP·원장·지급 건 INSERT 전에 끝난다. 당시 출연 Fake도 같은 순서라 모드를 바꿔도 오류 코드가 달라지지 않았다.
- **레거시 지급 건 (BD-71, 미결)**: PR #190(#182) 병합부터 #188 배포 전까지 당시 dev 서버(EC2 Compose)에서 강의를 구매했다면 `SCHEDULED`·`CANCELLED` 지급 건이 남았을 수 있었다. `SCHEDULED`·`SUSPENDED` 행이 있는 항목은 직접 지급이 `PAYOUT_ALREADY_SETTLED`로 영구히 막힌다(테스트로 확인, HP는 보류 그대로라 손실·중복 없음). 당시 작업 환경에서 dev DB에 접속하지 못해 확인하지 못했다. 이후 EC2 Compose·dev 서버는 없어졌고(#440) 운영 k3s 첫 배포에서 데이터 이관이 없었다(#552). BD-71을 닫을지는 `decisions.md` 10장에서 정한다.
    - 확인 쿼리(읽기 전용): `SELECT status, count(*) FROM billing.settlement WHERE source_type = 'LECTURE_ORDER_ITEM' GROUP BY status;`
    - 당시 추천: 지급 건을 지우지 않고 별도 보정 Issue로 처리한다. 지급 엔진에 레거시 행 재사용 분기를 넣는 방안은 엔진 변경이라 권하지 않았다.
- **PR #196 충돌 해결 (2026-10-14)**: #156(이의 제기)이 먼저 dev에 들어오며 `LectureSettlementPort`를 쓰기 시작해 충돌했다. #156 담당과 협의해 이의 제기 인정은 `release(full, "LECTURE-RETURN-{id}")`만 하도록 정리했다(지급 예정 취소 호출 제거, #156 규칙 유지).

### 3.14 강의 자동 지급·이의 제기 연동 검증 (#211, 2026-10-15, 기준 `origin/dev` 70ecb2c)

> 결정: BD-70 (6장, 유효), BD-72 폐기 → BD-142, BD-73 일부 대체됨 → BD-142(같은 트랜잭션 flush 전제 부분), BD-74 (10장: 판매자 탈퇴 부분은 policy.md 6장으로 해소, 강의 지급 배치의 재시도 불가 오류 격리 부분은 미결) — [decisions.md](../../docs/billing/decisions.md)
>
> **역사 기록 (잠금 순서 분석)**: 강의·고객센터 행과 지갑 행이 같은 DB·같은 트랜잭션에서 잠기던 때의 분석이다. JVM·database 분리 뒤 한 트랜잭션에서 함께 잠기지 않으므로 모듈 간 잠금 순서는 두지 않는다(BD-142). 지갑이 지키는 락 순서는 `billing` database 안의 것뿐이다. 오류 분류(BD-70)는 유효하다.

목표는 강의 지급을 Billing에 다시 만드는 것이 아니라, 강의가 직접 지급·반환할 때 금융 정합성과 재시도 안전성을 확인하는 것이었다. Billing·강의 운영 코드는 바꾸지 않았다(결함 없음).

- **오류를 지급 완료로 해석하지 않는다 (BD-70)**: `HOLD_ALREADY_CLOSED`는 지급 완료뿐 아니라 반환(구매 취소·이의 제기 인정)으로 닫힌 보류에서도 나고, `PAYOUT_ALREADY_SETTLED`는 레거시 `SCHEDULED`·`SUSPENDED` 행에서도 난다. 호출한 쪽이 이 오류를 "지급됨"으로 바꾸면 판매자에게 HP가 가지 않은 항목이 지급 완료가 된다. 그래서 지급 성공의 근거는 지급 호출의 정상 반환(처음 또는 replay)뿐이다.
- **재시도 불가 오류 격리 (BD-74 미결 부분)**: 당시 강의 지급 배치는 모든 예외를 같은 방식으로 다음 실행에 재시도해, `PAYOUT_ALREADY_SETTLED`·`HOLD_ALREADY_CLOSED`·`HOLD_STATE_INCONSISTENT`·`IDEMPOTENCY_KEY_CONFLICT`도 매일 warn만 남기고 영구 재시도했다. 오류 코드로 구분해 격리·알림하는 일은 강의 후속으로 남겼다.
- **당시 잠금 분석 (BD-72, 폐기)**: 경로별로 `lecture_order → inquiry → order_item → wallet(ID 오름차순) → point_hold → point_charge` 순서를 따른다고 보고 "순환 없음"으로 결론지었다. 이 전역 순서가 폐기된 이유는 위 머리말과 같다.
- **지금도 쓸 수 있는 사실**: Hibernate 7.4 `PostgreSQLDialect`의 `PESSIMISTIC_WRITE`는 `FOR NO KEY UPDATE`라 FK 검사의 `FOR KEY SHARE`와 충돌하지 않는다. 그래서 원장·상세·지급 건 INSERT가 다른 트랜잭션의 지갑·보류 락에 막히지 않는다. 같은 보류의 지급·반환은 지갑 → 보류 락으로 한쪽만 성공하고 늦은 쪽은 `HOLD_ALREADY_CLOSED`다(BD-73 유효 부분).
- **확인된 교착 (강의 범위)**: 같은 구매자가 같은 두 강의를 반대 순서로 동시에 주문하면 `uk_order_item_buyer_lecture_owned` 유일 키 대기가 엇갈려 40P01 → 한쪽 500이 났다(보류 전 단계라 금융 데이터는 일관). 강의 담당 수정 대안만 적었고, 이후 처리 여부는 이 문서에서 미확인이다.
- **검증 (당시)**: 실제 지급 배치 + 실제 Billing 경합 테스트(`LectureOrderPayoutIntegrationTest`, 당시 `apps` 모듈). 판매자 지갑 없음 → 지갑 생성 뒤 다음 실행에서 지급, 이의 제기 중 지급 안 함, 배치가 읽은 뒤 인정 커밋 → version 충돌로 Billing 미호출, 레거시 행 → 두 번 실행해도 무변화, 잠금 경합 재현(`pg_locks` 대기 확인). 클래스 3회 반복 실행 모두 통과했다.

### 3.15 TossPayments 테스트 결제 HP 충전 (#224, 2026-10-15, 기준 `origin/dev` 4d91633)

> 결정: BD-75~BD-81 (7장) — 유효(BD-75의 ERD 반영, BD-81의 최대 금액·주문 만료는 미결) — [decisions.md](../../docs/billing/decisions.md)

충전 HP가 시스템에 들어오는 첫 경로다. 기존 보류·반환·지급 엔진과 shared `BillingPort`는 바꾸지 않았고, 충전 확정은 기존 `LedgerRecordUseCase.record()`로만 지갑을 바꾼다.

- **기존 상태 분석**: `PointCharge`는 builder·원금 배분/복원만 있었고 UseCase·API·PG 연동이 없었다. `PointChargeStatus`(PENDING·COMPLETED·PARTIALLY_REFUNDED·REFUNDED·FAILED)로는 "승인 요청을 보냈지만 결과를 모름"을 표현할 수 없었다 → PENDING으로 두면 같은 주문의 동시 승인 요청을 막을 수 없고, FAILED로 두면 실제 승인된 결제를 잃는다. `pg_payment_key` UNIQUE는 무작위 IV 암호문에는 의미가 없다.
- **Flyway `V202610151830__alter_billing_point_charge_for_toss_confirm.sql` (새 파일, 기존 파일 수정 없음)**: 상태 CHECK에 PROCESSING 추가, `pg_payment_key` VARCHAR(255→512, 암호문 길이), `pg_payment_key_hash` VARCHAR(64) + `uk_point_charge_pg_payment_key_hash`, `failure_reason` TEXT, `idx_point_charge_2 (status, updated_at)`. 기존 UNIQUE(`pg_order_id`·`pg_payment_key`)는 유지. 기존 데이터: 충전 기능이 없어 운영·개발 DB에 충전 행이 생길 경로가 없었다(테스트만 직접 INSERT) → 새 컬럼은 NULL 허용이라 기존 행과 호환. **ERD에 없는 컬럼 2개(`pg_payment_key_hash`, `failure_reason`)를 더했다** — 결제키 암호화 규칙과 중복 식별·운영자 확인 요구 때문이며 ERD 갱신은 팀 공유 필요.
- **상태 전이**: PENDING(주문) → PROCESSING(승인 요청 시작, 결제키 저장) → COMPLETED(승인 확인 + 원장) | FAILED(승인 안 됨 확정). PROCESSING은 `failure_reason`에 마지막 미확정 사유를 남기며 유지된다.
- **트랜잭션 경계**: Facade·`ChargeConfirmUseCase`는 트랜잭션을 열지 않는다. `ChargeProcessing.claim`(충전 건 락만, 지갑 락 없음) 커밋 → TossPayments 승인(트랜잭션 밖) → `complete`(지갑 → 충전 건 락, 보류 생성의 지갑 → 충전 건과 같은 순서) 또는 `fail`·`noteUnresolved`. claim이 지갑을 잠그지 않아 보류·반환과 순환 대기가 없다. 충전 건 PENDING·PROCESSING은 `findAllocatableForUpdate`(COMPLETED·PARTIALLY_REFUNDED만) 대상이 아니라 보류 배분과 락이 겹치지 않는다.
- **결과 판정·복구 (확정, 현재 규칙은 AGENTS 10.1)**: 4xx 중 401·409·429와 ALREADY_PROCESSED_PAYMENT·IDEMPOTENT_REQUEST_PROCESSING은 결과 불명확(PROCESSING 유지), 그 밖의 4xx = FAILED. 5xx·시간 초과·연결 끊김·이미 처리됨 = 조회로 판단(DONE 확정, ABORTED·EXPIRED·CANCELED 실패, PARTIAL_CANCELED는 PROCESSING 유지, 조회 404·READY·IN_PROGRESS는 결과 확인에서만 같은 멱등키로 승인 재요청, 나머지는 PROCESSING 유지). 1차에서는 401·409·429도 FAILED, PARTIAL_CANCELED도 실패로 봤다 — 2차 검토 2번과 PR 전 보완 ①②에서 바뀜. 승인 후 DB 반영 실패는 PROCESSING + 결제키가 이미 커밋되어 있으므로 같은 결제의 재요청 또는 `ChargeRecoveryBatch`(기본 5분, 마지막 변경 1분 경과, 100건)가 조회로 확정한다. 승인 결과가 주문과 다르면 충전하지 않고 `CHARGE_STATE_INCONSISTENT` + 사유(운영자 확인).
- **멱등성·동시성**: 같은 주문 동시 승인 → 첫 요청만 claim(행 락), 나머지는 PROCESSING을 보고 조회 경로(Recover)로 간다. TossPayments `Idempotency-Key: hapbang-charge-confirm-{orderId}`로 PG 중복 승인 방지. 충전 원장 작업 키 `CHARGE:{chargeId}` + complete의 상태 재확인으로 HP 한 번. 결제키 HMAC UNIQUE가 다른 주문의 같은 결제키를 최종 차단(`CHARGE_PAYMENT_KEY_CONFLICT`).
- **보안**: 결제키 AES-256-GCM(무작위 12바이트 IV, 128비트 태그, "v1:" + Base64) + HMAC-SHA256(별도 키). 응답·로그에 결제키·시크릿 키 없음(`TossPayment`·`ChargeConfirmRequest`·`RecoveryTarget`·`TossPaymentsProperties`의 `toString` 마스킹). 환경 변수 3개(없으면 503, 앱 기동은 됨), `.env.example`에 생성 방법. 당시에는 compose dev·prod에 빈 기본값으로 넘겼고, 지금 prod는 k3s Secret `billing-app-secrets`로 넘긴다.
- **API (프론트 연동)**: ① `POST /api/v1/wallets/charges {amount}` → 201 `{orderId, orderName, amount, status: PENDING}` ② 결제창(클라이언트 키는 프론트 보관) `requestPayment({orderId, orderName, amount, successUrl, failUrl})` ③ successUrl에서 쿼리 `paymentKey·orderId·amount`를 `POST /api/v1/wallets/charges/confirm`으로 → 200 COMPLETED / 202 PROCESSING(같은 요청 재전송 또는 `GET /api/v1/wallets/charges/{orderId}`로 확인) / 400 `CHARGE_PAYMENT_REJECTED`(detail·pgCode) ④ 잔액은 `GET /api/v1/wallets/me`.
- **검증 범위**: 실제 TossPayments 테스트 API는 **호출하지 않았다**(이 환경에 테스트 키 없음). PG 응답은 JDK `HttpServer` 기반 `FakeTossPaymentsServer`가 TossPayments 문서의 승인·조회·오류 코드·멱등키 동작을 재현한다. 실제 결제창 → 승인 연동은 따로 확인해야 했다(로컬 확인은 아래. 당시 계획한 dev 서버는 #440에서 없어졌고, prod 확인은 7장 #224 후속).
- **2차 검토 (2026-10-15)**: 실제 코드에서 결함 4건을 찾아 테스트로 재현(수정 전 실패 확인)한 뒤 최소 수정했다. BillingPort·보류 엔진·Flyway는 그대로.
    1. HMAC UNIQUE 경합이 409가 아니라 500: `claim`이 `EntityManager.flush()`를 직접 불러 Hibernate `ConstraintViolationException`이 Spring 예외로 바뀌지 않았다 → `PointChargeRepository.saveAndFlush`로 바꿨다(롤백·PG 미호출은 원래도 맞았다).
    2. PG `PARTIAL_CANCELED`를 FAILED로 끝냄: 승인 후 일부만 취소되어 돈 일부가 PG에 남는데 추적에서 빠진다 → PROCESSING + 사유(운영자 확인). CANCELED(전액 취소)는 FAILED 유지.
    3. 결과 확인 배치 기아: 같은 사유로 미확정이면 엔티티 변경이 없어 `updated_at`이 그대로 → 오래된 순 100건이 계속 같은 미확정 건 → 100건이 넘으면 새 건을 복구하지 못한다 → 미확정 기록 때 `touchUpdatedAt`으로 확인 시각을 항상 갱신.
    4. 복호화 실패가 복구 전체를 막음: `recoveryTarget`이 먼저 복호화해 예외 → 결제키가 필요 없는 조회 확정(DONE)도 실패 → 복호화 실패 시 결제키 null, 조회 확정은 진행, 재승인만 `PAYMENT_KEY_UNREADABLE`로 미확정.
    - 추가 테스트(`ChargeIntegrationTest` 23 → 30): HMAC UNIQUE 경합(상대 미커밋 INSERT + `pg_locks` 대기 확인), 재요청 1 + 배치 2 동시 복구(원장 1·HP 1), FAILED 주문은 PG DONE이어도 COMPLETED 불가(재요청·배치·확정 단계), 다른 결제키로 승인된 결제 미충전, PARTIAL_CANCELED 미확정, 미확정 반복 시 `updated_at` 갱신, 복호화 불가 시 DONE 확정·재승인 보류.
    - 분석으로 확인(테스트 없음): PG 멱등키 15일 이후 재요청은 결제 세션(인증 후 10분)이 먼저 만료되어 READY 재승인 경로가 생기지 않는다. `SimpleClientHttpRequestFactory`(HttpURLConnection)는 재사용 연결에서 POST를 한 번 재전송할 수 있지만 Idempotency-Key가 PG 중복 승인을 막는다. 4xx = PG가 요청을 처리하지 않음 = 승인 안 됨이므로 FAILED는 돈을 잃지 않는다(설정 오류 401도 같은 처리 — 사용자는 새 주문 필요).
- **실제 TossPayments 테스트 결제 검증 (2026-10-15, 로컬)**: `local` 프로필 + 테스트 키(`test_sk_`, API 개별 연동)로 앱 실행 → 결제창(v2 SDK `payment().requestPayment`, 레포 밖 임시 페이지) 카드 인증 → `confirm` 200 COMPLETED, TossPayments 조회 `DONE`·10,000. 충전 건 COMPLETED·원금 10,000, 지갑 충전 HP 10,000, 원장 `CHARGE:1` 1줄(원장 합 = 지갑 = 원금 합), 같은 결제 재요청 2회 모두 처음 결과·PG 재호출 없음·원장 1줄. 가짜 결제키 승인은 실제 PG가 404 `NOT_FOUND_PAYMENT_SESSION` → 400 `CHARGE_PAYMENT_REJECTED`·FAILED.
    - **실제와 가짜 서버의 차이**: 인증만 하고 승인 전인 주문을 실제 TossPayments로 조회하면 **404 `NOT_FOUND_PAYMENT`**다(가짜 서버는 READY). 그래서 "승인 요청이 PG에 닿지 않음 → 조회 READY → 같은 멱등키로 재승인" 복구는 실제로 일어나지 않고 PROCESSING(운영자 확인)으로 남는다. 금융 위험은 없다. → 바로 아래 PR 전 보완 ①에서 조회 404에도 같은 멱등키로 재승인하게 했다.
- **PR 전 PG 오류 처리 보완 (2026-10-15, 커밋 `92017c1`)**: ① 조회 404 → `TossResult.NotFound`, 결과 확인(배치·같은 결제 재요청)에서만 같은 멱등키로 재승인(승인 요청 직후 경로는 재승인하지 않음). 재승인이 거절(세션 만료 등)이면 FAILED, 승인이면 확정, 모르면 PROCESSING. ② 4xx 분류를 상태 + 코드 기준으로: 401·409·429와 ALREADY_PROCESSED_PAYMENT·IDEMPOTENT_REQUEST_PROCESSING은 결과 불명확(PROCESSING 유지), 나머지 4xx는 FAILED. 가짜 서버도 실제처럼 승인 전 결제 조회를 404로 바꾸고 409·429·401·세션 만료 장애를 더했다(`ChargeIntegrationTest` 34, `TossPaymentsClientTest` 6, Billing 415).
    - 자동 복구 범위: 승인 요청이 PG에 닿지 않았고 결제 세션(인증 후 10분)이 남아 있으면 배치(마지막 변경 1분 경과, 5분 주기)가 재승인해 충전. 세션 만료면 FAILED(돈 안 나감). 운영자 범위: PG가 첫 요청을 5xx로 처리해 같은 멱등키 응답이 5xx로 굳은 경우(재승인도 같은 5xx), 조회 5xx 지속, 결제키 복호화 불가 + 404, PARTIAL_CANCELED·WAITING_FOR_DEPOSIT·승인 결과 불일치.
- **정하지 않은 것(팀·프론트)**: 결제창 방식(결제위젯 vs 결제창 SDK, 키 종류가 다름), successUrl·failUrl, 최대 충전 금액, PENDING 주문 만료(현재 영구 PENDING, PG 세션은 인증 후 10분에 만료), 결제 후 화면 이동, 가상계좌 등 비동기 결제수단(웹훅 필요, 현재 PROCESSING 유지·운영자 확인). 지금 남은 항목은 7장 #224 후속과 `decisions.md` 10장에 있다.

### 3.16 종류별 보류와 확정 차감 (#241, 2026-10-15, 기준 `origin/dev` 5d58307)

> 결정: BD-82~BD-86 (7장) — 유효 — [decisions.md](../../docs/billing/decisions.md)

- **기존 구조**: `point_hold`(HP 종류별 최초·남은 보류, 상태) + 별도 테이블 `point_hold_detail`(반환·지급 한 건씩, 충전 건 정보 없음) + `point_charge_usage`(충전 건별 배분: 남은 보류·소진·반환). 상세가 어느 충전 건에서 처리됐는지는 규칙(반환 역순·지급 순서)으로 재생해 배분 누적값과 대조한다(`ChargeUsageHistory`).
- **재사용**: 새 테이블 없음. 보류 종류는 `point_hold.hold_type`(기본 MIXED), 확정 차감은 `point_hold_detail.action_type = CAPTURE`, 충전 건 지정은 nullable `target_charge_id`(FK → point_charge). 소진은 `point_charge_usage.consumed_hp`(지급과 공유)라 배분 불변식·재생 대조가 그대로 동작한다. 보류 상태에 `CAPTURED` 추가.
- **왜 충전 건 지정인가**: 환불은 PG 결제(충전 건)마다 취소 결과가 다르다. 규칙(FIFO)으로 확정 차감하면 실제로 취소된 결제와 다른 충전 건의 원금을 차감해, 남은 원금이 실제 PG 잔액과 어긋난다(이후 환불이 이미 취소된 결제를 다시 취소하려 함). 그래서 CHARGE_ONLY 보류는 충전 건을 지정해 확정 차감·반환할 수 있게 했다.
- **동시성·정합성**: 확정 차감도 지갑 락 → 보류 락 → (반환 시) 충전 건 락 순서, 상세 UNIQUE, 상태 재확인. 보류·배분·상세·원장·지갑이 한 트랜잭션. 기존 반환·지급의 최종 상태 판단에 CAPTURE 이력을 더했다(기존 보류는 CAPTURE가 없어 결과 불변).
- **Migration** `V202610152000__alter_billing_hold_for_capture.sql`: hold_type + CHECK 2개, 상태 CHECK에 CAPTURED, 상세 동작 CHECK에 CAPTURE, target_charge_id + CHECK(지정이면 충전 HP만) + FK. 기존 행은 MIXED·NULL로 그대로 호환.
- **테스트**: `HoldCaptureIntegrationTest` 18(종류별 보류·잔액 부족·전체/부분/추가 확정 차감·확정 후 반환·재요청·동시 서로 다른 요청 8·같은 요청 동시·확정 차감 vs 반환 동시·지급과 보류 공유 불가·충전 건별 환불 부분 성공·지정 거절·원장 실패 롤백·DB 제약·MIXED 회귀), `ChargeUsageHistoryTest` +3, `BillingSchemaTest`(FK 19, 지정 충전 건 ID 참조), `BillingEnumStorageTest`(hold_type, 구성 CHECK 제외).
- **2차 검토 (2026-10-15)**: 충전 건 지정 확정 차감·반환에서 금액을 생략(null)하면 그 배분의 남은 보류 전부를 처리하던 계약을 금액 필수로 좁혔다(PG가 20,000 중 10,000만 취소했는데 20,000을 차감하는 실수 방지). 금액 관계 확인: 상세 충전 금액 = 지정 배분의 소진(확정 차감)·반환 증가분, 확정 차감은 `point_charge.remaining_principal_hp`를 바꾸지 않음(보류 때 이미 예약), 반환만 지정 충전 건 원금을 복원, 원장은 확정 차감 = 보류 감소 1줄(REFUND)·반환 = 보류→가용 2줄. 추가 테스트 3개(`HoldCaptureIntegrationTest` 21): PG 부분 취소 금액만 차감·금액 없는 지정 거절, FIFO 환불 부분 성공·실패·불명확 혼합의 충전 건별 금액, 서로 다른 충전 건 확정 차감·반환 + 같은 요청 재전송 동시 실행(3회 반복, 교착 없음).
- **남은 일(후속 환불·환전 Issue)**: 환불 확정 차감 후 `point_charge` 상태(PARTIALLY_REFUNDED·REFUNDED)·`point_refund` 기록, 환전 `exchange`·`exchange_payout` 연결은 각 Issue에서. 확정 차감은 HP 이동만 한다. → 환불은 #260(3.17)에서 했다.

### 3.17 TossPayments FIFO 부분 환불과 PG 취소 결과 복구 (#260, 2026-10-15, 기준 `origin/dev` 4eb55ea, `feature/tkt-260`)

> 결정: BD-87~BD-93 (7장). 상태: BD-87 일부 대체됨 → BD-119(금액 규칙, #453), BD-88~BD-93 유효 — [decisions.md](../../docs/billing/decisions.md)

규칙 전체는 billing AGENTS.md 10.2. 여기에는 결정 배경과 검증 기록만 둔다.

- **기존 상태**: `point_refund_request`·`point_refund` 테이블과 엔티티는 있었지만 동작이 없었고, 상태 값(PENDING·UNKNOWN 등)이 이번 정책과 달랐다. 충전 건에는 환불액을 기록할 곳이 없어 PARTIALLY_REFUNDED·REFUNDED를 판단할 수 없었다.
- **Flyway `V202610152100__alter_billing_refund_for_toss_cancel.sql` (새 파일)**: 두 테이블의 상태 CHECK 교체(기존 값은 UPDATE로 옮긴다. 기능이 없어 실제 행은 없다), 요청 `failure_reason`·`canceled_at`·`idx_point_refund_request_3 (status, updated_at)`, 개별 환불 `failure_reason`·`attempt_count`(CHECK ≥ 0)·`last_attempted_at`(3차에서 PG 요청 횟수·결과 재확인 횟수 컬럼으로 나눔, 아래)·`uk_point_refund_refund_request_id_charge_id`, 충전 건 `refunded_amount_hp`(기본 0) + `ck_point_charge_4`. **ERD에 없는 컬럼 6개를 더했다**(팀 공유 필요).
- **설계 선택**
    - 접수와 처리를 나누되 API는 접수 직후 같은 요청에서 처리까지 한다(결과를 바로 돌려주기 위해). 그래서 회원 취소는 사실상 "접수 후 처리 전에 멈춘 요청"에만 쓰인다. 회원이 취소할 수 있도록 일부러 처리를 미루는 정책은 없어서 두지 않았다.
    - 우리 취소를 PG 이력에서 찾는 방법: TossPayments 취소 이력(`cancels[]`)에는 우리 ID를 넣을 필드가 없다. 그래서 `cancelReason`에 멱등키를 대괄호로 감싸 넣고(`HP 환불 [hapbang-refund-cancel-{UUID}]`), 대괄호까지 포함해 비교한다(부분 일치 방지). 처음에는 `{요청ID}-{충전ID}`였으나 실제 PG 테스트 전 점검에서 DB 초기화·환경 간 ID 중복으로 같은 상점에 키가 재사용될 위험을 찾아 UUID로 바꿨다(3.17 4차).
    - 결과 확인에서만 같은 멱등키로 재요청한다. 조회에서 우리 취소가 안 보이면 재요청하고, 첫 요청이 PG에서 처리됐다면 그 응답이, 처리 중이면 409가 온다. 같은 실행에서 결과 확인은 1회만 한다(테스트에서 기준 시각을 미래로 주면 PG를 연달아 부르던 문제를 발견해 고쳤다).
    - 취소 금액이 요청과 다르면 차감하지 않고 운영자 확인. 돈은 이미 나갔을 수 있지만 얼마를 차감할지 자동으로 정하지 않는다.
    - `PointCharge.restorePrincipal`의 복원 한도를 `충전액 − 가용 원금 − 환불 누계`로 좁혔다(환불한 원금이 되돌아오지 않게).
- **TossPayments 확인 내용(문서, 2026-10-15)**: 취소 `POST /v1/payments/{paymentKey}/cancel` — `cancelReason`(필수, 200자), `cancelAmount`(없으면 전액), Idempotency-Key 지원. 응답은 Payment(`balanceAmount`, `status` PARTIAL_CANCELED·CANCELED, `cancels[]`: transactionKey·cancelAmount·cancelReason·canceledAt·cancelStatus DONE). 오류: 400 ALREADY_CANCELED_PAYMENT·PROVIDER_ERROR(일시)·INVALID_REQUEST 등, 403 NOT_CANCELABLE_AMOUNT·NOT_CANCELABLE_PAYMENT·FORBIDDEN_CONSECUTIVE_REQUEST(일시)·EXCEED_MAX_REFUND_DUE 등, 404 NOT_FOUND_PAYMENT, 5xx는 재시도 가능.
- **알려진 위험**: 실제 TossPayments가 5xx·시간 초과 응답을 멱등키 응답으로 기억하는지 확인하지 못했다. 기억한다면 같은 키 재요청도 같은 오류라 그 건은 UNKNOWN·보류로 계속 남는다(이중 환불은 없음, 테스트 `refund_unknownCachedErrorKeepsHold`). 몇 번 뒤 운영자 확인으로 넘길지는 3차에서 정했다(24시간·재확인 12회, 아래). 운영자용 처리 API(MANUAL_REVIEW 해소)는 없다.
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
    1. 준비: 루트 `.env`의 테스트 키(`test_sk_`)만 쓴다(라이브 키 금지). `local` 프로필로 앱 실행, USER로 로그인(당시 세션. 지금은 Gateway 사용자 헤더 인증이라 로컬 Envoy로 JWT를 넣는다, `docs/dev/local.md`).
    2. 충전 2건: 3.15 절차로 10,000 HP를 두 번 충전한다(결제 A, B). `GET /api/v1/wallets/me`로 충전 HP 20,000 확인.
    3. 부분 환불: `POST /api/v1/wallets/refunds {"amount":3000}`, `Idempotency-Key: manual-1` → 200 COMPLETED, 항목 1건(결제 A, 3,000). 토스 개발자센터 테스트 결제 내역에서 A가 부분 취소 3,000, 취소 사유 `HP 환불 [hapbang-refund-cancel-…]`인지 확인. DB: `point_charge`(A) `refunded_amount_hp` 3000·PARTIALLY_REFUNDED, 지갑 충전 HP 17,000, `point_ledger` REFUND −3,000.
    4. 재요청: 같은 헤더·금액으로 다시 보내 같은 `refundRequestId`·PG 취소 추가 없음 확인. 금액을 바꾸면 409.
    5. 여러 결제 건: `{"amount":12000}`, `Idempotency-Key: manual-2` → A 7,000 + B 5,000 순서로 취소, A는 REFUNDED(PG는 balanceAmount 0이지만 status `PARTIAL_CANCELED` — 아래 실제 결과 참고), B는 PARTIAL_CANCELED.
    6. 정합성: 지갑 충전 HP 5,000, 원장 합 = 지갑, 각 결제의 PG 취소 합 = `refunded_amount_hp`.
    7. 실행하지 않는 것: 실제 PG에서 5xx·시간 초과·이미 취소됨을 일부러 만드는 시험(가짜 서버로만 검증). 5xx 멱등 응답 기억 여부는 TossPayments에 문의하거나 문서로 확인.

### 3.18 환전 신청·테스트 계좌 스냅샷·수익 HP 보류 (#285, 2026-10-16, 기준 `origin/dev` ef0d200, `feature/tkt-285`)

> 결정: BD-94~BD-98 (8장). 상태: BD-95 일부 대체됨 → BD-120(수수료 계산, #453), BD-94·BD-96~BD-98 유효(BD-96은 1차 대체) — [decisions.md](../../docs/billing/decisions.md)

규칙 전체는 billing AGENTS.md 11장. 여기에는 결정 배경만 둔다.

- **기존 상태**: `exchange`·`exchange_account`·`exchange_payout` 테이블·엔티티만 있고 동작이 없었다(행 없음). 상태 CHECK에 취소가 없고, 계좌는 "구조만 준비"였다.
- **사용자 결정 (2026-10-16)**: ① 계좌 A안(신청마다 테스트 계좌 입력, 사전 등록 테이블 없음, 실제 확인 없음) ② 정산 회차 = 신청일 이후 첫 10일, **공휴일 미반영 날짜를 확정 정산일로 취급하지 않는다**(잠정 회차값으로만 저장·응답, 실제 송금일은 정산일 배치 Issue. 1차에는 주말 보정도 하지 않았다 — 2차에서 바뀜: 토·일이면 다음 월요일) ③ 이번에는 CANCELED만 추가, APPROVED는 승인 Issue에서 ④ 계좌번호 암호화는 결제키 키 재사용(환경 변수·compose 변경 없음) ⑤ 형식: 은행 숫자 3자리·계좌 숫자 8~20자리·예금주 1~50자.
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
- **후속 연동 지점 (당시)**: 관리자 승인(REQUESTED → APPROVED, Flyway로 상태 추가, 승인 후 취소 차단은 이미 됨) / 정산일 배치(영업일·공휴일로 실제 송금일 계산, `payout_scheduled_at`은 회차 선택용) / Mock 송금(`exchange_payout`, 성공 → 확정 차감, 불명확 → 보류 유지). 잠금 순서 지갑 → 환전 → 보류를 지킨다. → #294(승인)·#304(송금)·#305(정산일 배치)에서 했다. 승인 상태는 #294가 Flyway `V202610162000`으로 더했고(커밋 0b8c6f33), "승인 후 취소 차단"은 #285 때부터 `Exchange.cancel`이 REQUESTED만 받는다(커밋 820ca779, 지금 `Exchange.java` 201-204줄, BD-97). 당시 적은 "최종 실패 → release"는 #304에서 확정 실패(FAILED)는 보류 유지, 관리자 최종 실패(FINAL_FAILED)만 반환으로 정했다(BD-101).

### 3.19 환전 송금 Mock 은행 API·HTTP Client (#288, 2026-10-16, 기준 `origin/dev` 04e9c23, `feature/tkt-288`)

> 결정: BD-99~BD-101 (8장) — 유효(BD-100은 2·3차 대체) — [decisions.md](../../docs/billing/decisions.md)

규칙은 billing AGENTS.md 11장(환전 송금). 결정 배경만 둔다.

- **기존 상태**: `exchange_payout`(환전 1건당 1행, `exchange_id` UNIQUE·FK, `payout_request_id` UNIQUE, 공급자 MOCK)은 우리 쪽 송금 이력용 구조이고 코드가 없었다. HTTP 테스트 도구는 WireMock 없이 JDK `HttpServer` 가짜 서버(Toss)와 채팅 RANDOM_PORT E2E만 있었다.
- **왜 `exchange_payout`을 Mock 저장소로 쓰지 않았나**: 그 표는 "우리가 보낸 송금"이고 후속 송금 UseCase가 쓴다. Mock 은행이 같은 표를 쓰면 은행 쪽 기록과 우리 쪽 이력이 섞인다. 은행 쪽 멱등 기록은 FK 없는 `mock_payout_transfer`로 분리했고, 이번 Issue에서 `exchange_payout`은 쓰지 않는다. 운영 DB에도 표는 생기지만(사용자 결정 유지) Mock이 꺼져 행은 생기지 않는다.
- **같은 애플리케이션 HTTP 호출 위험과 대응**: ① 트랜잭션 안 호출 금지(Client가 HTTP·소켓 연결 전에 거절) ② Mock 쪽은 접수·결과 확정을 각각 짧은 트랜잭션으로 커밋, 지연은 트랜잭션 밖 ③ 응답 시간 제한(기본 5초)과 TIMEOUT 지연(기본 8초) 분리 ④ 포트는 `local.server.port`를 호출 때 읽는다 ⑤ 보안은 Mock 경로 전용 체인 + 기동 시 무작위 접근 키(global SecurityConfig 변경 없음, billing에 `spring-security-config`·`-web` 의존 추가).
- **2차 검토 결함 1건**: 활성 조건이 설정(`enabled`)뿐이라 운영·dev에 `HAPBANG_BILLING_MOCK_PAYOUT_ENABLED=true`가 잘못 들어가면 Mock API·시나리오 제어·세션 없는 보안 체인이 켜질 수 있었다 → 설정 + 프로필 허용 목록(local·test·default) + dev·prod 차단(`MockPayoutActivation`·`@ConditionalOnMockPayout`), Client도 같은 판단 — 허용 목록은 3·4차에서 바뀜(아래, 확정은 4차). 새 Flyway 없음.
- **3차 확정 (2026-10-16)**: 활성 조건에서 "프로필 없음(default)"을 뺐다 → `enabled=true AND 활성 프로필 (local OR test) AND NOT (dev OR prod)`. 실제 활성 프로필만 보므로 `spring.profiles.default=local`로 뜬 로컬 앱(bootRun·IntelliJ 기본 실행)은 Mock이 꺼진다 — 로컬에서 쓰려면 `SPRING_PROFILES_ACTIVE=local`. 실제 서버 HTTP 테스트는 `@ActiveProfiles("test")`. 테스트: 활성 조건 16(요청한 12 조합 + 기본 프로필 local·test, 설정 없음), 프로필 통합 8(prod·dev·프로필 없음·기본 프로필 local, 각 2).
- **4차 확정 (2026-10-16)**: 활성 프로필이 전부 local·test여야 한다(허용 목록 밖 프로필이 섞이면 꺼짐, local,staging 차단). 로컬 기본 실행에서 Mock이 꺼지는 것은 의도한 동작(필요하면 `SPRING_PROFILES_ACTIVE=local`). 테스트: 활성 조건 20, 프로필 통합 10(LocalWithStaging 추가).
- **테스트 격리**: 전체 시나리오를 지우던 `@BeforeEach clear()`를 없앴다(병렬 실행 시 다른 테스트 시나리오를 지울 수 있음). 지급 요청 ID가 테스트마다 새 UUID라 섞이지 않고, 실제 서버 컨텍스트는 클래스 끝에 닫는다(`@DirtiesContext`, 풀 5 — 전체 실행에서 DB 최대 연결 수 초과를 겪고 넣었다).
- **테스트**: `MockPayoutHttpIntegrationTest` 19(RANDOM_PORT 실제 HTTP: 시나리오 5종, 반복·충돌 5종·동시 8건, ID별 시나리오 동시 격리, 확정 뒤 시나리오 재지정 무효, 트랜잭션 안 소켓 연결 0·호출 1번 = 연결 1번, 대기 중 열린 트랜잭션 0·접수/결과 선커밋, 접근 키 단일 빈·403·응답/로그 비노출·다른 API 401, 로그 계좌 비노출, DB 기록만으로 재시작 복구, 환전 예상 수령액 18,000 송금 후 환전·지갑·보류·원장·exchange_payout 불변), `MockPayoutDisabledTest` 3, `MockPayoutDeployedProfileTest` 4(prod·dev에서 enabled=true여도 꺼짐), `MockPayoutActivationTest` 10, `MockPayoutTransferTest` 2, `BillingEnumStorageTest`·`BillingSchemaTest`(13개 표) 갱신.
- **계약(AGENTS.md 11장)**: Failed는 Release하지 않는다(재처리 가능하게 보류 유지), 확정 실패 뒤 재송금은 새 지급 시도 ID, 결과 불명확·처리 중은 같은 ID로 조회.


### 3.20 관리자 환전 승인·거절과 지갑 회원 (#294, 2026-10-16, 기준 `origin/dev` 586ec8a, `feature/tkt-294`)

> 결정: BD-102~BD-104 (8장). 상태: BD-102·BD-103 유효, BD-104 대체됨 → BD-132(#324에서 Backfill 구현, #552에서 지갑까지 생성) — [decisions.md](../../docs/billing/decisions.md)

규칙은 billing AGENTS.md 11장. 결정 배경만 둔다.

- **사전 분석**: #292가 `UserDto.version`(회원 @Version, 가입 0, 수정마다 +1)과 `ReplicaUser.memberVersion`·`syncFrom`(이하 버전 무시)을 넣었다. 다른 모듈(강의·출연·채팅)은 가입 이벤트로만 만들고 변경 이벤트는 기존 복제본만 고친다(없으면 실패 후 재시작 대기). 회원 복제본을 Backfill한 모듈은 없다(10-12 이벤트 도입 직후 리스너를 붙여 필요가 없었다).
- **사용자 결정**: Backfill 제외(후속 → #324에서 구현, BD-132), Member·Shared 변경 없음, 가입·변경 모두 Upsert, 복제본 없는 신청자는 승인 409, 거절은 신청자 상태 무관, 같은 관리자(거절은 같은 사유) 재요청만 멱등.
- **설계 선택**: 복제본 갱신은 행을 비관적 락으로 잡고 버전 비교(처음엔 낙관적 락 + 재시도 3회였으나 같은 회원 10건 동시 반영 테스트에서 재시도가 모자라 실패 → 락으로 직렬화, PK 충돌만 재시도). 버전 없는 정보는 반영하지 않는다(회원 모듈은 항상 버전을 담으므로 테스트용 정보만 해당). 관리자 처리 전 지갑 ID는 엔티티를 올리지 않는 쿼리로 읽는다(영속성 컨텍스트에 잠금 전 상태가 남지 않게).
- **구현 중 발견한 결함 1건(수정)**: 보류 정합성 검사에서 `Long` 두 값을 `==`로 비교해 128 이상 금액에서 항상 불일치 → `longValue()` 비교. 테스트로 발견.
- **ERD에 없는 것(팀 공유)**: 테이블 `billing_user`, `exchange`의 `approved_by`·`approved_at`·`rejected_by`·`rejected_at`·`rejection_reason`, 상태 값 APPROVED·REJECTED.
- **테스트**: `BillingUserSyncIntegrationTest` 8, `BillingMemberEventIsolationTest` 1, `ExchangeReviewIntegrationTest` 22, `ApiV1SettlementExchangeAdminControllerTest` 5, `ExchangeTest` +6, `BillingConstraintsTest` +8, `BillingEnumStorageTest`·`BillingSchemaTest`(14개 표) 갱신.
- **후속 (당시)**: 기존 회원 Backfill(Member 조회 계약 협의 → 계약 `docs/api/internal-members.md` #303, 회원 내부 목록 API·`MemberApiClient` #322(커밋 24d5b332), Backfill #324(커밋 5b010756, BD-132)), 송금 UseCase(→ #304), 정산일 배치(→ #305), 복제본 지연(이벤트 실패는 재시작 때만 재처리 → #430 Kafka 소비·그룹별 DLT, #564 DLT 복구 절차).


### 3.21 HP 내부 HTTP API (#273, 2026-10-29, 기준 `origin/dev` 29d1ea5, `feature/tkt-273`)

> 결정: BD-108, BD-111~BD-113 (9장) — 유효 — [decisions.md](../../docs/billing/decisions.md)

정책 원문은 `docs/billing/CONTEXT.md` 1장(2026-10-29 `/grill-with-docs`). 결정 배경만 둔다.

- **경로**: `ApiV1BillingInternalController`(Swagger `ApiV1BillingInternalSpec`)가 네 경로를 받고 `HoldFacade`를 부른다. 요청 하나 = Billing 로컬 트랜잭션 하나. 결과 변환은 `BillingResultDtos`로 옮겨 `BillingPortAdapter`와 같이 쓴다. `BillingPort`는 당시 #280까지 유지하기로 했다(#486에서 삭제, BD-128).
- **요청 본문 = shared Command**: BD-54("shared DTO를 Controller 요청 DTO로 쓰지 않는다")는 같은 JVM 전제였다. HTTP에서는 shared Command가 계약 자체이고, 구조 오류는 Jackson을 거쳐 400 `INVALID_REQUEST`다. 이슈의 "billing 내부 Command 변환"은 `HoldFacade` 시그니처·Hold 통합 테스트가 함께 바뀌므로 #280에서 하기로 했다(#494에서 했다, BD-129).
- **인가**: `HoldFacade`에 `@PreAuthorize("hasRole('INTERNAL')")`(D63). #280 뒤 최종 위치를 지금 정했다. `HoldFacade`·`BillingPort`를 직접 부르는 테스트 10개 클래스는 `@WithMockUser(roles = "INTERNAL")`, 다른 스레드에서 부르는 동시성 테스트는 `DelegatingSecurityContextExecutorService`로 인증을 넘긴다(검증 조건 불변).
- **동시 재요청**: 같은 회원·같은 키 동시 요청은 지갑 락으로 직렬화되어 replay된다(코드 변경 없음, HTTP 테스트로 고정). 409는 다른 회원이 같은 참조 대상을 보류할 때만이다. 처음 분석에서 "내용이 같아도 409"라고 잘못 판단했다가 `HoldCreateUseCase`의 지갑 락 위치를 보고 정정했다.
- **락 대기 상한(BD-108 결정, 이슈 범위 확장)**: Client 응답 timeout 5초와 무제한 락 대기가 맞지 않아 Billing 전체에 `lock_timeout = 3s`(Hikari `connection-init-sql`, `billing.yml`을 운영 설정·테스트 설정이 함께 import)를 둔다. 55P03은 Facade 진입점에서 `LockTimeouts`가 `HP_LOCK_TIMEOUT`(503)으로 바꾼다(보류·반환·지급·위약금, 충전, 환불, 환전, 관리자 환전 처리, 내 지갑 조회). 공통 `GlobalExceptionHandler`는 6개 앱이 같은 사본이라 바꾸지 않았다. shared `BillingFailureCode`에도 추가.
- **오류 코드 대조**: `BillingFailureCodeContractTest`가 `BillingErrorCode` 전체를 `default` 없는 `switch`로 `BillingFailureCode` 또는 `null`(내부 API에서 나오지 않음)로 분류한다. Billing 코드가 늘면 컴파일되지 않는다.
- **로그**: 요청마다 `operation`·회원·참조·요청 ID·금액과 `result=OK replayed=…`/`FAILED code=…`/`ERROR exception=…` 한 줄. correlation ID는 계약(`internal-billing.md` 3장)대로 tracing 도입 때 정한다.
- **테스트**: `ApiV1BillingInternalControllerTest` 13(성공·replay·409·업무 오류 detail·400 구조 오류·401 키 없음/틀림/세션만·기술 실패 500 + 전체 롤백·Facade 인가·요청 로그·동시 같은 요청·락 대기 초과 503), `BillingFailureCodeContractTest` 68, `LockTimeoutsTest` 3.
- **후속**: `docs/api/internal-billing.md` 2장 표에 `HP_LOCK_TIMEOUT` 반영, GLOSSARY("위약금 정산") 반영, #280(내부 Command 변환·`BillingPort` 삭제. `BillingPort` 삭제는 #486, 내부 Command 변환은 #494에서 했다). `decisions.md` 반영(BD-28·BD-54 일부 대체, BD-108 결정, BD-111~BD-113)은 2026-10-30에 했다.

### 3.22 환불·환전 1원 단위 (#453, 2026-10-30, 기준 `origin/dev` 3d6b61c, `feature/tkt-453`)

> 결정: BD-119~BD-122 (7·8장) — 유효 — [decisions.md](../../docs/billing/decisions.md)

정책 원문은 `docs/billing/CONTEXT.md` 4장(2026-10-30 `/grill-with-docs`)과 `docs/policy.md` 2·3장. 결정 배경만 둔다.

- **배경**: 충전은 1,000 단위지만 사용 금액은 1,000 단위가 아니라, 1,000 미만 끝자리 충전 HP·수익 HP를 현금화할 수 없었다. `policy.md` 2장 환불 규칙은 구현 전에 먼저 "0원 초과·잔액만큼 부분 환불"로 바뀌어 있었다.
- **금액**: 환불·환전 모두 1 HP 이상·1 HP 단위. 충전은 그대로 1,000 단위. 환전 최소 1,000 유지(단위만 1원)·최소 2원 안도 있었지만 잔액 전부를 현금화하도록 1 HP로 정했다.
- **계산**: 송금액을 버린다(송금 = floor(신청 × 90%), 수수료 = 나머지). 이전에는 수수료를 버렸다. 수수료가 항상 1 HP 이상이라 #336의 "수수료 0원 원장 없음" 전제가 유지된다.
- **송금액 0원(신청 1 HP)**: 허용. `ExchangePayoutProcessing.start`가 APPROVED 건이 송금액 0이면 은행을 부르지 않고 같은 트랜잭션에서 확정 차감 + 수수료 수입 + COMPLETED. 송금 시도 기록 없음. dev·prod(Mock 꺼짐)는 다른 환전처럼 건너뛴다. 그래서 `start`도 회원·플랫폼 지갑을 함께 잠근다(`lockForPayout`).
- **오류 코드**: `REFUND_AMOUNT_INVALID` → `REFUND_AMOUNT_TOO_SMALL`, `EXCHANGE_AMOUNT_INVALID` → `EXCHANGE_AMOUNT_TOO_SMALL`. HTTP에서는 0 이하를 `@Positive`가 먼저 400 `INVALID_REQUEST`로 막으므로 새 코드는 내부 호출 보호용이다. 프론트와 맞춘다.
- **DB**: Flyway `V202610301200`이 `ck_exchange_1`을 `requested_amount_hp >= 1`로 바꾼다. 기존 환전의 수수료·송금액은 바뀌지 않는다.
- **PG**: TossPayments 문서에 부분 취소 최소 금액 제한이 없다. 부분 취소가 안 되는 결제는 기존대로 거절 → 보류 유지 → 운영자 확인.
- **테스트**: `ExchangeTest`(송금액·수수료 계산표, 1 HP 단위 신청, 0원 완료 조건), `ExchangeIntegrationTest`·`RefundIntegrationTest`(1 HP 미만 거절, 1·999·1,234 환불), `ExchangePayoutIntegrationTest`(0원 환전: NOT_DUE, 은행 호출·시도 없음, 원장 4줄, 플랫폼 대사), 컨트롤러·오류 코드 대조. billing-app 1,141개 통과.

### 3.23 #453 이후 Issue (배경은 `decisions.md`·`CONTEXT.md`)

이 Issue들은 3장에 절을 따로 쓰지 않았다. 결정과 이유는 `decisions.md`의 각 행("이유" 칸), 정책 원문은 [`CONTEXT.md`](../../docs/billing/CONTEXT.md)에 있다.

| Issue | 결정 | 현재 상태 (2026-11-06) |
| --- | --- | --- |
| #336 플랫폼 지갑·수수료 수입 | BD-114~BD-118 | 유효. BD-115는 일부 대체됨 → BD-139("점검 배치는 없다" 부분) |
| #428·#430 회원 이벤트 Kafka 소비 | BD-123 | 유효 (BD-6 대체) |
| #471 Modulith 이벤트 기록 제거 | BD-124 | 대체됨 → BD-130 |
| #479 보류 무효화 | BD-125, BD-126, BD-127 | BD-125·BD-126 유효, BD-127 미결 |
| #486 `BillingPort` 삭제 | BD-128 | 유효 (BD-51 일부 대체, BD-52 폐기) |
| #494 내부 Command 변환 | BD-129 | 유효 |
| #497 지갑 이벤트 발행 | BD-130 | 유효 |
| #537 Gateway 헤더 인증·지갑 회원 확인 | BD-131 | 유효 |
| #552 첫 배포 준비 | BD-132, BD-133 | 유효 (BD-104 대체) |
| #562 나가는 호출 circuit breaker | BD-134 | 유효 |
| #564 배포 전 정리 | BD-135, BD-136 | 유효 |
| #577 보류·지급 조회 내부 API | BD-137 | 유효 |
| #598 배치 실행 락 | BD-138 | 일부 대체됨 → BD-139((3) 락 번호 규칙) |
| #607 변경된 지갑 매일 대사 | BD-139 | 유효 (BD-20 일부 대체) |
| #627 사용자 API rate limit | BD-140 | 유효 |
| #623 탈퇴용 지갑 닫기·다시 열기 | BD-141 | 일부 대체됨 → BD-145 |
| #655 JVM 분리 재검토 마무리 | BD-142, BD-143 | 유효 (BD-72·BD-107 폐기, BD-53·BD-60·BD-73 일부 대체, BD-64 대체, BD-66 폐기) |
| #674 회원 정보 파기 | BD-144, BD-145 | 유효 |
| #841 프로모션 지갑·쿠폰 예산 | BD-147~BD-156 | 유효 (#846 병합) |
| #842 주문 항목 프로모션 보류·지급·반환·조회 | BD-157~BD-161 | 유효, 구현·검증 완료 |

---

## 4. 지갑 안에서 원장 기록을 호출할 때의 계약

원장 기록(`LedgerRecordUseCase.record`)은 지갑 앱 안의 UseCase(보류·반환·지급·충전·환불·환전 등)만 부른다. 다른 앱은 내부 HTTP API로 부르고 지갑 트랜잭션에 참여하지 않는다(BD-143). 아래 4·6항의 출처인 BD-107(다른 모듈 호출자의 격리 수준·트랜잭션 처리)은 폐기됐고(→ BD-143), 지금은 지갑 안의 호출자에게만 해당한다. 3항의 출처는 BD-9(유효)다.

1. `LedgerRecordUseCase.record(LedgerOperation)`을 호출한다. `@Transactional(REQUIRED)`라 호출한 UseCase 트랜잭션에 참여하고, 실패하면 전체가 롤백된다(BD-7).
2. 작업 키는 업무 요청에서 **결정론적으로** 만든다(예: `HOLD:LECTURE_ORDER_ITEM:1001`). 재시도할 때 같은 키를 써야 중복 반영이 없다. `{키}:{줄수}`가 255자를 넘지 않아야 한다(BD-14).
3. 대상 지갑은 `walletId`로 넘긴다. 회원 ID만 있으면 `WalletRepository.findByUserId`로 찾고, 없으면 거래 실패로 처리한다(`getOrCreate` 금지, BD-9).
4. 호출한 트랜잭션은 기본 격리 수준(READ COMMITTED)을 쓴다. REPEATABLE READ 이상이면 락 시점에 직렬화 오류가 날 수 있다.
5. `record()`는 시작할 때 flush하므로 같은 트랜잭션의 다른 미반영 변경도 함께 DB에 반영된다(BD-11).
6. `IDEMPOTENCY_KEY_CONFLICT`가 UNIQUE 충돌로 난 경우 그 트랜잭션은 롤백 전용이 된다. 그 트랜잭션으로 작업을 계속하지 않는다.
7. 결과는 `LedgerOperationResult(operationKey, replayed, ledgerIds)`다.

---

## 5. 오류 코드

오류 코드 표는 이 문서에 두지 않는다. 원본은 코드다.

- 코드·HTTP 상태·메시지: [`BillingErrorCode.java`](src/main/java/com/hapbang/billing/domain/BillingErrorCode.java). 상수마다 HTTP 상태와 메시지가 있다. 추가한 Issue 번호 주석은 73개 상수 중 19개 바로 위(묶음 머리 주석 포함)에만 있고, #57~#134 때 더한 앞쪽 상수에는 없다(2026-11-06 확인).
- 코드별 발생 상황 설명: [`docs/billing/module.md`](../../docs/billing/module.md)의 "오류 코드" 절.
- 내부 API에서 소비 앱이 받는 실패 코드: `docs/api/internal-billing.md`와 shared `BillingFailureCode`. `BillingFailureCodeContractTest`가 `BillingErrorCode` 전체를 `default` 없는 `switch`로 분류해, 코드를 더하면 분류를 빠뜨릴 수 없다(BD-113).
- 응답 형식: RFC 9457 `ProblemDetail` + `code`(enum 이름), 공통 `GlobalExceptionHandler` 하나가 응답한다(BD-106, 루트 AGENTS.md 7장). `@PreAuthorize` 거절은 공통 코드 403 `FORBIDDEN`·401 `UNAUTHORIZED`다.
- 500 코드(`CHARGE_PRINCIPAL_MISMATCH`, `HOLD_STATE_INCONSISTENT` 등)는 불변식이 깨진 상황이다. 응답 `detail`은 일반 문구로 두고 원인은 `code`와 error 로그로 구분한다. 일반 예외(500 `INTERNAL_ERROR`)로 바꾸지 않은 것은 테스트·로그에서 원인을 코드로 구분하기 위해서다(2026-10-13 결정, a안, BD-105).
- 2026-11-06까지 이 장에 표가 있었지만 #188에서 지운 `SETTLEMENT_*` 3개가 남아 있고 이후 더한 코드 34개가 빠져 있었다. 표를 고쳐도 코드와 따로 갱신해야 해 다시 어긋나므로 지웠다.

---

## 6. 테스트

- 실행: `./gradlew :billing-app:test`. Docker가 켜져 있어야 한다(`common/test-support`의 PostgreSQL·Kafka Testcontainers). 단계별 실행 방법은 `test-spec.md` 1장.
- 검증 기준(`BT-*`)과 테스트 클래스 목록은 [`test-spec.md`](../../docs/billing/test-spec.md), 실제로 실행한 결과는 [`regression-log.md`](../../docs/billing/regression-log.md)에 적는다. 이 문서에는 실행 결과를 쌓지 않는다.
    - 2026-11-06까지 이 장에 Issue별 실행 결과 16회분(`regression-log.md` 5.1에서 날짜가 붙은 "기준" 항목 수, 2026-10-14 #141 ~ 2026-11-04 #674, 모듈별 건수)이 있었다. 대부분 JVM 분리(#356) 전 모놀리스 기준이라 지금 기준선으로 쓰지 않는다(`regression-log.md` 5장). 원문은 `docs/billing/regression-log.md` 5.1에 그대로 옮겼다.

### 테스트 작성 요령

- `@RepositoryTest`(JPA만, 롤백) / `@IntegrationTest`(전체 컨텍스트, 테스트마다 TRUNCATE) / `fixture.GatewayLogin.as(userId, role)`(MockMvc 로그인, `csrf()` 없음, #537). 지갑 회원이 필요한 신청 테스트는 `setUp`에서 `WalletMemberFixture`로 저장한다.
- 회원 이벤트 리스너는 #430부터 Kafka 소비다. 리스너 로직은 직접 호출(`new UserJoinedEventListener(...)`)로, Kafka 경로는 `BillingMemberEventKafkaTest`(Kafka Testcontainers)로 검증한다. 테스트 설정은 Kafka 리스너·토픽 생성을 끄고(`spring.kafka.listener.auto-startup=false`), Kafka 소비 테스트만 다시 켠다.
- 동시성 테스트는 실제 DB로 한다. 충돌 경로를 결정적으로 만들 때는 상대 트랜잭션이 INSERT 뒤 대기하게 하고 `pg_locks`의 `NOT granted`로 대기 상태를 확인한다. 조회 사이에 끼어들어야 하면 `@MockitoSpyBean`으로 n번째 조회 직후 `CountDownLatch`로 멈춘다(#141 `HoldCreateIntegrationTest#lookupRace_*`).
- 저장 실패 롤백은 테스트 안에서 행 트리거(`RAISE EXCEPTION`)를 잠시 걸어 만든다.
- PostgreSQL 드라이버는 Billing에서 `compileOnly`라 테스트 컴파일 경로에 없다. 드라이버 예외가 필요한 단위 테스트는 리플렉션으로 만든다(`WalletCreateUseCaseTest`). 서버 언어와 무관한 판별은 `RAISE ... USING ERRCODE, CONSTRAINT`로 검증한다(`WalletDuplicateViolationTest`).
- 처음부터 통과한 핵심 테스트는 보호 로직을 일부러 빼서 실패하는지 확인한다(지금까지 확인한 것: 락 제거·락 순서·refresh·flush·재반영 방어·누적 한도·대사 격리 수준, 조회 순서(#141), 원천 중복 확인(#134)).

---

## 7. 남은 일과 열린 결정

#866의 실제 billing 통합 검증은 별도 E2E 서버·전용 DB로 실행한다. 외부 PG 승인과 회원 가입은 초기 seed 범위 밖이고, 보류·반환·지급·예산·원장·대사는 실제 엔진으로 확인한다. 실행·검증 결과는 PR과 [`docs/dev/testing.md`](../../docs/dev/testing.md#강의billing-http-통합-테스트)를 따른다. 금융 정책·운영 계약 변경은 없다.

열린 결정은 [`decisions.md`](../../docs/billing/decisions.md) 10장이 원본이다. 새 미결 항목도 그곳에 적는다.

### 다음 Issue 후보

- **#842 호환 트리거 정리**: 모든 지급 writer가 재원 칼럼을 쓰는 버전으로 전환되고 이전 버전으로 rollback하지 않기로 확인한 뒤, 다음 배포의 신규 Flyway에서 `fill_settlement_buyer_funding` 트리거·함수를 제거한다. 같은 롤링 배포 안에서 제거하면 이전 pod의 INSERT를 막으므로 이번 파일에서 제거하지 않는다. `V202611101800`의 기존 지급 행 backfill은 settlement의 ACCESS EXCLUSIVE 락 아래에서 실행된다. 운영 데이터 행 수·소요 시간은 조회/측정하지 않았으며, 배포 전에 동등한 데이터 복제본으로 락 유지 시간을 확인한다.

- **#842 검증 완료**: 구매자·플랫폼 재원 전체 지급/복구, 0원 앵커, 같은 요청 replay·경합·롤백·기존 요청 호환을 검증했다. 실행 결과는 `regression-log.md`에 기록한다. 쿠폰·강의 호출 연결과 플랫폼 쿠폰 기능 개방은 후속 #843 범위다.

- **#537 후속**: policy.md 2장 "회원 상태와 충전·환불·환전"은 팀 확인을 받는다.
- **#552 후속**: Prometheus 지표·관리 포트로 메모리가 늘 수 있어 RSS를 확인한다(limit는 #608에서 512Mi로 올렸다. 그 뒤 RSS 확인 결과는 이 문서에 미기록). notification·search 앱을 더하기 전에 앱별 pool 또는 `max_connections`를 다시 정한다(`database.md` 5항).
- **#224 후속**: 실제 TossPayments 테스트 키로 prod(유일한 서버)에서 결제창 → 승인 수동 확인, 프론트 결제 계약 확정(결제창 방식·successUrl·failUrl), PENDING 주문 만료 정책, 최대 충전 금액, 가상계좌·웹훅(필요 시), PROCESSING 장기 미확정 건 운영자 조회·알림(관리자 Issue), ERD에 `pg_payment_key_hash`·`failure_reason` 반영.
- **팀 확인 (`decisions.md` 10장)**: 레거시 SCHEDULED 지급 건(BD-71), 강의 지급 배치의 재시도 불가 오류 격리(BD-74 미결 부분. 판매자 탈퇴 부분은 policy.md 6장으로 해소), 반환·지급 사유를 원장 `description`에 남길지(BD-109), 참조 대상의 보류 상태 조회 API(BD-127).
- **코드 주석 불일치 (해소, 2026-11-06 주석만 수정)**: `apps/billing-app/src/main/java/com/hapbang/billing/app/WalletWithdrawalUseCase.java` 30줄 주석은 "회원 락(`WalletUserLock`) → 지갑 락 → 기록 확인" 순서로 적혀 있으나, 실제 `close` 코드는 회원 락(54줄) → 탈퇴 기록 확인(55줄) → 지갑 락(71줄) 순서다. 문서 정리(2026-11-06)에서는 코드를 고치지 않았다. 2026-11-06에 주석을 실제 흐름에 맞게 고쳤다(로직 변경 없음).
- **코드 주석 불일치 (해소, 2026-11-06 주석만 수정)**: `apps/billing-app/src/main/java/com/hapbang/billing/app/BillingEventRepublishUseCase.java` 16줄 주석은 "replica 여럿이 같은 기록을 다시 보내도 … 분산 락은 두지 않는다"인데, 실제로는 #598부터 `BillingEventRepublishBatch.java` 26줄이 실행 락 `BillingJob.EVENT_REPUBLISH`(598005, BD-138)로 감싼다. 문서 정리(2026-11-06)에서는 코드를 고치지 않았다. 2026-11-06에 주석을 실제 흐름에 맞게 고쳤다(로직 변경 없음).
- **공통 테스트 기반 (`common/test-support`, 별도 Issue)**: Testcontainers가 공용 PostgreSQL 하나에 스키마만 나눠 쓴다. 인프라 문서 Phase 2-5(앱별 database·계정 구조, database 간 접근 차단 테스트)가 없고, 세션 시절 설정 `spring.session.jdbc.initialize-schema`가 남아 있다. 모든 앱에 영향을 주므로 지갑 Issue에서 바꾸지 않는다.

### 구현 메모 (참고)

- 반환은 충전 원금을 역순으로 복원하므로, 환불(FIFO)이 어느 충전 건(PG 결제)을 취소할지가 반환 이력에 따라 달라진다. 환불 가능 총액은 같다.
- 보류에 배분된 원금이 남은 충전 건은 `REFUNDED`로 바꾸면 안 된다(반환 때 원금을 되돌릴 곳이 없어 `CHARGE_PRINCIPAL_MISMATCH`). 일부 환불이면 `PARTIALLY_REFUNDED`로 둔다.
- 환전 금액·수수료·정산일 규칙은 policy.md 2·3장과 BD-119~BD-122, BD-96(정산일)을 본다.

---

## 8. 작업 방식 메모

- 이슈·브랜치·PR·병합·배포 확인은 [`docs/contributing/git-workflow.md`](../../docs/contributing/git-workflow.md)를 따른다. `origin/dev`에서 `feature/tkt-{Issue번호}`를 만들고 PR base는 반드시 `dev`다(#39·#42 때 PR이 main으로 열린 적이 있다). main에는 서버가 없고 리뷰 제출용으로만 병합한다.
- 커밋 메시지는 [`docs/contributing/commit.md`](../../docs/contributing/commit.md)를 따른다(`Feat: …`처럼 첫 글자가 대문자인 유형 + 한글 제목). #39~#324 때 쓴 `feat(billing): #N …` 형식은 쓰지 않는다.
- PR을 올리기 전에 테스트를 통과시킨다. dev에 병합하면 바뀐 앱이 prod k3s(유일한 서버 환경)에 자동 배포된다(루트 AGENTS 8장).
- 범위 밖 변경 금지: 기존 Flyway 파일, `common/shared`·다른 앱, Gradle 설정은 승인 없이 바꾸지 않는다. 새 테이블은 근거와 대안을 먼저 보고한다.
- 개인정보·DB 접속 정보·키는 커밋하지 않는다. 루트 `.env`는 `.gitignore` 대상이고, prod 비밀 값은 SealedSecret `billing-app-secrets`다.
