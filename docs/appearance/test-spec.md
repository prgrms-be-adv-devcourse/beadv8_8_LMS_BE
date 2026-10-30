# 출연(Appearance) 모듈: 테스트 명세

출연 모듈 테스트가 무엇을 보장하는지 정리한 문서다. 테스트 이름(`@DisplayName`)과 아래 케이스가 대응한다.
테스트를 추가·변경하면 이 문서도 함께 갱신한다.

- 정책과 구조: [module.md](module.md)
- 오류 코드: [error-codes.md](error-codes.md)

---

## 1. 실행 방법

| 명령 | 대상 | Docker 필요 |
|---|---|---|
| `./gradlew :appearance-app:test` | 출연 모듈 전체 테스트 | ✅ (저장소·통합·E2E 포함) |
| `./gradlew :apps:test --tests "com.hapbang.ModularityTests"` | 모듈 경계 검증 | ❌ |
| `./gradlew check` | CI와 같은 전체 검사 (모든 모듈) | ✅ |

- 저장소·통합·E2E 테스트는 Testcontainers로 임시 PostgreSQL을 띄우고, 끝나면 버린다. 팀 DB나 서버 DB는 건드리지 않는다.
- 테이블은 운영과 같이 Flyway 마이그레이션으로 만들고, Hibernate는 엔티티와 테이블이 맞는지만 검사한다(`validate`). 어긋나면 테스트가 시작 단계에서 실패한다.
- Windows에서 전체 검사가 다른 모듈의 한글 소스 때문에 컴파일에 실패하면 `./gradlew "-Dorg.gradle.jvmargs=-Dfile.encoding=UTF-8" check`로 실행한다.

### Billing 대역 (#289)

