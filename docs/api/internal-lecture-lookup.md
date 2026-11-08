# 강의 조회 내부 API 계약

쿠폰 모듈이 강의 하나의 판매자·가격·판매 상태를 읽을 때 쓰는 계약이다(#749). 판매자 쿠폰 발행 때 강의 소유자 확인과, 특정 강의 대상 쿠폰의 강의 가격 snapshot(`coupon.base_price`)에 쓴다. 읽기만 하고 강의의 상태를 바꾸지 않는다. 쿠폰 설계는 [쿠폰 모듈 문서](../../apps/lecture-app/boundedContext/coupon/docs/design.md)의 "통신 계약"에 있다.

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

| 상태 | `code` | 뜻 |
| --- | --- | --- |
| 404 | `LECTURE_NOT_FOUND` | 강의가 없거나 삭제되었다 |
| 400 | `INVALID_REQUEST` | `lectureId`가 숫자가 아니다 |
| 401 | (본문 없음) | 내부 API 키가 없거나 틀리다 |
| 5xx·시간 초과 | | 결과를 모른다. 읽기 전용이라 다시 불러도 된다 |

- 삭제된 강의는 상태가 `ON_SALE`로 남아 있을 수 있으므로 상태를 그대로 넘기지 않고 없는 강의(404)로 답한다.
- 쿠폰 클라이언트는 `code`가 `LECTURE_NOT_FOUND`인 404만 "강의 없음"으로 보고, 그 밖의 실패(401, 본문 없는 404, 5xx, 연결 실패·시간 초과, 읽을 수 없는 응답)는 조회 불가로 다룬다. 조회 불가를 강의 없음이나 성공으로 바꾸지 않는다.
