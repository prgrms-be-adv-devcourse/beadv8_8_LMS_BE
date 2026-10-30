# 강의 모듈 — 스키마·API·구현 참고

> 문서 지도: [핵심 설계](module.md) · [모듈 연동·운영](integration.md) · [스키마·API·구현 참고](reference.md) · [결정 기록](decisions.md)
>
> 장 번호는 기존 문서의 참조를 깨지 않도록 유지한다.

## 7. 스키마 (`lectures`)

ERD를 기준으로 하고, 이 설계에 필요한 변경을 표시했다. 모든 테이블은 `@Table(schema = "lectures")`. 공통 컬럼 `created_at`, `updated_at`은 채팅 모듈과 같이 엔티티에 직접 두고 도메인 메서드가 받은 시각으로 기록한다(`BaseEntity`·JPA Auditing 미사용. 회원 모듈의 `BaseEntity`가 병합되면 통일 여부를 팀과 정한다).

### 7.1 테이블

**lecture**

| 컬럼 | 타입 | 비고 |
| --- | --- | --- |
| id | bigint PK | |
| seller_user_id | bigint | 회원 ID (FK 없음) |
| thumbnail_file_id | bigint | `file_resource.id` |
| title | varchar | |
| description | text | |
| price_hp | bigint | 1,000 이상, 1,000 단위 (D6) |
| status | varchar | `PENDING_INSPECTION` / `REJECTED` / `ON_SALE` |
| rejection_reason | text | **추가** |
| inspection_requested_at | timestamp | **추가** |
| inspected_at | timestamp | **추가** |
| inspected_by | bigint | **추가**, 관리자 회원 ID |
| version | bigint | **추가**. 낙관적 락 (같은 강의를 동시에 바꾸는 요청 직렬화) |
| deleted_at | timestamp | Soft Delete |

**lecture_content**: ERD + `deleted_at`. `content_type` = `VIDEO` / `DOCUMENT`. 살아 있는 행끼리 `(lecture_id, sequence)`가 겹치지 않아야 하지만, **MVP에서는 DB 제약을 두지 않고** `Lecture` 애그리거트(교체 때마다 1부터 다시 매김)와 `lecture.version` 낙관적 락으로 보장한다 (D12). DB 제약이 필요해지면 지연 검사 제약 `EXCLUDE USING btree (lecture_id WITH =, sequence WITH =) WHERE (deleted_at IS NULL) DEFERRABLE INITIALLY DEFERRED`를 쓴다. 콘텐츠 교체 때 Hibernate가 새 행 INSERT를 기존 행 Soft Delete UPDATE보다 먼저 실행하므로, 즉시 검사하는 부분 유일 인덱스는 교체를 항상 실패시킨다.

