# 출연(Appearance) 모듈: 테스트 명세

출연 모듈 테스트가 무엇을 보장하는지 정리한 문서다. 테스트 이름(`@DisplayName`)과 아래 케이스가 대응한다.
테스트를 추가·변경하면 이 문서도 함께 갱신한다.

- 정책과 구조: [module.md](module.md)
- 오류 코드: [error-codes.md](error-codes.md)

---

## 1. 실행 방법

| 명령 | 대상 | Docker 필요 |
|---|---|---|
| `./gradlew :appearance:test` | 출연 모듈 전체 테스트 | ✅ (저장소·통합·E2E 포함) |
| `./gradlew :apps:test --tests "com.hapbang.ModularityTests"` | 모듈 경계 검증 | ❌ |
| `./gradlew check` | CI와 같은 전체 검사 (모든 모듈) | ✅ |

- 저장소·통합·E2E 테스트는 Testcontainers로 임시 PostgreSQL을 띄우고, 끝나면 버린다. 팀 DB나 서버 DB는 건드리지 않는다.
- 테이블은 운영과 같이 Flyway 마이그레이션으로 만들고, Hibernate는 엔티티와 테이블이 맞는지만 검사한다(`validate`). 어긋나면 테스트가 시작 단계에서 실패한다.
- Windows에서 전체 검사가 다른 모듈의 한글 소스 때문에 컴파일에 실패하면 `./gradlew "-Dorg.gradle.jvmargs=-Dfile.encoding=UTF-8" check`로 실행한다.

## 2. 테스트 구성

| 종류 | 테스트 | 확인 대상 | 이슈 |
|---|---|---|---|
| 단위 | `AppearancePolicyTest` | 정책 수치 계산 | #26 |
| 단위 | `AppearanceConfigTest` | 설정 값 주입, Clock, Billing 구현 선택 | #26 |
| 단위 | `FakeBillingClientTest` | Fake Billing 동작 규칙 | #26 |
| 단위 | `AppearanceUserTest` | 출연 회원 참여 자격 | #31 |
| 단위 | `AppearanceExceptionHandlerTest` | 출연 예외 → Problem Detail | #26, #31 |
| 단위 | `AppearanceRequestTest` | 출연 요청서 생성 검증·상태 전이·수정·조회 권한 | #98 |
| 단위 | `AppearanceRequestValidationTest` | 요청서 보내기·수정 요청 값 형식 (Bean Validation) | #98 |
| 저장소 | `AppearanceUserRepositoryTest` | `appearances.users` 저장·조회 | #31 |
| 저장소 | `AppearanceRequestRepositoryTest` | `appearances.requests` 저장, 진행 중 요청 부분 유니크 인덱스, 목록 조회 | #98 |
| 통합 | `AppearanceModuleIntegrationTest` | 모듈 전체 기동 | #26 |
| 통합 | `AppearanceUserFacadeTest` | 출연 회원 생성·갱신(Facade), 자격 확인(Support) | #31, #50, #98 |
| 통합 | `AppearanceUserEventListenerTest` | 회원 가입·정보 변경 이벤트 수신 | #31, #50 |
| E2E | `AppearanceApiE2ETest` | 세션 로그인 → 자격 확인 → 오류 응답, 회원 정지 반영 (앱 전체) | #31, #50 |
| 통합 | `AppearanceRequestFacadeTest` | 요청서 보내기(HP 보류·잔액 부족·무응답·중복·동시 요청), 세션 역할(`@PreAuthorize`), 출연 회원 자격, 조회, 수정(멱등) | #98 |
| E2E | `AppearanceRequestApiE2ETest` | 출연 요청서 API 5개: 세션 로그인·CSRF·역할 → 응답·오류 형식 (앱 전체) | #98 |
| 모듈 경계 | `ModularityTests` (apps) | 다른 모듈 내부 참조 없음 | — |

---

## 3. 단위 테스트

### 3.1 정책 — `AppearancePolicyTest`

