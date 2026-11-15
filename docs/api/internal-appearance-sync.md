# 출연 동기화 내부 API 계약

AI 추천(auxiliary-app chatbot)이 출연 데이터의 읽기용 사본을 둘 때 쓰는 내부 HTTP API다 (#713). 출연이 내부 API와 `shared.appearance`의 계약·Client를 제공하고, 소비 모듈은 Client만 쓴다 (D62). 출연 쪽 설계는 [`docs/appearance/module.md` 28장](../appearance/module.md).

- 공통 규칙(경로, 내부 API 키 인증, 회원 세션 불가, 내부 포트, RsData·ProblemDetail, Swagger 노출)은 [수익 내부 API 계약 1장](internal-earnings.md#1-공통-규칙)과 같다. 쿼리 규칙은 [회원 내부 API 1장](internal-members.md#1-회원-목록-키셋)과 같다.
- 평상시 반영은 Kafka 이벤트(`appearance.events.v1`)로 한다. 이 API는 매시(#717, 처음엔 주 1회) 처음부터 끝까지 읽어 이벤트 누락을 바로잡는 용도다(첫 실행이 곧 초기 적재).
- 이벤트와 API는 같은 DTO다. 같은 출연·같은 `version`이면 값이 같다.

## 1. 출연 목록 (키셋)

`GET /api/v1/internal/appearances/sync/appearances?afterId={id}&size={n}`

| 쿼리 | 필수 | 설명 |
| --- | --- | --- |
| `afterId` | X | 이 ID보다 큰 출연부터. 생략하면 처음부터. 0 이상 |
| `size` | X | 페이지 크기 1~500 (`AppearanceSyncPageDto.MAX_SIZE`), 기본 100 |

- 대상: **모든 출연(모든 상태)**. 출연은 지우지 않는다. 탈퇴 회원의 출연도 그대로 나온다(ID만, 개인정보 없음)
- 정렬: 출연 ID 오름차순 고정
- 응답 `data` (`shared.appearance.dto.AppearanceSyncPageDto`)

| 필드 | 설명 |
| --- | --- |
| `items` | 출연 목록. 각 항목은 출연 변경 이벤트와 같은 `AppearanceSyncDto` |
| `nextAfterId` | 다음 페이지의 `afterId`(이 페이지 마지막 출연 ID). 마지막 페이지면 null |

`AppearanceSyncDto`

| 필드 | 설명 |
| --- | --- |
| `id` | 출연 ID |
| `hostId`, `guestId` | 호스트·게스트 회원 ID |
| `result` | 공개 결과 (`shared.appearance.domain.AppearanceResult`): `SCHEDULED`(성립, 취소 요청 중 포함)·`COMPLETED`(확인)·`CANCELED`(일방·합의 취소)·`NO_SHOW`(불참) |
| `scheduledAt` | 방송 예정 일시. offset 없는 KST, 마이크로초까지 |
| `version` | 출연 버전. 상태가 바뀔 때마다 오른다(성립 직후 0). **큰 것만 반영한다** |

```json
{
  "statusCode": 200,
  "message": "출연 동기화 목록을 조회했습니다.",
  "data": {
    "items": [
      { "id": 31, "hostId": 1, "guestId": 2, "result": "COMPLETED", "scheduledAt": "2026-11-20T20:00:00", "version": 1 },
      { "id": 32, "hostId": 1, "guestId": 3, "result": "SCHEDULED", "scheduledAt": "2026-11-21T20:00:00", "version": 0 }
    ],
    "nextAfterId": 32
  }
}
```

- 금액(출연료·위약금), 취소·불참 사유, 출처(요청·모집)는 없다

## 2. 공개된 출연 리뷰 목록 (키셋)

`GET /api/v1/internal/appearances/sync/reviews?afterId={id}&size={n}`

쿼리는 1장과 같다(`afterId`는 리뷰 ID).

- 대상: **공개된 리뷰만**. 블라인드 공개 전 리뷰는 DB에서 걸러 키셋이 끊기지 않는다. 스캔 뒤 공개된 작은 ID 리뷰는 리뷰 공개 이벤트가 채운다
- 정렬: 리뷰 ID 오름차순 고정
- 응답 `data` (`shared.appearance.dto.AppearanceReviewSyncPageDto`): `items`, `nextAfterId`(이 페이지 마지막 리뷰 ID, 마지막 페이지면 null)

`AppearanceReviewSyncDto`

| 필드 | 설명 |
| --- | --- |
| `reviewId` | 리뷰 ID. **이것으로 한 번만 넣는다**(리뷰는 공개 뒤 바뀌지 않아 version이 없다) |
| `appearanceId` | 출연 ID |
| `revieweeUserId` | 리뷰를 받은 회원 ID |
| `rating` | 별점 1~5 |

- 리뷰 본문·쓴 사람은 없다. 네 필드의 이름과 값은 리뷰 공개 이벤트(`AppearanceReviewPublishedEvent`)의 같은 이름 필드와 같다. 이벤트로 받은 값과 비교할 때는 이벤트에서 이 네 필드만 뽑는다

## 3. 오류

| 상황 | 응답 |
| --- | --- |
| `size`가 1~500 밖, `afterId`가 음수·숫자 아님 | 400 `INVALID_REQUEST` + `errors[{field, message}]` |
| 내부 API 키가 없거나 틀림, 회원 로그인만 있음 | 401 (본문 없음) |
| 외부 포트(8080)·Gateway로 옴 | 404 |

## 4. 이벤트 (참고)

| 이벤트 (`appearance.events.v1`) | payload | key |
| --- | --- | --- |
| `AppearanceChangedEvent(AppearanceSyncDto appearance)` | 1장의 `AppearanceSyncDto` | `appearance:<출연 ID>` |
| `AppearanceReviewPublishedEvent` (기존, #513) | `reviewId`, `appearanceId`, `reviewerUserId`, `revieweeUserId`, `revieweeRole`, `rating`, `publishedAt` | `appearance-review-reviewee:<받은 회원 ID>` |

- `AppearanceChangedEvent`는 출연의 모든 상태 전이에서 바뀐 뒤 상태로 한 번씩 나간다(성립, 취소 요청·거절·자동 거절, 합의 취소, 확인·자동 확인, 불참, 호스트 일방 취소, 모집 전체 취소는 출연마다). 공개 결과가 같은 전이(`SCHEDULED` 유지)도 version이 올라 나간다
- 순서는 보장하지 않는다. 출연 이벤트끼리도(커밋 뒤 비동기 전송·재발행), 출연과 리뷰 사이도(key가 달라 다른 partition). 출연은 version으로, 리뷰는 `reviewId`로 멱등 처리하고, 리뷰가 출연보다 먼저 와도 저장한다
- 최소 한 번 전달이라 같은 메시지가 다시 올 수 있다

## 5. 사용법 (AI 추천)

```java
Long afterId = null;
do {
    AppearanceSyncPageDto page = appearanceApiClient.getSyncAppearances(afterId, AppearanceSyncPageDto.MAX_SIZE);
    page.items().forEach(appearanceReplica::upsertIfNewer);   // version이 큰 것만 반영
    afterId = page.nextAfterId();
} while (afterId != null);
```

- 설정: `hapbang.clients.appearance.base-url`(내부 포트 8081 주소). 받는 앱의 pod에 label `hapbang.io/internal-api-client: 'true'`가 있어야 출연 NetworkPolicy가 8081을 연다
- 실패: 모두 `AppearanceApiCallException`. `REJECTED`(4xx, 다시 보내도 같음) / `UNAVAILABLE`(연결 실패·시간 초과·5xx·읽을 수 없는 응답, 조회라 다시 보내도 안전)
