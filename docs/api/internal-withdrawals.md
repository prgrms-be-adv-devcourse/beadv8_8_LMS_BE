# 회원 탈퇴 내부 API 계약

회원 #593(탈퇴·복구)이 탈퇴해도 되는지 확인할 때 쓰는 내부 HTTP API다. 회원 앱이 탈퇴 확인을 지휘(오케스트레이션)하고, 지갑·출연·강의가 이 문서대로 제공한다.

- 구조: [수익 내부 API 계약](internal-earnings.md)과 같다. 제공 모듈이 내부 API와 `shared.<제공 모듈>` Client 메서드를 제공하고, 회원은 Client만 쓴다. 공통 DTO(`WithdrawalBlockReasonDto`, 조회 응답 `WithdrawalBlocksDto`)는 탈퇴가 회원 개념이라 `shared.member.dto`에 둔다. 지갑의 닫기·다시 열기 요청·응답은 지갑 전용이라 지갑이 `shared.billing.dto`에 둔다.
- 공통 규칙(경로, 내부 API 키, 회원 세션 불가, 외부 차단, RsData·ProblemDetail, Swagger)은 수익 내부 API 계약 1장과 같다.

## 1. 흐름

```
회원: 탈퇴 작업 기록 생성 (탈퇴 작업 ID = withdrawalId)
① 출연  GET  .../appearances/users/{userId}/withdrawal-blocks   → 막힌 이유 (읽기만)
② 강의  GET  .../lectures/users/{userId}/withdrawal-blocks      → 막힌 이유 (읽기만)
③ 지갑  POST .../billing/withdrawals/{withdrawalId}/close        → 막힌 이유가 없으면 지갑을 닫음
   - ①②③ 중 이유가 하나라도 있으면: ③에서 닫았으면 reopen → 회원은 409 WITHDRAWAL_BLOCKED + 이유 모음
④ 회원: WITHDRAWN 저장 → refresh token 폐기 → UserModifiedEvent(WITHDRAWN)
   - ④가 실패하면 지갑 reopen (보상)
복구(30일 안 재로그인): 지갑 reopen → 회원 ACTIVE → UserModifiedEvent(ACTIVE)
```

- 막힌 이유가 있으면 자동으로 취소·환불하지 않는다. 회원이 안내를 보고 직접 정리한 뒤 다시 탈퇴한다.
- 지갑은 확인과 닫기를 자기 DB 한 트랜잭션(지갑 행 잠금)으로 한다. 닫힌 뒤에는 새 결제가 들어오지 않는다.
- 출연·강의는 읽기만 한다. 확인과 `UserModifiedEvent` 도착 사이 몇 초 동안 탈퇴 회원이 **받는 쪽**인 거래(게스트 지정 등)가 생길 수 있다. 돈은 상대 지갑에 보류된 채라 잃지 않고, 이벤트가 오면 복제본 상태로 그 거래를 막는다(이미 정지·탈퇴 회원과의 거래를 막는 코드가 있다).

## 2. 공통 DTO

`shared.member.dto.WithdrawalBlockReasonDto`

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| `code` | String | 막힌 이유 코드. 모듈 접두어를 붙인다 (예: `APPEARANCE_IN_PROGRESS`, `LECTURE_ORDER_HOLDING`, `BILLING_CHARGE_BALANCE_REMAINING`). FE가 코드별로 바로가기(환불하기, 주문 내역)를 붙인다 |
| `message` | String | 사용자에게 그대로 보여 줄 문장. 무엇을 정리하면 되는지 적는다 (예: "충전 HP 12,000이 남아 있어요. 환불한 뒤 탈퇴할 수 있어요") |
| `availableAt` | LocalDate (KST) 또는 null | 기다리면 풀리는 경우 그 날짜 (예: 강의 보류 끝나는 날). 없으면 빠진다 |

- 이유는 **그 거래의 주인 모듈이 설명**한다. 보류는 지갑에도 있지만 맥락(어떤 출연·주문인지)을 아는 출연·강의가 적고, 지갑은 자기 것(잔액, 환불·환전 대기)만 적는다. 같은 보류를 두 번 적지 않는다.

