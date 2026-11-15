# Billing Module — AGENTS.md

> 문서 탐색: [지갑 문서 INDEX](../../docs/billing/INDEX.md)

이 문서는 합방 프로젝트의 Billing(지갑) 모듈에서 에이전트가 지킬 개발 규칙을 정의한다.

루트 `AGENTS.md`의 공통 아키텍처 및 코딩 규칙을 준수하며, Billing의 금융 정합성 관련 세부 규칙을 추가한다.

- 업무 규칙의 원본(SoT)은 `docs/policy.md`다. 결정·이유·이력은 `docs/billing/decisions.md`(`BD-*`)에 있고, 이 문서는 규칙만 적고 해당 BD 번호를 참조한다. 이력·배경은 여기에 적지 않는다.
- `docs/policy.md`는 구현보다 먼저 바뀔 수 있다. 정책과 코드가 다르면 오류로 단정하지 말고 보고한다.
- 작업 전에 `docs/billing/decisions.md`, `docs/billing/module.md`(현재 구현), `apps/billing-app/PROGRESS.md`(진행 현황)를 읽고, Issue를 마치면 decisions.md와 PROGRESS.md를 갱신한다.

## 1. 모듈 책임

Billing은 다음 기능을 담당한다.

- Wallet: 지갑 및 잔액 조회
- Ledger: HP 원장 및 잔액 정합성
- Hold: HP 보류·반환·부분 처리
- Charge: TossPayments 테스트 충전
- Refund: 충전 HP 현금 환불
- Settlement: 출연·강의 수익 HP 지급
- Exchange: 수익 HP 환전 신청 및 환전 송금 처리

다른 도메인의 Entity·Repository를 직접 참조하지 않는다.

회원은 `Long userId`로 참조하고, 다른 database와 물리적 FK 및 JOIN을 생성하지 않는다.

## 2. 프로젝트 및 DB 구조

- Gradle 프로젝트: `:billing-app` (`apps/billing-app`, 독립 실행 앱)
- PostgreSQL: database `billing`, 스키마 `billing` (prod 기준. 로컬·테스트는 공용 database 안의 `billing` 스키마, `docs/billing/module.md` 8.3)
- Entity 및 Enum 패키지: `com.hapbang.billing.domain`
- Repository 패키지: `com.hapbang.billing.out`
- 테이블 생성과 변경은 Flyway를 사용한다.
- Hibernate `ddl-auto=validate` 정책을 유지한다.
- 기존 Flyway 마이그레이션은 수정하지 않는다.
- `common/shared`, 다른 앱의 코드를 임의로 변경하지 않는다. `global`은 이 앱 안의 패키지(`com.hapbang.billing.global`)다.

## 3. 테이블

현재 테이블 목록·수와 추가한 마이그레이션은 `docs/billing/module.md` 8.2가 원본이다. 필드·자료형·제약 조건은 Flyway 마이그레이션(`apps/billing-app/src/main/resources/db/migration`)에서 확인한다.

- 기획서 ERD에 없던 테이블은 결정으로 추가했다: `mock_payout_transfer`(BD-99), `billing_user`(BD-103), `hold_void`(BD-125), `wallet_withdrawal`(BD-141), `wallet_reconciliation_run`·`wallet_reconciliation_run_wallet`(BD-146), `promotion_funding`·`promotion_withdrawal`·`budget_reservation`·`budget_release`(BD-147~BD-150), `promotion_hold`(BD-157~BD-160). Spring Modulith 발행 기록 테이블은 BD-130.
- 무엇을 기준 ERD로 삼을지는 미결이다(`docs/billing/decisions.md` 10장 "Billing 기준 ERD" 행). 임의로 정하지 않는다.

결정(`BD-*`) 없이 테이블이나 컬럼을 임의로 추가하지 않는다.

## 4. Entity 규칙

- PK는 `Long`과 `GenerationType.IDENTITY`를 사용한다.
- `@Table`에 테이블명과 스키마를 명시한다.
- Enum은 `EnumType.STRING`을 사용한다. 상수 이름이 DB 저장값이므로 바꾸지 않는다(`BillingEnumStorageTest`가 Flyway CHECK와 대조한다).
- Enum 상수마다 의미를 Javadoc(`/** ... */`)으로 적는다(BD-37). API 응답으로 나가는 Enum만 `@Schema` 설명을 더한다.
- 기본 생성자는 `protected`로 제한한다.
- 범용 `@Setter`와 `@Data`를 사용하지 않는다.
- Aggregate 사이는 객체 대신 ID(`Long`) 필드로 참조한다(BD-36). 필요한 엔티티는 Repository로 명시적으로 조회한다.
- 같은 Aggregate 안(보류 → 반환·지급 상세·충전 원금 배분, 환불 요청 → 환불, 환전 → 계좌·송금)만 `LAZY` 연관관계를 둔다.
- JPA 연관관계를 ID 필드로 바꿔도 DB FK·UNIQUE·CHECK는 Flyway 그대로 유지한다.
- 금융 Entity는 Hard Delete하지 않는다.
- 다른 도메인의 Entity를 JPA 연관관계로 연결하지 않는다.

## 5. 시간 처리

- 금융 거래 시각은 Java `Instant`를 사용한다.
- PostgreSQL 시간 컬럼은 `TIMESTAMPTZ`를 사용한다.
- KST 업무 날짜 계산에는 `Asia/Seoul` 시간대를 명시한다.
- 공통 `BaseEntity`는 `LocalDateTime`을 사용하므로 Billing 금융 Entity에는 그대로 상속하지 않는다.
- 공통 BaseEntity나 JPA Auditing 설정은 임의로 수정하지 않는다.

## 6. Wallet과 Ledger

- 지갑 유형(`WalletType`)은 회원 지갑 `MEMBER`, 플랫폼 지갑 `PLATFORM`(1개, `user_id` 없음, BD-114), 프로모션 지갑 `PROMOTION`(1개, `user_id` 없음, BD-147)이다. 플랫폼·프로모션 지갑은 닫지 않는다.
- HP 종류는 `CHARGE`(충전 HP)·`REVENUE`(수익 HP)이고, 플랫폼 지갑 전용으로 `FEE`(수수료 수입, 가용 칸만)가 있다(BD-115). 회원 지갑에는 FEE를, 플랫폼 지갑에는 충전·수익 HP를 기록하지 않는다(BD-117). 프로모션 지갑 전용으로 `PROMOTION`(프로모션 HP, 가용·예약·보류 칸)이 있고 다른 지갑에는 기록하지 않는다(BD-147). 지갑 유형별 HP 종류는 `WalletType.holds`, HP 종류별 칸은 `PointType.has`가 정한다.
- 가용·보류 잔액은 `AVAILABLE`과 `HELD`로 구분한다. 예약 칸 `RESERVED`(쿠폰 예산으로 묶은 HP)는 프로모션 HP만 있다(BD-147).
- `WALLET`의 잔액 칸(충전·수익 × 가용·보류 4개 + `fee_balance_hp` + 프로모션 HP 3개 `promotion_balance_hp`·`reserved_promotion_hp`·`held_promotion_hp`)은 원장 합계의 캐시다(BD-114·BD-115·BD-147).
- 프로모션 지갑의 충전·회수·예산 예약·해제 및 주문 항목 보류(`PROMOTION_HOLD`: 예약 − / 보류 +)·복구(`PROMOTION_RESTORE`: 보류 − / 예약 +)는 `PromotionLedgerOperations`로 만든다. 판매자 지급(`PAYOUT`)은 `HoldPayoutUseCase`가 구매자·프로모션 보류 차감과 판매자 수익 HP 증가를 합산한다. 모두 `LedgerRecordUseCase`로 같은 트랜잭션에서 기록한다(BD-148·BD-157~BD-160). 쿠폰 예산 예약(`budget_reservation`)의 총액 = 남은 + 보류 중 + 지급됨 + 해제됨이고, 예약 칸 = Σ 남은 예산, 보류 칸 = Σ 보류 중이다(BD-156). 지갑 대사가 이 불변식을 함께 본다.
- 원장(`POINT_LEDGER`)은 append-only다.
- 기존 원장 행을 수정하거나 삭제하지 않는다.
- 정정은 역분개를 통해 신규 원장 행으로 기록한다.
- 지갑의 잔액과 원장 기록은 동일 DB 트랜잭션에서 변경한다.
- 지갑 변경 시 `PESSIMISTIC_WRITE` 기반 비관적 락을 적용한다.
- 두 지갑 이상을 변경할 때는 지갑 ID 순서대로 락을 획득한다.

## 7. 멱등성

- 동일 비즈니스 요청은 한 번만 처리한다.
- 같은 멱등키에 서로 다른 요청 내용을 사용할 수 없다.
- `POINT_LEDGER.operation_key`는 하나의 비즈니스 작업에 속한 원장 기록을 묶는다.
- `operation_key`는 단독 UNIQUE로 지정하지 않는다.
- `POINT_LEDGER.idempotency_key`는 원장 항목별 고유 키이며 UNIQUE 제약을 적용한다.
- 원장 멱등키는 `{operationKey}:{lineNumber}` 방식으로 결정론적으로 생성한다.
- 멱등성은 애플리케이션 조회뿐 아니라 DB UNIQUE 제약으로도 보장한다.
- 같은 지갑의 보류 요청은 지갑 락으로 직렬화한다. 서로 다른 지갑의 같은 참조 대상 보류 요청은 지갑 락으로 막히지 않으므로 보류 멱등키 UNIQUE(`uk_point_hold_idempotency_key`)가 최종으로 보장하고, 그 위반은 `IDEMPOTENCY_KEY_CONFLICT`로 바꾼다(BD-16·BD-21).
- READ COMMITTED에서는 SELECT마다 그 시점에 커밋된 데이터를 본다(같은 트랜잭션 안에서도 스냅숏이 다르다). 함께 커밋되는 두 기록(보류·원장 등)으로 상태를 판정할 때는 나중에 쓰는 쪽을 먼저 읽지 말고, **먼저 읽은 기록이 보이면 나중에 읽는 기록도 반드시 보이는 순서**로 조회한다. 보류 생성은 원장 → 보류 순서로 읽는다(BD-27).
- 정상적인 멱등성 경쟁(다른 지갑이 같은 참조를 먼저 커밋)과 실제 원장·보류 불일치(보류 없이 원장만 있음)를 구분한다. 앞의 것은 `IDEMPOTENCY_KEY_CONFLICT`, 뒤의 것은 `HOLD_STATE_INCONSISTENT`다.