**file_resource**: `id`, `uploader_user_id`(회원 ID), `file_type` = `LECTURE_THUMBNAIL` / `LECTURE_VIDEO` / `LECTURE_DOCUMENT`, `object_key`(유일, 모듈 기준 key), `original_filename`, `content_type`, `file_size`(바이트), `status` = `PENDING` / `UPLOADED`, `uploaded_at`, `created_at`, `updated_at`, `deleted_at` (#89).

**cart**: ERD와 같음. 유일 `uc_cart_user_id (user_id)`.

**cart_item**: ERD + `deleted_at` **추가**(정책 7). 부분 유일 인덱스 `uk_cart_item_cart_lecture (cart_id, lecture_id) WHERE deleted_at IS NULL`. `lecture_id`는 다른 애그리거트라 FK를 두지 않는다. 같은 트랜잭션에서 같은 강의를 빼고 다시 담으면 Hibernate가 INSERT를 UPDATE보다 먼저 실행해 이 인덱스에 걸린다(7.1 `lecture_content`와 같은 문제). 지금 API는 요청마다 한 동작이라 해당 없고, 주문 후 빼기를 넣을 때 다시 확인한다.

**lecture_order**

| 컬럼 | 타입 | 비고 |
| --- | --- | --- |
| id | bigint PK | |
| buyer_user_id | bigint | |
| total_amount_hp | bigint | 항목 가격 합 |
| status | varchar | `HOLD_PENDING` / `HOLD_COMPENSATING` / `HOLD_FAILED` / `PAID` / `CANCELED` (4.6, D58) |
| ordered_at | timestamp | 주문 시도 시각 (주문·HOLD 작업을 저장한 시각). 구매 완료 시각과 다르다 (D58) |
| paid_at | timestamp | **추가** (#276). 모든 보류가 성공해 `PAID`가 된 시각. 그 전에는 null |
| canceled_at | timestamp | 모든 항목이 `CANCELED`·`RETURNED`가 된 시각 (4.6 파생 상태 `CANCELED`) |

**order_item**

| 컬럼 | 타입 | 비고 |
| --- | --- | --- |
| id | bigint PK | |
| order_id | bigint FK | `lecture_order.id` (같은 스키마) |
| lecture_id | bigint | |
| seller_user_id | bigint | 주문 시점 판매자 |
| buyer_user_id | bigint | **추가**. 보유 여부 유일 인덱스용 비정규화 |
| lecture_title_snapshot | varchar | |
| price_hp | bigint | 주문 시점 가격 |
| status | varchar | `HOLD_PENDING` / `HOLD_COMPENSATING` / `HOLD_FAILED` / `PURCHASED` / `CONFIRMED` / `CANCEL_PENDING` / `CANCELED` / `RETURN_PENDING` / `RETURNED` / `PAYOUT_PENDING` / `PAID_OUT` (4.6, #275~#277) |
| purchased_at | timestamp | **추가**. 구매일 기준. 보류 완료 전에는 null (`auto_confirm_at`·`payout_scheduled_at`도 같음, D58) |
| confirmed_at | timestamp | |
| confirmation_type | varchar | **추가**. `DIRECT` / `FIRST_PLAY` / `AUTO` |
| auto_confirm_at | timestamp | 5장 계산식 |
| payout_scheduled_at | timestamp | 5장 계산식 |
| paid_out_at | timestamp | **추가**. 지급 시각 |
| canceled_at | timestamp | 구매 취소 시각 |
| returned_at | timestamp | ERD의 `refunded_at`을 **이름 변경**. "환불"은 충전 HP 현금화만 가리키므로 "반환"을 쓴다 |
| objection_status | varchar | `NONE` / `FILED` / `ACCEPTED` / `REJECTED` |
| objection_reason | text | **추가** |
| objection_filed_at | timestamp | |
| objection_inquiry_id | bigint | **추가** (#156). 접수한 고객센터 문의 ID. 판단할 때 대조한다. 다른 모듈 ID라 FK 없음 |
| objection_resolved_at | timestamp | **추가** |
| objection_resolved_by_user_id | bigint | **추가** (#156). 판단한 관리자 ID |
| payout_failure_count | int | **deprecated** (#218, D54 → D59). 지급 실패 횟수. #277 이관 뒤에는 쓰지 않고 이력으로만 남는다 |
| last_payout_failure_code | varchar(100) | **deprecated** (#218, D54 → D59). 마지막 지급 실패 코드 |
| last_payout_failed_at | timestamp | **deprecated** (#218, D54 → D59). 마지막 지급 실패 시각 |
| payout_quarantined_at | timestamp | **deprecated** (#218, D54 → D59). 지급 격리 시각. 격리는 PAYOUT 작업 `DEAD`로 바뀐다. 물리 제거는 지급 실패 레거시 정리 티켓(#268)에서 별도 합의 후 |
| version | bigint | **추가**. 낙관적 락 (10장) |

인덱스

- 유일 `uk_order_item_buyer_lecture_owned (buyer_user_id, lecture_id) WHERE status IN ('HOLD_PENDING','HOLD_COMPENSATING','PURCHASED','CONFIRMED','CANCEL_PENDING','RETURN_PENDING','PAYOUT_PENDING','PAID_OUT')` — 같은 강의 중복 보유 방지 (D5). HP가 남아 있을 수 있는 처리 중 상태를 포함하고 `HOLD_FAILED`·`CANCELED`·`RETURNED`는 뺀다(D58, D59). 지금은 `PURCHASED`·`CONFIRMED`·`PAID_OUT`만이고, 상태를 더하는 티켓(#275~#277)마다 인덱스를 다시 만든다
- `(status, auto_confirm_at)`, `(status, payout_scheduled_at)` — 배치 조회용
- `(seller_user_id, purchased_at desc, id desc)` — 판매 내역 조회용 (D51)

**lecture_review**: ERD와 같음. `rating` 1~5 CHECK. 유일 `(order_item_id) WHERE deleted_at IS NULL`, 유일 `(lecture_id, reviewer_user_id) WHERE deleted_at IS NULL` — 삭제 후 재작성 허용.

**lecture_user** (**신규**, 회원 복제): `id`(회원 ID), `nickname`, `role`, `status`, `created_at`, `updated_at`. 채팅 모듈의 `CHAT_USER`와 같은 패턴.

**lecture_hp_task** (**신규**, #275, D57): HP 작업. 강의의 보류·반환·지급 호출 한 건. 삭제하지 않는다.

| 컬럼 | 타입 | 비고 |
| --- | --- | --- |
| id | bigint PK | |
| task_type | varchar | `HOLD` / `RELEASE` / `PAYOUT` |
| purpose | varchar | `ORDER_HOLD` / `HOLD_COMPENSATION` / `CANCEL` / `RETURN` / `PAYOUT` |
| request_id | varchar | 유일. billing 요청 ID와 같다 (6.2 멱등 키) |
| order_id, order_item_id | bigint | 대상 주문·항목 |
| payer_user_id, receiver_user_id | bigint | 구매자, 지급받는 판매자(지급만) |
| amount_hp | bigint | 항목 가격 |
| payload_fingerprint | varchar | 요청 값의 해시. 같은 `request_id`인데 다르면 충돌 |
| status | varchar | `PENDING` / `PROCESSING` / `COMPLETED` / `DEAD` |
| outcome | varchar | `COMPLETED`일 때: `SUCCEEDED` / `REJECTED`(HOLD의 확정 거절) / `SKIPPED`(실행 전에 필요 없어진 HOLD) |
| attempt_count | int | 현재 자동 재시도 주기의 선점 횟수 |
| total_attempt_count | int | 관리자 재시도를 포함한 누적 선점 횟수 |
| manual_retry_count | int | `DEAD`에서 관리자가 복구한 횟수 |
| next_attempt_at | timestamp | 다음 선점 가능 시각 |
| claim_token | uuid | 현재 선점 표식. 결과는 이 값이 같을 때만 기록 |
| lease_until | timestamp | 선점 만료 시각 |
| last_error_code | varchar | 마지막 실패 코드 (`BillingFailureCode` 또는 실행기 결과) |
| last_failed_at | timestamp | |
| last_retried_by_user_id, last_retried_at | bigint, timestamp | 마지막 관리자 재시도 |
| completed_at | timestamp | |
| created_at, updated_at | timestamp | |

인덱스: `(status, next_attempt_at)`, `(status, lease_until)`, `(order_item_id)`, `(order_id)`.

**lecture_hp_task_attempt** (**신규**, #275, D57): 선점 한 번 = 한 행. 선점할 때 만들고 실행 결과를 한 번만 기록한 뒤 다시 바꾸지 않는다. 삭제하지 않는다. 작업의 현재 상태와 시도 이력을 나눈다.

| 컬럼 | 타입 | 비고 |
| --- | --- | --- |
| id | bigint PK | |
| task_id | bigint FK | `lecture_hp_task.id` (같은 스키마) |
| attempt_no | int | 작업의 `total_attempt_count` 값 |
| claim_token | uuid | |
| result | varchar | 실행 중이면 null. `SUCCEEDED` / `TERMINAL_REJECTION` / `RETRYABLE` / `UNKNOWN_RESULT` / `INVARIANT_VIOLATION` / `LEASE_EXPIRED` |
| error_code | varchar | `BillingFailureCode` |
| raw_error_code | varchar | 변환 전 원래 코드(진단용, 관리자만) |
| error_detail | varchar(1000) | 예외 종류·메시지 (진단용, 관리자만) |
| started_at, completed_at | timestamp | |

**objection_command** (**신규**, #278, D60): 이의 제기 명령 처리 이력(inbox). 삭제하지 않는다. 항목이 다시 `NONE`이 되거나 새 문의가 생겨도 과거 결과를 재현한다.

| 컬럼 | 타입 | 비고 |
| --- | --- | --- |
| id | bigint PK | |
| command_id | varchar | 유일. `FILE:{inquiryId}` / `RESOLVE:{inquiryId}` / `WITHDRAW:{inquiryId}` |
| command_type | varchar | `FILE` / `RESOLVE` / `WITHDRAW` |
| inquiry_id, order_item_id | bigint | 고객센터 문의 ID(FK 없음), 대상 항목 |
| correlation_id | varchar | 접수부터 반환까지 한 흐름 |
| last_event_id | varchar | 마지막으로 받은 명령 이벤트 ID (판단·철회) |
| payload_fingerprint | varchar | 같은 `command_id`인데 다르면 `IDEMPOTENCY_KEY_CONFLICT` |
| result | varchar | `SUCCEEDED` / `REJECTED` (최종 업무 거절만 저장) |
| failure_code | varchar | `LectureObjectionFailureCode` |
| processed_at | timestamp | |

- 재시도하면 성공할 수 있는 실패(순서 지연, 낙관적 락 충돌)와 기술 실패(DB·타임아웃)는 결과로 저장하지 않는다 (#278).

### 7.2 ERD 반영 필요 목록

`changelog.md`의 "보류 (ERD 수정 필요)" 중 "강의 검수 상태 반영 필요" 항목을 이 설계로 해소한다.

- [ ] `LECTURE`: 검수 컬럼 4개, `version` 추가, `status` 값 정의
- [ ] `LECTURE_CONTENT`: `deleted_at` 추가
- [ ] `CART_ITEM`: `deleted_at` 추가 (정책 7)
- [ ] `LECTURE_ORDER`: `status` 값 정의(`PAID`/`CANCELED`), `canceled_at` 의미 정의
- [ ] `ORDER_ITEM`: `buyer_user_id`, `purchased_at`, `confirmation_type`, `paid_out_at`, `objection_reason`, `objection_resolved_at`, `version` 추가, `refunded_at` → `returned_at`, `status` 값 정의
- [ ] `LECTURE_USER` 추가
- [ ] `LECTURE_ORDER`·`ORDER_ITEM` 처리 중 상태와 `paid_at`, `LECTURE_HP_TASK`·`LECTURE_HP_TASK_ATTEMPT`·`OBJECTION_COMMAND` 추가 (#275~#278, D57~D60)

---

## 8. API 초안

모든 경로는 `/api/v1/lectures` 아래. 성공 응답은 `RsData{statusCode, message, data}`(본문 없는 응답은 204), 오류는 공통 핸들러의 ProblemDetail이다(D29, AGENTS 7장). Swagger로 Controller 명세를 먼저 만들어 프론트와 맞춘다.

### 8.1 강의

| 메서드 | 경로 | 권한 | 설명 |
| --- | --- | --- | --- |
| GET | `/api/v1/lectures` | 모두(비회원 포함) | `ON_SALE` 목록. 검색어(제목), 정렬(최신·가격·평점), 페이징. 판매자 닉네임·평균 평점·리뷰 수 포함 |
| GET | `/api/v1/lectures/{lectureId}` | 회원 | 상세 + 콘텐츠 목록(제목·순서·종류, 파일 URL 없음). 판매자 본인은 비공개·삭제 상태도 조회하고, 수정에 다시 쓸 `thumbnailFileId`·`contents[].fileId`를 받는다(그 밖의 회원은 `null`, D53). 관리자는 8.2의 `/admin/{lectureId}`로 조회한다(D30). 보유 항목이 있는 구매자는 삭제된 강의도 조회한다(D38) |
| POST | `/api/v1/lectures` | 인증된 인플루언서 | 등록 (메타 + 콘텐츠 목록) → `PENDING_INSPECTION` |
| PATCH | `/api/v1/lectures/{lectureId}` | 판매자 본인 | 메타 수정 (재검수 없음). 썸네일을 유지하려면 상세의 `thumbnailFileId`를 다시 보낸다(`null`은 삭제, D53) |
| DELETE | `/api/v1/lectures/{lectureId}` | 판매자 본인 | Soft Delete |
| PUT | `/api/v1/lectures/{lectureId}/contents` | 판매자 본인 | 콘텐츠 목록 전체 교체 (추가·수정·삭제·순서 변경 포함). `REJECTED`일 때만 허용 (D11). 바꾸지 않는 강은 상세의 `fileId`를 다시 보낸다 (D21, D53) |
| POST | `/api/v1/lectures/{lectureId}/inspection-requests` | 판매자 본인 | `REJECTED` → `PENDING_INSPECTION` |
| GET | `/api/v1/lectures/me` | 인증된 인플루언서 | 내가 등록한 강의 (모든 상태) |
| GET | `/api/v1/lectures/{lectureId}/contents/{contentId}/access` | 구매자·판매자·관리자 | Presigned GET URL 발급 `{url, expiresAt}`, 만료 1시간(D23). 자료는 원본 파일명으로 내려받는다(D27). 구매자 항목이 `PURCHASED`면 영상·자료 모두 403 `PLAY_CONSENT_REQUIRED`(재생 동의 필요). 구매자는 현재 콘텐츠만 받는다 (#91·D26, 구매자 #158·D38) |

- 시청 URL(#91·D26, #158·D38): 없는 강의 `LECTURE_NOT_FOUND`(404) → 판매자·관리자는 통과 → 보유 항목이 `PURCHASED`면 `PLAY_CONSENT_REQUIRED`(403, `orderId`·`orderItemId` 포함), `CONFIRMED`·`PAYOUT_PENDING`·`PAID_OUT`이면 통과(삭제된 강의 포함, D59). 결제·반환 처리 중 항목은 시청 항목이 아니다 → 그 밖의 회원에게 판매 중이 아닌 강의는 `LECTURE_NOT_FOUND`(404), 판매 중인 강의는 `CONTENT_ACCESS_DENIED`(403) → 이 강의에 없거나 교체된 콘텐츠 `LECTURE_CONTENT_NOT_FOUND`(404). 이 순서로 검사한다.

### 8.2 검수 (관리자)

| 메서드 | 경로 | 설명 |
| --- | --- | --- |
| GET | `/api/v1/lectures/admin/inspections?status=PENDING_INSPECTION` | 검수 대기 목록 |
| GET | `/api/v1/lectures/admin/{lectureId}` | 강의 상세 + 콘텐츠 목록. 상태·삭제 여부와 관계없이 조회하고 반려 사유와 파일 ID를 포함한다. 응답은 8.1 상세와 같다 (#103, D30, D53) |
| POST | `/api/v1/lectures/admin/{lectureId}/inspection/approve` | 승인 → `ON_SALE`. 검수 대기 중에는 내용이 바뀌지 않으므로 추가 검사 없음 (D11) |
| POST | `/api/v1/lectures/admin/{lectureId}/inspection/reject` | 반려 `{reason}` → `REJECTED` |

### 8.2.1 HP 작업 (관리자, D57, #275)

| 메서드 | 경로 | 설명 |
| --- | --- | --- |
| GET | `/api/v1/lectures/admin/hp-tasks` | HP 작업 목록(페이지). 필터: `status`, `taskType`, `referenceType`, `orderId`·`orderItemId`, `errorCode`. 응답: 작업 상태·결과, 유형·목적, 요청 ID, 주문·항목 ID, 금액, 시도 횟수(`attemptCount`·`totalAttemptCount`·`manualRetryCount`), 다음 시도 시각, 마지막 오류 코드·시각, 마지막 관리자 재시도 |
| GET | `/api/v1/lectures/admin/hp-tasks/{taskId}` | 작업 상세 + 시도 이력(`lecture_hp_task_attempt`, 원래 오류 코드·진단 정보 포함) |
| POST | `/api/v1/lectures/admin/hp-tasks/{taskId}/retry` | `DEAD` → `PENDING`(204). `attemptCount`만 0으로 하고 누적 횟수는 유지, `manualRetryCount` +1, 관리자·시각 기록. 같은 요청 ID로 다시 보내므로 HP가 두 번 움직이지 않는다. 없는 작업 404, `DEAD`가 아니면 409 |

- 원인은 billing 데이터에서 먼저 확인한다. 예: `PAYOUT_ALREADY_SETTLED`는 지급 예정 건(`billing.settlement`)이 남은 경우, `HOLD_ALREADY_CLOSED`는 보류가 이미 닫힌 경우라 강의 항목 상태와 billing 보류를 대조해 어느 쪽이 맞는지 판단한다 (D54의 운영 절차를 그대로 옮김).

### 8.2.2 지급 실패 (관리자, D54 → D59 호환)

| 메서드 | 경로 | 설명 |
| --- | --- | --- |
| GET | `/api/v1/lectures/admin/payout-failures` | #277부터 HP 작업 조회(`taskType = PAYOUT`)에 위임한다. 응답 형태는 프론트 전환 기간 동안 유지한다 |
| POST | `/api/v1/lectures/admin/payout-failures/{orderItemId}/retry` | #277부터 그 항목 PAYOUT 작업의 관리자 재시도에 위임한다 |

- 프론트가 8.2.1로 옮긴 뒤 지급 실패 레거시 정리 티켓(#268)에서 이 경로를 없앤다. 기존 데이터의 이관 건수 검증과 일정 기간의 대조가 먼저다 (D59).

### 8.3 파일

| 메서드 | 경로 | 권한 | 설명 |
| --- | --- | --- | --- |
| POST | `/api/v1/lectures/files` | 인증된 인플루언서 | `{fileType, originalFilename, contentType, fileSize}` → `{fileId}` (D22) |
| PUT | `/api/v1/lectures/files/{fileId}/content` | 업로더 본인 | 요청 본문이 파일(multipart·form 아님). S3에 저장하고 `UPLOADED` → 204 (D24) |

- 등록: 허용하지 않는 형식 `UNSUPPORTED_FILE_CONTENT_TYPE`(400), 최대 크기 초과 `FILE_TOO_LARGE`(400).
- 본문 업로드: 없는 파일 `FILE_NOT_FOUND`(404), 업로더가 아님 `NOT_FILE_UPLOADER`(403), 이미 올림 `FILE_ALREADY_UPLOADED`(409), `Content-Length` 없음 `FILE_LENGTH_REQUIRED`(411), 크기가 등록과 다름 `FILE_SIZE_MISMATCH`(400), 형식이 등록과 다름 `FILE_CONTENT_TYPE_MISMATCH`(400). 이 순서로 검사한다 (D24).
- 업로드 경로는 nginx가 PUT만 받고 form·multipart 본문은 415로 끊는다(`docs/deploy/s3.md`).
- 강의 등록·정보 수정·콘텐츠 교체에 맞지 않는 파일을 보내면 `INVALID_LECTURE_FILE`(400) (D21).

### 8.4 장바구니

| 메서드 | 경로 | 설명 |
| --- | --- | --- |
| GET | `/api/v1/lectures/cart` | 장바구니 조회 (구매 가능 여부 표시) |
| POST | `/api/v1/lectures/cart/items` | `{lectureId}` 담기 |
| DELETE | `/api/v1/lectures/cart/items/{lectureId}` | 빼기 |

### 8.5 주문

| 메서드 | 경로 | 설명 |
| --- | --- | --- |
| POST | `/api/v1/lectures/orders` | `{lectureIds}` 주문 (바로 구매는 1개). 응답의 `items`는 강의 ID 오름차순 (D55). 응답 상태는 아래 표 (D58) |
| GET | `/api/v1/lectures/orders` | 내 주문 목록 |
| GET | `/api/v1/lectures/orders/{orderId}` | 주문 상세 (항목 상태 + 4.6 파생 상태) |
| POST | `/api/v1/lectures/orders/{orderId}/items/{orderItemId}/confirm` | 직접 구매 확정 |
| POST | `/api/v1/lectures/orders/{orderId}/items/{orderItemId}/play-consent` | 재생 동의 → 구매 확정(`FIRST_PLAY`). 이후 영상·자료 URL 발급 가능. 이미 확정·지급이면 그대로 200 (D38) |
| POST | `/api/v1/lectures/orders/{orderId}/items/{orderItemId}/cancel` | 구매 취소. 반환이 끝나면 200 + `CANCELED`, 끝나지 않으면 202 + `CANCEL_PENDING` 항목 (D59) |
| GET | `/api/v1/lectures/purchased` | 내가 시청 가능한 강의 목록. `PURCHASED`·`CONFIRMED`·`PAYOUT_PENDING`·`PAID_OUT` 항목 기준 최근 구매순, 삭제된 강의 포함 (D39, D59) |
| GET | `/api/v1/lectures/sales` | 인증된 인플루언서: 판매 내역. 본인 강의가 팔린 주문 항목을 최근 구매순으로 (항목 상태·지급 예정 시각·지급 시각, D51). 보류가 끝나지 않았거나 실패한 항목(`HOLD_*`)은 팔린 것이 아니라 뺀다 (D58) |

주문 생성 응답 (D58). 상태가 달라지므로 컨트롤러는 `ResponseEntity<RsData<OrderResponse>>`로 같은 상태를 본문과 HTTP 응답에 쓴다.

| 결과 | 응답 |
| --- | --- |
| 모든 보류 성공 | 201 + `PAID` 주문 |
| billing 확정 거절 + 성공했던 보류의 반환까지 완료 (`HOLD_FAILED`) | 409 `INSUFFICIENT_BALANCE`. 보류의 확정 거절은 잔액 부족뿐이고, 강의 오류 코드가 billing과 같은 `code` 문자열을 써서 프론트 분기가 바뀌지 않는다 (D69) |
| 보류 결과 불명, 재시도 대상, 보상 반환 진행 중, 또는 요청 시간 예산(10초, D68)을 넘김 | 202 + `HOLD_PENDING`·`HOLD_COMPENSATING` 주문. `RsData.data`에 `orderId`·`status`를 담는다 |

```json
{ "statusCode": 202, "message": "주문 결제를 처리 중입니다.", "data": { "orderId": 123, "status": "HOLD_PENDING", "items": [ ... ] } }
```

- 202를 받은 프론트는 POST를 다시 보내지 않고 주문 상세를 조회한다. 같은 강의의 재주문은 부분 유일 인덱스가 409 `ALREADY_OWNED_LECTURE`로 막는다.
- 주문 목록·상세는 처리 중·실패 주문도 보여 준다. 처리 중 주문·항목에는 `processingStatus`(`PROCESSING`·`DELAYED`·`ACTION_REQUIRED`)를 담는다. 결제 단계는 주문 단위 값 하나를 항목에도 같이 싣는다 (4.6, D69).
- 주문 상세의 `orderedAt`은 주문을 시도한 시각, `paidAt`은 구매 완료를 기록한 시각이다. 결제 처리 중에는 `paidAt`·항목의 `purchasedAt`·`autoConfirmAt`·`payoutScheduledAt`이 `null`이다 (D58 (5)).

### 8.6 리뷰

| 메서드 | 경로 | 설명 |
| --- | --- | --- |
| GET | `/api/v1/lectures/{lectureId}/reviews` | 리뷰 목록 (최신순, 페이징). 평균 평점·리뷰 수는 강의 목록·상세에서 준다 |
| POST | `/api/v1/lectures/{lectureId}/reviews` | `{orderItemId, rating, content}` 작성 (201, `{id}`) |
| PATCH | `/api/v1/lectures/reviews/{reviewId}` | 작성자 본인 수정 (204) |
| DELETE | `/api/v1/lectures/reviews/{reviewId}` | 작성자 본인 Soft Delete (204) |

오류 코드는 D47을 따른다.

> 비회원 목록 조회(`GET /api/v1/lectures`)는 global `SecurityConfig`가 허용한다(#58). 그 밖의 강의 API는 로그인이 필요하고, 역할 인가는 Facade의 `@PreAuthorize`로 한다(D16, 9장).

### 8.6.1 판매 수익 내부 API (D63, #300)

billing #295가 회원의 받을 지급을 모아 보여 줄 때 호출한다. 회원 세션이 아니라 내부 API 키(`X-Internal-Api-Key`)로 인증하고, 서버 밖에서는 nginx가 404로 막는다. 계약 전체(상태 대응, 필드, 오류, Client)는 `docs/api/internal-earnings.md`.

| 메서드 | 경로 | 설명 |
| --- | --- | --- |
| GET | `/api/v1/internal/lectures/earnings/summary?userId=` | 판매자의 아직 지급하지 않은 수익을 상태별 `{amountHp, count}`로 합한다(`confirmationPending`·`scheduled`·`onHold`·`processing`·`failed`) + `asOf` |
| GET | `/api/v1/internal/lectures/earnings?userId=&status=` | 주문 항목 단위 수익 목록. `status` 생략 시 미지급 5개 상태. `statusUpdatedAt desc, sourceId desc` 고정 |

- 다른 모듈은 `shared.lecture.out.LectureApiClient`로 부른다(D62). `apps`에 Adapter를 두지 않는다.

### 8.7 용어 ↔ 코드 이름 (제안)

`glossary.md`의 "코드 레벨" 열이 비어 있어 강의 영역만 제안한다. 검수와 리뷰가 영어로 둘 다 review가 되지 않도록 **검수 = inspection, 리뷰 = review**로 나눈다.

| 용어 | 코드 |
| --- | --- |
| 강의 / 강의 콘텐츠 | `Lecture` / `LectureContent` |
| 검수, 검수 대기, 반려 | `Inspection`, `PENDING_INSPECTION`, `REJECTED` |
| 판매 중 | `ON_SALE` |
| 장바구니 / 주문 / 주문 항목 | `Cart` / `LectureOrder` / `OrderItem` |
| 구매 확정 / 구매 취소 | `confirm` (`CONFIRMED`) / `cancel` (`CANCELED`) |
| 첫 재생 | `FIRST_PLAY` |
| 내용 다름 이의 제기 | `Objection` |
| 반환 / 지급 | `return` (`RETURNED`) / `payout` (`PAID_OUT`) |
| 결제 처리 중 / 결제 취소 처리 중 / 결제 실패 | `HOLD_PENDING` / `HOLD_COMPENSATING` / `HOLD_FAILED` |
| 구매 취소 처리 중 / 반환 처리 중 / 지급 처리 중 | `CANCEL_PENDING` / `RETURN_PENDING` / `PAYOUT_PENDING` |
| HP 작업 (보류·반환·지급 호출 한 건) | `LectureHpTask` (`HOLD`·`RELEASE`·`PAYOUT`) |
| 이의 제기 인정 뒤 HP 반환 상태 (고객센터) | `hpReturnStatus` ("환불"이 아니다, 4장 용어) |
| 리뷰 / 평점 | `LectureReview` / `rating` |

---

## 9. 권한 정리

| 기능 | 비회원 | 유저 | 인플루언서 | 인증된 인플루언서 | 관리자 |
| --- | --- | --- | --- | --- | --- |
| 강의 목록 | O | O | O | O | O |
| 강의 상세 | X | O | O | O | O |
| 강의 등록·수정·삭제·재검수 신청 | X | X | X | O (본인) | X |
| 검수 승인·반려 | X | X | X | X | O |
| HP 작업 조회·재시도, 지급 실패 조회·재시도 | X | X | X | X | O |
| 장바구니·주문·구매 확정·구매 취소·이의 제기 | X | O | O | O | X |
| 판매 내역 | X | X | X | O (본인 판매분) | X |
| 판매 수익 내부 조회 (D63) | X | X | X | X | X |
| 콘텐츠 시청 | X | 구매자 | 구매자 | 구매자·판매자 | O (검수용) |
| 리뷰 작성·수정·삭제 | X | 구매자 | 구매자 | 구매자 | X |
| 리뷰 목록 | X | O | O | O | O |

- 판매 수익 내부 조회는 회원이 아니라 내부 API 키로 인증한 모듈(billing)만 부른다. `/api/v1/internal/**` 전용 보안 체인과 `LectureEarningInternalFacade`의 `@PreAuthorize("hasRole('INTERNAL')")`가 막는다 (D63).
- 역할 검사는 Facade(`LectureFacade`, `LectureInspectionFacade`, `LectureOrderFacade`, `LecturePayoutAdminFacade`)의 `@PreAuthorize`로 한다. 관리자 API(`/admin` 경로)는 `SecurityConfig`도 함께 막는다 (D16).
- 관리자의 강의 상세는 `/admin/{lectureId}`(`LectureInspectionFacade`)로 따로 두고, 일반 상세 API에는 역할 분기를 두지 않는다 (D30).
- 콘텐츠 시청은 "판매자 본인 또는 관리자 또는 구매자"라는 OR 조건이라 `@PreAuthorize`를 두지 않고 `LectureContentAccessSupport`가 판정한다. 관리자 여부는 회원 복제본(`LectureUser`)으로, 구매자 여부는 시청 가능 항목으로 본다 (D26, D38, D69).

---

## 11. 패키지 구조 (D11 — 채팅 모듈과 같은 구조)

계층별로 나눈다. Spring Modulith 기준으로 `com.hapbang.lecture` 하위 패키지는 모두 모듈 내부다.

```
com.hapbang.lecture
├── package-info.java   (@ApplicationModule)
├── in/                 Controller, Swagger 명세 인터페이스(`ApiV1Lecture*Spec`, D15), 회원·이의 제기 명령 이벤트 리스너, 요청 DTO (구현 전 스텁의 응답 DTO 포함)
├── app/                Facade(진입점·역할 검사), UseCase(쓰기 동작 하나당 하나), Support(공통 조회), 응답 DTO(트랜잭션 안에서 변환), 배치, HP 작업 실행기, 내부 HTTP가 호출하는 이의 제기 접수 진입점(D60)
├── domain/             엔티티·상태, LectureErrorCode(BaseErrorCode 구현)·LectureException(BusinessException 상속) (package-info.java: 테이블·제약 목록, TODO(db))
├── out/                Repository. 다른 bounded context용 Java Port는 두지 않는다(D62)
└── config/             LectureConfig (강의 Clock `lectureClock`, D37), HP 작업 설정(`lecture.hp-task.*`, D57)

com.hapbang.shared.lecture                         dto(이의 제기 접수 명령·결과), domain(오류 코드), event(V1), out(LectureApiClient) (D62)
com.hapbang.shared.billing                         dto(HP 명령·결과), domain(참조·오류 코드), event, out(BillingApiClient·호출 결과/예외) (D62)
com.hapbang.apps                                   bootstrap, scheduling, security·HTTP 공통 설정만 담당 (D62)
```

- 지금은 `in/LectureObjectionPortAdapter`(shared `LectureObjectionPort` 구현, #156)가 있다. #280에서 지운다 (6.1 전환 상태).
- `app`은 회원 모듈(`MemberFacade`)과 같은 Facade/UseCase/Support 구조다. 컨트롤러·이벤트 리스너는 Facade만 호출하고, `@Transactional`은 Facade 메서드에 건다 (D16). 예외: 파일 본문 업로드(D25)와 billing을 부르는 흐름(주문 생성·구매 취소·HP 작업 실행, D57)은 트랜잭션 없는 Facade가 짧은 트랜잭션들을 나눠 부르고, billing 호출은 그 사이 트랜잭션 밖에서 한다.
- 예외 응답은 global `GlobalExceptionHandler`가 `ProblemDetail` + `code`로 만든다(#71). 강의 모듈은 예외 처리기를 두지 않고 `new LectureException(LectureErrorCode.X)`로 던진다.
- S3는 global `S3FileStorage`를 `app`에서 쓴다. 강의 모듈에 S3 클라이언트를 두지 않는다 (D40).

---

## 12. 테스트 (제안 — 팀 테스트 방식 합의 전)

| 대상 | 방식 |
| --- | --- |
| 상태 전이·불변식 (`Lecture`, `OrderItem`), 5장 시간 계산 | 순수 단위 테스트, `Clock` 고정 |
| Repository, 부분 유일 인덱스 | `@RepositoryTest` (PostgreSQL Testcontainers) |
| 주문·취소·지급의 상태 전이와 HP 작업 기록, `Succeeded`·`Failed`·`BillingCallException` 결과별 처리 | `@IntegrationTest` + `BillingApiClient` 테스트 대역 또는 HTTP stub |
| HP 작업 선점·lease·`claimToken`·재시도·`DEAD`, 겹친 실행기, 결과 기록 실패 뒤 재실행 | `@IntegrationTest` (`Clock` 고정, 두 실행기를 동시에) |
| **트랜잭션 밖 호출** — Client 호출 구간에는 강의 트랜잭션이 없고, HTTP 요청은 billing 로컬 트랜잭션으로 실행되며, 결과 기록 구간에만 새 강의 트랜잭션이 있는지 | 전체 앱 컨텍스트 + 실제 self-HTTP 통합 테스트. 성공 뒤 결과 기록 실패, timeout과 같은 멱등 키 재호출도 확인한다 |
| 이의 제기 접수(동기)·판단·철회(이벤트), 명령 이력 멱등, 접수 ↔ 지급 대상 선점 경쟁 | `@IntegrationTest` (이벤트는 `TransactionTemplate` 안에서 발행하고 `await()`) |
| 모듈 경계 | 기존 `ModularityTests`. #280에서 shared `..port..`, 다른 모듈 Application·Repository·엔티티·예외 의존과 `apps`의 모듈 연결 구현을 막는 구조 테스트를 더한다. 트랜잭션 밖 호출은 위 통합 테스트로 확인한다 |

> **스키마 생성**: 모든 환경과 테스트가 Flyway 마이그레이션으로 스키마를 만들고 Hibernate는 `validate`만 한다 (D13, `docs/db-migration.md`). 강의 마이그레이션은 `boundedContext/lecture/src/main/resources/db/migration/`에 둔다.

---

## 13. 구현 순서 (개발 기간 10/8 ~ 10/21)

MVP 완료 기준인 "충전 → 강의 구매 → 구매 확정 → 지급 → 환전"을 먼저 관통시킨다.

| 단계 | 내용 | 의존 | 티켓 (✅ 병합) |
| --- | --- | --- | --- |
| 1 | `LectureHpPort` 합의(6.2, Q15 → D36: `shared` billing 연동 계약 #145), `LectureObjectionPort` 합의(고객센터), 회원 이벤트 합의(Q9, Q17), `shared`에 인터페이스·이벤트 record 추가, Swagger Controller 명세 (**명세 완료**: 8장 전체 스텁, 본문 501, D14) | 지갑·고객센터·회원 담당 | 명세 #48 ✅ #74 ✅ #94 ✅ |
| 2 | `Lecture`·`LectureContent` 등록·조회·검수, 반려 후 콘텐츠 수정 (파일은 ID만, S3 연동 전) | - | #55 ✅ #73 ✅, 관리자 상세 #103 ✅ |
| 3 | 장바구니, 주문 생성(`BillingPort` 항목별 보류, 테스트는 Fake), 직접 구매 확정, 구매 취소 | 지갑의 보류 구현·반환 (#145 ✅) | 장바구니 #92 ✅, 주문 조회·직접 확정 #146 1차(#152) ✅, 주문 생성·구매 취소 #146 2차 ✅ |
| 4 | 자동 구매 확정·지급 배치 → **강의 쪽 돈 한 바퀴 완료** | 지갑의 지급 (#145 ✅) | 스케줄링 #157 ✅, 자동 구매 확정 #149 ✅, 지급·판매 내역 #150 ✅, 지급 실패 격리·다건 주문 순서 #218 |
| 5 | S3 업로드·재생 URL, 재생 동의 구매 확정 | 인프라(S3 버킷 권한) | S3 #88 ✅ #89 ✅ #90 ✅ #91 ✅, 서버 경유 업로드 #106 ✅ #107 ✅, 내려받기 파일명 #125 ✅, 재생 동의·구매자 시청·`/purchased` #158 ✅ |
| 6 | 리뷰·평점, `LectureUser` 복제 | 회원 이벤트 | 회원 복제 #46 ✅ #87 ✅, 리뷰·평점 #155 ✅ |
| 7 | 내용 다름 이의 제기 (`LectureObjectionPort` 구현) | 고객센터 호출부, `INQUIRY` 대상 컬럼 | 강의 구현 #156 ✅, 고객센터 호출부 #189 ✅, 철회 #203 |
| 8 | 내부 HTTP 경계와 로컬 트랜잭션 기반으로 재설계 (D62, 6.1 전환 상태) | 고객센터·billing·출연 담당 | 상위 #271, HTTP 기반 #272 → #273 → #274·#275, 강의 HP #275 → #276 → #277, 이의 제기 #278 → #279, 이벤트 재발행 #266, 실패 명령 복구 #267, 지급 실패 레거시 정리 #268, 마무리 #280 |
| 9 | billing 연동용 판매 수익 내부 조회 API, 내부 API 키 인증·nginx 차단, shared `LectureApiClient` (D62 구조 먼저 적용) | billing #295, 출연 #301 | #300 |
| 공통 | 단계와 무관한 팀 공통 적용 | - | 공통 예외 #71 ✅, 관리자 권한·페이지 응답 #58 ✅, 성공 응답 RsData #126 ✅, enum 설명 #112 ✅ |

---

