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
| 시각 | ISO 8601, offset 포함(`2026-11-21T00:00:00+09:00`). **출연은 offset 없는 KST**(`2026-11-21T00:00:00`, 6.4). 값을 정할 수 없으면 `null`이고, `null` 필드는 응답 JSON에서 **빠진다**(공통 Jackson 설정) |
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
- `asOf`는 분류 기준 시각이다(지급 예정 시각이 지났는지를 이 시각으로 판정한다). #295 계약 코멘트의 요약 필드에 더한 필드라 무시해도 된다. 출연(#301)은 두지 않는다(6.4).
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

결제가 끝나지 않은 항목(`HOLD_PENDING`·`HOLD_COMPENSATING`·`HOLD_FAILED`)은 아직 팔린 것이 아니라 어느 상태에도 넣지 않고 요약·목록 모두에서 뺀다 (강의 D69, 판매 내역과 같음).

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

## 6. 출연 API

1장 공통 규칙·2장 상태를 따른다. 강의와 다른 점은 6.4에 모았다. 출연 쪽 상세는 `docs/appearance/module.md` 18장, 결정 기록은 출연 ISSUE APR-39.

### 6.1 `GET /api/v1/internal/appearances/earnings/summary`

게스트의 **아직 받지 못한** 수익(출연료·위약금)을 상태별로 합한다. 페이지와 관계없이 전체 출연 기준이다.

| 쿼리 | 필수 | 설명 |
| --- | --- | --- |
| `userId` | O | 게스트 회원 ID |
| `earningType` | X | `APPEARANCE_FEE`·`APPEARANCE_PENALTY`. 생략하면 둘 다 |

```json
{
  "statusCode": 200,
  "message": "출연 수익 요약을 조회했습니다.",
  "data": {
    "confirmationPending": { "amountHp": 20000, "count": 1 },
    "scheduled": { "amountHp": 0, "count": 0 },
    "onHold": { "amountHp": 21000, "count": 1 },
    "processing": { "amountHp": 31000, "count": 2 },
    "failed": { "amountHp": 23000, "count": 1 }
  }
}
```

- 수익이 없는 상태도 `{0, 0}`으로 준다. `scheduled`는 항상 0이다(6.3).
- 오류: `userId` 없음·1 미만, 계약에 없는 `earningType` 400 `INVALID_REQUEST`.

### 6.2 `GET /api/v1/internal/appearances/earnings`

게스트의 수익을 **출연 하나 = 한 건**으로 보여 준다. 호스트가 일방 취소한 출연은 위약금 한 건이다(출연료와 겹쳐 세지 않는다).

| 쿼리 | 필수 | 설명 |
| --- | --- | --- |
| `userId` | O | 게스트 회원 ID |
| `status` | X | 여러 번 줄 수 있다. **생략하거나 비우면 아직 받지 못한 상태만**(강의와 같다). `COMPLETED`·`CANCELED`는 지정해야 나온다. `SCHEDULED`만 주면 빈 결과 |
| `earningType` | X | 6.1과 같다 |
| `page`, `size` | X | 1장 공통 규칙 |

```json
{
  "statusCode": 200,
  "message": "출연 수익 목록을 조회했습니다.",
  "data": {
    "content": [
      {
        "sourceType": "APPEARANCE",
        "sourceId": 12,
        "earningType": "APPEARANCE_FEE",
        "title": "주말 합방 출연 요청",
        "amountHp": 30000,
        "status": "PROCESSING",
        "statusUpdatedAt": "2026-10-20T23:10:00"
      }
    ],
    "page": { "size": 20, "number": 0, "totalElements": 1, "totalPages": 1 }
  }
}
```

| 필드 | 설명 |
| --- | --- |
| `sourceType` | 항상 `APPEARANCE` |
| `sourceId` | 출연 ID (지급 단위). billing 지급 기록의 원천 ID와 같다. 원천 종류는 요청 출처 `SettlementSourceType.APPEARANCE_REQUEST`·모집 출처 `RECRUIT_APPEARANCE`로 나뉘지만 둘 다 출연 ID다 |
| `earningType` | `APPEARANCE_FEE`(출연료) · `APPEARANCE_PENALTY`(호스트 일방 취소 위약금) |
| `title` | 출처 요청서·모집글의 **현재** 제목 |
| `amountHp` | 출연료, 위약금이면 위약금 |
| `payoutScheduledAt` | 항상 `null`(필드 없음). 호스트가 방송 뒤 7일 안에 언제든 확인할 수 있어 지급 시각이 정해지지 않는다 |
| `statusUpdatedAt` | 지금 상태가 된 시각 (아래 표) |

- 정렬은 `statusUpdatedAt desc, sourceId desc`로 고정하고 요청의 `sort`는 무시한다(3.2와 같은 키).
- 오류: `userId` 없음·1 미만, `status`가 2장 7개가 아님, `earningType`이 위 2개가 아님(다른 모듈 값 `LECTURE_SALE` 포함) → 400 `INVALID_REQUEST`(`errors[0].field` = `userId`·`status`·`earningType`).

### 6.3 출연 → 상태

