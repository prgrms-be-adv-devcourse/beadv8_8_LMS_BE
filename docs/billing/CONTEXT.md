# 지갑(Billing) 작업 맥락

작업별로 합의한 정책을 적는다. 결정의 근거 문서는 각 항목에 적고, 내용은 복사하지 않는다.

## 1. 내부 HTTP API 구현 (#273)

2026-10-29 `/grill-with-docs`로 정했다. 계약은 [`docs/api/internal-billing.md`](../api/internal-billing.md)를 따른다.

### 1.1 경로·입출력

- `POST /api/v1/internal/billing/{holds, releases, payouts, penalty-settlements}`. 성공은 `RsData`, 실패는 `ProblemDetail` + `code`다. 요청 하나를 Billing 로컬 트랜잭션 하나로 처리한다.
- 요청 본문은 shared Command(`HoldCommand`·`ReleaseCommand`·`PayoutCommand`·`PenaltyCommand`)를 그대로 받는다. BD-54의 "shared DTO를 Controller 요청 DTO로 쓰지 않는다"는 같은 JVM 호출 전제였고, HTTP 계약에는 적용하지 않는다(BD-54 재검토 결과). Command 생성자의 구조 검사(NPE·IAE)는 Jackson 역직렬화 오류가 되어 400 `INVALID_REQUEST`로 응답한다.
- 응답 DTO는 `BillingPortAdapter`와 같은 방식으로 변환한다(BD-61). `amountHp`가 `null`이면 남은 보류 전액이다.
- 확정 차감(capture)은 노출하지 않는다.
- 이슈 #273의 "shared 요청 DTO → billing 내부 Command 변환"은 #280(`BillingPort` 삭제)에서 한다. 지금 `HoldFacade`는 shared Command를 받고, 시그니처를 바꾸면 `BillingPortAdapter`·Hold 통합 테스트까지 바뀌기 때문이다. 기존 `InternalHoldCommand`·`InternalReleaseCommand`는 환불·환전용(보류 종류 지정)이라 이 용도로 쓰지 않는다.

### 1.2 인증·노출

- 기존 `InternalApiSecurityConfig`를 쓴다. `X-Internal-Api-Key`가 없거나 틀리면 401(본문 없음)이고, 회원 세션은 읽지 않는다.
- `HoldFacade`에 `@PreAuthorize("hasRole('INTERNAL')")`를 붙인다(D63). #280 뒤에는 `HoldFacade`가 내부 API 전용 진입점이므로 최종 위치를 지금 정한다. 운영에서 `BillingPort`를 부르는 곳은 없고, `HoldFacade`·`BillingPort`를 직접 부르는 테스트는 `@WithMockUser(roles = "INTERNAL")`로 호출한다(검증 조건은 그대로).
- 호출한 쪽이 넘긴 `userId`를 믿는다([`internal-earnings.md`](../api/internal-earnings.md) 1장).
- Swagger에 노출한다. 외부는 진입점이 막는다(Compose는 nginx 404, k3s는 route 없음).

### 1.3 멱등성·동시성

- 같은 키 + 같은 내용은 `replayed = true`, 같은 키 + 다른 내용은 409 `IDEMPOTENCY_KEY_CONFLICT`다. 키 형식(BD-55)과 지급 오류 순서(BD-69)는 바꾸지 않는다.
- 같은 회원·같은 키의 동시 요청은 지갑 락·보류 락으로 직렬화되어 replay된다. 코드는 바꾸지 않고 HTTP 동시성 테스트로 고정한다.
- 다른 회원이 같은 참조 대상을 보류하면 보류 멱등키 UNIQUE 충돌로 409다(의도된 동작).

### 1.4 락 대기 상한 (BD-108 결정)