- 출연 모듈 통합 테스트는 `@AppearanceIntegrationTest`(= 팀 `@IntegrationTest` + `BillingClient` Mockito 대역), E2E 테스트는 `@AppearanceE2ETest`(실제 포트 + 같은 대역, #390)를 쓴다. 대역은 기본 성공(요청 값을 담은 `Succeeded`, #274)이고 Billing 규칙(잔액·보류·멱등)을 흉내 내지 않는다.
- 아래 표의 "보류·반환·지급·잔액" 기대 결과는 #289부터 **Billing에 보낸 요청 내용**(`BillingCalls`: 금액·요청 ID·원천·받는 회원)으로 확인한다. 실제 잔액·보류 변화는 지갑 쪽 테스트가 확인한다(16장 apps 통합 테스트는 #363에서 없어졌다).
- 실패는 `BillingStubs`로 만든다: 결과를 받지 못함(`BillingCallException(UNKNOWN_RESULT)`) / 지갑 거절(`Failed`: `INSUFFICIENT_BALANCE`·`WALLET_NOT_FOUND`·`HOLD_NOT_FOUND`). #274 전에는 각각 일반 런타임 예외·공통 업무 예외였다.
- 지갑 HTTP 응답을 흉내 내야 하면 Mock Billing 서버(`support/MockBilling`, WireMock)를 쓴다. 구성과 쓰는 법은 23장, 출연 흐름 시나리오는 24장.

### E2E 테스트 방식 (#390)

- E2E(HTTP 요청 → DB)는 `@AppearanceE2ETest`로 실제 서버(랜덤 포트)를 띄우고 RestAssured로 요청한다. 보안 필터(Gateway 사용자 헤더 로그인)·직렬화·오류 응답이 운영과 같은 경로를 거친다.
- 로그인은 `E2ELogin.as(회원 ID, 역할)` 한 곳에서 만든다. Gateway가 넘기는 `X-User-Id`·`X-User-Role` 헤더를 싣는다(#395, 27장). 세션·CSRF 토큰은 없다.
- ID처럼 `long`인 응답 값은 `E2EMatchers.number`로 비교한다(RestAssured는 JSON 정수를 크기에 따라 Integer·Long으로 읽는다).
- 컨트롤러 웹 슬라이스(`@WebMvcTest`)·Swagger 명세 테스트는 MockMvc 그대로다.

## 2. 테스트 구성

| 종류 | 테스트 | 확인 대상 | 이슈 |
|---|---|---|---|
| 단위 | `AppearancePolicyTest` | 정책 수치 계산 | #26 |
| 단위 | `AppearanceConfigTest` | 설정 값 주입, Clock | #26 |
| 단위 | `AppearanceUserTest` | 출연 회원 참여 자격 | #31 |
| 단위 | `AppearanceExceptionHandlerTest` | 출연 예외 → 공통 핸들러의 Problem Detail | #26, #31, #71 |
| 단위 | `AppearanceRequestTest` | 출연 요청서 생성 검증·상태 전이·수정·조회 권한 | #98 |
| 단위 | `AppearanceRequestValidationTest` | 요청서 보내기·수정 요청 값 형식 (Bean Validation) | #98 |
| 저장소 | `AppearanceUserRepositoryTest` | `appearances.users` 저장·조회 | #31 |
| 저장소 | `AppearanceRequestRepositoryTest` | `appearances.requests` 저장, 진행 중 요청 부분 유니크 인덱스, 목록 조회 | #98 |
| 통합 | `AppearanceModuleIntegrationTest` | 모듈 전체 기동 | #26 |
| 통합 | `AppearanceUserFacadeTest` | 출연 회원 생성·갱신(Facade), 자격 확인(Support) | #31, #50, #98 |
| 통합 | `AppearanceUserEventListenerTest` | 회원 가입·정보 변경 이벤트 Kafka 수신 (Testcontainers Kafka) | #31, #50, #394 |
| E2E | `AppearanceApiE2ETest` | 헤더 로그인 → 자격 확인 → 오류 응답, 회원 정지 반영 (앱 전체) | #31, #50 |
| 통합 | `AppearanceSwaggerDocsTest` | Swagger 명세의 성공 응답 스키마가 `RsData`인지 | #144 |
| 통합 | `AppearanceRequestFacadeTest` | 요청서 보내기(HP 보류·잔액 부족·무응답·중복·동시 요청), 로그인 역할(`@PreAuthorize`), 출연 회원 자격, 조회, 수정(멱등) | #98 |
| E2E | `AppearanceRequestApiE2ETest` | 출연 요청서 API 5개: 헤더 로그인·역할 → 응답(`RsData`)·오류 형식 (앱 전체) | #98, #144 |
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

### 3.2-1·3.3 (삭제, #289)

`BillingClientModeTest`(Billing 구현 선택)와 `FakeBillingClientTest`(Fake 동작 규칙)는 Fake Billing·`appearance.billing.mode`를 없애면서 지웠다.
출연 모듈 테스트의 Billing 대역 규칙은 아래 "Billing 대역" 절, 실제 Billing 확인은 16장.

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
| 출연 모듈 전체 + PostgreSQL 컨테이너로 기동 | 정책 값 주입, Clock 시간대 Asia/Seoul, Billing 창구가 대역(Mockito) |

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

### 4.4 회원 이벤트 Kafka 수신 — `AppearanceUserEventListenerTest` (Testcontainers Kafka, #394)

회원 앱처럼 shared 레코드를 JSON(`__TypeId__` 헤더)으로, key는 회원 ID로 `member.events.v1`에 보낸다. 업무 토픽은 테스트가 대신 만들고(partition 3), 리스너·토픽 생성을 켠 컨텍스트를 따로 띄워 끝나면 닫는다(`@DirtiesContext`). 테스트마다 다른 회원 ID를 쓴다. 강의 #429 테스트와 같은 경우를 확인한다.

| 케이스 | 기대 결과 |
|---|---|
| `UserJoinedEvent`(버전 0) | 출연 회원 생성: 닉네임·역할·ACTIVE·회원 버전 0 (최대 10초 대기) |
| 가입 → `UserModifiedEvent`(닉네임, VERIFIED_INFLUENCER, SUSPENDED, 버전 1) | 닉네임·역할·상태 갱신, 회원 버전 1 |
| 가입 → 변경(버전 2) 반영 뒤 같은 가입·버전 1 변경(늦게 옴)·버전 2 변경(중복) | 버전 2 정보 유지(1초 동안 확인), DLT 없음 |
| `UserLoggedOutEvent`(출연이 쓰지 않는 타입) → 가입 | 로그아웃은 무시하고 가입 반영, DLT 없음 |
| 가입 없이 `UserModifiedEvent` | 출연 회원 생성 안 됨. 재시도(1·2·4초, 7초 이상 걸림) 뒤 출연 DLT로: 원본 토픽·`AppearanceException` 헤더·본문 포함 |
| 깨진 JSON → 가입 | 깨진 메시지는 재시도 없이(1초 안) 원본 바이트 그대로 DLT 1건, 다음 가입은 반영 |
| DLT 토픽 | 이름 `member.events.v1.appearance-app.member-replica.DLT`, partition 3, 보존 7일 |

- 회원 버전 비교 자체는 공통 `ReplicaUser`(회원 #292, `ReplicaUserTest`)가 확인한다. 출연 쪽은 Kafka로 받았을 때 그 동작이 출연 회원에 적용되는지 본다.
- 이전 테스트(같은 서버 안 이벤트 발행, 발행 트랜잭션 롤백 경우)는 회원이 다른 앱이 되어 없앴다. 롤백된 회원 변경은 회원 앱이 Kafka로 보내지 않는다(#393과 같은 Modulith 외부화).

### 4.5 출연 요청서 저장소 — `AppearanceRequestRepositoryTest`

| 케이스 | 기대 결과 |
|---|---|
| 저장 후 조회 | ID 자동 생성, 상태·설명(TEXT)·수락 기한 일치, 버전·생성 시각 기록 |
| 같은 호스트·게스트에 `REQUESTED`가 있는데 새로 저장 | `DataIntegrityViolationException` (`uk_appearance_requests_pending_host_guest`) |
| 같은 호스트·게스트에 `HOLD_FAILED`만 있을 때 새로 저장 | 저장됨 (제한 대상 아님) |
| 방향이 반대(게스트 → 호스트)이거나 다른 게스트 | 저장됨 |
| `REQUESTED`·`HOLD_FAILED`·`HOLD_PENDING` 각 1건에서 보이는 상태로 목록 조회 | `REQUESTED` 1건만 (보낸·받은 목록 모두) |

### 4.6 출연 요청서 흐름 — `AppearanceRequestFacadeTest`

DB와 Billing 대역을 함께 쓴다. 로그인 역할은 `@WithMockUser(roles = "INFLUENCER")`로 넣는다(Facade의 `@PreAuthorize`). Billing 대역은 테스트마다 초기화된다.

| 케이스 | 기대 결과 |
|---|---|
| 보내기 | `REQUESTED`, 보류 30,000, 호스트 잔액 −30,000 |
| 잔액 29,999에서 보내기 | 409 `INSUFFICIENT_BALANCE`(출연 문구, #274), 요청서 `HOLD_FAILED`, 잔액 그대로, 보낸 목록 0건 |
| 호스트 지갑 없음 (#168) | 500 `HP_HOLD_FAILED`(지갑 코드는 응답하지 않음, #274), 요청서 `HOLD_FAILED` |
| 잔액 부족 실패 뒤 잔액을 채우고 같은 게스트에게 다시 보내기 | `REQUESTED` |
| Billing 무응답 | `BILLING_UNAVAILABLE`, 요청서 `HOLD_PENDING`, 보낸 목록 0건. 응답이 돌아와도 바로 다시 보내면 `DUPLICATE_APPEARANCE_REQUEST` |
| 응답 대기 중인 게스트에게 다시 보내기 / 다른 게스트에게 보내기 | `DUPLICATE_APPEARANCE_REQUEST` / `REQUESTED` (보류 2건) |
| 같은 게스트에게 5개 스레드가 동시에 저장 | 1건 저장, 4건 `DUPLICATE_APPEARANCE_REQUEST`, DB에 1건 |
| 게스트가 일반 유저 / 없는 회원 | `NOT_INFLUENCER` ("인플루언서 이상에게만 출연을 요청할 수 있습니다.") / `APPEARANCE_USER_NOT_FOUND` ("게스트 회원 정보를 찾을 수 없습니다."), 저장 안 됨 |
| 로그인 역할은 인플루언서, 출연 회원은 정지 | `APPEARANCE_USER_NOT_ACTIVE`, 저장 안 됨 |
| 로그인 역할 USER (보내기·목록) / ADMIN (보내기) | `AccessDeniedException` (Facade `@PreAuthorize`), 저장 안 됨 |
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

실제 서버(보안 필터, 헤더 로그인, 서비스, DB)를 띄우고 RestAssured로 요청한다. 로그인은 `E2ELogin`으로 만든다 (#390).
테스트 전용 컨트롤러로 로그인·자격 확인 기반만 확인한다. 실제 출연 API는 5.2에서 확인한다.

| 케이스 | 기대 결과 |
|---|---|
| 로그인하지 않고 요청 | 401 (보안 필터) |
| 인플루언서로 로그인 | 200, 헤더의 회원 ID가 `@LoginUserId`로 들어와 자격 확인 통과, `Set-Cookie` 없음 |
| 헤더 역할 인플루언서, 복제본은 일반 유저(토큰 발급 뒤 강등) | 403 `NOT_INFLUENCER` (#395) |
| 로그인했지만 출연 회원 정보 없음 | 404 Problem Detail, `code: APPEARANCE_USER_NOT_FOUND` |
| 일반 유저로 로그인 | 403 Problem Detail, `code: NOT_INFLUENCER`, 한글 `detail` 정상 (UTF-8) |
| 인플루언서로 요청 성공 → 회원 정지 반영(리스너 진입점 `AppearanceUserFacade.syncModified`, Kafka 수신은 4.4) → 같은 로그인으로 다시 요청 | 200 → 403 Problem Detail, `code: APPEARANCE_USER_NOT_ACTIVE` |

---

### 5.2 출연 요청서 API — `AppearanceRequestApiE2ETest`

앱 전체(보안 필터, 헤더 로그인, 메서드 보안, 서비스, DB, Billing 대역)를 띄우고 실제 API를 호출한다.
성공 응답은 `RsData`라서 값은 `$.data.…`에서 확인하고, `statusCode`·`message`도 함께 확인한다 (#144).

| 케이스 | 기대 결과 |
|---|---|
| 보내기 → 보낸 목록 → 받은 목록 → 상세 → 수정 | HTTP·`statusCode` 201, `data.status` `REQUESTED` → `data.content[0]`·`data.page.totalElements` 1 → 게스트 목록에 호스트 ID → 상세 필드 일치 → 보낸 값만 바뀜. 단계마다 `message` 문구 확인 |
| 로그인하지 않고 목록 조회 | 401 (보안 필터) |
| 일반 유저로 목록 조회, 관리자로 보내기 | 403 Problem Detail, `code: FORBIDDEN` (Facade `@PreAuthorize`, 공통 핸들러) |
| 제목 공백·URL 형식 오류·출연료 누락 | 400, `code: INVALID_REQUEST`, `errors`에 `title`·`hostChannelUrl`·`fee` |
| 출연료 19,999 | 400 Problem Detail(`data` 없음), `code: FEE_BELOW_MINIMUM`, `detail`에 최저 출연료 |
| 잔액 10,000에서 30,000 요청 | 409, `code: INSUFFICIENT_BALANCE`, `detail: HP 잔액이 부족합니다. 충전한 뒤 다시 시도해 주세요.` (출연 문구, #274) |
| 응답 대기 중인 게스트에게 다시 보내기 | 409, `code: DUPLICATE_APPEARANCE_REQUEST` |
| Billing 무응답 | 503, `code: BILLING_UNAVAILABLE` |
| 다른 회원 상세 조회 / 게스트 수정 | 404 `APPEARANCE_REQUEST_NOT_FOUND` / 403 `NOT_REQUEST_HOST` |
| 빈 본문(`{}`)으로 수정 | 400, `code: INVALID_REQUEST` |
| 사용자 헤더 없이 보내기 / `X-User-Id`만 보내기 | 401 / 401 (보안 필터, #395) |

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
| 일반 유저로 수락 | 403 `FORBIDDEN` |

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

DB와 Billing 대역을 함께 쓴다. 로그인 역할은 `@WithMockUser(roles = "INFLUENCER")`.

| 케이스 | 기대 결과 |
|---|---|
| 수락 | `ACCEPTED` + 출연 성립(스냅샷), 보류 30,000 그대로, HP 작업 없음 |
| 호스트가 수정한 뒤 옛 버전으로 수락 | `APPEARANCE_REQUEST_MODIFIED`, 출연 없음 (보내기 응답의 `version`이 DB와 같은지도 함께 확인) |
| 호스트가 수락 / 다른 회원이 수락 | `NOT_REQUEST_GUEST` / `APPEARANCE_REQUEST_NOT_FOUND` |
| 요청 뒤 호스트 정지 | `APPEARANCE_USER_NOT_ACTIVE` ("호스트가 현재 출연 거래를 할 수 없는 회원입니다."), 출연 없음 |
| 기한이 지났지만 아직 `REQUESTED` | 수락·거절·취소 모두 `APPEARANCE_REQUEST_EXPIRED`, 상태 그대로 |
| 로그인 역할 USER | 수락·거절·취소 모두 `AccessDeniedException` |
| 사유와 함께 거절 | `REJECTED`, 반환 작업 1건(금액 30,000, 호스트, 멱등 키), `DONE`, Billing에 호스트 반환 요청 |
| Billing 무응답에서 거절 | `REJECTED` 성공, 작업 `PENDING`, 시도 1, 오류에 대역의 무응답 예외, 다음 시도 시각, 보류 그대로 |
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

DB와 Billing 대역을 함께 쓴다. 로그인 역할은 `@WithMockUser(roles = "INFLUENCER")`.

| 케이스 | 기대 결과 |
|---|---|
| 올리기 | `RECRUITING`, 성립 인원 0, 응답 버전 = DB, Billing에 보류 요청(`APPEARANCE_RECRUIT`, 모집글 ID, 호스트, 90,000) |
| 같은 호스트가 두 번 올리기 | 2건 저장 (중복 제한 없음) |
| 잔액 89,999 | 409 `INSUFFICIENT_BALANCE`(출연 문구, #274), `HOLD_FAILED`, 내 목록·공개 목록 0건 |
| Billing 무응답 | `BILLING_UNAVAILABLE` ("…내 모집글 목록에서 결과를 확인해 주세요."), `HOLD_PENDING` |
| 모집 인원 11명 | `RECRUIT_COUNT_OUT_OF_RANGE`, 저장·보류 없음 |
| 정지된 호스트 | `APPEARANCE_USER_NOT_ACTIVE` |
| 로그인 역할 USER | 올리기·수정·내 목록은 `AccessDeniedException`, 공개 목록·상세는 조회 가능 |
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

DB와 Billing 대역을 쓴다. 시각 조건은 DB의 만든 시각·다음 시도 시각을 과거로 옮겨 만든다.

| 케이스 | 기대 결과 |
|---|---|
| 1분 넘게 `HOLD_PENDING`인 요청서 재확인 | `REQUESTED`, Billing에 보류 요청(요청서·30,000) |
| Billing이 처음 요청을 이미 처리했을 수 있는 경우 | `REQUESTED`, 처음과 같은 보류 요청(같은 호스트·요청서·금액)을 다시 보냄 (Billing이 처음 보류를 돌려주는 것은 16장 실제 Billing으로 확인) |
| 재확인 때 Billing 거절(잔액 부족) | `HOLD_FAILED` |
| 1분이 안 됨 / 여전히 Billing 무응답 | 그대로 `HOLD_PENDING` (정리 0건) |
| 모집글 재확인 | `RECRUITING`, Billing에 보류 요청(`APPEARANCE_RECRUIT`, 90,000) |
| 수락 기한이 지난 `HOLD_PENDING` (S5) | 재확인 → `REQUESTED` → 만료 → `EXPIRED` + 반환 `DONE`, 반환 요청 30,000 |
| 수락 기한이 지난 `REQUESTED` 만료 | `EXPIRED`, 작업 사유 `REQUEST_EXPIRED` `DONE`, 반환 요청(요청 ID = 작업 키, 30,000), 상세 `hpReleaseStatus` `DONE` |
| 수락 기한 전 | 그대로 `REQUESTED`, 작업 없음 |
| 만료 때 반환 실패 / 다시 실행 | `EXPIRED` 확정, 작업 `PENDING` 시도 1 / 만료 0건 |
| 반환 실패 작업: 다음 시도 시각 전 / 후 | 실행 안 함 / `DONE`, 시도 2, 같은 반환 요청 ID로 다시 보냄, 지표 done +1 |
| 계속 실패 | 2~9회는 `PENDING`(마지막 간격 약 1시간), 10회째 `DEAD`, 지표 격리 수 1 |
| 재시도 때 Billing 거절 (대역이 `HOLD_NOT_FOUND`로 거절) | 바로 `DEAD`, `HOLD_NOT_FOUND: …` |
| 처음 시도 전 작업: 만든 직후 / 1분 뒤 | 가져가지 않음(반환 요청 없음) / 실행 → `DONE`, 반환 요청 1번. (#289 전에는 Fake에 보류가 없어 `DEAD`였다. 대역은 기본 성공이라 `DONE`) |
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

DB와 Billing 대역을 쓴다. 로그인 역할은 `@WithMockUser(roles = "INFLUENCER")`, 동시 처리 테스트는 스레드마다 인플루언서 인증을 넣는다.

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
| 조회 | 내 지원 목록의 모집글 값 (#243): 모집글 A 제목 수정, 모집글 B 전체 취소 | 각 행에 현재 제목(`제목 바꿈`)·방송 예정 일시·상태(`RECRUITING` / `CANCELED`) |
| 조회 | 지원·수락 응답, 지원자 목록 (#243) | 모집글 제목이 담긴다 |
| 조회 | 모집글 상세의 내 지원 (#243) | 지원한 게스트: `APPLIED`·메시지·출연 ID 없음 → 수락 뒤 `SELECTED`·출연 ID. 취소한 게스트 `WITHDRAWN`. 지원 안 한 회원·호스트 `null` |
| 조회 | 모집글 지원자 수 (수락·거절·취소 각 1) | 상세 2, 공개 목록·내 모집글 목록도 2, 지원 없는 모집글 0 |
| 역할 | 로그인 역할 USER / ADMIN | 지원·취소·수락·거절·지원자 목록·내 지원 목록 모두 `AccessDeniedException` |

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
| 내 지원 목록 / `status=UNKNOWN` | 200 "내 지원 목록을 조회했습니다.", 내 것 1건, `recruitTitle`·`recruitScheduledAt`·`recruitStatus`(`RECRUITING`) (#243) / 400 `INVALID_REQUEST` |
| 모집글 상세 `myApplication` (#243): 지원한 게스트 / 일반 유저 / 호스트 | 지원 ID·`APPLIED`·메시지·출연 ID 없음 / 없음 / 없음 |
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
| 조기 마감 | Billing 무응답 → 재시도 배치 | 마감 성공 + `PENDING`, 반환 요청 없음 → 다음 시도 시각이 되어 재시도 후 `DONE`, 90,000 반환 요청 |
| 조기 마감 | 다른 회원 / 다시 마감 / 마감 일시 지남 / 보류 처리 중 | `NOT_RECRUIT_HOST` / `APPEARANCE_RECRUIT_NOT_OPEN` / `APPEARANCE_RECRUIT_NOT_OPEN`(반환 작업 없음) / `APPEARANCE_RECRUIT_NOT_FOUND` |
| 마감 뒤 | 새 지원 / 대기 지원 수락 / 미선정 지원 취소 | `APPEARANCE_RECRUIT_NOT_OPEN` / `APPEARANCE_RECRUIT_NOT_OPEN` / `APPLICATION_NOT_PENDING` |
| 동시성 | 조기 마감과 마지막 대기 지원 수락 동시 | 결과는 모두 응답 또는 업무 예외(교착 상태·500 원인 없음). 실패는 많아야 1건(`CONCURRENT_UPDATE` 또는 `APPEARANCE_RECRUIT_NOT_OPEN`). 출연 수 = 성립 인원, 마감됐으면 보류 = 성립 × 30,000, 아니면 보류 90,000, 호스트 잔액 = 기본 − 보류 |
| 기한 마감 | 마감 일시 지남, 수락 1·대기 1 | 1건, `CLOSED`(`DEADLINE`), 마감 시각 = 모집 마감 일시, 대기 → `NOT_SELECTED`, 반환 `DONE`, 보류 30,000 |
| 기한 마감 | 마감 일시 전 1건 + 지남 1건, 두 번 실행 | 1건 → 0건, 전인 모집글은 `RECRUITING`, 반환 작업 1건 |
| 기한 마감 | 정원이 찬 모집글 | 마감 1건, 반환 작업 없음 |
| 기한 마감 | 마감 일시가 지난 `HOLD_PENDING` (C5) | 마감 배치만으로는 0건·작업 없음 → 보류 재확인 후 마감 배치 1건, `CLOSED`, 90,000 반환 요청 |
| 동시성 | 조기 마감과 새 지원 2건 동시 (3회 반복, C9) | 마감은 성공, 지원은 성공 또는 `APPEARANCE_RECRUIT_NOT_OPEN`. 마감된 모집글에 `APPLIED` 지원 0건 |
| 기한 마감 | 대상 2건 중 1건 처리 중 충돌(`CONCURRENT_UPDATE`) (C8) | 1건만 마감, 충돌 건은 `RECRUITING`·반환 작업 없음(롤백) → 다음 실행에서 마감·반환 작업 1건 |
| 역할 | 로그인 역할 USER / ADMIN | `AccessDeniedException` |

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
| E2E | `AppearanceApiCancelE2ETest` | 조회(role 필수)·상세·다른 회원 404·일반 유저 403, 요청→거절→재요청→수락 흐름과 오류 코드, 관계없는 회원 취소 요청 404, 숫자 아닌 경로 |
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
| 요청서 출처 | 수락 | `CANCELED_BY_AGREEMENT`, Billing에 반환 30,000 요청 `CANCEL_AGREED`·출연별 반환 요청 ID·`DONE`, 수락 이벤트 1건, 출연 상세 `hpReleaseStatus` `DONE`·요청서 상세는 null |
| 요청서 출처 | 거절 → 재요청 → 거절 → 3번째 | `ESTABLISHED`·거절 사유·거절 이벤트(`autoRejected=false`) → 순번 1·2 → `CANCEL_REQUEST_LIMIT_EXCEEDED`, HP 작업 없음 |
| 요청서 출처 | 거부 | 요청 없이 수락 `CANCEL_REQUEST_NOT_PENDING`, 공백 사유 `INVALID_REQUEST`, 호스트 요청 `NOT_APPEARANCE_GUEST`, 다른 회원 `APPEARANCE_NOT_FOUND`, 처리 중 재요청 `CANCEL_REQUEST_ALREADY_PENDING`, 게스트 수락 `NOT_APPEARANCE_HOST`, 없는 출연 `APPEARANCE_NOT_FOUND` |
| 요청서 출처 | 방송 예정 일시 지남 | 요청·수락·거절 `APPEARANCE_SCHEDULE_PASSED` |
| 요청서 출처 | Billing 무응답에서 수락 | 합의 취소 성공, 반환 작업 `PENDING` |
| 모집 출처 | 정원 1, 마감 전 수락 | 성립 인원 0, 반환 없음(출연 `hpReleaseStatus` null), 보류 30,000 유지, 빈자리에 다른 지원자 수락 성공 (전에는 `RECRUIT_FULL`) |
| 모집 출처 | 정원 3·성립 1, 마감 반환 실패(`PENDING`) 뒤 합의 취소 반환 성공 | 출연 상세 `DONE`, 모집글 상세는 마감 반환 `PENDING` 그대로 (가려지지 않음) |
| 모집 출처 | 마감 전 수락 후 조기 마감 | 마감 반환 `RECRUIT_CLOSED` 30,000 1건 (합의 취소한 자리 포함) |
| 모집 출처 | 마감 후 수락 | 반환 30,000 `CANCEL_AGREED`·`APPEARANCE_RECRUIT-{id}-RELEASE-APPEARANCE-{id}`·`DONE`, 성립 인원 0 |
| 모집 출처 | 합의 취소 수락 + 빈자리 지원 수락 동시 | 결과는 응답 또는 업무 예외뿐(교착 상태 없음), 합의 취소는 성공, 성립 인원 = 유지 중인 출연 수 |
| 자동 거절 | 방송 예정 일시 지난 요청, 두 번 실행 | 1건 → 0건, `AUTO_REJECTED`, 출연 `ESTABLISHED`, HP 작업 없음, 거절 이벤트(`autoRejected=true`) |
| 자동 거절 | 방송 예정 일시 전 | 0건, `CANCEL_REQUESTED` 그대로 |
| 동시성 | 같은 요청에 수락·거절 동시 | 1건만 성공, 나머지 `CANCEL_REQUEST_NOT_PENDING` 또는 `CONCURRENT_UPDATE`, 결과에 맞게 반환 요청 1건 또는 없음 |
| 조회 | 내 출연 목록·상세 | 호스트 목록 최신순(모집·요청), 제목은 출처 제목, 게스트 `CANCEL_REQUESTED` 필터, 다른 쪽 역할 0건, 다른 회원 상세 `APPEARANCE_NOT_FOUND` |
| 역할 | 로그인 역할 USER / ADMIN | 5개 기능 모두 `AccessDeniedException` |

### 13.4 E2E — `AppearanceApiCancelE2ETest`

| 케이스 | 기대 결과 |
|---|---|
| `role=GUEST` 목록 / `role` 없음 | 200 "내 출연 목록을 조회했습니다.", 1건·제목 / 400 |
| 호스트 상세 / 다른 회원 / 일반 유저 | 200 `ESTABLISHED`·이력 없음 / 404 `APPEARANCE_NOT_FOUND` / 403 `FORBIDDEN` |
| 사유 없이 요청 / 요청 / 다시 요청 | 400 `INVALID_REQUEST` / 200 "출연 취소를 요청했습니다.", `CANCEL_REQUESTED` / 409 `CANCEL_REQUEST_ALREADY_PENDING` |
| 게스트 수락 / 호스트 거절(본문 없음) | 403 `NOT_APPEARANCE_HOST` / 200 "출연 취소 요청을 거절했습니다.", `ESTABLISHED`, `REJECTED` |
| 재요청 → 수락 / 3번째 요청 | 200 "출연 취소 요청을 수락했습니다.", `CANCELED_BY_AGREEMENT`, 이력 2번째 `ACCEPTED` / 409 `APPEARANCE_NOT_CANCELABLE` |
| 관계없는 회원이 취소 요청 / `/api/v1/appearances/abc` | 404 `APPEARANCE_NOT_FOUND` / 404 (#395 전에는 CSRF 없음 403) |

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
| 일방 취소 | 요청서 출처 | `CANCELED_BY_HOST`, 위약금 9,000·사유·`hpPenaltyStatus` `DONE`, 작업 `PENALTY`·`HOST_CANCELED`·`APPEARANCE_REQUEST-{id}-PENALTY-APPEARANCE-{id}`, Billing에 게스트 지급 9,000 + 호스트 반환 21,000 요청 |
| 일방 취소 | 처리 중 취소 요청 | 확인 없이 `CANCEL_REQUEST_CONFIRM_REQUIRED`(출연 그대로, HP 작업 없음) → 확인하면 취소 + `CLOSED_BY_HOST_CANCEL` + 위약금 지급 |
| 일방 취소 | 거부 | 게스트 `NOT_APPEARANCE_HOST`, 다른 회원 `APPEARANCE_NOT_FOUND`, 다시 취소·합의 취소된 출연 `APPEARANCE_NOT_CANCELABLE`, 방송 예정 일시 지남 `APPEARANCE_SCHEDULE_PASSED` |
| 일방 취소 | 모집 출처 (정원 1) | 모집글 보류 30,000 정산(0), 키 `APPEARANCE_RECRUIT-{id}-PENALTY-APPEARANCE-{id}`, 성립 인원 1 그대로, 대기 지원 수락 `RECRUIT_FULL` |
| 일방 취소 | Billing 무응답 → 재시도 배치 → 다시 실행 | 취소 성공·`PENDING`(게스트 잔액 그대로) → `DONE`, 위약금은 한 번만 지급(게스트 +9,000, 호스트 −9,000) |
| 일방 취소 | Billing 거절 (게스트 지갑 없음) | 취소 성공, 재시도 없이 `DEAD`(시도 1회), 마지막 오류 `WALLET_NOT_FOUND: …` |
| 전체 취소 | 모집 중, 3명 중 2명 성립·1명 대기 | `CANCELED`·사유·`hpReleaseStatus` `DONE`, 출연 2건 일방 취소(위약금 9,000씩·같은 사유), 대기 지원 `NOT_SELECTED`, 빈자리 반환 30,000 `RECRUIT_CANCELED`, Billing 요청 합계 = 보류 90,000 |
| 전체 취소 | 성립 0명 | 위약금 없음, 90,000 반환 요청 |
| 전체 취소 | 마감 뒤 | 위약금 1건, 반환은 마감 반환(`RECRUIT_CLOSED`)뿐 |
| 전체 취소 | 모집 중 합의 취소된 자리 | 위약금 없음, 빈자리 반환 60,000 (합의 취소 자리 포함) |
| 전체 취소 | 처리 중 취소 요청 | 확인 없이 `CANCEL_REQUEST_CONFIRM_REQUIRED`(모집글 `RECRUITING`, 일방 취소 0건) → 확인하면 요청 `CLOSED_BY_HOST_CANCEL`, 위약금 2건 |
| 전체 취소 | 거부 | 다른 회원 `NOT_RECRUIT_HOST`, 다시 취소 `APPEARANCE_RECRUIT_NOT_CANCELABLE`, 방송 예정 일시 지남 `APPEARANCE_SCHEDULE_PASSED`, 보류 처리 중 `APPEARANCE_RECRUIT_NOT_FOUND` |
| 동시성 | 전체 취소 + 대기 지원 수락 | 결과는 응답·업무 예외뿐(교착 상태 없음), 전체 취소 성공, 진행 중 출연 0건, Billing 요청 합계 = 보류 60,000. 수락이 먼저면 위약금 2건·빈자리 반환 없음, 막혔으면(`APPEARANCE_RECRUIT_NOT_OPEN`) 위약금 1건·빈자리 30,000 |
| 전체 취소 | 앞 작업(위약금)의 결과 기록 실패 (DB 오류 흉내) | 예외 없이 취소 성공 응답, 뒤 작업(빈자리 반환)도 `DONE`, 위약금 작업은 `PENDING` → 재시도로 `DONE`, 위약금은 한 번만 지급 |
| 역할 | 로그인 역할 USER / ADMIN | 일방 취소·전체 취소 `AccessDeniedException` |

### 14.3 E2E·기타

| 테스트 | 케이스 | 기대 결과 |
|---|---|---|
| `AppearanceApiCancelE2ETest` | 처리 중 요청 있는 출연: 게스트 / 확인 없음 / 확인 | 403 `NOT_APPEARANCE_HOST` / 409 `CANCEL_REQUEST_CONFIRM_REQUIRED` / 200 "출연을 취소했습니다.", `CANCELED_BY_HOST`, 위약금 9,000, 사유, `hpPenaltyStatus` `DONE`, 요청 `CLOSED_BY_HOST_CANCEL` |
| `AppearanceRecruitApiE2ETest` | 다른 회원 / 호스트(본문 없음) / 다시 | 403 `NOT_RECRUIT_HOST` / 200 "출연 모집을 취소했습니다.", `CANCELED`, 취소 시각, `hpReleaseStatus` `DONE` / 409 `APPEARANCE_RECRUIT_NOT_CANCELABLE` |
| `AppearanceSwaggerDocsTest` | 일방 취소·전체 취소 응답 스키마 | `RsDataAppearanceResDto` / `RsDataAppearanceRecruitResDto` |
| `HpTaskRepositoryTest` | 위약금 정산 작업 저장 / 반환 작업에 위약금 값 | 받는 회원·위약금·출연 ID 저장 / `ck_hp_tasks_receiver` 위반 (#282에서 제약 이름 변경) |

## 15. 출연 확인 (#282)

출연료 30,000. 확인 기간 = 방송 예정 일시 ~ 방송 예정 일시 + 7일 전. 흐름·E2E 테스트는 테스트 시계를 실제 순서대로 앞으로 돌려 "방송이 끝난 뒤"를 만든다 (모집 출처는 모집 마감 배치가 먼저, #297).

### 15.1 단위 — `AppearanceConfirmTest`

| 묶음 | 케이스 | 기대 결과 |
|---|---|---|
| 확인 | 방송 예정 일시 그 시각 | `CONFIRMED`, 확인 방식 `HOST`, 확인 시각 |
| 확인 | 확인 기간 끝나기 1초 전 | `CONFIRMED` |
| 확인 | 방송 예정 일시 1초 전 / 확인 기간이 끝난 시각 | `APPEARANCE_CONFIRM_NOT_OPEN` / `APPEARANCE_CONFIRM_PERIOD_ENDED` (상태 그대로) |
| 확인 | 게스트·다른 회원 | `NOT_APPEARANCE_HOST` "출연의 호스트만 출연을 확인할 수 있습니다." |
| 불참 | 불참 | `ABSENT`, 불참 시각, 사유 다듬음, 확인 방식·시각 없음 |
| 불참 | 공백 사유 / 501자 | null / `INVALID_REQUEST` "불참 사유는 500자 이하여야 합니다." (상태 그대로) |
| 불참 | 기간 밖 | 확인과 같은 오류 |
| 상태 | 확인 뒤 불참·다시 확인, 불참 뒤 확인 | `APPEARANCE_NOT_CONFIRMABLE` "이미 출연 확인에 응답한 출연입니다." (번복 불가) |
| 상태 | 취소 요청 처리 중 | `APPEARANCE_NOT_CONFIRMABLE` "처리 중인 출연 취소 요청이 정리된 뒤…" → 거절로 성립이 된 뒤 확인 가능 |
| 상태 | 일방 취소·합의 취소된 출연 | `APPEARANCE_NOT_CONFIRMABLE` "취소된 출연입니다." |
| 자동 확인 | 기간 끝 1초 전 / 그 시각 | 대상 아님 / 대상 → `CONFIRMED`, `AUTO` |
| 자동 확인 | 불참·취소 요청 중 출연, 대상 아닌데 호출 | 대상 아님, `IllegalStateException` |
| 지급 작업 | `HpTask.payout` | `PAYOUT`, 받는 회원 게스트, 지급액 = 출연료, 출연 ID, `PENDING`. 0원은 `IllegalArgumentException` |
| 보류 대상 | 요청서 출처 출연 | `APPEARANCE_REQUEST` + 요청서 ID |

### 15.2 저장소

| 테스트 | 케이스 | 기대 결과 |
|---|---|---|
| `AppearanceRepositoryTest` | 자동 확인 대상 (기준 시각 이전·같음·1초 뒤·이미 확인) | 성립 + 방송 예정 일시 ≤ 기준만, 오래된 순, 건수 제한 |
| `HpTaskRepositoryTest` | 지급 작업 저장 / 지급액 ≠ 출연료 | 받는 회원·지급액·출연 ID·사유 저장 / `ck_hp_tasks_receiver` 위반 |

### 15.3 흐름 — `AppearanceConfirmFlowTest`

| 묶음 | 케이스 | 기대 결과 |
|---|---|---|
| 확인 | 요청서 출처 | `CONFIRMED`·`HOST`·`hpPayoutStatus` `DONE`, 작업 `PAYOUT`·`APPEARANCE_CONFIRMED`·`APPEARANCE_REQUEST-{id}-PAYOUT-APPEARANCE-{id}`(지급액 30,000, 받는 회원 게스트), Billing에 게스트 지급 30,000 요청 |
| 확인 | 모집 출처 (정원 3, 성립 2): 마감 배치 → 방송 → 하나 확인·하나 불참 | 마감 반환 30,000(`…-RELEASE-1`) → 지급 30,000(`…-PAYOUT-APPEARANCE-…`) → 불참 반환 30,000(`…-RELEASE-APPEARANCE-…`), 요청 합계 = 보류 90,000 |
| 확인 | 거부 | 방송 전 `APPEARANCE_CONFIRM_NOT_OPEN`, 게스트 `NOT_APPEARANCE_HOST`, 다른 회원 `APPEARANCE_NOT_FOUND`, 기간 끝 `APPEARANCE_CONFIRM_PERIOD_ENDED`. 출연 `ESTABLISHED`, 작업 없음, 잔액 그대로 |
| 확인 | 확인 뒤 불참·다시 확인 | `APPEARANCE_NOT_CONFIRMABLE`, 지급 1건, 반환 0건 |
| 확인 | 방송 뒤 취소 요청 처리 중 | `APPEARANCE_NOT_CONFIRMABLE` → 자동 거절 배치 뒤 확인 성공 |
| 확인 | 정지된 호스트·게스트 | 확인 성공(응답은 출연 상세), 지급 `DONE`·게스트 지급 요청. 같은 호스트의 상세 조회는 `APPEARANCE_USER_NOT_ACTIVE` |
| 확인 | Billing 무응답 → 재시도 배치 → 다시 실행 | 확인 성공·`PENDING`(게스트 그대로) → `DONE`, 지급은 한 번 |
| 확인 | Billing 거절 (게스트 지갑 없음) | 확인 성공, `DEAD`(시도 1회), 마지막 오류 `WALLET_NOT_FOUND: …` |
| 불참 | 요청서 출처 | `ABSENT`·사유·불참 시각·`hpReleaseStatus` `DONE`·`hpPayoutStatus` 없음, 작업 `RELEASE`·`APPEARANCE_ABSENT`·`…-RELEASE-APPEARANCE-{id}`, Billing에 호스트 반환 30,000 요청, 지급 요청 없음 |
| 불참 | 확인과 불참 동시 | 1건만 성공, 나머지 `APPEARANCE_NOT_CONFIRMABLE` 또는 `CONCURRENT_UPDATE`, HP 작업 1건, 잔액은 성공한 쪽대로 |
| 자동 확인 | 기간 끝난 출연·하루 늦게 성립해 기간 안인 출연·불참 출연 | 1건만 `CONFIRMED`·`AUTO`·지급 `DONE`, 나머지 그대로 |
| 자동 확인 | 취소 요청 처리 중 | 건너뜀 → 자동 거절 뒤 다음 실행에서 확인 → 또 실행해도 0건, 지급 1건 |
| 자동 확인 | Billing 무응답 | 확인은 하고 지급 작업만 `PENDING` |
| 역할 | 로그인 역할 USER / ADMIN | 확인·불참 `AccessDeniedException` |

### 15.4 E2E·기타

| 테스트 | 케이스 | 기대 결과 |
|---|---|---|
| `AppearanceApiCancelE2ETest` | 확인: 방송 전 / 게스트 / 호스트(본문 없음) / 다시 불참 | 409 `APPEARANCE_CONFIRM_NOT_OPEN` / 403 `NOT_APPEARANCE_HOST` / 200 "출연을 확인했습니다.", `CONFIRMED`·`HOST`·확인 시각·`hpPayoutStatus` `DONE` / 409 `APPEARANCE_NOT_CONFIRMABLE` |
| `AppearanceApiCancelE2ETest` | 불참: 사유 501자 / 다른 회원 / 호스트 / 본문 없음 | 400 `INVALID_REQUEST` / 404 `APPEARANCE_NOT_FOUND` / 200 "불참으로 처리했습니다.", `ABSENT`·사유·불참 시각·`hpReleaseStatus` `DONE` / 200, 사유 없음 |
| `AppearanceSwaggerDocsTest` | 확인·불참 응답 스키마 | `RsDataAppearanceResDto` |
| `AppearanceBatchConditionTest` | 자동 확인 UseCase 추가 | 스위치 true면 배치 빈 생성 |

## 16. Billing 실제 연동 (#289)

### 16.1 apps — `AppearanceBillingFlowIntegrationTest` (실제 Billing)

앱 전체 + PostgreSQL. 출연 API를 그대로 호출하고 Billing 지갑(충전·수익 HP)·보류 남은 HP·HP 작업 상태를 본다. 호스트 HP는 충전 완료 건 + 충전 원장으로 만든다(Toss 결제 없음). 출연료 30,000.

| 묶음 | 케이스 | 기대 결과 |
|---|---|---|
| 요청서 | 보내기 → 게스트 거절 | 보류 30,000·호스트 충전 0 → 보류 0·호스트 30,000, `RELEASE DONE` |
| 요청서 | 잔액 29,000에서 보내기 | 409 `INSUFFICIENT_BALANCE`, Billing 보류 없음, 요청서 `HOLD_FAILED` |
| 요청서 | 수락 → 방송 뒤 확인 | `CONFIRMED`·지급 `DONE`, 게스트 수익 +30,000, 보류 0, `PAYOUT DONE` |
| 요청서 | 수락 → 방송 뒤 불참 | `ABSENT`·반환 `DONE`, 호스트 30,000, 게스트 0, 보류 0 |
| 요청서 | 수락 → 호스트 일방 취소 | 게스트 수익 9,000, 호스트 21,000, 보류 0, `PENALTY DONE` |
| 요청서 | 수락 → 취소 요청 → 합의 | `CANCELED_BY_AGREEMENT`·반환 `DONE`, 호스트 30,000, 게스트 0 |
| 모집 | 정원 3(보류 90,000), 2명 성립 → 조기 마감 → 1명 확인·1명 불참 | 마감 반환 30,000 → 보류 60,000, 게스트1 수익 30,000, 게스트2 0, 호스트 60,000, 보류 0 |
| 모집 | 정원 3, 1명 성립 → 모집 전체 취소 | 게스트 수익 9,000, 호스트 81,000(21,000 + 빈자리 60,000), 보류 0, `PENALTY DONE`·`RELEASE DONE` |
| 모집 | 정원 2, 2명 성립 → 마감 전 합의 취소 → 조기 마감 → 남은 1명 확인 | 합의 취소 때 반환 없음(보류 60,000 유지) → 마감에서 그 자리 30,000 반환 → 지급 30,000 → 보류 0 |
| 모집 | 정원 2, 2명 성립 → 조기 마감(반환 없음) → 마감 뒤 합의 취소 → 남은 1명 확인 | 합의 취소한 1인분 30,000 바로 반환 → 지급 30,000 → 보류 0 |
| 모집 | 잔액 89,000에서 올리기 | 409 `INSUFFICIENT_BALANCE`, 모집글 `HOLD_FAILED` |
| 견고성 | 지급 뒤 작업을 `PENDING`으로 되돌려 두 번 다시 실행 | 같은 지급 요청 ID라 게스트 수익 30,000 그대로, `PAYOUT DONE` |
| 견고성 | D+7 자동 확인 배치 | 게스트 수익 30,000, `PAYOUT DONE` |
| 견고성 | 게스트 지갑 없이 확인 | 응답 200 `CONFIRMED`, 지급 `DEAD`, 마지막 오류 `WALLET_NOT_FOUND…`, 보류 30,000 그대로 |

### 16.2 정리된 테스트

| 테스트 | 처리 |
|---|---|
| `FakeBillingClientTest` (출연) | 삭제 (Fake 삭제) |
| `BillingClientModeTest` (Billing 담당, 출연 모듈 안) | 삭제 (`mode` 설정 삭제) |
| apps `AppearanceBillingPortIntegrationTest` (Billing 담당) | Fake 참조·`mode` 속성만 제거, 나머지 그대로 |
| `AppearanceConfigTest` | Billing 구현 선택 케이스 삭제 |
| `AppearanceModuleIntegrationTest` | Billing 창구가 대역인지 확인 |
| 출연 모듈 흐름·E2E 테스트 | 잔액·보류 확인 → Billing에 보낸 요청 확인. 실패는 `BillingStubs` |

## 17. 정리 (#293)

### 17.1 작업 뒤 상세 조회 트랜잭션 — `AppearanceDetailReadTransactionTest`

상세 조회가 처음 읽는 Support `getById` 호출 시점에 트랜잭션이 있는지·읽기 전용인지 기록한다 (`@MockitoSpyBean`).

| 케이스 (트랜잭션 없는 Facade) | 기대 결과 |
|---|---|
| 요청서 거절 → 반환 실행 → 상세 | 마지막 읽기가 읽기 전용 트랜잭션 안 (`true/true`) |
| 모집글 조기 마감 → 반환 실행 → 상세 | 같음 |
| 출연 확인 → 지급 실행 → 회원 자격 확인 없는 상세 | 같음 |

- 읽기 전용 트랜잭션을 빼면 실패하는 것을 확인했다 (요청서 거절 케이스).

### 17.2 낙관적 락 충돌 — `AppearanceConcurrentUpdateTest`

스레드 타이밍에 기대지 않는다. 행을 읽은 직후 다른 트랜잭션(REQUIRES_NEW)이 같은 행의 버전을 먼저 올려 커밋한 상황을 만든다 (`@MockitoSpyBean`으로 `getById`를 가로챔).

| 케이스 | 기대 결과 |
|---|---|
| 요청서 기본 정보 수정 | 409 `CONCURRENT_UPDATE`, 제목 그대로 |
| 요청서 수락 | 409 `CONCURRENT_UPDATE`, 요청서 `REQUESTED` 그대로, 출연 없음 |
| 요청서 거절 (반환 기록) | 409 `CONCURRENT_UPDATE`, 요청서 그대로, HP 작업 없음 |
| 모집글 기본 정보 수정 | 409 `CONCURRENT_UPDATE`, 제목 그대로 |
| HP 작업 결과 기록 중 다른 실행이 먼저 기록 | 예외 없이 끝남, 먼저 기록한 결과(`DONE`, 시도 9) 그대로 (409 아님, APR-37 R2) |

## 18. 시나리오 전수 확인 (#297)

전체 시나리오 표는 저장소 밖 `hapbang_be/SCENARIOS.md`에 있다 (`시나리오 | 예상 결과 | 테스트 위치`, 확인 단계 🌐 E2E / 🔷 서비스 / 💰 실제 Billing / 🧩 단위).

### 18.1 테스트 시계 — `AppearanceTestClock`, `AppearanceTestClockTest`, `AppearanceTestClockWiringTest`

출연 통합 테스트(`@AppearanceIntegrationTest`)의 시계. 운영처럼 실제 시각으로 흐르되 테스트가 앞으로 돌릴 수 있고, 테스트마다 처음으로 돌아간다.
"기한이 지난 뒤"는 DB 값을 고치지 않고 시계를 실제 순서대로 돌려 만든다 (모집 마감 → 방송 → 확인 기간 끝, 다음 시도 시각, 배치 대기 시간).

| 케이스 | 기대 결과 |
|---|---|
| 앞당기지 않음 | 기준 시계와 같고 한국 시간대 |
| `advance` 여러 번 | 앞당긴 만큼 쌓임 |
| `advanceTo(목표)` / 이미 지난 목표 | 목표 1밀리초 뒤("같거나 지나면" 조건을 넘김) / 그대로 |
| 뒤로 돌리기 | `IllegalArgumentException` |
| `reset` | 앞당김 없음 |
| 시간대만 바꾼 시계 | 원래 시계의 앞당김을 따라감 |
| 주입 (통합) | `@Qualifier(CLOCK)` 주입은 테스트 시계, 시각을 쓰는 출연 빈 20개 이상이 모두 같은 시계, 운영 Clock 빈은 그대로 |
| 앞당긴 뒤 / 다음 테스트 | 실제 시각 + 10일 / 처음 시각으로 돌아가 있음 |

- 주의: 생성 시각(`created_at`)은 JPA Auditing이 실제 시각으로 기록한다 (팀 공통 test-support 설정). 시계를 앞당긴 뒤에 만든 행은 바로 그만큼 "오래된 행"이 되어 배치 대기 조건(1분)을 넘으므로, 대기 시간을 보는 테스트는 행을 먼저 만들고 시계를 돌린다.

### 18.2 오류 코드 HTTP — `AppearanceErrorCodeApiE2ETest`

서비스 테스트에서만 보던 오류 코드를 HTTP로 확인한다 (상태·`code`·`detail`). 출연 오류 코드 42개 중 41개가 E2E로 확인된다. `CONCURRENT_UPDATE`는 서비스 단계(17.2).

| 묶음 | 케이스 | 기대 결과 |
|---|---|---|
| 출연 요청 | 나에게 보내기 / 수락 기한이 방송 예정 일시 이후 | 400 `CANNOT_REQUEST_SELF` / 400 `INVALID_REQUEST_SCHEDULE` |
| 출연 요청 | 호스트가 자기 요청 수락 / 거절된 요청서 수정 | 403 `NOT_REQUEST_GUEST` / 409 `APPEARANCE_REQUEST_NOT_EDITABLE` |
| 출연 요청 | 수락 기한이 지남(만료 배치 전) | 409 `APPEARANCE_REQUEST_EXPIRED` |
| 출연 모집 | 모집 마감 일시가 방송 예정 일시 이후 / 마감된 모집글 수정 | 400 `INVALID_RECRUIT_SCHEDULE` / 409 `APPEARANCE_RECRUIT_NOT_EDITABLE` |
| 출연 | 호스트가 취소 요청 / 처리 중 요청 없이 응답 / 거절 두 번 뒤 세 번째 | 403 `NOT_APPEARANCE_GUEST` / 409 `CANCEL_REQUEST_NOT_PENDING` / 409 `CANCEL_REQUEST_LIMIT_EXCEEDED` |
| 출연 | 방송 예정 일시 지남: 취소 요청·일방 취소 / 확인 기간 끝: 확인·불참 | 409 `APPEARANCE_SCHEDULE_PASSED` / 409 `APPEARANCE_CONFIRM_PERIOD_ENDED` |

### 18.3 빈칸 채운 테스트

| 테스트 | 케이스 | 기대 결과 |
|---|---|---|
| `AppearanceBatchUseCaseTest` › 보류 재확인 | 보류 처리 중 모집글 재확인 중 Billing 거절(잔액 부족) | `HOLD_FAILED`, 같은 모집글로 보류 요청 2번, 내 목록·상세에 안 보임, 다시 가져가지 않음 |
| `AppearanceHostCancelFlowTest` › 일방 취소 | 일방 취소(확인함)와 합의 취소 수락 동시 | 한쪽만 성공: `CANCELED_BY_HOST`(요청 `CLOSED_BY_HOST_CANCEL`, 위약금만) 또는 `CANCELED_BY_AGREEMENT`(요청 `ACCEPTED`, 반환만) |
| `AppearanceHostCancelFlowTest` › 모집 전체 취소 | 모집 전체 취소(확인 없음)와 게스트 취소 요청 동시 | 취소가 먼저: 출연 `CANCELED_BY_HOST`·위약금 1건·요청 없음 / 요청이 먼저: 취소는 `CANCEL_REQUEST_CONFIRM_REQUIRED`(또는 충돌), 모집 `RECRUITING`·출연 `CANCEL_REQUESTED`·HP 작업 없음 |
| `AppearanceBatchScheduleTest` | appearance.yml cron 7개 | 모두 올바르고 매분 한 번, 서로 다른 초(0·5·10·20·30·40·50, 05초 알림 재발행은 #393) |
| `AppearanceBatchScheduleTest` | 스위치를 켜고 띄움 | `@Scheduled` 메서드 수 = 검사 표 7개, 7개가 yml cron 그대로 스케줄러에 등록 |
| `AppearanceBatchScheduleTest` | `@Scheduled` 기본값 | yml과 같고 시간대 `Asia/Seoul` |
| `AppearanceBatchScheduleTest` | 배치 메서드 호출 | 맞는 UseCase를 한 번씩 |

### 18.4 테스트 이름 정리

Billing이 대역인 출연 모듈 테스트에서 실제 잔액을 확인하는 것처럼 읽히던 표현("보류 0", "게스트 +30,000", "호스트 잔액 원래대로")을 "Billing에 ○○ 요청"으로 바꿨다 (9개, 이 문서 표도 같이). 실제 잔액은 16장(apps)에서만 확인한다.

## 19. 출연 수익 내부 조회 (#301)

### 19.1 분류 규칙 — `AppearanceEarningRuleTest` (단위)

| 케이스 | 기대 결과 |
|---|---|
| 성립·취소 요청 중·불참·합의 취소 + 작업 {없음, PENDING, DONE, DEAD} | 작업과 상관없이 `CONFIRMATION_PENDING`·`ON_HOLD`·`CANCELED`·`CANCELED` (출연료) |
| 확인 / 일방 취소 + PENDING·DEAD·DONE | `PROCESSING`·`FAILED`·`COMPLETED` (출연료 / 위약금) |
| 확인 / 일방 취소 + 작업 없음 | `FAILED` + 작업 없음 표시 (N2) |
| 모든 출연 상태 × 작업 4가지 | 모두 분류됨 |
| 조회 조건 | 지급 상태 128개 부분집합 × 종류 3개: 조건이 고르는 조합 = 분류가 그 상태·종류인 조합 |
| 빈 조건 | 상태 없음, 지급 예정(`SCHEDULED`)만, 위약금 + 확인 대기 → 조회하지 않음 |
| 작업 종류 | 지급·위약금만 (반환 제외) |

### 19.2 조회 — `AppearanceEarningQueryTest` (통합, 실제 흐름으로 상태를 만듦)

한 게스트에게 9건: 확인 대기 20,000, 보류(취소 요청 중) 21,000, 확인 + 지급 완료 30,000, 확인 + 결과 모름 22,000, 확인 + Billing 거절 23,000, 일방 취소 + 정산 완료(위약금 9,000), 일방 취소 + 결과 모름(위약금 9,000), 불참, 합의 취소.

| 묶음 | 케이스 | 기대 결과 |
|---|---|---|
| 분류 | 요약 | 확인 대기 20,000·1 / 지급 예정 0 / 보류 21,000·1 / 처리 중 31,000·2 / 실패 23,000·1 |
| 분류 | 목록 (전체 상태) | 9건, 상태·종류·금액 위와 같음. 일방 취소는 위약금 한 줄만, 원천 `APPEARANCE`·출연 ID, 제목, 지급 예정 시각 null |
| 분류 | 상태 기준 시각 | 완료·실패 = 작업 완료 시각, 처리 중 = 확인·일방 취소 시각, 취소 = 불참·합의 취소 시각, 확인 대기·보류 = 출연 수정 시각 |
| 분류 | 확인·일방 취소의 작업 | Billing 결과를 몰라도 지급·위약금 작업이 함께 기록됨 (위약금 9,000) |
| 분류 | 작업이 없는 확인 건(삭제로 재현) | 요약 실패 30,000·1, 목록 `FAILED` |
| 필터 | 기본 | 미지급 5건. 완료(출연료·위약금 2건)·취소(2건)는 고를 때만 |
| 필터 | 종류 | 위약금: 요약 처리 중 9,000·1, 목록 1건. 출연료: 미지급 4건 |
| 필터 | 맞는 것 없음 | 지급 예정(`SCHEDULED`)만, 위약금 + 확인 대기 → 빈 결과. 빈 상태 목록은 고르지 않은 것과 같음(미지급 5건) |
| 회원 | 모집 1:N | 성립 게스트 2명 각각 1인 출연료 한 건(원천 `APPEARANCE`·출연 ID, 모집글 제목), 미선정 게스트 없음 |
| 회원 | 다른 회원 | 호스트로 조회하면 빈 결과, 출연이 없는 회원은 0원·빈 목록 |
| 회원 | 회원 ID null·0·-1 | `INVALID_REQUEST` |
| 페이지 | 2건씩 끝까지 | 건수·금액 합계 = 요약, 중복 없음 |
| 페이지 | 같은 상태 기준 시각 4건 | 출연 ID 내림차순, 페이지 경계에서 중복·누락 없음 |
| 페이지 | 요청에 정렬(`sort=statusUpdatedAt`, `sort=fee`)이 옴 | 무시하고 고정 순서 그대로, 예외 없음 |
| 페이지 | 시각이 다름 | 나중에 확인한 출연이 먼저 |
| 권한 | 인플루언서 로그인만 | 요약·목록 `AccessDeniedException` (내부 호출 `ROLE_INTERNAL`만) |

### 19.3 상태 계약 — `AppearanceEarningStatusTest` (shared 단위)

| 케이스 | 기대 결과 |
|---|---|
| 상태 이름 | 강의와 같은 7개 (`CONFIRMATION_PENDING`·`SCHEDULED`·`ON_HOLD`·`PROCESSING`·`FAILED`·`COMPLETED`·`CANCELED`) |
| 미지급 | 완료·취소를 뺀 5개 |

### 19.4 내부 API — `AppearanceEarningInternalApiTest` (E2E, 실제 포트로 보안 체인부터 DB까지, #390)

흐름: 확인 대기 20,000 1건 + 확인했지만 Billing 결과 모름(처리 중) 22,000 1건.

| 케이스 | 기대 결과 |
|---|---|
| 요약 (키 있음) | 200 `RsData`, 메시지, 확인 대기 20,000·1 / 지급 예정 0 / 처리 중 22,000·1, `asOf` 없음. `earningType=APPEARANCE_PENALTY`면 0 |
| 목록 (`status` 2개 + `earningType`) | 1건: `sourceType` `APPEARANCE`, 출연 ID, `APPEARANCE_FEE`, 제목, 22,000, `PROCESSING`, `payoutScheduledAt` 필드 없음(#408), `statusUpdatedAt`은 offset 없는 시각 |
| 기본·페이지 (`size=1`) | 미지급 2건 중 최근(확인한 출연) 먼저, `page {size 1, number 0, totalElements 2, totalPages 2}`. 값 없는 `status=`도 미지급 2건(Spring이 빈 목록으로 바꿈). `status=SCHEDULED`만이면 빈 결과 |
| 출연이 없는 회원 | 0원 요약·빈 목록 |
| 요청 값 오류 | `userId` 없음(`errors[0].field`=`userId`), `userId=0`, `status=PAID`(`status`), `earningType=LECTURE_SALE`(`earningType`) → 400 `INVALID_REQUEST` |
| 인증 | 키 없음(본문 없는 401)·틀림·회원 로그인만 → 401 |
| 내부 키로 회원 API | `GET /api/v1/appearances` → 401 |

### 19.5 Client — `AppearanceApiClientTest` (shared 단위, 가짜 HTTP 서버)

강의 `LectureApiClientTest`와 같은 범위.

| 케이스 | 기대 결과 |
|---|---|
| 요약 | `userId`·키 헤더(종류를 주면 `earningType`)를 붙여 보내고 `data`를 돌려줌 |
| 목록 | `status` 여러 개·`earningType`·`page`·`size` 쿼리, 빠진 `payoutScheduledAt`·명시적 `null` 모두 null(#408), offset 없는 시각을 `LocalDateTime`으로 읽음 |
| 상태 null | 상태 조건 없이 보냄 |
| 4xx (ProblemDetail / 401 본문 없음) | `REJECTED` + 상태 + `code`(401은 null) |
| 연결 실패·시간 초과·503 | `UNAVAILABLE` (상태 0 / 0 / 503) |
| 깨진 JSON·모르는 상태 값·204 | `UNAVAILABLE` |
| 필수 필드 없음 (`data {}`, 항목 `{}`) | `UNAVAILABLE` |
| 500 ProblemDetail | `UNAVAILABLE` + `code` `INTERNAL_ERROR` |

### 19.6 그 밖

| 테스트 | 케이스 | 기대 결과 |
|---|---|---|
| `SharedAppearanceDependencyTest` (shared) | shared.appearance 의존 | 출연·다른 업무 모듈·global·shared.lecture를 참조하지 않음 |
| `AppearanceEarningSelfHttpIntegrationTest` (apps, 실제 포트) | Client 빈 | 앱 설정으로 1개 생김 |
| 〃 | Client로 자기 자신 호출 | 출연이 없는 게스트 0원 요약·빈 페이지 (`page {20, 0, 0, 0}`) |
| 〃 | 키가 틀린 Client | `REJECTED` 401 |
| 〃 | 명세 `/v3/api-docs` | 두 경로·`X-Internal-Api-Key` 헤더가 나오고 키 값은 없음 |

## 20. E2E를 실제 포트로 전환 (#390)

MockMvc E2E를 RestAssured(실제 포트)로 옮겼다. 검증 내용(상태 코드, `code`, 응답 필드, DB 상태)과 개수는 그대로다.

| 테스트 | 테스트 수 | 응답 검증 수 | 방식 |
|---|---|---|---|
| `AppearanceApiE2ETest` | 5 | 12 | RestAssured |
| `AppearanceApiCancelE2ETest` | 6 | 75 | RestAssured |
| `AppearanceApplicationApiE2ETest` | 10 | 67 | RestAssured |
| `AppearanceErrorCodeApiE2ETest` | 6 | 8 | RestAssured |
| `AppearanceRecruitApiE2ETest` | 10 | 67 | RestAssured |
| `AppearanceRequestApiE2ETest` | 11 | 55 | RestAssured |
| `AppearanceRequestRespondApiE2ETest` | 8 | 43 | RestAssured |
| `AppearanceEarningInternalApiTest` | 7 | 59 | RestAssured (내부 API 키 헤더) |
| `AppearanceSwaggerDocsTest`, `PageConfigTest`, `SecurityConfigTest`, `GlobalExceptionHandlerTest`, `InternalApiSecurityConfigTest`, `AppearanceExceptionHandlerTest` | — | — | MockMvc 유지 (명세·웹 슬라이스) |

| 케이스 | 기대 결과 |
|---|---|
| 본문이 선택인 API에 본문 없이 요청 | `E2ELogin`이 JSON 형식으로 보내므로 200·201 (RestAssured 기본 `text/plain`이면 415) |

## 21. 격리된 HP 작업 관리자 조회·다시 시도 (#397)

격리된(`DEAD`) HP 작업을 관리자가 확인하고 같은 멱등 키로 다시 실행한다. 업무 상태(거절·확인·취소)는 되돌리지 않는다.
다시 시도하면 시도 횟수를 0으로 되돌려 재시도 규칙을 처음부터 적용하고, 누가 언제 다시 시도했는지는 `hp_tasks`의 다시 시도 컬럼 3개에 남긴다.
격리 작업은 실제 흐름처럼 만든다: 작업 기록 → 1회 실행 → Billing 대역이 거절 → `DEAD`.

### 21.1 구성

| 종류 | 테스트 | 확인 대상 |
|---|---|---|
| 단위 | `HpTaskTest` (추가 4개) | 다시 시도 전이, 재시도 횟수 초기화, 다시 시도 기록 누적, DEAD가 아니면 거절 |
| 저장소 | `HpTaskRepositoryTest` (추가 2개) | 다시 시도한 작업의 배치 대기 시간 기준, 다시 시도 컬럼 제약 |
| 통합 | `HpTaskAdminFacadeTest` (11개) | 목록(상태·정렬·담는 값), 다시 시도 결과 3가지(성공·다시 거절·결과 모름), 다시 시도 뒤 배치 대기, 409·404, 두 번 누름, 복제본으로 관리자 재확인(정지·강등), 관리자만 |
| 통합 | `AppearanceConcurrentUpdateTest` (추가 1개) | 동시 다시 시도 → 409 `CONCURRENT_UPDATE` |
| E2E | `AppearanceHpTaskAdminApiE2ETest` (4개, RestAssured 실제 포트 — 20장 방식) | HTTP 응답(`RsData`·Problem Detail), 보안 필터 403·401, 강등된 관리자 403 `NOT_ADMIN`, 없는 상태 값 400 |
| 통합 | `AppearanceSwaggerDocsTest` (추가 1개) | 두 API의 성공 응답 스키마가 `RsData` |

### 21.2 단위 — `HpTaskTest`

| 케이스 | 기대 결과 |
|---|---|
| `DEAD` 작업을 관리자가 다시 시도 | `PENDING`, 시도 0회, 바로 실행 가능, 완료 시각 없음, 멱등 키·마지막 오류 그대로, 다시 시도 1회·관리자 ID·시각 기록 |
| 최대 2회 정책에서 2번 실패해 `DEAD` → 다시 시도 → 결과 모름 실패 | 바로 `DEAD`가 아니라 `PENDING`, 시도 1회, 1분 뒤 재시도. 한 번 더 실패하면 `DEAD` |
| 다시 시도 → 다시 `DEAD` → 다른 관리자가 다시 시도 | 다시 시도 2회, 마지막 관리자·시각은 두 번째 값 |
| 대기(`PENDING`)·완료(`DONE`) 작업 다시 시도 | `HP_TASK_NOT_DEAD`, 다시 시도 기록 없음 |

### 21.3 통합 — `HpTaskAdminFacadeTest`

| 묶음 | 케이스 | 기대 결과 |
|---|---|---|
| 목록 | `DEAD` 2건 + `DONE` 1건에서 `DEAD` 조회 | 2건, 마지막으로 바뀐 것부터. 작업 종류·대상·회원·금액·사유·멱등 키·시도 1회·마지막 오류(`HOLD_NOT_FOUND: …`)·격리 시각, 다시 시도 0회 |
| 목록 | `DONE` 조회 | 완료 작업만 |
| 다시 시도 | Billing 성공 | `DONE`, 시도 1회, 마지막 오류 지움, 다시 시도 1회·관리자 ID·시각. Billing 반환 요청 1건, 처음과 같은 요청 ID |
| 다시 시도 | Billing 응답 없음 | `PENDING`, 시도 1회, 다음 시도 시각이 지금 이후, 마지막 오류 기록. 시계를 다음 시도 시각으로 돌리면 재시도 배치가 같은 요청 ID로 실행해 `DONE` |
| 다시 시도 | 대기로 되돌린 뒤 실행 전에 끊김 (만든 지 3시간 지난 작업) | 재시도 배치가 바로는 가져가지 않음(Billing 호출 없음). 다시 시도한 지 1분이 지나면 가져가 같은 요청 ID로 실행해 `DONE` |
| 다시 시도 | Billing이 다시 거절 (`INSUFFICIENT_BALANCE`) | 바로 `DEAD`, 시도 1회, 새 오류 기록, 다시 시도 기록은 남음 |
| 다시 시도 | `DONE` 작업 | 409 `HP_TASK_NOT_DEAD`, Billing 호출 없음, 다시 시도 기록 없음 |
| 다시 시도 | 없는 작업 | 404 `HP_TASK_NOT_FOUND` |
| 다시 시도 | 성공한 뒤 한 번 더 누름 | 409 `HP_TASK_NOT_DEAD`, 다시 시도 1회 그대로 |
| 권한 | 로그인 역할은 관리자, 복제본이 정지 → 다시 시도·목록 / 복제본이 USER(강등) → 다시 시도 | 403 `APPEARANCE_USER_NOT_ACTIVE` ×2 / 403 `NOT_ADMIN`. 작업 `DEAD`·다시 시도 0회 그대로, Billing 호출 없음 |
| 권한 | 인플루언서가 목록·다시 시도 | `AccessDeniedException`, 작업은 `DEAD` 그대로 |
| 동시 (`AppearanceConcurrentUpdateTest`) | 격리 작업을 읽은 직후 다른 트랜잭션이 버전을 올려 커밋 | 409 `CONCURRENT_UPDATE`, 작업 `DEAD`·다시 시도 0회 그대로, Billing 호출 없음 |

### 21.3-1 저장소 — `HpTaskRepositoryTest`

| 케이스 | 기대 결과 |
|---|---|
| 다시 시도한 작업(시도 0회, 만든 지 3시간): 다시 시도 시각 기준 대기 시간 안 / 지남 | 재시도 대상 아님 / 대상. 만든 시각이 아니라 다시 시도 시각으로 센다 |
| 다시 시도 횟수만 있고 관리자·시각이 없는 행 저장 | `ck_hp_tasks_manual_retry` 위반 |

### 21.4 E2E — `AppearanceHpTaskAdminApiE2ETest`

| 케이스 | 기대 결과 |
|---|---|
| 관리자: 목록(`status` 생략) → 다시 시도 → 한 번 더 → `status=DEAD` 목록 | 200 메시지·`DEAD` 1건(종류·마지막 오류·격리 시각, `page.totalElements` 1) → 200 `DONE`·다시 시도 1회·관리자 ID → 409 `HP_TASK_NOT_DEAD` → 0건 |
| 없는 작업 다시 시도 / `status=UNKNOWN` | 404 `HP_TASK_NOT_FOUND` / 400 |
| 로그인 역할 관리자, 복제본 USER(강등): 다시 시도·목록 | 403 `NOT_ADMIN` ×2 |
| 인증된 인플루언서 목록·다시 시도 / 비로그인 목록 | 보안 필터에서 403 / 401. 작업은 `DEAD`, 다시 시도 0회 그대로 |

### 21.5 참고

- 두 관리자가 실제 스레드로 동시에 누르는 경우는 타이밍에 기대지 않도록, 위 동시 테스트처럼 "읽은 직후 다른 트랜잭션이 먼저 커밋"으로 만든다(17.2와 같은 방식). 늦게 누르면 이미 대기라 409 `HP_TASK_NOT_DEAD`다.
- 같은 작업이 두 곳에서 동시에 Billing에 가도 HP는 한 번만 움직인다: Billing이 지갑을 잠근 뒤 같은 요청 ID 기록을 확인해 두 번째는 처음 결과를 돌려주고, 출연은 대기 중일 때만 결과를 기록한다(버전 충돌이면 먼저 기록한 쪽). 실제 Billing으로 확인하는 일은 Mock Billing·HTTP 전환 작업에서 한다.

## 22. 출연 수익 내부 API 응답에서 null 필드 빼기 (#408)

#390에서 실제 포트로 확인해 보니 `payoutScheduledAt`이 `"payoutScheduledAt": null`로 나가 계약 문서(`docs/api/internal-earnings.md`, null 필드는 빠진다)와 달랐다. 계약 DTO `AppearanceEarningItemDto`의 이 필드에만 `@JsonInclude(NON_NULL)`을 붙였다.

| 테스트 | 케이스 | 기대 결과 |
|---|---|---|
| `AppearanceEarningInternalApiTest` (E2E) | 목록 | `data.content[0]`에 `payoutScheduledAt` 키가 없음 (`null` 값이 아니라 키 자체가 없음) |
| `AppearanceApiClientTest` (shared 단위) | 필드 없음·명시적 `null` 두 항목 | 둘 다 `payoutScheduledAt` = `null`로 읽음 (받는 쪽 Client는 응답 모양이 바뀌어도 같게 동작) |

## 23. Mock Billing 서버 (#391)

지갑(billing-app) 대신 지갑 내부 API(`docs/api/internal-billing.md`) 4개 경로를 흉내 내는 HTTP 서버다. 모듈 간 E2E는 보류하고, 출연이 지갑 응답(성공·재전송·거절·지연·장애)에 맞게 움직이는지 이 서버로 확인한다.

### 23.1 구성

| 구성 | 내용 |
|---|---|
| 도구 | WireMock 3.13.2 `wiremock-standalone`(테스트 의존성). 경로별 응답, 상태 시나리오(첫 요청 → 재전송), 지연·연결 끊김, 받은 요청 조회를 기본 기능으로 쓴다. 내부 라이브러리를 숨긴 standalone이라 Spring Boot 4·Jackson 3와 충돌하지 않는다 |
| `support/MockBilling` | 서버(JVM에 하나, 처음 쓸 때 띄움)와 시나리오 메서드: `succeed`·`replayAfterFirst(경로, 요청 ID)`·`replayHoldAfterFirst(참조 유형, 참조 ID)`·`fail(상태, code, detail)`·`delay`·`connectionReset`·`refusedBaseUrl`, 받은 요청 `requests(경로)`. 재전송은 멱등 키(요청 ID, 보류는 참조 대상) 단위이고 같은 내용의 재전송만 흉내 낸다(응답을 이번 요청 값으로 만들어서 같은 키·다른 내용도 `replayed = true`가 된다. 실제 지갑의 409 `IDEMPOTENCY_KEY_CONFLICT`는 `fail`로 확인한다). 서버를 띄울 때 4개 경로를 한 번씩 불러 응답 템플릿을 미리 컴파일한다(첫 응답이 느려 짧은 응답 대기 시간에 걸리지 않도록) |
| `support/MockBillingIntegrationTest` | 부모 클래스. `@AppearanceIntegrationTest`와 같되 `BillingClient`를 Mockito 대역으로 바꾸지 않아 운영 구현(`BillingHttpClient` → `BillingApiClient`)이 HTTP로 Mock을 부른다(#274) + Mock 주소·응답 대기 시간(1초) 설정 + Circuit Breaker가 열리지 않게(최소 호출 수 10,000) + 테스트마다 `MockBilling.reset()`. 설정을 한 곳에 둬서 상속한 테스트끼리 Spring 테스트 컨텍스트를 같이 쓴다 |
| 성공 응답 파일 | `src/test/resources/mock-billing/__files/{hold,release,payout,penalty}-success.json`. `*ResultDto`와 같은 필드이고 요청 값을 그대로 돌려준다(응답 템플릿). 계약이 바뀌면 함께 고친다 |
| 실패 응답 | 지갑과 같은 `ProblemDetail` + `code` (`application/problem+json`) |

쓰는 법:

```java
class SomeTest extends MockBillingIntegrationTest { // base-url = Mock, 테스트마다 reset

    @Test
    void example() {
        MockBilling.fail(Endpoint.HOLD, 409, "INSUFFICIENT_BALANCE", "HP 잔액이 부족합니다.");
        // ... 출연 흐름 실행 ...
        assertThat(MockBilling.requests(Endpoint.HOLD)).hasSize(1);
    }
}
```

- 테스트 클래스마다 `@DynamicPropertySource`를 따로 두지 않는다. 설정이 같아도 클래스마다 Spring 테스트 컨텍스트가 새로 떠서 느려진다.

`hapbang.clients.billing.base-url`이 생기면 shared `BillingApiClient` 빈이 만들어지고, 출연 `BillingHttpClient`가 그것을 쓴다(#274).
- Circuit Breaker: 상속한 테스트가 한 Client를 같이 써서 앞 테스트의 실패(5xx 10회 등)가 쌓이면 회로가 열려 뒤 테스트가 요청을 보내지 못한다. 그래서 열리지 않게 두고, 회로 동작은 shared `BillingApiClientTest`가 확인한다.

### 23.2 Mockito 대역(`BillingStubs`·`BillingCalls`)과의 관계

| 대역 | 쓰는 곳 (#274 반영) |
|---|---|
| `BillingStubs` (`BillingClient` Mockito 대역의 응답 정하기) | 출연 업무 규칙(상태·작업 기록·재시도)을 보는 테스트. 응답은 `BillingCallResult`·`BillingCallException`으로 운영 구현과 같은 모양이다 |
| `BillingCalls` (Mockito에 남은 호출 인자) | Billing에 보낸 요청 내용 확인. 받아들인 반환 합계(`releasedAmount`)는 대역이 성공으로 응답한 요청만 센다 |
| `MockBilling` | `BillingApiClient` 응답 분류(`MockBillingTest`, 23.3)와 출연 `BillingClient` HTTP 구현의 실패 분류(재시도·DEAD·`HOLD_PENDING`·사용자 응답, `AppearanceBillingHttpTest`, 24장) |

### 23.3 `MockBillingTest` (통합, 출연 테스트 컨텍스트 + 실제 `BillingApiClient`)

| 묶음 | 케이스 | 기대 결과 |
|---|---|---|
| 성공 | 보류·반환·지급·위약금 정산 | `Succeeded`, 응답 = 요청 값(참조 대상·요청 ID·받는 회원·원천·금액), 남은 보류 0, `replayed = false` |
| 성공 | 받은 요청 | 경로, 내부 API 키 헤더, JSON 형식, 요청 ID(보류는 없음), 금액. 부르지 않은 경로는 0건 |
| 성공 | 전액 반환(금액 null) | 요청에 금액 없음, 반환액 0으로 응답 |
| 재전송 | 같은 반환 요청 2번 | 첫 `replayed = false`, 두 번째 `true`·같은 반환액, 받은 요청 2건(같은 요청 ID) |
| 재전송 | 시나리오를 건 요청 ID와 다른 요청 ID를 섞어 보냄 | 다른 ID는 매번 `false`(각자 처음 처리), 건 ID를 다시 보낼 때만 `true` |
| 재전송 | 보류: 같은 참조 대상 / 다른 참조 대상 | 같은 참조 대상 두 번째만 `true`, 다른 참조 대상은 `false` |
| 거절 | 409 `INSUFFICIENT_BALANCE` | `Failed`: 코드, `detail` "HP 잔액이 부족합니다.", 409, `rawCode` |
| 거절 | 409 `IDEMPOTENCY_KEY_CONFLICT` | `Failed` 같은 코드 |
| 거절 | 404 처음 보는 코드 / 400 코드 없음 | `UNMAPPED`, `rawCode` `SOMETHING_NEW` / `UNMAPPED`, 400 |
| 결과 없음 | 429 | `RETRYABLE`, 429 |
| 결과 없음 | 500 `HOLD_STATE_INCONSISTENT` | `UNKNOWN_RESULT`, 500, `rawCode` |
| 결과 없음 | 응답 2초 지연 (응답 대기 1초) | `UNKNOWN_RESULT`, 상태 0. 지갑은 요청을 받음(1건) |
| 결과 없음 | 응답 도중 연결 끊김 | `UNKNOWN_RESULT`, 상태 0 |
| 결과 없음 | 연결 거부 (듣지 않는 포트) | `RETRYABLE`, 상태 0 |

## 24. 지갑 HTTP 연동 전환 (#274)

출연 → 지갑 호출을 shared `BillingPort`(같은 JVM Java 호출)에서 지갑 내부 HTTP API(공용 `BillingApiClient`)로 바꿨다. 출연 업무 코드는 `BillingClient`만 알고, 응답은 예외 클래스가 아니라 `BillingCallResult`(성공·거절)와 `BillingCallException`(결과를 받지 못함)으로 분기한다. 처리 표는 module.md 4.2.

### 24.1 `AppearanceBillingHttpTest` (통합, Mock Billing 서버 + 운영 `BillingClient` 구현)

`MockBillingIntegrationTest`를 상속한다. 출연 Facade·배치를 실제로 실행하고, 지갑은 HTTP로 Mock Billing을 부른다.

| 묶음 | 케이스 | 기대 결과 |
|---|---|---|
| 보류 | 성공 | `REQUESTED`. 지갑이 받은 요청: 내부 API 키, JSON, 호스트·`APPEARANCE_REQUEST`·요청서 ID·출연료 |
| 보류 | 잔액 부족 409 (지갑 설명 있음) | `AppearanceHoldRejectedException` 409 `INSUFFICIENT_BALANCE`, 메시지는 출연 문구(지갑 설명 아님), `HOLD_FAILED` |
| 보류 | 목록 밖 코드 401 `INVALID_INTERNAL_API_KEY` | 500 `HP_HOLD_FAILED`, 메시지는 출연 문구(지갑 코드·설명이 나가지 않음), `HOLD_FAILED` |
| 보류 | 409 `IDEMPOTENCY_KEY_CONFLICT` | 500 `HP_HOLD_FAILED`, `HOLD_FAILED` |
| 보류 | 5xx → 지갑 복구 → 재확인 배치 | 503 `BILLING_UNAVAILABLE`·`HOLD_PENDING` → 재확인 1건 → `REQUESTED`. 지갑은 같은 요청서로 보류 요청 2번을 받음 |
| 보류 | 응답 2초 지연(응답 대기 1초)인데 지갑은 받음 → 재확인 | `BILLING_UNAVAILABLE`·`HOLD_PENDING`, 지갑은 1건 받음 → 재확인으로 `REQUESTED` |
| 보류 | 모집글 429 (처리하지 않음) | 공용 Client가 짧게 다시 보낸 뒤에도 실패 → `BILLING_UNAVAILABLE`, 모집글 `HOLD_PENDING` |
| HP 작업 | 반환 성공 (게스트 거절) | 반환 작업 `DONE`. 지갑이 받은 반환 요청 ID = 작업 멱등 키, 호스트, 출연료 |
| HP 작업 | 위약금 정산 성공 (호스트 일방 취소) | 위약금 작업 `DONE`. 지갑이 받은 취소 ID = 작업 멱등 키, 게스트, 반환액 + 위약금 = 출연료 |
| HP 작업 | 지급은 됐는데 완료 기록이 빠짐(SQL로 `PENDING`) → 다시 실행 | `DONE`. 지갑은 같은 지급 요청 ID로 2번 받음(지갑이 처음 결과를 돌려줘 한 번만 지급) |
| HP 작업 | 응답 시간 초과인데 지갑은 처리함 → 다음 시도 | `PENDING`·시도 1·`UNKNOWN_RESULT: …` → 다음 시도에 `DONE`, 같은 지급 요청 ID 2번 |
| HP 작업 | 반환 429 | `PENDING`, 마지막 오류 `RETRYABLE 429: …` |
| HP 작업 | 지급 409 `IDEMPOTENCY_KEY_CONFLICT` | 재시도 없이 `DEAD`(시도 1), 마지막 오류 `IDEMPOTENCY_KEY_CONFLICT: {지갑 설명}`. 출연은 `CONFIRMED`, 지급 상태 `DEAD` |
| HP 작업 | 지급 목록 밖 코드 422 `NEW_WALLET_RULE` | `DEAD`, 마지막 오류 `UNMAPPED(NEW_WALLET_RULE, 422): 새 지갑 규칙` |
| HP 작업 | 지급 401(내부 API 키 오류, 본문 없음) → 키 복구 → 다음 시도 | 연동 장애라 `DEAD`가 아니라 `PENDING`·시도 1, 마지막 오류 `UNMAPPED(null, 401)`(계약상 401은 본문이 없다) → 다음 시도에 `DONE`, 같은 지급 요청 ID 2번 |
| HP 작업 | 지급 5xx 계속 → 관리자 다시 시도 | 10회째 `DEAD`, 마지막 오류 `UNKNOWN_RESULT 500 HOLD_STATE_INCONSISTENT: …` → 지갑 복구 뒤 관리자 다시 시도(#397) → `DONE`. 지갑은 같은 지급 요청 ID로 11번 받음 |

### 24.2 기존 테스트에서 바뀐 것

| 테스트 | 바뀐 것 | 이유 |
|---|---|---|
| `support/BillingStubs` | 기본 성공을 `SucceedByDefault` 확장이 테스트마다 건다(요청 값을 담은 `Succeeded`). 거절은 `Failed`를 돌려주고, 결과를 받지 못함은 `BillingCallException(UNKNOWN_RESULT)`을 던진다. `Rejection`은 지갑 코드·HTTP 상태·설명 | 운영 구현과 같은 모양으로 응답해야 UseCase 분기를 그대로 확인한다. Mockito 기본값(null)은 운영에 없는 응답이다 |
| `com.hapbang.shared.billing.out.BillingCallExceptions` (출연 테스트 코드) | `BillingCallException`을 만든다 | 생성자가 공용 Client 패키지 전용이라 테스트 코드만 같은 패키지에 둔다 |
| `support/BillingCalls.releasedAmount` | 대역이 성공으로 응답한 반환만 센다(`BillingStubs`가 기록) | 기본 성공도 설정(stub)이 돼 "설정이 걸린 호출 = 실패" 판단을 쓸 수 없다 |
| `AppearanceRequestFacadeTest`·`AppearanceRecruitFacadeTest` 보류 거절 | 잔액 부족 → `AppearanceHoldRejectedException` 409 `INSUFFICIENT_BALANCE`(출연 문구), 지갑 없음 → 500 `HP_HOLD_FAILED` | 지갑 오류를 그대로 전달하지 않고 출연 코드로 바꾼다 |
| `AppearanceRequestApiE2ETest` 잔액 부족 | `detail`이 출연 문구 | 같음 |
| `AppearanceBatchUseCaseTest`·`AppearanceRequestRespondFacadeTest` 무응답 마지막 오류 | `IllegalStateException: …` → `UNKNOWN_RESULT: …` | 결과를 받지 못한 실패는 종류·상태·지갑 코드로 기록한다 |

## 25. 알림 이벤트 Kafka 발행 (#393)

요청 도착·지원 도착 이벤트를 Modulith 외부화로 토픽 `appearance.events.v1`에 내보낸다(구성은 `module.md` 11.3, 팀 규칙은 `kafka.md` #339). 다른 출연 테스트는 외부화를 끄고(`hapbang-module-test.yaml`), 이 테스트만 `KafkaTestcontainersConfig`(`apache/kafka:4.2.1`)를 가져와 켠다.

### 25.1 `AppearanceEventKafkaTest` (통합, Testcontainers Kafka)

토픽은 운영과 같은 이름 하나를 같이 쓰고, 앱이 뜰 때 선언한다. 테스트마다 새 소비자 그룹을 토픽 끝으로 옮긴 뒤 읽어 앞 테스트의 메시지를 보지 않고, 그중 그 테스트가 만든 key의 메시지만 본다. 보내기는 커밋 뒤 비동기라 15초 안에 기다린다. 외부화를 켠 컨텍스트를 따로 띄우므로 끝나면 닫는다(`@DirtiesContext`, 테스트 DB 연결 상한).

| 케이스 | 기대 결과 |
|---|---|
| 앱 기동 | 토픽 `appearance.events.v1`이 partition 3개로 있음 |
| 요청서 보내기 성공(커밋) | 메시지 1건. key `appearance-request:<요청서 ID>`, 헤더 `eventType` = `AppearanceRequestSentEvent`·`eventVersion` = `1`, `eventId` 헤더 없음, `__TypeId__` = 레코드 전체 클래스 이름(JSON 직렬화기, #394에서 Modulith JSON 기본 세트를 끈 뒤에도 그대로). 본문을 shared `AppearanceRequestSentEvent`로 읽으면 요청서 ID·호스트·게스트·제목·출연료·방송 예정 일시·수락 기한이 같음 |
| 지원(커밋) | 메시지 1건. key `appearance-application:<지원 ID>`, `eventType` = `AppearanceApplicationSubmittedEvent`, 본문의 지원 ID·모집글 ID·모집글 제목·호스트·게스트·지원 시각 |
| 발행한 트랜잭션 롤백 | 롤백한 이벤트는 나가지 않음 (뒤에 커밋한 이벤트가 도착한 뒤 확인) |
| 첫 보내기의 응답 유실(브로커에는 도착, 앱은 실패로 받음) → 시계를 2분 앞당겨 재발행 배치(`AppearanceEventRepublishUseCase.republishDue()`) 1회 | 배치가 1건을 다시 보냄. 같은 key로 2건 이상, `eventType`·본문이 같음(받는 쪽이 `(eventType, 요청서 ID)`로 거름) |

- 응답 유실은 `KafkaTemplate`을 `@MockitoSpyBean`으로 감싸 첫 `send`만 실제로 보낸 뒤 실패를 돌려주게 만든다.
- 발행 시점·횟수·내용(같은 JVM)은 기존 `AppearanceEventPublishTest`가 그대로 확인한다.

### 25.2 `AppearanceExternalEventsTest` (단위)

| 케이스 | 기대 결과 |
|---|---|
| 요청 도착·지원 도착 | 내보냄 (`@Externalized`) |
| 출연 취소 요청 이벤트 3종 | 내보내지 않음 |
| 회원 이벤트(`UserJoinedEvent`·`UserModifiedEvent`) | 내보내지 않음 (다른 컨텍스트 이벤트) |

### 25.3 `AppearanceEventRepublishUseCaseTest` (단위, `IncompleteEventPublications` 대역)

미완료 기록 목록을 Modulith처럼 발행 시각 순으로 재발행 조건에 넘긴다. `batch-size` 3, 대기 시간 1분.

| 케이스 | 기대 결과 |
|---|---|
| 내보내는 이벤트 5건(10·8·7·6·5분 전) + 취소 요청 이벤트 1건(9분 전) + 방금(30초 전) 1건 | 10·8·7분 전 3건만 고름(오래된 순), 반환 3, 로그 `count=3, waiting=2, batchSize=3` |
| 취소 요청 이벤트와 방금 발행한 기록뿐 | 고르지 않음, 반환 0, 재발행 로그 없음 |

## 26. 지갑 회로가 열려 있으면 배치 건너뛰기 (#439)

보류 재확인·HP 작업 재시도 배치는 `BillingClient.isCircuitOpen()`이 `true`면 대상을 가져오지 않고, 건마다 보내기 전에도 다시 봐서 회차 도중에 열리면 멈춘다. 시도 횟수가 열린 회로 거절로 닳지 않게 하기 위해서다.

### 26.1 `AppearanceBatchUseCaseTest` (통합, Billing Mockito 대역)

| 묶음 | 케이스 | 기대 결과 |
|---|---|---|
| 보류 재확인 | 대역이 회로 열림 → 재확인 → 닫힘 → 재확인 | 열린 동안 0건, 보류 요청 없음, `HOLD_PENDING` 그대로 → 닫히면 1건 `REQUESTED` |
| HP 작업 재시도 | 반환 실패 작업, 다음 시도 시각 지남, 대역이 회로 열림 → 재시도 → 닫힘 → 재시도 | 열린 동안 0건, 반환 요청 없음, `PENDING`·시도 1 그대로, 대기 수 지표 1로 갱신 → 닫히면 같은 반환 요청 ID로 `DONE` |
| HP 작업 재시도 | 반환 작업 3건, 대역은 반환 요청을 한 번 받으면 회로 열림 | 1건만 실행, 반환 요청 1번, 나머지 2건 `PENDING`·시도 0 그대로 |
| HP 작업 재시도 | 회로는 닫혀 보이지만 반환이 `CIRCUIT_OPEN`(시험 호출 몫 참)으로 실패 → 대역 복구 → 재시도 | `PENDING`·시도 1 그대로, 마지막 오류 `RETRYABLE CIRCUIT_OPEN: …`, 다음 시도 시각만 늦춰짐 → 복구 뒤 `DONE`(시도 2) |

Mockito 대역의 `isCircuitOpen()` 기본값은 `false`라 다른 테스트는 바뀌지 않는다. `BillingCalls.none`은 회로 상태 확인을 요청으로 세지 않는다(배치가 매번 부르므로).

### 26.2 `AppearanceBillingCircuitTest` (통합, Mock Billing 서버 + 실제 공용 Client 회로)

`MockBillingIntegrationTest`는 회로가 열리지 않게 두므로 따로 설정한다: 최근 2번 중 절반 실패면 열림, 1초 뒤 시험 중(HALF_OPEN), 시험 호출 1번 성공이면 닫힘. 설정이 달라 Spring 테스트 컨텍스트가 따로 뜨고, 끝나면 닫는다(`@DirtiesContext`, 열어 두면 캐시된 컨텍스트들의 DB 연결이 테스트 DB 한도를 넘음). 테스트마다 회로를 닫고 끝내고, 시작할 때 닫혀 있는지 확인한다. 회로 시간은 실제 시간이라 시험 중으로 넘어가기를 최대 5초 기다린다.

| 케이스 | 기대 결과 |
|---|---|
| 보류 성공 + 지급 5xx로 회로 열림 → 다음 시도 시각에 재시도 배치 → 시험 중으로 넘어감 → 재시도 배치 | 열린 동안 0건, 지갑이 받은 지급 1번·시도 1 그대로 → 같은 지급 요청 ID로 `DONE`(시도 2, 지급 2번), 회로 닫힘 |
| 요청서·모집글 보류 5xx로 회로 열림(둘 다 `HOLD_PENDING`) → 재확인 배치 → 시험 중으로 넘어감 → 재확인 배치 | 열린 동안 0건, 지갑이 받은 보류 2번 그대로 → 2건 정리(`REQUESTED`·`RECRUITING`), 회로 닫힘 |
| 반환 작업 3건, 지갑 5xx → 재시도 배치 → 시험 중(지갑 여전히 5xx) → 재시도 배치 → 지갑 복구·시험 중 → 재시도 배치 | 닫힌 회로에서 2건 실패로 열려 3번째는 보내지 않음(시도 1·1·0) → 시험 중 1건만 보내고 실패해 다시 열림(시도 합 3, 반환 요청 3번) → 1건 성공으로 닫혀 3건 모두 `DONE` |

## 27. Gateway 사용자 헤더 인증 (#395)

출연 앱이 Gateway가 넘긴 `X-User-Id`·`X-User-Role`로 로그인 상태를 만든다(`module.md` 20장). 세션·CSRF는 없앴다. 테스트 로그인도 같은 헤더로 바꿨다: MockMvc `GatewayLogin.as`, E2E `E2ELogin.as`. 테스트 전용 세션 로그인 경로(`E2ELoginConfig`)는 지웠다.

### 27.1 `GatewayUserHeaderFilterTest` (웹 슬라이스, 실제 `SecurityConfig`)

| 케이스 | 기대 결과 |
|---|---|
| `X-User-Id: 7`, `X-User-Role: INFLUENCER` | 200, `getName()` = `7`, 권한 `ROLE_INFLUENCER` 하나 |
| 헤더 없음 | 401 |
| 한쪽만(`7`/없음, 없음/`INFLUENCER`), ID `abc`·`0`·`-1`·빈 값·` 7`, 역할 `ROLE_INFLUENCER`·`influencer`·`SUPER`·빈 값 (11개) | 401, 경고 로그 `사용자 헤더 형식 오류로 인증하지 않음` |
| 헤더 로그인으로 CSRF 토큰 없이 POST | 200, `Set-Cookie` 없음, 세션 없음 |

### 27.2 기존 테스트에서 바뀐 것

| 테스트 | 바뀐 것 |
|---|---|
| `SecurityConfigTest`·`PageConfigTest`·`GlobalExceptionHandlerTest` | 로그인을 `TestLogin`(OAuth2 흉내) → `GatewayLogin`(헤더, 실제 필터를 거침). `csrf()`와 구글 등록 정보 설정 제거 |
| `InternalApiSecurityConfigTest` | 회원 로그인을 세션 → 사용자 헤더. 사용자 헤더만으로는 내부 API 401(올바른 키와 함께 오면 `ROLE_INTERNAL`만). CSRF 케이스는 "회원 API도 토큰 없이 POST 200"으로 |
| `AppearanceApiE2ETest` | 세션 쿠키가 내려가지 않음, 헤더 역할은 인플루언서지만 복제본이 일반 유저면 403 `NOT_INFLUENCER` 추가 |
| `AppearanceRequestApiE2ETest` | "CSRF 없음 403" → "사용자 헤더 없음 401, `X-User-Id`만 401" |
| `AppearanceApiCancelE2ETest` | "CSRF 없음 403" → "관계없는 회원의 취소 요청 404 `APPEARANCE_NOT_FOUND`" |

권한 테스트(비로그인 401, 역할 부족 403, 다른 사람 자원 404, 정지 회원 거절)는 기존 E2E·Facade 테스트가 헤더 로그인으로 그대로 확인한다.

