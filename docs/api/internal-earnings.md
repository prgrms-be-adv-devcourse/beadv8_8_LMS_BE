# 수익 예정·지급 상태 내부 API 계약

billing #295(내 HP 거래 내역·지급 예정 요약)이 강의·출연의 **받을 지급**을 모아 보여 줄 때 쓰는 내부 HTTP API다. 강의 #300, 출연 #301이 같은 상태·필드 이름으로 제공하고, billing이 두 응답을 합한다.

- 용어: **수익(earning)** 은 판매자·게스트가 **앞으로 받을 지급 한 건**이다. 이미 지갑에 들어온 "수익 HP"(AGENTS.md 4장)와 다르다.
- 구조: 보류 중인 모듈 간 HTTP 연동(D62, #271·#281)을 먼저 적용한다. 제공 모듈이 내부 API와 `shared.<제공 모듈>`의 계약·Client를 제공하고, 소비 모듈은 Client만 쓴다. 결정 기록은 강의 설계 문서 D63.

## 1. 공통 규칙

| 항목 | 규칙 |
| --- | --- |
| 경로 | `/api/v1/internal/<제공 모듈>/...` (예: `/api/v1/internal/lectures/earnings`) |
| 인증 | 요청 헤더 `X-Internal-Api-Key`(`shared.internal.InternalApiHeaders.API_KEY`)의 값이 `hapbang.internal-api.key`와 같아야 한다. 없거나 틀리면 **401, 본문 없음** |
| 회원 세션 | 쓰지 않는다. `/api/v1/internal/**`은 전용 보안 체인이 처리해 로그인 세션이 있어도 키가 없으면 401이다. 반대로 내부 키로 일반 API를 부를 수 없다 |
| 회원 ID | 호출한 모듈이 **자기 쪽에서 인증한 회원의 ID**를 쿼리 `userId`로 넘긴다. 제공 모듈은 그 ID를 그대로 믿고, 회원이 있는지 따로 확인하지 않는다(없는 회원은 0건) |
| 외부 차단 | 서버 nginx가 `/api/v1/internal/`을 밖에서 404로 끊는다. 내부 호출은 앱 안에서 자기 주소(`http://localhost:8080`)로 해 nginx를 거치지 않는다 |
| 키 설정 | 환경 변수 `HAPBANG_INTERNAL_API_KEY` → `hapbang.internal-api.key`. **비어 있으면 앱이 시작하지 않는다.** 로컬 프로필만 `local-placeholder` 기본값이 있다. 키는 코드·로그·PR에 남기지 않는다 (docs/deploy/env-vars.md) |
| 금액 | HP 정수(1 HP = 1원) |
| 시각 | ISO 8601, offset 포함(`2026-11-21T00:00:00+09:00`). 값을 정할 수 없으면 `null`이고, `null` 필드는 응답 JSON에서 **빠진다**(공통 Jackson 설정) |
| 성공 응답 | 다른 API와 같이 `RsData`(`statusCode`, `message`, `data`) |
| 오류 응답 | 다른 API와 같이 `ProblemDetail` + `code`. 요청 값 오류는 400 `INVALID_REQUEST` + `errors[{field, message}]` |
| 페이지 | `page`(0부터)·`size`(기본 20, 최대 100). 응답 `data`는 `{content, page: {size, number, totalElements, totalPages}}` |
| Swagger | 노출한다(운영은 Swagger가 꺼져 있다). 각 API에 `X-Internal-Api-Key` 헤더 파라미터가 있다 |

## 2. 상태

| 상태 | 뜻 | 요약에 포함 |
| --- | --- | --- |
| `CONFIRMATION_PENDING` | 구매 확정 대기. 취소될 수 있다 | O (`confirmationPending`) |
| `SCHEDULED` | 지급 예정. 확정되었고 지급 예정 시각 전 | O (`scheduled`) |
| `ON_HOLD` | 지급 중단(이의 제기) | O (`onHold`) |
| `PROCESSING` | 처리 중. 지급 예정 시각이 지나 다음 지급 처리에서 지급(일시 실패 뒤 재시도 포함) | O (`processing`) |
| `FAILED` | 지급 실패. 다시 시도해도 풀리지 않아 지급이 격리되어 관리자 조치를 기다린다 | O (`failed`) |
| `COMPLETED` | 지급 완료 | X |
| `CANCELED` | 취소·반환되어 지급하지 않는다 | X |

- 상태 이름은 응답 문자열이라 바꾸지 않는다. 강의는 `shared.lecture.domain.LectureEarningStatus`, 출연은 `shared.appearance`의 같은 이름 enum이다.
- "보류"라는 말은 쓰지 않는다. 지급 전 HP는 billing에서 모두 "HP 보류" 상태라, 이의 제기로 지급이 멈춘 것은 "지급 중단"이라고 한다.

## 3. 강의 API

### 3.1 `GET /api/v1/internal/lectures/earnings/summary`

판매자의 **아직 지급하지 않은** 수익을 상태별로 합한다. 페이지와 관계없이 전체 항목 기준이다.

| 쿼리 | 필수 | 설명 |
| --- | --- | --- |
| `userId` | O | 판매자 회원 ID |

```json
{
  "statusCode": 200,
  "message": "판매 수익 요약을 조회했습니다.",
  "data": {
    "confirmationPending": { "amountHp": 10000, "count": 1 },
    "scheduled": { "amountHp": 20000, "count": 2 },
    "onHold": { "amountHp": 0, "count": 0 },
    "processing": { "amountHp": 0, "count": 0 },
    "failed": { "amountHp": 5000, "count": 1 },
    "asOf": "2026-11-05T12:00:00+09:00"
  }
}
```

- 수익이 없는 상태도 `{0, 0}`으로 준다.
- `asOf`는 분류 기준 시각이다(지급 예정 시각이 지났는지를 이 시각으로 판정한다). #295 계약 코멘트의 요약 필드에 더한 필드라 무시해도 된다. 출연(#301)도 같은 필드를 둘지 맞춘다.
- 오류: `userId` 없음 400 `INVALID_REQUEST`.

### 3.2 `GET /api/v1/internal/lectures/earnings`

판매자의 수익을 **주문 항목 하나 = 한 건**으로 보여 준다.

| 쿼리 | 필수 | 설명 |
| --- | --- | --- |
| `userId` | O | 판매자 회원 ID |
| `status` | X | 여러 번 줄 수 있다(`status=SCHEDULED&status=FAILED`). **생략하면 아직 지급하지 않은 다섯 상태만**. `COMPLETED`·`CANCELED`는 지정해야 나온다. Client에 `null`·빈 목록을 주면 생략과 같다 |
| `page`, `size` | X | 1장 공통 규칙 |

```json
{
  "statusCode": 200,
  "message": "판매 수익 목록을 조회했습니다.",
  "data": {
    "content": [
      {
        "sourceType": "LECTURE",
        "sourceId": 31,
        "earningType": "LECTURE_SALE",
        "title": "방송 장비 세팅 강의",
        "amountHp": 10000,
        "status": "SCHEDULED",
        "payoutScheduledAt": "2026-11-21T00:00:00+09:00",
        "statusUpdatedAt": "2026-10-20T10:00:00+09:00"
      }
    ],
    "page": { "size": 20, "number": 0, "totalElements": 1, "totalPages": 1 }
  }
}
```

| 필드 | 설명 |
| --- | --- |
| `sourceType` | 항상 `LECTURE`(출연은 `APPEARANCE`, #295·티켓 5장). `sourceId`의 종류는 주문 항목이고, billing 지급 기록(`SettlementSourceType.LECTURE_ORDER_ITEM`)의 원천 ID와 같다 |
| `sourceId` | 주문 항목 ID (지급 단위) |
| `earningType` | 항상 `LECTURE_SALE` |
| `title` | 주문 시점 강의 제목 |
| `amountHp` | 주문 시점 가격. 지급에 수수료가 없어 받을 금액과 같다 |
| `payoutScheduledAt` | 지급 예정 시각(구매일 + 31일 00:00 KST). `ON_HOLD`·`FAILED`·`CANCELED`는 시각을 정할 수 없어 `null`(필드 없음) |
| `statusUpdatedAt` | 지금 상태가 된 시각 (아래 표) |

- 정렬은 `statusUpdatedAt desc, sourceId desc`로 고정하고 요청의 `sort`는 무시한다. 출연 목록도 같은 키라 billing이 두 목록을 같은 기준으로 합칠 수 있다.
- 오류: `userId` 없음, `status`가 위 7개가 아님 → 400 `INVALID_REQUEST`(`errors[0].field` = `userId`·`status`).

### 3.3 강의 항목 → 상태

위에서부터 먼저 맞는 조건을 쓴다. 요약·목록·필터가 같은 분류식(`LectureOrderRepository.EARNING_STATUS`) 하나를 쓴다.

| 상태 | 강의 주문 항목 조건 | `statusUpdatedAt` |
| --- | --- | --- |
| `ON_HOLD` | 이의 제기 접수 중(`FILED`). 구매 확정 전후 모두. 티켓 4장 표에는 구매 확정 뒤만 있지만, 접수되면 구매 취소가 막히고(D44) 지급도 멈추므로 구매 확정 전도 지급 중단으로 본다 | 접수 시각 |
| `CONFIRMATION_PENDING` | `PURCHASED` | 구매 시각(이의 제기가 기각됐으면 기각 시각) |
| `FAILED` | `CONFIRMED` + 지급 격리됨(다시 시도해도 풀리지 않는 실패, D54) | 격리 시각 |
| `PROCESSING` | `CONFIRMED` + 지급 예정 시각 지남 + 격리 안 됨. 매일 01:00 지급 배치 대상(`findPayoutDueItemIds`)과 같다. 일시 실패(지갑 없음 등)와 격리 해제 뒤의 항목도 다음 배치가 다시 시도하므로 여기다 | 지급 예정 시각(그 뒤에 기각됐으면 기각 시각) |
| `SCHEDULED` | `CONFIRMED` + 지급 예정 시각 전 | 구매 확정 시각(이의 제기가 기각됐으면 기각 시각) |
| `COMPLETED` | `PAID_OUT` | 지급 시각 |
| `CANCELED` | `CANCELED`(구매 취소) · `RETURNED`(이의 제기 인정 반환) | 취소 시각 · 반환 시각 |

## 4. Client (`shared.lecture.out.LectureApiClient`)

billing은 URI·헤더를 직접 만들지 않고 이 Client를 주입받는다.

```java
LectureEarningSummaryDto getEarningSummary(long sellerUserId);
LectureEarningPageDto getEarnings(long sellerUserId, Collection<LectureEarningStatus> statuses, int page, int size);
```

| 설정 | 기본값 | 설명 |
| --- | --- | --- |
| `hapbang.clients.lecture.base-url` | `http://localhost:${server.port:8080}` (apps) | 강의 서비스 주소. **이 값이 있을 때만 Client 빈이 생긴다**(모듈 테스트에는 없으니 Fake를 쓴다) |
| `hapbang.clients.lecture.connect-timeout` | 2s | 연결 제한 시간 |
| `hapbang.clients.lecture.read-timeout` | 5s | 응답 제한 시간 |
| `hapbang.internal-api.key` | (환경 변수) | Client가 헤더에 붙인다 |

실패는 `LectureApiCallException`이다(shared는 global에 의존하지 않아 `BusinessException`이 아니다). 호출한 모듈이 자기 오류로 바꾼다.

| `getReason()` | 언제 | `getStatus()` / `getCode()` |
| --- | --- | --- |
| `REJECTED` | 4xx (요청 값 오류, 내부 키 오류 401) | HTTP 상태 / `ProblemDetail.code`(401은 본문이 없어 `null`) |
| `UNAVAILABLE` | 연결 실패, 응답 시간 초과, 5xx, 읽을 수 없는 성공 응답(깨진 JSON, 계약에 없는 enum 값, 필수 필드 없음, 본문·`data` 없음, 204 등) | 응답을 받았으면 그 HTTP 상태, 받지 못했으면 0 / 5xx 본문의 `ProblemDetail.code`(예: `INTERNAL_ERROR`), 그 밖에는 `null` |

- 어떤 실패든 Spring `RestClientException`이 아니라 `LectureApiCallException`으로 나온다.
- 조회라 `UNAVAILABLE`은 그대로 다시 불러도 안전하다.
- 응답 시각은 `+09:00`이지만, 앱 Jackson이 읽으면서 **같은 시각의 UTC offset으로 바꿀 수 있다.** 날짜로 나눌 때는 KST(`Asia/Seoul`)로 바꿔 쓴다.

## 5. 한계

| 한계 | 이유 |
| --- | --- |
| 구매 취소와 반환이 같은 `CANCELED`다 | 공통 상태에 반환이 따로 없다. 나눠 보여야 하면 공통 계약에 필드를 더하고 출연과 다시 맞춘다 |
| 이의 제기 철회·격리 해제는 `statusUpdatedAt`이 바뀌지 않는다(격리 해제된 항목은 지급 예정 시각으로 보인다) | 강의 항목에 그 시각이 남지 않는다(D52·D54) |
| 일시 실패한 항목은 실패가 아니라 처리 중으로 보인다 | 다음 배치가 자동으로 다시 시도한다. 실패 횟수·코드는 관리자 API(`/admin/payout-failures`)에서 본다 |
| offset 페이지라 조회하는 동안 상태가 바뀌면 항목이 페이지 사이에서 중복되거나 빠질 수 있다 | 정렬 키(`statusUpdatedAt`)가 상태 변경으로 바뀐다. 커서 페이지는 이번 범위 밖이다 |
| 요약과 목록은 같은 시점의 값이 아니다 | 호출이 두 번이라 그 사이 지급 배치 등이 돌 수 있다. 요약의 `asOf`로 기준 시각을 알 수 있다 |
| 분류가 deprecated 컬럼(D54의 지급 실패 기록)에 기댄다 | HP 작업 전환(#277·#268)을 재개하면 `PAYOUT_PENDING`→`PROCESSING`, 작업 `DEAD`→`FAILED`로 다시 대응시킨다. 상태 이름은 그대로다 |