| 케이스 | 기대 결과 |
|---|---|
| 출연료 19,999 / 20,000 | 최저 출연료 미달 / 충족 |
| 위약금: 출연료 20,000 / 25,555 / 0 | 6,000 / 7,666 (7,666.5에서 끝전 버림) / 0 |
| 출연 확인 마감: 방송 10/20 21:30 | 10/27 21:30 (7×24시간 뒤) |
| 출연 취소 요청을 0 / 1 / 2회 보낸 상태 | 추가 요청 가능 / 가능 / 불가 |
| 최저 출연료 0, 위약금 101%, 확인 기간 0, 최대 횟수 0 | 정책 객체 생성 실패 (앱이 시작되지 않음) |

### 3.2 설정 — `AppearanceConfigTest`

테스트용 `application.yaml`의 `spring.config.import`로 `appearance.yml`을 실제 앱과 같은 방식으로 읽는다.

| 케이스 | 기대 결과 |
|---|---|
| 기본 설정 | 최저 출연료 20,000 / 위약금 30% / 확인 기간 7일 / 취소 요청 최대 2회 |
| 설정 우선순위: 외부 설정으로 최저 출연료 30,000 | 30,000 (외부 설정이 우선). 우선순위 구조만 확인하며, 정책 값은 `appearance.yml`에서만 변경 |
| 출연 Clock | 시간대 Asia/Seoul |
| `appearance.billing.mode` 기본값 / `http` | Fake Billing 등록 / 미등록 |

### 3.3 Fake Billing — `FakeBillingClientTest`

**Fake Billing 동작 규칙**

| # | 규칙 |
|---|---|
| F1 | 모든 회원은 잔액 10,000,000 HP로 시작한다 (테스트에서 변경 가능) |
| F2 | 보류: 잔액이 충분하면 잔액에서 빼고 `HELD`. 부족하면 아무것도 바꾸지 않고 `INSUFFICIENT_BALANCE` (예외 아님) |
| F3 | 보류는 대상(요청서·모집글) 단위로 쌓인다 |
| F4 | 반환: 보류에서 빼서 호스트 잔액에 더한다 |
| F5 | 지급: 보류에서 빼서 게스트 잔액에 더한다 |
| F6 | 일괄 정산: 총액이 보류 금액 이하인지 먼저 확인한 뒤 적용한다 (전부 성공 또는 전부 실패) |
| F7 | 보류보다 많이 반환·지급하려 하면 예외를 던진다 |
| F8 | 같은 멱등 키로 다시 요청하면 처리하지 않고 처음 결과를 돌려준다 |
| F9 | 같은 멱등 키를 다른 종류의 요청에 쓰면 예외 |
| F10 | 금액 0 이하, 빈 멱등 키, 빈 정산 항목은 Billing 호출 전에 막는다 |
| F11 | 메모리에만 저장하므로 앱이 재시작되면 기록이 사라진다 |
| F12 | 무응답으로 설정하면(`setUnavailable(true)`) 모든 요청에 `BillingUnavailableException`을 던지고 아무것도 처리하지 않는다. `reset()`하면 다시 응답한다 |

| 케이스 | 기대 결과 | 규칙 |
|---|---|---|
| 잔액 50,000에서 30,000 보류 | `HELD`, 보류 30,000, 잔액 20,000 | F2 |
| 잔액 10,000에서 30,000 보류 | `INSUFFICIENT_BALANCE`, 잔액·보류 변화 없음 | F2 |
| 같은 멱등 키로 보류 두 번 | 한 번만 처리 (잔액 20,000) | F8 |
| 30,000 보류 후 전액 반환 | 남은 보류 0, 호스트 잔액 50,000 | F4 |
| 30,000 보류 후 전액 지급 | 남은 보류 0, 게스트 잔액 30,000 | F5 |
| 30,000 보류 후 일괄 정산 (위약금 9,000 지급 + 21,000 반환) | 남은 보류 0, 게스트 9,000, 호스트 41,000 | F6 |
| 30,000 보류 후 40,000 일괄 정산 | 예외, 보류 30,000 그대로 | F6, F7 |
| 빈 멱등 키, 금액 0 | 요청 객체 생성 실패 | F10 |
| 무응답 설정 후 보류 → `reset()` 후 보류 | 예외, 잔액 변화 없음 → `HELD` | F12 |
| 멱등 키 만들기 (`APPEARANCE_REQUEST`, 12, `HOLD`, 1) | `APPEARANCE_REQUEST-12-HOLD-1` | — |

