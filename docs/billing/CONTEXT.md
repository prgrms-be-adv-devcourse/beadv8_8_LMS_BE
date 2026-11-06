# 지갑(Billing) 작업 맥락

2026-10-29~11-02에 `/grill-with-docs` 작업 세션에서 합의한 맥락(배경, 검토한 대안, 당시 범위)을 이슈별로 남긴 기록이다.

- 이 문서는 **당시 기록**이다. 여기 나온 결정은 모두 [decisions.md](decisions.md)의 `BD-*`로 옮겨졌고, 결정의 원본은 decisions.md다. 업무 규칙의 원본은 [`docs/policy.md`](../policy.md), 지금 코드 동작은 [module.md](module.md)다. 이 문서와 그 문서들이 다르면 그 문서들이 맞다.
- 각 장 머리에 옮겨 간 BD 번호와 상태(2026-11-06 기준)를 적었다. 나중에 폐기·대체된 내용은 본문에 **[폐기]**·**[대체됨]**으로 표시했다.
- #497(11-02) 뒤의 작업(#537, #552, #562, #564, #577, #598, #607, #623, #627, #655, #674)은 이 문서에 적지 않고 decisions.md에 바로 기록했다. 새 결정도 decisions.md에 적는다.
- "테스트"·"범위·완료 조건" 절은 이슈 관리용 **당시 범위 기록**이다. 지금의 테스트 기준은 [test-spec.md](test-spec.md)다.

## 1. 내부 HTTP API 구현 (#273)

> 결정 위치: `BD-108`(유효, `lock_timeout`), `BD-111`(유효, shared Command 본문), `BD-112`(유효, `HoldFacade` `ROLE_INTERNAL`), `BD-113`(유효, 오류 코드 대조 테스트), `BD-135`(유효, capture 비노출), `BD-54`(일부 대체됨 → `BD-111`). 1.5의 `BillingPort` 공존은 **폐기**(`BD-128`). 계약은 [`internal-billing.md`](../api/internal-billing.md).

2026-10-29 `/grill-with-docs`로 정했다.

### 1.1 경로·입출력

- `POST /api/v1/internal/billing/{holds, releases, payouts, penalty-settlements}`. 요청 하나를 Billing 로컬 트랜잭션 하나로 처리한다.
- 요청 본문은 shared Command를 그대로 받는다(`BD-111`). BD-54의 "shared DTO를 Controller 요청 DTO로 쓰지 않는다"는 같은 JVM 호출 전제였고 HTTP 계약에는 적용하지 않는다는 것이 재검토 결과였다.
- 응답 DTO는 `BillingResultDtos`로 변환한다(BD-61). 당시 `BillingPortAdapter`와 공유했고, Adapter는 #486에서 지웠다.
- 확정 차감(capture)은 노출하지 않는다(`BD-135`).
- **[대체됨]** 당시에는 "shared 요청 DTO → billing 내부 Command 변환"을 #280으로 미루고 `HoldFacade`가 shared Command를 받게 두었다. 변환은 #494에서 했다(8장, `BD-129`).

### 1.2 인증·노출

- 기존 `InternalApiSecurityConfig`(`X-Internal-Api-Key`)와 `HoldFacade` `@PreAuthorize("hasRole('INTERNAL')")`를 쓴다(`BD-112`, D63). 호출한 쪽이 넘긴 `userId`를 믿는다([`internal-earnings.md`](../api/internal-earnings.md) 1장).
- **[폐기]** "운영에서 `BillingPort`를 부르는 곳은 없고, `BillingPort`를 직접 부르는 테스트는 `@WithMockUser(roles = "INTERNAL")`로 호출한다"는 Port가 남아 있던 당시 기준이다. `BillingPort`는 #486에서 지웠다(`BD-128`).
- Swagger에 노출한다. 외부는 진입점이 막는다(k3s Envoy에 route 없음). **[폐기]** 당시 적은 "Compose는 nginx 404"는 Compose 운영이 #440에서 없어져 해당 없다. 운영은 k3s뿐이다.

### 1.3 멱등성·동시성

- 키 형식(BD-55)과 지급 오류 순서(BD-69)는 바꾸지 않는다. 같은 회원·같은 키의 동시 요청은 지갑 락·보류 락으로 직렬화되어 replay되므로 코드는 바꾸지 않고 HTTP 동시성 테스트로 고정한다.
- 다른 회원이 같은 참조 대상을 보류하면 보류 멱등키 UNIQUE 충돌로 409다(의도된 동작).

