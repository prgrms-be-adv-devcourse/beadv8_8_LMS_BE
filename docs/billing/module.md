# 지갑(Billing) 모듈 — 현재 구현

> 문서 탐색: [지갑 문서 INDEX](INDEX.md)

> 문서 지도: **[현재 구현](module.md)** · [결정 기록](decisions.md) · [테스트 검증 기준](test-spec.md) · [회귀 실행 기록](regression-log.md)
>
> - 기준: 2026-11-06 `dev` 40fa9b25 (Billing 코드 마지막 변경은 #674 파기). 문서와 코드가 다르면 **코드가 맞다**. 확인 후 이 문서를 고친다.
> - 이 문서는 "지금 코드가 어떻게 동작하는가"만 적는다. 업무 규칙(금액·수수료·권한·탈퇴 등)의 원본은 [`docs/policy.md`](../policy.md), 결정과 이유는 [decisions.md](decisions.md)(`BD-*`), 구현 규칙은 `apps/billing-app/AGENTS.md`, 진행 상황은 `apps/billing-app/PROGRESS.md`다. 정책·결정은 여기서 다시 쓰지 않고 장 번호·BD 번호로 가리킨다.
> - 용어는 [`docs/GLOSSARY.md`](../GLOSSARY.md)가 원본이다. 인프라 목표의 원본은 [`docs/architecture/final-project-infrastructure.md`](../architecture/final-project-infrastructure.md)(#369)다. 갱신 이력은 11장에 있다.
> - 표시: **As-Is** = 지금 코드, **To-Be** = 목표(아직 구현되지 않음), **미확인** = 코드로 확인하지 못함, **TBD** = 팀 결정 전.

## 1. 범위

### 1.1 하는 일

| 영역 | 기능 | 진입점 |
| --- | --- | --- |
| 지갑 | 회원 지갑 생성·조회, 내 HP 거래 내역 | `WalletFacade`, `UserJoinedEventListener` |
| 원장 | HP 원장 기록, 잔액 버킷 이동, 역분개 검증, 잔액 대사(최근 바뀐 지갑 매일 자동, #607) | `LedgerRecordUseCase`, `WalletReconciliationUseCase`, `WalletReconciliationBatch` |
| 보류 | 보류·반환·지급·위약금·보류 무효화(강의·출연용), 종류별 보류·확정 차감(Billing 내부용), 서비스 간 대사 조회(#577) | `HoldFacade`(← 내부 API `ApiV1BillingInternalController`, `in.BillingCommands`가 shared Command를 `Payment*Command`로 변환, #494), `InternalHoldFacade` |
| 충전 | TossPayments 테스트 결제 승인, 결과 불명확 복구 | `ChargeFacade`, `ChargeRecoveryBatch` |
| 환불 | 충전 HP 환불(PG 부분 취소, FIFO), 결과 불명확 복구 | `RefundFacade`, `RefundRecoveryBatch` |
| 환전 | 신청·취소, 관리자 승인·거절, 환전 송금(Mock 은행), 정산일 배치, 송금 복구·재시도·최종 실패 | `ExchangeFacade`, `ExchangeAdminFacade`, `ExchangeSettlementBatch`, `ExchangePayoutRecoveryBatch` |
| 수익 조회 | 강의·출연의 받을 지급(수익)을 합쳐 요약·목록 제공 | `EarningFacade` |
| 지갑 회원 | `billing_user` 동기화(이벤트), 기존 회원 Backfill(관리자) | `BillingMemberEventListener`, `BillingUserBackfillFacade` |
| 탈퇴·파기 | 회원 탈퇴용 지갑 확인하고 닫기·다시 열기(내부 API, #623), 회원 정보 파기 때 열린 지갑 닫기·환전 계좌 지우기(#674), 잔액이 남은 닫힌 지갑 지표 | `WalletWithdrawalFacade` → `WalletWithdrawalUseCase`, `BillingMemberEventListener`(`UserMaskedEvent`) → `WalletMaskingUseCase`, `ClosedWalletBalanceMetrics` |

### 1.2 하지 않는 일

- 출연·강의 거래 상태 판단(구매 확정, 이의 제기, 지급 시점, 위약금 비율 계산). 지급 원천 모듈이 판단하고 Billing은 HP 이동만 한다 (`BD-56`, `BD-67`).
- 수익 금액·상태 계산. 강의·출연이 내부 API로 주고 Billing은 합치기만 한다 ([`docs/api/internal-earnings.md`](../api/internal-earnings.md)).
- 실제 은행 송금, 계좌 실명 확인 (policy.md 2장, `BD-94`).
- 탈퇴 지휘. 회원 앱이 출연·강의·지갑의 막힌 이유를 모아 탈퇴를 진행하고, 지갑은 자기 막힌 이유 계산과 닫기·다시 열기만 한다 ([`docs/api/internal-withdrawals.md`](../api/internal-withdrawals.md)).
- 다른 모듈 테이블 조회·변경.

## 2. 패키지와 계층

```text
apps/billing-app/src/main/java/com/hapbang/billing/
├── BillingApplication.java   scanBasePackages = {com.hapbang.billing, com.hapbang.shared}
├── package-info.java         @ApplicationModule(displayName = "Billing")
├── domain/   엔티티·Enum·BillingErrorCode·BillingException
├── out/      Repository(삭제 메서드 없음), 외부 Client(TossPayments, Mock 송금), 결제키 암호화, 회원 락(WalletUserLock)
├── app/      UseCase·Facade·배치·요청/결과 record
├── in/       Controller·Spec·응답 DTO·이벤트 리스너·Mock 은행
└── global/   앱 내부 공통(예외 핸들러, 보안, 내부 API 키 필터, RsData, 페이지·스케줄링·실행 락·rate limit 설정)
```

| 계층 | 코드에서 보이는 의존 방향 (main) | 강제 수단 |
| --- | --- | --- |
| `domain` | → `global`(예외 기반 타입) | 없음 |
| `out` | → `domain` | 없음 |
| `app` | → `domain`, `out`, `global` | `shared.billing.dto` 참조 금지(`BillingSharedDtoAccessTest`) |
| `in` | → `app`, `domain`, `global`, `out`(Mock 은행 조건·접근 키 4건) | 없음 |
| `global` | → `global` | 없음 |

- 아키텍처 테스트는 둘이다.
  - `WalletApplyLedgerAccessTest`: `Wallet.applyLedger` 호출을 `LedgerRecordUseCase`로 제한한다.
  - `BillingSharedDtoAccessTest`(#494, `BD-129`): `app`·`domain`·`out`은 `shared.billing.dto`를 참조하지 않는다. `in`만 허용한다.
- 계층 방향 ArchUnit·Modulith `verify` 테스트는 없다.
- 예전 `global` Gradle 모듈은 없어졌다. 예외 처리·보안은 `com.hapbang.billing.global`에 있다 ([`docs/architecture/app-modules.md`](../architecture/app-modules.md)).
- Gradle 프로젝트는 `:billing-app`(경로 `apps/billing-app`), 의존은 `:shared`(`common/shared`)뿐이다. 앱 사이 Gradle 의존은 `verifyModuleStructure`가 막는다.

### 2.1 기능별 진입점

권한 표시는 Facade의 `@PreAuthorize`다(U·I·VI = USER·INFLUENCER·VERIFIED_INFLUENCER, INTERNAL = 내부 API 키). Facade는 트랜잭션을 열지 않고 UseCase·Processing이 연다. 원장 기록과 지갑 잔액 변경은 모두 `LedgerRecordUseCase`(유일한 경로, `lockWallet`으로 지갑 락도 제공)를 거친다. 배치는 모두 `JobLock`(6.1)으로 실행한다.

| 기능 | Facade(권한) | UseCase·Processing | 주요 out |
| --- | --- | --- | --- |
| 지갑 조회·생성 | `WalletFacade.getMyWallet`(U·I·VI), `getOrCreateWallet`(검사 없음, 가입 리스너·Backfill용) | `WalletCreateUseCase`(없으면 `REQUIRES_NEW`로 생성, 탈퇴 닫기 기록이 있으면 닫힌 채 생성) | `WalletRepository`, `WalletWithdrawalRepository`, `WalletUserLock` |
| 거래 내역 | `WalletFacade.getMyTransactions`(U·I·VI) | `WalletTransactionQueryUseCase`(원장을 작업 키 단위로 합침) | `WalletRepository`, `PointLedgerRepository` |
| 충전 | `ChargeFacade`(U·I·VI): `createOrder`·`confirm`·`getCharge`. 설정·회로 확인, 주문·승인은 `WalletMemberCheck` | `ChargeOrderUseCase`, `ChargeConfirmUseCase`(DB 단계 사이에 TossPayments 호출·결과 판정) → `ChargeProcessing`(claim·complete·fail·noteUnresolved를 각각 짧은 트랜잭션). 복구 `ChargeRecoveryBatch` → `ChargeRecoveryUseCase` | `PointChargeRepository`, `TossPaymentsClient`, `PaymentKeyCipher`, `BillingEventPublisher` |
| 환불 | `RefundFacade`(U·I·VI): `request`·`getRefund`·`cancel`. 신청만 `WalletMemberCheck` | `RefundRequestUseCase`(접수 + CHARGE_ONLY 보류), `RefundProcessUseCase`(개별 환불 FIFO PG 취소) → `RefundProcessing`. 복구 `RefundRecoveryBatch` → `RefundRecoveryUseCase` | `PointRefundRequestRepository`, `PointRefundRepository`, `TossPaymentsClient`, `InternalHoldFacade`, `BillingEventPublisher` |
| 보류 생성·반환·지급·위약금·무효화 (내부 API) | `HoldFacade`(INTERNAL) ← `ApiV1BillingInternalController`(`BillingCommands`가 shared Command를 `Payment*Command`로 변환). 락 대기 초과는 `HP_LOCK_TIMEOUT` | `HoldCreateUseCase`(수익 HP 우선·충전 HP FIFO), `HoldReleaseUseCase`(충전 HP 우선·충전 원금 역순 복원), `HoldPayoutUseCase`(수익 HP 우선·충전 원금 FIFO 소진), `PenaltySettlementUseCase`(출연이 정한 반환액 + 지급액을 한 트랜잭션), `HoldVoidUseCase`. 공통 단계 `HoldProcessing`(트랜잭션 없음: 보류 락·이력 재생 대조·적용·지급 건 저장), 키 `BillingKeys`, 금액 검사 `BillingCommandValidator` | `PointHoldRepository`, `PointHoldDetailRepository`, `PointChargeRepository`, `PointChargeUsageRepository`, `SettlementRepository`, `HoldVoidRepository`, `BillingEventPublisher`(지급·위약금) |
| 확정 차감 (지갑 안) | `InternalHoldFacade`(검사 없음, 환불·환전 UseCase만 씀): `hold`·`capture`·`release` | `HoldCreateUseCase`, `HoldCaptureUseCase`, `HoldReleaseUseCase` → `HoldProcessing` | 보류 행과 같음 |
| 보류 대사 조회 (내부 API) | `HoldQueryFacade`(INTERNAL): `query`·`changes` | `HoldQueryUseCase` | `PointHoldRepository`, `PointHoldDetailRepository`, `SettlementRepository`, `HoldVoidRepository`, `WalletRepository` |
| 수익 조회 | `EarningFacade`(U·I·VI): `summary`·`list` | `EarningQueryUseCase` → `LectureEarningSource`·`AppearanceEarningSource` | shared `LectureApiClient`·`AppearanceApiClient`, `OutboundCircuitBreakers` |
| 환전 신청·취소 | `ExchangeFacade`(I·VI): `request`·`list`·`get`·`cancel`. 암호화 키 확인, 신청만 `WalletMemberCheck` | `ExchangeRequestUseCase`(REVENUE_ONLY 보류 + 계좌 스냅샷), `ExchangeProcessing`(조회·취소 = 반환) | `ExchangeRepository`, `ExchangeAccountRepository`, `ExchangeAccountCipher`, `InternalHoldFacade` |
| 환전 관리자 심사 | `ExchangeAdminFacade`(ADMIN): `list`·`get`·`approve`·`reject` | `ExchangeReviewUseCase`(락 `ExchangeLocks`) | `ExchangeRepository`, `BillingUserRepository`, `InternalHoldFacade`, `BillingEventPublisher`(거절) |
| 환전 송금·정산 배치·복구 | `ExchangeAdminFacade`(ADMIN): `payoutAttempts`·`payoutAttemptsOf`·`recheckPayout`·`retryPayout`·`failPayoutFinally`. 배치 `ExchangeSettlementBatch`, `ExchangePayoutRecoveryBatch` | `ExchangeSettlementUseCase` → `ExchangePayoutUseCase` → `ExchangePayoutProcessing`(시도 기록·결과 반영·최종 실패) | `PayoutTransferClient`(`MockPayoutHttpClient`), `ExchangePayoutRepository`, `InternalHoldFacade`, `BillingEventPublisher` |
| 플랫폼 지갑 | `PlatformWalletAdminFacade`(ADMIN): `get`·`feeIncomes` | `PlatformWalletQueryUseCase` | `WalletRepository`, `PointLedgerRepository` |
| 회원 Backfill | `BillingUserBackfillFacade`(ADMIN) | `BillingUserBackfillUseCase` → 회원마다 `BillingUserSyncUseCase` + `WalletFacade.getOrCreateWallet` | shared `MemberApiClient`, `OutboundCircuitBreakers`, `JobLock`(`MEMBER_BACKFILL`) |
| 지갑 회원 동기화 (Kafka) | 리스너(검사 없음): `BillingMemberEventListener`(`billing-app.member-replica`), `UserJoinedEventListener`(`billing-app.wallet-creation`) | `BillingUserSyncUseCase`(`REQUIRES_NEW` + 재시도), 지갑 생성은 `WalletFacade.getOrCreateWallet` | `BillingUserRepository` |
| 탈퇴 닫기·다시 열기 (내부 API) | `WalletWithdrawalFacade`(INTERNAL) ← `ApiV1BillingInternalController` | `WalletWithdrawalUseCase`, 막힌 이유 `WalletWithdrawalBlocks` | `WalletUserLock`, `WalletRepository`, `WalletWithdrawalRepository` |
| 파기 | `BillingMemberEventListener`(`UserMaskedEvent`, 검사 없음) | `WalletMaskingUseCase` | `WalletUserLock`, `WalletRepository`, `ExchangeRepository`, `ExchangeAccountRepository`, `MockPayoutTransferRepository` |
| 대사 배치 | `WalletReconciliationBatch` | `ChangedWalletReconciliationUseCase` → 지갑마다 `WalletReconciliationUseCase` | `WalletRepository`, `PointLedgerRepository` |
| 이벤트 재발행 | `BillingEventRepublishBatch` | `BillingEventRepublishUseCase` | Modulith `IncompleteEventPublications` (7.1 이벤트 발행 세부) |

## 3. 도메인 모델과 상태 전이

### 3.1 애그리거트

| 애그리거트 | 테이블 | 안에서만 쓰는 연관관계 |
| --- | --- | --- |
| 지갑 | `wallet` (회원 지갑 `MEMBER`, 플랫폼 지갑 `PLATFORM` 1개 — #336. 닫힘 표시 `closed_at`, 탈퇴로 닫았으면 `closed_by_withdrawal_id` — #623·#674) | — |
| 원장 | `point_ledger` (append-only) | — |
| 보류 | `point_hold` → `point_hold_detail`, `point_charge_usage` | `@ManyToOne` |
| 보류 무효화 기록 | `hold_void` (참조 대상당 1건, #479) | — |
| 충전 | `point_charge` | — |
| 환불 요청 | `point_refund_request` → `point_refund` | `@ManyToOne` |
| 지급 건 | `settlement` | — |
| 환전 | `exchange` → `exchange_account`, `exchange_payout` | `@OneToOne`·`@ManyToOne` |
| 지갑 회원 | `billing_user` (`ReplicaUser` 상속) | — |
| 탈퇴 기록 | `wallet_withdrawal` (회원 앱의 탈퇴 작업 ID당 1건, 닫기·다시 열기 결과, #623) | — |
| Mock 은행 기록 | `mock_payout_transfer` (local·test 전용 기능) | — |

애그리거트 사이는 `Long` ID 필드로 참조한다 (`BD-36`).

### 3.2 상태 전이 (엔티티 메서드 기준)

| Enum·상태 | 전이 | 만드는 코드가 없는 상수 |
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
| `LedgerTransactionType` | CHARGE, HOLD, RELEASE, PAYOUT, REFUND, EXCHANGE, EXCHANGE_FEE(플랫폼 지갑 수수료 수입, #336) 기록 | REVERSAL(검증 코드만 있고 main 호출부 없음, 테스트에서만 사용) |
| 지갑 닫힘 (컬럼, Enum 아님) | 열림 → `closeFor(withdrawalId)` 탈퇴로 닫힘(`closed_by_withdrawal_id`·`closed_at`) / `closeForMasking` 파기로 닫힘(`closed_at`만) · 탈퇴로 닫힘 → `reopen`(같은 탈퇴 작업 ID일 때만) → 열림. 파기로 닫힌 지갑은 다시 열리지 않는다. 플랫폼 지갑은 닫을 수 없다 | — |
| `WithdrawalCloseOutcome`·`WithdrawalReopenOutcome` (shared, `wallet_withdrawal` 저장값) | 닫기 CLOSED / BLOCKED(막힌 이유 JSONB), 다시 열기 REOPENED / NOT_CLOSED | — |

그 밖의 Enum: `WalletType`(MEMBER·PLATFORM), `PointType`(CHARGE·REVENUE·FEE), `BalanceBucket`(AVAILABLE·HELD), `PointHoldType`(MIXED·CHARGE_ONLY·REVENUE_ONLY), `PayoutType`(NORMAL·PENALTY, shared), `HoldDetailActionType`(RELEASE·PAYOUT·CAPTURE), `RefundManualReviewReason`, `PgProvider`(TOSS_PAYMENTS), `PayoutProvider`(MOCK), 수익 조회용 `EarningSourceType`·`EarningType`·`EarningStatus`. 상수 이름이 DB 저장값이라 바꾸지 않는다(`BillingEnumStorageTest`).

## 4. 잔액·원장 불변식

정책 원본: policy.md "팀 규칙 > 원장과 민감 정보", 1·4장.

| ID | 불변식 | 지키는 곳 |
| --- | --- | --- |
| INV-1 | 지갑 잔액 4개(충전·수익 × 가용·보류)와 플랫폼 지갑의 수수료 수입(FEE 가용)은 원장 합계와 같다 | `LedgerRecordUseCase`가 유일한 변경 경로, `WalletReconciliationUseCase`로 대조 |
| INV-2 | 원장은 수정·삭제되지 않는다 | DB 트리거 `trg_point_ledger_append_only`(UPDATE·DELETE → 23514), `@PreRemove` |
| INV-3 | 잔액은 음수가 되지 않는다, 원장 금액은 0이 아니다 | `INSUFFICIENT_BALANCE`, `INVALID_POINT_AMOUNT`, `Math.addExact`(`BALANCE_OVERFLOW`) |
| INV-4 | 완료 충전 건의 가용 원금 합 = `wallet.charge_balance_hp` | 보류 전 대조(`CHARGE_PRINCIPAL_MISMATCH`) |
| INV-5 | 보류의 반환·지급·확정 차감 상세를 처리 순서로 재생하면 충전 배분 누적값과 같다 | `ChargeUsageHistory`(`HOLD_STATE_INCONSISTENT`) |
| INV-6 | 지급 원천 하나는 한 번만 지급된다 | `uk_settlement_source_type_source_id`, `PAYOUT_ALREADY_SETTLED` |
| INV-7 | `refunded_amount_hp + remaining_principal_hp ≤ amount_hp` | `ck_point_charge_4` |
| INV-8 | 한 보류는 반환·지급·확정 차감 중 허용된 조합으로만 끝나고, 보류 종류별 구성(CHARGE_ONLY는 수익 0, REVENUE_ONLY는 충전 0)을 지킨다 | `ck_point_hold_5`, `HOLD_TYPE_NOT_SUPPORTED` |
| INV-9 | 닫힌 지갑에는 새로 HP가 드나드는 신청(충전 주문·승인, 보류 생성 = 강의·출연 결제·환불·환전 신청)이 생기지 않는다. 이미 생긴 의무(보류 반환·지급 받기·복구 배치)는 받는다 | 지갑 락 아래 `Wallet.requireOpen`(`ChargeOrderUseCase`, `ChargeProcessing`, `HoldCreateUseCase`) → 409 `WALLET_CLOSED`. 닫힌 지갑에 가용 HP가 들어오면 warn 로그(`LedgerRecordUseCase`) |

환불·환전의 추가 불변식(요청액 = 개별 환불 합 등)은 `apps/billing-app/AGENTS.md` 10.2·11장에 있다.

## 5. 트랜잭션·동시성·멱등성

### 5.1 트랜잭션 경계

| 흐름 | 경계 (As-Is) |
| --- | --- |
| 보류·반환·지급·위약금 (`HoldFacade`) | UseCase `@Transactional(REQUIRED)`. 내부 HTTP API 요청 하나가 지갑 트랜잭션 하나다(#486, `BD-143`). 지갑 안 호출(환불·환전 UseCase → 보류)만 바깥 트랜잭션에 참여한다(`BD-53`) |
| 원장 기록 | `LedgerRecordUseCase.record` `REQUIRED`, 락 헬퍼는 `MANDATORY` |
| 지갑 생성 | `REQUIRES_NEW`(UNIQUE 위반 후 바깥에서 재조회, `BD-4`). 회원 락을 먼저 잡고, 그 회원의 마지막 탈퇴 닫기 기록이 있으면 닫힌 지갑으로 만든다 |
| 충전 | claim 커밋 → PG 승인(트랜잭션 밖) → complete/fail 커밋 (`BD-76`) |
| 환불 | 접수 한 트랜잭션 → 개별 환불마다 PROCESSING 커밋 → PG 취소(밖) → 결과 반영 |
| 환전 송금 | 시도 기록 커밋 → Mock 송금(밖, 트랜잭션 안에서 부르면 `IllegalStateException`) → 결과 반영 커밋 |
| 수익 조회 | 트랜잭션 없음(내부 HTTP 호출) |
| 지갑 회원 반영 | 리스너는 `NOT_SUPPORTED`, 반영은 `REQUIRES_NEW` + 최대 3회 재시도 |
| 탈퇴 닫기·다시 열기 | 요청 하나 = 한 트랜잭션: 회원 락 → 탈퇴 기록 확인 → 지갑 락 → 막힌 이유 계산 → 닫기 (`WalletWithdrawalUseCase`) |
| 파기 | 이벤트 하나 = 한 트랜잭션: 회원 락 → 지갑 락 → 열린 지갑 닫기 → 끝난 환전의 계좌 지우기 → 끝난 Mock 송금 기록 덮기 (`WalletMaskingUseCase`) |

### 5.2 락 순서

| 흐름 | 순서 |
| --- | --- |
| 공통 | 지갑(ID 오름차순, `PESSIMISTIC_WRITE`, 락 전 flush·락 후 refresh) → 보류 → 충전 건(ID 오름차순) |
| 환불 | 지갑 → 환불 요청 → 보류 → 충전 건 |
| 환전·송금 | 지갑 → 환전(`findByIdForUpdate` + refresh) → 보류 → 환전 송금 |
| 충전 claim | 충전 건만(`FOR NO KEY UPDATE`), 지갑 없음 |
| 지갑 생성·탈퇴 닫기·다시 열기·파기 | 회원 락(`WalletUserLock`, `pg_advisory_xact_lock(int, int)`, 배치 실행 락과 키 공간 분리) → 지갑. 지갑이 아직 없는 회원의 닫기와 지갑 생성을 직렬화한다 |
| 모듈 간 | 없다. database가 나뉘어 한 트랜잭션 안에 다른 모듈 락이 없다(`BD-72` 폐기 → `BD-142`) |

`lock_timeout`은 3초다(연결마다 `SET lock_timeout = '3s'`, `billing.yml`, `BD-108`).

### 5.3 멱등키

| 작업 | 키 (Billing이 만든다, `BillingKeys`) | 최종 보장 |
| --- | --- | --- |
| 원장 줄 | `{operationKey}:{줄번호}` | `uk_point_ledger_idempotency_key` |
| 보류 | `HOLD:{referenceType}:{referenceId}` (참조 대상당 1건) | `uk_point_hold_idempotency_key` |
| 반환 | `RELEASE:{holdId}:{releaseRequestId}` | 보류 상세 UNIQUE |
| 보류 무효화 | 참조 대상(`referenceType` + `referenceId`)당 1건. 남은 보류 반환은 `RELEASE:{holdId}:VOID` (#479) | `uk_hold_void_reference` |
| 지급 | `PAYOUT:{holdId}:{payoutRequestId}` | 보류 상세 UNIQUE + 원천 UNIQUE |
| 위약금 | `PENALTY:{holdId}:{cancellationId}` (`…:RELEASE`, `…:PAYOUT`) | 위와 같음 |
| 확정 차감 | `CAPTURE:{holdId}:{captureRequestId}` | 보류 상세 UNIQUE |
| 충전 | 주문 ID, 결제키 HMAC, 원장 `CHARGE:{chargeId}`, PG `hapbang-charge-confirm-{orderId}` | UNIQUE 3개 |
| 환불 | 회원 + `Idempotency-Key` 헤더, PG `hapbang-refund-cancel-{UUID}`(저장값) | UNIQUE |
| 환전 신청 | 회원 + `Idempotency-Key` 헤더 | UNIQUE |
| 환전 반환·확정 차감 요청 ID | `EXCHANGE-CANCEL-{id}`, `EXCHANGE-REJECT-{id}`, `EXCHANGE-PAYOUT-{id}`, `EXCHANGE-PAYOUT-FAIL-{id}`(최종 실패 반환) | 보류 상세 UNIQUE |
| 환전 수수료 수입 | 원장 작업 키 `EXCHANGE_FEE:{exchangeId}` (환전당 1건, #336) | `uk_point_ledger_idempotency_key` |
| 환전 송금 시도 | 시도마다 새 UUID(`payout_request_id`), 결과 불명확 시 같은 ID로만 조회·재전송 | `uk_exchange_payout_active`(FAILED 아닌 시도 1건) |
| 탈퇴 닫기·다시 열기 | 회원 앱의 탈퇴 작업 ID. 같은 회원 재요청은 처음 결과(`replayed`), 다른 회원은 `IDEMPOTENCY_KEY_CONFLICT`, 다시 열기를 받은 탈퇴 작업의 늦은 닫기는 409 `WITHDRAWAL_ALREADY_REOPENED` | `pk_wallet_withdrawal` |

같은 키에 다른 내용 = `IDEMPOTENCY_KEY_CONFLICT`. 같은 키·같은 내용 = 처음 결과(`replayed=true`).

## 6. 기능별 처리 흐름 (요약)

업무 규칙은 policy.md, 결정은 BD, 구현 규칙은 `apps/billing-app/AGENTS.md`의 해당 장에 있다. 여기서는 흐름과 위치만 적는다.

| 기능 | 흐름 | 규칙·결정 |
| --- | --- | --- |
| 보류 | 지갑 락 → 원장 조회 → 보류 조회(재요청 판정, 닫히기 전 보류의 재요청은 처음 결과) → 닫힌 지갑이면 `WALLET_CLOSED` → 수익 HP 우선·충전 HP FIFO 배분 → 원장·보류·배분 저장 | AGENTS 8장, `BD-21`~`BD-28` |
| 반환 | 충전 HP 먼저, 원금 LIFO 복원 | AGENTS 8장, `BD-29`~`BD-35` |
| 보류 무효화 (#479) | 지갑 락 → 무효화 기록 조회(재요청) → 보류 조회 → 남은 보류 전부 반환(요청 ID `VOID`) / 끝난 보류는 그대로 / 보류 없음 → 무효화 기록 저장. 기록이 있는 참조 대상의 보류는 `HOLD_VOIDED` | `BD-125`~`BD-126` |
| 지급 | 수익 HP 보류 먼저, 충전 원금 FIFO 소진, 받는 쪽 수익 HP 가용, `settlement` COMPLETED INSERT | AGENTS 8·8.1장, `BD-39`~`BD-45` |
| 위약금 | 출연이 정한 반환액 + 지급액을 한 트랜잭션 처리 | AGENTS 8장, `BD-56`~`BD-59` |
| 충전 | 주문 생성 → 결제창 → 승인(claim → PG → complete) → 복구 배치. 주문·승인 때 닫힌 지갑이면 PG를 부르지 않고 `WALLET_CLOSED` | AGENTS 10.1장, `BD-75`~`BD-81` |
| 환불 | 접수(CHARGE_ONLY 보류) → 개별 환불 FIFO 순차 PG 취소 → 성공 확정 차감 / 실패 반환 / 불명확 보류 유지 → 복구 배치 | AGENTS 10.2장, `BD-87`~`BD-93`, `BD-119` |
| 환전 신청·심사 | 신청(REVENUE_ONLY 보류 + 계좌 스냅샷) → 취소(REQUESTED) 또는 관리자 승인·거절(거절 = 반환) | AGENTS 11장, `BD-94`~`BD-98`, `BD-102`, `BD-120` |
| 환전 정산·송금 (#304·#305) | 정산일 배치가 APPROVED + 예정 시각 지난 건을 100건씩(실행당 최대 1,000) `execute` → 시도 기록 커밋 → Mock 송금 → 성공 확정 차감 + 수수료 수입. 송금액 0원(신청 1 HP)은 시도·Mock 송금 없이 바로 확정 차감 + 수수료 수입 + COMPLETED(#453) / 실패 FAILED(보류 유지) / 불명확 UNKNOWN. 복구 배치가 PROCESSING·UNKNOWN을 같은 ID로 조회, 24시간(`auto-check-limit`) 지나면 MANUAL_REVIEW_REQUIRED. 관리자 재확인·재시도(새 UUID)·최종 실패(FINAL_FAILED + 반환 `EXCHANGE-PAYOUT-FAIL-{id}`) | AGENTS 11장, `BD-99`~`BD-101`, `BD-116`, `BD-121` |
| 내 거래 내역 (#295) | 원장을 작업 키 단위로 합쳐 14개 유형(`WalletTransactionType`)으로 보여 준다. 지갑이 없으면 빈 페이지 | — |
| 수익 조회 (#295) | 강의·출연 내부 API를 매 요청 호출해 메모리에서 합친다. 저장하지 않는다. 한쪽 실패 = 전체 503, 깊이 1,000 초과 = 400 | [`internal-earnings.md`](../api/internal-earnings.md) |
| 회원 Backfill (#324) | 관리자 호출 → `MemberApiClient` 키셋 페이지 → 회원마다 지갑 회원 Upsert + 지갑 생성. 한 번 최대 5,000명, 동시 실행 금지는 배치와 같은 실행 락(`BillingJob.MEMBER_BACKFILL`, 6.1) | `BD-132`, `BD-138` |
| 탈퇴 닫기 (#623) | 회원 앱이 호출 → 회원 락 → 탈퇴 기록 확인(재요청·늦은 닫기) → 지갑 없음: CLOSED 기록만(나중에 지갑을 닫힌 채 만든다) / 지갑 락 → 막힌 이유(충전·수익 잔액, 처리 중인 환불·환전·충전, `WalletWithdrawalBlocks`) 있으면 BLOCKED 기록, 없으면 지갑 닫기 + CLOSED 기록. 출연·강의 보류는 그 모듈이 이유로 적는다 | policy.md 6장, `BD-141`, [`internal-withdrawals.md`](../api/internal-withdrawals.md) 4장 |
| 탈퇴 다시 열기 (#623) | 이 탈퇴 작업으로 닫힌 지갑이면 열고(REOPENED), 아니면 그대로(NOT_CLOSED). 어느 경우든 다시 열기 기록을 남겨 늦은 닫기를 거절한다 | `BD-141` |
| 파기 (#674) | `UserMaskedEvent`(소비 그룹 `billing-app.member-replica`) → 열린 지갑을 `closed_at`만으로 닫음(잔액·처리 중 거래와 무관) → 끝난 환전(COMPLETED·REJECTED·CANCELED·FINAL_FAILED)의 계좌 삭제, 끝난 Mock 송금 기록의 예금주·계좌 해시 덮기. 처리 중인 환전의 계좌는 남기고 error 로그. 금액·상태·원장은 건드리지 않는다 | policy.md 6장, `BD-144`, `BD-145` |
| 잔액이 남은 닫힌 지갑 | gauge `hapbang.billing.wallet.closed.with.balance`. 수집 때마다 DB(부분 인덱스 `idx_wallet_2`)에서 세고, 못 세면 NaN | `BD-141`, `BD-145` |

### 6.1 배치

모두 `hapbang.scheduling.enabled=true`(기본 켬)일 때만 등록되고 zone은 `Asia/Seoul`이다.

| 배치 | cron 속성 (기본값) | 추가 조건 |
| --- | --- | --- |
| `ChargeRecoveryBatch` | `hapbang.billing.charge.recovery-cron` (`0 */5 * * * *`) | — |
| `RefundRecoveryBatch` | `hapbang.billing.refund.recovery-cron` (`30 */5 * * * *`) | — |
| `ExchangePayoutRecoveryBatch` | `hapbang.billing.exchange.payout.recovery-cron` (`15 */5 * * * *`) | `…payout.recovery-enabled` (없으면 켬) |
| `ExchangeSettlementBatch` | `hapbang.billing.exchange.settlement.cron` (`0 5 10-23 * * *`) | `…settlement.enabled` (없으면 켬). 송금 Client를 못 쓰면 실행을 건너뜀 |
| `BillingEventRepublishBatch` | `hapbang.billing.event.republish-cron` (`5 * * * * *`) | — |
| `WalletReconciliationBatch` (#607) | `hapbang.billing.reconciliation.cron` (`45 2 4 * * *`) | 대상 창 `hapbang.billing.reconciliation.window` (`72h`) |

여러 pod에서 같은 배치가 동시에 돌지 않게 실행 락을 둔다(#598, BD-138). 배치는 `run()`에서 `JobLock.runOrSkip`으로 PostgreSQL advisory lock을 기다리지 않고 잡고, 잡지 못하면 그 회차를 건너뛴다. 관리자 회원 Backfill도 같은 락을 쓴다(다른 실행 중 409 `BACKFILL_ALREADY_RUNNING`, 락 오류 503 `BACKFILL_LOCK_UNAVAILABLE`).

| 항목 | 내용 |
| --- | --- |
| 락 번호 | `global.scheduling.BillingJob` enum의 고정 상수(598001~598006, 잔액 대사 607001). 바꾸지 않고, 새 작업은 그 작업을 더한 이슈 번호 × 1000 + 순번을 쓴다(BD-139) |
| 락 연결 | Hikari pool 밖의 전용 연결 1개(`ApplicationName=billing-app-job-lock`). 첫 락 시도 때 연결한다. pod당 DB 연결 +1. 호출은 한 모니터로 직렬화되고, 상한은 연결 확인 1초·연결 3초·쿼리 3초(호출당 최악 락 시도 약 7초, 해제 약 4초) |
| 같은 pod 안 | advisory lock은 같은 session이면 재진입되므로, 이 pod가 쥔 작업을 메모리로 확인해 막는다 |
| 다른 실행이 쥠 / 락 오류 | info 로그 / warn 로그. 둘 다 실행하지 않고 `hapbang.billing.job.lock.skipped{job, reason=held\|error}`를 센다 |
| 락 연결이 끊김 | 그 연결의 락은 PostgreSQL이 푼다. 돌던 작업은 끝까지 두고, 다음 시도 때 다시 연결하며 warn을 남긴다 |
| 막지 않는 것 | 한쪽이 끝내고 푼 뒤 다른 pod가 늦게 시작한 같은 회차, 기동 때 Modulith 미완료 이벤트 재발행. 각 UseCase의 락·멱등성으로 허용한다 |

### 6.2 잔액 대사 배치 (#607, BD-139)

매일 04:02:45 KST에 최근 72시간 안에 바뀐 지갑(`wallet.updated_at` 또는 `point_ledger.created_at`)을 지갑마다 `WalletReconciliationUseCase.reconcile`로 대사한다. 불일치를 고치지 않는다(BD-20).

| 항목 | 내용 |
| --- | --- |
| 대상 | 지금 − 창(`hapbang.billing.reconciliation.window`, 72h) 이후 수정된 지갑 ∪ 원장이 기록된 지갑. 플랫폼 지갑 포함. 실행 시점을 저장하지 않으므로 두 회차를 연달아 건너뛰어도 놓치지 않는다. 창보다 오래 바뀌지 않은 지갑은 보지 않는다 |
| 인덱스 | `idx_wallet_1 (updated_at)`, `idx_point_ledger_5 (created_at)` (`V202611031800`) |
| 불일치 | error 로그 `잔액 대사 불일치 walletId=…, buckets=[CHARGE/AVAILABLE wallet=… ledger=… diff=…]` |
| 지갑 하나의 오류 | warn 로그 후 계속. 전체를 돈 뒤 한 번 더 시도하고, 그래도 실패하면 `failed`로 세고 다음 회차 대상에 창과 관계없이 더한다(pod 메모리, 재시작 때 사라짐) |
| 끝 | info 로그 `잔액 대사 완료 since=…, total, matched, mismatched, retried, failed` |
| 지표 | `hapbang.billing.reconciliation.wallets{result=matched\|mismatched\|failed}`(마지막 실행, gauge), `hapbang.billing.reconciliation.last.success`(epoch 초, Prometheus `…_last_success_seconds`). last.success는 재시도 뒤 `failed`가 0일 때만 갱신한다(불일치는 성공, 대상 0개도 성공). pod가 다시 뜨면 첫 실행 전까지 0 |
| 실행 락 | `BillingJob.WALLET_RECONCILIATION`(607001). 다른 pod가 쥐면 그 회차를 건너뛴다 |

### 6.3 배포 중 동작

- k3s에서 replica 1개, `core-data`(t3.large) node, `maxSurge: 1`·`maxUnavailable: 0`이다(`apps/billing-app/k8s/base/deployment.yaml`).
- rolling update 중에는 새 pod와 기존 pod가 잠깐 함께 떠서 두 pod의 배치가 같은 cron 시각에 겹칠 수 있다. 6.1의 실행 락이 한쪽만 실행하게 한다.
- To-Be: Graceful shutdown 때 배치·HTTP를 어떤 순서로 멈출지는 인프라 문서 4장(`DrainCoordinator`, `/api/v1/internal/operations/drain`)이 목표이며, billing-app에는 아직 없다.

## 7. 외부 의존과 통신 상태

### 7.1 As-Is (코드 기준)

| 방향 | 방식 | 상태 | 관련 |
| --- | --- | --- | --- |
| 강의 → Billing (보류·반환·지급·무효화) | shared `BillingApiClient`로 지갑 내부 HTTP API 호출(#275~#277). HP 작업 실행기가 로컬 트랜잭션 밖에서 부른다 | 같은 JVM의 shared `BillingPort`·`BillingPortAdapter`는 #486에서 지웠다 | #271, #486, `BD-128` |
| 출연 → Billing (보류·반환·지급·위약금·무효화) | shared `BillingApiClient`로 지갑 내부 HTTP API 호출(#274) | — | #274, #486 |
| 회원 → Billing (탈퇴 닫기·다시 열기) | member-app `BillingWalletWithdrawalGate` → shared `BillingApiClient` → `POST /api/v1/internal/billing/withdrawals/{withdrawalId}/{close,reopen}` | — | #623, `BD-141` |
| Billing이 내부 API 제공 | `ApiV1BillingInternalController`(8.1 내부 API 행), `X-Internal-Api-Key` → `ROLE_INTERNAL`. `HoldFacade`·`WalletWithdrawalFacade`에 `@PreAuthorize("hasRole('INTERNAL')")` | 계약은 [`internal-billing.md`](../api/internal-billing.md), [`internal-withdrawals.md`](../api/internal-withdrawals.md) | #273, #479, #577, #623 |
| 회원 → Billing (지갑 생성, 지갑 회원, 파기) | Kafka `member.events.v1`을 소비 그룹 둘로 받는다(#430, `BD-123`): `billing-app.member-replica`(`BillingMemberEventListener`, 가입·변경 → 지갑 회원 Upsert, `UserMaskedEvent` → 파기 처리 #674), `billing-app.wallet-creation`(`UserJoinedEventListener`, 가입 → 지갑, ADMIN 제외). 실패는 블로킹 재시도 3회 뒤 그룹별 DLT(`BillingKafkaConfig`). DLT 복구는 kafka CLI 확인 + 관리자 Backfill 운영 절차(`BD-136`, `docs/deploy/k8s.md`) | 로컬·테스트 설정은 리스너·토픽 생성을 끈다(Kafka 소비 테스트만 켬). 앱 시작 전 가입자는 지갑 조회 보완·Backfill로 채운다 | #430, #674 |
| Billing → 다른 앱 (이벤트 발행) | Kafka `billing.events.v1`(#497, `BD-130`): 충전·지급·환불·환전 결과 이벤트 7종. Modulith externalization(발행 기록 = outbox), 같은 트랜잭션 발행 + 매분 재발행(`BillingEventRepublishBatch`) | 소비자는 아직 없다. 알림은 auxiliary-app의 notification 모듈이 받을 예정이다(#193·#684, [`notification.md`](../architecture/infrastructure/notification.md)) | [`CONTEXT.md`](CONTEXT.md) 9장 |
| Billing → 강의·출연 (수익 조회) | shared `LectureApiClient`·`AppearanceApiClient` 내부 HTTP. 지갑 쪽 회로 `lecture`·`appearance` + 연결 실패만 1회 재시도(#562) | base-url 환경 변수 필요 | #295, internal-earnings.md, `BD-134` |
| Billing → 회원 (Backfill) | shared `MemberApiClient` 내부 HTTP. 지갑 쪽 회로 `member`, 재시도 없음(#562) | — | #322, #324, `BD-134` |
| Billing → TossPayments | HTTP (`TossPaymentsClient`). 회로 `toss`(결과 `Unknown`만 실패, 재시도 없음). 열려 있으면 충전·환불 신청 503, 결과 확인 배치는 회차를 건너뜀(#562) | — | `BD-134` |
| Billing → Mock 은행 | 같은 billing-app에 실제 HTTP (`localhost:{port}`) | local·test 프로필에서만 켜짐 | #288, `BD-100` |
| 인증 | `GatewayUserHeaderFilter`가 Envoy가 넘긴 `X-User-Id`·`X-User-Role`로 인증한다(#537). `SecurityConfig`는 STATELESS·CSRF 없음, `/api/v1/*/admin/**` = ADMIN, 나머지 인증 필요. `@LoginUserId` = 헤더의 회원 ID. 충전 주문·승인·환불 신청·환전 신청은 지갑 회원(`billing_user`) 상태·역할도 확인한다(`WalletMemberCheck`, 없음 404·부적격 403) | Envoy SecurityPolicy(`apps/billing-app/k8s/base/gateway.yaml`)가 JWT를 검증하고, NetworkPolicy가 8080에 Gateway만 들인다 | #537, `BD-131` |
| 외부 노출 차단 | Envoy Gateway에 `/api/v1/internal/**` route가 없어 404다 | 지갑 내부 API도 서버 밖에서 닿지 않는다 | 루트 AGENTS 7장 |
| rate limit | 사용자 API 14개에 회원 × API마다 분당 신청·취소 10·일반 조회 60·수익 조회 20(`hapbang.billing.rate-limit.*`), 넘으면 429 `TOO_MANY_REQUESTS` + `Retry-After`, 지표 `hapbang.billing.rate.limit.rejected{api}`. 내부·관리자 API 제외 | — | #627, `BD-140` |

이벤트 발행 세부 (#497, 코드 기준):

| 항목 | 지금 값 | 위치 |
| --- | --- | --- |
| 토픽 | `billing.events.v1`, partition 3, 복제 1. 지갑 앱이 `NewTopic`으로 선언한다(외부화를 끈 `spring.modulith.events.externalization.enabled=false`에서는 선언하지 않음) | `BillingEventExternalizationConfig`, shared `BillingEventTopic` |
| 대상·key | `BillingExternalEvents.isExternalized`(shared 지갑 이벤트 중 `@Externalized`). 토픽과 key는 레코드의 `@Externalized`가 정한다 | shared `com.hapbang.shared.billing.event` |
| 헤더 | `eventType`(레코드 이름, 이름을 바꿔도 값은 유지)·`eventVersion`(`1`) 두 개. 이벤트 ID 헤더는 없다 | `BillingEventExternalizationConfig` |
| 직렬화 | `spring.modulith.events.kafka.enable-json: false`, 발행 값 `JacksonJsonSerializer`(`spring.kafka.producer.value-serializer`) | `billing.yml` |
| 발행 기록 | `billing.event_publication`(Modulith 스키마 초기화), `completion-mode: archive`, `republish-outstanding-events-on-restart: true` | `application.yaml` |
| 실행 중 재발행 | `BillingEventRepublishBatch` cron `hapbang.billing.event.republish-cron`(`5 * * * * *`, 매분 5초) → `BillingEventRepublishUseCase`. 유예 `hapbang.billing.event.republish-pending-grace`(`1m`)가 지난 미완료 기록을 한 번에 `hapbang.billing.event.republish-batch-size`(`100`)개까지 다시 보내고 warn 로그. 배치는 실행 락 `BillingJob.EVENT_REPUBLISH`(598005)로 한 pod만 돈다 | `app/BillingEventRepublish*.java` |

### 7.2 목표 대비 남은 것

인프라 문서(#369)와 루트 `AGENTS.md` 2·7장의 목표 중 보류·반환·지급 내부 API, 내부 API 노출 차단, 회원 이벤트 Kafka 수신, 지갑 이벤트 발행, Gateway 인증, rate limit, prod database 분리, k3s 배포는 구현됐다(7.1, 6.3, 8.3). 남은 것만 적는다.

| 영역 | 목표 | 지금 |
| --- | --- | --- |
| 지갑 이벤트 소비 | 지갑 이벤트 → 알림센터 알림(인프라 문서 1장). HP 원장을 Kafka consumer가 확정하지 않는다 | 발행만 있다. 알림 모듈 쪽 소비·매핑은 #193 |
| Database (로컬·테스트) | 모듈별 database (8.3) | prod만 적용. 로컬·테스트는 공용 database + `billing` 스키마 |
| Graceful shutdown | `DrainCoordinator`로 배치·HTTP 정지 순서 (인프라 문서 4장) | 없음 (6.3) |

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
| `POST /api/v1/internal/billing/holds`·`releases`·`payouts`·`penalty-settlements`·`hold-voids` | `X-Internal-Api-Key` (ROLE_INTERNAL) | [`internal-billing.md`](../api/internal-billing.md). `hold-voids`는 #479 |
| `POST /api/v1/internal/billing/holds/query`, `GET /api/v1/internal/billing/holds` | `X-Internal-Api-Key` | 서비스 간 HP 대사용 보류·지급 조회(#577, `BD-137`). `GET`은 참조 유형별 기간 키셋 페이지(`idx_point_hold_2`) |
| `POST /api/v1/internal/billing/withdrawals/{withdrawalId}/close`, `…/reopen` | `X-Internal-Api-Key` (ROLE_INTERNAL) | 회원 탈퇴용 지갑 닫기·다시 열기(#623, `BD-141`). [`internal-withdrawals.md`](../api/internal-withdrawals.md) 4장 |

U = USER, I = INFLUENCER, VI = VERIFIED_INFLUENCER. 관리자 API는 URL 규칙 + Facade `@PreAuthorize` 두 겹이다. 내부 API(`/api/v1/internal/billing/**`)는 내부 API 키로 인증하고 Envoy route가 없다.

### 8.2 DB 테이블과 Flyway

- 테이블은 Flyway 테이블 16개 + Modulith 발행 기록 2개다. DDL·제약은 Flyway(`apps/billing-app/src/main/resources/db/migration`, 19개)가 원본이다.
- 모든 테이블은 `billing` 스키마에 있다(`spring.flyway.default-schema`, `spring.modulith.events.jdbc.schema`). 이 스키마가 어느 database에 있는지는 환경마다 다르다(8.3).

| 테이블 | 추가한 마이그레이션 |
| --- | --- |
| `wallet`, `point_ledger`, `point_hold`, `point_hold_detail`, `point_charge`, `point_charge_usage`, `point_refund_request`, `point_refund`, `settlement`, `exchange`, `exchange_account`, `exchange_payout` | `V202610121245` (이후 마이그레이션이 컬럼·제약 추가) |
| `mock_payout_transfer` | `V202610161700` |
| `billing_user` | `V202610162010` |
| (`wallet.wallet_type`·`fee_balance_hp`, 플랫폼 지갑 행, 원장 `FEE`·`EXCHANGE_FEE`) | `V202610301000` (#336) |
| (`ck_exchange_1`을 1 HP 이상으로) | `V202610301200` (#453) |
| `hold_void` (보류 무효화 기록) | `V202610301600` (#479) |
| (`idx_point_hold_2 (reference_type, updated_at, id)`, 대사 조회) | `V202611031200` (#577) |
| (`idx_wallet_1`, `idx_point_ledger_5`, 잔액 대사 대상) | `V202611031800` (#607) |
| `wallet_withdrawal` (탈퇴 기록), (`wallet.closed_by_withdrawal_id`·`closed_at`, `ck_wallet_closed`, `idx_wallet_2`) | `V202611032000` (#623) |
| (`ck_wallet_closed`를 "탈퇴 작업 ID가 있으면 닫은 시각도 있다"로, `idx_wallet_2`를 `closed_at` 기준으로) | `V202611041200` (#674, `BD-145`) |
| `event_publication`, `event_publication_archive` (Modulith 발행 기록) | #471(`V202610301400`)에서 지웠다가 #497에서 다시 쓴다. Flyway가 아니라 Modulith 스키마 초기화가 만든다(엔티티 아님). `V202610301400`의 첫 줄 주석("지갑은 Spring Event를 발행하지도 받지도 않는다")은 #471 당시 기준이다. 적용된 Flyway는 주석도 체크섬에 들어가므로 고치지 않는다(#564, `BD-130`) |

- 나머지 마이그레이션(`V202610131930`~`V202610162100`)은 기존 테이블의 제약·컬럼 변경이다.
- `apps/billing-app/AGENTS.md` 3장의 "12개 테이블" 목록은 최초 마이그레이션 기준이다. 이후 결정(`BD-99`·`BD-103`·`BD-114`·`BD-125`·`BD-141`·`BD-145`)으로 테이블·컬럼이 늘었다.
- Flyway 설정: `baseline-on-migrate`, `baseline-version: 0`, `out-of-order: true`.

### 8.3 Database 분리

루트 `AGENTS.md` 2·3장의 "모듈마다 database" 규칙이다. 목표의 원본은 인프라 문서 10장이다.

| 환경 | database | 스키마 | 근거 |
| --- | --- | --- | --- |
| prod (k3s) | `billing` (계정 `billing`, `REVOKE CONNECT ... FROM PUBLIC`) | `billing` | `apps/billing-app/k8s/base/kustomization.yaml`의 `DB_URL=…/billing`, `k8s/platform/base/postgres.yaml` |
| 로컬 (Compose) | 공용 database 1개(`POSTGRES_DB`) | `billing` | `docker/compose.yaml`, `application-local.yaml` |
| 테스트 (Testcontainers) | 공용 PostgreSQL 컨테이너 1개(test-support) | `billing` | `common/test-support` |

- (#552 확인) prod로 옮긴 데이터는 없다. Compose 운영 DB는 #440에서 지웠고, Flyway가 빈 `billing` database에 스키마를 처음부터 만든다. 기존 회원의 지갑 회원·지갑은 prod 첫 배포 때 Backfill로 채운다(`BD-132`, `docs/deploy/k8s.md` "billing-app 첫 배포"). connection은 Hikari 10 명시, 전체 최악 80/100이다(`BD-133`, `database.md` 5항).

| 항목 | 현재 | 남은 검토 |
| --- | --- | --- |
| 다른 모듈 테이블과의 관계 | FK·JOIN 없음(코드 기준, `BillingSchemaTest`) | — |
| 모듈 간 트랜잭션 | 없음. 강의·출연·회원은 내부 HTTP API로 부르고 요청 하나가 지갑 트랜잭션 하나다(`BD-128`, `BD-143`) | — |
| 모듈 간 락 순서 `BD-72` | 폐기(`BD-142`) | 지갑 안 락 순서만 남는다(5.2) |
| Flyway 이력 | `billing` 스키마의 `flyway_schema_history`, baseline 0 | — |
| Modulith 이벤트 기록 | `billing.event_publication`·`event_publication_archive`(#497, 지갑 이벤트 outbox) | — |
| connection | 앱별 pool | 총 connection = (앱 수 + surge pod 수) × pool 크기. `max_connections`와 함께 다시 계산 |
| Testcontainers | 공용 PostgreSQL 컨테이너 1개, 스키마 `billing` | 앱 test가 자기 database만 띄울지(TBD) |

### 8.4 오류 코드

`BillingErrorCode` 73개(2026-11-06 `dev` 40fa9b25 기준으로 셈)를 코드 순서대로 적는다. 원본은 코드다. 응답 형식(ProblemDetail + `code` = enum 이름)과 500 코드를 `INTERNAL_ERROR`로 바꾸지 않는 이유는 `BD-105`·`BD-106`, 내부 API 실패 코드와의 분류 대조는 `BD-113`(`BillingFailureCodeContractTest`)에 있다. 역할 검사(`@PreAuthorize`) 실패는 이 표가 아니라 global 공통 코드다(로그인 403 `FORBIDDEN`, 비로그인 401 `UNAUTHORIZED`). 500 코드는 응답 `detail`이 일반 문구이고 원인은 `code`와 error 로그로 구분한다.

| 코드 | HTTP 상태 | 메시지 | 나는 상황 |
| --- | --- | --- | --- |
| `WALLET_NOT_FOUND` | 404 | 지갑을 찾을 수 없습니다. | 거래·대사 대상 지갑 없음 |
| `ORIGINAL_LEDGER_NOT_FOUND` | 404 | 역분개할 원장 항목을 찾을 수 없습니다. | 역분개 원본 원장 없음 |
| `INVALID_REVERSAL` | 400 | 역분개 요청이 원본 원장 항목과 맞지 않습니다. | 역분개 정책 위반 |
| `INVALID_POINT_AMOUNT` | 400 | HP 금액이 올바르지 않습니다. | 0 HP 줄, 0 이하 이동 금액, 0 이하 보류·부분 반환·부분 지급 금액, 음수 위약금 반환액·지급액, 반환액 + 지급액 long 초과 |
| `INSUFFICIENT_BALANCE` | 409 | HP 잔액이 부족합니다. | 잔액이 음수가 되는 감소 |
| `BALANCE_OVERFLOW` | 409 | HP 잔액이 허용 범위를 넘습니다. | Long 범위 초과 |
| `IDEMPOTENCY_KEY_CONFLICT` | 409 | 같은 작업 키로 다른 내용의 원장 기록이 요청되었습니다. | 같은 작업 키에 다른 내용, 같은 참조 대상 보류에 다른 지갑·금액, 같은 보류·같은 반환 요청 ID에 다른 금액, 같은 취소 ID에 다른 반환액·지급액·받는 회원·원천 |
| `HOLD_NOT_FOUND` | 404 | HP 보류를 찾을 수 없습니다. | 반환할 참조 대상의 보류 없음 |
| `HOLD_OWNER_MISMATCH` | 409 | 요청한 회원의 HP 보류가 아닙니다. | 요청 회원의 지갑 보류가 아님 |
| `HOLD_ALREADY_CLOSED` | 409 | 남은 보류 HP가 없어 더 처리할 수 없습니다. | 남은 보류가 없는(끝난) 보류에 새 반환 요청 |
| `HOLD_VOIDED` | 409 | 보류가 무효화된 대상이라 HP를 보류할 수 없습니다. | 보류 무효화 기록이 있는 참조 대상에 보류 생성 (`HoldCreateUseCase`, #479) |
| `RELEASE_AMOUNT_EXCEEDS_HELD` | 409 | 반환 금액이 남은 보류 HP보다 많습니다. | 남은 보류보다 큰 반환 |
| `PAYOUT_AMOUNT_EXCEEDS_HELD` | 409 | 지급 금액이 남은 보류 HP보다 많습니다. | 남은 보류보다 큰 지급 |
| `PENALTY_BASE_EXCEEDS_HELD` | 409 | 위약금 정산 기준액이 남은 보류 HP보다 많습니다. | 위약금 정산 기준액(호스트 반환액 + 게스트 지급액)이 남은 보류보다 큼 |
| `PENALTY_AMOUNT_ZERO` | 400 | 위약금이 0 HP가 되는 정산 기준액은 정산할 수 없습니다. | 위약금 정산의 게스트 지급액이 0 HP (#145부터 출연이 보낸 지급액 기준) |
| `PAYOUT_TO_PAYER` | 400 | 보류한 회원 자신에게는 지급할 수 없습니다. | 보류한 회원 자신에게 지급 |
| `PAYOUT_SOURCE_MISMATCH` | 400 | 지급 원천이 보류의 거래 대상과 맞지 않습니다. | 지급 원천이 보류의 거래 대상과 맞지 않음 |
| `PAYOUT_ALREADY_SETTLED` | 409 | 같은 출연 계약·주문 항목(또는 같은 출연 요청)에 이미 지급했습니다. | 같은 지급 원천(출연 계약·주문 항목)에 어느 보류에서든 지급이 이미 있음(유형 무관), 출연 요청(1:1) 보류에 지급이 이미 있음 |
| `CAPTURE_AMOUNT_EXCEEDS_HELD` | 409 | 확정 차감 금액이 남은 보류 HP보다 많습니다. | 확정 차감 금액이 남은 보류(충전 건 지정이면 그 배분의 남은 보류)보다 큼 (#241) |
| `HOLD_TYPE_NOT_SUPPORTED` | 400 | 이 보류 종류에서는 할 수 없는 처리입니다. | MIXED 보류 확정 차감, CHARGE_ONLY가 아닌 보류에 충전 건 지정 (#241) |
| `HOLD_CHARGE_NOT_FOUND` | 400 | 보류에 배분되지 않은 충전 건입니다. | 지정한 충전 건이 보류에 배분되지 않음 (#241) |
| `CHARGE_UNAVAILABLE` | 503 | 지금은 HP 충전을 사용할 수 없습니다. | TossPayments 시크릿 키·결제키 암호화 키가 설정되지 않음 (#224) |
| `CHARGE_AMOUNT_INVALID` | 400 | 충전 금액은 1,000 HP 이상, 1,000 HP 단위여야 합니다. | 충전 금액이 1,000 미만이거나 1,000 단위가 아님 (#224) |
| `CHARGE_NOT_FOUND` | 404 | 충전 주문을 찾을 수 없습니다. | 충전 주문이 없거나 다른 회원의 주문 (#224) |
| `CHARGE_AMOUNT_MISMATCH` | 400 | 결제 금액이 충전 주문 금액과 다릅니다. | 승인 요청 금액 ≠ 저장한 주문 금액 (PG 호출 전 거절, #224) |
| `CHARGE_PAYMENT_KEY_CONFLICT` | 409 | 다른 결제로 처리 중이거나 처리된 충전 주문, 또는 이미 사용된 결제입니다. | 다른 주문에 이미 쓴 결제키, 처리 중·끝난 주문에 다른 결제키 (#224) |
| `CHARGE_ALREADY_FAILED` | 409 | 결제가 승인되지 않아 끝난 충전 주문입니다. 새 충전 주문으로 다시 결제해 주세요. | 승인 실패로 끝난 주문에 다시 승인 요청 (#224) |
| `CHARGE_PAYMENT_REJECTED` | 400 | 결제가 승인되지 않았습니다. | PG가 승인하지 않음(4xx·만료·취소). detail = PG 메시지, 속성 `pgCode` (#224) |
| `REFUND_UNAVAILABLE` | 503 | 지금은 HP 환불을 사용할 수 없습니다. | TossPayments 시크릿 키·결제키 암호화 키가 없어 환불 접수 불가 (#260) |
| `REFUND_AMOUNT_TOO_SMALL` | 400 | 환불 금액은 1 HP 이상이어야 합니다. | 환불 금액이 1 HP보다 작음 (#453, 이전 `REFUND_AMOUNT_INVALID`: 1,000 미만·1,000 단위 아님, #260) |
| `REFUND_IDEMPOTENCY_KEY_INVALID` | 400 | Idempotency-Key 헤더는 영문·숫자·-·_ 1~64자여야 합니다. | `Idempotency-Key` 헤더 형식 오류(영문·숫자·-·_ 1~64자) (#260) |
| `REFUND_NOT_FOUND` | 404 | 환불 요청을 찾을 수 없습니다. | 환불 요청이 없거나 다른 회원의 요청 (#260) |
| `REFUND_NOT_CANCELABLE` | 409 | 환불이 이미 진행되었거나 끝나 취소할 수 없습니다. | PG 취소를 시작했거나 끝난 요청을 회원이 취소 (#260) |
| `EXCHANGE_UNAVAILABLE` | 503 | 지금은 환전 신청을 사용할 수 없습니다. | 계좌번호 암호화 키가 없어 환전 신청 불가 (`ExchangeFacade`, #285) |
| `EXCHANGE_AMOUNT_TOO_SMALL` | 400 | 환전 금액은 1 HP 이상이어야 합니다. | 환전 금액이 1 HP보다 작음 (#285, #453) |
| `EXCHANGE_IDEMPOTENCY_KEY_INVALID` | 400 | Idempotency-Key 헤더는 영문·숫자·-·_ 1~64자여야 합니다. | 환전 신청 `Idempotency-Key` 헤더 형식 오류(영문·숫자·-·_ 1~64자) (#285) |
| `EXCHANGE_NOT_FOUND` | 404 | 환전 신청을 찾을 수 없습니다. | 환전 신청이 없거나 다른 회원의 신청 (#285) |
| `EXCHANGE_NOT_CANCELABLE` | 409 | 관리자 승인 전인 환전 신청만 취소할 수 있습니다. | REQUESTED가 아닌(관리자 승인·거절 뒤) 환전을 회원이 취소 (#285) |
| `EXCHANGE_ALREADY_REVIEWED` | 409 | 이미 처리되었거나 승인·거절할 수 없는 상태의 환전 신청입니다. | REQUESTED가 아닌 환전을 관리자가 승인·거절 (`ExchangeReviewUseCase`, #294) |
| `EXCHANGE_APPLICANT_NOT_SYNCED` | 409 | 신청자의 회원 정보가 아직 Billing에 반영되지 않아 승인할 수 없습니다. | 승인할 때 신청자의 지갑 회원(`billing_user`)이 없음 (#294) |
| `EXCHANGE_APPLICANT_NOT_ELIGIBLE` | 409 | 신청자가 정상 상태의 인플루언서가 아니어서 승인할 수 없습니다. | 승인할 때 신청자가 정상 상태의 인플루언서가 아님 (#294) |
| `EXCHANGE_REJECTION_REASON_INVALID` | 400 | 거절 사유는 앞뒤 공백을 빼고 1~500자여야 합니다. | 거절 사유가 공백 제외 1~500자가 아님 (#294) |
| `EXCHANGE_NOT_PAYABLE` | 409 | 관리자가 승인한 환전 신청만 송금할 수 있습니다. | REQUESTED·REJECTED·CANCELED 환전을 송금 (`ExchangePayoutUseCase`, #304) |
| `EXCHANGE_PAYOUT_NOT_DUE` | 409 | 정산 예정일 전에는 환전 송금을 할 수 없습니다. | APPROVED 환전의 정산 예정 시각 전 송금 (#304) |
| `EXCHANGE_PAYOUT_UNAVAILABLE` | 503 | 지금은 환전 송금을 처리할 수 없습니다. | 송금 Client를 쓸 수 없음(Mock 은행 꺼짐 = prod). 송금 관리자 API 503 (#304) |
| `EXCHANGE_PAYOUT_NOT_RETRYABLE` | 409 | 송금이 확정 실패한 환전만 다시 송금할 수 있습니다. | 송금 실패(FAILED)가 아닌 환전을 관리자가 재시도 (#304) |
| `EXCHANGE_PAYOUT_NOT_FINALIZABLE` | 409 | 송금 실패가 확정된 환전만 최종 실패로 종료할 수 있습니다. | 송금 실패(FAILED)가 아닌 환전을 관리자가 최종 실패로 종료 (#304) |
| `EXCHANGE_PAYOUT_NOT_CHECKABLE` | 409 | 결과를 다시 확인할 송금 시도가 없습니다. | 결과가 확정되지 않은 마지막 송금 시도(PROCESSING·UNKNOWN·MANUAL_REVIEW_REQUIRED)가 없는데 관리자가 재확인 (#304) |
| `EXCHANGE_FINAL_FAILURE_REASON_INVALID` | 400 | 최종 실패 사유는 앞뒤 공백을 빼고 1~500자여야 합니다. | 최종 실패 사유가 공백 제외 1~500자가 아님 (#304) |
| `EARNING_SOURCE_UNAVAILABLE` | 503 | 지금은 수익 내역을 조회할 수 없습니다. 잠시 후 다시 시도해 주세요. | 강의·출연 내부 API 중 하나라도 조회 실패(Client 미설정·연결·5xx·회로 열림·응답 계약 불일치). 부분 결과 없이 전체 실패 (#295) |
| `EARNING_SOURCE_REJECTED` | 503 | 지금은 수익 내역을 조회할 수 없습니다. | 강의·출연 내부 API가 4xx로 거절(내부 API 키·요청 계약 불일치). 재시도해도 같아 운영자 확인 (#295) |
| `EARNING_PAGE_TOO_DEEP` | 400 | 수익 목록은 앞쪽 1,000건까지만 조회할 수 있습니다((page + 1) × size ≤ 1,000). | 수익 목록 `(page + 1) × size`가 1,000 초과 (`EarningQueryUseCase`, #295) |
| `MEMBER_SOURCE_UNAVAILABLE` | 503 | 지금은 회원 정보를 조회할 수 없습니다. 잠시 후 다시 시도해 주세요. | Backfill 중 회원 내부 API 조회 실패·응답 계약 불일치. 실행 전체 중단, 반영한 회원은 유지 (#324) |
| `MEMBER_SOURCE_REJECTED` | 503 | 지금은 회원 정보를 조회할 수 없습니다. | Backfill 중 회원 내부 API가 4xx로 거절(내부 API 키·요청 계약 불일치) (#324) |
| `BACKFILL_ALREADY_RUNNING` | 409 | 회원 정보 Backfill이 이미 실행 중입니다. | 다른 Backfill·같은 실행 락 작업이 실행 중 (`JobLock`, #324·#598) |
| `BACKFILL_LOCK_UNAVAILABLE` | 503 | 지금은 회원 정보 Backfill을 시작할 수 없습니다. 잠시 후 다시 시도해 주세요. | 실행 락(advisory lock) 시도 실패(DB 연결 불가 등). 실행하지 않았으므로 다시 시도 가능 (#598, BD-138) |
| `WALLET_MEMBER_NOT_FOUND` | 404 | 회원 정보가 아직 지갑에 반영되지 않았습니다. 잠시 후 다시 시도해 주세요. | 충전 주문·승인, 환불 신청, 환전 신청 때 지갑 회원(`billing_user`)이 아직 없음 (`WalletMemberCheck`, #537) |
| `WALLET_MEMBER_NOT_ALLOWED` | 403 | 회원 상태나 역할 때문에 이 신청을 할 수 없습니다. | 위 신청 때 지갑 회원 상태·역할이 허용되지 않음(탈퇴, 환전은 정상 인플루언서만 등) (`WalletMemberCheck`, #537, BD-131) |
| `WALLET_CLOSED` | 409 | 탈퇴한 회원의 지갑이에요. | 탈퇴·파기로 닫힌 지갑에 충전 주문·충전 승인 시작·새 보류(환불·환전 신청 포함) (#623) |
| `WITHDRAWAL_ALREADY_REOPENED` | 409 | 이미 다시 열기를 처리한 탈퇴 작업이라 지갑을 닫을 수 없습니다. | 다시 열기를 이미 받은 탈퇴 작업의 닫기 (#623) |
| `MOCK_PAYOUT_FORBIDDEN` | 403 | 가상 송금 API 접근 키가 올바르지 않습니다. | Mock 은행 API의 `X-Mock-Payout-Key`가 틀림 (local·test 전용, #288) |
| `MOCK_PAYOUT_INVALID_REQUEST` | 400 | Idempotency-Key와 지급 요청 ID가 같은 UUID여야 합니다. | Mock 은행 송금의 `Idempotency-Key`와 지급 요청 ID가 같은 UUID가 아님 (#288) |
| `MOCK_PAYOUT_IDEMPOTENCY_CONFLICT` | 409 | 같은 지급 요청 ID로 다른 내용의 송금이 요청되었습니다. | 같은 지급 요청 ID에 다른 환전 ID·금액·은행·계좌번호·예금주 (`MockPayoutBank`, #288) |
| `MOCK_PAYOUT_NOT_FOUND` | 404 | 가상 송금 요청을 찾을 수 없습니다. | 조회한 지급 요청 ID의 Mock 송금 기록 없음(SERVER_ERROR 시나리오 뒤 조회 포함) (#288) |
| `MOCK_PAYOUT_UNAVAILABLE` | 503 | 지금은 가상 송금을 처리할 수 없습니다. | 결제키 암호화 키가 없어 Mock 송금 불가 (`MockPayoutBank`, #288) |
| `MOCK_PAYOUT_SERVER_ERROR` | 500 | 가상 송금 처리 중 오류가 발생했습니다. | SERVER_ERROR 시나리오. 기록하지 않고 끝난 5xx (#288) |
| `HP_LOCK_TIMEOUT` | 503 | 지금은 HP를 처리할 수 없습니다. 잠시 후 다시 시도해 주세요. | 지갑·보류·충전 건 등의 락을 `lock_timeout`(3초) 안에 얻지 못함(PostgreSQL 55P03). 롤백되어 HP가 움직이지 않았으므로 같은 요청을 다시 보낸다 (#273) |
| `CHARGE_PRINCIPAL_MISMATCH` | 500 | HP 처리 중 오류가 발생했습니다. | 배분 가능 충전 원금 합계 ≠ 지갑 충전 HP 가용 잔액, 가용 원금을 넘는 배분, 충전 금액을 넘는 복원·가용 원금 상태가 아닌 충전 건에 복원 |
| `HOLD_STATE_INCONSISTENT` | 500 | HP 처리 중 오류가 발생했습니다. | 보류(반환 상세) 없이 원장만 있음, 보류와 원장 줄 수·충전 원금 배분·상세 합계 불일치, 상세 재생 결과 ≠ 배분 누적값, 지급 상세 없이 지급 건·원장만 있음, 위약금 기록 일부만 있음 |
| `CHARGE_STATE_INCONSISTENT` | 500 | HP 처리 중 오류가 발생했습니다. | PG 승인 결과가 주문과 다름(주문·결제키·금액). 충전하지 않고 PROCESSING + 사유 (#224) |
| `REFUND_STATE_INCONSISTENT` | 500 | HP 처리 중 오류가 발생했습니다. | 요청·개별 환불·보류 기록 불일치(완료액 초과, 요청 진행 중인데 앞 건이 끝난 실패 상태, 반환한 건의 PG 성공 등) (#260) |
| `EXCHANGE_STATE_INCONSISTENT` | 500 | HP 처리 중 오류가 발생했습니다. | 환전 신청·수익 HP 보류·송금 시도 기록 불일치(보류 금액·종류 불일치 등) (#285) |
| `EXCHANGE_ACCOUNT_UNREADABLE` | 500 | HP 처리 중 오류가 발생했습니다. | 저장된 환전 계좌번호 암호문 복호화 실패(키 없음·키 변경·암호문 손상). 같은 신청인지 판단할 수 없어 거절 (`ExchangeAccountCipher`, #285) |

## 9. 설정과 환경 변수

| 설정 | 기본값 | 없을 때 |
| --- | --- | --- |
| `HAPBANG_INTERNAL_API_KEY` → `hapbang.internal-api.key` | 빈 값(local `local-placeholder`) | **기동 실패** (`InternalApiProperties`) |
| `MEMBER_API_BASE_URL`, `LECTURE_API_BASE_URL`, `APPEARANCE_API_BASE_URL` | 없음(local 8081·8084·8083) | dev·prod에서 값이 없으면 placeholder 해석 실패로 기동 실패(추정, 미확인). Client가 없으면 해당 기능 503 |
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` (dev·prod) | 없음 | 기동 실패 |
| `TOSS_PAYMENTS_SECRET_KEY` | 빈 값 | 충전·환불 503 |
| `BILLING_PAYMENT_KEY_ENCRYPTION_KEY`, `BILLING_PAYMENT_KEY_HMAC_KEY` | 빈 값 | 둘 다 없으면 충전·환불·환전 신청·Mock 송금 503. 하나만 있거나 형식이 틀리거나 두 값이 같으면 **기동 실패** |
| `hapbang.billing.mock-payout.enabled` + 활성 프로필 전부 local/test | local yaml만 true | 송금 불가, 정산일 배치 건너뜀, 송금 관리자 API 503 |
| `hapbang.scheduling.enabled` | true | 배치 6개 미등록 |
| `hapbang.billing.rate-limit.write`·`read`·`earning` (#627) | 10·60·20 (회원 × API마다 분당) | 기본값. 1 미만이면 **기동 실패** |
| 서버 포트 | local 8082 | — |

키 값은 코드·로그·문서에 남기지 않는다 ([`docs/deploy/env-vars.md`](../deploy/env-vars.md)).

## 10. 알려진 제약 (As-Is)

해소된 제약(옛 L1~L5·L7·L10: `BillingPort` 원격 구현, 회원 이벤트 JVM 경계, 앱 간 로그인, 배치 분산 락, Backfill 동시 실행, `lock_timeout`, 회원 상태 확인)은 지웠다. 해소 경위는 11장 이력과 `BD-128`·`BD-123`·`BD-131`·`BD-138`·`BD-108`에 있다.

| # | 제약 | 영향 | 관련 |
| --- | --- | --- | --- |
| L6 | 잔액 대사는 최근 72시간 안에 바뀐 지갑만 매일 자동으로 한다(6.2). 관리자 API 없음 | 창보다 오래 바뀌지 않은 지갑의 불일치는 수동으로만 찾는다. 창 안의 불일치·실패는 Grafana alert "지갑 잔액 대사 불일치·실패"로 Slack에 온다(#781, [구성한 alert](../architecture/infrastructure/observability.md#구성한-alert-781)) | `BD-20`, `BD-139` |
| L8 | 환전 정산일 공휴일 미반영 | 잠정 예정일 | `BD-96` |
| L9 | 실제 송금은 Mock(local·test)만 | prod에서 송금 기능 503, 정산일 배치 건너뜀 | policy.md 2장, `BD-100` |
| L11 | 닫힌 지갑에 들어온 반환·지급 HP는 환불·환전할 수 없어 회원이 복구하기 전까지 묶인다 | warn 로그와 gauge `hapbang.billing.wallet.closed.with.balance`로만 안다. alert는 없다 | `BD-141`, `BD-145` |
| L12 | 파기 때 처리 중인 환전의 계좌는 지우지 않는다. 이벤트는 다시 오지 않는다 | 송금이 끝난 뒤 운영자가 지워야 한다(error 로그로 안다) | `BD-144` |
| L13 | Graceful shutdown 순서 제어(`DrainCoordinator`)가 없다 | 배포·종료 중 진행 중인 배치·요청은 각 UseCase의 멱등성·복구 배치에 맡긴다 | 7.2 |

## 11. 갱신 이력

| 날짜 | 기준 커밋 | 반영 내용 |
| --- | --- | --- |
| 2026-10-29 | 5a4dc1a | 최초 작성 (#356 독립 앱 구조 기준) |
| 2026-10-29 | da834b1 | dev pull 반영. billing-app·`common/shared` 코드 변경 없음. #369 최종 인프라 결정(JWT·Envoy·Resilience4j·Kafka outbox/inbox·database 분리 순서·replica 1개 배치)을 7.2·8.3·6.1·10장에 반영. Billing 이벤트 발행 없음을 7.1에 추가 |
| 2026-10-30 | d8d6c25 | #336 플랫폼 지갑·환전 수수료 수입 반영(3.1, INV-1, 5.3, 8.1, 8.2). 결정은 `BD-114`~`BD-118` |
| 2026-10-30 | 3d6b61c | #453 환불·환전 1원 단위, 송금액 버림(송금 = floor(신청 × 90%)), 송금액 0원 환전은 은행 없이 완료(6장 환전 정산 행, 8.2). 결정은 `BD-119`~`BD-122` |
| 2026-10-30 | 2ba9624 | #428 Kafka 소비 규칙(#339) 반영: 회원 이벤트 수신(7.2)을 `eventId` inbox에서 자연 키 멱등·소비 그룹 둘·그룹별 DLT로 고침 |
| 2026-10-30 | f8021a3 | #430 회원 이벤트 Kafka 소비 구현 반영(7.1 회원 → Billing, 7.2 남은 것) |
| 2026-10-30 | c58fb0f | #471 Spring Event 제거: Modulith 이벤트 기록 설정·`event_publication` 테이블 삭제(8.2·8.3, 7.2) |
| 2026-10-30 | 33367bd | #479 보류 무효화 내부 API(6장, 5.3, 8.1, 8.2). 결정은 `BD-125`~`BD-126` |
| 2026-10-31 | 454bfd2 | #486 shared `BillingPort`·`BillingPortAdapter` 삭제(1.1, 2, 7.1, 8.3, L1) |
| 2026-11-01 | aeb1bdb | #494 shared Command → billing 내부 Command(`Payment*Command`) 변환(1.1, 5.1). 결정은 `BD-129` |
| 2026-11-02 | 437411c | #497 지갑 이벤트 발행(7.1·7.2·8.2·8.3). 결정은 `BD-130` |
| 2026-11-02 | 81cfdd48 | #552 prod 첫 배포 준비: 관리 포트 8082·Prometheus, Hikari 10, 데이터 이관 없음, Backfill 지갑 생성(8.3, BD-132·BD-133) |
| 2026-11-02 | 35e7aae1 | #562 나가는 호출 circuit breaker(7.1, BD-134) |
| 2026-11-02 | 35e7aae1 | #564 DLT 복구 절차(BD-136), migration 주석 정정(8.2), capture 비노출(BD-135) |
| 2026-11-03 | 8e7fa8b7 | #577 서비스 간 HP 대사용 보류·지급 조회 내부 API(7.1, BD-137) |
| 2026-11-03 | 2cfe5307 | #598 배치·Backfill 실행 락(6.1, 9, L4·L5, BD-138) |
| 2026-11-03 | 84f504c4 | #607 변경된 지갑의 잔액 대사 배치(2, 6.1, 6.2, 9, L6, BD-139) |
| 2026-11-03 | 90938379 | #627 사용자 API 회원별 rate limit(8.1 rate limit 행, 9, BD-140) |
| 2026-11-04 | b0731110 | #655 JVM 분리 재검토 마무리: 모듈 간 락 순서 폐기(5.2, BD-142), 원격 호출 트랜잭션 계약(7.2, 8.3, BD-143), 해소된 제약 L2·L3·L7·L10 표시, 7.2·8.3을 현재 상태로 고침 |
| 2026-11-06 | 40fa9b25 | Billing 문서 정리: #623 탈퇴 지갑 닫기·다시 열기(`wallet_withdrawal`, `WALLET_CLOSED`, 내부 API close·reopen, BD-141)와 #674 파기(`WalletMaskingUseCase`, `closed_at`, `V202611041200`, BD-144·BD-145) 반영(1.1, 1.2, 3, INV-9, 5, 6, 7.1, 8.1, 8.2, L11·L12). 테이블 16개 + Modulith 2개·Flyway 19개(8.2), Enum(`PointType` FEE, `EXCHANGE_FEE`, `WalletType`) 보정, 아키텍처 테스트 둘(2), Backfill 동시 실행 방지를 실행 락으로 고침(6), 대사 조회·탈퇴 내부 API를 8.1에 추가, 환경별 database·스키마 구분(8.2·8.3), 7.2를 남은 목표만으로 줄임, 해소된 제약 행 삭제(10), 용어 정리("가상 송금" → "환전 송금", "회원 복제본" → "지갑 회원") |
| 2026-11-06 | 40fa9b25 | 옛 PROGRESS·CONTEXT에서 옮김: 기능별 진입점 표(2.1, 18행), 오류 코드 표 73개(8.4, 옛 PROGRESS 5장 + 코드), 이벤트 발행 세부(토픽·헤더·재발행 설정, 7.1, 옛 CONTEXT 9.1). rate limit 14개·8.1 권한을 코드와 대조(차이 없음) |