## 8. HP 사용·보류·반환·지급

- 수익 HP를 충전 HP보다 먼저 사용한다.
- 충전 HP는 오래된 충전 건부터 FIFO로 배분한다.
- `POINT_CHARGE.remaining_principal_hp`는 해당 충전 건의 가용 충전 원금이다.
- 충전 완료 건의 `remaining_principal_hp` 합계는 `WALLET.charge_balance_hp`와 일치해야 한다.
- 최초 보류는 `POINT_HOLD`와 `POINT_LEDGER`에 기록한다.
- 충전 HP 보류의 원금 배분은 `POINT_CHARGE_USAGE`에 저장한다.
- `POINT_HOLD_DETAIL`은 이후 부분 반환·부분 지급 이력을 기록한다.
- 반환 시 원래 사용한 HP 종류를 보존한다.
- 정상 지급된 수익 HP를 자동으로 강제 회수하지 않는다.
- 송신자 보류 HP 감소와 수령자 수익 HP 증가는 하나의 DB 트랜잭션에서 처리한다.
- 반환은 충전 HP 보류를 먼저, 부족분만 수익 HP 보류를 되돌리고, 충전 원금은 최초 배분의 역순(LIFO)으로 원래 충전 건에 복원한다(BD-29).
- 지급은 수익 HP 보류를 먼저, 부족분만 충전 HP 보류를 쓰고, 충전 원금은 최초 배분 순서(FIFO)로 소진한다. 소진한 원금은 보류한 회원에게 돌아가지 않는다. 받는 쪽에는 출처와 관계없이 수익 HP(가용)로 들어간다(BD-39).
- 보류한 회원 자신에게는 지급하지 않는다(같은 지갑 안에서 충전 HP가 수익 HP로 바뀌는 것을 막는다, BD-45).
- 호스트 일방 취소 위약금 (BD-56): **위약금 비율·금액 계산(반올림 포함)과 호스트 반환액은 출연(Appearance)이 정한다.** Billing은 비율을 계산하거나 검증하지 않고, 받은 호스트 반환액(`releaseAmountHp`, 0 이상)과 게스트 지급액(`payoutAmountHp`, 1 이상)을 한 트랜잭션에서 반환·지급한다. 두 금액의 합이 그 취소 건의 위약금 기준액이며 남은 보류 이하여야 하고(합계 long overflow는 `INVALID_POINT_AMOUNT`), 넘으면 `PENALTY_BASE_EXCEEDS_HELD`로 거절한다. 최소 출연료 수치는 Billing에 두지 않는다.
    - **게스트 지급액(위약금) 0 HP는 거절한다**(`PENALTY_AMOUNT_ZERO` 400). 음수 금액은 `INVALID_POINT_AMOUNT` 400 (BD-57).
    - **호스트 반환액 0 HP는 허용한다 (BD-58).** 이때 반환하지 않고 RELEASE 상세·원장을 만들지 않으며(0 HP 원장 금지), 게스트 지급(PAYOUT 상세·지급 건·원장)만 한 트랜잭션에 기록한다. 재요청은 반환 상세가 없으면 반환액 0으로 보고 대조해 처음 결과를 돌려준다(추가 지급 없음). 반환 상세 없이 반환 원장만 있으면 `HOLD_STATE_INCONSISTENT`다.
    - 멱등키 `PENALTY:{holdId}:{cancellationId}`(반환 `…:RELEASE`, 지급 `…:PAYOUT`). 같은 취소 ID에 반환액·지급액(각각)·받는 회원·원천이 다르면 `IDEMPOTENCY_KEY_CONFLICT`, 다른 취소 ID로 같은 출연 계약을 다시 지급하면 `PAYOUT_ALREADY_SETTLED`(`UNIQUE(source_type, source_id)`) (BD-48·BD-59·BD-43).
    - 멱등성·락 순서(지갑 ID 오름차순 → 보류 → 충전 건)·원장 대사·반환/지급 이력 재생 정책은 위약금에도 그대로 적용한다(BD-49).
- 지급 원천(`SETTLEMENT.source_type`·`source_id`)은 출연 계약·강의 주문 항목 단위다: 1:1은 출연 요청에서 성립한 출연 계약 ID, 1:N은 게스트별 출연 계약 ID, 강의는 주문 항목 ID. 게스트 회원 ID는 받는 사람 식별용이며 원천 ID로 쓰지 않는다. 한 원천은 보류·지급 유형(정상·위약금)과 관계없이 한 번만 지급하고(DB `UNIQUE(source_type, source_id)`, 앱 확인은 일찍 거절용), 1:1 출연 요청 보류의 지급은 1건이다. 출연 계약이 그 보류에 속하는지·계약 출연료는 출연이 검증한다(BD-41·BD-42·BD-43·BD-50).
- 한 보류의 반환·지급 상세를 처리 순서대로 위 규칙으로 재생한 결과가 배분(`POINT_CHARGE_USAGE`) 누적값과 같아야 한다. 이 규칙들은 바꾸지 않는다(바꾸면 기존 이력을 재구성할 수 없다, BD-33).
- 두 지갑 이상을 바꾸는 작업은 모든 지갑을 ID 오름차순으로 먼저 잠근 뒤 보류 → 충전 건 순서로 잠근다(BD-49).
- **서비스 간 HP 대사 조회 (BD-137)**: 대사 주체는 강의·출연이고 지갑은 조회만 준다. `POST /api/v1/internal/billing/holds/query`(참조 대상별)·`GET /api/v1/internal/billing/holds`(기간 안에 바뀐 보류, 키셋), 진입점 `HoldQueryFacade`(`ROLE_INTERNAL`, 읽기 전용). 응답은 HP 종류별 금액을 담는 예외이고, 지갑 Enum은 같은 이름의 shared Enum으로 준다(`BillingStatusContractTest`). 계약은 `docs/api/internal-billing.md` 4장.
- **보류 무효화 (BD-125·BD-126)**: 내부 API `POST /api/v1/internal/billing/hold-voids`(`HoldVoidUseCase`). 남은 보류는 전부 반환(반환 요청 ID `VOID`) → `RELEASED`, 끝난 보류 → `ALREADY_CLOSED`, 보류 없음 → `VOIDED`. 세 경우 모두 `hold_void`(참조 대상 UNIQUE)에 남기고, 기록이 있는 참조 대상의 보류(재요청 포함)는 `HOLD_VOIDED` 409로 거절한다. 보류와 같은 지갑 락으로 직렬화한다. 상세는 `docs/billing/CONTEXT.md` 6장. 참조 대상의 보류 상태 조회 API를 둘지는 미결이다(BD-127).

### 8.0 종류별 보류와 확정 차감 (BD-82~BD-86)

- **보류 종류(`point_hold.hold_type`)**: MIXED(수익 HP 우선 + 충전 HP, 강의·출연 = 내부 API 보류 전부), CHARGE_ONLY(충전 HP만, 환불 `POINT_REFUND_REQUEST`), REVENUE_ONLY(수익 HP만, 환전 `EXCHANGE`). 종류는 참조 유형으로 정해지고(`InternalHoldRequests.holdTypeOf`), 쓸 수 있는 종류의 잔액이 모자라면 `INSUFFICIENT_BALANCE`. 기존 MIXED 규칙은 바꾸지 않는다. DB `ck_point_hold_5`가 종류와 구성(CHARGE_ONLY는 수익 0, REVENUE_ONLY는 충전 0)을 막는다.
- **확정 차감(CAPTURE)**: 보류 HP를 다른 회원에게 옮기지 않고 최종 차감한다(받는 지갑 없음). 내부 API로 노출하지 않는다(BD-135). 원장은 보류 감소 줄만(환불 `REFUND`, 환전 `EXCHANGE`). 충전 HP는 지급과 같이 배분 순서(FIFO)로 소진하고 원금은 충전 건에 돌아가지 않는다. 전체·부분 허용, 남은 금액은 추가 확정 차감·반환 가능. 확정 차감만으로 끝나면 `CAPTURED`, 반환·지급과 섞이면 `MIXED`. **MIXED 보류(강의·출연)는 확정 차감하지 않는다**(`HOLD_TYPE_NOT_SUPPORTED`), 강의·출연 지급을 확정 차감으로 대체하지 않는다.
- **충전 건 지정 금액은 필수 (BD-85)**: `CaptureCommand.ofCharge`·`InternalReleaseCommand.ofCharge`는 금액을 반드시 받는다(생략 시 요청 단계 `IllegalArgumentException`). PG가 실제 취소한 금액·실패 확정 금액을 그대로 넘기고, 그 배분의 남은 보류를 넘으면 다른 충전 건으로 나누지 않고 거절한다. 금액 없는 "남은 보류 전부"는 충전 건을 지정하지 않는 `full`에서만 쓴다.
- **충전 건 지정(`point_hold_detail.target_charge_id`)**: CHARGE_ONLY 보류에서 PG 결제 건(충전 건)별 결과를 반영한다. 성공 건만 그 충전 건 배분에서 확정 차감, 실패 확정 건만 그 충전 건에 반환, 결과 불명확 건은 보류 유지. 비어 있는 상세는 기존 규칙(반환 = 배분 역순, 지급·확정 차감 = 배분 순서)으로 재생한다 → 기존 이력 재구성 불변.
- **내부 경로만**: `InternalHoldFacade.hold(InternalHoldCommand)`·`capture(CaptureCommand)`·`release(InternalReleaseCommand)`. shared DTO·`HOLDABLE_REFERENCE_TYPES`는 바꾸지 않는다. 지급(`payout`)은 내부 참조 유형을 받지 않는다(shared 계약), 확정 차감은 강의·출연 참조 유형을 받지 않는다 → 같은 보류를 지급과 확정 차감이 나눠 쓰지 않는다.
- **멱등키**: 확정 차감 `CAPTURE:{holdId}:{captureRequestId}`(상세 UNIQUE + 원장 작업 키), 내부 반환 `RELEASE:{holdId}:{releaseRequestId}`(기존과 같은 형식). 같은 ID에 다른 금액·지정 충전 건이면 `IDEMPOTENCY_KEY_CONFLICT`.
- **오류 코드(기존 코드 이름·의미 불변)**: `CAPTURE_AMOUNT_EXCEEDS_HELD` 409, `HOLD_TYPE_NOT_SUPPORTED` 400, `HOLD_CHARGE_NOT_FOUND` 400. 강의 지급 실패 분류(`WALLET_NOT_FOUND`만 일시)는 그대로이며 이 코드들은 강의 지급 경로에서 나지 않는다.