### 1.4 락 대기 상한

- 결정은 `BD-108`(`lock_timeout = 3s`, 55P03 → `HP_LOCK_TIMEOUT` 503, shared `BillingFailureCode`에도 추가).
- 맥락: Client 응답 timeout(5초)보다 짧아야 Client가 `UNKNOWN_RESULT`로 받고 같은 키로 다시 보낼 수 있다. 배치는 건별로 예외를 격리하므로 해당 건만 다음 실행에서 다시 처리하고, 충전 승인 중 초과되면 PROCESSING으로 남아 충전 복구 배치가 처리한다.
- 이슈 #273 본문에는 없었지만 무제한 락 대기가 Client timeout과 맞지 않아 #273과 같은 PR에 넣었다.

### 1.5 공존 — **[폐기 → BD-128]**

- 당시 결정: "`BillingPort`는 유지한다. 같은 `HoldFacade`를 Java 호출과 HTTP가 함께 쓴다. 삭제는 #280이다." #486에서 `BillingPort`·`BillingPortAdapter`를 지웠다(7장).

### 1.6 테스트 (당시 범위 기록)

- `@IntegrationTest` + MockMvc로 네 경로마다 성공·replay·409·400(구조 오류)·401(키 없음)과 락 대기 초과 503, 기술 실패 500·전체 롤백, Facade 인가, 요청 로그를 검사했다.
- 오류 코드 대조 단위 테스트는 `BD-113`.
- 로그: 요청마다 요청 ID·참조 ID·금액·결과를 key=value 한 줄로 남기고 내부 API 키는 남기지 않는다. correlation ID는 tracing 도입 때로 미뤘다(`internal-billing.md` 3장).

### 1.7 용어

- 경로 `penalty-settlements`와 `settlePenalty`는 그대로 둔다. "위약금 정산"은 호스트 출연 취소 위약금의 반환과 지급을 한 번에 처리하는 것으로, GLOSSARY "정산"(지급·환전을 통틀어 부르는 말)의 예외다.

### 1.8 범위 (당시 범위 기록)