요약·목록·필터가 같은 분류 규칙(`AppearanceEarningRule`) 하나를 쓴다. 작업은 출연의 지급·위약금 HP 작업(`PAYOUT`·`PENALTY`, 출연마다 많아야 1건)이다.

| 상태 | 출연 조건 | 종류 | `statusUpdatedAt` |
| --- | --- | --- | --- |
| `CONFIRMATION_PENDING` | 성립(`ESTABLISHED`). 방송 뒤 호스트 확인(또는 7일 뒤 자동 확인)을 기다린다 | 출연료 | 출연 수정 시각 |
| `SCHEDULED` | 없음. 확인하는 순간 지급을 실행해 "지급 시각은 정해졌는데 실행 전"인 단계가 없다 | - | - |
| `ON_HOLD` | 게스트 출연 취소 요청을 호스트가 처리 중(`CANCEL_REQUESTED`) | 출연료 | 출연 수정 시각 |
| `PROCESSING` | 확인(`CONFIRMED`) 또는 일방 취소(`CANCELED_BY_HOST`) + 작업 처리 중(일시 실패 뒤 자동 재시도 포함) | 출연료 / 위약금 | 확인 시각 / 취소 시각 |
| `FAILED` | 확인·일방 취소 + 작업 격리(`DEAD`), 또는 있어야 할 작업이 없음(데이터 이상, ERROR 로그) | 출연료 / 위약금 | 작업 완료(격리) 시각 / 확인·취소 시각 |
| `COMPLETED` | 확인·일방 취소 + 작업 완료 | 출연료 / 위약금 | 작업 완료 시각 |
| `CANCELED` | 불참(`ABSENT`) · 합의 취소(`CANCELED_BY_AGREEMENT`) | 출연료 | 불참 시각 · 취소 시각 |

### 6.4 강의와 다른 점

| 항목 | 강의 | 출연 | 이유 |
| --- | --- | --- | --- |
| 시각 | `OffsetDateTime`(`+09:00`) | `LocalDateTime`(KST, offset 없음) | billing과 먼저 합의(#301). 기존 출연 API·DB 규칙과 같다. 합칠 때 출연 시각을 `Asia/Seoul`로 읽는다 |
| 요약 `asOf` | 있음 | 없음 | 출연 분류는 저장된 상태만 보고 조회 시각에 기대지 않는다 |
| `earningType` 필터 | 없음 | 있음(선택) | billing 요청. 출연은 종류가 둘이다 |
| `SCHEDULED` | 쓴다 | 나오지 않는다 | 6.3 |
| `payoutScheduledAt` | 상태에 따라 있음 | 항상 없음 | 6.2 |

### 6.5 한계

| 한계 | 이유 |
| --- | --- |
| 확인 대기·`ON_HOLD`의 `statusUpdatedAt`은 출연 수정 시각이다(취소 요청이 거절되어 확인 대기로 돌아오면 거절 시각) | 출연에 성립·취소 요청 시각 컬럼이 따로 없다 |
| offset 페이지라 조회하는 동안 상태가 바뀌면 항목이 페이지 사이에서 중복되거나 빠질 수 있다 | 5장 강의와 같다 |
| 요약과 목록은 같은 시점의 값이 아니다 | 호출이 두 번이다. 출연은 `asOf`가 없다 |

## 7. 출연 Client (`shared.appearance.out.AppearanceApiClient`)

4장 강의 Client와 같은 방식이다. billing은 이 Client를 주입받는다.

```java
AppearanceEarningSummaryDto getEarningSummary(long guestUserId);
AppearanceEarningSummaryDto getEarningSummary(long guestUserId, AppearanceEarningType earningType);
AppearanceEarningPageDto getEarnings(long guestUserId, Collection<AppearanceEarningStatus> statuses, int page, int size);
AppearanceEarningPageDto getEarnings(long guestUserId, Collection<AppearanceEarningStatus> statuses,
                                     AppearanceEarningType earningType, int page, int size);
```

| 설정 | 기본값 | 설명 |
| --- | --- | --- |
| `hapbang.clients.appearance.base-url` | `http://localhost:${server.port:8080}` (apps) | 출연 서비스 주소. **이 값이 있을 때만 Client 빈이 생긴다**(모듈 테스트에는 없으니 Fake를 쓴다) |
| `hapbang.clients.appearance.connect-timeout` | 2s | 연결 제한 시간 |
| `hapbang.clients.appearance.read-timeout` | 5s | 응답 제한 시간 |
| `hapbang.internal-api.key` | (환경 변수) | Client가 헤더에 붙인다 |

- 실패는 `AppearanceApiCallException`이고 `getReason()`·`getStatus()`·`getCode()`는 4장 표와 같다(`REJECTED`: 4xx, `UNAVAILABLE`: 연결 실패·시간 초과·5xx·읽을 수 없는 성공 응답).
- `statuses`가 `null`이거나 비어 있으면 상태 조건 없이 보낸다(= 미지급만). `earningType`이 `null`이면 둘 다.
- 응답 시각은 offset 없는 `LocalDateTime`(KST) 그대로 읽힌다.