### 8.1 강의 판매 수익 지급 (BD-67~BD-70)

- **강의 직접 지급 방식**: 강의가 지급 시점(구매일 + 31일 00:00 KST, `payout_scheduled_at`)·구매 확정·이의 제기·판매자 상태를 판단하고, 지급할 때 내부 API 지급(`PayoutCommand.full(구매자, LECTURE_ORDER_ITEM, 항목 ID, "LECTURE-PAYOUT-{항목 ID}", 판매자, LECTURE_ORDER_ITEM, 항목 ID)`)을 직접 부른다. 출연과 같이 **지급 원천 모듈이 업무 정책을 소유**하고, Billing은 보류·반환·지급·원장·멱등성만 맡는다.
- **Billing은 강의 지급 예정 건(SCHEDULED)을 만들지 않는다**(BD-62·BD-63 폐기). 강의 지급 건은 실제 지급이 성공할 때 기존 지급 경로가 `COMPLETED`로 한 번 INSERT한다(`UNIQUE(source_type, source_id)` 그대로).
- **지급 검증은 기존 지급 엔진 그대로**: 원천은 그 주문 항목(`verifySource`), 보낸 쪽 ≠ 받는 쪽, 받는 쪽 지갑 필수(`WALLET_NOT_FOUND`, 자동 생성 없음), 수익 HP 우선·충전 HP FIFO 소진, 같은 지급 요청 ID는 재요청(replay), 끝난 보류는 `HOLD_ALREADY_CLOSED`, 보류가 남아도 같은 원천의 다른 요청 ID는 `PAYOUT_ALREADY_SETTLED`.
- **Billing에 넣지 않는 것**: 판매자 탈퇴 조회, 이의 제기 판단, 지급 예정일 계산, 지급 예정 금액 집계(강의 주문 항목 기준으로 강의가 제공). 판매자 탈퇴 조건은 policy.md 6장 "판매자 탈퇴 · 자격 상실"을 따른다. 강의 지급 배치의 재시도 불가 오류 격리는 미결이다(BD-74).
- **Flyway 유지**: `V202610141530__add_settlement_cancelled_status.sql`은 수정·삭제하지 않는다. `SettlementStatus.CANCELLED`도 DB CHECK·기존 행 호환을 위해 남긴다(만드는 코드는 없다).
- **레거시 지급 건 주의**: 예전 코드가 만든 `SCHEDULED`·`SUSPENDED` 지급 건이 남은 주문 항목은 직접 지급이 `PAYOUT_ALREADY_SETTLED`로 막힌다. 지급 건은 삭제하지 않는다. 남았는지와 보정 방식은 미결이다(BD-71).
- **판매자 지갑 (BD-68)**: 구매 때 판매자 지갑을 보지 않는다. 지급 때 없으면 `WALLET_NOT_FOUND`로 실패하고 지급 변경 전체가 롤백되며(보류는 지급 전 그대로), 지갑을 자동 생성하지 않는다. 지갑이 생긴 뒤 같은 원천·같은 지급 요청 ID로 다시 지급할 수 있다.
- **중복 지급 오류 코드 (BD-69, 엔진 순서 그대로, 바꾸지 않는다)**: 같은 지급 요청 ID = 재요청(replay), 전액 지급으로 끝난 보류에 다른 요청 ID = `HOLD_ALREADY_CLOSED`, 보류가 남았는데 같은 원천의 다른 요청 ID = `PAYOUT_ALREADY_SETTLED`. 어느 쪽도 HP·원장·지급 건이 늘지 않는다.
- **오류를 지급 완료로 해석하지 않는다 (BD-70)**: `HOLD_ALREADY_CLOSED`는 지급 완료뿐 아니라 **반환(구매 취소·이의 제기 인정)으로 닫힌 보류**에서도 나고, `PAYOUT_ALREADY_SETTLED`는 완료 건뿐 아니라 **레거시 `SCHEDULED`·`SUSPENDED` 행**에서도 난다. 호출한 쪽이 이 오류를 "지급됨"으로 바꾸면 판매자에게 HP가 가지 않은 항목이 PAID_OUT이 된다. 지급 성공의 근거는 `payout()`의 정상 반환(처음 또는 replay)뿐이다.
    - 정상 재시도(같은 `payoutRequestId`): `WALLET_NOT_FOUND`(지갑 생성 후), 일시적 DB·애플리케이션 장애, 롤백된 이전 시도. 같은 요청이 이미 커밋됐으면 replay로 처음 결과를 받는다.
    - 재시도로 풀리지 않는 금융 충돌(확인·알림 대상): `HOLD_ALREADY_CLOSED`, `PAYOUT_ALREADY_SETTLED`, `HOLD_STATE_INCONSISTENT`, `IDEMPOTENCY_KEY_CONFLICT`, `CHARGE_PRINCIPAL_MISMATCH`, `PAYOUT_SOURCE_MISMATCH`.
- **지급·반환 경합 (BD-142)**: 같은 보류의 `payout`·`release`는 지갑(ID 오름차순) → 보류 `PESSIMISTIC_WRITE` 순서로 직렬화되어 둘 중 하나만 성공한다(늦은 쪽 `HOLD_ALREADY_CLOSED`). 업무 상태 경합은 각 업무 모듈이 자기 database에서 막는다(예: 강의 `order_item.version` 낙관적 락). 금융 중복 이동 차단(Billing)과 업무 상태 정합성(업무 모듈)은 따로 검증한다.
- **락 순서 (BD-142)**: 모듈 간 잠금 순서는 두지 않는다. Billing이 지키는 락 순서는 `billing` database 안의 것뿐이다: 지갑(ID 오름차순) → 환불 요청·환전 → 보류 → 충전 건(ID 오름차순)·환전 송금(`docs/billing/module.md` 5.2). 이 순서를 바꾸는 변경은 영향 범위를 먼저 보고한다.
- 지급 완료 후 취소·환수, 지급 완료 후 일반 반환은 하지 않는다(별도 정책).

## 9. 도메인 간 통신

- Lecture와 Appearance의 HP 보류·반환·지급·위약금 정산 요청은 지갑 내부 HTTP API(`POST /api/v1/internal/billing/{holds,releases,payouts,penalty-settlements,hold-voids}`)로 한다(BD-111·BD-125). 소비 앱은 shared `BillingApiClient`(Resilience4j)만 쓰고, 로컬 트랜잭션 밖에서 부른다(D62). 계약은 `docs/api/internal-billing.md`, 지갑 쪽 정책은 `docs/billing/CONTEXT.md` 1·2·6장.
- **연동 계약은 `shared`의 `com.hapbang.shared.billing`에 둔다(BD-128).** 요청·결과 `dto`(`HoldCommand`·`ReleaseCommand`·`PayoutCommand`·`PenaltyCommand`·`HoldVoidCommand`와 각 결과 DTO), Enum·오류 코드 `domain`(`BillingReferenceType`·`SettlementSourceType`·`BillingFailureCode`·`HoldVoidOutcome`), 공통 HTTP Client `out`. **Java Port는 두지 않는다**: `SharedBillingDependencyTest`가 port 패키지 없음·sealed가 아닌 인터페이스 없음을 고정한다. Lecture·Appearance는 `:shared`만 의존하고 `:billing-app`의 클래스를 참조하지 않는다.
- 진입점은 `billing.in.ApiV1BillingInternalController` → `in.BillingCommands`(shared Command → `app.Payment*Command`) → `HoldFacade`(`@PreAuthorize("hasRole('INTERNAL')")`, D63, BD-112)다. 요청 하나가 지갑 트랜잭션 하나이고, 실패하면 그 요청의 변경이 전부 롤백된다. 호출한 쪽 트랜잭션에는 참여하지 않는다(BD-143).
- `shared`는 `billing`에 의존하지 않는다. shared 요청 DTO는 구조(필수 값·참조 유형·요청 ID 형식)만 검사하고, 금액·잔액·소유·초과·지급 원천·자기 지급·멱등성 같은 금융 검증은 Billing이 `BillingException`으로 한다. shared에서 `BillingException`·`BillingErrorCode`를 쓰지 않는다.
- **두 종류의 예외를 구분한다 (BD-54·BD-111).**
    - shared 구조 검증은 `NullPointerException`(필수 값 없음)·`IllegalArgumentException`(보류할 수 없는 참조 유형, 요청 ID 형식)을 쓴다. 업무 예외가 아니며 `BusinessException`이나 shared 전용 예외로 바꾸지 않는다. 내부 HTTP API에서는 Command 생성자가 요청 본문 역직렬화 중에 실패하므로 global `GlobalExceptionHandler`가 400 `INVALID_REQUEST`로 응답한다.
    - Billing 금융 검증은 `BillingException`(`BusinessException` 하위)과 기존 `BillingErrorCode`를 그대로 쓴다: `INVALID_POINT_AMOUNT` 400, `PENALTY_AMOUNT_ZERO` 400, `INSUFFICIENT_BALANCE` 409, `IDEMPOTENCY_KEY_CONFLICT` 409, `PAYOUT_ALREADY_SETTLED` 409, `HOLD_VOIDED` 409 등. 응답은 `ProblemDetail`(+ `code`)이고, 소비 앱 Client가 `BillingFailureCode`로 바꾼다.
    - **내부 HTTP API는 shared Command를 요청 본문으로 그대로 받는다**(BD-111, `docs/billing/CONTEXT.md` 1.1). shared Command가 곧 모듈 간 HTTP 계약이기 때문이다. `app`·`domain`·`out`은 shared Command를 쓰지 않고 `Payment*Command`를 받는다(BD-129, `BillingSharedDtoAccessTest`). 회원이 부르는 사용자 API는 각 모듈의 요청 DTO(`@Valid`, 400 `INVALID_REQUEST`)로 받고 shared DTO를 쓰지 않는다.