### 3.4 출연 회원 자격 — `AppearanceUserTest`

| 케이스 | 기대 결과 |
|---|---|
| INFLUENCER · ACTIVE, VERIFIED_INFLUENCER · ACTIVE | 참여 가능 |
| USER · ACTIVE, ADMIN · ACTIVE | `NOT_INFLUENCER` |
| INFLUENCER · WITHDRAWN, INFLUENCER · SUSPENDED | `APPEARANCE_USER_NOT_ACTIVE` (상태를 역할보다 먼저 확인) |
| 원본 회원 정보 변경 반영 (`syncFrom`) | 닉네임·역할이 새 값으로 바뀜 |

### 3.5 오류 응답 — `AppearanceExceptionHandlerTest`

| 케이스 | 기대 결과 |
|---|---|
| 출연 예외 발생 (`NOT_INFLUENCER`) | 403, `application/problem+json`, `code: NOT_INFLUENCER`, `detail`에 기본 메시지 |
| 상세 메시지를 따로 준 출연 예외 | `detail`에 그 메시지 |
| 버전 충돌(`ObjectOptimisticLockingFailureException`) | 409, `code: CONCURRENT_UPDATE` |
| `@PreAuthorize` 거절(`AccessDeniedException`) | 403, `code: NOT_INFLUENCER` |

### 3.6 출연 요청서 — `AppearanceRequestTest`

기준 시각 10/13 12:00, 최저 출연료 20,000.

| 케이스 | 기대 결과 |
|---|---|
| 올바른 값 (수락 기한 +2일, 방송 +7일, 출연료 30,000) | `HOLD_PENDING`으로 생성 |
| 제목 101자, http(s)가 아닌 URL, 설명 5,001자, 0분, 1,441분 / 경계값(100자, 5,000자, 1,440분) | `INVALID_REQUEST` / 생성 |
| 호스트 = 게스트 | `CANNOT_REQUEST_SELF` |
| 출연료 19,999 / 20,000 | `FEE_BELOW_MINIMUM` ("출연료는 최저 출연료(20,000 HP) 이상이어야 합니다.") / 생성 |
| 수락 기한 = 현재, 현재 −1분 | `INVALID_REQUEST_SCHEDULE` |
| 수락 기한 = 방송 예정 일시, 방송 +1분 | `INVALID_REQUEST_SCHEDULE` |
| 보류 성공 / 잔액 부족 반영 | `REQUESTED` / `HOLD_FAILED` |
| `HOLD_PENDING`이 아닌 요청서에 보류 결과 다시 반영 | `IllegalStateException` (코드 오류) |
| 호스트가 제목·채널 URL만 수정 (설명 null) | 제목·URL만 바뀌고 설명은 그대로 |
| 제목은 맞고 설명이 공백뿐인 수정 | `INVALID_REQUEST`, 제목도 바뀌지 않음 (모두 확인한 뒤 반영) |
| 게스트가 수정 | `NOT_REQUEST_HOST` |
| `HOLD_PENDING` 요청서 수정 | `APPEARANCE_REQUEST_NOT_EDITABLE` |
| 호스트·게스트 조회 / 다른 회원 조회 | 통과 / `APPEARANCE_REQUEST_NOT_FOUND` |
| `HOLD_PENDING`·`HOLD_FAILED` 요청서를 호스트가 조회 | `APPEARANCE_REQUEST_NOT_FOUND` (숨김) |
| `REQUESTED`·`ACCEPTED`·`REJECTED`·`EXPIRED`·`CANCELED` | 목록·상세에 보이는 상태 |

