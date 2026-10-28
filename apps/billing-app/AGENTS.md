# Billing Module — AGENTS.md

이 문서는 합방 프로젝트의 Billing 모듈 구현 규칙을 정의한다.

루트 `AGENTS.md`의 공통 아키텍처 및 코딩 규칙을 준수하며, Billing의 금융 정합성 관련 세부 규칙을 추가한다.

작업 전에 `apps/billing-app/PROGRESS.md`(진행 현황·결정 기록·호출 계약)를 읽고, Issue를 마치면 갱신한다.

## 1. 모듈 책임

Billing은 다음 기능을 담당한다.

- Wallet: 지갑 및 잔액 조회
- Ledger: HP 원장 및 잔액 정합성
- Hold: HP 보류·반환·부분 처리
- Charge: TossPayments 테스트 충전
- Refund: 충전 HP 현금 환불
- Settlement: 출연·강의 수익 HP 지급
- Exchange: 수익 HP 환전 신청 및 가상 송금 처리

다른 도메인의 Entity·Repository를 직접 참조하지 않는다.

회원은 `Long userId`로 참조하고, 다른 스키마와 물리적 FK 및 JOIN을 생성하지 않는다.

## 2. 프로젝트 및 DB 구조

- Gradle 모듈: `:billing`
- PostgreSQL 스키마: `billing`
- Entity 및 Enum 패키지: `com.hapbang.billing.domain`
- Repository 패키지: `com.hapbang.billing.out`
- 테이블 생성과 변경은 Flyway를 사용한다.
- Hibernate `ddl-auto=validate` 정책을 유지한다.
- 기존 Flyway 마이그레이션은 수정하지 않는다.
- 기존 `shared`, `global`, 다른 바운디드 컨텍스트의 코드를 임의로 변경하지 않는다.

## 3. ERD

Billing 전체 ERD는 다음 12개 테이블로 구성한다.

1. `WALLET`
2. `POINT_LEDGER`
3. `POINT_HOLD`
4. `POINT_HOLD_DETAIL`
5. `POINT_CHARGE`
6. `POINT_CHARGE_USAGE`
7. `POINT_REFUND_REQUEST`
8. `POINT_REFUND`
9. `SETTLEMENT`
10. `EXCHANGE`
11. `EXCHANGE_ACCOUNT`
12. `EXCHANGE_PAYOUT`

정확한 필드, 자료형, 관계 및 제약 조건은 별도로 제공하는 최종 Billing ERD를 따른다.

ERD에 정의되지 않은 컬럼이나 테이블을 임의로 추가하지 않는다.

## 4. Entity 규칙

