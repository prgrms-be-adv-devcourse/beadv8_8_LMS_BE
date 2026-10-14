# 출연(Appearance) 모듈: 정책과 구조

출연 모듈이 지키는 정책, 모듈 구조, 다른 모듈과의 연동, DB 스키마를 정리한 문서다.
현재 구현된 범위(#26, #31, #50, #98)를 기준으로 하며, 기능이 추가될 때마다 함께 갱신한다.

- 오류 코드: [error-codes.md](error-codes.md)
- 테스트 명세: [test-spec.md](test-spec.md)
- 팀 공통 규칙: [AGENTS.md](../../AGENTS.md)

---

## 1. 범위

### 1.1 출연 모듈이 하는 일

- 출연 요청(1:1), 출연 모집(1:N)과 지원, 출연 성립, 출연 취소 요청, 출연 확인
- 호스트·게스트 자격 확인 (회원 정보 복제본 기준)
- HP 보류·반환·지급 시점 판단과 Billing 호출

### 1.2 출연 모듈이 하지 않는 일

- HP 잔액·원장 관리 → 지갑+정산(billing) 모듈
- 로그인·회원 가입 → 회원 모듈 (출연 모듈은 세션의 회원 ID와 회원 이벤트만 사용)
- 이의 제기 판정·패널티 → 고객센터 모듈

### 1.3 구현 현황

| 기능 | 상태 | 이슈 |
|---|---|---|
| 모듈 기반 (정책 설정, Clock, 오류 응답, Billing 계약과 Fake) | ✅ | #26 |
| 출연 회원 복제, 호스트·게스트 자격 확인, 팀 공통 로그인 적용 | ✅ | #31 |
| 회원 정보 변경 이벤트 반영 | ✅ | #50 |
| 출연 요청서 보내기(HP 보류)·조회·기본 정보 수정 | ✅ | #98 |
| 출연 요청 응답(수락·거절·만료·취소) / 출연 모집·지원 / 출연 취소 요청 / 출연 확인 | ⬜ 예정 | — |

---

## 2. 정책 요약

### 2.1 참여 자격

출연 거래(요청·모집·지원·수락)에는 **현재 활동 중(ACTIVE)이고 인플루언서 이상**인 회원만 참여할 수 있다.

| 역할 \ 상태 | ACTIVE | SUSPENDED | WITHDRAWN |
|---|---|---|---|
| USER | ❌ `NOT_INFLUENCER` | ❌ `APPEARANCE_USER_NOT_ACTIVE` | ❌ `APPEARANCE_USER_NOT_ACTIVE` |
| INFLUENCER | ✅ | ❌ `APPEARANCE_USER_NOT_ACTIVE` | ❌ `APPEARANCE_USER_NOT_ACTIVE` |
| VERIFIED_INFLUENCER | ✅ | ❌ `APPEARANCE_USER_NOT_ACTIVE` | ❌ `APPEARANCE_USER_NOT_ACTIVE` |
| ADMIN | ❌ `NOT_INFLUENCER` | ❌ `APPEARANCE_USER_NOT_ACTIVE` | ❌ `APPEARANCE_USER_NOT_ACTIVE` |

- 상태를 먼저 확인하고, 그다음 역할을 확인한다.
- 관리자는 출연 거래에 참여하지 않는다 (AGENTS.md 6장 역할 표).
- 구현: `AppearanceUser.validateCanAppear()`

### 2.2 정책 수치

정책 수치는 코드에 직접 쓰지 않고 `AppearancePolicy`로만 사용한다. 값은 `boundedContext/appearance/src/main/resources/appearance.yml`에 있다.

| 항목 | 값 | 설정 키 | 계산 메서드 |
|---|---|---|---|
| 최저 출연료 (1인당) | 20,000 HP | `appearance.policy.minimum-fee` | `isBelowMinimumFee(fee)` |
| 호스트 일방 취소 위약금 | 출연료의 30%, 원 단위 미만 버림 | `appearance.policy.penalty-rate-percent` | `calculatePenalty(fee)` |
| 출연 확인 기간 | 방송 예정 일시 + 7×24시간. 지나면 자동 확인(지급) | `appearance.policy.confirm-period` | `confirmDeadline(scheduledAt)` |
| 출연 취소 요청 최대 횟수 | 출연 1건당 2회 (거절 후 1회 재요청) | `appearance.policy.max-cancel-request-count` | `canRequestCancel(count)` |

- 정책 값은 `appearance.yml`에서만 바꾼다 (PR 리뷰를 거치도록). 환경 변수 등 외부 설정으로 덮어쓰지 않는다.
- 잘못된 값(최저 출연료 0 이하, 위약금 비율 0~100 밖 등)이면 앱이 시작되지 않는다.

### 2.3 시간

- 출연 모듈의 시각은 모두 한국 시간(Asia/Seoul)으로 다룬다. DB는 `timestamp`(시간대 없음), Java는 `LocalDateTime`.
- 기한·마감처럼 로직에 쓰는 현재 시각은 `appearanceClock` 빈(`Clock`)으로만 얻는다. 테스트에서는 고정 시각을 넣는다.

---

## 3. 모듈 구조

### 3.1 패키지

```
com.hapbang.appearance
├── config        모듈 설정 (정책 등록, Clock)
├── domain        정책 수치, 오류 코드, 엔티티
├── app           시나리오 조립 (Facade → UseCase → Support, 응답 DTO)
├── in            바깥에서 들어오는 곳 (Controller·Swagger Spec·요청 DTO, 이벤트 리스너, 이후 Scheduler)
├── out           바깥으로 나가는 곳 (Repository, Billing 연동)
├── global        전역 global 모듈로 옮길 후보 (오류 응답은 #71에서 global로 이관되어 현재 비어 있음)
└── shared        전역 shared 모듈로 옮길 후보 (서비스 시간대)
```

- 의존 방향: `in → app → domain ← out`
- app 계층 구성 (팀 강의 모듈과 같은 방식)

| 이름 | 역할 | 트랜잭션 |
|---|---|---|
| `…Facade` | in 계층(컨트롤러·리스너)이 부르는 유일한 진입점. 세션 역할 검사 `@PreAuthorize` | **기능 하나 = 트랜잭션 하나를 여기서 연다** |
| `…UseCase` | 기능 하나의 로직 (예: `AppearanceRequestCreateUseCase` = 요청서 저장) | 열지 않음 (Facade 트랜잭션에 참여). 예외는 아래 |
| `…Support` | 저장소(Repository) 접근과 공통 조회 (예: `getById`, 자격 확인 조회) | 열지 않음 |

- 예외: 외부 호출(Billing)을 사이에 두고 단계별로 따로 커밋해야 하는 기능(요청서 보내기)은 Facade가 트랜잭션을 열지 않고, 각 UseCase 메서드가 직접 연다.
- 세션 역할 검사(`@PreAuthorize`)는 Facade에서, 출연 회원 자격(정지·강등)과 업무 규칙은 UseCase·도메인에서 확인한다.
- 스케줄러·이벤트 리스너는 로그인 정보가 없으므로 `@PreAuthorize`가 없는 별도 Facade를 쓴다 (예: `AppearanceUserFacade`).
- `global`, `shared` 패키지는 팀 공통 방식이 정해지지 않아 출연 모듈 안에 임시로 둔 것이다. 옮길 조건은 각 `package-info.java`에 적었다.

### 3.2 주요 클래스

| 클래스 | 역할 |
|---|---|
| `config.AppearanceConfig` | `AppearancePolicy` 등록, `appearanceClock` 빈 |
| `domain.AppearancePolicy` | 정책 수치와 계산 |
| `domain.AppearanceUser` | 출연 회원(복제 회원). `validateCanAppear()`로 참여 자격 확인 |
| `domain.AppearanceErrorCode`, `AppearanceException` | 오류 코드와 업무 예외 |
| `app.AppearanceUserFacade` | 회원 이벤트 리스너의 진입점: `syncJoined()` / `syncModified()` (트랜잭션, 역할 검사 없음) |
| `app.AppearanceUserSyncUseCase` | `createIfAbsent()`: 가입 정보로 생성 (있으면 무시) / `update()`: 변경 정보로 갱신 (없으면 실패) |
| `app.AppearanceUserSupport` | `getParticipant()`: 자격 확인 후 출연 회원 반환 / `getGuest()`: 게스트 기준 메시지로 자격 확인 |
| `domain.AppearanceRequest`, `AppearanceRequestStatus` | 출연 요청서와 상태. 생성 시 값 검증, 보류 결과 반영, 기본 정보 수정, 조회 권한 확인 |
| `domain.AppearanceRequestRules` | 요청서 입력 값 길이·형식 제한 (도메인과 요청 DTO 검증이 같이 사용) |
| `app.AppearanceRequestFacade` | 출연 요청서 API 진입점. `@PreAuthorize`, 기능별 트랜잭션, 보내기의 "저장 → HP 보류 → 상태 변경" 순서 조립 |
| `app.AppearanceRequestCreateUseCase` | 보내기 1단계: 자격·값·중복 확인 후 `HOLD_PENDING` 저장 (자체 트랜잭션) |
| `app.AppearanceRequestHoldUseCase` | 보내기 2·3단계: Billing 보류 요청(트랜잭션 밖), 결과 반영 `markRequested`/`markHoldFailed` (자체 트랜잭션) |
| `app.AppearanceRequestQueryUseCase` | 보낸·받은 목록, 상세 (출연 회원 자격과 조회 권한 확인) |
| `app.AppearanceRequestUpdateUseCase` | 기본 정보 수정 |
| `app.AppearanceRequestSupport` | 요청서 저장소 접근: `getById`, `existsInProgress`, `saveNow`, `findSent`, `findReceived` (최신순 고정) |
| `in.ApiV1AppearanceRequestController`, `ApiV1AppearanceRequestSpec` | 출연 요청서 REST API(Facade만 호출)와 Swagger 명세 (요청 값 검증은 Spec에) |
| `in.AppearanceUserEventListener` | 회원 가입·변경 이벤트 수신 → `AppearanceUserFacade` |
| `out.AppearanceUserRepository` | 출연 회원 저장·조회 |
| `out.AppearanceRequestRepository` | 출연 요청서 저장·목록 조회 |
| `out.billing.BillingClient` | Billing과 주고받는 값(계약) |
| `out.billing.FakeBillingClient` | 계약 확정 전까지 쓰는 메모리 구현 (테스트에서 잔액·무응답 조절) |
| `out.billing.BillingUnavailableException` | Billing 무응답(타임아웃 등). 처리 여부를 알 수 없어 같은 멱등 키로 다시 확인해야 함 |
| (삭제, #71) | 출연 예외는 global의 공통 핸들러(`com.hapbang.global.exception.GlobalExceptionHandler`)가 Problem Detail로 응답한다 |

### 3.3 설정 파일

| 파일 | 역할 |
|---|---|
| `boundedContext/appearance/src/main/resources/appearance.yml` | 정책 수치, Billing 모드 |
| `apps/src/main/resources/application.yaml` | `spring.config.import: classpath:appearance.yml`로 위 파일을 읽음 |
| `boundedContext/appearance/src/test/resources/application.yaml` | 테스트에서 위 파일을 읽음 (테스트 전용, 배포 미포함) |

---

## 4. 다른 모듈과의 연동

| 상대 | 방식 | 주고받는 것 | 상태 |
|---|---|---|---|
| 회원 | 이벤트 수신 | `UserJoinedEvent`, `UserModifiedEvent` → 출연 회원 생성·갱신 | ✅ |
| 회원 (global) | 로그인 | `@LoginUserId`로 세션의 회원 ID | ✅ |
| 지갑+정산 (billing) | HTTP API | 보류, 반환, 지급, 일괄 정산 | 계약 초안 + Fake 구현 |
| 고객센터 | 이벤트 발행 | 이의 제기 (패널티 요청) | 예정 |

### 4.1 회원 정보 복제

```
회원 모듈 ──UserJoinedEvent───▶ AppearanceUserEventListener ─▶ AppearanceUserFacade.syncJoined()   ─▶ createIfAbsent() ─▶ appearances.users
          └─UserModifiedEvent─▶                             └▶ AppearanceUserFacade.syncModified() ─▶ update()
```

| 이벤트 | 발행 시점 (회원 모듈) | 출연 모듈 처리 |
|---|---|---|
| `UserJoinedEvent` | 회원 가입 | 출연 회원 생성. **이미 있으면 무시** |
| `UserModifiedEvent` | 회원 정보(닉네임·역할·상태) 변경 | 있는 출연 회원 갱신. **없으면 `APPEARANCE_USER_NOT_FOUND`로 실패** → 이벤트 저장소에 남았다가 재시작 때 재처리 |

- **모든 회원 이벤트는 회원 모듈의 트랜잭션이 커밋된 뒤에만 반영한다** (`@ApplicationModuleListener`, 새 트랜잭션에서 처리). 회원 쪽 처리가 롤백되면 출연 회원도 바뀌지 않는다.
- **출연 회원은 가입 이벤트로만 만든다.** 늦게 처리된 가입 이벤트가 이미 반영된 변경 정보를 덮어쓰지 않게 하기 위해서다 (채팅·강의 모듈과 같은 방식).
    - 복제 회원은 ID를 직접 넣기 때문에 JPA `save()`가 merge로 동작한다. 가입·변경이 각자 "없으면 생성"하면 PK 충돌 없이 나중 것이 덮어쓴다.
- 변경 이벤트는 도착한 순서대로 그대로 반영한다.
- 처리에 실패하면 Modulith 이벤트 저장소(`event_publication`)에 남고, 서버가 재시작될 때 다시 처리된다 (`republish-outstanding-events-on-restart`). 주기적으로 재시도하지는 않는다.
- 회원이 정지·탈퇴 상태로 바뀌면 이후 출연 거래 자격 확인에서 `APPEARANCE_USER_NOT_ACTIVE`가 된다.
- 한계 (팀에서 수용)
    - 가입보다 변경 이벤트가 먼저 처리되면, 그 변경은 서버 재시작 때까지 반영되지 않는다.
    - 이벤트에 버전·발생 시각이 없어서, 변경 이벤트끼리 순서가 바뀌어 처리되면(실패한 변경 이벤트의 재처리 등) 옛 정보가 반영될 수 있다. 회원 이벤트에 버전이 생기면 더 새로운 이벤트만 반영하도록 바꾼다 (#56).

### 4.2 Billing 연동 (계약 초안)

출연 모듈은 "언제, 어떤 금액·사유로 Billing을 호출하는지"만 책임진다. 계약은 `out/billing/BillingClient.java` 한 파일에 있다.

| 동작 | 요청 값 | 응답 값 |
|---|---|---|
| 보류 | idempotencyKey, refType, refId, hostId, amount | `HELD` / `INSUFFICIENT_BALANCE`, 보류 총액 |
| 반환 | idempotencyKey, refType, refId, hostId, amount, reason | 남은 보류 금액 |
| 지급 | idempotencyKey, refType, refId, appearanceId, guestId, amount, reason | 남은 보류 금액 |
| 일괄 정산 | idempotencyKey, refType, refId, items(지급/반환, 받는 사람, 출연 ID, 금액, 사유) | 남은 보류 금액 (전부 성공 또는 전부 실패) |

- `refType`: `APPEARANCE_REQUEST`(출연 요청서) / `APPEARANCE_RECRUIT`(출연 모집글). 보류는 이 단위로 1건이고, 반환·지급은 나눠서 처리한다.
- 멱등 키 형식: `{refType}-{refId}-{동작}-{순번}` (예: `APPEARANCE_REQUEST-12-HOLD-1`, `BillingClient.idempotencyKey()`로 만든다). 같은 키로 다시 요청하면 처음 결과를 돌려받는다.
- Billing이 응답하지 않으면 `BillingUnavailableException`. 출연 모듈은 대상을 그대로 두고 나중에 같은 멱등 키로 다시 확인한다.
- 사유
    - 반환: `REQUEST_REJECTED`, `REQUEST_EXPIRED`, `REQUEST_CANCELED`, `CANCEL_AGREED`, `NO_SHOW`, `RECRUIT_UNDERFILLED`, `RECRUIT_CANCELED`, `HOST_CANCELED`
    - 지급: `APPEARANCE_CONFIRMED`, `HOST_CANCEL_PENALTY`
- 구현체는 `appearance.billing.mode`로 고른다. 현재는 `fake`(메모리 구현)만 있으며, 계약이 확정되면 HTTP 구현으로 교체한다.

---

## 5. DB 스키마 (`appearances`)

테이블은 Flyway 마이그레이션으로 만든다 ([docs/db-migration.md](../db-migration.md)). 위치: `boundedContext/appearance/src/main/resources/db/migration/`

### 5.1 `appearances.users` — 출연 회원 (회원 복제)

| 컬럼 | 타입 | 설명 |
|---|---|---|
| `id` | BIGINT, PK | 원본 회원 ID 그대로 (자동 생성 없음) |
| `nickname` | VARCHAR(255) | 닉네임 |
| `role` | VARCHAR(255) | `USER` / `INFLUENCER` / `VERIFIED_INFLUENCER` / `ADMIN` |
| `status` | VARCHAR(255) | `ACTIVE` / `SUSPENDED` / `WITHDRAWN` |
| `created_at`, `updated_at` | TIMESTAMP(6) | 생성·수정 시각 (JPA Auditing) |

- 마이그레이션: `V202610121130__create_appearance_user_table.sql` (#31)
- 탈퇴한 회원도 행을 지우지 않고 `status = WITHDRAWN`으로 남긴다.

### 5.2 `appearances.requests` — 출연 요청서

| 컬럼 | 타입 | 설명 |
|---|---|---|
| `id` | BIGINT, PK (identity) | 요청서 ID |
| `host_id`, `guest_id` | BIGINT | 회원 ID (FK 없음) |
| `title` | VARCHAR(100) | 제목 (수정 가능) |
| `host_channel_url` | VARCHAR(255) | 호스트 채널 URL (수정 가능) |
| `scheduled_at` | TIMESTAMP(6) | 방송 예정 일시 |
| `expected_minutes` | INTEGER | 예상 출연 시간(분) |
| `content` | TEXT | 콘텐츠 설명 (수정 가능) |
| `fee` | BIGINT | 출연료 (HP) |
| `accept_deadline` | TIMESTAMP(6) | 수락 기한 |
| `status` | VARCHAR(255) | `HOLD_PENDING` / `HOLD_FAILED` / `REQUESTED` / `ACCEPTED` / `REJECTED` / `EXPIRED` / `CANCELED` |
| `reject_reason`, `responded_at`, `canceled_at` | VARCHAR(500), TIMESTAMP(6) | 게스트 응답·만료·취소 처리에서 사용 (현재 비어 있음) |
| `version` | BIGINT | 낙관적 락 |
| `created_at`, `updated_at` | TIMESTAMP(6) | 생성·수정 시각 (JPA Auditing) |

- 마이그레이션: `V202610131039__create_appearance_request_table.sql` (#98)
- 제약: 자기 자신에게 요청 불가(`host_id <> guest_id`), `fee > 0`, `expected_minutes > 0`, `accept_deadline < scheduled_at`
- **부분 유니크 인덱스** `uk_appearance_requests_pending_host_guest`: `(host_id, guest_id) where status in ('HOLD_PENDING', 'REQUESTED')`. 같은 호스트·게스트의 진행 중 요청서를 1건으로 제한한다 (동시 요청 차단).
- 인덱스: 보낸 목록 `(host_id, created_at)`, 받은 목록 `(guest_id, created_at)`

---

## 6. 출연 요청 (#98)

### 6.1 API

| 메서드 | 경로 | 설명 | 성공 |
|---|---|---|---|
| POST | `/api/v1/appearances/requests` | 출연 요청서 보내기 (HP 보류) | 201 |
| GET | `/api/v1/appearances/requests/sent` | 보낸 요청 목록 (호스트) | 200 |
| GET | `/api/v1/appearances/requests/received` | 받은 요청 목록 (게스트) | 200 |
| GET | `/api/v1/appearances/requests/{requestId}` | 요청서 상세 (호스트·게스트) | 200 |
| PATCH | `/api/v1/appearances/requests/{requestId}` | 기본 정보 수정 (호스트, `REQUESTED`까지) | 200 |

- **성공 응답은 팀 공통 `RsData`로 감싼다** (#144, AGENTS.md 7장): `{ "statusCode": 201, "message": "...", "data": { … } }`. 요청서·목록은 `data` 안에 들어간다. 오류는 그대로 Problem Detail이다.

| API | `message` |
|---|---|
| 보내기 (201) | 출연 요청을 보냈습니다. |
| 보낸 목록 | 보낸 출연 요청 목록을 조회했습니다. |
| 받은 목록 | 받은 출연 요청 목록을 조회했습니다. |
| 상세 | 출연 요청서를 조회했습니다. |
| 수정 | 출연 요청서를 수정했습니다. |

- 생성은 `@ResponseStatus(HttpStatus.CREATED)` + `RsData.created(...)`로 HTTP 상태와 본문 `statusCode`를 같게 맞춘다. 앞으로 추가하는 출연 API도 처음부터 `RsData`로 만들고, 본문이 없으면 `ResponseEntity<Void>`(204)를 쓴다.

- 권한: 로그인(401은 보안 필터) → Facade `@PreAuthorize("hasAnyRole('INFLUENCER', 'VERIFIED_INFLUENCER')")`(거절 시 공통 403 `FORBIDDEN`) → UseCase에서 출연 회원 자격 재확인(`NOT_INFLUENCER`·`APPEARANCE_USER_NOT_ACTIVE`). 세션 역할은 로그인 시점 값이라, 로그인 이후 정지·강등은 서비스 확인이 막는다 (조회 포함).
- 쓰기 요청(POST·PATCH)은 CSRF 토큰(`X-XSRF-TOKEN` 헤더)이 필요하다 (global SecurityConfig).
- 목록은 `Pageable`(page, size; 기본 20, 최대 100). **정렬은 최신순(`createdAt` desc, `id` desc) 고정**이며 `sort` 값은 무시한다. 응답은 `RsData`의 `data`에 팀 공통 PagedModel 형식(`content`, `page`).
- 검색·필터(상태, 기간, 키워드 등)는 아직 정하지 않았다. 정해지면 조건 객체를 받아 조회 조건을 조합하고, 보이는 상태 제한과 최신순 정렬은 유지한다.
- 응답에는 회원 ID(`hostId`, `guestId`)만 담고 닉네임은 담지 않는다. 목록마다 회원 정보를 함께 조회하는 부담을 피하기 위해서이며, 화면에 필요한 닉네임은 회원 프로필 API로 조회한다.
- 수정은 보낸 값만 바꾸고 보내지 않은(null) 값은 그대로 둔다. 하나도 보내지 않으면 400.
- 수정(PATCH)은 보낸 값으로 덮어쓰기만 하므로 **멱등**이다. 같은 요청을 여러 번 보내도 결과가 같고, 바뀐 값이 없으면 저장도 일어나지 않는다(버전·수정 시각 그대로). 그래서 별도 멱등 키가 필요 없다 (멱등 키는 HP 보류처럼 다시 실행하면 결과가 달라지는 Billing 호출에만 쓴다).

### 6.2 요청서 보내기 흐름

```
Controller ─▶ AppearanceRequestFacade.create (@PreAuthorize, 트랜잭션 없음)
  ① CreateUseCase.createPending  [트랜잭션 1, 커밋]
       호스트 자격 → 값 검증(형식, 자기 자신, 최저 출연료, 현재 < 수락 기한 < 방송 예정 일시) → 게스트 자격
       → 진행 중 요청 확인 → HOLD_PENDING 저장 (부분 유니크 인덱스 위반이면 DUPLICATE)
  ② HoldUseCase.requestHold  [트랜잭션 밖]  BillingClient.hold, 멱등 키 APPEARANCE_REQUEST-{id}-HOLD-1
       ├─ HELD                 → ③ HoldUseCase.markRequested  [트랜잭션 2] → REQUESTED, 201
       ├─ INSUFFICIENT_BALANCE → ③ HoldUseCase.markHoldFailed [트랜잭션 2] → HOLD_FAILED, 409 INSUFFICIENT_HP
       └─ 무응답(예외)          → HOLD_PENDING 그대로, 503 BILLING_UNAVAILABLE
```

- 저장을 먼저 커밋하는 이유: Billing을 기다리는 동안 DB 트랜잭션을 잡지 않고, 보류 결과와 관계없이 요청서 기록이 남게 하기 위해서다.
- `HOLD_PENDING`으로 남은 요청서는 이후 스케줄러가 같은 멱등 키로 다시 확인해 `REQUESTED`/`HOLD_FAILED`로 정리한다 (예정). Billing의 재요청 방식이 정해지면 맞춘다.

### 6.3 규칙

| 규칙 | 내용 | 이유 |
|---|---|---|
| 진행 중 요청 1건 | 같은 호스트 → 같은 게스트에게 `HOLD_PENDING`·`REQUESTED` 요청서가 있으면 새 요청 차단 (409). 끝난 요청서(수락·거절·만료·취소·보류 실패)는 제외 | 출연이 성립하기 전까지 요청은 1건으로 하여 요청 중복을 확실히 막는다 |
| 보류 미완료·실패 숨김 | `HOLD_PENDING`·`HOLD_FAILED`는 목록·상세에 보이지 않고 기록으로만 남는다 | 실패는 그 자리에서 409로 알린다. 처리 중인 요청은 끝나기 전까지 바로 중복 요청이 되지 않게 한다 (Billing 재처리 설계 후 수정 예정) |
| 조회 권한 | 호스트·게스트만. 다른 회원에게는 404 | 요청서가 있다는 사실도 알리지 않는다 |
| 수정 범위 | 제목·콘텐츠 설명·호스트 채널 URL만, 호스트만, `REQUESTED`까지 | 금액·일시 수정은 HP 보류 조정 등 연쇄 작용이 있어 팀 결정 후 추가 |
| 입력 값 제한 | 제목 100자, 채널 URL http(s) 255자, 설명 5,000자, 예상 출연 시간 1~1,440분 (`AppearanceRequestRules`) | DB 컬럼과 맞추고, 요청 DTO와 도메인 양쪽에서 확인 |
| 상태 충돌 응답 | 중복·잔액 부족·수정 불가 상태는 409 | 강의·채팅 모듈과 같은 기준 |
| 동시 수정 | 같은 요청서를 동시에 수정·응답하면 버전(`@Version`)이 맞지 않는 쪽을 409 `CONCURRENT_UPDATE` | 나중에 커밋하려던 요청이 앞선 변경을 모르고 덮어쓰지 않게 한다 |