### 3.7 요청 값 형식 — `AppearanceRequestValidationTest`

| 케이스 | 기대 결과 |
|---|---|
| 보내기: 올바른 값 | 통과 |
| 보내기: 제목 공백, URL이 http(s)가 아님, 예상 출연 시간 0분·1441분, 제목 101자 | 해당 항목만 실패 |
| 보내기: 모든 값 없음 | 8개 항목 모두 실패 |
| 수정: 제목만 보냄 / 아무것도 보내지 않음 | 통과 / `anyFieldPresent` 실패 |
| 수정: 제목 공백만, 설명 줄바꿈만, URL `ftp://` | 해당 항목 실패 |

---

## 4. 저장소 · 통합 테스트

### 4.1 출연 회원 저장소 — `AppearanceUserRepositoryTest`

| 케이스 | 기대 결과 |
|---|---|
| 회원 ID 7로 저장 후 조회 | `appearances.users`에 ID 7 그대로 저장, 닉네임·역할 일치, 생성·수정 시각 자동 기록 |

### 4.2 모듈 기동 — `AppearanceModuleIntegrationTest`

| 케이스 | 기대 결과 |
|---|---|
| 출연 모듈 전체 + PostgreSQL 컨테이너로 기동 | 정책 값 주입, Clock 시간대 Asia/Seoul, Billing 구현이 Fake |

### 4.3 출연 회원 관리 — `AppearanceUserFacadeTest`

동기화는 `AppearanceUserFacade`(트랜잭션)로, 자격 확인은 `AppearanceUserSupport`로 확인한다.

| 케이스 | 기대 결과 |
|---|---|
| 가입 정보로 `syncJoined` | 출연 회원 생성 |
| 일반 유저 생성 → `syncModified`(INFLUENCER) → 같은 가입 정보로 다시 `syncJoined` | 1명 그대로, 역할 INFLUENCER 유지 (가입 정보 무시) |
| 있는 회원을 `syncModified` (역할 USER → INFLUENCER) | 1명 그대로, 역할만 갱신 |
| 없는 회원을 `syncModified` | `APPEARANCE_USER_NOT_FOUND`, 출연 회원 생성 안 됨 |
| 인플루언서로 `getParticipant` | 출연 회원 반환 |
| 없는 회원으로 `getParticipant` | `APPEARANCE_USER_NOT_FOUND` |
| 일반 유저로 `getParticipant` | `NOT_INFLUENCER` |
| 인플루언서를 정지 상태로 `syncModified`한 뒤 `getParticipant` | `APPEARANCE_USER_NOT_ACTIVE` |

### 4.4 회원 이벤트 수신 — `AppearanceUserEventListenerTest`

회원 모듈이 가입·변경 이벤트를 발행한 것처럼 만들고 확인한다. 리스너는 발행한 트랜잭션이 커밋된 뒤에만 실행된다.

| 케이스 | 기대 결과 |
|---|---|
| 트랜잭션 안에서 `UserJoinedEvent` 발행 후 커밋 | 출연 회원 생성 (최대 5초 대기) |
| `UserJoinedEvent`를 발행한 트랜잭션이 롤백 | 출연 회원 생성 안 됨 |
| 인플루언서가 있는 상태에서 `UserModifiedEvent`(닉네임 변경, VERIFIED_INFLUENCER, SUSPENDED) 발행 후 커밋 | 닉네임·역할·상태 갱신, 출연 회원은 1명 그대로 (최대 5초 대기) |
| 출연 회원이 없는 상태에서 `UserModifiedEvent` 발행 후 커밋 | 출연 회원 생성 안 됨 (0.5초 동안 확인). 처리 실패로 남아 재시작 때 재처리 |
| 가입 → 변경(닉네임·VERIFIED_INFLUENCER) 반영 뒤 같은 `UserJoinedEvent`를 다시 발행 | 변경된 닉네임·역할 유지, 출연 회원 1명 (늦은 가입 이벤트 무시) |
| `UserModifiedEvent`(WITHDRAWN)를 발행한 트랜잭션이 롤백 | 상태 ACTIVE 그대로 |