- 멱등키는 Billing이 만든다(`app.BillingKeys`, BD-55). 호출한 쪽은 보류 멱등키를 만들거나 넘기지 않고, 반환·지급·취소마다 안정적인 요청 ID만 넘긴다. 같은 요청 ID로 다시 보내도 HP는 두 번 움직이지 않는다.
- 호출 쪽의 실패 복구(HP 작업·재시도·보상)는 각 모듈이 소유한다: 강의 `docs/lecture/integration.md`, 출연 `docs/appearance/module.md`. Billing 오류·결과 불명을 성공으로 해석하지 않는다(BD-70).
- **지갑이 부르는 다른 앱 내부 API (BD-134)**: 수익 조회(shared `LectureApiClient`·`AppearanceApiClient`)와 회원 Backfill(`MemberApiClient`)은 shared Client를 고치지 않고 지갑의 `out.OutboundCircuitBreakers`로 감싼다(회로 `lecture`·`appearance`·`member`). `UNAVAILABLE`만 실패로 세고, 수익 조회만 연결 실패를 1회 다시 보낸다. 새 나가는 호출을 더하면 같은 컴포넌트에 회로를 더한다.
- **지갑 이벤트 발행 (BD-130)**: 충전·지급·환불·환전 결과를 `billing.events.v1`에 발행한다(`BillingEventPublisher`, Modulith externalization, 같은 트랜잭션 + 매분 재발행). 이벤트 레코드는 `shared.billing.event`이고 사실만 담는다(계좌·결제키·사유 원문 없음). 상세는 `docs/billing/CONTEXT.md` 9장. 알림용 실패·운영자 확인·환전 승인 이벤트는 BD-162, 지급 완료 출처 제목(`sourceTitle`)은 BD-163을 따른다. 제목은 저장하지 않고 멱등 비교에 넣지 않으며, 제목 때문에 지급을 거절하지 않는다.
- 거래 상태의 확정·취소 판단은 각 업무 도메인이 담당한다.
- Billing은 HP 이동 및 원장 정합성만 책임진다.
- 다른 도메인의 테이블을 직접 조회해 거래 상태를 변경하지 않는다.

## 10. 충전·환불

- 충전 및 PG 취소에는 TossPayments 테스트 API만 사용한다.
- PG 승인과 DB 커밋을 원자적인 트랜잭션으로 가정하지 않는다.
- 충전 HP 사용과 환불 모두 FIFO 정책을 적용한다.
- 여러 충전 건에 걸친 환불은 `POINT_REFUND_REQUEST`로 관리한다.
- 외부 API 타임아웃을 자동 성공 또는 최종 실패로 간주하지 않는다.
- 중복 결제·중복 환불을 방지한다.

### 10.1 HP 충전 (TossPayments 코어 API, BD-75~BD-81)

- **흐름**: `POST /api/v1/wallets/charges`(주문 생성, PENDING, HP 변화 없음, 서버가 `HPCHG-{UUID32}` 발급) → 프론트 결제창 → `POST /api/v1/wallets/charges/confirm`(paymentKey·orderId·amount) → 서버 승인 `POST /v1/payments/confirm` → COMPLETED + 충전 원장 `CHARGE:{chargeId}` 1줄(충전 HP AVAILABLE +금액) + 가용 원금 = 충전액. 조회 `GET /api/v1/wallets/charges/{orderId}`.
- **상태**: `PENDING → PROCESSING → COMPLETED | FAILED`. PROCESSING = 승인을 요청했지만 결과 미확정(BD-75). PENDING·PROCESSING·FAILED는 가용 원금 0이라 보류 배분·원금 불변식에 들어가지 않는다.
- **트랜잭션 분리 (바꾸지 않는다, BD-76)**: PG 호출은 DB 트랜잭션 밖. ① claim(충전 건만 `FOR NO KEY UPDATE`, 소유·금액 대조, 결제키 암호문·HMAC 저장, PROCESSING 커밋) → ② TossPayments 승인(트랜잭션 없음) → ③ complete(지갑 → 충전 건 락, 승인 결과 대조, COMPLETED + 원장 한 트랜잭션) 또는 fail·noteUnresolved. ①을 먼저 커밋해야 ②③ 어느 단계가 실패해도 결제를 추적·복구할 수 있다.
- **결과 판정 (BD-77)**: 2xx DONE → 확정. 4xx 중 401·409·429 상태와 ALREADY_PROCESSED_PAYMENT·IDEMPOTENT_REQUEST_PROCESSING 코드는 PG가 결제를 판단하지 않은 것이라 결과 불명확, 그 밖의 4xx → 승인 안 됨 확정(같은 멱등키 재요청도 같은 응답) → FAILED. **조회 404(실제 TossPayments는 승인 전 결제도 404)는 FAILED가 아니라 결과 확인에서 같은 Idempotency-Key 재승인**(세션 만료면 재승인이 NOT_FOUND_PAYMENT_SESSION으로 거절되어 FAILED)(`failure_reason` = "CODE: message"). 5xx·시간 초과·연결 끊김·이미 처리됨 → **FAILED로 만들지 않고** `GET /v1/payments/orders/{orderId}` 조회: DONE → 확정, ABORTED·EXPIRED·CANCELED(전액 취소) → FAILED, READY·IN_PROGRESS → (결과 확인 때만) 같은 Idempotency-Key로 승인 재요청(결제키를 복호화할 수 없으면 재요청 없이 미확정), 그 외(**PARTIAL_CANCELED는 돈 일부가 PG에 남으므로 FAILED 금지**, WAITING_FOR_DEPOSIT 등)·조회 실패 → PROCESSING 유지 + 사유 기록.
- **복구 (BD-78)**: PROCESSING 건은 같은 결제의 재요청(Recover)과 결과 확인 배치(`ChargeRecoveryBatch`, 5분, 마지막 변경 1분 경과 건, 오래된 순 100건)가 위 조회 규칙으로 확정한다. 미확정으로 남길 때는 사유가 같아도 `updated_at`(확인 시각)을 갱신한다(배치가 같은 미확정 건만 반복해 다른 건을 굶기지 않게). DONE 확정에는 결제키 원문이 필요 없다(PG 결과의 결제키를 HMAC으로 대조). 승인 결과가 주문과 다르면(주문·결제키·금액) 충전하지 않고 PROCESSING + 사유를 남기고 `CHARGE_STATE_INCONSISTENT`(500) → 운영자 확인.
- **멱등성 (BD-79)**: 주문 ID UNIQUE, 결제키 HMAC UNIQUE(`uk_point_charge_pg_payment_key_hash`, 위반은 Repository `saveAndFlush`로 받아 409로 바꾼다 — `EntityManager.flush()` 직접 호출은 Spring 예외로 바뀌지 않아 500이 된다), 충전 원장 작업 키 `CHARGE:{chargeId}`, 확정은 지갑·충전 건 락 아래 상태 재확인, TossPayments 승인 `Idempotency-Key: hapbang-charge-confirm-{orderId}`. 끝난 주문 + 같은 결제키 = 처음 결과(replay), 다른 결제키 = `CHARGE_PAYMENT_KEY_CONFLICT`, FAILED 주문 = `CHARGE_ALREADY_FAILED`.
- **결제키 보호 (BD-80)**: `pg_payment_key` = AES-256-GCM 암호문(무작위 IV, "v1:" 접두사), `pg_payment_key_hash` = HMAC-SHA256(별도 키). 결제키·시크릿 키를 응답·로그·`toString`에 남기지 않는다. 키는 환경 변수 `TOSS_PAYMENTS_SECRET_KEY`·`BILLING_PAYMENT_KEY_ENCRYPTION_KEY`·`BILLING_PAYMENT_KEY_HMAC_KEY`(`hapbang.billing.*`). HMAC 키는 바꾸지 않는다(기존 해시와 맞지 않게 된다).
    - 설정이 없을 때 앱은 뜨고 해당 기능만 503이다(`docs/billing/module.md` 9장): TossPayments 시크릿 키나 결제키 키 둘이 없으면 충전·환불 503(`CHARGE_UNAVAILABLE`·`REFUND_UNAVAILABLE`), 결제키 키 둘이 없으면 환전 신청(`EXCHANGE_UNAVAILABLE`)·Mock 송금도 503.
    - 결제키 키 중 하나만 있거나, 형식이 틀리거나(암호화 키 Base64 32바이트, HMAC 키 Base64 32바이트 이상), 두 값이 같으면 시작 실패(`out.PaymentKeyCipher`).