## 3. 출연·강의: 막힌 이유 조회

`GET /api/v1/internal/appearances/users/{userId}/withdrawal-blocks`
`GET /api/v1/internal/lectures/users/{userId}/withdrawal-blocks`

- 읽기만 한다. 멱등 키가 없다.
- 응답 `data`: `shared.member.dto.WithdrawalBlocksDto` `{ "reasons": [WithdrawalBlockReasonDto] }`. 막힌 이유가 없으면 빈 목록이다. 복제 회원 정보가 없는 회원도 빈 목록이다.

```json
{
  "statusCode": 200,
  "message": "탈퇴 확인을 마쳤습니다.",
  "data": {
    "reasons": [
      { "code": "APPEARANCE_IN_PROGRESS", "message": "진행 중인 출연 1건이 있어요. 출연을 마치거나 취소한 뒤 탈퇴할 수 있어요" }
    ]
  }
}
```

막을 조건 (각 모듈이 확정한다)

| 모듈 | 막을 조건 (안) |
| --- | --- |
| 출연 | 호스트·게스트로 진행 중인 출연 요청·출연·모집·지원, 처리 중인 출연 취소 요청·이의 제기, 지급 전 출연료 |
| 강의 | 확정(#625, 강의 D75). 아래 표 |

강의 막을 조건 (#625, 강의 D75). 판정은 주문 항목 단위이고, 한 항목은 위에서부터 먼저 맞는 이유 하나로만 센다(구매자는 이의 제기가 먼저다). 같은 이유는 건수를 합친다. 응답 순서는 이 표의 순서다.

| code | 역할 | 조건 | message (N은 천 단위 쉼표) | availableAt |
| --- | --- | --- | --- | --- |
| `LECTURE_ORDER_PROCESSING` | 구매자 | 결제·결제 취소·구매 취소·반환 처리 중(`HOLD_PENDING`·`HOLD_COMPENSATING`·`CANCEL_PENDING`·`RETURN_PENDING`) | 처리 중인 주문 N건이 있어요. 잠시 뒤 다시 시도해 주세요. | 없음 |
| `LECTURE_ORDER_HOLDING` | 구매자 | 구매 HP 보류 중(`PURCHASED`·`CONFIRMED`·`PAYOUT_PENDING`). 구매 확정 뒤에도 지급 예정일(구매일 + 31일)까지 막는다 | 구매한 강의 N건의 이의 제기 기간이 끝나지 않았어요. 기간이 끝난 뒤 탈퇴할 수 있어요. | 아래 |
| `LECTURE_OBJECTION_FILED` | 구매자 | 이의 제기 접수 중 | 처리 중인 이의 제기 N건이 있어요. 결과가 나온 뒤 탈퇴할 수 있어요. | 없음 |
| `LECTURE_SALE_PAYOUT_PENDING` | 판매자 | 아직 지급하지 않은 판매 수익. [수익 계약](internal-earnings.md) 3.3의 미지급 다섯 상태와 같다(지급 중단 포함) | 아직 지급되지 않은 판매 수익 N건이 있어요. 지급이 끝난 뒤 탈퇴할 수 있어요. | 아래 |
| `LECTURE_ON_SALE` | 판매자 | 삭제하지 않은 판매 중·검수 대기 강의. 강의를 삭제하면 신규 판매만 멈추고 기존 구매자 시청·지급은 그대로다 | 판매 중이거나 검수 대기 중인 강의 N개가 있어요. 강의를 삭제한 뒤 탈퇴할 수 있어요. | 없음 |

- 결제 실패와 끝난 항목(구매 취소·반환·지급 완료), 반려된 강의는 막지 않는다.
- `availableAt`은 묶인 항목의 가장 늦은 지급 예정 시각의 KST 날짜다(수익 API의 `payoutScheduledAt`과 같은 날짜). 지급 예정 시각을 정할 수 없는 항목(수익 상태 `ON_HOLD`·`FAILED`)이 섞였거나, 가장 늦은 지급 예정 시각이 이미 지나 지급 처리 중이면 빠진다.
- 오류: 내부 키 없음·틀림 401(본문 없음), `userId`가 숫자가 아님 400 `INVALID_REQUEST`.
- Client: `shared.lecture.out.LectureApiClient.getWithdrawalBlocks(long userId)`. 실패는 수익 계약 4장의 `LectureApiCallException`과 같다.

## 4. 지갑: 확인하고 닫기 · 다시 열기

### 4.1 `POST /api/v1/internal/billing/withdrawals/{withdrawalId}/close`

요청 `{ "userId": 42 }`

- 한 트랜잭션에서 지갑 행을 잠그고 → 막힌 이유를 확인하고 → 이유가 없으면 지갑을 닫는다.
- 응답 `data`: `{ withdrawalId, outcome, reasons, replayed }`
  - `outcome` `CLOSED`: 닫았다 (`reasons` 빈 목록)
  - `outcome` `BLOCKED`: 막힌 이유가 있어 닫지 않았다
- 멱등: 같은 `withdrawalId` + 같은 `userId`면 처음 결과를 `replayed = true`로 돌려준다. 같은 `withdrawalId` + 다른 `userId`면 409 `IDEMPOTENCY_KEY_CONFLICT`.
- 늦은 닫기 거절: 다시 열기(4.2)를 이미 받은 `withdrawalId`로 닫기가 오면 지갑을 닫지 않고 409 `WITHDRAWAL_ALREADY_REOPENED`로 거절한다. 회원이 결과를 모르는 닫기를 정리(다시 열기)한 뒤, 네트워크에서 늦어진 그 닫기가 도착해 지갑이 닫힌 채 남는 일을 막는다(보류 무효화 뒤 보류를 `HOLD_VOIDED`로 거절하는 것과 같은 방식)
- 지갑이 아직 없는 회원(가입 이벤트 미반영)은 지갑을 만들지 않고 `CLOSED` 기록만 남긴다. 나중에 지갑을 만들 때 이 기록으로 닫힌 지갑을 만든다(#623, BD-141).
- 지갑 막을 조건 (#623 확정)

| code | 조건 | message |
| --- | --- | --- |
| `BILLING_CHARGE_BALANCE_REMAINING` | 충전 HP 가용 잔액 > 0 | "충전 HP 12,000이 남아 있어요. 환불한 뒤 탈퇴할 수 있어요." |
| `BILLING_REVENUE_BALANCE_REMAINING` | 수익 HP 가용 잔액 > 0 | "수익 HP 3,000이 남아 있어요. 환전을 신청한 뒤 탈퇴할 수 있어요." |
| `BILLING_REFUND_IN_PROGRESS` | 환불 REQUESTED·PROCESSING·MANUAL_REVIEW_REQUIRED | "처리 중인 환불이 있어요. 환불이 끝난 뒤 탈퇴할 수 있어요." |
| `BILLING_EXCHANGE_IN_PROGRESS` | 환전 REQUESTED·APPROVED·PROCESSING·FAILED·UNKNOWN | "처리 중인 환전이 있어요. 송금이 끝난 뒤 탈퇴할 수 있어요." 모두 신청·승인 상태면 `availableAt` = 가장 늦은 정산 예정일 |
| `BILLING_CHARGE_IN_PROGRESS` | 충전 PROCESSING(PG 승인 결과 확인 중) | "확인 중인 충전이 있어요. 잠시 뒤 다시 시도해 주세요." |

  - 주문만 한 충전(PENDING)은 막지 않는다. 닫힌 뒤 승인하면 PG를 부르기 전에 거절된다.
  - 출연·강의 보류(보류 HP)는 그 모듈이 적으므로 지갑은 적지 않는다(2장).
- 닫힌 지갑: 충전 주문, 충전 승인 시작(PG 호출 전), 새 보류(강의·출연 결제, 환불·환전 신청)를 409 `WALLET_CLOSED`로 거절한다. 이미 생긴 의무(닫기 전에 만든 보류의 재요청, 보류 반환·지급 받기·위약금, 결제 복구)는 받는다. 강의·출연이 `WALLET_CLOSED`를 정상 거절로 처리하는 일은 후속 이슈다.
- 닫힌 지갑에 들어온 HP는 지갑에 남는다. 환불·환전은 회원이 복구(4.2 다시 열기)한 뒤에 할 수 있다. 지갑은 들어올 때 warn 로그를 남기고, 잔액이 남은 닫힌 지갑 수를 지표(`hapbang.billing.wallet.closed.with.balance`, 수집 때마다 셈, 실패하면 NaN)로 낸다(BD-141 (7)). 정상 흐름에서는 출연·강의가 받을 돈이 남은 회원을 3장 조건으로 먼저 막는다.
- 오류: 같은 `withdrawalId` + 다른 `userId` 409 `IDEMPOTENCY_KEY_CONFLICT`, 다시 열린 `withdrawalId` 409 `WITHDRAWAL_ALREADY_REOPENED`, `userId` 없음 400 `INVALID_REQUEST`, 락 대기 초과 503 `HP_LOCK_TIMEOUT`(처리하지 않음).
- Client: `shared.billing.out.BillingApiClient.closeForWithdrawal(WithdrawalCloseCommand)` → `WithdrawalCloseResultDto`. 처리 안 됨이 확실하면 `BillingCallException(RETRYABLE)`, 결과 불명이면 `UNKNOWN_RESULT`, 업무 거절은 `BillingCallResult.Failed`.

### 4.2 `POST /api/v1/internal/billing/withdrawals/{withdrawalId}/reopen`

요청 `{ "userId": 42 }`

- 이 `withdrawalId`로 닫은 지갑을 다시 연다. 보상(다른 모듈에서 막힘, 회원 저장 실패)과 복구(30일 안 재로그인)에서 부른다.
- 응답 `data`: `{ withdrawalId, outcome, replayed }`
  - `REOPENED`: 다시 열었다
  - `NOT_CLOSED`: 이 작업으로 닫힌 적이 없다(닫기가 실패했거나 `BLOCKED`였다). 지갑은 바꾸지 않는다
- 두 결과 모두 다시 열기 기록을 남긴다. 기록이 있는 `withdrawalId`의 닫기는 4.1대로 거절한다
- 멱등: 같은 `withdrawalId`로 다시 오면 처음 결과를 `replayed = true`로 돌려준다. `userId`가 다르면 409 `IDEMPOTENCY_KEY_CONFLICT`.
- 지갑이 다른 `withdrawalId`로 닫혀 있으면 열지 않는다(`NOT_CLOSED`). 지갑이 없으면 이 작업이 회원의 마지막 `CLOSED` 기록일 때 `REOPENED`다.
- Client: `BillingApiClient.reopenForWithdrawal(WithdrawalReopenCommand)` → `WithdrawalReopenResultDto`.

## 5. 실패 처리 (회원)

| 단계 | 실패 | 회원 처리 |
| --- | --- | --- |
| ①② 막힌 이유 조회 | 응답 없음·5xx·4xx | 탈퇴를 멈추고 503 `WITHDRAWAL_UNAVAILABLE` (아직 아무것도 닫지 않음) |
| ③ 닫기 | 처리 안 됨이 확실 (연결 거부 등) | 탈퇴를 멈추고 503 |
| ③ 닫기 | 결과 불명 (시간 초과·5xx) | reopen으로 보상을 시도하고 503. reopen도 결과 불명이면 배치가 같은 `withdrawalId`로 다시 부른다 |
| ④ 회원 저장 | 실패 | reopen(보상). 실패하면 배치가 다시 부른다 |

- 계속 실패한 작업은 관리자 확인 대상으로 남긴다.
- 출연·강의 조회를 먼저 하고 지갑 닫기를 마지막에 해서, 되돌릴 일이 생기는 경우를 줄인다.

## 6. 작업 전 결정할 것

- ~~강의: 판매 중 강의는 판매 중지를 필수로 할지 (구매자 시청은 유지)~~ → 막는다. 판매 중지 상태를 새로 두지 않고 강의 삭제로 정리한다(강의 D75, 3장)
- 각 모듈: 3장의 막을 조건이 빠짐없는지 (지갑 4장은 #623에서 확정했다. 환전이 1원 단위라 소액 수익 HP 문제는 없다)
