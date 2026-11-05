# 지갑 내부 API 계약

강의·출연이 HP를 보류·반환·지급하고 출연 취소 위약금을 정산하고, 더는 쓰지 않는 보류를 무효화할 때 쓰는 내부 HTTP API다. 지갑이 내부 API(#273)를, `shared.billing`이 계약(`dto`·`domain`)과 Client(`out.BillingApiClient`, #272)를 제공하고, 소비 모듈은 Client만 쓴다 (D62, D64).

- 공통 규칙(경로, 내부 API 키 인증, 회원 세션 불가, 진입점 차단, RsData·ProblemDetail, Swagger 노출)은 [수익 내부 API 계약 1장](internal-earnings.md#1-공통-규칙)과 같다.
- 소비 모듈: 출연(#274), 강의(#275~#277). 같은 JVM 호출용 shared `BillingPort`는 #486에서 지웠다(#280).
- 지갑 쪽 구현(#273)은 이 문서를 따르고, 바뀌면 이 문서를 함께 고친다.

## 1. 경로

모두 `POST`, 요청 본문은 JSON(`Content-Type: application/json`)이다. 요청 하나를 지갑 로컬 트랜잭션 하나로 처리한다.

확정 차감(capture: 보류 HP를 다른 회원에게 옮기지 않고 최종 차감)은 내부 API로 노출하지 않는다(#564, BD-135). 강의·출연의 확정은 모두 지급(`/payouts`)이고, 확정 차감은 지갑 안의 환불·환전에서만 쓴다. 강의·출연 보류(MIXED)의 확정 차감은 지갑이 `HOLD_TYPE_NOT_SUPPORTED`로 막는다.

| 경로 | 요청 (`shared.billing.dto`) | 응답 `data` | 멱등 키 |
| --- | --- | --- | --- |
| `/api/v1/internal/billing/holds` | `HoldCommand` | `HoldResultDto` | 참조 대상(`referenceType` + `referenceId`). 참조 대상당 보류 1건 |
| `/api/v1/internal/billing/releases` | `ReleaseCommand` | `ReleaseResultDto` | 보류 하나 안의 `releaseRequestId` |
| `/api/v1/internal/billing/payouts` | `PayoutCommand` | `PayoutResultDto` | 보류 하나 안의 `payoutRequestId` |
| `/api/v1/internal/billing/penalty-settlements` | `PenaltyCommand` | `PenaltyResultDto` | 보류 하나 안의 `cancellationId` |
| `/api/v1/internal/billing/hold-voids` | `HoldVoidCommand` | `HoldVoidResultDto` | 참조 대상(`referenceType` + `referenceId`). 참조 대상당 무효화 1건 (#479) |

- 같은 멱등 키 + 같은 내용이면 처음 결과를 `replayed = true`로 돌려준다. 같은 키 + 다른 내용이면 409 `IDEMPOTENCY_KEY_CONFLICT`다.
- 반환·지급의 `amountHp`가 `null`이면 남은 보류 전액이다. `isFull()`은 본문에 넣지 않는다.

보류 무효화 (#479): 호출한 모듈이 참조 대상의 보류를 더는 쓰지 않는다고 알린다. 보류 결과를 모르는 채로 업무를 취소·보상할 때(보류가 결과 불명이었거나, 보류가 뒤늦게 도착할 수 있을 때) 반환 대신 부른다.

- 요청은 `{userId, referenceType, referenceId}`이다. `userId`는 보류한 회원이다.
- 결과 `outcome`:
  - `RELEASED`: 남은 보류를 전부 반환했다(`releasedHp` > 0). 반환 요청 ID `VOID`로 기존 반환과 같이 처리한다(원장 `RELEASE`).
  - `ALREADY_CLOSED`: 보류가 이미 끝났다(전부 반환·지급). 아무것도 바꾸지 않는다(`releasedHp` 0).
  - `VOIDED`: 보류가 없다. 무효 표시만 남긴다(`releasedHp` 0).
- 세 경우 모두 무효화 기록을 남긴다. 기록이 있는 참조 대상의 보류 요청은 409 `HOLD_VOIDED`로 거절한다. 무효화 전에 만든 보류의 재요청도 거절한다.
- 같은 참조 대상에 다시 보내면 처음 결과를 `replayed = true`로 돌려준다. 다른 회원의 보류나 무효화 기록이면 409 `HOLD_OWNER_MISMATCH`다.
- 보류와 같은 지갑 락으로 처리해, 같은 참조 대상의 보류와 무효화가 동시에 와도 "보류가 남은 채 무효화됨"은 생기지 않는다.
- 무효화는 되돌릴 수 없다. 다시 결제하려면 새 참조 대상(주문 항목·출연 요청)으로 보류한다.

요청 예 (구매 취소 전체 반환):

```json
{
  "userId": 7,
  "referenceType": "LECTURE_ORDER_ITEM",
  "referenceId": 31,
  "releaseRequestId": "LECTURE-CANCEL-31",
  "amountHp": null
}
```

성공 응답 예:

```json
{
  "statusCode": 200,
  "message": "HP를 반환했습니다.",
  "data": {
    "referenceType": "LECTURE_ORDER_ITEM",
    "referenceId": 31,
    "releaseRequestId": "LECTURE-CANCEL-31",
    "releasedHp": 10000,
    "remainingHeldHp": 0,
    "replayed": false
  }
}
```

실패 응답은 지갑 `BillingErrorCode`의 HTTP 상태와 `ProblemDetail`(`code`, `detail`)이다. 키가 없거나 틀리면 401(본문 없음)이다.

```json
{ "status": 409, "detail": "HP 잔액이 부족합니다.", "code": "INSUFFICIENT_BALANCE" }
```

## 2. 결과와 실패 분류 (D64, D66)

Client의 다섯 메서드(`hold`·`release`·`payout`·`settlePenalty`·`voidHold`)는 `BillingCallResult<T>`를 돌려주거나 `BillingCallException`을 던진다.

| 응답·예외 | 결과 |
| --- | --- |
| 2xx이고 `data`가 있음 | `Succeeded(dto)` |
| 408·425·429 | `BillingCallException(RETRYABLE)`. 지갑이 요청을 처리하지 않았다는 응답이다(요청 시간 초과, 너무 이름, 요청 한도 초과) |
| 그 밖의 4xx | `Failed(code, detail, httpStatus, rawCode)`. `code`가 아래 목록에 없거나 본문이 없으면 `UNMAPPED`이고, 원래 문자열은 `rawCode`에 남는다 |
| 5xx(코드가 있어도), 2xx인데 본문을 읽을 수 없거나 `data`가 없음, 그 밖의 상태 | `BillingCallException(UNKNOWN_RESULT)`. 받은 HTTP 상태와 오류 `code`를 담는다 |
| 연결 거부·주소를 찾지 못함(`ConnectException`·`UnknownHostException`·`NoRouteToHostException`) | `BillingCallException(RETRYABLE)`, HTTP 상태 0 |
| 그 밖의 I/O 오류(연결·응답 시간 초과, 응답 도중 끊김) | `BillingCallException(UNKNOWN_RESULT)`, HTTP 상태 0 |
| 시도 한 번이 전체 상한(8초)을 넘음 | `BillingCallException(UNKNOWN_RESULT)`, HTTP 상태 0 (D66) |
| 회로가 열려 요청을 보내지 않음 | `BillingCallException(RETRYABLE)`, HTTP 상태 0, `rawCode` `CIRCUIT_OPEN` (D66) |

- `RETRYABLE`은 지갑이 요청을 처리하지 않은 것이 확실하다는 뜻이다. `UNKNOWN_RESULT`는 지갑이 처리했을 수도 있다는 뜻이다. 둘 다 같은 멱등 키로 다시 보내면 HP가 두 번 움직이지 않는다.
- Client는 `RETRYABLE`이면 그 자리에서 첫 호출 포함 3회까지 다시 보낸다(200ms부터 두 배씩, 지터 0.2, 최대 약 0.7초). 다 실패하면 마지막 예외를 그대로 던지므로, 호출한 쪽이 받는 분류는 위 표와 같다. `UNKNOWN_RESULT`·`Failed`·`CIRCUIT_OPEN`은 다시 보내지 않는다 (D66).
- 5xx를 결과 불명으로 두는 이유: 지갑이 커밋한 뒤 응답을 만들다 실패해도 5xx다. 지갑이 불변식 위반으로 응답하는 500 `HOLD_STATE_INCONSISTENT`·`CHARGE_PRINCIPAL_MISMATCH`도 결과 불명으로 오고, 호출한 쪽은 재시도 한도를 넘기면 운영 확인 대상(DEAD)으로 둔다.
- `Failed`를 거절이라 부르지 않는다. 업무 거절(`INSUFFICIENT_BALANCE`)뿐 아니라 재시도 대상(`WALLET_NOT_FOUND`)도 온다. 재시도할지는 호출한 쪽이 작업 유형별로 정한다(강의 D57).
- `detail`은 사용자에게 보여 줄 수 있는 지갑 메시지다. `rawCode`와 원인 예외는 사용자에게 보여 주지 않고 로그와 작업 시도 기록에만 남긴다.

`BillingFailureCode` (`shared.billing.domain`): 지갑 `BillingErrorCode` 가운데 이 다섯 호출에서 나올 수 있는 코드만 같은 이름으로 둔다. 지갑 코드와의 대조는 #273이 테스트로 고정한다.

| 묶음 | 코드 |
| --- | --- |
| 지갑·잔액 | `WALLET_NOT_FOUND`, `INSUFFICIENT_BALANCE`, `BALANCE_OVERFLOW`, `INVALID_POINT_AMOUNT` |
| 멱등 | `IDEMPOTENCY_KEY_CONFLICT` |
| 보류 | `HOLD_NOT_FOUND`, `HOLD_OWNER_MISMATCH`, `HOLD_ALREADY_CLOSED`, `HOLD_VOIDED`(#479), `HOLD_TYPE_NOT_SUPPORTED`, `HOLD_CHARGE_NOT_FOUND`, `HOLD_STATE_INCONSISTENT`(500), `CHARGE_PRINCIPAL_MISMATCH`(500) |
| 반환·지급 | `RELEASE_AMOUNT_EXCEEDS_HELD`, `PAYOUT_AMOUNT_EXCEEDS_HELD`, `PAYOUT_TO_PAYER`, `PAYOUT_SOURCE_MISMATCH`, `PAYOUT_ALREADY_SETTLED` |
| 위약금 (출연) | `PENALTY_BASE_EXCEEDS_HELD`, `PENALTY_AMOUNT_ZERO` |
| 락 대기 | `HP_LOCK_TIMEOUT`(503). 결과 불명으로 오며, 같은 키로 다시 보내면 된다 (#273) |
| 변환 불가 | `UNMAPPED` |

## 3. 사용법 (강의·출연)

```java
try {
    BillingCallResult<ReleaseResultDto> result = billingApiClient.release(command);
    switch (result) {
        case BillingCallResult.Succeeded<ReleaseResultDto> s -> recordReleased(s.value());
        case BillingCallResult.Failed<ReleaseResultDto> f -> recordFailed(f.code(), f.rawCode());
    }
} catch (BillingCallException e) {
    recordNotAnswered(e.getKind(), e.getHttpStatus(), e.getRawCode());
}
```

- 로컬 트랜잭션 밖에서 부른다. 업무 상태와 작업을 먼저 커밋하고, 결과는 짧은 트랜잭션으로 기록한다 (D62).
- 설정: `hapbang.clients.billing.base-url`(billing-app 주소, 로컬 `http://localhost:8082`), 연결 2초·응답 5초(`connect-timeout`·`read-timeout`), 내부 API 키 `hapbang.internal-api.key`. base-url이 없으면 Client 빈이 생기지 않는다.
- 회복성(#411): Client 안에서 `Retry( CircuitBreaker( TimeLimiter( HTTP ) ) )`를 건다.
  - Circuit Breaker는 `BillingCallException`만 실패로 세고 429와 `HP_LOCK_TIMEOUT`은 세지 않는다. `Failed`는 지갑이 응답한 결과라 세지 않는다.
  - `isCircuitOpen()`이 `true`면 지금 부르면 `CIRCUIT_OPEN`으로 실패한다. 강의는 HP 작업 실행기가 선점 직전에 보고 이때 선점하지 않는다(배치·요청 안 즉시 실행 모두, 강의 D67 → D79). 출연은 배치가 건너뛴다(#439). 회로는 호출이 없어도 OPEN 시간이 지나면 시험 중(HALF_OPEN)으로 넘어간다.
  - 설정은 `hapbang.clients.billing.resilience.*`이고, 기본값·지표·health는 [`docs/billing/CONTEXT.md`](../billing/CONTEXT.md) 2장에 있다.
- correlation ID 헤더는 보내지 않는다. 요청 ID는 본문 필드로 전달하고, 추적 헤더는 추적(tracing)을 도입할 때 정한다.

## 4. 서비스 간 HP 대사 조회 (#577)

강의·출연이 자기 대상(주문 항목·요청서·모집글)의 업무 상태가 지갑 기록과 맞는지 비교할 때 쓴다. 대사의 주체는 각 업무 모듈이고, 지갑은 기록만 준다(BD-137). 두 경로 모두 HP를 움직이지 않고, 인증·응답 형식·회로·재시도는 위와 같다.

| 메서드·경로 | 요청 | 응답 `data` | Client |
| --- | --- | --- | --- |
| `POST /api/v1/internal/billing/holds/query` | `HoldQueryCommand{referenceType, referenceIds}`. 참조 ID 1~500개 | `List<HoldStateDto>`: 요청 순서대로, 같은 ID는 한 번 | `queryHolds` |
| `GET /api/v1/internal/billing/holds?referenceType=&updatedAfter=&afterId=&size=` | 참조 유형, 이 시각 이후(ISO 8601), 커서(처음 0), 건수 1~500(기본 100) | `HoldStatePageDto{items, nextUpdatedAfter, nextAfterId, hasMore}` | `listHolds` |

`HoldStateDto` (참조 대상 하나)

| 필드 | 뜻 |
| --- | --- |
| `referenceType`·`referenceId` | 요청한 참조 대상 |
| `hold` | 보류. 없으면 `null`. `status`(`BillingHoldStatus`: `HELD`·`PARTIALLY_PROCESSED`·`RELEASED`·`PAID`·`CAPTURED`·`MIXED`), `payerUserId`(보류한 회원), 금액 `original`·`remaining`·`released`·`paid`·`captured` |
| 금액 | 각각 `{chargeHp, revenueHp, totalHp}`(충전 HP·수익 HP·합계). HP 종류마다 `original = remaining + released + paid + captured` 다. 반환·지급·확정 차감은 보류 상세(처리 건) 합이다 |
| `voided` | 보류 무효화 기록(#479). 없으면 `null`. `{outcome, releasedHp}`. 보류 없이 무효화만 있을 수 있다(`hold`는 `null`) |
| `settlements` | 그 보류에서 나간 지급 건 전부(상태 무관). `{sourceType, sourceId, payoutType(NORMAL·PENALTY), receiverUserId, grossAmountHp, paidAmountHp, status(BillingSettlementStatus)}` |

- 지갑에 기록이 없는 참조 ID도 `hold`·`voided`가 `null`이고 `settlements`가 빈 기록으로 준다. 빠진 ID가 없다.
- 지갑·보류·원장 ID와 충전 건별 배분은 담지 않는다. HP 종류별 금액은 대사용 조회에서만 준다(다른 내부 API 결과는 합계만 준다).
- 목록 조회는 마지막 변경 시각·보류 순 키셋이다. `nextUpdatedAfter`·`nextAfterId`는 항상 있고 이 페이지의 마지막 보류 위치다(빈 페이지면 요청한 커서 그대로). `hasMore`가 `true`면 다음 요청의 `updatedAfter`·`afterId`로 그대로 넘겨 이어 읽고, `false`면 멈추고 커서를 저장해 다음 대사를 거기서 시작한다. 커서는 확정 대기 시간을 넘지 않으므로 저장한 커서에서 시작해도 빠지는 보류가 없다. `nextAfterId`는 커서 값으로만 쓴다. 마지막 변경 시각이 같은 보류는 커서의 보류 위치로 나뉘어 페이지 경계에서 빠지거나 겹치지 않는다. 보류는 반환·지급될 때마다 마지막 변경 시각이 바뀌어 목록 뒤로 간다. 보류 없이 무효화만 있는 참조 대상은 목록에 없다.
- 목록 조회는 최근 10초(확정 대기 시간, `hapbang.billing.hold-query.settle-window`) 안에 바뀐 보류를 주지 않는다. 마지막 변경 시각은 커밋이 아니라 기록 시점의 시각이라, 늦게 커밋된 보류가 이미 지나간 커서 뒤에 끼어 영영 빠지는 것을 막기 위해서다. 시각 기록 뒤 가장 오래 기다리는 곳(같은 참조 대상 보류의 UNIQUE 대기)이 락 대기 상한 3초에서 끝나므로 그보다 넉넉히 둔다. 호출자는 커서를 되돌려 겹쳐 읽지 않아도 된다. 참조 대상별 조회에는 이 제한이 없다. 마지막 변경 시각과 기준 시각은 모두 지갑 앱 시각이고, 지갑 앱 인스턴스 하나(replica 1)를 전제로 한다. 여러 인스턴스로 늘리면 인스턴스 사이 시각 차이만큼 여유가 줄어든다.
- 한 응답 안의 값은 읽기 전용 트랜잭션 하나에서 읽지만 같은 스냅숏은 아니다(READ COMMITTED). 대사 중에 처리가 끼면 다음 대사에서 다시 본다.
- 오류: 참조 ID 0개·501개 이상, 보류할 수 없는 참조 유형, `size` 1~500 밖, `afterId` 음수, 시각 형식 오류는 400 `INVALID_REQUEST`(`Failed`). 키 없음 401.
