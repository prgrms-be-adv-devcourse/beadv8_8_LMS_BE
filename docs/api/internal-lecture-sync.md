# 강의 동기화 내부 API 계약

aux-app AI context(강의 추천)가 강의 데이터의 **읽기용 사본**을 자기 저장소에 둘 때 쓰는 계약이다 (#712, 강의 결정 D82). 강의가 네 종류의 데이터를 **이벤트**(평상시 반영)와 **키셋 내부 API**(주 1회 정합성 확인, 첫 실행이 곧 초기 적재)로 같은 DTO로 제공한다.

- 공통 규칙(경로, 내부 API 키 인증, 회원 세션 불가, 내부 포트 분리, RsData·ProblemDetail, Swagger 노출)은 [수익 내부 API 계약 1장](internal-earnings.md#1-공통-규칙)과 같다. 쿼리 규칙은 [회원 내부 API 1장](internal-members.md#1-회원-목록-키셋)과 같다.
- AI는 강의 DB에 접근하지 않고 강의의 내부 상태·규칙을 해석하지 않는다. "보유 중인지", "추천해도 되는지", "평점에 반영되는 리뷰인지"는 **강의가 판단해서** 줄인 값(`result`, `onSale`, `counted`)으로 보낸다. 판단 기준은 강의가 바꿀 수 있다.
- 다른 모듈은 `shared.lecture.out.LectureApiClient`의 `getSyncLectures`·`getSyncPurchases`·`getSyncCartItems`·`getSyncReviews`로 부른다(D62).

## 1. 종류

| 종류 | DTO (`shared.lecture.dto`) | 이벤트 (`shared.lecture.event`, 토픽 `lecture.events.v1`) | key | 내부 API |
| --- | --- | --- | --- | --- |
| 강의 | `LectureSyncDto` | `LectureChangedEvent(lecture)` | `lecture:<강의 ID>` | `GET /api/v1/internal/lectures/sync/lectures` |
| 구매 | `LecturePurchaseSyncDto` | `LecturePurchaseChangedEvent(purchase)` | `order-item:<주문 항목 ID>` | `GET /api/v1/internal/lectures/sync/purchases` |
| 장바구니 항목 | `CartItemSyncDto` | `CartItemChangedEvent(cartItem)` | `cart-item:<장바구니 항목 ID>` | `GET /api/v1/internal/lectures/sync/cart-items` |
| 리뷰 | `LectureReviewSyncDto` | `LectureReviewChangedEvent(review)` | `lecture-review:<리뷰 ID>` | `GET /api/v1/internal/lectures/sync/reviews` |

헤더는 `eventType`(레코드 이름)·`eventVersion`(`1`)이다([kafka.md](../architecture/infrastructure/kafka.md) 발행 규칙).

## 2. 필드

**`LectureSyncDto`**

| 필드 | 설명 |
| --- | --- |
| `id`, `sellerUserId` | 강의 ID, 판매자 회원 ID. 판매자는 본인 강의를 추천에서 뺄 때 쓴다(장바구니·주문과 같은 규칙) |
| `title`, `description` | 제목, 소개(없으면 응답에서 빠진다) |
| `onSale` | 추천해도 되는 강의인지. 지금은 판매 중이고 삭제되지 않은 강의(`Lecture.isOnSale()`) |
| `deleted` | 삭제(Soft Delete)되었는지 |
| `contents` | 삭제되지 않은 콘텐츠 `{id, title, sequence}`, 순서대로. 콘텐츠 종류·파일은 싣지 않는다 |
| `version` | 강의 원본의 버전 |

**`LecturePurchaseSyncDto`** (주문 항목 하나)

| 필드 | 설명 |
| --- | --- |
| `orderItemId`, `buyerUserId`, `lectureId` | 주문 항목 ID, 구매자, 강의 |
| `result` | 아래 구매 결과 |
| `orderedAt` | 주문한 시각. 항상 있다 |
| `purchasedAt` | 결제가 끝나 구매 완료된 시각. 결제 전·결제 실패면 없다 |
| `version` | 주문 항목 원본의 버전 |

| `result` | 주문 항목 상태 | 뜻 | AI 사용 |
| --- | --- | --- | --- |
| `OWNED` | `PURCHASED`, `CONFIRMED`, `PAYOUT_PENDING`, `PAID_OUT` | 구매 완료부터 지급 뒤까지(시청 가능) | 추천에서 제외 + 관심 신호 |
| `PENDING` | `HOLD_PENDING`, `HOLD_COMPENSATING`, `CANCEL_PENDING`, `RETURN_PENDING` | 결제·구매 취소·반환 처리 중 | 추천에서 제외 |
| `CANCELED` | `CANCELED` | 구매 취소됨 | 무시 |
| `RETURNED` | `RETURNED` | 이의 제기 인정으로 반환됨 | 무시 (다시 추천 가능) |
| `FAILED` | `HOLD_FAILED` | 결제 실패 | 무시 |

- `OWNED`와 `PENDING`을 합친 것이 **보유**(같은 강의를 다시 살 수 없는 상태, 용어집)와 같다. 한쪽만 맞으면 보유 강의가 추천되거나 살 수 있는 강의가 추천에서 영원히 빠지므로, 강의는 모든 상태에서 이 관계를 테스트한다(`OrderItemStatusTest`).

**`CartItemSyncDto`**

| 필드 | 설명 |
| --- | --- |
| `id`, `userId`, `lectureId` | 장바구니 항목 ID, 장바구니 주인, 강의 |
| `deleted` | 장바구니에서 뺐는지(직접 빼기, 결제가 끝나 자동으로 빼기) |
| `addedAt` | 담은 시각 |
| `version` | 장바구니 항목 원본의 버전 |

- 다시 담으면 새 항목(새 ID)이다. 같은 회원·강의에 항목이 여러 개일 수 있고, 행 단위로 다룬다.
- 강의가 삭제·판매 중지되어도 항목은 `deleted=false`로 남는다(장바구니 조회 때 구매 불가로 보인다). 강의의 `onSale`로 거른다.

**`LectureReviewSyncDto`**

| 필드 | 설명 |
| --- | --- |
| `id`, `lectureId`, `reviewerUserId` | 리뷰 ID, 강의, 작성자 |
| `rating` | 별점 1~5. 리뷰 본문은 싣지 않는다 |
| `counted` | 강의 평균 평점·리뷰 목록에 반영되는 리뷰인지. 지운 리뷰와, 주문 항목이 반환 처리 중·반환된 리뷰는 `false` |
| `deleted` | 지웠는지(Soft Delete) |
| `version` | 리뷰 원본의 버전. `counted`만 바뀌어도 오른다 |

- 지우고 다시 쓰면 새 리뷰(새 ID)다. 반환된 항목의 리뷰는 같은 강의를 다시 사서 새 리뷰를 쓸 때 강의가 지운다(`deleted=true` 이벤트가 따로 나간다).

**시각**은 offset을 붙인 ISO 8601(`+09:00`)이다. DTO가 시각을 KST offset으로 맞춰 두므로, JSON을 읽은 쪽의 Jackson이 offset을 UTC로 바꿔 읽어도 같은 행이면 `equals`·`hashCode`가 같다. 값이 없으면 응답 JSON에서 빠진다.

## 3. 버전과 이벤트

- 각 DTO의 `version`은 원본 행의 `@Version`이다. **DTO에 실린 값이 바뀌면 version이 반드시 오른다.** 강의 콘텐츠만 바뀌어도 강의 version이 오른다(교체가 강의 수정 시각을 바꾼다). 리뷰의 `counted`가 주문 항목 상태 때문에 바뀌면 강의가 리뷰 version을 강제로 올린다(리뷰 수정 시각은 그대로다).
- 반대로 DTO에 없는 필드만 바뀌어 version만 오를 수 있다(가격·썸네일, 구매 확정·지급, 리뷰 본문). 이때 이벤트는 생략될 수 있고, 주간 대조에서 "version은 크고 내용은 같음"이 나오면 정상이다(네 종류 공통).
- 이벤트는 바뀐 뒤의 **전체 상태**다. 받는 쪽은 version이 크면 반영하고, 작으면 버리고, 같은데 내용이 다르면 어긋남으로 기록한다. 이벤트끼리 순서는 보장하지 않는다(커밋 뒤 비동기 전송·재발행, key가 다르면 다른 partition).

이벤트가 나가는 지점:

| 종류 | 지점 |
| --- | --- |
| 강의 | 등록, 정보 수정, 콘텐츠 교체, 검수 승인·반려, 재검수 신청, 삭제 |
| 구매 | 결과·실린 값이 바뀌는 전이만: 주문 생성(`PENDING`), 결제 성공(`OWNED`, `purchasedAt`), 결제 실패(`FAILED`, 확정 거절·보류 무효화 모두), 구매 취소 요청(`PENDING`)·완료(`CANCELED`), 이의 제기 인정(`PENDING`)·반환 완료(`RETURNED`). 결제 취소 처리 중, 구매 확정, 지급, 이의 제기 접수·철회·기각은 결과가 같아 보내지 않는다 |
| 장바구니 항목 | 담기, 빼기, 결제가 끝나 주문한 강의를 장바구니에서 자동으로 빼기 |
| 리뷰 | 작성, 수정, 삭제, 반환된 항목의 리뷰 자동 삭제, 이의 제기 인정으로 `counted`가 바뀔 때(그 항목에 살아 있는 리뷰가 있을 때만) |

## 4. 내부 API (키셋)

`GET /api/v1/internal/lectures/sync/{lectures|purchases|cart-items|reviews}?afterId={id}&size={n}`

| 쿼리 | 필수 | 설명 |
| --- | --- | --- |
| `afterId` | X | 이 ID보다 큰 항목부터. 생략하면 처음부터. 0 이상 |
| `size` | X | 페이지 크기 1~500 (`*SyncPageDto.MAX_SIZE`), 기본 100 |

- 대상: **모든 행**. 강의는 모든 상태(검수 대기·반려·삭제 포함), 구매는 모든 결과, 장바구니 항목·리뷰는 뺀·지운 행(`deleted=true`)까지 싣는다. 강의 콘텐츠만 삭제되지 않은 것을 싣는다.
- 정렬: ID 오름차순 고정. 구매의 ID는 주문 항목 ID다.
- 응답 `data` (`LectureSyncPageDto`·`LecturePurchaseSyncPageDto`·`CartItemSyncPageDto`·`LectureReviewSyncPageDto`)

| 필드 | 설명 |
| --- | --- |
| `items` | 항목. 각 항목은 같은 종류의 이벤트와 같은 DTO다 |
| `nextAfterId` | 다음 페이지의 `afterId`. **마지막 페이지면 응답에서 빠진다(null)** |

```json
{
  "statusCode": 200,
  "message": "구매 동기화 목록을 조회했습니다.",
  "data": {
    "items": [
      { "orderItemId": 31, "buyerUserId": 7, "lectureId": 11, "result": "PENDING",
        "orderedAt": "2026-11-06T09:00:00+09:00", "version": 0 },
      { "orderItemId": 32, "buyerUserId": 7, "lectureId": 12, "result": "OWNED",
        "orderedAt": "2026-11-06T09:00:00+09:00", "purchasedAt": "2026-11-06T09:00:01.123456+09:00", "version": 1 }
    ],
    "nextAfterId": 32
  }
}
```

- 같은 ID·같은 version이면 이벤트 payload와 내부 API 항목이 **같은 값**이다. 둘 다 강의 엔티티의 `toSyncDto()`로 만들고, 강의 시계를 DB와 같은 마이크로초 정밀도로 둔다.
- 오류: `size`가 1~500 밖이거나 `afterId`가 음수면 400 `INVALID_REQUEST` + `errors[{field, message}]`. 키가 없거나 틀리면 401(본문 없음). 외부 API 포트로 오면 404다(내부 포트 분리).

## 5. 사용법 (aux-app)

```java
Long afterId = null;
do {
    LecturePurchaseSyncPageDto page = lectureApiClient.getSyncPurchases(afterId, LecturePurchaseSyncPageDto.MAX_SIZE);
    page.items().forEach(purchaseApplier::apply);   // 이벤트와 같은 적용기: version이 크면 반영
    afterId = page.nextAfterId();
} while (afterId != null);
```

- 주 1회(월요일 10:00 KST) 종류별로 따로 처음부터 끝까지 읽어 이벤트 누락을 바로잡는다. 첫 실행이 곧 초기 적재다.
- 키셋이라 페이지를 넘기는 사이 생긴 행 때문에 중복·누락이 생기지 않는다. 대조 중 바뀐 행은 이벤트로 들어온다.
- 탈퇴 회원: 강의는 따로 할 일이 없다. AI가 `UserMaskedEvent`를 받아 자기 쪽 데이터를 지우고, 이후 들어오는 그 회원 데이터를 버린다.
- 같은 토픽의 지금 소비자인 고객센터(`customer-support-app.objection-result`)는 이 타입을 기본 처리기에서 무시한다. 고객센터는 native 이미지라 이 레코드들을 reflection에 등록해 두었다(`reachability-metadata.json`, 일반 해결은 #722).
- 같은 토픽을 소비할 계획인 알림(`notification.lecture-event`, [알림 설계](../architecture/infrastructure/notification.md))은 구현할 때 같은 처리가 필요하다: 기본 `@KafkaHandler`로 무시하고, native 앱이면 reflection에 등록한다.