- PK는 `Long`과 `GenerationType.IDENTITY`를 사용한다.
- `@Table`에 테이블명과 스키마를 명시한다.
- Enum은 `EnumType.STRING`을 사용한다. 상수 이름이 DB 저장값이므로 바꾸지 않는다(`BillingEnumStorageTest`가 Flyway CHECK와 대조한다).
- Enum 상수마다 의미를 Javadoc(`/** ... */`)으로 적는다(#116, 강의 모듈 #112와 같은 방식). API 응답으로 나가는 Enum만 `@Schema` 설명을 더한다.
- 기본 생성자는 `protected`로 제한한다.
- 범용 `@Setter`와 `@Data`를 사용하지 않는다.
- Aggregate 사이는 객체 대신 ID(`Long`) 필드로 참조한다(#116). 필요한 엔티티는 Repository로 명시적으로 조회한다.
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

- HP는 `CHARGE`와 `REVENUE`로 구분한다.
- 가용·보류 잔액은 `AVAILABLE`과 `HELD`로 구분한다.
- `WALLET`의 네 가지 잔액은 원장 합계의 캐시다.
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
- 반환은 충전 HP 보류를 먼저, 부족분만 수익 HP 보류를 되돌리고, 충전 원금은 최초 배분의 역순(LIFO)으로 원래 충전 건에 복원한다(#100).
- 지급은 수익 HP 보류를 먼저, 부족분만 충전 HP 보류를 쓰고, 충전 원금은 최초 배분 순서(FIFO)로 소진한다. 소진한 원금은 보류한 회원에게 돌아가지 않는다. 받는 쪽에는 출처와 관계없이 수익 HP(가용)로 들어간다(#134).
- 보류한 회원 자신에게는 지급하지 않는다(같은 지갑 안에서 충전 HP가 수익 HP로 바뀌는 것을 막는다).
- 호스트 일방 취소 위약금: 게스트 위약금 = floor(정산 기준액 × 30 / 100), 호스트 반환 = 기준액 - 위약금. 반환과 지급을 한 트랜잭션에서 처리한다. 정산 기준액은 취소 대상 게스트의 계약 출연료로 출연이 정하고 남은 보류 이하여야 한다. 위약금이 0 HP가 되는 기준액은 거절한다(최소 출연료 수치는 Billing에 두지 않는다)(#134).
- 지급 원천(`SETTLEMENT.source_type`·`source_id`)은 출연 계약·강의 주문 항목 단위다: 1:1은 출연 요청에서 성립한 출연 계약 ID, 1:N은 게스트별 출연 계약 ID, 강의는 주문 항목 ID. 게스트 회원 ID는 받는 사람 식별용이며 원천 ID로 쓰지 않는다. 한 원천은 보류·지급 유형(정상·위약금)과 관계없이 한 번만 정산하고(DB `UNIQUE(source_type, source_id)`, 앱 확인은 일찍 거절용), 1:1 출연 요청 보류의 지급은 1건이다. 출연 계약이 그 보류에 속하는지·계약 출연료는 출연이 검증한다.
- 한 보류의 반환·지급 상세를 처리 순서대로 위 규칙으로 재생한 결과가 배분(`POINT_CHARGE_USAGE`) 누적값과 같아야 한다. 이 규칙들은 바꾸지 않는다(바꾸면 기존 이력을 재구성할 수 없다).
- 두 지갑 이상을 바꾸는 작업은 모든 지갑을 ID 오름차순으로 먼저 잠근 뒤 보류 → 충전 건 순서로 잠근다.

## 9. 도메인 간 통신

- Lecture와 Appearance의 HP 보류·반환·지급 요청 및 결과 처리는 동기 API 방식을 사용한다.
- HP 보류 성공은 실제 보류와 DB 커밋이 완료된 이후에 반환한다.
- 거래 상태의 확정·취소 판단은 각 업무 도메인이 담당한다.
- Billing은 HP 이동 및 원장 정합성만 책임진다.
- HTTP 방식으로 트랜잭션이 분리되면 실패 복구와 멱등성 처리를 구현한다.
- 다른 도메인의 테이블을 직접 조회해 거래 상태를 변경하지 않는다.

## 10. 충전·환불

- 충전 및 PG 취소에는 TossPayments 테스트 API만 사용한다.
- PG 승인과 DB 커밋을 원자적인 트랜잭션으로 가정하지 않는다.
- 충전 HP 사용과 환불 모두 FIFO 정책을 적용한다.
- 여러 충전 건에 걸친 환불은 `POINT_REFUND_REQUEST`로 관리한다.
- 외부 API 타임아웃을 자동 성공 또는 최종 실패로 간주하지 않는다.
- 중복 결제·중복 환불을 방지한다.

## 11. 환전

- 충전 HP는 환전할 수 없다.
- 수익 HP만 환전할 수 있다.
- 최소 환전 금액은 1,000 HP이며 1,000 HP 단위로 신청한다.
- 환전 수수료는 10%다.
- 환전 신청 시 수익 HP를 즉시 보류한다.
- 매월 10일 정산하며 주말·공휴일이면 다음 영업일에 처리한다.
- 현재 MVP에서는 Spring Boot의 Mock Controller로 가상 송금 결과를 반환한다.
- 실제 은행 송금이나 TossPayments 지급대행 API는 사용하지 않는다.
- `EXCHANGE_ACCOUNT`는 미래 송금 기능을 위해 구조만 준비한다.
- 실제 계좌정보 입력 및 저장 기능은 구현하지 않는다.
- 가상 송금 성공 시 보류 HP를 차감하고, 최종 실패 시 반환한다.
- 결과가 불명확한 경우에는 보류를 유지한다.

## 12. 테스트

- PostgreSQL Testcontainers를 사용해 Entity·Repository·DB 제약을 검증한다.
- 원장과 지갑 잔액의 정합성을 검증한다.
- 동시 보류, 중복 요청, 잔액 부족, 부분 반환·지급을 테스트한다.
- 충전 HP FIFO 배분·반환·환불을 테스트한다.
- 외부 API 실패·타임아웃에 대한 복구를 검증한다.
- 구현하지 않은 기능을 테스트 통과한 것으로 보고하지 않는다.

## 13. AI 작업 범위

- GitHub Issue에 명시된 범위만 구현한다.
- 현재 Issue가 Entity·Repository·Flyway 작업이면 비즈니스 Service·Controller·Scheduler를 임의로 만들지 않는다.
- 기존 모듈·공통 설정·마이그레이션을 승인 없이 변경하지 않는다.
- ERD와 기존 코드가 충돌하면 구현 전에 보고한다.
- 작업 완료 후 변경 파일, 테스트 명령 및 결과를 보고한다.
## 14. API 컨벤션 (2026-10-12 팀 회의 결정)

Billing의 Controller·Facade를 새로 만들거나 고칠 때 따른다. 적용 예시는 지갑 API(`ApiV1WalletController`, `ApiV1WalletSpec`, `WalletFacade`)와 강의 모듈(`apps/lecture-app`)이다.

- **컨트롤러 이름**: `ApiV1{Module}Controller`로 통일한다 (예: `ApiV1WalletController`).
- **Swagger 명세 분리**: `@Tag`·`@Operation` 등 Swagger 어노테이션은 같은 `in` 패키지의 `ApiV1{Module}Spec` 인터페이스에 둔다 (예: `ApiV1WalletController` → `ApiV1WalletSpec`). Billing은 강의 모듈의 `{Controller}Docs` 대신 이 이름을 쓴다. 컨트롤러는 이 인터페이스를 `implements`하고 메서드에 `@Override`를 붙인다. 매핑·파라미터 바인딩·`@ResponseStatus`는 컨트롤러에 남긴다.
- **페이지네이션**: 요청은 Spring Data `Pageable`, 응답은 `Page`(global `PageConfig`가 `PagedModel` 형태로 직렬화)를 쓴다. 모듈 전용 페이지 응답 DTO를 만들지 않는다. 최대 페이지 크기는 100이다.
- **관리자 API 경로**: 관리자 전용 API만 `/api/v1/{module}/admin/**` 아래에 둔다 (예: `/api/v1/wallets/admin/...`, `/api/v1/settlements/admin/...`). 관리자가 아닌 API에는 `admin`을 붙이지 않는다.
- **권한 검사**:
    - 관리자 API는 두 겹으로 막는다. `SecurityConfig`의 `requestMatchers("/api/v1/*/admin/**").hasRole("ADMIN")`과 서비스 레이어(Facade·Service 등)의 `@PreAuthorize("hasRole('ADMIN')")`.
    - 그 외 역할 검사(루트 AGENTS.md 6장 권한 표)는 컨트롤러가 아니라 서비스 레이어(Facade·Service 등) 메서드의 `@PreAuthorize`로 한다. 컨트롤러에는 `@PreAuthorize`를 붙이지 않는다. 컨트롤러에서 `isUserInRole` 등으로 직접 검사하지 않는다.
    - 이벤트 리스너·배치처럼 로그인 정보 없이 호출하는 경로는 `@PreAuthorize`가 없는 별도 Facade 메서드를 쓴다 (예: `WalletFacade.getOrCreateWallet`).
    - `@PreAuthorize` 거절은 `BillingErrorCode`가 아니라 global `GlobalExceptionHandler`가 응답한다. 로그인했으면 403 `FORBIDDEN`, 비로그인이면 401 `UNAUTHORIZED`다 (루트 AGENTS.md 7장 "오류 응답", #71).
- **성공 응답**: 컨트롤러는 루트 AGENTS.md 7장 "성공 응답 (RsData)"대로 `RsData`로 감싸 반환한다. Facade·UseCase는 응답 형식을 모르고 DTO·도메인 결과만 돌려준다(예: `ApiV1WalletController`가 `WalletResponse`를 `RsData.ok`로 감싼다). 오류는 `ProblemDetail` 그대로다.
- **헬스체크**: Actuator `/actuator/health`로 앱·DB 상태를 확인한다 (global·apps 공통 설정, 모듈에서 따로 만들지 않는다).
- **테스트용 mock 계정**: 로컬 실행 시 member 모듈(`MemberDataInit`, 로컬 로그인)이 유저·인플루언서·인증된 인플루언서·관리자 역할별 테스트 계정을 만든다.