- 범위 밖: Resilience4j 적용(2장 #411), `BillingPort` 삭제와 내부 Command 변환(#280 → #486·#494), correlation ID(tracing 도입 때).

## 2. Billing Client 회복성 — Resilience4j (#411)

> 결정 위치: 이 장은 **소비 쪽(강의·출연이 쓰는 shared `BillingApiClient`) 설계**라 지갑 BD가 없다. 지갑 내부 API를 부르는 쪽의 연동 계약이라 여기에 남긴다. 실패 분류의 원본은 [`internal-billing.md`](../api/internal-billing.md) 2장(강의 D64·D66), 강의 실행기 동작은 강의 결정 D66~D68이다. 지갑이 **다른 서비스를 부르는** 회로는 별도 결정 `BD-134`(#562)다.

2026-10-29 `/grill-with-docs`로 정했다. **강의 합의** 표시가 붙은 항목은 강의 실행기 동작이 바뀌거나 D64를 보강해 강의 담당과 합의했고(2026-10-29, 제안대로), 강의 결정 기록 D66~D68에 남겼다.

### 2.1 구조

- shared `BillingApiClient` 안에서 감싼다. 출연(#274)도 같은 보호를 받는다.
- Resilience4j core 모듈(`circuitbreaker`·`retry`·`timelimiter`)을 코드로 조합한다. shared는 라이브러리라 starter의 어노테이션 AOP를 쓰지 않는다.
- 인스턴스는 지갑 하나에 하나(`billing`)다. 네 경로가 같은 billing-app을 부르기 때문이다.
- 감싸는 순서는 `Retry( CircuitBreaker( TimeLimiter( 호출 ) ) )`다. 재시도마다 CB에 기록되고, CB가 열리면 남은 재시도는 멈춘다.
- 설정 키는 `hapbang.clients.billing.resilience.*`, 기본값은 `BillingClientProperties`.

### 2.2 Circuit Breaker

- 실패로 세는 것은 `BillingCallException`(RETRYABLE·UNKNOWN_RESULT)이다. `Failed`(4xx 업무 응답)는 지갑이 정상이라는 뜻이라 세지 않는다.
- `BillingCallException`이어도 세지 않는 것: 429(과부하 신호), 503 `HP_LOCK_TIMEOUT`(특정 지갑의 락 경합이라, 세면 회원 한 명의 경합으로 모든 회원의 호출이 막힌다. 분류는 UNKNOWN_RESULT 그대로).
- 값: count 기반 창 20, 최소 호출 10, 실패율 50%, OPEN 30초, HALF_OPEN 허용 3회, 느린 호출 4초(응답 timeout 5초보다 짧고 `lock_timeout` 3초보다 길다), 느린 호출 비율 50%(기본값 100%면 느린 호출로는 사실상 열리지 않는다).
- OPEN이라 요청을 보내지 않으면 `BillingCallException(RETRYABLE, 0, rawCode = "CIRCUIT_OPEN")`. 요청이 지갑에 닿지 않은 것이 확실하므로 RETRYABLE이다. **강의 합의 → D66**.
- 강의 HP 작업 배치는 선점 전에 CB가 OPEN이면 그 회차를 건너뛴다(`isCircuitOpen()`: OPEN·FORCED_OPEN이면 `true`). 지갑 장애가 길어져도 정상 작업이 시도 횟수를 다 써서 `DEAD`가 되지 않게 하려는 것이다. **강의 합의 → D67**, 구현은 #423(강의 D79).
  - 배치가 열린 회로를 부르지 않으므로 OPEN → HALF_OPEN 자동 전이를 켠다. 끄면 주문 요청이 없을 때 회로가 계속 열려 배치가 영원히 건너뛴다.
- CB 상태는 Micrometer 지표(`resilience4j.circuitbreaker.*`, `resilience4j.retry.calls`, `name=billing`)와 health(`billingClient`, CLOSED = UP, OPEN·HALF_OPEN = UNKNOWN)에 노출한다. readiness에는 넣지 않는다. 지갑 장애로 강의·출연 pod까지 트래픽에서 빠지면 안 되기 때문이다.

### 2.3 TimeLimiter

- 소켓 timeout(연결 2초, 응답 5초)은 그대로 두고, TimeLimiter는 시도 한 번의 전체 상한 8초로 둔다(안전망). 실행 스레드는 가상 스레드다.
- 시간 초과는 반드시 UNKNOWN_RESULT다. HTTP 요청이 뒤에서 계속 돌아 지갑이 커밋할 수 있기 때문이다.
- 주문 요청 하나의 시간 예산은 10초다. 넘으면 남은 항목은 배치로 넘기고 202로 응답한다. **강의 합의 → D68**, 구현은 #276.

### 2.4 짧은 Retry

- RETRYABLE만 재시도한다(연결 거부, 408·425·429). UNKNOWN_RESULT, `Failed`, `CIRCUIT_OPEN`은 HP 작업 백오프(D57)에 맡긴다. 429의 `Retry-After`는 따르지 않는다.
- 첫 호출 포함 3회, 200ms에서 두 배씩, 지터 0.2(최대 약 0.7초). 다 실패하면 마지막 `BillingCallException`을 그대로 던진다.
- Client 안의 재시도는 지표로만 남기고 HP 작업 시도 행은 늘리지 않는다.

### 2.5 범위·테스트 (당시 범위 기록)

- `BillingApiClient`만 바꿨다. `LectureApiClient`·`AppearanceApiClient`·`MemberApiClient`는 바꾸지 않았다(지갑이 부르는 쪽 회로는 이후 `BD-134`로 지갑 앱 안에 따로 두었다).
- `BillingApiClientTest`(CB 전이, OPEN 비호출, 재시도, 시간 초과, 세지 않는 실패), `BillingClientPropertiesTest`, `BillingClientHealthIndicatorTest`, `BillingClientWiringTest`.
- 장애 주입(Testcontainers pause·stop)은 최종 프로젝트 포트폴리오 항목으로 따로 둔다(#339, 2026-11-03).

## 3. 환전 수수료 수입 기록 — 플랫폼 지갑 (#336)

> 결정 위치: `BD-114`(유효, 플랫폼 지갑), `BD-115`(일부 대체됨 → `BD-139`, "점검 배치 없음" 부분), `BD-116`(유효, 원장 기록), `BD-117`(유효, 관리자 조회), `BD-118`(유효, 테스트 load script). 규칙은 policy.md 3장 "수수료 수입 기록", 용어는 GLOSSARY "수수료 수입"·"플랫폼 지갑".

2026-10-29 `/grill-with-docs`로 정했다.

### 3.1 배경

- 당시에는 환전 송금이 성공하면 회원 보류 수익 HP 전액만 확정 차감되고, 수수료가 어디로 갔는지 원장에 남지 않았다. 수수료 수입을 원장에 남겨 "잔액은 원장의 합"이 플랫폼 몫까지 성립하게 한다.

### 3.2 검토한 것

- 수수료 수입을 수익 HP 칸에 넣는 안은 버렸다. 환전·반환 규칙과 섞인다. 그래서 HP 종류 `FEE`(가용 칸만)와 캐시 `fee_balance_hp`를 새로 둔다(`BD-115`).
- 플랫폼 지갑은 Flyway가 한 행을 넣어 환경마다 같은 방식으로 생긴다(`BD-114`). 기존 데이터는 소급하지 않는다. 당시 판단 근거는 "dev·prod는 송금을 건너뛰어 송금 성공 건이 없고, local·test만 해당한다"였다(dev 서버는 이후 #440에서 없어졌다).
- 다른 수수료성 수입이 생겨도 같은 플랫폼 지갑에 모으고 거래 유형으로 구분한다. 출연 위약금은 게스트에게 지급하므로 해당 없다.
- 기록 시점은 송금 성공 처리(`ExchangePayoutProcessing.succeed`) 안, 확정 차감 바로 뒤의 별도 작업(`EXCHANGE_FEE:{환전 ID}`)이다(`BD-116`). 공통 확정 차감은 환불도 쓰므로 바꾸지 않았다. 작업 키가 환전당 하나라 재확인·수동 재송금에도 한 번만 남는다.
- 금액은 신청 때 저장한 `fee_amount_hp`다. 회원에게 보여 준 금액과 같고 수수료율이 바뀌어도 달라지지 않는다. 송금 최종 실패·취소·거절은 보류 전액 반환이라 기록하지 않는다.
- 락은 지갑 ID 오름차순으로 회원 지갑과 플랫폼 지갑을 함께 잡는다(`ExchangeLocks.lockForPayout`). 환전 송금은 배치가 차례로 처리해 플랫폼 지갑 행 경합은 거의 없다고 봤다.
- 회원 기능에는 나오지 않는다. 회원 API는 `findByUserId`로 지갑을 찾아 `user_id`가 없는 플랫폼 지갑에 닿지 않고, Wallet 도메인·DB CHECK가 칸 변경을 막는다.
- 관리자 조회 API는 이슈에 "필요 여부는 회의에서 정한다"였지만 잔액·내역 둘 다 이번에 만들기로 했다(2026-10-29, `BD-117`).
- 테스트 DB 정리가 플랫폼 지갑 행도 지우므로 billing 테스트 설정의 Hibernate load script로 다시 넣는다. test-support는 바꾸지 않는다(`BD-118`).

### 3.3 정합성 점검

- `WalletReconciliationUseCase.reconcile`이 FEE 칸도 비교한다.
- **[대체됨 → BD-139]** `reconcile`을 주기적으로 부르는 점검 배치는 #336 범위 밖이었다. #607부터 최근 바뀐 지갑(플랫폼 지갑 포함)을 매일 대사한다.

### 3.4 범위·완료 조건 (당시 범위 기록)

- 기존 API와 shared 계약(`BillingApiClient`·`BillingFailureCode`)은 바뀌지 않았다.
- 범위 밖: **[대체됨 → BD-139]** 정합성 점검 배치, 송금 결과 이벤트 발행(9장 #497에서 함).
- 완료 조건: 회원 보류 차감액 = 송금 KRW + 수수료 수입이 원장으로 확인, 재확인·재송금에도 한 번만 기록, 최종 실패·취소·거절에는 기록 없음, 플랫폼 지갑이 회원 API로 노출되지 않음.

## 4. 환불·환전 1원 단위 (#453)

> 결정 위치: `BD-119`(유효, 환불 1 HP 단위), `BD-120`(유효, 환전 1 HP·송금액 버림), `BD-121`(유효, 송금액 0원 환전), `BD-122`(유효, 오류 코드 이름). 이 결정으로 `BD-87` 일부 대체됨 → `BD-119`, `BD-95` 일부 대체됨 → `BD-120`. 규칙은 policy.md 2장(현금화 대상·단위)과 3장(플랫폼 수수료율).

2026-10-30 `/grill-with-docs`로 정했다.

### 4.1 배경

- 충전은 1,000원 이상·1,000원 단위지만 사용 금액은 1,000원 단위가 아니어서, 1,000원 미만 끝자리 충전 HP·수익 HP를 현금화할 수 없었다.

| 기능 | 이전 | 이후 |
| --- | --- | --- |
| 환불 | 1,000 HP 이상·1,000 단위 | 1 HP 이상·1 단위 |
| 환전 | 1,000 HP 이상·1,000 단위 | 1 HP 이상·1 단위 |
| 충전 | 1,000 HP 이상·1,000 단위 | 그대로 |

### 4.2 검토한 것

- 환전 최소를 1,000으로 두고 단위만 1원으로 하는 안도 있었지만, 잔액 전부를 현금화할 수 있게 1원으로 정했다.
- 환불 1원 부분 취소: TossPayments 문서에는 부분 취소 최소 금액·단위 제한이 없다. 부분 취소가 안 되는 결제는 PG가 거절하고 보류를 유지한 채 MANUAL_REVIEW_REQUIRED로 넘어간다. 테스트 키의 동작 차이는 확인하지 못했다.
- 송금액·수수료 계산을 "수수료를 버림"에서 "송금액을 버림"으로 바꿨다(`BD-120`). 예: 1 → 송금 0원·수수료 1, 1,234 → 1,110원·124. 신청 때 계산해 저장하므로 이미 저장된 환전은 바뀌지 않는다.
- 송금액 0원은 신청 1원뿐이다. 최소를 2원으로 두는 안도 있었지만 허용했다(`BD-121`). 송금하지 않았으므로 송금 시도(`exchange_payout`) 기록은 남기지 않는다. 송금이 꺼진 환경에서는 정산일 배치가 통째로 건너뛰므로 0원 환전도 완료되지 않는다.
- 단위가 1이라 "단위가 맞지 않음" 오류는 없어지고 최소보다 작은 경우만 남는다(`BD-122`). 회원 API 오류 코드라 shared `BillingFailureCode`와는 관계없다. 프론트와 맞춰야 했다.

### 4.3 범위·완료 조건 (당시 범위 기록)

- 바뀐 곳: `RefundRequestUseCase`, `ExchangeRequestUseCase`, `Exchange.requested`·`feeOf`·`@Check`, Flyway `ck_exchange_1`, `BillingErrorCode`, 요청 DTO·Swagger 설명, `ExchangePayoutProcessing.start`(0원 분기).
- 완료 조건: 1 HP 이상 어떤 금액이든 환불·환전 신청 가능, 신청 1원 환전은 은행 호출 없이 완료되고 원장으로 회원 보류 차감 1 = 송금 0원 + 수수료 수입 1, 이미 저장된 환전은 그대로.

## 5. Spring Event 제거 (#471) — **[대체됨 → BD-130]**

> 결정 위치: `BD-124`(대체됨 → `BD-130`). 이 장의 "Modulith 이벤트 기록 설정·테이블 삭제"는 #497(9장)에서 지갑 이벤트 발행을 위해 되살렸다. 지금 기준은 `BD-130`과 9장이다.

2026-10-30 `/grill-with-docs`로 정했다. #430으로 회원 이벤트 리스너가 Kafka 소비로 바뀌어, 당시 지갑은 Spring Event를 발행하지도 받지도 않았다.

### 5.1 범위 (당시 범위 기록)

- Billing만 했다. member-app은 회원 이벤트를 Modulith publication registry(outbox)로 Kafka에 내보내므로(#416) 이벤트 기록이 계속 필요했다. 다른 앱의 `@ApplicationModuleListener`는 각 앱의 Kafka 전환 이슈 범위였다.

### 5.2 당시 결정 (**[대체됨 → BD-130]**)

- billing `spring.modulith.events.*` 설정을 지우고, Flyway `V202610301400`으로 `billing.event_publication`·`event_publication_archive`를 지웠다(남은 기록도 함께, 되돌릴 수 없음). 설정만 끄고 테이블을 남기는 안도 있었지만 지우기로 했다.
- 테이블만 지우면 종료 때 이벤트 기록 레지스트리가 `BadSqlGrammarException`을 내고, `JdbcEventPublicationAutoConfiguration`만 빼면 `StalenessMonitorConfiguration`이 기동에 실패해 두 자동 설정을 함께 뺐다.
- `V202610301400`은 적용된 마이그레이션이라 지금도 남아 있다. 첫 줄 주석은 당시 기준이며 체크섬 때문에 고치지 않는다(`BD-130`, #564).
- 당시 테스트 `ModulithEventsDisabledTest`는 #497에서 지웠다(9.2).

## 6. 보류 무효화 (#479)

> 결정 위치: `BD-125`(유효, 무효화 API), `BD-126`(유효, 무효화 뒤 보류 409). 6.2 끝의 보류 상태 조회 API는 `BD-127`(미결; #577의 대사 조회 `BD-137`과 겹치는지 미정). 계약은 [`internal-billing.md`](../api/internal-billing.md) 1장 "보류 무효화".

2026-10-30 `/grill-with-docs`로 정했다.

### 6.1 배경 (보상 흐름 확인)

- 강의는 성공으로 기록한 보류만 보상 반환한다. 반환이 `HOLD_NOT_FOUND`를 받으면 불변식 위반으로 보고 작업을 DEAD로 둔다.
- 출연은 결과 불명(`HOLD_PENDING`) 재확인에 횟수 제한이 없고, 재확인이 보류를 다시 보내 뒤늦은 보류를 만들 수 있다. 결과 불명 뒤 재확인이 `Failed`를 받으면 이미 생긴 보류가 남는다.
- 지갑은 보류가 없는 반환을 404로 거절하고 아무것도 남기지 않는다. 그래서 반환 뒤에 도착한 보류가 그대로 생긴다.
- 반환만으로는 "보류가 없다"와 "보류가 아직 도착하지 않았다"를 구분할 수 없어, 무효화 API를 새로 둔다.

### 6.2 정한 것

- 무효화 API와 세 결과(`RELEASED`·`ALREADY_CLOSED`·`VOIDED`), 무효화 기록(`billing.hold_void`)은 `BD-125`, 기록이 있는 참조 대상의 보류 거절(409 `HOLD_VOIDED`)은 `BD-126`.
- 보류 테이블·불변식은 바꾸지 않고 기록 테이블을 따로 둔다. 반환은 기존 반환 경로를 반환 요청 ID `VOID`로 재사용한다.
- 보류 상태 조회 API는 이번에 만들지 않는다(`BD-127`, 미결).
- 용어: "보류 무효화"(피할 말: 보류 취소). GLOSSARY에 더했다.

### 6.3 범위·테스트 (당시 범위 기록)

- 지갑 API, `shared.billing` 계약(`HoldVoidCommand`·`HoldVoidResultDto`·`HoldVoidOutcome`·`HOLD_VOIDED`), Client `voidHold`까지. 강의·출연이 언제 무효화를 부를지는 각 모듈 담당이 정한다(사용 제안은 #479 본문).
- `HoldVoidIntegrationTest`(세 결과, 재요청, 무효화 뒤 보류 거절, 소유 확인, 지갑 없음, 동시 실행), `ApiV1BillingInternalControllerTest.voidHold`, `BillingApiClientTest`, `BillingFailureCodeContractTest`, `BillingEnumStorageTest`.

## 7. BillingPort 테스트 전환과 삭제 (#486)

> 결정 위치: `BD-128`(유효). 1.5의 "`BillingPort` 유지"를 이 장이 폐기했다.

2026-10-31 `/grill-with-docs`로 정했다. #280(shared Port와 In-process 연동 제거)의 billing 범위다. 강의(#275~#277)·출연(#274)이 내부 HTTP API로 전환해 운영에서 `BillingPort`를 쓰는 곳이 없었다.

### 7.1 정한 것

- shared `BillingPort`, billing-app `BillingPortAdapter`, `BillingPortIntegrationTest` 삭제(`BD-128`). #280 담당과 확인했다.
- 호출한 쪽 트랜잭션 참여 테스트 4개는 HTTP에서는 없는 동작이라 지운다. "요청 하나 = 지갑 트랜잭션 하나"는 `ApiV1BillingInternalControllerTest#technicalFailureRollsBack`이 이미 검증한다.
- Port 테스트에만 있던 케이스(`#lectureOrderItems`, `#amountErrors`)는 내부 API 테스트로 옮기고, 다른 통합 테스트의 보류 준비는 `HoldFacade` 직접 호출(`ROLE_INTERNAL`)로 바꾼다.
- "HTTP Contract Test"는 MockMvc + 실제 보안 체인 + PostgreSQL 방식을 늘리는 것이다. 계약 도구(Spring Cloud Contract·Pact)는 들이지 않는다.

### 7.2 범위 (당시 범위 기록)

- billing 쪽만. 다른 bounded context import 금지 구조 테스트, 앱별 기동 확인, 배포 문서의 `hapbang.clients.*`는 #280 본 이슈에 남겼다.
- 내부 Command 변환은 시그니처 변경이 테스트 약 80곳에 퍼져 리뷰를 나누려고 #486 병합 뒤 별도 이슈로 했다(2026-10-31 결정, 8장 #494).

## 8. shared Command → billing 내부 Command 변환 (#494)

> 결정 위치: `BD-129`(유효). HTTP 계약과 동작은 바뀌지 않았다.

2026-11-01 `/grill-with-docs`로 정했다. #273에서 #280으로 미뤘던 항목이다(1.1).

### 8.1 정한 것

- 내부 Command 5개(`Payment*Command`)와 변환 위치(`in.BillingCommands`), `BillingSharedDtoAccessTest`는 `BD-129`. 이름의 `Payment`는 용어집의 "HP 결제"다.
- 환불·환전용 `InternalHoldCommand`·`InternalReleaseCommand`와 합치지 않았다. 참조 유형으로 두 경로를 나누는 장치(BD-86)를 타입으로 유지하려는 것이다.
- 참조 유형 목록과 요청 ID 형식은 shared를 바꾸지 않고 billing app의 `PaymentHoldRequests`에 복사했다(`InternalHoldRequests` 선례). `PaymentHoldRequestsTest`가 shared 계약과 같은 값을 받고 거절하는지 고정한다.

### 8.2 테스트 (당시 범위 기록)

- 테스트 13개 클래스의 호출을 내부 Command로 바꿨다. `BillingSharedDtoAccessTest`는 일부러 위반을 넣어 실패하는 것을 확인했다.

## 9. 지갑 이벤트 발행 (#497)

> 결정 위치: `BD-130`(유효, `BD-124` 대체). 9.4의 알림 연동은 지갑 결정이 아니라 알림 쪽에 넘긴 검토 목록이다. 규칙은 [`kafka.md`](../architecture/infrastructure/kafka.md)와 ADR 0001·0002.

2026-11-02 `/grill-with-docs`로 정했다. HP 원장은 PostgreSQL 트랜잭션과 멱등키가 확정하고, Kafka는 완료된 사실만 전달한다.

### 9.1 정한 것

- 범위는 지갑 발행까지다. 알림 소비자는 범위 밖이다(9.4를 #193에 넘긴다).
- 발행 방식(Spring Modulith externalization, 발행 기록 = outbox, 완료 모드 `archive`, 같은 트랜잭션 발행 + 매분 재발행), 토픽 `billing.events.v1`, 이벤트 7종은 `BD-130`.
- 검토 맥락:
  - 발행 기록 테이블은 이슈 본문의 "Flyway로"에서 바꿔 Modulith 스키마 초기화로 만든다. Modulith 2.1의 테이블 형식(v1·v2)을 직접 DDL로 맞추다 틀리지 않으려는 것이다.
  - Modulith Kafka 모듈의 JSON 기본 세트를 끈다(`spring.modulith.events.kafka.enable-json: false`). 켜 두면 그 변환기가 기본 리스너 팩토리에 들어가 회원 이벤트 소비(#430)가 모두 실패한다(확인함).
  - 지급 유형을 싣기 위해 `PayoutType`을 `shared.billing.domain`으로 옮겼다(`BillingReferenceType`·`SettlementSourceType` 선례).

| 이벤트 | 발행 지점 | key | 받는 쪽 멱등 키 |
| --- | --- | --- | --- |
| `ChargeCompletedEvent` | `ChargeProcessing.complete`(승인 응답·복구 배치) | `point-charge:<충전 ID>` | 충전 ID |
| `PayoutCompletedEvent` | `HoldPayoutUseCase`(정상)·`PenaltySettlementUseCase`(위약금)의 지급 건 저장. 재요청은 발행하지 않는다 | `settlement:<지급 건 ID>` | 지급 건 ID |
| `RefundCompletedEvent` | `RefundProcessing`의 요청 종료 중 `COMPLETED`·`PARTIALLY_COMPLETED` | `point-refund-request:<환불 요청 ID>` | 환불 요청 ID |
| `ExchangeCompletedEvent` | `ExchangePayoutProcessing` 송금 성공·송금액 0원 완료 | `exchange:<환전 ID>` | 환전 ID |
| `ExchangeRejectedEvent` | `ExchangeReviewUseCase.reject` | `exchange:<환전 ID>` | 환전 ID |
| `ExchangePayoutFailedEvent` | `ExchangePayoutProcessing` 송금 시도 확정 실패(시도마다) | `exchange:<환전 ID>` | 송금 시도 ID |
| `ExchangeFinalFailedEvent` | `ExchangePayoutProcessing.failFinally` | `exchange:<환전 ID>` | 환전 ID |

- payload: 대상 ID, 회원 ID, HP 금액, KRW 금액(충전 결제액, 환전 송금액·수수료), DB에 저장된 업무 시각. 계좌·예금주·은행·PG 결제키·실패 원문·관리자 사유는 싣지 않는다.

### 9.2 테스트 (당시 범위 기록)

- `BillingEventKafkaTest`(Kafka Testcontainers), 각 흐름 통합 테스트의 `@RecordApplicationEvents`, `BillingExternalEventsTest`(지갑 이벤트만 내보냄).
- #471의 `ModulithEventsDisabledTest`는 지웠다. `BillingSchemaTest`는 Flyway 테이블만 보고 Modulith 발행 기록 테이블은 목록에서 뺀다. 테스트의 완료 모드는 모두 운영과 같은 `archive`다.

### 9.3 범위 밖 (당시 범위 기록)

- 알림 소비자, 채팅 책임 분리(#193, 팀 합의).
- 이벤트 위치를 shared 밖으로 옮기는 것(kafka.md·ADR 변경이라 팀 안건). AsyncAPI·CI 호환성 검사(하지 않기로 함, #339).

### 9.4 알림 연동 검토 (#193에 넘김)

소비하는 쪽(알림)이 정할 것이다. 지갑은 아래를 전제로 발행한다.

- 소비 주체: 당시에는 notification-app을 전제로 했다. **[대체됨]** notification-app은 만들지 않고, 알림은 auxiliary-app 안의 notification 모듈이 맡는다(#684·#710·#714, [`notification.md`](../architecture/infrastructure/notification.md)).
- 소비 그룹 이름: 당시 적은 `notification-app.<목적>`은 앱이 없어져 맞지 않는다. 지갑 쪽에서는 **미결**이다. 알림 모듈 설계 문서(notification.md 2장)에 이름이 적혀 있지만 소비 코드는 아직 없어, 이 문서에서 확정하지 않는다. kafka.md 토픽 목록의 소비 그룹 칸도 알림 쪽이 구현할 때 채운다.
- 알림 종류 매핑: 충전 완료, 지급 완료(정상·위약금 구분), 환불 완료(전부·일부), 환전 완료·거절·송금 실패·최종 실패 중 무엇을 알림으로 만들지.
- 일반 유저 수신: 충전·환불 완료는 일반 유저(강의 구매자)도 받는다.
- 멱등: 이벤트마다 위 표의 멱등 키를 `(eventType, 대상 ID)`로 쓴다. 송금 실패만 송금 시도 ID다.
- 순서: 같은 환전의 이벤트는 같은 key지만 Modulith 전송은 순서가 바뀔 수 있다. 송금 실패 알림이 완료 알림보다 늦게 오면 어떻게 보일지 정한다(예: 업무 시각 비교, 완료 뒤의 실패는 무시).
- 문구: 이벤트에는 사실만 있다. 금액·시각 표시는 알림 쪽이 만든다.