- 변경 이벤트끼리 순서가 바뀌어 처리되는 경우는 막지 않는다 (이벤트에 버전이 없음, [module.md](module.md) 4.1 참고).

### 4.5 출연 요청서 저장소 — `AppearanceRequestRepositoryTest`

| 케이스 | 기대 결과 |
|---|---|
| 저장 후 조회 | ID 자동 생성, 상태·설명(TEXT)·수락 기한 일치, 버전·생성 시각 기록 |
| 같은 호스트·게스트에 `REQUESTED`가 있는데 새로 저장 | `DataIntegrityViolationException` (`uk_appearance_requests_pending_host_guest`) |
| 같은 호스트·게스트에 `HOLD_FAILED`만 있을 때 새로 저장 | 저장됨 (제한 대상 아님) |
| 방향이 반대(게스트 → 호스트)이거나 다른 게스트 | 저장됨 |
| `REQUESTED`·`HOLD_FAILED`·`HOLD_PENDING` 각 1건에서 보이는 상태로 목록 조회 | `REQUESTED` 1건만 (보낸·받은 목록 모두) |

### 4.6 출연 요청서 흐름 — `AppearanceRequestFacadeTest`

DB와 Fake Billing을 함께 쓴다. 세션 역할은 `@WithMockUser(roles = "INFLUENCER")`로 넣는다(Facade의 `@PreAuthorize`). 테스트가 끝날 때마다 Fake Billing을 초기화한다.

| 케이스 | 기대 결과 |
|---|---|
| 보내기 | `REQUESTED`, 보류 30,000, 호스트 잔액 −30,000 |
| 잔액 29,999에서 보내기 | `INSUFFICIENT_HP`, 요청서 `HOLD_FAILED`, 잔액 그대로, 보낸 목록 0건 |
| 잔액 부족 실패 뒤 잔액을 채우고 같은 게스트에게 다시 보내기 | `REQUESTED` |
| Billing 무응답 | `BILLING_UNAVAILABLE`, 요청서 `HOLD_PENDING`, 보낸 목록 0건. 응답이 돌아와도 바로 다시 보내면 `DUPLICATE_APPEARANCE_REQUEST` |
| 응답 대기 중인 게스트에게 다시 보내기 / 다른 게스트에게 보내기 | `DUPLICATE_APPEARANCE_REQUEST` / `REQUESTED` (보류 2건) |
| 같은 게스트에게 5개 스레드가 동시에 저장 | 1건 저장, 4건 `DUPLICATE_APPEARANCE_REQUEST`, DB에 1건 |
| 게스트가 일반 유저 / 없는 회원 | `NOT_INFLUENCER` ("인플루언서 이상에게만 출연을 요청할 수 있습니다.") / `APPEARANCE_USER_NOT_FOUND` ("게스트 회원 정보를 찾을 수 없습니다."), 저장 안 됨 |
| 세션은 인플루언서, 출연 회원은 정지 | `APPEARANCE_USER_NOT_ACTIVE`, 저장 안 됨 |
| 세션 역할 USER (보내기·목록) / ADMIN (보내기) | `AccessDeniedException` (Facade `@PreAuthorize`), 저장 안 됨 |
| 출연 회원이 일반 유저로 강등된 뒤 보낸 목록 조회 | `NOT_INFLUENCER` (조회도 막음) |
| 호스트가 3건을 보내고 1건을 받음 | 보낸 목록 최신순 2건(+1), 받은 목록 1건 |
| 3건을 size 2로 조회 | 총 3건, 2페이지, 첫 페이지 첫 항목이 가장 최근 요청서 |
| 상세: 호스트·게스트 / 다른 회원 / 없는 ID | 조회됨 / `APPEARANCE_REQUEST_NOT_FOUND` / `APPEARANCE_REQUEST_NOT_FOUND` |
| 호스트가 제목만 수정 | 제목만 바뀌고 설명 그대로, DB 반영 |
| 게스트 수정 / 다른 회원 수정 | `NOT_REQUEST_HOST` / `APPEARANCE_REQUEST_NOT_FOUND` |
| 제목 공백, URL `ftp://`로 수정 (DTO 검증 없이 직접 호출) | `INVALID_REQUEST`, 제목 그대로 (도메인에서도 확인) |
| 같은 제목으로 두 번 수정 | 두 번째는 버전·수정 시각 그대로 (멱등) |

