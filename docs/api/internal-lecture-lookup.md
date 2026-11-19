# 강의 조회 내부 API 계약

쿠폰 모듈이 강의의 판매자·가격·판매 상태·제목을 읽을 때 쓰는 계약이다(#749). 판매자 쿠폰 발행 때 강의 소유자 확인과, 특정 강의 대상 쿠폰의 강의 가격 snapshot(`coupon.base_price`)에 하나 조회(1장)를 쓰고, 쿠폰 받기 화면 목록의 강의 제목·판매 중 거르기에 일괄 조회(2장)를 쓴다. 읽기만 하고 강의의 상태를 바꾸지 않는다. 쿠폰 설계는 [쿠폰 모듈 문서](../../apps/lecture-app/boundedContext/coupon/docs/design.md)의 "통신 계약"에 있다.

- 공통 규칙(경로, 내부 API 키 인증, 회원 세션 불가, 진입점 차단, RsData·ProblemDetail, Swagger 노출)은 [수익 내부 API 계약 1장](internal-earnings.md#1-공통-규칙)과 같다.
- 응답 타입은 `common/shared`의 `shared.lecture.dto.LectureLookupDto`다.
- 쿠폰은 lecture-app 안에서 같이 뜨지만 JVM이 나뉜 것처럼 HTTP로 부른다. 쿠폰의 `@HttpExchange` 클라이언트(`com.hapbang.coupon.out.lecture`)가 `hapbang.coupon.lecture-base-url`로 부르며, 기본값은 같은 서버의 내부 API 포트(`http://localhost:${hapbang.internal-api.port:${server.port:8080}}`)다. 쿠폰을 별도 앱으로 빼면 이 값만 바꾼다.

## 1. 조회 `GET /api/v1/internal/lectures/{lectureId}`

성공 200 `data` (`LectureLookupDto`)

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| `lectureId` | number | 강의 ID |
| `sellerId` | number | 판매자 회원 ID |
| `price` | number | 지금 가격(HP) |
| `saleStatus` | string | 강의 검수 상태 이름: `PENDING_INSPECTION`(검수 대기), `REJECTED`(반려), `ON_SALE`(판매 중). 장바구니 담기·주문은 `ON_SALE`에서만 받는다 |
| `title` | string | 지금 강의 제목. 강의는 늘 채운다. 일괄 조회를 더할 때 붙인 필드라 레코드에서 `null`을 막지 않는다 |

| 상태 | `code` | 뜻 |
| --- | --- | --- |
| 404 | `LECTURE_NOT_FOUND` | 강의가 없거나 삭제되었다 |
| 400 | `INVALID_REQUEST` | `lectureId`가 숫자가 아니다 |
| 401 | (본문 없음) | 내부 API 키가 없거나 틀리다 |
| 5xx·시간 초과 | | 결과를 모른다. 읽기 전용이라 다시 불러도 된다 |

- 삭제된 강의는 상태가 `ON_SALE`로 남아 있을 수 있으므로 상태를 그대로 넘기지 않고 없는 강의(404)로 답한다.
- 쿠폰 클라이언트는 `code`가 `LECTURE_NOT_FOUND`인 404만 "강의 없음"으로 보고, 그 밖의 실패(401, 본문 없는 404, 5xx, 연결 실패·시간 초과, 읽을 수 없는 응답)는 조회 불가로 다룬다. 조회 불가를 강의 없음이나 성공으로 바꾸지 않는다.

## 2. 일괄 조회 `GET /api/v1/internal/lectures?ids={id}&ids={id}…`

쿠폰 받기 화면 목록이 받을 수 있는 특정 강의 대상 쿠폰의 강의를 한 번에 읽는다. 쿠폰마다 1장을 부르지 않기 위해서다.

| 쿼리 | 필수 | 설명 |
| --- | --- | --- |
| `ids` | O | 강의 ID. `ids`를 여러 번 보내거나 쉼표로 구분한 값 하나로 보낸다(두 방식을 섞지 않는다). 1~100개(중복 포함, `LectureLookupDto.MAX_BATCH_SIZE`) |

성공 200 `data`: `{강의 ID: LectureLookupDto | null}`

- 요청한 ID마다 키가 있다(중복은 한 번, 요청 순서). 값의 필드는 1장과 같다.
- 없거나 삭제된 강의는 값이 `null`이다(1장의 404 대신). 판매 상태로 거르지 않는다: 검수 대기·반려 강의도 값이 있다. 판매 중인지는 부르는 쪽이 `saleStatus`로 판단한다.

```json
{
  "statusCode": 200,
  "message": "강의를 조회했습니다.",
  "data": {
    "11": { "lectureId": 11, "sellerId": 3, "price": 15000, "saleStatus": "ON_SALE", "title": "방송 장비 세팅" },
    "12": null
  }
}
```

| 상태 | `code` | 뜻 |
| --- | --- | --- |
| 400 | `INVALID_REQUEST` | `ids`가 없거나 0개·100개 초과이거나 숫자가 아니다 |
| 401 | (본문 없음) | 내부 API 키가 없거나 틀리다 |
| 5xx·시간 초과 | | 결과를 모른다. 읽기 전용이라 다시 불러도 된다 |

- 쿠폰 클라이언트는 100개씩 나눠 `ids`를 여러 번 보내는 모양으로 차례로 부르고, 나눈 요청마다 일괄 조회 전용 circuit breaker(`lecture-batch`, 설정은 1장 회로 `lecture`와 같은 `hapbang.coupon.lecture-client.*`)를 지난다. 쿠폰 받기 화면 목록이 요청마다 부르는 가장 많고 무거운 호출이라, 그 실패·지연으로 열린 회로가 1장을 쓰는 주문 쿠폰 계산·쿠폰 발행을 막지 않게 회로를 나눴다. 하나라도 실패하면 전체를 조회 불가로 다룬다(일부만 쓰지 않는다).
- 쿠폰 클라이언트는 성공 응답이어도 `data`가 없거나 요청한 ID의 키가 빠졌으면 조회 불가로 다룬다. 빠진 키를 강의 없음으로 바꾸지 않는다. 일괄 조회는 404 `LECTURE_NOT_FOUND`를 내지 않으므로 그 응답도 조회 불가다(4xx라 회로 실패로는 세지 않는다).
