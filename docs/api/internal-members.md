# 회원 내부 API 계약

billing이 회원 복제본(`billing_user`)을 기존 회원으로 채울(백필) 때 쓰는 내부 HTTP API다 (#303). 회원이 내부 API와 `shared.member`의 계약·Client를 제공하고, 소비 모듈은 Client만 쓴다 (D62).

- 공통 규칙(경로, 내부 API 키 인증, 회원 세션 불가, nginx 차단, RsData·ProblemDetail, Swagger 노출)은 [수익 내부 API 계약 1장](internal-earnings.md#1-공통-규칙)과 같다.
- 신규 가입·수정은 지금처럼 회원 이벤트(`UserJoinedEvent`·`UserModifiedEvent`)로 동기화한다. 이 API는 이벤트를 받기 전에 가입한 회원을 채우는 용도다.

## 1. 회원 목록 (키셋)

`GET /api/v1/internal/members?afterId={id}&size={n}`

| 쿼리 | 필수 | 설명 |
| --- | --- | --- |
| `afterId` | X | 이 ID보다 큰 회원부터. 생략하면 처음부터. 0 이상 |
| `size` | X | 페이지 크기 1~500 (`MemberPageDto.MAX_SIZE`), 기본 100 |

- 대상: **모든 역할(USER·INFLUENCER·VERIFIED_INFLUENCER·ADMIN)과 모든 상태(ACTIVE·SUSPENDED·WITHDRAWN)**. 하드 삭제된 회원은 없다
- 정렬: 회원 ID 오름차순 고정
- 응답 `data` (`shared.member.dto.MemberPageDto`)

| 필드 | 설명 |
| --- | --- |
| `items` | 회원 목록. 각 항목은 회원 이벤트와 같은 `UserDto` (`id`, `nickname`, `role`, `status`, `version`) |
| `nextAfterId` | 다음 페이지의 `afterId`. **마지막 페이지면 응답에서 빠진다(null)** |

```json
{
  "statusCode": 200,
  "message": "회원 목록을 조회했습니다.",
  "data": {
    "items": [
      { "id": 1, "nickname": "member1", "role": "USER", "status": "ACTIVE", "version": 3 },
      { "id": 2, "nickname": "member2", "role": "INFLUENCER", "status": "WITHDRAWN", "version": 1 }
    ],
    "nextAfterId": 2
  }
}
```

- `version`은 회원 원본의 낙관적 락 버전(`members.users.version`, #284)이다
- 오류: `size`가 1~500 밖이거나 `afterId`가 음수면 400 `INVALID_REQUEST` + `errors[{field, message}]`. 키가 없거나 틀리면 401(본문 없음)

## 2. 사용법 (billing)

```java
Long afterId = null;
do {
    MemberPageDto page = memberApiClient.getMembers(afterId, MemberPageDto.MAX_SIZE);
    page.items().forEach(billingUserSync::upsert);   // 없으면 생성, 있으면 syncFrom
    afterId = page.nextAfterId();
} while (afterId != null);
```

- **반영은 `ReplicaUser.syncFrom`을 거친다.** `version`으로 옛 정보를 무시하므로(#284), 백필 중에 회원 이벤트가 먼저 반영돼도 백필이 옛 값으로 덮지 않는다
- 키셋이라 페이지를 넘기는 사이 가입한 회원 때문에 중복·누락이 생기지 않는다. 백필 뒤 가입한 회원은 가입 이벤트로 들어온다
- 설정: `hapbang.clients.member.base-url`(단일 앱은 `http://localhost:8080`), 연결 2초·응답 5초 (`hapbang.clients.member.connect-timeout`·`read-timeout`)
- 실패: 모두 `MemberApiCallException`. `REJECTED`(4xx, 다시 보내도 같음) / `UNAVAILABLE`(연결 실패·시간 초과·5xx·읽을 수 없는 응답, 조회라 다시 보내도 안전). 오류 응답의 `code`와 HTTP 상태를 담는다