---

## 5. E2E 테스트

### 5.1 API 흐름 — `AppearanceApiE2ETest`

앱 전체(보안 필터, 세션 로그인, 서비스, DB)를 띄우고 MockMvc로 요청한다. 로그인은 팀 공통 `TestLogin`으로 만든다.
테스트 전용 컨트롤러로 로그인·자격 확인 기반만 확인한다. 실제 출연 API는 5.2에서 확인한다.

| 케이스 | 기대 결과 |
|---|---|
| 로그인하지 않고 요청 | 401 (보안 필터) |
| 인플루언서로 로그인 | 200, 세션의 회원 ID가 `@LoginUserId`로 들어와 자격 확인 통과 |
| 로그인했지만 출연 회원 정보 없음 | 404 Problem Detail, `code: APPEARANCE_USER_NOT_FOUND` |
| 일반 유저로 로그인 | 403 Problem Detail, `code: NOT_INFLUENCER`, 한글 `detail` 정상 (UTF-8) |
| 인플루언서로 요청 성공 → 회원 정지 이벤트(`UserModifiedEvent`) 반영 → 같은 로그인으로 다시 요청 | 200 → 403 Problem Detail, `code: APPEARANCE_USER_NOT_ACTIVE` |

---

### 5.2 출연 요청서 API — `AppearanceRequestApiE2ETest`

앱 전체(보안 필터, 세션 로그인, CSRF, 메서드 보안, 서비스, DB, Fake Billing)를 띄우고 실제 API를 호출한다. 쓰기 요청은 `csrf()`를 함께 보낸다.

| 케이스 | 기대 결과 |
|---|---|
| 보내기 → 보낸 목록 → 받은 목록 → 상세 → 수정 | 201 `REQUESTED` → `content[0]`·`page.totalElements` 1 → 게스트 목록에 호스트 ID → 상세 필드 일치 → 보낸 값만 바뀜 |
| 로그인하지 않고 목록 조회 | 401 (보안 필터) |
| 일반 유저 세션으로 목록 조회, 관리자 세션으로 보내기 | 403 Problem Detail, `code: NOT_INFLUENCER` (Facade `@PreAuthorize`) |
| 제목 공백·URL 형식 오류·출연료 누락 | 400, `code: INVALID_REQUEST`, `errors`에 `title`·`hostChannelUrl`·`fee` |
| 출연료 19,999 | 400, `code: FEE_BELOW_MINIMUM`, `detail`에 최저 출연료 |
| 잔액 10,000에서 30,000 요청 | 409, `code: INSUFFICIENT_HP` |
| 응답 대기 중인 게스트에게 다시 보내기 | 409, `code: DUPLICATE_APPEARANCE_REQUEST` |
| Billing 무응답 | 503, `code: BILLING_UNAVAILABLE` |
| 다른 회원 상세 조회 / 게스트 수정 | 404 `APPEARANCE_REQUEST_NOT_FOUND` / 403 `NOT_REQUEST_HOST` |
| 빈 본문(`{}`)으로 수정 | 400, `code: INVALID_REQUEST` |
| CSRF 토큰 없이 보내기 | 403 (보안 필터) |

---

## 6. 모듈 경계 — `ModularityTests` (apps)

| 케이스 | 기대 결과 |
|---|---|
| 전체 모듈 구조 검증 | 다른 모듈의 내부 코드를 참조하지 않음 (출연 → `global`은 공개된 `auth`만 사용) |
