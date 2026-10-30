# 지갑(Billing) 모듈 — 현재 구현

> 문서 지도: **[현재 구현](module.md)** · [결정 기록](decisions.md) · [테스트 검증 기준](test-spec.md) · [회귀 실행 기록](regression-log.md)
>
> - 기준: 2026-10-29 작업 트리(`dev` da834b1 + 커밋하지 않은 `apps/billing-app/AGENTS.md`·`PROGRESS.md` 변경). 문서와 코드가 다르면 **코드가 맞다**. 확인 후 이 문서를 고친다.
> - 목표 구조(To-Be)의 원본은 [`docs/architecture/final-project-infrastructure.md`](../architecture/final-project-infrastructure.md)(#369 최종 인프라 결정)다. 이 문서는 그중 Billing에 닿는 부분만 가리킨다. 갱신 이력은 11장에 있다.
> - 이 문서는 "지금 코드가 어떻게 동작하는가"만 적는다. 지켜야 할 규칙은 `apps/billing-app/AGENTS.md`, 진행 상황은 `apps/billing-app/PROGRESS.md`, 결정 이유는 [decisions.md](decisions.md)(`BD-*`)에 있다.
> - 돈·HP 정책 수치는 [`docs/policy.md`](../policy.md), 용어는 [`docs/GLOSSARY.md`](../GLOSSARY.md)가 원본이다. 여기서는 장 번호로만 가리킨다.
> - 표시: **As-Is** = 지금 코드, **To-Be** = 목표(아직 구현되지 않음), **미확인** = 코드로 확인하지 못함, **TBD** = 팀 결정 전.

## 1. 범위

### 1.1 하는 일

| 영역 | 기능 | 진입점 |
| --- | --- | --- |
| 지갑 | 회원 지갑 생성·조회, 내 HP 거래 내역 | `WalletFacade`, `UserJoinedEventListener` |
| 원장 | HP 원장 기록, 잔액 버킷 이동, 역분개 검증, 잔액 대사(라이브러리만) | `LedgerRecordUseCase`, `WalletReconciliationUseCase` |
| 보류 | 보류·반환·지급·위약금(강의·출연용), 종류별 보류·확정 차감(Billing 내부용) | `HoldFacade`(← `BillingPortAdapter`), `InternalHoldFacade` |
| 충전 | TossPayments 테스트 결제 승인, 결과 불명확 복구 | `ChargeFacade`, `ChargeRecoveryBatch` |
| 환불 | 충전 HP 환불(PG 부분 취소, FIFO), 결과 불명확 복구 | `RefundFacade`, `RefundRecoveryBatch` |
| 환전 | 신청·취소, 관리자 승인·거절, 가상 송금(Mock 은행), 정산 배치, 송금 복구·재시도·최종 실패 | `ExchangeFacade`, `ExchangeAdminFacade`, `ExchangeSettlementBatch`, `ExchangePayoutRecoveryBatch` |
| 수익 조회 | 강의·출연의 받을 지급(수익)을 합쳐 요약·목록 제공 | `EarningFacade` |
| 회원 복제본 | `billing_user` 동기화(이벤트), 기존 회원 Backfill(관리자) | `BillingMemberEventListener`, `BillingUserBackfillFacade` |

### 1.2 하지 않는 일

- 출연·강의 거래 상태 판단(구매 확정, 이의 제기, 지급 시점, 위약금 비율 계산). 지급 원천 모듈이 판단하고 Billing은 HP 이동만 한다 (`BD-56`, `BD-67`).
- 수익 금액·상태 계산. 강의·출연이 내부 API로 주고 Billing은 합치기만 한다 ([`docs/api/internal-earnings.md`](../api/internal-earnings.md)).
- 실제 은행 송금, 계좌 실명 확인 (policy.md 2장, `BD-94`).
- 다른 모듈 테이블 조회·변경.

## 2. 패키지와 계층

```text
apps/billing-app/src/main/java/com/hapbang/billing/
├── BillingApplication.java   scanBasePackages = {com.hapbang.billing, com.hapbang.shared}
├── package-info.java         @ApplicationModule(displayName = "Billing")
├── domain/   엔티티·Enum·BillingErrorCode·BillingException
├── out/      Repository(삭제 메서드 없음), 외부 Client(TossPayments, Mock 송금), 결제키 암호화
├── app/      UseCase·Facade·배치·요청/결과 record
├── in/       Controller·Spec·응답 DTO·이벤트 리스너·BillingPortAdapter·Mock 은행
└── global/   앱 내부 공통(예외 핸들러, 보안, 내부 API 키 필터, RsData, 페이지·스케줄링 설정)
```

| 계층 | 코드에서 보이는 의존 방향 (main) | 강제 수단 |
| --- | --- | --- |
| `domain` | → `global`(예외 기반 타입) | 없음 |
| `out` | → `domain` | 없음 |
| `app` | → `domain`, `out`, `global` | 없음 |
| `in` | → `app`, `domain`, `global`, `out`(Mock 은행 조건·접근 키 4건) | 없음 |
| `global` | → `global` | 없음 |

- 아키텍처 테스트는 `WalletApplyLedgerAccessTest` 하나다(`Wallet.applyLedger` 호출을 `LedgerRecordUseCase`로 제한). 계층 방향 ArchUnit·Modulith `verify` 테스트는 없다.
- 예전 `global` Gradle 모듈은 없어졌다. 예외 처리·보안은 `com.hapbang.billing.global`에 있다 ([`docs/architecture/app-modules.md`](../architecture/app-modules.md)).
- Gradle 프로젝트는 `:billing-app`(경로 `apps/billing-app`), 의존은 `:shared`(`common/shared`)뿐이다. 앱 사이 Gradle 의존은 `verifyModuleStructure`가 막는다.

## 3. 도메인 모델과 상태 전이

### 3.1 애그리거트

| 애그리거트 | 테이블 | 안에서만 쓰는 연관관계 |
| --- | --- | --- |
| 지갑 | `wallet` (회원 지갑 `MEMBER`, 플랫폼 지갑 `PLATFORM` 1개 — #336) | — |
| 원장 | `point_ledger` (append-only) | — |
| 보류 | `point_hold` → `point_hold_detail`, `point_charge_usage` | `@ManyToOne` |
| 충전 | `point_charge` | — |
| 환불 요청 | `point_refund_request` → `point_refund` | `@ManyToOne` |
| 지급 건 | `settlement` | — |
| 환전 | `exchange` → `exchange_account`, `exchange_payout` | `@OneToOne`·`@ManyToOne` |
| Billing 회원 복제본 | `billing_user` (`ReplicaUser` 상속) | — |
| Mock 은행 기록 | `mock_payout_transfer` (local·test 전용 기능) | — |

애그리거트 사이는 `Long` ID 필드로 참조한다 (`BD-36`).

### 3.2 상태 전이 (엔티티 메서드 기준)

| Enum | 전이 | 만드는 코드가 없는 상수 |
| --- | --- | --- |
| `PointHoldStatus` | `held` → HELD · 일부 처리 → PARTIALLY_PROCESSED · 남은 보류 0: 반환만 RELEASED / 지급만 PAID / 확정 차감만 CAPTURED / 섞이면 MIXED. HELD·PARTIALLY_PROCESSED만 열린 상태(닫힌 보류 처리 = `HOLD_ALREADY_CLOSED`) | — |
| `PointChargeUsageStatus` | HELD → PARTIALLY_PROCESSED → RELEASED / CONSUMED / MIXED | — |
| `PointChargeStatus` | `requested` PENDING → `startConfirm` PROCESSING → `complete` COMPLETED / `fail` FAILED · `refund` COMPLETED·PARTIALLY_REFUNDED → PARTIALLY_REFUNDED / REFUNDED | — |
| `PointRefundRequestStatus` | REQUESTED → PROCESSING → COMPLETED / PARTIALLY_COMPLETED / FAILED / MANUAL_REVIEW_REQUIRED · REQUESTED → CANCELED | — |
| `PointRefundStatus` | PENDING → PROCESSING → SUCCEEDED / FAILED / UNKNOWN / MANUAL_REVIEW_REQUIRED · UNKNOWN → SUCCEEDED / FAILED · PENDING(요청 0회) → CANCELED | — |
| `SettlementStatus` | `Settlement.completed` → COMPLETED만 | SCHEDULED, SUSPENDED, PROCESSING, FAILED, CANCELLED (#142·#182 레거시 호환용, `BD-62`·`BD-71`) |
| `ExchangeStatus` | REQUESTED → APPROVED / REJECTED / CANCELED · APPROVED·FAILED → PROCESSING → COMPLETED / FAILED / UNKNOWN · UNKNOWN → 결과 반영 · FAILED → FINAL_FAILED | — |
| `ExchangePayoutStatus` | `requested` → PROCESSING(PENDING을 거치지 않음) → COMPLETED / FAILED / UNKNOWN / MANUAL_REVIEW_REQUIRED | PENDING |
| `MockPayoutTransferStatus` | PROCESSING → SUCCEEDED / FAILED | — |
| `AccountVerificationStatus` | `ExchangeAccount.unverified` → PENDING만 | VERIFIED, FAILED |
| `LedgerTransactionType` | CHARGE, HOLD, RELEASE, PAYOUT, REFUND, EXCHANGE 기록 | REVERSAL(검증 코드만 있고 main 호출부 없음, 테스트에서만 사용) |

그 밖의 Enum: `PointHoldType`(MIXED·CHARGE_ONLY·REVENUE_ONLY), `PayoutType`(NORMAL·PENALTY), `HoldDetailActionType`(RELEASE·PAYOUT·CAPTURE), `PointType`(CHARGE·REVENUE), `BalanceBucket`(AVAILABLE·HELD). 상수 이름이 DB 저장값이라 바꾸지 않는다(`BillingEnumStorageTest`).

## 4. 잔액·원장 불변식

정책 원본: policy.md "팀 규칙 > 원장과 민감 정보", 1·4장.

| ID | 불변식 | 지키는 곳 |
| --- | --- | --- |
| INV-1 | 지갑 잔액 4개(충전·수익 × 가용·보류)와 플랫폼 지갑의 수수료 수입(FEE 가용)은 원장 합계와 같다 | `LedgerRecordUseCase`가 유일한 변경 경로, `WalletReconciliationUseCase`로 대조 |
| INV-2 | 원장은 수정·삭제되지 않는다 | DB 트리거 `trg_point_ledger_append_only`(UPDATE·DELETE → 23514), `@PreRemove` |
| INV-3 | 잔액은 음수가 되지 않는다, 원장 금액은 0이 아니다 | `INSUFFICIENT_BALANCE`, `INVALID_POINT_AMOUNT`, `Math.addExact`(`BALANCE_OVERFLOW`) |
| INV-4 | 완료 충전 건의 가용 원금 합 = `wallet.charge_balance_hp` | 보류 전 대조(`CHARGE_PRINCIPAL_MISMATCH`) |
| INV-5 | 보류의 반환·지급·확정 차감 상세를 처리 순서로 재생하면 충전 배분 누적값과 같다 | `ChargeUsageHistory`(`HOLD_STATE_INCONSISTENT`) |
| INV-6 | 지급 원천 하나는 한 번만 정산된다 | `uk_settlement_source_type_source_id`, `PAYOUT_ALREADY_SETTLED` |
| INV-7 | `refunded_amount_hp + remaining_principal_hp ≤ amount_hp` | `ck_point_charge_4` |
| INV-8 | 한 보류는 반환·지급·확정 차감 중 허용된 조합으로만 끝나고, 보류 종류별 구성(CHARGE_ONLY는 수익 0, REVENUE_ONLY는 충전 0)을 지킨다 | `ck_point_hold_5`, `HOLD_TYPE_NOT_SUPPORTED` |

환불·환전의 추가 불변식(요청액 = 개별 환불 합 등)은 `apps/billing-app/AGENTS.md` 10.2·11장에 있다.

## 5. 트랜잭션·동시성·멱등성

### 5.1 트랜잭션 경계

| 흐름 | 경계 (As-Is) |
| --- | --- |
| 보류·반환·지급·위약금 (`HoldFacade`) | UseCase `@Transactional(REQUIRED)`. 같은 JVM 호출자의 트랜잭션에 참여하도록 설계됐다(`BD-52`·`BD-53`). **독립 JVM에서는 이 참여가 성립하지 않는다**(7장) |
| 원장 기록 | `LedgerRecordUseCase.record` `REQUIRED`, 락 헬퍼는 `MANDATORY` |
| 지갑 생성 | `REQUIRES_NEW`(UNIQUE 위반 후 바깥에서 재조회, `BD-4`) |
| 충전 | claim 커밋 → PG 승인(트랜잭션 밖) → complete/fail 커밋 (`BD-76`) |
| 환불 | 접수 한 트랜잭션 → 개별 환불마다 PROCESSING 커밋 → PG 취소(밖) → 결과 반영 |
| 환전 송금 | 시도 기록 커밋 → Mock 송금(밖, 트랜잭션 안에서 부르면 `IllegalStateException`) → 결과 반영 커밋 |
| 수익 조회 | 트랜잭션 없음(내부 HTTP 호출) |
| 회원 복제본 반영 | 리스너는 `NOT_SUPPORTED`, 반영은 `REQUIRES_NEW` + 최대 3회 재시도 |

### 5.2 락 순서

| 흐름 | 순서 |
| --- | --- |
| 공통 | 지갑(ID 오름차순, `PESSIMISTIC_WRITE`, 락 전 flush·락 후 refresh) → 보류 → 충전 건(ID 오름차순) |
| 환불 | 지갑 → 환불 요청 → 보류 → 충전 건 |
| 환전·송금 | 지갑 → 환전(`findByIdForUpdate` + refresh) → 보류 → 환전 송금 |
| 충전 claim | 충전 건만(`FOR NO KEY UPDATE`), 지갑 없음 |
| 모듈 간 (단일 DB 트랜잭션 시절) | `lecture_order → inquiry → order_item → wallet → point_hold → point_charge` (`BD-72`, 분리 후 재검토 대상) |

`lock_timeout`은 설정하지 않았다(무제한 대기, `BD-108` TBD).

### 5.3 멱등키

| 작업 | 키 (Billing이 만든다, `BillingKeys`) | 최종 보장 |
| --- | --- | --- |
| 원장 줄 | `{operationKey}:{줄번호}` | `uk_point_ledger_idempotency_key` |
| 보류 | `HOLD:{referenceType}:{referenceId}` (참조 대상당 1건) | `uk_point_hold_idempotency_key` |
| 반환 | `RELEASE:{holdId}:{releaseRequestId}` | 보류 상세 UNIQUE |
| 지급 | `PAYOUT:{holdId}:{payoutRequestId}` | 보류 상세 UNIQUE + 원천 UNIQUE |
| 위약금 | `PENALTY:{holdId}:{cancellationId}` (`…:RELEASE`, `…:PAYOUT`) | 위와 같음 |
| 확정 차감 | `CAPTURE:{holdId}:{captureRequestId}` | 보류 상세 UNIQUE |
| 충전 | 주문 ID, 결제키 HMAC, 원장 `CHARGE:{chargeId}`, PG `hapbang-charge-confirm-{orderId}` | UNIQUE 3개 |
| 환불 | 회원 + `Idempotency-Key` 헤더, PG `hapbang-refund-cancel-{UUID}`(저장값) | UNIQUE |
| 환전 신청 | 회원 + `Idempotency-Key` 헤더 | UNIQUE |
| 환전 반환·확정 차감 요청 ID | `EXCHANGE-CANCEL-{id}`, `EXCHANGE-REJECT-{id}`, `EXCHANGE-PAYOUT-{id}`, `EXCHANGE-PAYOUT-FAIL-{id}` | 보류 상세 UNIQUE |
| 환전 수수료 수입 | 원장 작업 키 `EXCHANGE_FEE:{exchangeId}` (환전당 1건, #336) | `uk_point_ledger_idempotency_key` |
| 환전 송금 시도 | 시도마다 새 UUID(`payout_request_id`), 결과 불명확 시 같은 ID로만 조회·재전송 | `uk_exchange_payout_active`(FAILED 아닌 시도 1건) |

같은 키에 다른 내용 = `IDEMPOTENCY_KEY_CONFLICT`. 같은 키·같은 내용 = 처음 결과(`replayed=true`).

## 6. 기능별 처리 흐름 (요약)

상세 규칙은 `apps/billing-app/AGENTS.md`의 해당 장이 원본이다. 여기서는 흐름과 위치만 적는다.

| 기능 | 흐름 | 상세 원본 |
| --- | --- | --- |
| 보류 | 지갑 락 → 원장 조회 → 보류 조회(재요청 판정) → 수익 HP 우선·충전 HP FIFO 배분 → 원장·보류·배분 저장 | AGENTS 8장, `BD-21`~`BD-28` |
| 반환 | 충전 HP 먼저, 원금 LIFO 복원 | AGENTS 8장, `BD-29`~`BD-35` |
| 지급 | 수익 HP 보류 먼저, 충전 원금 FIFO 소진, 받는 쪽 수익 HP 가용, `settlement` COMPLETED INSERT | AGENTS 8·8.1장, `BD-39`~`BD-45` |
| 위약금 | 출연이 정한 반환액 + 지급액을 한 트랜잭션 처리 | AGENTS 8장, `BD-56`~`BD-59` |
| 충전 | 주문 생성 → 결제창 → 승인(claim → PG → complete) → 복구 배치 | AGENTS 10.1장 |
| 환불 | 접수(CHARGE_ONLY 보류) → 개별 환불 FIFO 순차 PG 취소 → 성공 확정 차감 / 실패 반환 / 불명확 보류 유지 → 복구 배치 | AGENTS 10.2장 |
| 환전 신청·심사 | 신청(REVENUE_ONLY 보류 + 계좌 스냅샷) → 취소(REQUESTED) 또는 관리자 승인·거절(거절 = 반환) | AGENTS 11장 |
| 환전 정산·송금 (#304·#305) | 정산 배치가 APPROVED + 예정 시각 지난 건을 100건씩(실행당 최대 1,000) `execute` → 시도 기록 커밋 → Mock 송금 → 성공 확정 차감 + 수수료 수입. 송금액 0원(신청 1 HP)은 시도·Mock 송금 없이 바로 확정 차감 + 수수료 수입 + COMPLETED(#453) / 실패 FAILED(보류 유지) / 불명확 UNKNOWN. 복구 배치가 PROCESSING·UNKNOWN을 같은 ID로 조회, 24시간(`auto-check-limit`) 지나면 MANUAL_REVIEW_REQUIRED. 관리자 재확인·재시도(새 UUID)·최종 실패(FINAL_FAILED + 반환) | AGENTS 11장(송금 계약), 이 표 |
| 내 거래 내역 (#295) | 원장을 작업 키 단위로 합쳐 14개 유형(`WalletTransactionType`)으로 보여 준다. 지갑이 없으면 빈 페이지 | — |
| 수익 조회 (#295) | 강의·출연 내부 API를 매 요청 호출해 메모리에서 합친다. 저장하지 않는다. 한쪽 실패 = 전체 503, 깊이 1,000 초과 = 400 | [`internal-earnings.md`](../api/internal-earnings.md) |
| 회원 Backfill (#324) | 관리자 호출 → `MemberApiClient` 키셋 페이지 → 회원마다 복제본 Upsert. 한 번 최대 5,000명, 동시 실행 금지(인스턴스 내 `AtomicBoolean`) | — |

### 6.1 배치

모두 `hapbang.scheduling.enabled=true`(기본 켬)일 때만 등록되고 zone은 `Asia/Seoul`이다.

| 배치 | cron 속성 (기본값) | 추가 조건 |
| --- | --- | --- |
| `ChargeRecoveryBatch` | `hapbang.billing.charge.recovery-cron` (`0 */5 * * * *`) | — |
| `RefundRecoveryBatch` | `hapbang.billing.refund.recovery-cron` (`30 */5 * * * *`) | — |
| `ExchangePayoutRecoveryBatch` | `hapbang.billing.exchange.payout.recovery-cron` (`15 */5 * * * *`) | `…payout.recovery-enabled` (없으면 켬) |
| `ExchangeSettlementBatch` | `hapbang.billing.exchange.settlement.cron` (`0 5 10-23 * * *`) | `…settlement.enabled` (없으면 켬). 송금 Client를 못 쓰면 실행을 건너뜀 |

여러 인스턴스로 실행할 때의 중복 실행 방지(분산 락)는 없다. 현재 배포 단위가 1개라는 전제다.

- To-Be(k3s): 앱마다 replica 1개다. billing-app은 t3.large(`core-data`)에 두고, `maxSurge: 1`, `maxUnavailable: 0`으로 배포한다(final-project-infrastructure.md 3장).
- 추정: rolling update 중에는 새 pod와 기존 pod가 잠깐 함께 뜬다. 이 사이에 두 pod의 배치가 같은 cron 시각에 겹칠 수 있다. 이때 중복 처리를 막는 것은 각 UseCase의 락·멱등성뿐이다(L4).
- Graceful shutdown 때 배치·HTTP를 어떤 순서로 멈출지는 같은 문서 4장(`DrainCoordinator`, `/api/v1/internal/operations/drain`)이 목표이며, billing-app에는 아직 없다.

## 7. 외부 의존과 JVM 분리 이후 통신 상태

### 7.1 As-Is (코드 기준)

| 방향 | 방식 | 상태 | 관련 |
| --- | --- | --- | --- |
| 강의 → Billing (보류·반환·지급) | shared `BillingPort`를 같은 JVM에서 Java 호출. 구현 `BillingPortAdapter`는 billing-app에만 있다 | **깨짐.** lecture-app의 `OrderCreateUseCase`·`OrderCancelUseCase`·`OrderObjectionResolveUseCase`·`OrderPayoutUseCase`가 `BillingPort`를 주입받지만 빈이 없어 기동 실패 | #280, 설계 #271 |
| 출연 → Billing (보류·반환·지급·위약금) | appearance-app `BillingPortClient`가 `BillingPort` 주입 | **깨짐.** 빈 없음으로 기동 실패. `FakeBillingClient`·`appearance.billing.mode`는 #289에서 삭제됨(AGENTS 9장 설명과 다름) | #280 |
| Billing이 내부 API 제공 | `InternalApiSecurityConfig`(`/api/v1/internal/**`, `X-Internal-Api-Key`)는 있다 | **매핑된 내부 컨트롤러 없음.** 보류·반환·지급 HTTP API가 아직 없다 | #280 |
| 회원 → Billing (지갑 생성, 복제본) | Kafka `member.events.v1`을 소비 그룹 둘로 받는다(#430): `billing-app.member-replica`(`BillingMemberEventListener`, 가입·변경 → 복제본 Upsert), `billing-app.wallet-creation`(`UserJoinedEventListener`, 가입 → 지갑, ADMIN 제외). 실패는 블로킹 재시도 3회 뒤 그룹별 DLT(`BillingKafkaConfig`) | 발행 쪽(#416)이 병합돼야 실제 이벤트가 온다. 로컬·테스트 설정은 리스너·토픽 생성을 끈다(Kafka 소비 테스트만 켬). 앱 시작 전 가입자는 지갑 조회 보완·Backfill로 채운다 | #430, BD-123 |
| Billing → 다른 앱 (이벤트 발행) | 없음. billing-app main 코드에 `ApplicationEventPublisher`·`publishEvent` 호출이 없다 | 해당 없음 | To-Be 7.2 |
| Billing → 강의·출연 (수익 조회) | shared `LectureApiClient`·`AppearanceApiClient` 내부 HTTP | 분리 구조에 맞다. base-url 환경 변수 필요 | #295, internal-earnings.md |
| Billing → 회원 (Backfill) | shared `MemberApiClient` 내부 HTTP | 분리 구조에 맞다 | #322, #324 |
| Billing → TossPayments | HTTP (`TossPaymentsClient`) | 영향 없음 | — |
| Billing → Mock 은행 | 같은 billing-app에 실제 HTTP (`localhost:{port}`) | 영향 없음(같은 앱). local·test 프로필에서만 켜짐 | #288 |
| 인증 | billing `SecurityConfig`: CSRF SPA 쿠키, `/api/v1/*/admin/**` = ADMIN, 나머지 인증 필요. oauth2Login·spring-session 의존 없음. `@LoginUserId` = `Long.valueOf(authentication.getName())` | **미확인:** 다른 앱(member-app)이 만든 로그인 세션을 billing-app이 인식할 방법이 코드에 없다(추정: 지금은 사용자 API 인증 불가). 목표는 JWT + `X-User-*` 헤더다(7.2) | #370~#372 (JWT 전환) |
| 외부 노출 차단 | Envoy Gateway에 `/api/v1/internal/**` route가 없어 404다 | billing은 내부 API를 노출하지 않으므로 지금은 해당 경로가 없다 | 루트 AGENTS 7장 |

`SecurityConfig`에 `GET /api/v1/lectures`가 공개 경로로 남아 있다(billing이 제공하지 않는 경로, 모놀리스 시절 흔적으로 추정).

### 7.2 To-Be (목표, 구현되지 않음)

출처는 `docs/architecture/final-project-infrastructure.md`(아래 "인프라 문서")와 루트 `AGENTS.md` 2·7장이다. Billing 쪽 구현은 아직 없다.

| 영역 | 목표 (정해진 것) | Billing에서 검토할 것 (TBD) |
| --- | --- | --- |
| 보류·반환·지급 | Billing이 `/api/v1/internal/billing/...` 내부 HTTP API 제공, 소비 앱은 shared Client 사용 (AGENTS 3장 경계 규칙, D62·D63). 앱 간 HTTP 호출의 timeout·circuit breaker는 각 앱의 **Resilience4j**로 통일 (인프라 문서 3장, #280). database 분리 전에 보상 처리를 끝낸다: 보류 실패 → 주문 취소, 주문 실패 → 보류 반환 (인프라 문서 10장) | 호출자 트랜잭션 참여(`BD-53`)·한 트랜잭션 원자성(`BD-64`)을 대신할 방식(호출자 상태 + 멱등 재시도, 결과 불명확 처리), 오류 응답 → 호출자 상태 매핑, circuit open일 때 호출자 처리, 모듈 간 락 순서(`BD-72`) 폐기 여부 |
| 내부 API 노출 | k3s에서는 `/api/v1/internal/**`에 Envoy `HTTPRoute`를 만들지 않고, NetworkPolicy로 내부 API는 앱끼리만 닿게 한다. 인증은 `X-Internal-Api-Key` 유지 (인프라 문서 3장) | 없음 (현재 키 필터 그대로) |
| 회원 이벤트 수신 | Kafka(#339, [`kafka.md`](../architecture/infrastructure/kafka.md)). member-app이 Modulith publication registry를 outbox로 쓰는 externalization으로 `member.events.v1`(key 회원 ID)에 발행한다. `eventId`는 없다([ADR 0001](../adr/0001-context-topics-with-mixed-event-types.md)·[0002](../adr/0002-modulith-externalization-without-event-id.md)). Billing은 목적별 소비 그룹 둘로 받는다: `billing-app.member-replica`(가입·변경 → 회원 복제본 Upsert), `billing-app.wallet-creation`(가입 → 지갑 생성). 멱등은 업무 자연 키다: 복제본은 `(user.id, user.version)` 버전 비교(`BD-103`), 지갑은 회원 ID UNIQUE(`uk_wallet_user_id`, `BD-1`)로 이미 있으면 그대로 둔다. 별도 inbox·처리 기록 테이블은 두지 않는다(`BD-123`). 블로킹 재시도 3회(1초 → 최대 10초) 뒤 그룹별 DLT `member.events.v1.billing-app.member-replica.DLT`·`member.events.v1.billing-app.wallet-creation.DLT`(billing-app이 `NewTopic`으로 선언, partition 3, 보존 7일) | 소비는 #430에서 구현(7.1). 남은 것: 미완료 `event_publication` 기록 정리·Modulith 이벤트 설정 정리(Spring Event 제거), #416 실제 발행과 이어 보는 계약 검증 |
| Billing 이벤트 발행 | 인프라 문서 1장의 시연 흐름: 지갑 이벤트 → notification-app 알림·감사 projection. HP 원장을 Kafka consumer가 확정하지 않는다 | 어떤 사실을 발행할지(충전 완료, 지급, 환전 결과 등)는 미정. 현재 발행 코드 없음 |
| 인증 | member-app이 JWT(RS256·JWKS) 발급 → Envoy가 서명 검증 후 `X-User-Id`·`X-User-Role` 헤더로 전달(외부에서 붙인 `X-User-*`는 제거) → 앱 공용 헤더 인증 필터가 인증 객체 생성 → `@PreAuthorize` 인가 (#370~#372). Bearer 헤더라 API의 `X-XSRF-TOKEN` CSRF 보호는 뺀다. NetworkPolicy로 앱 외부 API 포트는 Envoy만 접근 | `@LoginUserId`·`SecurityConfig`(CSRF SPA 설정) 전환. **HP가 움직이는 경로(보류·반환·지급·환전)는 토큰의 `role`만 믿지 않고 복제한 회원 상태를 함께 확인한다**(인프라 문서 3장, #371) → 현재 Billing은 환전 승인에서만 `billing_user`를 본다. 어느 경로에 확인을 더할지 TBD |
| rate limit | 각 앱의 Resilience4j RateLimiter, key는 `X-User-Id` 또는 client IP (인프라 문서 3장) | Billing 경로별 한도 (TBD) |
| Database | 모듈별 database 분리 (8.3) | 8.3 |
| 배포 | k3s, 앱마다 replica 1개, billing-app은 t3.large (6.1) | 배치 중복 실행 (L4) |

## 8. API와 DB 요약

### 8.1 API

상세 명세는 Swagger(각 `ApiV1*Spec`)가 원본이다. 성공은 `RsData`, 오류는 `ProblemDetail` + `code`([`docs/api/conventions.md`](../api/conventions.md)).

| 경로 | 권한 | 비고 |
| --- | --- | --- |
| `GET /api/v1/wallets/me`, `GET /api/v1/wallets/me/transactions` | U·I·VI | |
| `POST /api/v1/wallets/charges`, `POST …/charges/confirm`, `GET …/charges/{orderId}` | U·I·VI | 생성 201, 승인 미확정 202 |
| `POST /api/v1/wallets/refunds`, `GET …/refunds/{id}`, `POST …/refunds/{id}/cancel` | U·I·VI | `Idempotency-Key` 필수, 미완료 202 |
| `POST·GET /api/v1/settlements/exchanges`, `GET …/{id}`, `POST …/{id}/cancel` | I·VI | 신청 `Idempotency-Key` 필수, 201 |
| `GET /api/v1/settlements/earnings`, `GET …/earnings/summary` | U·I·VI | |
| `/api/v1/settlements/admin/exchanges/**` (목록·상세·승인·거절·송금 목록·재확인·재시도·최종 실패) | ADMIN | |
| `POST /api/v1/settlements/admin/billing-users/backfill` | ADMIN | |
| `GET /api/v1/settlements/admin/platform-wallet`, `GET …/platform-wallet/fee-incomes` | ADMIN | 수수료 수입 잔액·내역 (#336) |
| `POST·GET /api/v1/wallets/mock-payouts/**` | `X-Mock-Payout-Key` | Swagger 숨김, local·test 전용 |

U = USER, I = INFLUENCER, VI = VERIFIED_INFLUENCER. 관리자 API는 URL 규칙 + Facade `@PreAuthorize` 두 겹이다. 내부 API(`/api/v1/internal/billing/**`)는 없다.

### 8.2 DB (As-Is)

- PostgreSQL **database 1개**, 앱마다 **schema** 분리. Billing은 `billing` 스키마(`spring.flyway.default-schema`, `spring.modulith.events.jdbc.schema`).
- 테이블 14개. DDL·제약은 Flyway(`apps/billing-app/src/main/resources/db/migration`, 13개)가 원본이다.

| 테이블 | 추가한 마이그레이션 |
| --- | --- |
| `wallet`, `point_ledger`, `point_hold`, `point_hold_detail`, `point_charge`, `point_charge_usage`, `point_refund_request`, `point_refund`, `settlement`, `exchange`, `exchange_account`, `exchange_payout` | `V202610121245` (이후 마이그레이션이 컬럼·제약 추가) |
| `mock_payout_transfer` | `V202610161700` |
| `billing_user` | `V202610162010` |
| (`wallet.wallet_type`·`fee_balance_hp`, 플랫폼 지갑 행, 원장 `FEE`·`EXCHANGE_FEE`) | `V202610301000` (#336) |
| (`ck_exchange_1`을 1 HP 이상으로) | `V202610301200` (#453) |
| `event_publication`, `event_publication_archive` (추정: Modulith 기본 이름) | Modulith `schema-initialization` |

- `apps/billing-app/AGENTS.md` 3장의 "12개 테이블" 목록에는 `billing_user`·`mock_payout_transfer`가 없다.
- Flyway 설정: `baseline-on-migrate`, `baseline-version: 0`, `out-of-order: true`. 기존 모놀리스 공용 DB 이력 전환은 미완료(#338).

### 8.3 Database 분리 To-Be (목표, 구현되지 않음)

루트 `AGENTS.md` 2·3장은 "모듈마다 database"를 규칙으로 적고 있고, 현재 구현은 schema 분리다. 목표의 원본은 인프라 문서 10장이다.

- PostgreSQL instance 1개 안에 앱마다 database를 둔다. Billing은 database `billing`(지갑, 원장, 보류, 지급, 환전)이다.
- database마다 소유 role을 두고 `REVOKE CONNECT ... FROM PUBLIC`으로 막는다.
- 진행 순서(#338): ① 모듈을 넘는 동기 호출을 내부 API + 보상 처리로 바꾼다(#271~#281) → ② 앱별 publication externalization을 만든다(#339) → ③ 스키마를 database로 옮긴다(`pg_dump`/restore, Flyway 이력 보존) → ④ connection 수와 메모리를 다시 잰다.

| 항목 | 현재 | 분리 시 바뀌는 것 / 검토 |
| --- | --- | --- |
| 접속 정보 | dev·prod `DB_URL`·`DB_USERNAME`·`DB_PASSWORD` (앱별 값 가능) | database `billing`, Billing 전용 계정은 자기 database에만 접속 |
| 다른 모듈 테이블과의 관계 | FK·JOIN 없음(코드 기준, `BillingSchemaTest`) | 그대로 |
| 모듈 간 트랜잭션 | 강의·출연이 `BillingPort`를 같은 트랜잭션에서 호출(`BD-53`, `BD-64`) | 원자적으로 묶을 수 없다. 분리 전에 내부 API + 보상 처리가 끝나야 한다 |
| 모듈 간 락 순서 `BD-72` | 같은 DB 트랜잭션 전제 | 성립하지 않는다 → 폐기·대체 결정 필요 |
| Flyway 이력 | `billing` 스키마의 `flyway_schema_history`, baseline 0 | `pg_dump`/restore로 데이터·이력 보존 |
| Modulith 이벤트 기록 | `billing` 스키마 | 자기 database에 그대로 두고 outbox로 쓴다(registry는 `DataSource` 하나에 묶인다) |
| connection | 앱별 pool | 총 connection = (앱 수 + surge pod 수) × pool 크기. `max_connections`와 함께 다시 계산 |
| Testcontainers | 공용 PostgreSQL 컨테이너 1개(test-support), 스키마 `billing` | 앱 test는 자기 database만 띄운다. test-support의 DB 정리·설정이 맞는지 확인 |

## 9. 설정과 환경 변수

| 설정 | 기본값 | 없을 때 |
| --- | --- | --- |
| `HAPBANG_INTERNAL_API_KEY` → `hapbang.internal-api.key` | 빈 값(local `local-placeholder`) | **기동 실패** (`InternalApiProperties`) |
| `MEMBER_API_BASE_URL`, `LECTURE_API_BASE_URL`, `APPEARANCE_API_BASE_URL` | 없음(local 8081·8084·8083) | dev·prod에서 값이 없으면 placeholder 해석 실패로 기동 실패(추정, 미확인). Client가 없으면 해당 기능 503 |
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` (dev·prod) | 없음 | 기동 실패 |
| `TOSS_PAYMENTS_SECRET_KEY` | 빈 값 | 충전·환불 503 |
| `BILLING_PAYMENT_KEY_ENCRYPTION_KEY`, `BILLING_PAYMENT_KEY_HMAC_KEY` | 빈 값 | 둘 다 없으면 충전·환불·환전 신청·Mock 송금 503. 하나만 있거나 형식이 틀리거나 두 값이 같으면 **기동 실패** |
| `hapbang.billing.mock-payout.enabled` + 활성 프로필 전부 local/test | local yaml만 true | 송금 불가, 정산 배치 건너뜀, 송금 관리자 API 503 |
| `hapbang.scheduling.enabled` | true | 배치 4개 미등록 |
| 서버 포트 | local 8082 | — |

키 값은 코드·로그·문서에 남기지 않는다 ([`docs/deploy/env-vars.md`](../deploy/env-vars.md)).

## 10. 알려진 제약 (As-Is)

| # | 제약 | 영향 | 관련 |
| --- | --- | --- | --- |
| L1 | `BillingPort` 원격 구현 없음 | 강의·출연 앱 기동 실패, 구매·취소·지급·위약금 거래 전체 불가 | #280 |
| L2 | 회원 이벤트가 JVM을 넘지 않음 | 신규 가입자 지갑·복제본 미생성(지갑은 조회 시 생성, 복제본은 Backfill로 보완) → 환전 승인 409 가능 | #339 |
| L3 | 앱 간 로그인 공유 미확인 | 사용자·관리자 API 인증 경로 미검증. 목표는 JWT + `X-User-*` 헤더(7.2) | #370~#372 |
| L4 | 배치 분산 락 없음 | 인스턴스 2개 이상이면 중복 실행 가능. 목표 배포는 replica 1개지만 rolling update(`maxSurge: 1`) 중 잠깐 겹칠 수 있다(추정, 6.1) | TBD |
| L5 | Backfill 동시 실행 방지가 인스턴스 내부만 | 위와 같음 | TBD |
| L6 | 잔액 대사는 라이브러리만, 호출하는 API·배치 없음 | 불일치 감지가 수동 | `BD-20` |
| L7 | `lock_timeout` 미설정 | 락 대기 무제한 | `BD-108` |
| L8 | 환전 정산일 공휴일 미반영 | 잠정 예정일 | `BD-96` |
| L9 | 실제 송금은 Mock(local·test)만 | dev·prod에서 송금 기능 503 | policy.md 2장 |
| L10 | HP 이동 경로의 회원 상태 확인은 환전 승인뿐 | JWT 전환 후에는 정지·역할 변경이 최대 15분(access token 수명) 늦게 반영된다. 목표는 HP 경로에서 복제본 상태를 함께 확인하는 것(7.2) | #371 |

## 11. 갱신 이력

| 날짜 | 기준 커밋 | 반영 내용 |
| --- | --- | --- |
| 2026-10-29 | 5a4dc1a | 최초 작성 (#356 독립 앱 구조 기준) |
| 2026-10-29 | da834b1 | dev pull 반영. billing-app·`common/shared` 코드 변경 없음. #369 최종 인프라 결정(JWT·Envoy·Resilience4j·Kafka outbox/inbox·database 분리 순서·replica 1개 배치)을 7.2·8.3·6.1·10장에 반영. Billing 이벤트 발행 없음을 7.1에 추가 |
| 2026-10-30 | d8d6c25 | #336 플랫폼 지갑·환전 수수료 수입 반영(3.1, INV-1, 5.3, 8.1, 8.2). 상세 정책은 [`CONTEXT.md`](CONTEXT.md) 3장 |
| 2026-10-30 | 3d6b61c | #453 환불·환전 1원 단위, 송금액 버림(송금 = floor(신청 × 90%)), 송금액 0원 환전은 은행 없이 완료(6장 환전 정산 행, 8.2). 상세는 [`CONTEXT.md`](CONTEXT.md) 4장 |
| 2026-10-30 | 2ba9624 | #428 Kafka 소비 규칙(#339) 반영: 회원 이벤트 수신(7.2)을 `eventId` inbox에서 자연 키 멱등·소비 그룹 둘·그룹별 DLT로 고침 |
| 2026-10-30 | f8021a3 | #430 회원 이벤트 Kafka 소비 구현 반영(7.1 회원 → Billing, 7.2 남은 것) |
