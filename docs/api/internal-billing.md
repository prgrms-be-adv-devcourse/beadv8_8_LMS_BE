# 지갑 내부 API 계약

강의·출연이 HP를 보류·반환·지급하고 출연 취소 위약금을 정산할 때 쓰는 내부 HTTP API다. 지갑이 내부 API(#273)를, `shared.billing`이 계약(`dto`·`domain`)과 Client(`out.BillingApiClient`, #272)를 제공하고, 소비 모듈은 Client만 쓴다 (D62, D64).

- 공통 규칙(경로, 내부 API 키 인증, 회원 세션 불가, 진입점 차단, RsData·ProblemDetail, Swagger 노출)은 [수익 내부 API 계약 1장](internal-earnings.md#1-공통-규칙)과 같다.
- 소비 모듈: 출연(#274), 강의(#275~#277). 기존 shared `BillingPort`는 전환이 끝나면 지운다(#280).
- 지갑 쪽 구현(#273)은 이 문서를 따르고, 바뀌면 이 문서를 함께 고친다.

## 1. 경로

모두 `POST`, 요청 본문은 JSON(`Content-Type: application/json`)이다. 요청 하나를 지갑 로컬 트랜잭션 하나로 처리한다.

| 경로 | 요청 (`shared.billing.dto`) | 응답 `data` | 멱등 키 |
| --- | --- | --- | --- |
| `/api/v1/internal/billing/holds` | `HoldCommand` | `HoldResultDto` | 참조 대상(`referenceType` + `referenceId`). 참조 대상당 보류 1건 |
| `/api/v1/internal/billing/releases` | `ReleaseCommand` | `ReleaseResultDto` | 보류 하나 안의 `releaseRequestId` |
| `/api/v1/internal/billing/payouts` | `PayoutCommand` | `PayoutResultDto` | 보류 하나 안의 `payoutRequestId` |
| `/api/v1/internal/billing/penalty-settlements` | `PenaltyCommand` | `PenaltyResultDto` | 보류 하나 안의 `cancellationId` |

- 같은 멱등 키 + 같은 내용이면 처음 결과를 `replayed = true`로 돌려준다. 같은 키 + 다른 내용이면 409 `IDEMPOTENCY_KEY_CONFLICT`다.
- 반환·지급의 `amountHp`가 `null`이면 남은 보류 전액이다. `isFull()`은 본문에 넣지 않는다.

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

## 2. 결과와 실패 분류 (D64)

Client의 네 메서드(`hold`·`release`·`payout`·`settlePenalty`)는 `BillingCallResult<T>`를 돌려주거나 `BillingCallException`을 던진다.

| 응답·예외 | 결과 |
| --- | --- |
| 2xx이고 `data`가 있음 | `Succeeded(dto)` |
| 4xx | `Failed(code, detail, httpStatus, rawCode)`. `code`가 아래 목록에 없거나 본문이 없으면 `UNMAPPED`이고, 원래 문자열은 `rawCode`에 남는다 |
| 5xx(코드가 있어도), 2xx인데 본문을 읽을 수 없거나 `data`가 없음, 그 밖의 상태 | `BillingCallException(UNKNOWN_RESULT)`. 받은 HTTP 상태와 오류 `code`를 담는다 |
| 연결 거부·주소를 찾지 못함(`ConnectException`·`UnknownHostException`·`NoRouteToHostException`) | `BillingCallException(RETRYABLE)`, HTTP 상태 0 |
| 그 밖의 I/O 오류(연결·응답 시간 초과, 응답 도중 끊김) | `BillingCallException(UNKNOWN_RESULT)`, HTTP 상태 0 |

- `RETRYABLE`은 요청이 지갑에 닿지 않은 것이 확실하다는 뜻이다. `UNKNOWN_RESULT`는 지갑이 처리했을 수도 있다는 뜻이다. 둘 다 같은 멱등 키로 다시 보내면 HP가 두 번 움직이지 않는다.
- 5xx를 결과 불명으로 두는 이유: 지갑이 커밋한 뒤 응답을 만들다 실패해도 5xx다. 지갑이 불변식 위반으로 응답하는 500 `HOLD_STATE_INCONSISTENT`·`CHARGE_PRINCIPAL_MISMATCH`도 결과 불명으로 오고, 호출한 쪽은 재시도 한도를 넘기면 운영 확인 대상(DEAD)으로 둔다.
- `Failed`를 거절이라 부르지 않는다. 업무 거절(`INSUFFICIENT_BALANCE`)뿐 아니라 재시도 대상(`WALLET_NOT_FOUND`)도 온다. 재시도할지는 호출한 쪽이 작업 유형별로 정한다(강의 D57).
- `detail`은 사용자에게 보여 줄 수 있는 지갑 메시지다. `rawCode`와 원인 예외는 사용자에게 보여 주지 않고 로그와 작업 시도 기록에만 남긴다.

`BillingFailureCode` (`shared.billing.domain`): 지갑 `BillingErrorCode` 가운데 이 네 호출에서 나올 수 있는 코드만 같은 이름으로 둔다. 지갑 코드와의 대조는 #273이 테스트로 고정한다.

| 묶음 | 코드 |
| --- | --- |
| 지갑·잔액 | `WALLET_NOT_FOUND`, `INSUFFICIENT_BALANCE`, `BALANCE_OVERFLOW`, `INVALID_POINT_AMOUNT` |
| 멱등 | `IDEMPOTENCY_KEY_CONFLICT` |
| 보류 | `HOLD_NOT_FOUND`, `HOLD_OWNER_MISMATCH`, `HOLD_ALREADY_CLOSED`, `HOLD_TYPE_NOT_SUPPORTED`, `HOLD_CHARGE_NOT_FOUND`, `HOLD_STATE_INCONSISTENT`(500), `CHARGE_PRINCIPAL_MISMATCH`(500) |
| 반환·지급 | `RELEASE_AMOUNT_EXCEEDS_HELD`, `PAYOUT_AMOUNT_EXCEEDS_HELD`, `PAYOUT_TO_PAYER`, `PAYOUT_SOURCE_MISMATCH`, `PAYOUT_ALREADY_SETTLED` |
| 위약금 (출연) | `PENALTY_BASE_EXCEEDS_HELD`, `PENALTY_AMOUNT_ZERO` |
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
- correlation ID 헤더는 보내지 않는다. 요청 ID는 본문 필드로 전달하고, 추적 헤더는 추적(tracing)을 도입할 때 정한다.