- **금액 (BD-81)**: 1 HP = 1원, 1,000 이상·1,000 단위(`CHARGE_AMOUNT_INVALID`). 최대 금액·주문 만료는 정책이 없어 두지 않았다(미결).
- **TossPayments 회로 (BD-134)**: `TossPaymentsClient`는 회로 `toss` 안에서 부르고 결과 `Unknown`만 실패로 센다(재시도 없음). 열린 채 호출되면 요청하지 않고 `Unknown(CIRCUIT_OPEN)`이라 기존 결과 불명 처리를 탄다. 충전 주문·승인·환불 신청은 회로가 열려 있으면 상태를 바꾸기 전에 503(`CHARGE_UNAVAILABLE`·`REFUND_UNAVAILABLE`), 결과 확인 배치는 건마다 `TossCircuitGate`로 확인해 열려 있으면 남은 건을 다음 회차로 넘긴다.
- 충전은 유저·인플루언서·인증된 인플루언서만(`ChargeFacade` `@PreAuthorize`, 관리자 403). 주문·승인 모두 지갑 회원도 정상 상태의 같은 역할이어야 한다(`WalletMemberCheck`, 승인은 TossPayments를 부르기 전에 확인, BD-131). 지갑을 만들지 않는다(없으면 `WALLET_NOT_FOUND`).

### 10.2 충전 HP 환불 (TossPayments 결제 부분 취소, BD-87~BD-93·BD-119)

- **API**: `POST /api/v1/wallets/refunds {amount}` + `Idempotency-Key` 헤더(필수, `[A-Za-z0-9_-]{1,64}`, 같은 회원의 같은 키 = 같은 요청, 금액이 다르면 `IDEMPOTENCY_KEY_CONFLICT`) → 끝났으면 200(COMPLETED·PARTIALLY_COMPLETED·FAILED), 결과 확인 중·운영자 확인이면 202. 조회 `GET /api/v1/wallets/refunds/{id}`, 회원 취소 `POST /api/v1/wallets/refunds/{id}/cancel`(REQUESTED일 때만, 아니면 409 `REFUND_NOT_CANCELABLE`). 남의 요청은 404 `REFUND_NOT_FOUND`. 유저·인플루언서·인증된 인플루언서만(`RefundFacade` `@PreAuthorize`, 관리자 403). 신청은 지갑 회원이 정상·정지 상태여야 한다(탈퇴 403, 조회·취소는 확인하지 않음, BD-131). 결제 설정이 없으면 접수 전 503 `REFUND_UNAVAILABLE`.
- **금액 (BD-119)**: 충전 HP만, 1 이상·1 단위(`REFUND_AMOUNT_TOO_SMALL`). 남은 충전 HP를 끝자리까지 환불할 수 있다. 0 이하는 요청 DTO `@Positive`가 400 `INVALID_REQUEST`로 먼저 막는다. 수익 HP는 환불 가능 금액에 들어가지 않는다(부족하면 `INSUFFICIENT_BALANCE`).
- **접수(한 트랜잭션)**: `point_refund_request`(REQUESTED) → `InternalHoldFacade.hold(POINT_REFUND_REQUEST)`(CHARGE_ONLY, 가용 원금 FIFO = 충전 완료 시각·ID 순, 사용·보류된 원금 제외, 건너뛰지 않음) → 배분마다 `point_refund`(PENDING, PG 취소 멱등키 `hapbang-refund-cancel-{UUID}`를 이때 한 번 만들어 `idempotency_key`(UNIQUE)에 저장. 최초 취소·결과 조회·같은 키 재요청·취소 사유 표식 모두 이 저장값을 쓴다. DB ID로 만들지 않는다 — DB 초기화·같은 상점을 쓰는 다른 환경에서 키가 겹치지 않게, BD-88). 배분한 원금은 보류가 끝날 때까지 다른 보류·환불에 쓰이지 않는다. 진행 중인 요청이 있어도 남은 가용 충전 HP 안에서 추가 요청 가능.
- **PG 취소(순차 FIFO, 트랜잭션 밖, BD-89)**: 개별 환불 ID 순(= FIFO)으로 하나씩. 요청 전에 요청·개별 환불을 PROCESSING으로 커밋한다. `POST /v1/payments/{paymentKey}/cancel {cancelReason: "HP 환불 [멱등키]", cancelAmount}` + `Idempotency-Key: 멱등키`. 앞 건이 SUCCEEDED여야 다음 건을 요청한다.
    - 성공(취소 이력에 우리 표식 `[멱등키]` 취소가 **정확히 1건**, cancelStatus DONE, transactionKey 있음, 금액 일치) → `capture(ofCharge(..., "REFUND-{개별 환불ID}", 충전 건, PG 취소액))` + `point_charge.refunded_amount_hp` 증가(전액이면 REFUNDED, 아니면 PARTIALLY_REFUNDED) + 요청 완료액 증가. 가용 원금은 바꾸지 않는다(보류 때 이미 예약).
    - 요청 자체 오류로 거절(INVALID_REQUEST·FORBIDDEN_REQUEST·INCORRECT_BASIC_AUTH_FORMAT)·결제키 복호화 불가 → 그 건 `release(ofCharge(..., "REFUND-{개별 환불ID}", ...))` FAILED, **뒤 건은 PG에 요청하지 않고 반환(CANCELED)**, 요청 종료(성공액 있으면 PARTIALLY_COMPLETED, 없으면 FAILED).
    - **PG 환급 불가 거절**: 그 밖의 4xx(NOT_CANCELABLE_PAYMENT·EXCEED_MAX_REFUND_DUE·NOT_ALLOWED_PARTIAL_REFUND·REFUND_REJECTED·EXCEED_CANCEL_LIMIT 등, 문서에 없는 코드 포함)는 PG로 돌려줄 수 없을 뿐 환불 청구는 남으므로 **반환하지 않고 보류 유지 + MANUAL_REVIEW_REQUIRED**(뒤 건은 반환).
    - 결과 확인 중 같은 키 재요청이 거절되면 반환·운영자 확인 전에 취소 이력을 다시 본다(앞선 요청이 늦게 처리된 경우 성공으로 확정).
    - 결과 불명확(5xx·시간 초과·연결 끊김·401·409·429·PROVIDER_ERROR·FORBIDDEN_CONSECUTIVE_REQUEST·NOT_AVAILABLE_BANK) → 결제키 조회(`GET /v1/payments/{paymentKey}`)의 취소 이력에서 우리 표식을 찾는다. 있으면 성공, 없으면 UNKNOWN(보류 유지, 다음 건 요청 안 함).
    - 우리 취소가 먼저 처리됐을 수 있는 거절(ALREADY_CANCELED_PAYMENT·ALREADY_REFUND_PAYMENT·NOT_CANCELABLE_AMOUNT·NOT_MATCHES_REFUNDABLE_AMOUNT·NOT_FOUND_PAYMENT) → 조회해서 우리 취소가 있으면 성공, 없으면 MANUAL_REVIEW_REQUIRED.
    - MANUAL_REVIEW_REQUIRED(조회 404, 취소 금액 불일치, cancelStatus ≠ DONE, transactionKey 없음, **같은 표식 취소 여러 건(중복 취소)**, PG 환급 불가 거절, 위 거절 + 우리 취소 없음): 그 건은 보류 유지, 아직 요청하지 않은 건은 반환, 요청도 MANUAL_REVIEW_REQUIRED(자동 처리 대상에서 빠짐, 운영자 처리 기능은 없음).
- **복구 (BD-92)**: `RefundRecoveryBatch`(기본 5분, `hapbang.billing.refund.recovery-cron`, 마지막 변경 1분 경과 `recovery-after`, 100건)가 REQUESTED·PROCESSING 요청을 이어서 처리한다. 결과 대기 건은 조회 → 우리 취소가 없으면 **같은 멱등키**로 한 번 재요청(한 번 실행에 결과 확인 1회). PG 성공 후 DB 반영 실패도 PROCESSING으로 남아 조회로 확정 차감한다(재취소 없음). 처리 중 예외로 상태를 못 바꾼 요청도 확인 시각을 갱신해 목록 뒤로 보낸다(기아 방지).
- **UNKNOWN 자동 복구 한도 (BD-90)**: 처음 UNKNOWN이 된 시각(`unknown_since`)부터 24시간, 또는 결과 재확인(`result_check_count`) 12회 중 먼저 도달하면 MANUAL_REVIEW_REQUIRED(`UNKNOWN_TIME_LIMIT`·`UNKNOWN_CHECK_LIMIT`). 재확인 횟수 = 결과 확인 배치에서 취소 결과를 확인하려고 실제로 시도한 조회 수(조회 전에 기록, 조회 실패 포함, 개별 환불 건별). 처음 PG 요청 직후의 즉시 조회는 세지 않는다. 성공이 확인되면 횟수와 관계없이 확정 차감. 한도 도달 시 보류 유지·자동 반환 없음·배치 대상 제외. 시간 판단은 `RefundClock`.
- **PG 취소 요청과 결과 재확인은 따로 센다**: `cancel_request_count`·`first_cancel_requested_at`·`last_cancel_requested_at`(POST), `result_check_count`·`last_result_checked_at`(GET). 최초 PG 요청 시각과 최초 UNKNOWN 시각은 다른 컬럼이다.
- **멱등키 안전 기간 (BD-91)**: TossPayments 멱등키는 처음 사용한 날부터 15일 유효. 날짜 기준 계산 가능성을 고려해 **최초 PG 요청 + 14일(경계 포함)부터 같은 키 자동 POST를 막는다**(`IDEMPOTENCY_KEY_SAFE_WINDOW`). 그 뒤에는 조회만 한다: 우리 취소가 있으면 확정 차감, 없거나 조회도 실패하면 보류 유지 + MANUAL_REVIEW_REQUIRED(`IDEMPOTENCY_KEY_EXPIRED`). POST는 처음 요청(START)과 결과 확인 재요청 두 곳뿐이며, 재요청은 `reserveRetry`(요청 락 아래 기간 확인 + 요청 기록 커밋) 뒤에만 보낸다.
- **운영자 확인 기록**: `point_refund.manual_review_reason`(`RefundManualReviewReason`) + `manual_review_at` + `failure_reason`(PG 코드·메시지), `idx_point_refund_4 (status, updated_at)`. 관리자 조회·처리 API는 별도 Issue.
- **회원 취소**: REQUESTED(첫 PG 요청 전)에만. 첫 PG 요청 전에 같은 요청 락 아래에서 PROCESSING을 커밋하므로 PG 취소와 겹치지 않는다. 전부 반환, 요청·개별 환불 CANCELED.
- **락 순서**: 지갑 → 환불 요청 → 보류 → 충전 건. 같은 요청의 모든 변경은 환불 요청 락 아래에서 상태를 다시 읽는다. 개별 환불 하나는 확정 차감(`CAPTURE:{holdId}:REFUND-{id}`) 또는 반환(`RELEASE:{holdId}:REFUND-{id}`) 중 하나로 한 번만 처리된다.
- **불변식**: 요청액 = 개별 환불 합 = 보류 최초 충전액, 완료액 = SUCCEEDED 합 = CAPTURE 상세 합 = −REFUND 원장 합, 반환 합(FAILED·CANCELED) = RELEASE 상세 합, `refunded_amount_hp` = 그 충전 건의 SUCCEEDED 합 = PG 취소 합, `refunded_amount_hp + remaining_principal_hp ≤ amount_hp`(`ck_point_charge_4`), 한 요청에 충전 건당 개별 환불 1건(`uk_point_refund_refund_request_id_charge_id`).
- **상태**: 요청 REQUESTED·PROCESSING·COMPLETED·PARTIALLY_COMPLETED·FAILED·MANUAL_REVIEW_REQUIRED·CANCELED, 개별 환불 PENDING·PROCESSING·SUCCEEDED·FAILED·UNKNOWN·MANUAL_REVIEW_REQUIRED·CANCELED.
- **CANCELED**: 요청 CANCELED = 회원이 첫 PG 취소 요청 전(REQUESTED)에 전체를 취소, 남은 충전 HP 보류 전부 반환. 개별 CANCELED = 그 충전 건에 PG 취소 요청을 보내지 않고 처리 대상에서 뺌(회원 취소·앞 건 실패나 운영자 확인으로 FIFO 중단), 그 건 보류 반환. PG 요청을 보낸 건(`cancel_request_count > 0`, PROCESSING·UNKNOWN·MANUAL_REVIEW_REQUIRED·SUCCEEDED·FAILED)은 CANCELED가 될 수 없다(`PointRefund.cancel`이 PENDING·요청 0회만 허용).

