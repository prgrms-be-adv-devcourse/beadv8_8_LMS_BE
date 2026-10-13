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
| 저장소 | `AppearanceUserRepositoryTest` | `appearances.users` 저장·조회 | #31 |
| 통합 | `AppearanceModuleIntegrationTest` | 모듈 전체 기동 | #26 |
| 통합 | `AppearanceUserServiceTest` | 출연 회원 생성·갱신, 자격 확인 | #31, #50 |
| 통합 | `AppearanceUserEventListenerTest` | 회원 가입·정보 변경 이벤트 수신 | #31, #50 |
| E2E | `AppearanceApiE2ETest` | 세션 로그인 → 자격 확인 → 오류 응답, 회원 정지 반영 (앱 전체) | #31, #50 |
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

### 4.3 출연 회원 관리 — `AppearanceUserServiceTest`

| 케이스 | 기대 결과 |
|---|---|
| 가입 정보로 `createIfAbsent` | 출연 회원 생성 |
| 일반 유저 생성 → `update`(INFLUENCER) → 같은 가입 정보로 다시 `createIfAbsent` | 1명 그대로, 역할 INFLUENCER 유지 (가입 정보 무시) |
| 있는 회원을 `update` (역할 USER → INFLUENCER) | 1명 그대로, 역할만 갱신 |
| 없는 회원을 `update` | `APPEARANCE_USER_NOT_FOUND`, 출연 회원 생성 안 됨 |
| 인플루언서로 `getParticipant` | 출연 회원 반환 |
| 없는 회원으로 `getParticipant` | `APPEARANCE_USER_NOT_FOUND` |
| 일반 유저로 `getParticipant` | `NOT_INFLUENCER` |
| 인플루언서를 정지 상태로 `update`한 뒤 `getParticipant` | `APPEARANCE_USER_NOT_ACTIVE` |

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

---

## 5. E2E 테스트

### 5.1 API 흐름 — `AppearanceApiE2ETest`

앱 전체(보안 필터, 세션 로그인, 서비스, DB)를 띄우고 MockMvc로 요청한다. 로그인은 팀 공통 `TestLogin`으로 만든다.
아직 출연 API가 없어서 테스트 전용 컨트롤러로 확인하며, 출연 API가 생기면 실제 API로 검증한다.

| 케이스 | 기대 결과 |
|---|---|
| 로그인하지 않고 요청 | 401 (보안 필터) |
| 인플루언서로 로그인 | 200, 세션의 회원 ID가 `@LoginUserId`로 들어와 자격 확인 통과 |
| 로그인했지만 출연 회원 정보 없음 | 404 Problem Detail, `code: APPEARANCE_USER_NOT_FOUND` |
| 일반 유저로 로그인 | 403 Problem Detail, `code: NOT_INFLUENCER`, 한글 `detail` 정상 (UTF-8) |
| 인플루언서로 요청 성공 → 회원 정지 이벤트(`UserModifiedEvent`) 반영 → 같은 로그인으로 다시 요청 | 200 → 403 Problem Detail, `code: APPEARANCE_USER_NOT_ACTIVE` |

---

## 6. 모듈 경계 — `ModularityTests` (apps)

| 케이스 | 기대 결과 |
|---|---|
| 전체 모듈 구조 검증 | 다른 모듈의 내부 코드를 참조하지 않음 (출연 → `global`은 공개된 `auth`만 사용) |