- Billing 전체에 `lock_timeout = 3s`를 Hikari `connection-init-sql`(`SET lock_timeout = '3s'`)로 적용한다. Client 응답 timeout(5초)보다 짧게 둔다.
- 락 대기 초과(PostgreSQL 55P03)는 새 코드 `HP_LOCK_TIMEOUT`(503)으로 응답한다. 트랜잭션은 롤백되므로 Client는 `UNKNOWN_RESULT`로 받아 같은 키로 다시 보내면 된다.
- `HP_LOCK_TIMEOUT`을 shared `BillingFailureCode`에도 추가한다.
- 배치는 건별로 예외를 격리하므로 해당 건만 다음 실행에서 다시 처리한다. 충전 승인 중 초과되면 PROCESSING으로 남고 충전 복구 배치가 처리한다.
- #273과 같은 PR에 포함한다.

### 1.5 공존

- `BillingPort`는 유지한다. 같은 `HoldFacade`를 Java 호출과 HTTP가 함께 쓴다. 삭제는 #280이다.

### 1.6 테스트

- `@IntegrationTest` + MockMvc로 네 경로마다 성공·replay·409·400(구조 오류)·401(키 없음, 세션만 있음)을 검사한다. status·code·detail, 같은 요청의 동시 처리, 락 대기 초과 503, 처리 중 기술 실패의 500(내부 메시지 미노출)과 전체 롤백, Facade 인가, 요청 로그도 검사한다.
- 오류 코드 대조 단위 테스트(이슈 #273대로 역방향): `BillingErrorCode` 전체를 `default` 없는 `switch`로 `BillingFailureCode`(내부 API에서 나오는 코드) 또는 `null`(나오지 않는 코드)로 바꾼다. 바뀐 코드는 이름이 같고, `UNMAPPED`를 뺀 `BillingFailureCode`가 모두 어떤 Billing 코드에서 나온다. Billing에 오류 코드가 늘면 컴파일되지 않아 분류를 빠뜨리지 않는다.

### 1.6.1 로그

- 요청마다 요청 ID·참조 ID·금액과 결과(`result=OK replayed=…`, `result=FAILED code=…`, `result=ERROR exception=…`)를 key=value 한 줄로 남긴다(`ApiV1BillingInternalController`). 내부 API 키는 남기지 않는다.
- correlation ID는 받지 않는다. 계약(`internal-billing.md` 3장)이 "추적 헤더는 tracing 도입 때 정한다"이므로 이슈 #273의 correlation ID 항목은 그때로 미룬다.

### 1.7 용어

- 경로 `penalty-settlements`와 `settlePenalty`는 그대로 둔다. "위약금 정산"은 호스트 출연 취소 위약금의 반환과 지급을 한 번에 처리하는 것으로, GLOSSARY "정산"(지급·환전을 통틀어 부르는 말)의 예외다.

### 1.8 범위

- 이슈 #273 본문에 없는 락 대기 상한(1.4: `lock_timeout`, `HP_LOCK_TIMEOUT`, 충전·환불·환전 Facade 변환)을 #273에 포함한다. Client 응답 timeout(5초)과 무제한 락 대기가 맞지 않아 내부 API와 함께 정했다. PR 본문에 이 사유를 적는다.
- 범위 밖: Resilience4j 적용(별도 이슈, 동시 진행), `BillingPort` 삭제와 내부 Command 변환(#280), correlation ID(tracing 도입 때).

## 2. Billing Client 회복성 — Resilience4j (#411)

2026-10-29 `/grill-with-docs`로 정했다. 대상은 shared `BillingApiClient`이고, 실패 분류는 [`docs/api/internal-billing.md`](../api/internal-billing.md) 2장(강의 D64·D66)을 따른다. **강의 합의** 표시가 붙은 항목은 강의 실행기 동작이 바뀌거나 D64를 보강해서 강의 담당과 합의했고(2026-10-29, 제안대로), 강의 결정 기록 D66~D68에 남겼다.

### 2.1 구조

- shared `BillingApiClient` 안에서 감싼다. 출연(#274)도 같은 보호를 받는다.
- Resilience4j core 모듈(`circuitbreaker`·`retry`·`timelimiter`)을 코드로 조합한다. shared는 라이브러리라 starter의 어노테이션 AOP를 쓰지 않는다. Spring Boot 4.1용 starter 지원 여부는 확인하지 않았다.
- 인스턴스는 지갑 하나에 하나(`billing`)다. 네 경로(보류·반환·지급·위약금 정산)가 같은 billing-app을 부르기 때문이다.
- 감싸는 순서는 `Retry( CircuitBreaker( TimeLimiter( 호출 ) ) )`다. 재시도마다 CB에 기록되고, CB가 열리면 남은 재시도는 멈춘다.
- 설정 키는 `hapbang.clients.billing.resilience.*`다. 기본값은 `BillingClientProperties`에 두고 앱 yaml에서 덮어쓴다.

### 2.2 Circuit Breaker

- 실패로 세는 것은 `BillingCallException`(RETRYABLE·UNKNOWN_RESULT)이다. `Failed`(4xx 업무 응답)는 지갑이 정상이라는 뜻이라 세지 않는다.
- 다음은 `BillingCallException`이어도 세지 않는다.
  - 429: 과부하 신호이지 장애가 아니다.
  - 503 `HP_LOCK_TIMEOUT`(1.4): 특정 지갑의 락 경합이라, 세면 회원 한 명의 경합으로 모든 회원의 호출이 막힌다. 분류는 그대로 UNKNOWN_RESULT다.
- 값: count 기반 창 20, 최소 호출 10, 실패율 50%, OPEN 30초, HALF_OPEN 허용 3회, 느린 호출 4초(응답 timeout 5초보다 짧고 `lock_timeout` 3초보다 길다), 느린 호출 비율 50%(실패율과 같게. 기본값 100%면 느린 호출로는 사실상 열리지 않는다).
- OPEN이라 요청을 보내지 않으면 `BillingCallException(RETRYABLE, 0, rawCode = "CIRCUIT_OPEN")`을 던진다. 요청이 지갑에 닿지 않은 것이 확실하므로 RETRYABLE이다. **강의 합의 → D66**(D64 보강, D58 보상 판단과 연결).
- 강의 HP 작업 배치는 선점 전에 CB가 OPEN이면 그 회차를 건너뛴다. 그래야 지갑 장애가 길어져도 정상 작업이 시도 횟수를 다 써서 `DEAD`가 되지 않는다. Client는 CB 상태를 알려 주는 `isCircuitOpen()`을 제공한다(OPEN·FORCED_OPEN이면 `true`, HALF_OPEN은 `false`). **강의 합의 → D67**(D57 실행기 변경). 실행기 변경은 강의 후속 이슈에서 한다.
  - 배치가 열린 회로를 부르지 않으므로 OPEN → HALF_OPEN 자동 전이를 켠다(`automaticTransitionFromOpenToHalfOpenEnabled`). 끄면 호출이 있어야 HALF_OPEN이 되어, 주문 요청이 없을 때 회로가 계속 열려 배치가 영원히 건너뛴다.
- CB 상태는 Micrometer 지표와 health에 노출한다. readiness에는 넣지 않는다. 지갑 장애로 강의·출연 pod까지 트래픽에서 빠지면 안 되기 때문이다.
  - 지표: `resilience4j-micrometer`로 CB·Retry 지표를 Resilience4j 기본 이름(`resilience4j.circuitbreaker.*`, `resilience4j.retry.calls`)과 `name=billing` 태그로 낸다(`BillingClientMetrics`).
  - health: `/actuator/health`의 `billingClient`(`BillingClientHealthIndicator`). CLOSED면 UP, OPEN·HALF_OPEN이면 UNKNOWN이고 DOWN은 쓰지 않는다(Resilience4j 기본 동작). 상세에 state·실패율·느린 호출 비율·호출 수를 담는다. k8s probe는 liveness·readiness 그룹만 보므로 pod에는 영향이 없다.
  - 앱에는 actuator가 있으므로 shared는 `spring-boot-health`를 compileOnly로만 쓴다.

### 2.3 TimeLimiter

- 소켓 timeout(연결 2초, 응답 5초)은 그대로 두고, TimeLimiter는 시도 한 번의 전체 상한 8초로 둔다. 소켓 timeout 합(7초)이 먼저 끝나는 것이 정상이고, TimeLimiter는 그것이 동작하지 않을 때의 안전망이다.
- 시간 초과는 반드시 UNKNOWN_RESULT다. 시간이 다 돼도 HTTP 요청은 뒤에서 계속 돌아 지갑이 커밋할 수 있기 때문이다.
- 실행 스레드는 가상 스레드다.
- 주문 요청 하나의 시간 예산은 10초다. 넘으면 남은 항목은 배치로 넘기고 202(처리 중)로 응답한다(D58 흐름). **강의 합의 → D68**, 구현은 #276.

### 2.4 짧은 Retry

- RETRYABLE만 재시도한다(연결 거부, 408·425·429). UNKNOWN_RESULT, `Failed`, `CIRCUIT_OPEN`은 재시도하지 않고 HP 작업 백오프(D57)에 맡긴다.
- 첫 호출을 포함해 3회, 200ms에서 시작해 두 배씩 늘리고 지터 0.2(±20%, 강의 HP 작업 `retry-jitter-ratio`와 같다)를 둔다. 기다리는 시간은 최대 약 0.7초다.
- 429의 `Retry-After`는 따르지 않는다.
- 다 실패하면 마지막 `BillingCallException`을 그대로 던진다. 실행기 분류는 바뀌지 않는다.
- 주문 요청 안 즉시 실행과 HP 작업 배치에 같은 정책을 쓴다.
- Client 안의 재시도는 Micrometer 지표(재시도 횟수, CB 상태)로만 남긴다. HP 작업 시도 행은 늘리지 않는다.

### 2.5 범위·테스트

- 이번에는 `BillingApiClient`만 바꾼다. `LectureApiClient`·`AppearanceApiClient`·`MemberApiClient`는 공통 부분이 보이면 그때 `common`으로 옮긴다.
- `BillingApiClientTest`를 늘린다.
  - CB 전이(CLOSED → OPEN → HALF_OPEN → CLOSED)
  - OPEN이면 요청을 보내지 않음(`CIRCUIT_OPEN` = RETRYABLE, `isCircuitOpen()`), 호출 없이 HALF_OPEN으로 자동 전이
  - RETRYABLE만 재시도, 재시도 중 성공
  - 시간 초과 → UNKNOWN_RESULT
  - `Failed`·429·`HP_LOCK_TIMEOUT`은 CB 실패로 세지 않음
- 설정 기본값·바인딩(`BillingClientPropertiesTest`), health 상태(`BillingClientHealthIndicatorTest`), base-url이 있을 때만 Client·지표·health 빈이 생기는지(`BillingClientWiringTest`)도 확인한다.
- Toxiproxy 장애 주입은 최종 프로젝트 포트폴리오 항목으로 따로 둔다.
- 함께 고친 문서: `docs/api/internal-billing.md` 2·3장, 강의 결정 기록 D66~D68(D57·D58·D64 상태), `docs/lecture/integration.md` 6.2, `docs/billing/test-spec.md` BT-PORT-04.

## 3. 환전 수수료 수입 기록 — 플랫폼 지갑 (#336)

2026-10-29 `/grill-with-docs`로 정했다. 용어는 [`docs/GLOSSARY.md`](../GLOSSARY.md)의 "수수료 수입"·"플랫폼 지갑", 규칙은 [`docs/policy.md`](../policy.md) 3장 "수수료 수입 기록"이다. 지금은 환전 송금이 성공하면 회원 보류 수익 HP 전액만 확정 차감되고, 수수료가 어디로 갔는지 원장에 남지 않는다. 수수료 수입을 원장에 남겨 "잔액은 원장의 합"이 플랫폼 몫까지 성립하게 한다.

### 3.1 플랫폼 지갑

- `wallet`에 `wallet_type`(`MEMBER`·`PLATFORM`)을 더한다. 플랫폼 지갑은 `user_id`가 NULL이다.
  - CHECK: `MEMBER`면 `user_id`가 있고, `PLATFORM`이면 없다.
  - `PLATFORM`은 부분 유일 인덱스로 하나만 둔다. 회원 지갑의 `UNIQUE (user_id)`는 그대로 둔다.
- Flyway 마이그레이션(`V202610301000__add_billing_platform_wallet.sql`)에서 플랫폼 지갑 한 행을 INSERT한다. 환경마다 같은 방식으로 생긴다. 송금 결과 반영에서 플랫폼 지갑이 없으면 `EXCHANGE_STATE_INCONSISTENT`(500, 처리 중단), 관리자 조회에서 없으면 500이다.
- 기존 데이터는 소급하지 않는다. dev·prod는 송금을 건너뛰어 송금 성공 건이 없고, local·test만 해당한다.

### 3.2 HP 종류와 잔액 캐시

- `point_type`에 `FEE`를 더한다. 수수료 수입은 충전 HP도 수익 HP도 아니다. 수익 HP 칸을 쓰면 환전·반환 규칙과 섞인다.
- FEE는 `AVAILABLE` 칸만 쓴다(`HELD` 없음).
- 잔액 캐시 `wallet.fee_balance_hp`를 더한다.
  - CHECK: 회원 지갑은 `fee_balance_hp = 0`, 플랫폼 지갑은 충전·수익·보류 칸이 모두 0.
  - 캐시는 원장 합과 대사한다(3.5).
- 다른 수수료성 수입이 생겨도 같은 플랫폼 지갑에 모으고, 원장 거래 유형으로 구분한다. 지금은 환전 수수료뿐이다(출연 위약금은 게스트에게 지급하므로 해당 없음).

### 3.3 원장 기록

- 거래 유형 `EXCHANGE_FEE`를 더한다. 원장 한 줄은 다음과 같다.

| 칸 | 값 |
| --- | --- |
| 지갑 | 플랫폼 지갑 |
| `point_type`·`balance_bucket` | `FEE`·`AVAILABLE` |
| `amount_hp` | +`exchange.fee_amount_hp` |
| `transaction_type` | `EXCHANGE_FEE` |
| `reference_type`·`reference_id` | `EXCHANGE`·환전 ID |
| 작업 키 / 멱등키 | `EXCHANGE_FEE:{환전 ID}` / `EXCHANGE_FEE:{환전 ID}:1`(원장 멱등키는 작업 키에 줄 번호를 붙인다) |

- 환전 송금 성공(`ExchangePayoutProcessing.succeed`)에서 확정 차감과 같은 트랜잭션으로, 확정 차감 바로 뒤에 별도 작업으로 기록한다.
  - 공통 확정 차감(capture)은 환불도 쓰므로 바꾸지 않는다.
  - 작업 키가 환전 한 건에 하나라 재확인·수동 재송금이 있어도 한 번만 남는다.
- 금액은 신청 때 저장한 `fee_amount_hp`다. 회원에게 보여 준 금액과 같고, 수수료율이 바뀌어도 달라지지 않는다.
  - 확정 차감액 = `fee_amount_hp` + 송금 KRW인지 검사하고, 다르면 불변식 위반이다(DB 제약 `ck_exchange_4`와 같은 식).
- 수수료는 항상 100 HP 이상이다(환전 최소 1,000원·1,000원 단위, 10% 버림). 0원 원장은 생기지 않는다.
- 송금 최종 실패·취소·거절은 보류 전액 반환이므로 기록하지 않는다.
- 락은 지금 규칙(지갑 ID 오름차순)대로 회원 지갑과 플랫폼 지갑을 함께 잡는다. 송금 결과 반영(`ExchangePayoutProcessing.apply`·`applyManualCheck`)은 `ExchangeLocks.lockForPayout`으로 두 지갑 → 환전 → 송금 시도 → 보류 순서로 잠근다. 환전 송금은 배치가 차례로 처리해 플랫폼 지갑 행 경합은 거의 없다.

### 3.4 접근 범위

- 조회는 관리자 API만 한다(3.6). 바꾸는 곳은 환전 송금 성공 처리 하나뿐이다. 정산일 배치, 관리자 결과 재확인, 수동 재송금이 모두 이 처리를 거친다.
- 회원 기능에는 나오지 않는다.
  - 회원 API는 `findByUserId`로 지갑을 찾는데, 플랫폼 지갑은 `user_id`가 없어 찾을 수 없다.
  - Wallet 도메인이 플랫폼 지갑의 충전·수익·보류 칸 변경과 회원 지갑의 수수료 칸 변경을 불변식 위반으로 거절한다. DB CHECK(3.2)도 같은 것을 막는다.
- 테스트로 회원 API(지갑 조회·충전·환불·환전)가 플랫폼 지갑에 닿지 않음을 확인한다.

### 3.5 정합성 점검

- `WalletReconciliationUseCase.reconcile`이 FEE 칸도 비교한다(`fee_balance_hp` = FEE 원장 합).
- "FEE 원장 합 = 송금 완료 환전의 `fee_amount_hp` 합"은 테스트로 확인한다.
- `reconcile`을 주기적으로 부르는 점검 배치는 지금 없고, 이번 범위 밖이다.

### 3.6 관리자 조회 API

- `GET /api/v1/settlements/admin/platform-wallet`: 수수료 수입 잔액.
- `GET /api/v1/settlements/admin/platform-wallet/fee-incomes`: 수수료 수입 내역.
  - 항목: 원장 ID, 환전 ID, 수수료 HP, 신청 HP, 송금 KRW, 기록 시각. 환전 테이블은 Billing 안이라 조인한다.
  - 기록 시각 최신순. 기존 관리자 환전 목록처럼 `Pageable` + `Page` 응답이다.
- 기존 관리자 API처럼 `/admin` 경로(global SecurityConfig)와 Facade `@PreAuthorize`(ADMIN)로 함께 막는다.
- 이슈는 "필요 여부는 회의에서 정한다"였지만, 잔액과 내역 조회를 모두 이번에 만들기로 했다(2026-10-29).

### 3.7 테스트

- 운영에서는 Flyway가 플랫폼 지갑을 넣지만, 테스트 DB 정리(`SchemaManager.truncate`)가 그 행도 지운다. billing 테스트 설정(`src/test/resources/hapbang-module-test.yaml`)의 Hibernate load script(`billing-test-platform-wallet.sql`)로 비울 때마다 다시 넣는다(2026-10-29 결정). test-support는 바꾸지 않는다.
- 회원 지갑이 없음을 확인하던 테스트는 `wallet_type = 'MEMBER'`만 센다.
- `ExchangePayoutIntegrationTest`: 성공·재실행 시 수수료 한 줄과 플랫폼 지갑 대사, 수동 재송금 뒤 한 번만 기록·최종 실패는 기록 없음, 관리자 API 응답·권한, 플랫폼 지갑이 회원 ID로 조회되지 않음. 환전별 원장 합은 송금 성공이면 −송금액이다.
- `WalletTest`: 플랫폼 지갑의 수수료 수입 반영과 지갑 유형·FEE 보류 칸 가드.

### 3.8 범위·완료 조건

- 기존 API는 바뀌지 않는다. shared 계약(`BillingApiClient`·`BillingFailureCode`)도 바뀌지 않는다.
- 범위 밖: 정합성 점검 배치, 송금 결과 이벤트 발행(우선순위 12번 Billing 이벤트 발행. 발행 지점은 이 송금 성공 트랜잭션 근처가 된다).
- 완료 조건(이슈): 회원 보류 차감액 = 송금 KRW + 수수료 수입이 원장으로 확인된다. 재확인·재송금에도 한 번만 기록된다. 최종 실패·취소·거절에는 기록되지 않는다. 플랫폼 지갑이 회원 API로 노출되지 않는다. `./gradlew check`가 통과한다.