## 11. 환전

- 충전 HP는 환전할 수 없다.
- 수익 HP만 환전할 수 있다.
- 최소 환전 금액은 1 HP이며 1 HP 단위로 신청한다(`EXCHANGE_AMOUNT_TOO_SMALL`, BD-120).
- 환전 수수료는 10%다. 송금액 = floor(신청 × 90%), 수수료 = 신청 − 송금액이라 수수료는 항상 1 HP 이상이다. 신청 때 확정·저장한다(BD-120).
- 송금액이 0원인 환전(신청 1 HP)은 정산 배치가 은행을 부르지 않고 보류 확정 차감 + 수수료 수입 기록 + COMPLETED로 끝낸다. 송금 시도(`exchange_payout`)는 남기지 않는다(BD-121).
- 환전 신청 시 수익 HP를 즉시 보류한다.
- **정산일 (policy.md 2장, BD-96)**: 신청 때 잠정 정산 예정일 `payout_scheduled_at`(NOT NULL)을 정한다. KST 신청일이 1~9일이면 당월 10일, 10일 이후(당일 포함)면 다음 달 10일 → 토·일이면 다음 월요일 → 그 날 00:00 KST. **공휴일은 반영하지 않는다.** 응답 `settlementRoundDate`도 같은 잠정 예정일이다. 승인 시점에 따른 회차 이월은 policy.md 2장 "환전 신청과 송금"을 따르고, 관리자 승인 때 확정한다(`ExchangeSettlementRound.forApproval`).
- 실제 은행 송금이나 TossPayments 지급대행 API는 사용하지 않는다. 환전 송금은 Mock 은행으로만 하고, Mock이 꺼진 환경(dev·prod)에서는 송금을 건너뛴다(policy.md 2장).
- **환전 송금 Mock (BD-99·BD-100)**: Mock 은행 `MockPayoutController`(`billing.in`, `/api/v1/wallets/mock-payouts`, Swagger 숨김)와 HTTP Client `MockPayoutHttpClient`(`billing.out`, 계약 `PayoutTransferClient`). Client는 같은 애플리케이션에 **실제 HTTP**로 요청한다(주소 미설정 시 `localhost:{실제 서버 포트}`).
    - **금융 트랜잭션과 분리(필수)**: Client는 DB 트랜잭션 안에서 부르면 HTTP 전에 `IllegalStateException`. 지갑·환전·보류 락을 쥔 채 같은 애플리케이션을 기다리지 않는다. Mock 쪽은 접수·결과 확정을 각각 짧은 트랜잭션으로 커밋하고 지연은 트랜잭션 밖에서 기다린다.
    - **켜짐 조건 (BD-100)**: `hapbang.billing.mock-payout.enabled=true` AND 활성 프로필이 하나 이상 AND 활성 프로필이 **전부** local 또는 test. 실제 활성 프로필(`getActiveProfiles`)만 보며 프로필 미지정·기본 프로필(`spring.profiles.default=local`)은 꺼짐 → 로컬에서 Mock을 쓰려면 `SPRING_PROFILES_ACTIVE=local`. 허용 목록 밖 프로필이 하나라도 섞이면(local,staging·local,dev 등) 꺼진다(`MockPayoutActivation`, `@ConditionalOnMockPayout`, Client도 같은 판단). 꺼져 있으면 컨트롤러·Mock 처리·시나리오 제어·전용 보안 체인 빈이 없고 Client는 HTTP 없이 거절한다.
    - **보안**: Mock 경로 전용 `SecurityFilterChain`(HIGHEST_PRECEDENCE, 세션·CSRF 없음)이 앱 기동 시 무작위로 만든 접근 키(`X-Mock-Payout-Key`)가 맞는 요청만 통과시킨다. 다른 경로는 global 설정 그대로.
    - **멱등성**: 송금 시도 ID(UUID) = Idempotency-Key. Mock 은행 기록 `billing.mock_payout_transfer`(계좌번호는 HMAC만). 같은 ID·같은 내용 → 저장된 결과, 다른 내용 → 409. 동시 요청은 UNIQUE로 1건만 접수(나머지 202 PROCESSING 또는 저장된 결과).
    - **시나리오**: SUCCESS(기본)·FAILED(확정 실패)·DELAY(제한 안 지연)·TIMEOUT(처리 후 응답 지연 = 응답 유실)·SERVER_ERROR(처리 전 500). 테스트 코드가 `MockPayoutScenarios.set(송금 시도 ID, 시나리오)`로만 고른다(HTTP 제어 없음, 숨은 규칙 없음).
    - **Client 결과**: Succeeded·Failed(확정)·Processing·NotFound(조회)·Conflict(409)·Rejected(그 밖의 4xx)·Unknown(시간 초과·연결 실패·5xx·해석 실패). 재시도 없음. Unknown·Processing·NotFound에서 새 송금 시도 ID로 보내지 않는다.
    - **알려진 한계**: Mock 처리가 접수(PROCESSING) 커밋 뒤 결과 확정 전에 서버가 멈추면 그 기록은 PROCESSING으로 남고 같은 ID 재요청은 202 PROCESSING이다(Mock 전용, 실제 금전 무관).
- **환전 송금 흐름 (BD-101)**: ① 트랜잭션 1(지갑 → 환전 → 보류 락, 상태·보류 확인)에서 송금 시도 기록(`exchange_payout`, 송금 시도 ID UUID) 저장·커밋 ② 트랜잭션 밖에서 `send` ③ 트랜잭션 2(같은 락 순서)에서 결과 반영 후 커밋. Mock 기록(`mock_payout_transfer`)과 우리 송금 이력(`exchange_payout`)은 따로 남는다(서로 대신하지 않는다).
- **결과별 금융 처리 (BD-101, policy.md 2장)**:
    - Succeeded → 보류 확정 차감(요청 ID `EXCHANGE-PAYOUT-{환전 ID}`) + 같은 트랜잭션에 수수료 수입 기록(`EXCHANGE_FEE:{환전 ID}`, BD-116).
    - Failed(확정 실패, FAILED) → **반환하지 않고 보류 유지**(같은 환전 건을 다시 송금할 수 있어야 한다). 관리자 수동 재송금은 새 송금 시도다.
    - Processing·Unknown → 보류 유지, 같은 ID로 조회. NotFound → 같은 ID 재전송 여부를 UseCase가 판단. Conflict·Rejected → 운영자 확인(보류 유지).
    - **반환은 회원 취소(`EXCHANGE-CANCEL-{id}`)·관리자 거절(`EXCHANGE-REJECT-{id}`)·관리자 최종 실패(FINAL_FAILED, `EXCHANGE-PAYOUT-FAIL-{id}`) 때만 한다.** 수수료 수입은 기록하지 않는다.
