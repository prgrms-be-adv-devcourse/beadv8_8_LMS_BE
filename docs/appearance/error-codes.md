# 출연(Appearance) 모듈: 오류 코드

출연 API의 오류는 Problem Detail(RFC 9457) 형식으로 응답한다. 오류 코드가 추가되면 이 문서도 함께 갱신한다.
성공 응답은 이 형식이 아니라 팀 공통 `RsData`(`statusCode`, `message`, `data`)다 ([module.md](module.md) 6.1, #144).

- 정책과 구조: [module.md](module.md)
- 테스트 명세: [test-spec.md](test-spec.md)

---

## 1. 응답 형식

```http
HTTP/1.1 403 Forbidden
Content-Type: application/problem+json

{
  "title": "Forbidden",
  "status": 403,
  "detail": "인플루언서 이상만 출연 거래에 참여할 수 있습니다.",
  "instance": "/api/v1/appearances/...",
  "code": "NOT_INFLUENCER"
}
```

| 필드 | 설명 |
|---|---|
| `status` | HTTP 상태 코드 |
| `title` | HTTP 상태 이름 |
| `detail` | 사용자에게 보여줄 메시지. 기본 메시지 대신 상황별 메시지가 들어갈 수 있다 |
| `instance` | 요청 경로 |
| `code` | 출연 오류 코드 (아래 표). 프론트는 이 값으로 분기한다 |
| `errors` | `INVALID_REQUEST`일 때만. `[{"field": "title", "message": "제목은 필수입니다."}]` 형식 (공통 핸들러) |

- `type`은 기본값(`about:blank`)이라 응답에서 생략된다.
- (#71) 모듈 핸들러(`AppearanceExceptionHandler`)는 없앴다. `AppearanceException extends BusinessException`이고, global의 공통 핸들러(`GlobalExceptionHandler`)가 모든 모듈의 오류를 같은 형식(Problem Detail + `code`)으로 응답한다 (AGENTS.md 7장).
- 요청 값 검증·형식 오류(400 `INVALID_REQUEST` + `errors`), `@PreAuthorize` 거절(403 `FORBIDDEN`, 비로그인 401 `UNAUTHORIZED`)은 공통 코드로 응답한다.
- 로그인하지 않은 요청의 401, CSRF 토큰이 없는 쓰기 요청의 403은 보안 필터가 응답하며, 이 형식이 아니다 (본문 없음).

## 2. 오류 코드

| 코드 | 상태 | 기본 메시지 | 발생 상황 | 이슈 |
|---|---|---|---|---|
| `INVALID_REQUEST` | 400 | 요청 값이 올바르지 않습니다. | 요청 값 형식·범위 오류, 읽을 수 없는 요청 본문, 거절 사유 500자 초과, 숨김 상태로 목록 필터. 검증 오류면 `errors`에 항목별 메시지. 도메인 형식 확인에 걸리면 `detail`에 제한 내용 | #26, #98, #137 |
| `APPEARANCE_USER_NOT_FOUND` | 404 | 출연 회원 정보를 찾을 수 없습니다. | 출연 모듈에 복제된 회원 정보가 없음 (가입 이벤트 미반영 등) | #31 |
| `APPEARANCE_USER_NOT_ACTIVE` | 403 | 현재 이용할 수 없는 회원입니다. | 탈퇴(`WITHDRAWN`)·정지(`SUSPENDED`) 회원이 출연 거래에 참여 | #31 |
| `NOT_INFLUENCER` | 403 | 인플루언서 이상만 출연 거래에 참여할 수 있습니다. | 출연 회원(복제된 역할)이 일반 유저·관리자. 세션 역할 검사(`@PreAuthorize`) 거절은 공통 `FORBIDDEN` | #31, #98 |
| `CANNOT_REQUEST_SELF` | 400 | 자기 자신에게는 출연을 요청할 수 없습니다. | 호스트와 게스트가 같음 | #98 |
| `FEE_BELOW_MINIMUM` | 400 | 출연료가 최저 출연료보다 적습니다. | 출연료 < 최저 출연료. `detail`에 최저 출연료 금액 표시 | #98 |
| `INVALID_REQUEST_SCHEDULE` | 400 | 수락 기한은 현재 이후이고 방송 예정 일시보다 앞서야 합니다. | 현재 < 수락 기한 < 방송 예정 일시가 아님 | #98 |
| `NOT_REQUEST_HOST` | 403 | 출연 요청서를 보낸 호스트만 수정할 수 있습니다. | 게스트가 요청서 수정. 응답 전 취소는 `detail`이 "…취소할 수 있습니다." | #98, #137 |
| `APPEARANCE_REQUEST_NOT_FOUND` | 404 | 출연 요청서를 찾을 수 없습니다. | 없는 요청서, 호스트·게스트가 아닌 회원의 조회, 보류 미완료·실패 요청서 | #98 |
| `DUPLICATE_APPEARANCE_REQUEST` | 409 | 이미 처리 중이거나 응답 대기 중인 출연 요청이 있습니다. | 같은 게스트에게 진행 중인 요청서가 있음 (동시 요청 포함) | #98 |
| `APPEARANCE_REQUEST_NOT_EDITABLE` | 409 | 게스트가 응답하기 전인 출연 요청서만 수정할 수 있습니다. | `REQUESTED`가 아닌 요청서 수정 | #98 |
| `NOT_REQUEST_GUEST` | 403 | 출연 요청을 받은 게스트만 응답할 수 있습니다. | 게스트가 아닌 회원(호스트 등)이 수락·거절 | #137 |
| `APPEARANCE_REQUEST_NOT_AWAITING_RESPONSE` | 409 | 응답 대기 중인 출연 요청이 아닙니다. | 이미 수락·거절·취소된 요청서에 응답·취소 | #137 |
| `APPEARANCE_REQUEST_EXPIRED` | 409 | 수락 기한이 지난 출연 요청입니다. | 현재 시각 ≥ 수락 기한 (만료 처리 전이어도) | #137 |
| `APPEARANCE_REQUEST_MODIFIED` | 409 | 요청서 내용이 바뀌었습니다. 다시 확인한 뒤 수락해 주세요. | 수락 요청의 `version`이 현재 요청서와 다름 | #137 |
| `CONCURRENT_UPDATE` | 409 | 다른 요청이 먼저 처리되었습니다. 새로 고침한 뒤 다시 시도해 주세요. | 같은 요청서를 동시에 바꿔 버전(낙관적 락)이 맞지 않음. UseCase가 반영 시점에 확인해 던진다 | #98 |
| `BILLING_UNAVAILABLE` | 503 | HP 보류 처리가 지연되고 있습니다. 잠시 후 보낸 요청 목록에서 결과를 확인해 주세요. | 보류 결과를 알 수 없음(무응답 등). 요청서는 `HOLD_PENDING`으로 남음 | #98 |

### Billing 오류 (그대로 전달, #168)

출연 요청서 보내기에서 Billing이 HP 보류를 거절하면 출연 오류로 바꾸지 않고 **Billing 오류 코드·메시지를 그대로** 응답한다.
요청서는 `HOLD_FAILED`로 기록만 남는다. (이전의 `INSUFFICIENT_HP`는 없앴다.)

| code | HTTP | detail | 상황 |
|---|---|---|---|
| `INSUFFICIENT_BALANCE` | 409 | HP 잔액이 부족합니다. | 출연료만큼 보류할 잔액이 없음 |
| `WALLET_NOT_FOUND` | 404 | 지갑을 찾을 수 없습니다. | 호스트 지갑이 없음 |

- 반환·지급은 업무 상태를 커밋한 뒤 처리하므로 사용자 응답에 Billing 오류가 나가지 않는다. Billing 오류는 HP 작업 `last_error`에 `{code}: {메시지}`로 기록된다.
- Fake Billing은 Billing 오류 코드를 `out/billing/FakeBillingErrorCode.java`에 같은 값으로 옮겨 두었다.

- 정의 위치: `boundedContext/appearance/src/main/java/com/hapbang/appearance/domain/AppearanceErrorCode.java`
- 출연 예외는 `throw new AppearanceException(AppearanceErrorCode.XXX)`로 던진다. 상황별 메시지가 필요하면 두 번째 인자로 넘긴다.
