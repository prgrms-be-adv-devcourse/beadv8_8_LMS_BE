# 출연 모집글 내부 API 계약

chat이 모집용 단체방을 만들고 초대할 때(#584) 출연의 모집글·지원 이력을 확인하는 내부 HTTP API다 (#586). chat은 모집·지원을 복제하지 않고 그때그때 부른다(chat 설계 문서 3.4, Q35). 출연이 내부 API를 제공하고, chat은 자기 `@HttpExchange` Client로 부른다(shared 계약·Client 없음).

- 공통 규칙(경로, 내부 API 키 인증, 회원 세션 불가, RsData·ProblemDetail, Swagger 노출)은 [수익 내부 API 계약 1장](internal-earnings.md#1-공통-규칙)과 같다.
- 주소: 출연 앱 내부 포트 `http://appearance-app:8081`(k8s). 외부 포트(8080)·Gateway로 오면 404다.
- 출연 쪽 설계·구조: `docs/appearance/module.md` 24장

## 1. 공통

| 항목 | 내용 |
| --- | --- |
| 인증 | `X-Internal-Api-Key`. 없거나 틀리면 401(본문 없음), 회원 로그인만으로도 401 |
| 권한 | 출연은 호스트인지 확인하지 않는다. chat이 1장 응답의 `hostId`로 확인한다 |
| 회원 | 출연의 회원 복제를 확인하지 않는다. 정지·탈퇴 회원도 지원 이력이 있으면 나온다 |
| 지원 이력 | 지원 상태(`APPLIED`·`SELECTED`·`NOT_SELECTED`·`WITHDRAWN`)와 관계없다. 같은 회원은 같은 모집글에 다시 지원할 수 없어 회원마다 한 번만 나오고, 지원 행은 지우지 않아 이력은 늘기만 한다 |
| 오류 | 없는 모집글 404 `APPEARANCE_RECRUIT_NOT_FOUND`. 값이 범위 밖이거나 숫자가 아니면 400 `INVALID_REQUEST` |

## 2. 모집글 요약

`GET /api/v1/internal/appearances/recruits/{recruitId}`

```json
{
  "statusCode": 200,
  "message": "출연 모집글을 조회했습니다.",
  "data": { "recruitId": 12, "hostId": 1, "title": "주말 게임 합방 게스트 모집", "status": "RECRUITING" }
}
```

- `status`는 모집글 상태 다섯 가지를 **그대로** 준다: `HOLD_PENDING`(HP 보류 처리 중)·`HOLD_FAILED`(HP 보류 실패)·`RECRUITING`·`CLOSED`·`CANCELED`. 회원 API는 앞의 두 상태를 호스트 외에는 404로 숨기지만, 이 API는 숨기지 않고 방을 만들 수 있는지는 chat이 판단한다.
- `title`은 현재 제목이다(호스트가 수정하면 바뀐다).
- 오류: `recruitId` 1 미만·숫자 아님 400, 없는 모집글 404.

## 3. 지원자 회원 ID 목록 (키셋)

`GET /api/v1/internal/appearances/recruits/{recruitId}/applicants?afterUserId={id}&size={n}`

| 쿼리 | 필수 | 설명 |
| --- | --- | --- |
| `afterUserId` | X | 이 회원 ID보다 큰 회원부터. 생략하거나 0이면 처음부터. 0 이상 ([회원 내부 API](internal-members.md)의 `afterId`와 같음) |
| `size` | X | 페이지 크기 1~500, 기본 100 |

```json
{
  "statusCode": 200,
  "message": "출연 모집글 지원자 목록을 조회했습니다.",
  "data": { "userIds": [2, 3, 4], "nextAfterUserId": 4 }
}
```

- 정렬: 회원 ID 오름차순 고정
- `nextAfterUserId`: 다음 요청의 `afterUserId`. **마지막 페이지면 응답에서 빠진다.** 남은 회원이 `size`와 딱 맞게 끝나도 빠진다.
- 지원이 없는 모집글은 `userIds: []`
- 오류: `afterUserId` 음수, `size` 1~500 밖, 숫자 아님 400. 없는 모집글 404.

## 4. 지원 이력 확인

`GET /api/v1/internal/appearances/recruits/{recruitId}/applicants/exists?userIds=1,2,3`

| 쿼리 | 필수 | 설명 |
| --- | --- | --- |
| `userIds` | O | 확인할 회원 ID 1~100개. `userIds=1,2,3` 또는 `userIds=1&userIds=2`. 개수는 중복을 포함해 센다 |

```json
{
  "statusCode": 200,
  "message": "출연 모집글 지원 이력을 확인했습니다.",
  "data": { "appliedUserIds": [2, 4] }
}
```

- 요청한 회원 중 지원 이력이 있는 회원만, 오름차순·중복 없음. 아무도 없으면 `[]`
- 오류: `userIds` 없음·101개 이상·1 미만·숫자 아님 400. 없는 모집글 404.

## 5. 사용법 (chat)

```java
Long afterUserId = null;
do {
    ApplicantIds page = appearanceInternalApi.getApplicantIds(recruitId, afterUserId, 500);
    candidates.addAll(page.userIds());
    afterUserId = page.nextAfterUserId();   // 마지막 페이지면 null
} while (afterUserId != null);
```

- 지원 이력은 지워지지 않으므로 초대 직전에 4장으로 확인한 결과는 초대 처리까지 그대로 유효하다.
- 모두 조회라 실패하면 다시 보내도 안전하다. 연결 실패·시간 초과·5xx를 어떻게 처리할지(chat 설계 문서는 503 `APPEARANCE_UNAVAILABLE`)는 chat이 정한다.
