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
| 단위 | `FakeBillingClientTest` | Fake Billing 동작 규칙 | #26, #168 |
| 단위 | `BillingClientModeTest` | `appearance.billing.mode`에 따른 Billing 구현 선택 (Billing 담당 추가) | #182 |
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
| 단위 | `AppearanceBasicInfoTest` | 요청서·모집글 공통 기본 정보: 형식 검증, 보낸 값만 바꾼 새 객체 | #175 |
| 단위 | `AppearanceRecruitTest` | 모집글 생성 검증(모집 인원·출연료·마감 일시)·보류 금액·상태 전이·수정·공개 여부, 마감·돌려줄 금액 | #175, #222 |
| 저장소 | `AppearanceRecruitRepositoryTest` | `appearances.recruits` 저장, 공개 목록·내 목록 조회 | #175 |
| 통합 | `AppearanceRecruitFacadeTest` | 올리기(HP 보류·Billing 거절·무응답), 역할별 권한, 조회, 수정 | #175 |
| E2E | `AppearanceRecruitApiE2ETest` | 출연 모집글 API 6개 (앱 전체, 조기 마감 포함) | #175, #222 |
| 통합 | `AppearanceRecruitCloseTest` | 조기 마감·기한 마감 배치·반환·동시성 | #222 |
| 단위·저장소·통합·E2E | `AppearanceCancelRequestTest`, `AppearanceCancelRequestRepositoryTest`, `AppearanceCancelRequestFlowTest`, `AppearanceApiCancelE2ETest` | 출연 조회, 출연 취소 요청·응답·자동 거절 | #231 |
| 단위·통합 | `AppearanceHostCancelTest`, `AppearanceHostCancelFlowTest` (+ E2E·Swagger·`HpTaskRepositoryTest` 추가) | 호스트 일방 취소·모집 전체 취소·위약금 정산 | #239 |
| 단위 | `HpTaskRetryPolicyTest`, `AppearanceBatchRulesTest` | 재시도 정책(지수 백오프+지터), 만료·멱등한 보류 결과 반영 | #187 |
| 통합 | `AppearanceBatchUseCaseTest` | 보류 재확인, 수락 기한 만료, HP 작업 재시도·격리, 지표 | #187 |
| 단위 | `AppearanceBatchConditionTest` | 배치 빈은 팀 스케줄링 스위치가 켜질 때만 | #187 |
| 단위 | `AppearanceApplicationTest` | 모집 지원·취소·수락·거절, 모집글 쪽 확인, 모집 출연 성립 | #195 |
| 저장소 | `AppearanceApplicationRepositoryTest` | `appearances.applications` 저장, 재지원 유니크, 지원자 수 | #195 |
| 통합 | `AppearanceApplicationFacadeTest` | 지원·취소·수락·거절·조회, 동시 지원·동시 수락 | #195 |
| E2E | `AppearanceApplicationApiE2ETest` | 출연 모집 지원 API 6개 (앱 전체) | #195 |
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
| `appearance.billing.mode` 기본값 / 잘못된 값(`http`) | Fake Billing 등록 / 미등록 |

### 3.2-1 Billing 구현 선택 — `BillingClientModeTest` (Billing 담당 추가, #182)

| 케이스 | 기대 결과 |
|---|---|
| `mode` 없음 / `fake` | `FakeBillingClient`만 등록 (기본값) |
| `billing-port` | `BillingPortClient`만 등록, shared `BillingPort`로 위임 |
| 잘못된 값(`billingport`, `billing_port`, `http`, 빈 값) | 어느 구현도 등록되지 않음 (조용히 실제·가짜로 바뀌지 않음) |
| `BILLING-PORT` / `FAKE` | 대소문자 구분 없이 같은 값 |