- **송금 시도 ID**: 확정 실패 뒤 다시 보내는 것은 새 송금 시도다. 앞선 시도의 송금 시도 ID를 재사용하지 않고 새 ID·멱등키로 보내며 시도 기록을 구분한다. `exchange_payout`은 `(exchange_id, attempt_no)` UNIQUE + 부분 유일 인덱스 `uk_exchange_payout_active`(FAILED가 아닌 시도는 환전당 1건)다(`V202610162100`). 결과를 모를 때(Unknown·Processing)는 절대 새 ID로 보내지 않는다.
- **계좌 (BD-94)**: 계좌를 미리 등록하지 않고 환전 신청마다 테스트 계좌(은행 코드 숫자 3자리, 계좌번호 숫자 8~20자리, 예금주 1~50자)를 입력해 `EXCHANGE_ACCOUNT`에 신청 당시 스냅샷으로 저장한다(환전 1건당 1행, 이후 변경 없음). 계좌 사전 등록 테이블은 만들지 않는다.
    - 계좌번호는 결제키와 같은 AES-256-GCM 키(`PaymentKeyCipher`, `BILLING_PAYMENT_KEY_ENCRYPTION_KEY`)로 암호화해 저장하고, 응답에는 끝 4자리만 보이게 마스킹한다. 키가 없으면 신청 503 `EXCHANGE_UNAVAILABLE`.
    - 실제 계좌 존재 확인·실명 인증·은행 API 호출은 하지 않는다. 검증 상태는 `PENDING`으로만 저장하고 `VERIFIED`로 저장하지 않는다.
- **신청 (BD-95·BD-131)**: 인플루언서·인증된 인플루언서만. 지갑 회원도 정상 상태의 인플루언서 이상이어야 한다(`WalletMemberCheck`, 조회·취소는 확인하지 않음). `POST /api/v1/settlements/exchanges`(Idempotency-Key 필수). 한 트랜잭션에서 신청(REQUESTED) → `InternalHoldFacade.hold(EXCHANGE, 환전 ID)`(REVENUE_ONLY, 가용 수익 HP만) → 보류 연결 → 계좌 스냅샷. 수수료·예상 수령액을 신청 시 확정. 같은 회원 같은 키는 처음 신청(금액·계좌가 다르면 409). 다른 키의 중복 신청은 허용(잔액 안에서만 보류, 지갑 락으로 직렬화).
- **취소 (BD-97)**: 관리자 승인 전(REQUESTED)만. 지갑 → 환전 → 보류 순서로 잠그고 CANCELED·`canceled_at` 기록 후 보류 전액 반환(반환 요청 ID `EXCHANGE-CANCEL-{환전 ID}`). 이미 CANCELED면 반환 없이 현재 결과. 그 밖의 상태는 409 `EXCHANGE_NOT_CANCELABLE`.
- **환전 계좌 멱등성 (BD-95)**: 같은 키 재요청은 금액·은행 코드·예금주(앞뒤 공백 제거)·계좌번호가 모두 같아야 한다. 계좌번호는 저장 암호문을 복호화한 원문과 비교한다(무작위 IV라 암호문끼리 비교하지 않는다. HMAC 컬럼은 두지 않는다). 복호화하지 못하면(키 없음·변경·손상) 새 신청이나 409로 보지 않고 500 `EXCHANGE_ACCOUNT_UNREADABLE`로 거절한다. 조회는 막지 않고 계좌번호를 `****`로 전부 가린다.
- **잠금 계약 — 취소·승인·거절·송금이 모두 지킨다 (BD-98)**
    1. 순서: **지갑(`lockWallet`) → 환전(`findByIdForUpdate` + refresh) → 보류(`InternalHoldFacade` 안에서)**. 관리자는 신청자가 아니므로 먼저 락 없이 환전의 `walletId`를 읽고 → 그 지갑을 잠근 뒤 → 환전을 잠그고 다시 읽는다(잠그기 전 읽은 상태로 판단하지 않는다).
    2. 상태 전이는 환전 락을 잡은 뒤 최신 상태로만 판단한다. 승인: REQUESTED → APPROVED만, 회원 취소: REQUESTED → CANCELED만. 둘이 경쟁하면 지갑 락에서 직렬화되어 먼저 커밋한 쪽만 성공하고 뒤쪽은 바뀐 상태를 보고 거절한다(승인 쪽은 409 계열, 취소 쪽 `EXCHANGE_NOT_CANCELABLE`).
    3. CANCELED는 승인할 수 없다. APPROVED 이후는 회원이 취소할 수 없다(`Exchange.cancel`이 REQUESTED 외 모두 거절).
    4. 승인·송금 전에 보류가 HELD이고 남은 수익 HP 보류 = 신청 금액인지 확인한다. 반환(RELEASED)된 보류의 환전은 승인·송금·확정 차감하지 않는다.
    5. 반환·확정 차감 요청 ID는 환전 건마다 고정(취소 `EXCHANGE-CANCEL-{id}`, 거절 `EXCHANGE-REJECT-{id}`, 송금 성공 `EXCHANGE-PAYOUT-{id}`, 최종 실패 `EXCHANGE-PAYOUT-FAIL-{id}`)해 재시도해도 한 번만 처리된다. 한 보류는 반환과 확정 차감 중 하나로만 끝난다.
    6. 테스트 요구: 승인 vs 취소 동시 실행(여러 회)에서 한쪽만 성공·보류 반환 0 또는 1회·확정 차감 없음 / CANCELED 승인 거절 / APPROVED 취소 거절 / 반환된 보류 송금 거절 / 승인 후 송금 성공 확정 차감 1회·최종 실패 반환 1회 / 지갑 = 원장·환전 원장 합 정합성. `ExchangeIntegrationTest.cancelWaitsForConcurrentStatusChange`가 잠금 순서(지갑 → 환전)를 지키는 다른 트랜잭션과 취소의 직렬화를 검증한다.
- **관리자 승인·거절 (BD-102)**: `/api/v1/settlements/admin/exchanges`(목록·상세·`/{id}/approve`·`/{id}/reject`), ADMIN만(`SecurityConfig` admin 경로 + `ExchangeAdminFacade` `@PreAuthorize`), 관리자 ID는 `@LoginUserId`. 처리는 `ExchangeReviewUseCase`.
    - 잠금: 환전의 `walletId`만 락 없이 읽기(`findWalletIdById`) → 지갑 → 환전(`findByIdForUpdate` + refresh) → 보류(refresh PESSIMISTIC_WRITE). 회원 취소와 같은 순서라 승인·거절·취소 경쟁 시 하나만 상태를 바꾼다.
    - 승인: REQUESTED → APPROVED, `approved_by`·`approved_at`. 신청자의 지갑 회원이 ACTIVE + INFLUENCER·VERIFIED_INFLUENCER여야 한다. **지갑 회원 없음 = 409 `EXCHANGE_APPLICANT_NOT_SYNCED`**, 자격 없음 = 409 `EXCHANGE_APPLICANT_NOT_ELIGIBLE`(둘 다 상태·보류 변경 없음). 승인은 보류를 유지하고 확정 차감·송금을 하지 않는다(송금은 정산 배치). 승인 때 정산 회차를 확정한다(위 "정산일").
    - 거절: REQUESTED → REJECTED, `rejected_by`·`rejected_at`·`rejection_reason`(앞뒤 공백 제거 1~500자, `EXCHANGE_REJECTION_REASON_INVALID`) + 같은 트랜잭션에서 `InternalHoldFacade.release(full, 신청자 userId, EXCHANGE, id, "EXCHANGE-REJECT-{id}")`. 신청자 회원 상태와 무관.
    - 보류 정합성(이 환전·REVENUE_ONLY·HELD·남은 수익 HP 보류 = 신청 금액·충전 0)이 틀리면 승인·거절 모두 중단(500 `EXCHANGE_STATE_INCONSISTENT`).
    - 멱등: 같은 관리자 재승인, 같은 관리자·같은 사유(정규화 후) 재거절 = 처음 결과(반환 다시 안 함). 그 밖(다른 관리자·다른 결정·다른 사유·취소·송금 단계) = 409 `EXCHANGE_ALREADY_REVIEWED`. 승인·거절된 신청의 회원 취소 = 409 `EXCHANGE_NOT_CANCELABLE`.
    - DB(Flyway `V202610162000`): 상태 APPROVED·REJECTED, `ck_exchange_6~9`(승인 기록 쌍, APPROVED면 승인 기록·REQUESTED·REJECTED·CANCELED면 없음 — 송금 단계에서는 보존, REJECTED ⇔ 거절 기록, 사유 1~500·trim).
- **지갑 회원 (BD-103)**: `billing.billing_user`(`BillingUser extends ReplicaUser`, 회원 ID PK·FK 없음, 생성·수정 시각은 shared BaseEntity라 TIMESTAMP — Billing TIMESTAMPTZ 규칙의 예외). `BillingMemberEventListener`가 `UserJoinedEvent`·`UserModifiedEvent`를 **같은 Upsert 규칙**으로 반영(`BillingUserSyncUseCase`): 없으면 생성(가입 전 변경도 생성), 있으면 행을 PESSIMISTIC_WRITE로 잠그고 `ReplicaUser.syncFrom`(회원 버전 이하 무시). **버전 없는 정보는 반영하지 않는다.** 동시 생성 PK 충돌은 최대 3회 다시 시도. 지갑 리스너(`UserJoinedEventListener`)와 별도라 실패가 서로 영향을 주지 않는다. 관리자도 복제한다.
    - **기존 회원 Backfill (BD-132)**: 관리자 API `POST /api/v1/settlements/admin/billing-users/backfill`이 회원 내부 API로 기존 회원을 읽어 지갑 회원을 이벤트와 같은 규칙으로 반영하고, 관리자가 아닌 회원의 지갑도 `getOrCreateWallet`으로 만든다. `member.events.v1`은 72시간만 보존되므로 prod 첫 배포 때 route를 열기 전에 실행한다(`docs/deploy/k8s.md` "billing-app 첫 배포"). 테스트·운영 회원을 임의로 ACTIVE로 채우지 않는다.
    - **회원 이벤트 DLT 복구 (BD-136)**: DLT를 리스너에 다시 넣는 도구는 없다. kafka CLI로 확인하고 원인을 고친 뒤 관리자 회원 Backfill로 다시 맞춘다(`docs/deploy/k8s.md` "billing-app 회원 이벤트 DLT 복구").

## 12. 테스트

