# 회원 내부 API 계약

다른 모듈이 회원 정보를 읽는 내부 HTTP API다. 회원이 내부 API와 `shared.member`의 계약·Client(`MemberApiClient`)를 제공하고, 소비 모듈은 Client만 쓴다 (D62).

| 장 | API | 쓰는 곳 |
| --- | --- | --- |
| 1·2 | 회원 목록 | billing 회원 복제본(`billing_user`) 백필 (#303), search 처음 적재 |
| 3 | 인플루언서 통계 | search가 10분마다 채널 이름·팔로워·출연 평점 갱신 (#769) |
| 4 | 회원 변경분 | search 증분 보정(이벤트 유실 바로잡기)과 재색인 중 따라잡기 (#769) |
| 5 | 콘텐츠 분야 목록 | search가 분야 코드를 이름으로 바꿔 색인 (#769) |

- 공통 규칙(경로, 내부 API 키 인증, 회원 세션 불가, nginx 차단, RsData·ProblemDetail, Swagger 노출)은 [수익 내부 API 계약 1장](internal-earnings.md#1-공통-규칙)과 같다.
- 신규 가입·수정은 지금처럼 회원 이벤트(`UserJoinedEvent`·`UserModifiedEvent`)로 동기화한다. 이 API들은 이벤트를 대신하지 않고, 이벤트 전에 가입한 회원 채우기·이벤트에 싣지 않는 통계·어긋남 바로잡기에 쓴다.

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
| `items` | 회원 목록. 각 항목은 회원 이벤트와 같은 `UserDto` (`id`, `nickname`, `role`, `status`, `version`, `contentCategories`, `bio`) |
| `nextAfterId` | 다음 페이지의 `afterId`. **마지막 페이지면 응답에서 빠진다(null)** |

```json
{
  "statusCode": 200,
  "message": "회원 목록을 조회했습니다.",
  "data": {
    "items": [
      { "id": 1, "nickname": "member1", "role": "USER", "status": "ACTIVE", "version": 3,
        "contentCategories": [], "bio": null },
      { "id": 2, "nickname": "member2", "role": "INFLUENCER", "status": "WITHDRAWN", "version": 1,
        "contentCategories": ["MAPLESTORY", "GAME"], "bio": "메이플 위주로 방송해요" }
    ],
    "nextAfterId": 2
  }
}
```

- `version`은 회원 원본의 낙관적 락 버전(`members.users.version`, #284)이다
- `contentCategories`는 콘텐츠 분야 **코드** 목록(고른 순서)이고, 없으면 빈 배열이다(null 아님). 코드는 enum이 아니라 문자열이라 처음 보는 코드가 와도 읽기가 실패하지 않는다. 이름은 싣지 않는다(바뀔 수 있어서). `bio`는 자기소개이고 없으면 null이다 (#711)
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

## 3. 인플루언서 통계 (키셋)

`GET /api/v1/internal/members/influencer-stats?afterId={id}&size={n}`

| 쿼리 | 필수 | 설명 |
| --- | --- | --- |
| `afterId` | X | 이 회원 ID보다 큰 인플루언서부터. 생략하면 처음부터. 0 이상 |
| `size` | X | 페이지 크기 1~500 (`InfluencerStatsPageDto.MAX_SIZE`), 기본 100 |

- 대상: **정상 인플루언서**(INFLUENCER·VERIFIED_INFLUENCER + ACTIVE). 인플루언서 목록 API(`GET /api/v1/members/influencers`)와 같은 기준이다
- 정렬: 회원 ID 오름차순 고정
- 한 페이지 회원의 채널·평점을 한 번씩 묶어 읽는다(회원 수와 상관없이 SQL 3번)
- 응답 `data` (`shared.member.dto.InfluencerStatsPageDto`)

| 필드 | 설명 |
| --- | --- |
| `items[].userId` | 회원 ID |
| `items[].version` | 회원 원본 버전(`UserDto.version`과 같은 값). 받는 쪽이 가진 version보다 크면 프로필이 바뀐 것이다 |
| `items[].channels` | 인증된 채널(승인, 삭제 안 됨) **모두** `[{channelName, followerCount}]`, 채널 ID 오름차순. 채널 없이 인플루언서가 된 회원(관리자 지정)은 빈 배열 |
| `items[].appearanceRating` | 받은 출연 리뷰 전체의 평점 `{average, count}`. `average`는 소수 첫째 자리까지이고 **리뷰가 없으면 응답에서 빠진다(null)**, `count`는 0 |
| `nextAfterId` | 다음 페이지의 `afterId`. 마지막 페이지면 빠진다(null) |

```json
{
  "statusCode": 200,
  "message": "인플루언서 통계를 조회했습니다.",
  "data": {
    "items": [
      { "userId": 3, "version": 2,
        "channels": [ { "channelName": "본채널", "followerCount": 1200 },
                      { "channelName": "다시보기", "followerCount": 300 } ],
        "appearanceRating": { "average": 4.3, "count": 7 } },
      { "userId": 5, "version": 0, "channels": [], "appearanceRating": { "count": 0 } }
    ],
    "nextAfterId": 5
  }
}
```

- 팔로워 수는 회원 앱의 팔로워 수 갱신(매일)이 저장한 값이다. 이 API는 치지직을 부르지 않는다
- 채널이 여러 개일 때 검색은 이름을 모두 색인하고, 팔로워 필터·정렬·가산은 가장 큰 값을 쓴다. 합하지 않는다: 부 채널(다시보기·클립) 팔로워는 대부분 본 채널과 같은 사람이라 합하면 부풀려진다 (2026-11-09 결정)
- 평균이 null인 것은 "리뷰 없음"이지 0점이 아니다. 회원 프로필의 평점과 같은 규칙이다 (2026-11-09 결정)
- 오류: `size`가 1~500 밖이거나 `afterId`가 음수면 400 `INVALID_REQUEST` + `errors[{field, message}]`. 키가 없거나 틀리면 401(본문 없음)

## 4. 회원 변경분 (키셋)

`GET /api/v1/internal/members/changes?afterUpdatedAt={시각}&afterId={id}&size={n}`

| 쿼리 | 필수 | 설명 |
| --- | --- | --- |
| `afterUpdatedAt` | X | 커서 시각. 회원 앱 시간대(Asia/Seoul)의 **오프셋 없는** ISO 시각, 마이크로초까지 (예: `2026-11-09T10:15:30.123456`). `Z`·`+09:00`이 붙으면 400 |
| `afterId` | X | 커서 회원 ID. 0 이상 |
| `size` | X | 페이지 크기 1~500 (`MemberChangePageDto.MAX_SIZE`), 기본 100 |

- 커서 둘은 **함께 넘기거나 함께 생략**한다. 생략하면 처음부터 읽는다
- 대상: `users.updated_at`이 커서보다 뒤인 **모든 역할·상태**의 회원. 인플루언서가 아니게 된 회원(강등·정지·탈퇴)도 나와야 받는 쪽이 숨길 수 있다
- 정렬: `(updated_at, id)` 오름차순. 행 값 비교 `(updated_at, id) > (커서)`라 같은 시각에 바뀐 여러 회원이 페이지 경계에 걸려도 빠지지 않는다. 인덱스 `idx_users_updated_at_id`
- `updated_at`은 회원 행이 바뀔 때마다 오른다. 닉네임·역할·상태·자기소개뿐 아니라 콘텐츠 분야만 바꿔도 오른다. 프로필 사진처럼 `UserDto`에 없는 칸이 바뀌어도 오르므로, 받는 쪽은 version으로 같은 내용을 거른다
- 응답 `data` (`shared.member.dto.MemberChangePageDto`)

| 필드 | 설명 |
| --- | --- |
| `items` | 회원 목록. 각 항목은 회원 이벤트와 같은 `UserDto` (1장과 같음) |
| `nextAfterUpdatedAt`·`nextAfterId` | 이 페이지 **마지막 회원의 커서**. 다음에 부를 때 그대로 넘긴다. **마지막 페이지에도 준다**. 항목이 없을 때만 빠진다(null) |
| `hasNext` | 이 커서 뒤에 회원이 더 있으면 true. false면 이번 주기는 여기서 멈춘다 |

```json
{
  "statusCode": 200,
  "message": "회원 변경분을 조회했습니다.",
  "data": {
    "items": [
      { "id": 7, "nickname": "member7", "role": "USER", "status": "ACTIVE", "version": 4,
        "contentCategories": ["GAME"], "bio": "소개" }
    ],
    "nextAfterUpdatedAt": "2026-11-09T10:15:30.123456",
    "nextAfterId": 7,
    "hasNext": false
  }
}
```

### 받는 쪽 규칙: 1분 겹쳐 읽기

`updated_at`은 회원 앱이 엔티티를 저장(flush)할 때 정해지고, 커밋은 그 뒤다. 그래서 먼저 시각을 받은 트랜잭션이 늦게 커밋되면, 받는 쪽이 이미 지나간 커서 **앞**에 그 행이 나중에 나타난다. 커서만 따라가면 이 회원을 놓친다.

- 받는 쪽은 페이지마다 응답의 커서를 저장한다. 다음 주기에는 **저장한 커서 시각에서 1분을 뺀 시각**(`afterId`는 0)부터 다시 읽는다
- 겹쳐 읽은 회원은 이미 반영한 것일 수 있다. `version`이 가진 값보다 크지 않으면 무시한다(이벤트와 같은 규칙)
- 1분은 이슈 #769에서 정한 여유다. 저장(flush)부터 커밋까지 1분 넘게 걸린 트랜잭션의 변경은 이 보정이 놓칠 수 있고, 그때는 회원 이벤트가 반영한다
- `updated_at`은 회원 앱 서버의 시계로 찍는다. 지금은 회원 앱이 1대(`replicas: 1`)라 상관없지만, 여러 대로 늘리면 서버끼리 시계 차이도 이 1분 안이어야 한다
- 마지막 페이지에도 커서를 주는 이유: 항목인 `UserDto`에는 `updated_at`이 없어 받는 쪽이 커서를 스스로 만들 수 없다. 마지막 페이지에서 커서를 주지 않으면 다음 주기에 그 페이지(최대 500명)를 통째로 다시 읽는다 (2026-11-09 결정)
- 응답의 커서는 해석하지 않고 그대로 넘긴다. Client는 `MemberApiClient.getChanges(afterUpdatedAt, afterId, size)`이고, 커서 하나만 넘기면 요청을 보내지 않고 `IllegalArgumentException`을 던진다

```java
// saved: 지난 주기에 저장한 커서 (처음이면 null → 처음부터)
LocalDateTime afterUpdatedAt = saved == null ? null : saved.updatedAt().minusMinutes(1);
Long afterId = saved == null ? null : 0L;
MemberChangePageDto page;
do {
    page = memberApiClient.getChanges(afterUpdatedAt, afterId, MemberChangePageDto.MAX_SIZE);
    page.items().forEach(searchIndexer::applyIfNewer);   // version이 크면 반영, 아니면 무시
    if (page.nextAfterUpdatedAt() != null) {             // 항목이 있으면 커서가 온다 (마지막 페이지 포함)
        afterUpdatedAt = page.nextAfterUpdatedAt();
        afterId = page.nextAfterId();
        cursorStore.save(afterUpdatedAt, afterId);       // 페이지마다 저장
    }
} while (page.hasNext());
```

- 오류: 커서 둘 중 하나만 넘기거나 있을 수 없는 날짜(13월 등)면 400 `INVALID_REQUEST`. 시각 형식이 틀리거나(오프셋이 붙은 시각 포함) `size`가 1~500 밖이거나 `afterId`가 음수면 400 `INVALID_REQUEST` + `errors[{field, message}]`. 키가 없거나 틀리면 401(본문 없음)

## 5. 콘텐츠 분야 목록

`GET /api/v1/internal/members/content-categories`

- 대상: 분야 **전부**. `active`가 꺼진 분야도 준다(이미 고른 회원의 분야에 이름을 붙여야 하므로, #711 Q50)
- 정렬: `code` 오름차순. 로그인 회원용 공개 목록(`GET /api/v1/members/content-categories`)은 `active`만, 고른 수 많은 순이라 화면용이고, 이 API는 이름을 찾는 용도다
- 페이지 없음(분야는 수십 개)
- 응답 `data` (`shared.member.dto.ContentCategoriesDto`)

| 필드 | 설명 |
| --- | --- |
| `items[].code` | 영문 `UPPER_SNAKE` 코드. 바뀌지 않는다(`UserDto.contentCategories`에 실리는 값) |
| `items[].name` | 한글 이름. 바뀔 수 있다 |
| `items[].active` | 새로 고를 수 있는지 |

```json
{
  "statusCode": 200,
  "message": "콘텐츠 분야 목록을 조회했습니다.",
  "data": {
    "items": [
      { "code": "BATTLEGROUNDS", "name": "배틀그라운드", "active": true },
      { "code": "BEAUTY", "name": "뷰티", "active": true }
    ]
  }
}
```

- 이름이 바뀌어도 이벤트는 나가지 않는다. 받는 쪽은 이 API를 주기적으로 읽어 바뀐 이름을 반영한다
- 오류: 키가 없거나 틀리면 401(본문 없음)
