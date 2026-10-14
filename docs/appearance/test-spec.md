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
| 단위 | `AppearanceExceptionHandlerTest` | 출연 예외 → 공통 핸들러의 Problem Detail | #26, #31, #71 |
| 단위 | `AppearanceRequestTest` | 출연 요청서 생성 검증·상태 전이·수정·조회 권한 | #98 |
| 단위 | `AppearanceRequestValidationTest` | 요청서 보내기·수정 요청 값 형식 (Bean Validation) | #98 |
| 저장소 | `AppearanceUserRepositoryTest` | `appearances.users` 저장·조회 | #31 |
| 저장소 | `AppearanceRequestRepositoryTest` | `appearances.requests` 저장, 진행 중 요청 부분 유니크 인덱스, 목록 조회 | #98 |
| 통합 | `AppearanceModuleIntegrationTest` | 모듈 전체 기동 | #26 |
| 통합 | `AppearanceUserFacadeTest` | 출연 회원 생성·갱신(Facade), 자격 확인(Support) | #31, #50, #98 |
| 통합 | `AppearanceUserEventListenerTest` | 회원 가입·정보 변경 이벤트 수신 | #31, #50 |
| E2E | `AppearanceApiE2ETest` | 세션 로그인 → 자격 확인 → 오류 응답, 회원 정지 반영 (앱 전체) | #31, #50 |
| 통합 | `AppearanceSwaggerDocsTest` | Swagger 명세의 성공 응답 스키마가 `RsData`인지 | #144 |
| 통합 | `AppearanceRequestFacadeTest` | 요청서 보내기(HP 보류·잔액 부족·무응답·중복·동시 요청), 세션 역할(`@PreAuthorize`), 출연 회원 자격, 조회, 수정(멱등) | #98 |
| E2E | `AppearanceRequestApiE2ETest` | 출연 요청서 API 5개: 세션 로그인·CSRF·역할 → 응답(`RsData`)·오류 형식 (앱 전체) | #98, #144 |
| 단위 | `AppearanceRequestResponseTest` | 수락·거절·응답 전 취소 규칙 (행위자·상태·기한·버전·사유) | #137 |
| 단위 | `AppearanceTest` | 출연 성립과 스냅샷 | #137 |
| 단위 | `HpTaskTest` | HP 작업 생성·성공·실패 기록 | #137 |
| 저장소 | `AppearanceRepositoryTest` | `appearances.appearances` 저장, 요청서 하나에 출연 하나 | #137 |
| 저장소 | `HpTaskRepositoryTest` | `appearances.hp_tasks` 저장, 멱등 키 유니크, 대상별 최근 작업 | #137 |
| 통합 | `AppearanceRequestRespondFacadeTest` | 수락·거절·취소, 반환 성공·실패, 기한 경과, 동시 처리, 상태 필터, 역할 | #137 |
| E2E | `AppearanceRequestRespondApiE2ETest` | 수락·거절·취소 API와 상태 필터 (앱 전체) | #137 |
| 단위 | `AppearanceRecruitTest` | 모집글 생성 검증(모집 인원·출연료·마감 일시)·보류 금액·상태 전이·수정·공개 여부 | #175 |
| 저장소 | `AppearanceRecruitRepositoryTest` | `appearances.recruits` 저장, 공개 목록·내 목록 조회 | #175 |
| 통합 | `AppearanceRecruitFacadeTest` | 올리기(HP 보류·Billing 거절·무응답), 역할별 권한, 조회, 수정 | #175 |
| E2E | `AppearanceRecruitApiE2ETest` | 출연 모집글 API 5개 (앱 전체) | #175 |
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
| 모집 인원 0 / 1 / 10 / 11명 (#175) | 불가 / 허용 / 허용 / 불가 |
| 최저 출연료 0, 위약금 101%, 확인 기간 0, 최대 횟수 0, 최대 모집 인원 0 | 정책 객체 생성 실패 (앱이 시작되지 않음) |

### 3.2 설정 — `AppearanceConfigTest`

테스트용 `application.yaml`의 `spring.config.import`로 `appearance.yml`을 실제 앱과 같은 방식으로 읽는다.

| 케이스 | 기대 결과 |
|---|---|
| 기본 설정 | 최저 출연료 20,000 / 위약금 30% / 확인 기간 7일 / 취소 요청 최대 2회 / 최대 모집 인원 10명 |
| 설정 우선순위: 외부 설정으로 최저 출연료 30,000 | 30,000 (외부 설정이 우선). 우선순위 구조만 확인하며, 정책 값은 `appearance.yml`에서만 변경 |
| 출연 Clock | 시간대 Asia/Seoul |
| `appearance.billing.mode` 기본값 / `http` | Fake Billing 등록 / 미등록 |

### 3.3 Fake Billing — `FakeBillingClientTest` (#168에서 Billing 기준으로 다시 씀)

**Fake Billing 동작 규칙** (Billing 구현의 보류·반환·지급·위약금 UseCase를 따른다)

| # | 규칙 |
|---|---|
| F1 | 모든 회원은 지갑과 잔액 10,000,000 HP로 시작한다 (테스트에서 잔액·지갑 없음 변경 가능). 충전·수익 HP는 나누지 않는다 |
| F2 | 보류: 참조 대상당 1건. 잔액이 부족하면 보류를 만들지 않고 `INSUFFICIENT_BALANCE`(409) 예외 |
| F3 | 같은 대상·같은 회원·같은 금액으로 다시 보류하면 처음 보류를 돌려준다(`replayed`). 다른 회원·금액이면 `IDEMPOTENCY_KEY_CONFLICT` |
| F4 | 반환·지급·위약금은 요청 ID로 구분한다. 같은 요청 ID 재전송은 처음 결과(`replayed`), 같은 ID에 다른 내용이면 `IDEMPOTENCY_KEY_CONFLICT` |
| F5 | 보류 없음 `HOLD_NOT_FOUND`, 다른 회원의 보류 `HOLD_OWNER_MISMATCH`, 남은 보류 0에 새 요청 `HOLD_ALREADY_CLOSED`, 남은 보류 초과 `RELEASE_/PAYOUT_AMOUNT_EXCEEDS_HELD`·`PENALTY_BASE_EXCEEDS_HELD` |
| F6 | 지급: 자기 자신 `PAYOUT_TO_PAYER`, 원천 유형 불일치 `PAYOUT_SOURCE_MISMATCH`, 같은 원천(또는 출연 요청 보류)에 두 번째 지급 `PAYOUT_ALREADY_SETTLED` |
| F7 | 위약금: 출연이 계산한 반환액·위약금을 함께 옮긴다 (Billing은 비율 계산 안 함). 위약금 0이면 `PENALTY_AMOUNT_ZERO` |
| F8 | 금액 0 이하 `INVALID_POINT_AMOUNT`, 지갑 없음 `WALLET_NOT_FOUND` |
| F9 | 거절은 `FakeBillingException`(공통 업무 예외)이며 코드·HTTP 상태·메시지가 Billing과 같다 |
| F10 | 무응답 설정(`setUnavailable(true)`)이면 아무것도 처리하지 않고 `BillingUnavailableException`(업무 예외 아님) |
| F11 | 메모리에만 저장하므로 앱이 재시작되면 기록이 사라진다 |

| 케이스 | 기대 결과 | 규칙 |
|---|---|---|
| 잔액 50,000에서 30,000 보류 | 보류 30,000, `replayed` false, 잔액 20,000 | F2 |
| 잔액 10,000에서 30,000 보류 | `INSUFFICIENT_BALANCE`(409, "HP 잔액이 부족합니다."), 잔액·보류 변화 없음 | F2, F9 |
| 잔액 부족 뒤 잔액을 채워 다시 보류 | 새로 보류 | F2 |
| 같은 보류 두 번 / 다른 금액·다른 회원 | 처음 보류(`replayed`), 잔액 한 번만 빠짐 / `IDEMPOTENCY_KEY_CONFLICT` | F3 |
| 금액 0 / 지갑 없음 | `INVALID_POINT_AMOUNT` / `WALLET_NOT_FOUND` | F8 |
| 보류할 수 없는 참조 유형으로 요청 생성 | `IllegalArgumentException` (shared DTO 구조 검사) | — |
| 30,000 보류 후 전액 반환 / 같은 반환 요청 ID 재전송 | 호스트 잔액 50,000 / 처음 결과(`replayed`), 두 번 반환 안 함 | F4 |
| 10,000 부분 반환 후 전체 반환 | 남은 20,000 반환 | — |
| 반환: 보류 없음 / 다른 회원 / 30,001 / 끝난 보류에 새 ID / 같은 ID에 다른 금액 | `HOLD_NOT_FOUND` / `HOLD_OWNER_MISMATCH` / `RELEASE_AMOUNT_EXCEEDS_HELD` / `HOLD_ALREADY_CLOSED` / `IDEMPOTENCY_KEY_CONFLICT` | F4, F5 |
| 반환 요청 ID에 ':' | `IllegalArgumentException` | — |
| 30,000 지급 / 재전송 | 게스트 30,000, 보류 0 / `replayed` | F4 |
| 지급: 자기 자신 / 원천 `RECRUIT_APPEARANCE` / 30,001 / 지급 뒤 다른 출연으로 또 지급 | `PAYOUT_TO_PAYER` / `PAYOUT_SOURCE_MISMATCH` / `PAYOUT_AMOUNT_EXCEEDS_HELD` / `PAYOUT_ALREADY_SETTLED` | F5, F6 |
| 위약금 (반환 21,000 + 위약금 9,000) / 재전송 | 게스트 9,000, 호스트 41,000, 보류 0 / `replayed` | F7 |
| 위약금 0 / 합계 30,001 / 같은 취소 ID에 다른 금액 / 다른 취소 ID로 또 정산 | `PENALTY_AMOUNT_ZERO` / `PENALTY_BASE_EXCEEDS_HELD`(보류 그대로) / `IDEMPOTENCY_KEY_CONFLICT` / `PAYOUT_ALREADY_SETTLED` | F4, F6, F7 |
| 무응답 설정 후 보류 → 해제 후 보류 | `BillingUnavailableException`, 잔액 변화 없음 → 새로 보류 | F10 |

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
| 잔액 29,999에서 보내기 | Billing 오류 `INSUFFICIENT_BALANCE` 그대로(메시지 포함), 요청서 `HOLD_FAILED`, 잔액 그대로, 보낸 목록 0건 |
| 호스트 지갑 없음 (#168) | Billing 오류 `WALLET_NOT_FOUND` 그대로, 요청서 `HOLD_FAILED` |
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
성공 응답은 `RsData`라서 값은 `$.data.…`에서 확인하고, `statusCode`·`message`도 함께 확인한다 (#144).

| 케이스 | 기대 결과 |
|---|---|
| 보내기 → 보낸 목록 → 받은 목록 → 상세 → 수정 | HTTP·`statusCode` 201, `data.status` `REQUESTED` → `data.content[0]`·`data.page.totalElements` 1 → 게스트 목록에 호스트 ID → 상세 필드 일치 → 보낸 값만 바뀜. 단계마다 `message` 문구 확인 |
| 로그인하지 않고 목록 조회 | 401 (보안 필터) |
| 일반 유저 세션으로 목록 조회, 관리자 세션으로 보내기 | 403 Problem Detail, `code: FORBIDDEN` (Facade `@PreAuthorize`, 공통 핸들러) |
| 제목 공백·URL 형식 오류·출연료 누락 | 400, `code: INVALID_REQUEST`, `errors`에 `title`·`hostChannelUrl`·`fee` |
| 출연료 19,999 | 400 Problem Detail(`data` 없음), `code: FEE_BELOW_MINIMUM`, `detail`에 최저 출연료 |
| 잔액 10,000에서 30,000 요청 | 409, `code: INSUFFICIENT_BALANCE`, `detail: HP 잔액이 부족합니다.` (Billing 오류 그대로) |
| 응답 대기 중인 게스트에게 다시 보내기 | 409, `code: DUPLICATE_APPEARANCE_REQUEST` |
| Billing 무응답 | 503, `code: BILLING_UNAVAILABLE` |
| 다른 회원 상세 조회 / 게스트 수정 | 404 `APPEARANCE_REQUEST_NOT_FOUND` / 403 `NOT_REQUEST_HOST` |
| 빈 본문(`{}`)으로 수정 | 400, `code: INVALID_REQUEST` |
| CSRF 토큰 없이 보내기 | 403 (보안 필터) |

### 5.3 Swagger 명세 — `AppearanceSwaggerDocsTest`

`/v3/api-docs`를 요청해 명세의 응답 스키마가 실제 응답과 같은지 확인한다 (강의 모듈 `LectureSwaggerDocsTest`와 같은 방식).

| 케이스 | 기대 결과 |
|---|---|
| 보내기(201)·상세·수정의 응답 스키마 | `RsDataAppearanceRequestResDto` (`statusCode`, `message`, `data` → `AppearanceRequestResDto`) |
| 보낸·받은 목록의 응답 스키마 | `RsDataPageAppearanceRequestSummaryResDto`, `data` → `PagedModelAppearanceRequestSummaryResDto`(`content`, `page`) |
| 수락·거절·응답 전 취소의 응답 스키마 (#137) | `RsDataAppearanceRequestResDto` |

### 5.4 출연 요청 응답 API — `AppearanceRequestRespondApiE2ETest` (#137)

| 케이스 | 기대 결과 |
|---|---|
| 보내기 응답의 `version`으로 수락 | 200, "출연 요청을 수락했습니다.", `data.status` `ACCEPTED`, `data.appearanceId`, `data.respondedAt` |
| 호스트가 수정한 뒤 옛 `version`으로 수락 / `version` 없이 수락 | 409 `APPEARANCE_REQUEST_MODIFIED` / 400 `INVALID_REQUEST` |
| 사유와 함께 거절 | 200, `REJECTED`, `rejectReason`, `hpReleaseStatus` `DONE` |
| 사유 501자 / Billing 무응답에서 거절 | 400 / 200 `REJECTED` + `hpReleaseStatus` `PENDING` |
| 게스트가 취소 / 호스트가 응답 전 취소 | 403 `NOT_REQUEST_HOST` / 200 `CANCELED`, `DONE` |
| 거절한 요청서를 수락 | 409 `APPEARANCE_REQUEST_NOT_AWAITING_RESPONSE` |
| `status=REJECTED&status=CANCELED` / `status=REQUESTED` / `status=HOLD_FAILED` / `status=UNKNOWN` | 거절한 1건 / 0건 / 400 / 400 |
| 일반 유저 세션으로 수락 | 403 `FORBIDDEN` |

---

## 6. 모듈 경계 — `ModularityTests` (apps)

| 케이스 | 기대 결과 |
|---|---|
| 전체 모듈 구조 검증 | 다른 모듈의 내부 코드를 참조하지 않음 (출연 → `global`은 공개된 `auth`만 사용) |

---

## 7. 출연 요청 응답 (#137) 단위·저장소·통합 테스트

### 7.1 응답 규칙 — `AppearanceRequestResponseTest`

수락 기한: 보낸 시각 + 2일. 확인 순서: 행위자 → 상태 → 기한 → (수락) 버전.

| 케이스 | 기대 결과 |
|---|---|
| 게스트가 본 버전으로 기한 전 수락 | `ACCEPTED`, 응답 시각 기록 |
| 버전이 다름 / 호스트가 수락 / 기한과 같거나 지남 | `APPEARANCE_REQUEST_MODIFIED`(상태 그대로) / `NOT_REQUEST_GUEST` / `APPEARANCE_REQUEST_EXPIRED` |
| 사유와 함께 거절 / 사유 없이 거절 | `REJECTED` + 사유·응답 시각 / 사유 null |
| 사유 500자 / 501자 | 통과 / `INVALID_REQUEST` |
| 호스트가 기한 전 취소 / 게스트가 취소 / 기한 지난 취소 | `CANCELED` + 취소 시각 / `NOT_REQUEST_HOST`("…취소할 수 있습니다.") / `APPEARANCE_REQUEST_EXPIRED` |
| 거절한 요청서에 다시 거절·수락·취소 | `APPEARANCE_REQUEST_NOT_AWAITING_RESPONSE` |

### 7.2 출연 — `AppearanceTest`

| 케이스 | 기대 결과 |
|---|---|
| 수락된 요청서로 출연 생성 | `ESTABLISHED`, 출처 `REQUEST`, 출연료·방송 예정 일시는 요청서 값 그대로(스냅샷) |
| 수락되지 않은 요청서로 생성 | `IllegalStateException` (코드 오류) |

### 7.3 HP 작업 — `HpTaskTest`

| 케이스 | 기대 결과 |
|---|---|
| 반환 작업 생성 / 금액 0 | `PENDING`, 시도 0, 바로 실행 가능 / `IllegalArgumentException` |
| Billing 요청 ID 만들기 (`APPEARANCE_REQUEST`, 12, `RELEASE`, 1) (#168) | `APPEARANCE_REQUEST-12-RELEASE-1`, Billing 허용 문자만 |
| 성공 기록 | `DONE`, 시도 1, 완료 시각, 더는 실행 대상 아님 |
| 실패 기록 | `PENDING` 유지, 시도 1, 오류, 다음 시도 시각 전에는 실행 대상 아님 |
| 오류 1,500자 / 완료된 작업에 다시 기록 | 1,000자로 자름 / `IllegalStateException` |

### 7.4 저장소 — `AppearanceRepositoryTest`, `HpTaskRepositoryTest`

| 케이스 | 기대 결과 |
|---|---|
| 출연 저장 후 요청서 ID로 조회 | 스냅샷 값 일치, 버전 기록 |
| 같은 요청서로 출연 두 번 | `DataIntegrityViolationException` (`uk_appearances_request_id`) |
| 같은 멱등 키로 작업 두 번 | `DataIntegrityViolationException` (`uk_hp_tasks_idempotency_key`) |
| 대상별 최근 작업 | 가장 나중에 기록한 작업 |

### 7.5 흐름 — `AppearanceRequestRespondFacadeTest`

DB와 Fake Billing을 함께 쓴다. 세션 역할은 `@WithMockUser(roles = "INFLUENCER")`.

| 케이스 | 기대 결과 |
|---|---|
| 수락 | `ACCEPTED` + 출연 성립(스냅샷), 보류 30,000 그대로, HP 작업 없음 |
| 호스트가 수정한 뒤 옛 버전으로 수락 | `APPEARANCE_REQUEST_MODIFIED`, 출연 없음 (보내기 응답의 `version`이 DB와 같은지도 함께 확인) |
| 호스트가 수락 / 다른 회원이 수락 | `NOT_REQUEST_GUEST` / `APPEARANCE_REQUEST_NOT_FOUND` |
| 요청 뒤 호스트 정지 | `APPEARANCE_USER_NOT_ACTIVE` ("호스트가 현재 출연 거래를 할 수 없는 회원입니다."), 출연 없음 |
| 기한이 지났지만 아직 `REQUESTED` | 수락·거절·취소 모두 `APPEARANCE_REQUEST_EXPIRED`, 상태 그대로 |
| 세션 역할 USER | 수락·거절·취소 모두 `AccessDeniedException` |
| 사유와 함께 거절 | `REJECTED`, 반환 작업 1건(금액 30,000, 호스트, 멱등 키), `DONE`, 보류 0, 호스트 잔액 복구 |
| Billing 무응답에서 거절 | `REJECTED` 성공, 작업 `PENDING`, 시도 1, 오류에 `BillingUnavailableException`, 다음 시도 시각, 보류 그대로 |
| Billing이 반환을 거절(보류 없음)할 때 거절 (#168) | `REJECTED` 성공, 작업 `PENDING`, 오류 `HOLD_NOT_FOUND: HP 보류를 찾을 수 없습니다.` |
| 응답 전 취소 / 게스트가 취소 | `CANCELED` + `DONE`(사유 `REQUEST_CANCELED`) / `NOT_REQUEST_HOST`, 작업 없음 |
| 거절 후 다시 거절·취소 | `APPEARANCE_REQUEST_NOT_AWAITING_RESPONSE`, 작업은 1건뿐 |
| 수락과 취소를 동시에 | 하나만 성공, 다른 쪽은 `CONCURRENT_UPDATE` 또는 `APPEARANCE_REQUEST_NOT_AWAITING_RESPONSE`, 출연 + 작업 합계 1건 |
| 상태 필터 `REJECTED·CANCELED` / 생략 / `HOLD_FAILED` | 거절 1건 / 전체 최신순 / `INVALID_REQUEST` |
| 상세 | `version` 포함, 수락 후 `appearanceId`, 반환 상태 없음 |

---

## 8. 출연 모집글 (#175)

### 8.1 모집글 규칙 — `AppearanceRecruitTest`

| 케이스 | 기대 결과 |
|---|---|
| 올바른 값 | `HOLD_PENDING`, 성립 인원 0, 보류 금액 = 30,000 × 3 = 90,000 |
| 모집 인원 1명 / 10명 | 허용 |
| 모집 인원 0명 / 11명 | `RECRUIT_COUNT_OUT_OF_RANGE` ("모집 인원은 1명 이상 10명 이하여야 합니다.") |
| 1인당 출연료 19,999 / 20,000 | `FEE_BELOW_MINIMUM` ("1인당 출연료는 최저 출연료(20,000 HP) 이상…") / 허용 |
| 보류 금액이 long을 넘는 1인당 출연료 | `INVALID_REQUEST` |
| 마감 = 현재 / 마감 < 현재 / 마감 = 방송 예정 일시 | `INVALID_RECRUIT_SCHEDULE` |
| 빈 제목 / ftp URL / 예상 출연 시간 0 | `INVALID_REQUEST` (요청서와 같은 제한) |
| 보류 성공 / 거절 / `HOLD_PENDING`이 아닌데 반영 | `RECRUITING` / `HOLD_FAILED` / `IllegalStateException` |
| 호스트가 모집 중 수정 (null은 그대로) | 보낸 값만 바뀜 |
| 다른 회원이 수정 | `NOT_RECRUIT_HOST` |
| `HOLD_PENDING` 수정 / 모집 중이지만 마감 일시가 지남 | `APPEARANCE_RECRUIT_NOT_EDITABLE` |
| 형식이 틀린 값으로 수정 | `INVALID_REQUEST`, 아무것도 바뀌지 않음 |
| `HOLD_PENDING` 상세 확인 | `APPEARANCE_RECRUIT_NOT_FOUND` |

### 8.2 저장소 — `AppearanceRecruitRepositoryTest`

| 케이스 | 기대 결과 |
|---|---|
| 저장 후 조회 | 1인당 출연료·모집 인원·마감 일시 일치, 성립 인원 0, 마감 방식 null, 버전 기록 |
| 공개 목록 조회 | 모집 중이고 마감 일시가 기준 시각 이후인 것만 (마감 일시 지난 모집 중·보류 처리 중 제외) |
| 내 목록 조회 | 호스트·상태로 거름 |

### 8.3 흐름 — `AppearanceRecruitFacadeTest`

DB와 Fake Billing을 함께 쓴다. 세션 역할은 `@WithMockUser(roles = "INFLUENCER")`.

| 케이스 | 기대 결과 |
|---|---|
| 올리기 | `RECRUITING`, 성립 인원 0, 응답 버전 = DB, Fake 보류(`APPEARANCE_RECRUIT`, 모집글 ID) 90,000, 호스트 잔액 −90,000 |
| 같은 호스트가 두 번 올리기 | 2건 저장 (중복 제한 없음) |
| 잔액 89,999 | Billing 오류 `INSUFFICIENT_BALANCE` 그대로, `HOLD_FAILED`, 내 목록·공개 목록 0건 |
| Billing 무응답 | `BILLING_UNAVAILABLE` ("…내 모집글 목록에서 결과를 확인해 주세요."), `HOLD_PENDING` |
| 모집 인원 11명 | `RECRUIT_COUNT_OUT_OF_RANGE`, 저장·보류 없음 |
| 정지된 호스트 | `APPEARANCE_USER_NOT_ACTIVE` |
| 세션 역할 USER | 올리기·수정·내 목록은 `AccessDeniedException`, 공개 목록·상세는 조회 가능 |
| 공개 목록 | 모든 호스트의 모집 중 모집글, 최신순 |
| 내 목록 / `CLOSED` 필터 / `HOLD_FAILED` 필터 | 내 글만 / 0건 / `INVALID_REQUEST` |
| 상세 / 보류 처리 중 / 없는 ID | 다른 회원도 조회 / `APPEARANCE_RECRUIT_NOT_FOUND` / `APPEARANCE_RECRUIT_NOT_FOUND` |
| 수정 / 같은 수정 반복 | 보낸 값만 바뀌고 버전 증가 / 버전 그대로 (멱등) |
| 다른 회원이 수정 / 없는 모집글 | `NOT_RECRUIT_HOST` / `APPEARANCE_RECRUIT_NOT_FOUND` |

### 8.4 E2E — `AppearanceRecruitApiE2ETest`

| 케이스 | 기대 결과 |
|---|---|
| 올리기 | 201, "출연 모집글을 올렸습니다.", `RECRUITING`, `feePerPerson`, `recruitCount`, `selectedCount` 0, `version` |
| 모집 인원 11명 / 모집 인원 누락 | 400 `RECRUIT_COUNT_OUT_OF_RANGE` / 400 `INVALID_REQUEST` |
| 잔액 부족 | 409 `INSUFFICIENT_BALANCE` |
| 일반 유저(USER) 공개 목록·상세 / 올리기 | 200 / 403 `FORBIDDEN` |
| 비로그인 공개 목록 | 401 (비회원 공개는 팀 합의 후) |
| 내 목록 `status=RECRUITING` / `status=HOLD_PENDING` | 내 글 1건 / 400 `INVALID_REQUEST` |
| 호스트 수정 / 다른 회원 수정 / 빈 수정 | 200 "출연 모집글을 수정했습니다." / 403 `NOT_RECRUIT_HOST` / 400 |
| 없는 모집글 상세 | 404 `APPEARANCE_RECRUIT_NOT_FOUND` |
| Swagger (`AppearanceSwaggerDocsTest`) | 올리기·상세·수정은 `RsDataAppearanceRecruitResDto`, 공개·내 목록은 `RsDataPageAppearanceRecruitSummaryResDto` → `PagedModelAppearanceRecruitSummaryResDto` |