- PostgreSQL Testcontainers를 사용해 Entity·Repository·DB 제약을 검증한다.
- 원장과 지갑 잔액의 정합성을 검증한다.
- 동시 보류, 중복 요청, 잔액 부족, 부분 반환·지급을 테스트한다.
- 충전 HP FIFO 배분·반환·환불을 테스트한다.
- 외부 API 실패·타임아웃에 대한 복구를 검증한다.
- 구현하지 않은 기능을 테스트 통과한 것으로 보고하지 않는다.

## 13. AI 작업 범위

- GitHub Issue에 명시된 범위만 구현한다. Issue 범위 밖의 계층(Service·Controller·Scheduler 등)을 임의로 만들지 않는다.
- 기존 모듈·공통 설정·마이그레이션을 승인 없이 변경하지 않는다.
- 정책(policy.md)·결정(BD)과 기존 코드가 다르면 구현 전에 보고한다. policy.md가 먼저 바뀐 경우일 수 있으므로 오류로 단정하지 않는다.
- 작업 완료 후 변경 파일, 테스트 명령 및 결과를 보고한다.

## 14. API 컨벤션

Billing의 Controller·Facade를 새로 만들거나 고칠 때 따른다. 적용 예시는 지갑 API(`ApiV1WalletController`, `ApiV1WalletSpec`, `WalletFacade`)와 강의 모듈(`apps/lecture-app`)이다.

- **컨트롤러 이름**: `ApiV1{Module}Controller`로 통일한다 (예: `ApiV1WalletController`).
- **Swagger 명세 분리**: `@Tag`·`@Operation` 등 Swagger 어노테이션은 같은 `in` 패키지의 `ApiV1{Module}Spec` 인터페이스에 둔다 (예: `ApiV1WalletController` → `ApiV1WalletSpec`). Billing은 강의 모듈의 `{Controller}Docs` 대신 이 이름을 쓴다. 컨트롤러는 이 인터페이스를 `implements`하고 메서드에 `@Override`를 붙인다. 매핑·파라미터 바인딩·`@ResponseStatus`는 컨트롤러에 남긴다.
- **페이지네이션**: 요청은 Spring Data `Pageable`, 응답은 `Page`(global `PageConfig`가 `PagedModel` 형태로 직렬화)를 쓴다. 모듈 전용 페이지 응답 DTO를 만들지 않는다. 최대 페이지 크기는 100이다.
- **관리자 API 경로**: 관리자 전용 API만 `/api/v1/{module}/admin/**` 아래에 둔다 (예: `/api/v1/wallets/admin/...`, `/api/v1/settlements/admin/...`). 관리자가 아닌 API에는 `admin`을 붙이지 않는다.
- **권한 검사**:
    - 관리자 API는 두 겹으로 막는다. `SecurityConfig`의 `requestMatchers("/api/v1/*/admin/**").hasRole("ADMIN")`과 서비스 레이어(Facade·Service 등)의 `@PreAuthorize("hasRole('ADMIN')")`.
    - 그 외 역할 검사(`docs/policy.md` "팀 규칙 > 역할과 권한")는 컨트롤러가 아니라 서비스 레이어(Facade·Service 등) 메서드의 `@PreAuthorize`로 한다. 컨트롤러에는 `@PreAuthorize`를 붙이지 않는다. 컨트롤러에서 `isUserInRole` 등으로 직접 검사하지 않는다.
    - 이벤트 리스너·배치처럼 로그인 정보 없이 호출하는 경로는 `@PreAuthorize`가 없는 별도 Facade 메서드를 쓴다 (예: `WalletFacade.getOrCreateWallet`).
    - `@PreAuthorize` 거절은 `BillingErrorCode`가 아니라 global `GlobalExceptionHandler`가 응답한다. 로그인했으면 403 `FORBIDDEN`, 비로그인이면 401 `UNAUTHORIZED`다 (`docs/api/conventions.md`).
- **성공 응답**: 컨트롤러는 루트 AGENTS.md 7장·`docs/api/conventions.md`대로 `RsData`로 감싸 반환한다. Facade·UseCase는 응답 형식을 모르고 DTO·도메인 결과만 돌려준다(예: `ApiV1WalletController`가 `WalletResponse`를 `RsData.ok`로 감싼다). 오류는 `ProblemDetail` 그대로다.

## 15. 운영·공통 규칙

- **인증 (BD-131)**: 사용자 API는 Envoy Gateway가 JWT를 검증해 넘긴 `X-User-Id`·`X-User-Role`로 인증한다(`global.auth.GatewayUserHeaderFilter`). 세션·CSRF를 쓰지 않는다. 테스트는 `fixture.GatewayLogin.as(userId, role)`로 로그인하고 `csrf()`를 붙이지 않는다.
- **지갑 회원 확인 (BD-131)**: HP를 받거나 내보내는 사용자 신청(충전 주문·승인, 환불 신청, 환전 신청)은 Facade의 `@PreAuthorize`(토큰 역할)에 더해 `WalletMemberCheck`로 `billing_user`의 상태·역할을 확인한다. 지갑 회원 없음 404 `WALLET_MEMBER_NOT_FOUND`, 부적격 403 `WALLET_MEMBER_NOT_ALLOWED`. 이런 신청을 새로 만들면 같은 확인을 붙이고, 테스트 `setUp`에서 `WalletMemberFixture`로 지갑 회원을 저장한다.
- **닫힌 지갑 (BD-141·BD-145)**: 닫힘 표시는 `wallet.closed_at`이다(`Wallet.isClosed`). 닫힌 지갑을 찾는 쿼리는 `closed_at`으로 거른다.
    - 새로 HP가 드나드는 신청(충전 주문·충전 승인 시작·새 보류)은 그 작업의 지갑 락 아래에서 `Wallet.requireOpen()`으로 확인하고 409 `WALLET_CLOSED`로 거절한다. Facade에서 미리 보는 것만으로는 닫기와 직렬화되지 않는다. 이미 생긴 의무(보류 재요청·반환·지급 받기·위약금·복구 배치)는 거절하지 않는다(BD-141).
    - 지갑을 새로 만드는 경로는 `WalletCreateUseCase`만 쓴다(회원 락 아래에서 탈퇴 기록을 보고 닫힌 지갑을 만든다, BD-141).
    - 탈퇴로 닫으면 탈퇴 작업 ID와 시각이, 회원 정보 파기(`UserMaskedEvent`)로 닫으면 시각만 있다. 다시 열기는 탈퇴 작업 ID가 같을 때만 열어 파기로 닫힌 지갑은 열리지 않는다(BD-145).
    - 파기 때 끝난 환전의 계좌만 지우고 처리 중인 환전의 계좌는 송금에 필요해 남긴다(BD-144, `WalletMaskingUseCase`).
- **배치 실행 락 (BD-138·BD-139)**: `@Scheduled` 배치는 `run()`에서 `JobLock.runOrSkip(BillingJob.X, ...)`으로 감싸 배포 중 겹친 pod에서 한 곳만 실행한다(PostgreSQL advisory lock, 잡지 못하면 그 회차를 건너뜀). 동시에 한 번만 돌아야 하는 관리자 작업은 `tryAcquire`를 쓴다(Backfill: 다른 실행 중 409, 락 오류 503). 새 배치를 더하면 `BillingJob`에 그 이슈 번호 × 1000 + 순번(예: #607 → 607001)을 더하고 기존 번호는 바꾸지 않는다(BD-139). UseCase의 건별 락·멱등성은 그대로 둔다(락은 동시 실행만 막는다).
- **잔액 대사 배치 (BD-139)**: `WalletReconciliationBatch`가 매일 04:02:45 KST에 최근 72시간 안에 바뀐 지갑(`wallet.updated_at`·`point_ledger.created_at`)만 대사한다. 불일치는 고치지 않고 error 로그와 `hapbang.billing.reconciliation.*` 지표로만 알린다. 오류 지갑은 끝에 1회 재시도하고, 그래도 실패하면 다음 회차로 넘긴다. `last.success`는 failed 0일 때만 갱신한다. 실행 기록·이월·기동 시 지표 복원은 DB 실행 기록을 원본으로 하고(BD-146), 그 기록의 저장·읽기 실패가 대사와 기동을 멈추지 않게 한다. 대사 대상 조회가 인덱스(`idx_wallet_1`, `idx_point_ledger_5`)를 타므로 두 컬럼의 의미(수정 시각, 기록 시각)를 바꾸지 않는다.
- **사용자 API rate limit (BD-140)**: 회원이 부르는 사용자 API에는 컨트롤러 메서드에 `@RateLimited(RateLimitedApi.X)`를 붙인다(신청·취소 분당 10, 일반 조회 60, 다른 앱을 부르는 조회 20). 새 사용자 API를 더하면 `RateLimitedApi`에 넣고 같은 애노테이션을 붙인다. 내부·관리자 API에는 붙이지 않는다. 검증 실패(400)·권한 거절(403)도 세고, 처리 중 결과 확인은 같은 신청을 다시 보내지 말고 조회 API로 안내한다. 넘으면 429 `TOO_MANY_REQUESTS` + `Retry-After`(공통 핸들러). member와 같은 이름을 쓰므로 바꾸지 않는다. 테스트 공유 컨텍스트는 `hapbang-module-test.yaml`에서 한도를 크게 둔다.
- **헬스체크·지표 (BD-133)**: Actuator `/actuator/health`로 앱·DB 상태를 확인하고 `/actuator/prometheus`로 지표를 낸다. k8s에서는 관리 포트 8082(`MANAGEMENT_SERVER_PORT`)에서만 열리고, 8080(Gateway 전용)·8081(내부 API)에서는 404다(`ManagementPortTest`).
- **로컬 테스트 계정**: 로컬에서는 member-app(`MemberDataInit`, 로컬 로그인)이 유저·인플루언서·인증된 인플루언서·관리자 역할별 테스트 계정을 만들고, Gateway를 거쳐 billing-app을 부른다.
