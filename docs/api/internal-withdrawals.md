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
| 강의 | 구매자: 보류 중인 주문(`availableAt` = 보류 끝나는 날)·이의 제기 / 판매자: 지급 전 판매 건, 판매 중 강의 |

## 4. 지갑: 확인하고 닫기 · 다시 열기

### 4.1 `POST /api/v1/internal/billing/withdrawals/{withdrawalId}/close`

요청 `{ "userId": 42 }`

- 한 트랜잭션에서 지갑 행을 잠그고 → 막힌 이유를 확인하고 → 이유가 없으면 지갑을 닫는다.
- 응답 `data`: `{ withdrawalId, outcome, reasons, replayed }`
  - `outcome` `CLOSED`: 닫았다 (`reasons` 빈 목록)
  - `outcome` `BLOCKED`: 막힌 이유가 있어 닫지 않았다
- 멱등: 같은 `withdrawalId` + 같은 `userId`면 처음 결과를 `replayed = true`로 돌려준다. 같은 `withdrawalId` + 다른 `userId`면 409 `IDEMPOTENCY_KEY_CONFLICT`.
- 지갑 막을 조건 (안): 충전 HP·수익 HP 잔액, 처리 중인 환불·환전, 복구 대상인 미완료 결제
- 닫힌 지갑 (안): 충전·보류·환전·환불 신청을 거절한다. 이미 생긴 의무(보류 반환·지급 받기, 결제 복구)는 받는다.

### 4.2 `POST /api/v1/internal/billing/withdrawals/{withdrawalId}/reopen`

요청 `{ "userId": 42 }`

- 이 `withdrawalId`로 닫은 지갑을 다시 연다. 보상(다른 모듈에서 막힘, 회원 저장 실패)과 복구(30일 안 재로그인)에서 부른다.
- 응답 `data`: `{ withdrawalId, outcome, replayed }`
  - `REOPENED`: 다시 열었다
  - `NOT_CLOSED`: 이 작업으로 닫힌 적이 없다(닫기가 실패했거나 `BLOCKED`였다). 아무것도 바꾸지 않는다
- 멱등: 같은 `withdrawalId`로 다시 오면 처음 결과를 `replayed = true`로 돌려준다.

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

- 지갑: 999 HP 이하 소액 수익 HP(환전 불가)가 남으면 탈퇴가 계속 막힌다 → 포기 동의 후 허용할지
- 지갑: 지갑이 아직 없는 회원(가입 이벤트 미반영)의 닫기 결과
- 강의: 판매 중 강의는 판매 중지를 필수로 할지 (구매자 시청은 유지)
- 각 모듈: 3장·4장의 막을 조건이 빠짐없는지