- apps `AppearanceBillingPortIntegrationTest`(Billing 담당 추가, #182)는 `billing-port`로 실제 Billing과 연동되는지 확인한다.

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

### 8.5 기본 정보 값 객체 — `AppearanceBasicInfoTest` (#175 리팩터링)

| 케이스 | 기대 결과 |
|---|---|
| 형식이 맞는 값 | 생성 |
| 제목 공백·101자 / ftp URL / 설명 5,001자 | `INVALID_REQUEST` |
| 제목만 바꾸기 | 제목만 바뀐 새 객체, 원래 객체는 그대로 |
| 바꾸려는 URL 형식 오류 | `INVALID_REQUEST` |
| (설정) `AppearanceConfigTest` | 도메인 `AppearancePolicy`에 어노테이션 없음, `AppearancePolicyProperties`·`AppearancePolicy` 빈 각 1개 |

---

## 9. 출연 배치 (#187)

### 9.1 재시도 정책 — `HpTaskRetryPolicyTest`

| 케이스 | 기대 결과 |
|---|---|
| 1·2·3·6·7·40번째 시도 (지터 0) | 1분·2분·4분·32분·1시간·1시간 뒤 |
| 난수 0.5 | 간격의 10% 추가 (1분 → 66초, 1시간 → 66분) |
| 9번째 / 10번째 시도 | 남음 / 다 씀 |
| 기본 간격 0, 최대 < 기본, 횟수 0, 지터 1.5 | 생성 실패 |

### 9.2 도메인 — `HpTaskTest`, `AppearanceBatchRulesTest`

| 케이스 | 기대 결과 |
|---|---|
| 결과를 알 수 없는 실패 (최대 3회 정책) | 1회 후 1분 뒤, 2회 후 2분 뒤, 3회째 `DEAD`(완료 시각 기록, 실행 대상 아님) |
| Billing 거절 기록 | 바로 `DEAD`, 시도 1, 오류 기록, 이후 결과 기록 불가 |
| 수락 기한 1초 전 / 기한과 같음 | 만료 대상 아님 / 대상, 만료하면 `EXPIRED` |
| 기한 전 만료 / 거절된 요청서 만료 | `IllegalStateException` |
| 요청서 `markRequested` 두 번 / `HOLD_FAILED` 두 번 | 그대로(멱등) / 그대로, 이후 `markRequested`는 예외 |
| 모집글 `markRecruiting` 두 번 | 그대로, 이후 `markHoldFailed`는 예외 |
| (변경) `AppearanceRequestTest` 이미 `REQUESTED`에 다시 반영 | 같은 결과는 그대로, 다른 결과는 예외 |

### 9.3 저장소

| 테스트 | 케이스 | 기대 결과 |
|---|---|---|
| `HpTaskRepositoryTest` | 재시도 대상 조회 | 다음 시도 시각 전 제외, 처음 시도 전 작업은 대기 시간 지나야 포함, 완료 제외, 다음 시도 시각 순, 건수 제한, 상태별 개수 |
| `AppearanceRequestRepositoryTest` | 만료 대상 / 보류 재확인 대상 | 수락 기한과 같거나 지난 `REQUESTED` / 기준 시각 이전에 만든 `HOLD_PENDING` |
| `AppearanceRecruitRepositoryTest` | 보류 재확인 대상 | 기준 시각 이전에 만든 `HOLD_PENDING` 모집글만 |

### 9.4 흐름 — `AppearanceBatchUseCaseTest`

DB와 Fake Billing을 쓴다. 시각 조건은 DB의 만든 시각·다음 시도 시각을 과거로 옮겨 만든다.

| 케이스 | 기대 결과 |
|---|---|
| 1분 넘게 `HOLD_PENDING`인 요청서 재확인 | `REQUESTED`, 보류 30,000 |
| Billing이 처음 요청을 이미 처리한 경우 | `REQUESTED`, HP는 한 번만 빠짐 |
| 재확인 때 잔액 부족 | `HOLD_FAILED` |
| 1분이 안 됨 / 여전히 Billing 무응답 | 그대로 `HOLD_PENDING` (정리 0건) |
| 모집글 재확인 | `RECRUITING`, 보류 90,000 |
| 수락 기한이 지난 `HOLD_PENDING` (S5) | 재확인 → `REQUESTED` → 만료 → `EXPIRED` + 반환 `DONE`, 잔액 복구 |
| 수락 기한이 지난 `REQUESTED` 만료 | `EXPIRED`, 작업 사유 `REQUEST_EXPIRED` `DONE`, 보류 0, 상세 `hpReleaseStatus` `DONE` |
| 수락 기한 전 | 그대로 `REQUESTED`, 작업 없음 |
| 만료 때 반환 실패 / 다시 실행 | `EXPIRED` 확정, 작업 `PENDING` 시도 1 / 만료 0건 |
| 반환 실패 작업: 다음 시도 시각 전 / 후 | 실행 안 함 / `DONE`, 시도 2, 잔액 복구, 지표 done +1 |
| 계속 실패 | 2~9회는 `PENDING`(마지막 간격 약 1시간), 10회째 `DEAD`, 지표 격리 수 1 |
| 재시도 때 Billing 거절 (보류 없음) | 바로 `DEAD`, `HOLD_NOT_FOUND: …` |
| 처음 시도 전 작업: 만든 직후 / 1분 뒤 | 가져가지 않음 / 실행 |
| (변경) `AppearanceRequestRespondFacadeTest` 거절 때 Billing 반환 거절 | 작업 `PENDING` → **`DEAD`** |

### 9.5 배치 빈 — `AppearanceBatchConditionTest`

| 케이스 | 기대 결과 |
|---|---|
| `hapbang.scheduling.enabled` 없음 / `false` | 배치 빈 없음 |
| `true` | 배치 빈 1개 |

---

## 10. 출연 모집 지원과 수락 (#195)

### 10.1 구성

| 종류 | 테스트 | 확인 대상 |
|---|---|---|
| 단위 | `AppearanceApplicationTest` | 지원·취소·수락·거절 규칙, 모집글 쪽 확인(호스트·모집 중·정원), 모집 출연 성립 |
| 저장소 | `AppearanceApplicationRepositoryTest` | 저장, 재지원 유니크, 지원자 수(취소 제외·묶음 조회), 목록, 지원당 출연 1건 |
| 통합 | `AppearanceApplicationFacadeTest` | 지원(동시 지원 포함)·취소·수락(정원·자격·동시 수락)·거절·조회·지원자 수, 역할 |
| E2E | `AppearanceApplicationApiE2ETest` | API 6개, RsData, 400/403/404/409, 공개 목록 지원자 수 |
| 통합 | `AppearanceSwaggerDocsTest` (추가) | 지원 API 응답 스키마, 모집글 요약의 `applicantCount` |

### 10.2 단위 — `AppearanceApplicationTest`

| 묶음 | 케이스 | 기대 결과 |
|---|---|---|
| 지원 | 모집 중·마감 전 / 메시지 없음 | `APPLIED`, 메시지 저장 / 메시지 null |
| 지원 | 메시지 500자 / 501자 | 허용 / `INVALID_REQUEST` |
| 지원 | 자기 모집글 | `CANNOT_APPLY_OWN_RECRUIT` |
| 지원 | 보류 처리 중 모집글 / 마감 일시와 같은 시각 | `APPEARANCE_RECRUIT_NOT_OPEN` |
| 지원 | 정원이 찬 모집글 | `APPLIED` (지원은 받음) |
| 취소 | 지원한 게스트가 대기 중 취소 | `WITHDRAWN` + 취소 시각 |
| 취소 | 다른 회원 / 이미 취소 / 이미 수락 | `NOT_APPLICATION_GUEST` / `APPLICATION_NOT_PENDING` / `APPLICATION_NOT_PENDING` |
| 응답 | 수락 / 거절 | `SELECTED` / `NOT_SELECTED` + 응답 시각 |
| 응답 | 취소된 지원 수락 / 거절된 지원 다시 거절 | `APPLICATION_NOT_PENDING` |
| 모집글 | 다른 회원이 응답 / 마감 일시 지남 | `NOT_RECRUIT_HOST`("…지원에 응답할 수 있습니다.") / `APPEARANCE_RECRUIT_NOT_OPEN` |
| 모집글 | 정원 2명에 성립 2번 → 3번째 | 성립 2, 가득 참, `RECRUIT_FULL`, 상태는 `RECRUITING` 그대로 |
| 출연 | 수락된 지원으로 성립 | 출처 `RECRUIT`, 호스트·게스트, 출연료 30,000(1인당), 방송 예정 일시 스냅샷, 요청서 ID 없음, `ESTABLISHED` |
| 출연 | 수락 안 된 지원으로 성립 | `IllegalStateException` (코드 오류) |

### 10.3 저장소 — `AppearanceApplicationRepositoryTest`

| 케이스 | 기대 결과 |
|---|---|
| 저장 후 조회 | `APPLIED`, 메시지, 버전·생성 시각 |
| 취소한 뒤 같은 게스트가 다시 저장 | `DataIntegrityViolationException` (`uk_appearance_applications_recruit_guest`), 지원 여부 조회는 상태와 무관하게 true |
| 지원자 수: 대기·미선정·취소 각 1건 | 2 (취소 제외). 여러 모집글 묶음 조회에서 지원 없는 모집글은 결과에 없음 |
| 모집글별 / 게스트별 목록 | 모집글의 2건 / 내 지원 최신순, `SELECTED` 필터는 0건 |
| 같은 지원으로 출연 두 번 | `DataIntegrityViolationException`, 지원 ID로 출연 조회 |

### 10.4 흐름 — `AppearanceApplicationFacadeTest`

DB와 Fake Billing을 쓴다. 세션 역할은 `@WithMockUser(roles = "INFLUENCER")`, 동시 처리 테스트는 스레드마다 인플루언서 인증을 넣는다.

| 묶음 | 케이스 | 기대 결과 |
|---|---|---|
| 지원 | 지원 | `APPLIED`, 메시지, 출연 ID 없음, 모집글 지원자 수 1 |
| 지원 | 다시 지원 / 취소 후 다시 지원 | `DUPLICATE_APPLICATION` |
| 지원 | 같은 게스트가 동시에 5번 | 1건 저장, 4건 `DUPLICATE_APPLICATION` |
| 지원 | 자기 모집글 / 없는 모집글 / 마감 일시 지남 | `CANNOT_APPLY_OWN_RECRUIT` / `APPEARANCE_RECRUIT_NOT_FOUND` / `APPEARANCE_RECRUIT_NOT_OPEN` |
| 지원 | 보류 처리 중 모집글 | `APPEARANCE_RECRUIT_NOT_FOUND` (목록에 없는 모집글) |
| 지원 | 정원 1명이 찬 뒤 | 지원 `APPLIED` |
| 지원 | 정지된 게스트 | `APPEARANCE_USER_NOT_ACTIVE` |
| 취소 | 취소 | `WITHDRAWN`, 지원자 수 0 |
| 취소 | 다른 회원 / 수락된 지원 / 없는 지원 | `NOT_APPLICATION_GUEST` / `APPLICATION_NOT_PENDING` / `APPLICATION_NOT_FOUND` |
| 취소 | 게스트 취소와 호스트 수락 동시 | 1건 성공, 1건 `CONCURRENT_UPDATE` 또는 `APPLICATION_NOT_PENDING`. 취소됐으면 성립 인원 0·출연 없음, 수락됐으면 성립 인원 1·출연 1건 |
| 수락 | 수락 | `SELECTED`, 출연(출처 `RECRUIT`, 지원·모집글 ID, 게스트, 30,000), 성립 인원 1, 지원자 수 1, 보류 90,000 그대로, 잔액 변화 없음 |
| 수락 | 정원 1명이 찬 뒤 두 번째 수락 | `RECRUIT_FULL`, 모집글 `RECRUITING`, 출연 1건 |
| 수락 | 다른 회원 / 거절된 지원 / 마감 일시 지남 | `NOT_RECRUIT_HOST` / `APPLICATION_NOT_PENDING` / `APPEARANCE_RECRUIT_NOT_OPEN`, 출연 없음 |
| 수락 | 지원 뒤 게스트 정지 | `APPEARANCE_USER_NOT_ACTIVE`, 성립 인원 0, 지원 `APPLIED` 그대로, 출연 없음 |
| 수락 | 마지막 한 자리에 지원 3건 동시 수락 | 1건 성공, 2건 `CONCURRENT_UPDATE` 또는 `RECRUIT_FULL`, 성립 인원 1, 출연 1건 |
| 거절 | 거절 | `NOT_SELECTED`, 지원자 수에 남음, 그 게스트 재지원 `DUPLICATE_APPLICATION` |
| 거절 | 다른 회원 | `NOT_RECRUIT_HOST` |
| 조회 | 지원자 목록 | 최신순, 수락된 지원만 출연 ID, `APPLIED` 필터, 호스트가 아니면 "…지원자 목록을 볼 수 있습니다." |
| 조회 | 내 지원 목록 | 내 지원만 최신순, `WITHDRAWN` 필터 |
| 조회 | 모집글 지원자 수 (수락·거절·취소 각 1) | 상세 2, 공개 목록·내 모집글 목록도 2, 지원 없는 모집글 0 |
| 역할 | 세션 역할 USER / ADMIN | 지원·취소·수락·거절·지원자 목록·내 지원 목록 모두 `AccessDeniedException` |

### 10.5 E2E — `AppearanceApplicationApiE2ETest`

| 케이스 | 기대 결과 |
|---|---|
| 지원 / 다시 지원 | 201 "출연 모집에 지원했습니다.", `APPLIED`, 모집글 ID, 메시지 / 409 `DUPLICATE_APPLICATION` |
| 요청 본문 없이 지원 (메시지 선택) | 201, `APPLIED`, 메시지 없음 |
| 메시지 501자 / 자기 모집글 / 일반 유저 | 400 `INVALID_REQUEST` / 400 `CANNOT_APPLY_OWN_RECRUIT` / 403 `FORBIDDEN` |
| 정원 1명: 첫 수락 / 두 번째 수락 / 모집글 상세 | 200 "지원을 수락했습니다.", `SELECTED`, 출연 ID / 409 `RECRUIT_FULL` / `RECRUITING`, 성립 1, 지원자 2 |
| 다른 회원 수락 / 호스트 거절 | 403 `NOT_RECRUIT_HOST` / 200 "지원을 거절했습니다.", `NOT_SELECTED` |
| 다른 회원 취소 / 취소 / 다시 취소 | 403 `NOT_APPLICATION_GUEST` / 200 "지원을 취소했습니다.", `WITHDRAWN` / 409 `APPLICATION_NOT_PENDING` |
| 지원자 목록(호스트, `status=APPLIED`) / 게스트가 지원자 목록 | 200 "지원자 목록을 조회했습니다.", 2건 / 403 `NOT_RECRUIT_HOST` |
| 내 지원 목록 / `status=UNKNOWN` | 200 "내 지원 목록을 조회했습니다.", 내 것 1건 / 400 `INVALID_REQUEST` |
| 일반 유저의 공개 모집글 목록 | `applicantCount` 1 |
| 없는 지원 수락 | 404 `APPLICATION_NOT_FOUND` |

---

## 11. 출연 모집 마감 (#222)

### 11.1 구성

| 종류 | 테스트 | 확인 대상 |
|---|---|---|
| 단위 | `AppearanceRecruitTest` (모집 마감) | 조기 마감·기한 마감 조건, 마감 방식·시각, 돌려줄 금액 |
| 단위 | `AppearanceApplicationTest` (추가) | 마감 때 대기 지원 미선정 정리 |
| 저장소 | `AppearanceRecruitRepositoryTest`, `AppearanceApplicationRepositoryTest` (추가) | 기한 마감 대상 조회, 모집글의 대기 지원 조회 |
| 통합 | `AppearanceRecruitCloseTest` | 조기 마감(0명·일부·정원·반환 실패·거부·마감 뒤·동시 수락), 기한 마감 배치(보류 처리 중 흐름 포함), 역할 |
| E2E | `AppearanceRecruitApiE2ETest` (추가) | 조기 마감 API, 반환 진행 상태 호스트만, 공개 목록에서 빠짐 |
| 통합 | `AppearanceSwaggerDocsTest`, `AppearanceBatchConditionTest` (변경) | 조기 마감 응답 스키마, 배치 빈에 마감 UseCase 추가 |

### 11.2 단위

| 묶음 | 케이스 | 기대 결과 |
|---|---|---|
| 조기 마감 | 호스트, 모집 중, 마감 일시 전 (성립 0명) | `CLOSED`, `EARLY`, 마감 시각 = 지금 |
| 조기 마감 | 다른 회원 / 마감 일시와 같은 시각 / 이미 마감 / 보류 처리 중 | `NOT_RECRUIT_HOST` / `APPEARANCE_RECRUIT_NOT_OPEN` ×3 |
| 기한 마감 대상 | 마감 일시 1초 전 / 마감 일시 / 보류 처리 중 / 이미 마감 | 아님 / 대상 / 아님 / 아님 |
| 기한 마감 | 대상 아님 / 마감 일시 30초 뒤 처리 | `IllegalStateException` / `CLOSED`, `DEADLINE`, 마감 시각 = 모집 마감 일시 |
| 돌려줄 금액 | 30,000 × 3명에 성립 0 / 1 / 3 | 90,000 / 60,000 / 0 |
| 지원 정리 | 대기 중 / 수락됨 / 취소됨 | `NOT_SELECTED`, 응답 시각 없음 / `APPLICATION_NOT_PENDING` / `APPLICATION_NOT_PENDING` |

### 11.3 저장소

| 케이스 | 기대 결과 |
|---|---|
| 기한 마감 대상: 마감 일시 지남 2건(마감 일시 다름)·아직 1건·조기 마감됨 1건 | 지난 2건만, 마감 일시 이른 순. 기준 시각을 1초 당기면 1건 |
| 모집글의 대기 지원: 대기 1·수락 1, 다른 모집글 대기 1 | 이 모집글의 대기 1건만 |

### 11.4 흐름 — `AppearanceRecruitCloseTest`

| 묶음 | 케이스 | 기대 결과 |
|---|---|---|
| 조기 마감 | 성립 0명, 대기 지원 1 | `CLOSED`(`EARLY`), 대기 → `NOT_SELECTED`, 반환 작업 `RECRUIT_CLOSED` 90,000 `DONE`, 멱등 키 `APPEARANCE_RECRUIT-{id}-RELEASE-1`, 보류 0, 호스트 잔액 원래대로 |
| 조기 마감 | 수락 1·대기 1·취소 1 | 60,000 반환, 보류 30,000 남음, `SELECTED`·`NOT_SELECTED`·`WITHDRAWN`, 지원자 수 2 그대로, 출연 1건 유지 |
| 조기 마감 | 정원 1명이 찬 뒤 | `CLOSED`, 반환 작업 없음, `hpReleaseStatus` null, 보류 30,000 그대로 |
| 조기 마감 | 상세를 호스트 / 다른 회원이 조회 | `hpReleaseStatus` `DONE` / null |
| 조기 마감 | Billing 무응답 → 재시도 배치 | 마감 성공 + `PENDING`, 보류 그대로 → 재시도 후 `DONE`, 보류 0 |
| 조기 마감 | 다른 회원 / 다시 마감 / 마감 일시 지남 / 보류 처리 중 | `NOT_RECRUIT_HOST` / `APPEARANCE_RECRUIT_NOT_OPEN` / `APPEARANCE_RECRUIT_NOT_OPEN`(반환 작업 없음) / `APPEARANCE_RECRUIT_NOT_FOUND` |
| 마감 뒤 | 새 지원 / 대기 지원 수락 / 미선정 지원 취소 | `APPEARANCE_RECRUIT_NOT_OPEN` / `APPEARANCE_RECRUIT_NOT_OPEN` / `APPLICATION_NOT_PENDING` |
| 동시성 | 조기 마감과 마지막 대기 지원 수락 동시 | 결과는 모두 응답 또는 업무 예외(교착 상태·500 원인 없음). 실패는 많아야 1건(`CONCURRENT_UPDATE` 또는 `APPEARANCE_RECRUIT_NOT_OPEN`). 출연 수 = 성립 인원, 마감됐으면 보류 = 성립 × 30,000, 아니면 보류 90,000, 호스트 잔액 = 기본 − 보류 |
| 기한 마감 | 마감 일시 지남, 수락 1·대기 1 | 1건, `CLOSED`(`DEADLINE`), 마감 시각 = 모집 마감 일시, 대기 → `NOT_SELECTED`, 반환 `DONE`, 보류 30,000 |
| 기한 마감 | 마감 일시 전 1건 + 지남 1건, 두 번 실행 | 1건 → 0건, 전인 모집글은 `RECRUITING`, 반환 작업 1건 |
| 기한 마감 | 정원이 찬 모집글 | 마감 1건, 반환 작업 없음 |
| 기한 마감 | 마감 일시가 지난 `HOLD_PENDING` (C5) | 마감 배치만으로는 0건·작업 없음 → 보류 재확인 후 마감 배치 1건, `CLOSED`, 90,000 반환, 잔액 원래대로 |
| 동시성 | 조기 마감과 새 지원 2건 동시 (3회 반복, C9) | 마감은 성공, 지원은 성공 또는 `APPEARANCE_RECRUIT_NOT_OPEN`. 마감된 모집글에 `APPLIED` 지원 0건 |
| 기한 마감 | 대상 2건 중 1건 처리 중 충돌(`CONCURRENT_UPDATE`) (C8) | 1건만 마감, 충돌 건은 `RECRUITING`·반환 작업 없음(롤백) → 다음 실행에서 마감·반환 작업 1건 |
| 역할 | 세션 USER / ADMIN | `AccessDeniedException` |

### 11.5 E2E — `AppearanceRecruitApiE2ETest`

| 케이스 | 기대 결과 |
|---|---|
| 다른 회원 조기 마감 / 일반 유저 | 403 `NOT_RECRUIT_HOST` / 403 `FORBIDDEN` |
| 호스트 조기 마감 / 다시 마감 | 200 "출연 모집을 마감했습니다.", `CLOSED`, `EARLY`, 마감 시각, `hpReleaseStatus` `DONE` / 409 `APPEARANCE_RECRUIT_NOT_OPEN` |
| 일반 유저가 상세 / 공개 목록 | `CLOSED`, `hpReleaseStatus` 없음 / 0건 |

---

## 12. 출연 알림 이벤트 (#227)

| 종류 | 테스트 | 케이스 | 기대 결과 |
|---|---|---|---|
| 단위 | `AppearanceBatchRulesTest` | 보류 성공 반영: 처음 / 이미 `REQUESTED` | true(바뀜) / false(그대로) |
| 단위 | `AppearanceRequestTest` | 요청서 `toDto()` | 요청서 ID·호스트·게스트·제목·출연료·방송 예정 일시·수락 기한 |
| 단위 | `AppearanceApplicationTest` | 지원 `toDto(모집글)` / 다른 모집글 | 모집글 제목·호스트 포함 / `IllegalArgumentException` |
| 통합 | `AppearanceEventPublishTest` | 요청서 보내기 성공 | `AppearanceRequestSentEvent` 1건, 내용 일치 |
| 통합 | 〃 | Billing 보류 거절 | 0건 |
| 통합 | 〃 | 보류 결과 모름 → 보류 재확인 배치 | 0건 → 1건 (같은 요청서 ID) |
| 통합 | 〃 | 이미 `REQUESTED`인데 보류 성공을 다시 반영 | 1건 그대로 (중복 없음) |
| 통합 | 〃 | 지원 | `AppearanceApplicationSubmittedEvent` 1건, 내용 일치 |
| 통합 | 〃 | 다시 지원 / 자기 모집글 지원 | `DUPLICATE_APPLICATION` / `CANNOT_APPLY_OWN_RECRUIT`, 추가 발행 없음 |
| 통합 | 〃 | 모집글 올리기만 | 알림 이벤트 0건 |
| apps | `ModularityTests` | 출연 → shared 의존 | 통과 (shared는 boundedContext를 모름) |

---

## 13. 출연 조회와 출연 취소 요청 (#231)

### 13.1 구성

| 종류 | 테스트 | 확인 대상 |
|---|---|---|
| 단위 | `AppearanceCancelRequestTest` | 요청(사유·게스트·처리 중·방송 예정 일시·횟수·성립 상태), 수락·거절·자동 거절, 응답 가능 확인, 볼 수 있는 사람, 모집글 성립 인원 감소, 출연별 반환 요청 ID |
| 저장소 | `AppearanceCancelRequestRepositoryTest` | 저장·처리 중 요청·횟수·이력, 처리 중 요청 부분 유니크, 순번 유니크, 자동 거절 대상(조인), 출연 목록·출처 조회·잠금 조회 |
| 통합 | `AppearanceCancelRequestFlowTest` | 요청서 출처(요청·수락 반환·거절·횟수·거부·방송 예정 일시·반환 실패), 모집 출처(마감 전·마감 반환에 포함·마감 후 반환·동시 수락), 자동 거절 배치, 수락·거절 동시, 조회, 역할, 이벤트 |
| E2E | `AppearanceApiCancelE2ETest` | 조회(role 필수)·상세·다른 회원 404·일반 유저 403, 요청→거절→재요청→수락 흐름과 오류 코드, CSRF, 숫자 아닌 경로 |
| 통합 | `AppearanceSwaggerDocsTest`, `AppearanceBatchConditionTest` (변경) | 출연 API 응답 스키마, 배치에 자동 거절 추가 |

### 13.2 단위 — `AppearanceCancelRequestTest`

| 케이스 | 기대 결과 |
|---|---|
| 게스트가 성립 출연에 요청 (사유 앞뒤 공백) | `REQUESTED`, 순번 1, 사유 다듬음, 출연 `CANCEL_REQUESTED` |
| 사유 없음·공백·501자 / 500자 | `INVALID_REQUEST` (출연 상태 그대로) / 허용 |
| 호스트·다른 회원 요청 / 처리 중 다시 요청 | `NOT_APPEARANCE_GUEST` / `CANCEL_REQUEST_ALREADY_PENDING` |
| 방송 예정 일시 정각 / 1초 전 | `APPEARANCE_SCHEDULE_PASSED` / 허용 |
| 거절 후 재요청 / 3번째 | 순번 2 / `CANCEL_REQUEST_LIMIT_EXCEEDED` ("출연 1건당 2번까지") |
| 합의 취소된 출연에 요청 | `APPEARANCE_NOT_CANCELABLE` |
| 수락 / 거절(사유·공백) / 자동 거절 | `ACCEPTED`+`CANCELED_BY_AGREEMENT`+취소 시각 / `REJECTED`+사유(공백은 null)+`ESTABLISHED` / `AUTO_REJECTED`, 사유 없음 |
| 응답 가능 확인 | 요청 없음 `CANCEL_REQUEST_NOT_PENDING`, 게스트 `NOT_APPEARANCE_HOST`, 방송 예정 일시 `APPEARANCE_SCHEDULE_PASSED` |
| 응답된 요청에 다시 응답 | `CANCEL_REQUEST_NOT_PENDING` |
| 볼 수 있는 사람 | 호스트·게스트만, 그 밖 `APPEARANCE_NOT_FOUND` |
| 모집글 성립 인원 감소 | 1 → 0, 0에서 `IllegalStateException` |
| 출연별 반환 요청 ID | `APPEARANCE_RECRUIT-5-RELEASE-APPEARANCE-12`, 마감 반환 ID와 다름 |

### 13.3 흐름 — `AppearanceCancelRequestFlowTest`

| 묶음 | 케이스 | 기대 결과 |
|---|---|---|
| 요청서 출처 | 요청 | `CANCEL_REQUESTED`, 이력 1건, `AppearanceCancelRequestedEvent` 1건(호스트·출연·제목) |
| 요청서 출처 | 수락 | `CANCELED_BY_AGREEMENT`, 반환 30,000 `CANCEL_AGREED`·출연별 반환 요청 ID·`DONE`, 보류 0, 잔액 원래대로, 수락 이벤트 1건, 출연 상세 `hpReleaseStatus` `DONE`·요청서 상세는 null |
| 요청서 출처 | 거절 → 재요청 → 거절 → 3번째 | `ESTABLISHED`·거절 사유·거절 이벤트(`autoRejected=false`) → 순번 1·2 → `CANCEL_REQUEST_LIMIT_EXCEEDED`, HP 작업 없음 |
| 요청서 출처 | 거부 | 요청 없이 수락 `CANCEL_REQUEST_NOT_PENDING`, 공백 사유 `INVALID_REQUEST`, 호스트 요청 `NOT_APPEARANCE_GUEST`, 다른 회원 `APPEARANCE_NOT_FOUND`, 처리 중 재요청 `CANCEL_REQUEST_ALREADY_PENDING`, 게스트 수락 `NOT_APPEARANCE_HOST`, 없는 출연 `APPEARANCE_NOT_FOUND` |
| 요청서 출처 | 방송 예정 일시 지남 | 요청·수락·거절 `APPEARANCE_SCHEDULE_PASSED` |
| 요청서 출처 | Billing 무응답에서 수락 | 합의 취소 성공, 반환 작업 `PENDING` |
| 모집 출처 | 정원 1, 마감 전 수락 | 성립 인원 0, 반환 없음(출연 `hpReleaseStatus` null), 보류 30,000 유지, 빈자리에 다른 지원자 수락 성공 (전에는 `RECRUIT_FULL`) |
| 모집 출처 | 정원 3·성립 1, 마감 반환 실패(`PENDING`) 뒤 합의 취소 반환 성공 | 출연 상세 `DONE`, 모집글 상세는 마감 반환 `PENDING` 그대로 (가려지지 않음) |
| 모집 출처 | 마감 전 수락 후 조기 마감 | 마감 반환 `RECRUIT_CLOSED` 30,000 1건, 보류 0 |
| 모집 출처 | 마감 후 수락 | 반환 30,000 `CANCEL_AGREED`·`APPEARANCE_RECRUIT-{id}-RELEASE-APPEARANCE-{id}`·`DONE`, 보류 0, 성립 인원 0 |
| 모집 출처 | 합의 취소 수락 + 빈자리 지원 수락 동시 | 결과는 응답 또는 업무 예외뿐(교착 상태 없음), 합의 취소는 성공, 성립 인원 = 유지 중인 출연 수 |
| 자동 거절 | 방송 예정 일시 지난 요청, 두 번 실행 | 1건 → 0건, `AUTO_REJECTED`, 출연 `ESTABLISHED`, HP 작업 없음, 거절 이벤트(`autoRejected=true`) |
| 자동 거절 | 방송 예정 일시 전 | 0건, `CANCEL_REQUESTED` 그대로 |
| 동시성 | 같은 요청에 수락·거절 동시 | 1건만 성공, 나머지 `CANCEL_REQUEST_NOT_PENDING` 또는 `CONCURRENT_UPDATE`, 결과에 맞게 보류 0 또는 30,000 |
| 조회 | 내 출연 목록·상세 | 호스트 목록 최신순(모집·요청), 제목은 출처 제목, 게스트 `CANCEL_REQUESTED` 필터, 다른 쪽 역할 0건, 다른 회원 상세 `APPEARANCE_NOT_FOUND` |
| 역할 | 세션 USER / ADMIN | 5개 기능 모두 `AccessDeniedException` |

### 13.4 E2E — `AppearanceApiCancelE2ETest`

| 케이스 | 기대 결과 |
|---|---|
| `role=GUEST` 목록 / `role` 없음 | 200 "내 출연 목록을 조회했습니다.", 1건·제목 / 400 |
| 호스트 상세 / 다른 회원 / 일반 유저 | 200 `ESTABLISHED`·이력 없음 / 404 `APPEARANCE_NOT_FOUND` / 403 `FORBIDDEN` |
| 사유 없이 요청 / 요청 / 다시 요청 | 400 `INVALID_REQUEST` / 200 "출연 취소를 요청했습니다.", `CANCEL_REQUESTED` / 409 `CANCEL_REQUEST_ALREADY_PENDING` |
| 게스트 수락 / 호스트 거절(본문 없음) | 403 `NOT_APPEARANCE_HOST` / 200 "출연 취소 요청을 거절했습니다.", `ESTABLISHED`, `REJECTED` |
| 재요청 → 수락 / 3번째 요청 | 200 "출연 취소 요청을 수락했습니다.", `CANCELED_BY_AGREEMENT`, 이력 2번째 `ACCEPTED` / 409 `APPEARANCE_NOT_CANCELABLE` |
| CSRF 없음 / `/api/v1/appearances/abc` | 403 / 404 |

---

## 14. 호스트 일방 취소·모집 전체 취소 (#239)

### 14.1 단위 — `AppearanceHostCancelTest`

| 케이스 | 기대 결과 |
|---|---|
| 성립 출연 일방 취소 (30,000) | `CANCELED_BY_HOST`, 위약금 9,000, 사유 다듬음, 취소 시각 |
| 33,333 출연 / 공백 사유 | 위약금 9,999 (원 단위 미만 버림) / 사유 null |
| 게스트·다른 회원 / 방송 예정 일시 / 사유 501자 | `NOT_APPEARANCE_HOST` / `APPEARANCE_SCHEDULE_PASSED` / `INVALID_REQUEST` (상태 그대로) |
| 합의 취소된 출연 / 이미 일방 취소 | `APPEARANCE_NOT_CANCELABLE` |
| 처리 중 취소 요청, 확인 없음 / 확인 | `CANCEL_REQUEST_CONFIRM_REQUIRED`(상태 그대로) / 취소 + 요청 `CLOSED_BY_HOST_CANCEL` |
| 모집 중 3명 중 1명 성립 전체 취소 | `CANCELED`, 빈자리 60,000, 성립 인원 1 그대로, 사유·시각 |
| 마감 뒤 전체 취소 | 빈자리 0 |
| 다른 회원 / 방송 예정 일시 / 보류 처리 중 / 이미 취소 | `NOT_RECRUIT_HOST` / `APPEARANCE_SCHEDULE_PASSED` / `APPEARANCE_RECRUIT_NOT_CANCELABLE` ×2 |
| 위약금 정산 작업 | `PENALTY`, 기준액 30,000·위약금 9,000·반환 몫 21,000, 받는 회원·출연 ID. 위약금 0·기준액 초과는 `IllegalArgumentException` |

### 14.2 흐름 — `AppearanceHostCancelFlowTest`

| 묶음 | 케이스 | 기대 결과 |
|---|---|---|
| 일방 취소 | 요청서 출처 | `CANCELED_BY_HOST`, 위약금 9,000·사유·`hpPenaltyStatus` `DONE`, 작업 `PENALTY`·`HOST_CANCELED`·`APPEARANCE_REQUEST-{id}-PENALTY-APPEARANCE-{id}`, 보류 0, 호스트 −9,000·게스트 +9,000 |
| 일방 취소 | 처리 중 취소 요청 | 확인 없이 `CANCEL_REQUEST_CONFIRM_REQUIRED`(출연 그대로, HP 작업 없음) → 확인하면 취소 + `CLOSED_BY_HOST_CANCEL` + 위약금 지급 |
| 일방 취소 | 거부 | 게스트 `NOT_APPEARANCE_HOST`, 다른 회원 `APPEARANCE_NOT_FOUND`, 다시 취소·합의 취소된 출연 `APPEARANCE_NOT_CANCELABLE`, 방송 예정 일시 지남 `APPEARANCE_SCHEDULE_PASSED` |
| 일방 취소 | 모집 출처 (정원 1) | 모집글 보류 30,000 정산(0), 키 `APPEARANCE_RECRUIT-{id}-PENALTY-APPEARANCE-{id}`, 성립 인원 1 그대로, 대기 지원 수락 `RECRUIT_FULL` |
| 일방 취소 | Billing 무응답 → 재시도 배치 → 다시 실행 | 취소 성공·`PENDING`(게스트 잔액 그대로) → `DONE`, 위약금은 한 번만 지급(게스트 +9,000, 호스트 −9,000) |
| 일방 취소 | Billing 거절 (게스트 지갑 없음) | 취소 성공, 재시도 없이 `DEAD`(시도 1회), 마지막 오류 `WALLET_NOT_FOUND: …` |
| 전체 취소 | 모집 중, 3명 중 2명 성립·1명 대기 | `CANCELED`·사유·`hpReleaseStatus` `DONE`, 출연 2건 일방 취소(위약금 9,000씩·같은 사유), 대기 지원 `NOT_SELECTED`, 빈자리 반환 30,000 `RECRUIT_CANCELED`, 보류 0, 호스트 −18,000·게스트 +9,000씩 |
| 전체 취소 | 성립 0명 | 위약금 없음, 90,000 반환, 호스트 잔액 원래대로 |
| 전체 취소 | 마감 뒤 | 위약금 1건, 반환은 마감 반환(`RECRUIT_CLOSED`)뿐, 보류 0 |
| 전체 취소 | 모집 중 합의 취소된 자리 | 위약금 없음, 빈자리 반환 60,000 (합의 취소 자리 포함) |
| 전체 취소 | 처리 중 취소 요청 | 확인 없이 `CANCEL_REQUEST_CONFIRM_REQUIRED`(모집글 `RECRUITING`, 일방 취소 0건) → 확인하면 요청 `CLOSED_BY_HOST_CANCEL`, 위약금 2건 |
| 전체 취소 | 거부 | 다른 회원 `NOT_RECRUIT_HOST`, 다시 취소 `APPEARANCE_RECRUIT_NOT_CANCELABLE`, 방송 예정 일시 지남 `APPEARANCE_SCHEDULE_PASSED`, 보류 처리 중 `APPEARANCE_RECRUIT_NOT_FOUND` |
| 동시성 | 전체 취소 + 대기 지원 수락 | 결과는 응답·업무 예외뿐(교착 상태 없음), 전체 취소 성공, 진행 중 출연 0건, 보류 0. 수락이 먼저면 위약금 2건·빈자리 반환 없음, 막혔으면(`APPEARANCE_RECRUIT_NOT_OPEN`) 위약금 1건·빈자리 30,000 |
| 전체 취소 | 앞 작업(위약금)의 결과 기록 실패 (DB 오류 흉내) | 예외 없이 취소 성공 응답, 뒤 작업(빈자리 반환)도 `DONE`, 위약금 작업은 `PENDING` → 재시도로 `DONE`, 위약금은 한 번만 지급 |
| 역할 | 세션 USER / ADMIN | 일방 취소·전체 취소 `AccessDeniedException` |

### 14.3 E2E·기타

| 테스트 | 케이스 | 기대 결과 |
|---|---|---|
| `AppearanceApiCancelE2ETest` | 처리 중 요청 있는 출연: 게스트 / 확인 없음 / 확인 | 403 `NOT_APPEARANCE_HOST` / 409 `CANCEL_REQUEST_CONFIRM_REQUIRED` / 200 "출연을 취소했습니다.", `CANCELED_BY_HOST`, 위약금 9,000, 사유, `hpPenaltyStatus` `DONE`, 요청 `CLOSED_BY_HOST_CANCEL` |
| `AppearanceRecruitApiE2ETest` | 다른 회원 / 호스트(본문 없음) / 다시 | 403 `NOT_RECRUIT_HOST` / 200 "출연 모집을 취소했습니다.", `CANCELED`, 취소 시각, `hpReleaseStatus` `DONE` / 409 `APPEARANCE_RECRUIT_NOT_CANCELABLE` |
| `AppearanceSwaggerDocsTest` | 일방 취소·전체 취소 응답 스키마 | `RsDataAppearanceResDto` / `RsDataAppearanceRecruitResDto` |
| `HpTaskRepositoryTest` | 위약금 정산 작업 저장 / 반환 작업에 위약금 값 | 받는 회원·위약금·출연 ID 저장 / `ck_hp_tasks_penalty` 위반 |
