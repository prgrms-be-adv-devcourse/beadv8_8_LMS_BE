# 쿠폰 사용 내부 API 계약

강의 모듈이 주문할 때 쿠폰 할인을 계산하고, 쿠폰을 예약·해제하고, 강의 가격을 올려도 되는지 확인할 때 쓰는 내부 HTTP API다. 쿠폰 모듈이 제공하고 강의가 부른다. 쿠폰 설계는 [쿠폰 모듈 문서](../../apps/lecture-app/boundedContext/coupon/docs/design.md)의 "계산과 주문"·"모듈 경계"에 있다.

- 공통 규칙(경로, 내부 API 키 인증, 회원 세션 불가, 진입점 차단, RsData·ProblemDetail, Swagger 노출)은 [수익 내부 API 계약 1장](internal-earnings.md#1-공통-규칙)과 같다. 회원 로그인만 있고 키가 없으면 401이다. 쿠폰 진입점(`CouponInternalFacade`)은 `INTERNAL` 역할만 받는다.
- 요청·응답 타입은 `common/shared`의 `shared.coupon.api` record다(아래 각 절). 요청 record는 필수 값·빈 목록·음수 가격 같은 구조만 검사하고, 어기면 400 `INVALID_REQUEST`다.
- 쿠폰은 lecture-app 안에서 같이 뜨지만 JVM이 나뉜 것처럼 HTTP로 부른다. 강의 쪽 클라이언트와 주문 단계 분리는 아직 없다(다음 단계).
- 사용 경로(이 다섯 API)는 Redis를 쓰지 않는다. Redis가 멈춰도 동작한다.
- 금액은 HP 정수다. 쿠폰 한 장의 할인 `discount = platformAmount + sellerAmount`, 항목 할인 `discount = platformDiscount + sellerDiscount`, 구매자 결제액 `buyerAmount = price - discount ≥ 0`, 판매자 지급액 `sellerPayout = price - sellerDiscount`다.
- 쿠폰 유형 `couponType`은 `SELLER`(판매자 쿠폰, 판매자 부담) 또는 `PLATFORM`(플랫폼 쿠폰, 플랫폼 부담)이다. 한 항목에 판매자 쿠폰 1장과 플랫폼 쿠폰 1장까지, 판매자 → 플랫폼 순서로 적용한다.

## 1. 흐름

```
① calculate    POST .../coupons/calculate                → 추천 또는 선택 매핑의 금액·쿠폰별 근거(읽기만)
② reserve      POST .../coupons/reservations             → 근거를 다시 검증·계산해 모든 쿠폰을 예약(전부 또는 전무)
   - 결과를 모르면(시간 초과 등) ④로 확인하거나 같은 workId·같은 내용으로 다시 보낸다
③ release      POST .../coupons/releases                 → 주문 실패·보상 뒤 예약을 해제(전부 또는 전무)
④ status       GET  .../coupons/reservations/{workId}    → 예약 작업 결과(읽기만)
⑤ blocked      GET  .../coupons/price-raise-blocked      → 강의 가격 인상 제한(읽기만)
```

## 2. 작업 ID와 멱등

| 항목 | 규칙 |
| --- | --- |
| `workId` 형식 | 1~100자 `[A-Za-z0-9:_-]`. 아니면 400 `INVALID_WORK_ID` |
| 같은 workId·같은 내용 | 처음 결과를 그대로 다시 준다. 예약 성공은 저장된 사용 이력으로 다시 만들고, 예약 거절은 같은 거절 코드와 같은 `memberCouponId`로 다시 거절한다 |
| 같은 workId·다른 내용 | 409 `COUPON_WORK_CONFLICT`. 내용은 회원·주문과 항목·쿠폰(순서 무관)으로 비교한다. 해제 요청에는 회원이 없으므로 해제할 사용 시도에 저장된 회원 ID를 쓴다(5장) |
| 예약과 해제 | 서로 다른 작업이다. 예약 workId와 해제 workId는 따로 정한다 |

## 3. 계산 `POST /api/v1/internal/coupons/calculate`

요청 (`CouponCalculateRequest`)

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `memberId` | number | O | 주문하는 회원 ID |
| `items` | array | O | 주문할 강의 목록(1개 이상). 한 주문에 같은 강의를 두 번 넣을 수 없다 |
| `items[].lectureId` | number | O | 강의 ID |
| `items[].sellerId` | number | O | 판매자 회원 ID |
| `items[].price` | number | O | 강의 가격(HP, 0 이상) |
| `selections` | array 또는 null | | 고른 쿠폰 매핑. 없거나 `null`이면 추천, `[]`이면 쿠폰 미사용(할인 0), 값이 있으면 그 매핑만 검증·계산한다. 매핑에 없는 강의는 추천으로 채우지 않는다 |
| `selections[].memberCouponId` | number | O | 발급 쿠폰 ID |
| `selections[].lectureId` | number | O | 쿠폰을 쓸 강의 ID(`items`에 있는 강의) |

```json
{
  "memberId": 7,
  "items": [
    { "lectureId": 101, "sellerId": 30, "price": 30000 },
    { "lectureId": 102, "sellerId": 31, "price": 10000 }
  ],
  "selections": null
}
```

성공 200 `data` (`CouponCalculateResponse`)

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| `recommended` | boolean | 매핑을 생략해 추천한 결과인지 |
| `optimal` | boolean | 추천 결과가 최대 할인으로 확인되었는지. 추천하고 탐색을 끝까지 마쳤을 때만 `true`다. 탐색 상한을 넘어 찾은 최선이거나 추천이 아니면(선택 매핑·미사용) `false`다. `false`인 결과를 "최대 할인"이라고 표시하지 않는다 |
| `items[]` | array | 요청한 강의 순서대로의 항목별 결과 |
| `items[].lectureId` · `price` | number | 강의 ID, 강의 가격 |
| `items[].discount` · `platformDiscount` · `sellerDiscount` | number | 항목 할인 합, 플랫폼 부담, 판매자 부담 |
| `items[].buyerAmount` · `sellerPayout` | number | 구매자 결제액, 판매자 지급액 |
| `items[].coupons[]` | array | 적용 순서대로의 쿠폰별 근거. 쿠폰이 없으면 빈 목록 |
| `coupons[].memberCouponId` · `couponId` | number | 발급 쿠폰 ID, 쿠폰 정책 ID |
| `coupons[].couponType` | string | `SELLER` 또는 `PLATFORM` |
| `coupons[].applyOrder` | number | 항목 안 적용 순서(1부터) |
| `coupons[].baseAmount` | number | 적용 직전 남은 금액 |
| `coupons[].discount` · `platformAmount` · `sellerAmount` | number | 이 쿠폰의 할인, 플랫폼 부담분, 판매자 부담분 |
| `coupons[].expectedGeneration` | number | 계산 때 읽은 발급 쿠폰 generation. 예약 요청에 그대로 보낸다 |
| `coupons[].policyVersion` | number | 계산 때 읽은 쿠폰 정책 버전. 예약 요청에 그대로 보낸다 |
| `totalDiscount` · `totalBuyerAmount` | number | 전체 할인 합, 전체 구매자 결제액 합 |

```json
{
  "statusCode": 200,
  "message": "쿠폰 할인을 계산했습니다.",
  "data": {
    "recommended": true,
    "optimal": true,
    "items": [
      {
        "lectureId": 101, "price": 30000, "discount": 7500, "platformDiscount": 2500, "sellerDiscount": 5000,
        "buyerAmount": 22500, "sellerPayout": 25000,
        "coupons": [
          { "memberCouponId": 501, "couponId": 11, "couponType": "SELLER", "applyOrder": 1, "baseAmount": 30000,
            "discount": 5000, "platformAmount": 0, "sellerAmount": 5000, "expectedGeneration": 0, "policyVersion": 1 },
          { "memberCouponId": 502, "couponId": 12, "couponType": "PLATFORM", "applyOrder": 2, "baseAmount": 25000,
            "discount": 2500, "platformAmount": 2500, "sellerAmount": 0, "expectedGeneration": 0, "policyVersion": 3 }
        ]
      },
      {
        "lectureId": 102, "price": 10000, "discount": 0, "platformDiscount": 0, "sellerDiscount": 0,
        "buyerAmount": 10000, "sellerPayout": 10000, "coupons": []
      }
    ],
    "totalDiscount": 7500,
    "totalBuyerAmount": 32500
  }
}
```

- 강의마다 강의 조회 내부 API([강의 조회 계약](internal-lecture-lookup.md))로 가격·판매자를 다시 확인한다. 묶음 조회가 없어 강의마다 한 번 부른다. 강의가 없으면 404 `LECTURE_NOT_FOUND`, 가격·판매자가 다르면 409 `COUPON_LECTURE_PRICE_MISMATCH`(detail에 강의 ID)다.
- 아무것도 바꾸지 않는다. 다시 불러도 된다(재계산도 같은 API).

## 4. 예약 `POST /api/v1/internal/coupons/reservations`

요청 (`CouponReserveRequest`)

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `memberId` | number | O | 주문하는 회원 ID |
| `orderId` | number | O | 주문 ID |
| `workId` | string | O | 예약 작업 ID(2장) |
| `items` | array | O | 주문 항목 목록(1개 이상). 같은 강의를 두 번 넣을 수 없다 |
| `items[].orderItemId` | number | O | 주문 항목 ID |
| `items[].lectureId` · `sellerId` | number | O | 강의 ID, 판매자 회원 ID |
| `items[].price` | number | O | 주문 스냅샷의 강의 가격(HP, 0 이상). 예약은 강의를 다시 조회하지 않고 이 가격으로 다시 계산한다 |
| `items[].coupons` | array | O | 이 항목에 쓸 쿠폰의 계산 근거. 쿠폰이 없으면 `[]` |
| `coupons[].memberCouponId` | number | O | 발급 쿠폰 ID |
| `coupons[].expectedGeneration` · `policyVersion` | number | O | 계산 응답의 값 |
| `coupons[].applyOrder` | number | O | 계산 응답의 적용 순서 |
| `coupons[].discount` · `platformAmount` · `sellerAmount` | number | O | 계산 응답의 금액 근거 |

```json
{
  "memberId": 7,
  "orderId": 9001,
  "workId": "order-9001:reserve",
  "items": [
    {
      "orderItemId": 90011, "lectureId": 101, "sellerId": 30, "price": 30000,
      "coupons": [
        { "memberCouponId": 501, "expectedGeneration": 0, "policyVersion": 1, "applyOrder": 1,
          "discount": 5000, "platformAmount": 0, "sellerAmount": 5000 },
        { "memberCouponId": 502, "expectedGeneration": 0, "policyVersion": 3, "applyOrder": 2,
          "discount": 2500, "platformAmount": 2500, "sellerAmount": 0 }
      ]
    },
    { "orderItemId": 90012, "lectureId": 102, "sellerId": 31, "price": 10000, "coupons": [] }
  ]
}
```

성공 200 `data` (`CouponReserveResponse`)

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| `workId` | string | 예약 작업 ID |
| `status` | string | 항상 `RESERVED` |
| `items[].orderItemId` | number | 주문 항목 ID. 예약한 쿠폰이 있는 항목만 온다 |
| `items[].discount` · `platformDiscount` · `sellerDiscount` | number | 항목 할인 합, 플랫폼 부담, 판매자 부담 |
| `items[].coupons[].memberCouponId` · `couponId` | number | 발급 쿠폰 ID, 쿠폰 정책 ID |
| `items[].coupons[].reservedGeneration` | number | 예약으로 오른 generation. 해제 요청에 보낸다 |
| `items[].coupons[].applyOrder` · `baseAmount` | number | 적용 순서, 적용 직전 남은 금액 |
| `items[].coupons[].discount` · `platformAmount` · `sellerAmount` | number | 이 쿠폰의 할인, 플랫폼 부담분, 판매자 부담분 |
| `items[].coupons[].policyVersion` | number | 예약 때 확인한 쿠폰 정책 버전 |

```json
{
  "statusCode": 200,
  "message": "쿠폰을 예약했습니다.",
  "data": {
    "workId": "order-9001:reserve",
    "status": "RESERVED",
    "items": [
      {
        "orderItemId": 90011, "discount": 7500, "platformDiscount": 2500, "sellerDiscount": 5000,
        "coupons": [
          { "memberCouponId": 501, "couponId": 11, "reservedGeneration": 1, "applyOrder": 1, "baseAmount": 30000,
            "discount": 5000, "platformAmount": 0, "sellerAmount": 5000, "policyVersion": 1 },
          { "memberCouponId": 502, "couponId": 12, "reservedGeneration": 1, "applyOrder": 2, "baseAmount": 25000,
            "discount": 2500, "platformAmount": 2500, "sellerAmount": 0, "policyVersion": 3 }
        ]
      }
    ]
  }
}
```

- 응답 `items`는 저장한 사용 이력으로 만든다. 쿠폰이 없는 주문 항목(위 예시의 90012)은 빠진다. 처음 응답과 같은 workId 재응답이 같다.
- 한 트랜잭션에서 소유·기한·상태·정책 버전·적용 대상·중첩·순서를 다시 검증하고, 금액을 다시 계산해 요청 근거와 대조한다. 그 뒤 모든 쿠폰을 발급 쿠폰 ID 오름차순으로 generation 조건부 갱신(CAS)해 예약하고 사용 이력을 쓴다.
- 하나라도 실패하면 전부 롤백한다. 다른 쿠폰도 AVAILABLE 그대로다. 거절은 작업 기록에 거절 코드(실패한 쿠폰이 있으면 `CODE:memberCouponId`)로 남아, 같은 workId로 다시 보내면 같은 코드·같은 `memberCouponId`로 거절된다.
- 해제한 쿠폰은 같은 주문 항목에 다시 예약할 수 있다. 새 generation으로 새 사용 시도가 생긴다. 같은 주문 항목·같은 발급 쿠폰의 예약(RESERVED)은 동시에 하나뿐이다.
- 0 할인 쿠폰을 골라도 거절하지 않고 할인 0으로 예약한다.
- 실패한 409 응답에는 실패한 쿠폰의 `memberCouponId`(number)가 함께 온다. 같은 workId 재응답에도 같다. 특정 쿠폰이 아닌 실패(작업 내용 충돌 등)에는 없다.

```json
{
  "type": "about:blank",
  "title": "Conflict",
  "status": 409,
  "detail": "쿠폰 상태가 바뀌었습니다. 다시 계산해 주세요.",
  "code": "COUPON_GENERATION_MISMATCH",
  "memberCouponId": 502
}
```

## 5. 해제 `POST /api/v1/internal/coupons/releases`

요청 (`CouponReleaseRequest`)

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `orderId` | number | O | 주문 ID. 예약한 주문과 같아야 한다 |
| `workId` | string | O | 해제 작업 ID(2장) |
| `usages` | array | O | 해제할 사용 시도(1개 이상) |
| `usages[].memberCouponId` | number | O | 발급 쿠폰 ID |
| `usages[].generation` | number | O | 예약 응답의 `reservedGeneration` |
| `reason` | string | O | 해제 사유(500자 이하) |
| `compensationRef` | string | | 보상 완료 근거(200자 이하). 지금은 기록만 하고 검증하지 않는다 |

```json
{
  "orderId": 9001,
  "workId": "order-9001:release",
  "usages": [
    { "memberCouponId": 501, "generation": 1 },
    { "memberCouponId": 502, "generation": 1 }
  ],
  "reason": "HOLD_FAILED",
  "compensationRef": null
}
```

성공 200 `data` (`CouponReleaseResponse`)

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| `workId` | string | 해제 작업 ID |
| `usages[].memberCouponId` · `generation` | number | 해제한 사용 시도 |
| `usages[].releasedTo` | string | 해제 뒤 발급 쿠폰 상태: `AVAILABLE`(만료 전), `EXPIRED`(만료 뒤) |

```json
{
  "statusCode": 200,
  "message": "쿠폰 예약을 해제했습니다.",
  "data": {
    "workId": "order-9001:release",
    "usages": [
      { "memberCouponId": 501, "generation": 1, "releasedTo": "AVAILABLE" },
      { "memberCouponId": 502, "generation": 1, "releasedTo": "EXPIRED" }
    ]
  }
}
```

- 나열한 예약(RESERVED) 사용 시도를 한 트랜잭션에서 CAS로 해제한다. 전부 성공 또는 전부 롤백이다.
- 사용됨(USED)·다른 주문·다른 generation이면 409 `COUPON_RELEASE_NOT_ALLOWED`다. 나열한 사용 시도를 찾지 못해도 같다.
- 요청에 회원 ID가 없다. 작업 내용 비교(2장)에는 해제할 사용 시도에 저장된 회원 ID를 쓴다.

## 6. 예약 상태 `GET /api/v1/internal/coupons/reservations/{workId}?memberId=&orderId=`

| 파라미터 | 위치 | 필수 | 설명 |
| --- | --- | --- | --- |
| `workId` | 경로 | O | 예약 작업 ID(2장 형식) |
| `memberId` | 쿼리 | O | 주문한 회원 ID |
| `orderId` | 쿼리 | O | 주문 ID |

성공 200 `data` (`CouponReservationStatusResponse`)

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| `workId` | string | 예약 작업 ID |
| `status` | string | `RESERVED`(예약됨), `RELEASED`(모두 해제됨), `REJECTED`(거절됨), `NOT_FOUND`(기록 없음. 회원·주문이 다를 때도) |
| `rejectCode` | string | 거절 코드(8장의 `code`, 발급 쿠폰 ID 없이 코드만). `REJECTED`일 때만 있고 그 밖에는 빠진다 |
| `items` | array | 예약 응답(4장)의 `items`와 같은 모양에 `coupons[].status`(`RESERVED`·`RELEASED`, 사용 시도별 상태)를 더했다. 저장한 사용 이력으로 만들어 쿠폰이 없는 주문 항목은 빠진다. `REJECTED`·`NOT_FOUND`면 빈 목록 |

```json
{
  "statusCode": 200,
  "message": "쿠폰 예약 상태를 조회했습니다.",
  "data": {
    "workId": "order-9001:reserve",
    "status": "RESERVED",
    "items": [
      {
        "orderItemId": 90011, "discount": 5000, "platformDiscount": 0, "sellerDiscount": 5000,
        "coupons": [
          { "memberCouponId": 501, "couponId": 11, "reservedGeneration": 1, "applyOrder": 1, "baseAmount": 30000,
            "discount": 5000, "platformAmount": 0, "sellerAmount": 5000, "policyVersion": 1, "status": "RESERVED" }
        ]
      }
    ]
  }
}
```

- 읽기만 한다. 예약 결과를 모를 때(시간 초과 등) 쓴다. `NOT_FOUND`는 예약이 처리되지 않았다는 뜻이므로 같은 workId·같은 내용으로 다시 예약해도 된다.

## 7. 가격 인상 제한 `GET /api/v1/internal/coupons/price-raise-blocked?lectureId=`

| 파라미터 | 위치 | 필수 | 설명 |
| --- | --- | --- | --- |
| `lectureId` | 쿼리 | O | 강의 ID |

성공 200 `data` (`CouponPriceRaiseBlockedResponse`)

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| `lectureId` | number | 강의 ID |
| `blocked` | boolean | 가격을 올릴 수 없는지. 그 강의 대상 플랫폼 쿠폰이 발급 중(OPEN)이거나, 그 쿠폰의 예약된(RESERVED) 발급 쿠폰 또는 만료 전 사용 가능한(AVAILABLE) 발급 쿠폰이 있으면 `true`. 판매자 쿠폰은 보지 않는다. 발급 중단(SUSPENDED) 쿠폰은 발급 중으로 치지 않는다 |

```json
{
  "statusCode": 200,
  "message": "가격 인상 제한을 조회했습니다.",
  "data": { "lectureId": 101, "blocked": true }
}
```

- 읽기만 한다. 강의가 가격을 올리기 전에 부른다(가격 인상 제한 집행은 다음 단계).

## 8. 오류

| 상태 | `code` | API | 뜻 |
| --- | --- | --- | --- |
| 400 | `INVALID_REQUEST` | 전부 | 필수 값 없음, 빈 `items`·`usages`, 음수 가격, `reason` 500자 초과·`compensationRef` 200자 초과, 숫자가 아님, JSON 형식 오류 |
| 400 | `INVALID_WORK_ID` | 예약·해제·예약 상태 | `workId` 형식이 2장과 다르다 |
| 400 | `COUPON_TOO_MANY_ITEMS` | 계산·예약 | 항목 수가 상한(`hapbang.coupon.calculate.max-items`)을 넘는다 |
| 400 | `COUPON_DUPLICATE_LECTURE` | 계산·예약 | 한 주문에 같은 강의가 두 번 있다 |
| 409 | `COUPON_NOT_APPLICABLE` | 계산·예약 | 쿠폰의 적용 대상이 아닌 강의다 |
| 409 | `COUPON_NOT_USABLE` | 계산·예약 | 사용 불가: 본인 소유가 아니거나, AVAILABLE이 아니거나, 만료(`expiresAt ≤ 지금`)됐다 |
| 409 | `COUPON_STACKING_VIOLATION` | 계산·예약 | 중첩 위반: 같은 주체 쿠폰 두 장, 단독 쿠폰과 다른 쿠폰, 한 쿠폰을 두 항목에 |
| 404 | `LECTURE_NOT_FOUND` | 계산 | 강의가 없거나 삭제되었다 |
| 409 | `COUPON_LECTURE_PRICE_MISMATCH` | 계산 | 요청의 가격·판매자가 강의 조회 결과와 다르다. detail에 강의 ID가 있다 |
| 409 | `COUPON_GENERATION_MISMATCH` | 예약 | 발급 쿠폰의 generation이 계산 때와 다르다(다른 주문이 먼저 예약 등) |
| 409 | `COUPON_POLICY_VERSION_CHANGED` | 예약 | 쿠폰 정책 버전이 계산 때와 다르다 |
| 409 | `COUPON_AMOUNT_MISMATCH` | 예약 | 다시 계산한 금액·적용 순서가 요청 근거와 다르다 |
| 409 | `COUPON_WORK_CONFLICT` | 예약·해제 | 같은 workId로 다른 내용을 보냈다 |
| 409 | `COUPON_RELEASE_NOT_ALLOWED` | 해제 | 사용됨(USED)·다른 주문·다른 generation이거나 사용 시도를 찾지 못해 해제할 수 없다 |
| 503 | `LECTURE_CATALOG_UNAVAILABLE` | 계산 | 강의 조회 내부 API에 닿지 못했다. 다시 불러도 된다 |
| 401 | (본문 없음) | 전부 | 내부 API 키가 없거나 틀리다 |
| 5xx·시간 초과 | | 전부 | 결과를 모른다. 계산·조회는 다시 부르고, 예약·해제는 같은 workId·같은 내용으로 다시 보내거나 예약 상태로 확인한다 |

- 409 중 특정 발급 쿠폰 때문에 실패한 것(적용 대상 아님, 사용 불가, 중첩 위반, generation 불일치, 정책 버전 변경, 금액 근거 불일치)에는 그 쿠폰의 `memberCouponId`가 함께 온다. 같은 workId로 다시 보낸 예약의 거절에도 같은 값이 온다.
