# 출연(Appearance) 모듈: 정책과 구조

출연 모듈이 지키는 정책, 모듈 구조, 다른 모듈과의 연동, DB 스키마를 정리한 문서다.
현재 구현된 범위(#26, #31)를 기준으로 하며, 기능이 추가될 때마다 함께 갱신한다.

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
- 로그인·회원 가입 → 회원 모듈 (출연 모듈은 세션의 회원 ID와 가입 이벤트만 사용)
- 이의 제기 판정·패널티 → 고객센터 모듈

### 1.3 구현 현황

| 기능 | 상태 | 이슈 |
|---|---|---|
| 모듈 기반 (정책 설정, Clock, 오류 응답, Billing 계약과 Fake) | ✅ | #26 |
| 출연 회원 복제, 호스트·게스트 자격 확인, 팀 공통 로그인 적용 | ✅ | #31 |
| 출연 요청 / 출연 모집·지원 / 출연 취소 요청 / 출연 확인 | ⬜ 예정 | — |

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
├── app           시나리오 조립 (Service)
├── in            바깥에서 들어오는 곳 (이벤트 리스너, 이후 Controller·Scheduler)
├── out           바깥으로 나가는 곳 (Repository, Billing 연동)
├── global        전역 global 모듈로 옮길 후보 (Problem Detail 오류 응답)
└── shared        전역 shared 모듈로 옮길 후보 (서비스 시간대)
```

- 의존 방향: `in → app → domain ← out`
- `global`, `shared` 패키지는 팀 공통 방식이 정해지지 않아 출연 모듈 안에 임시로 둔 것이다. 옮길 조건은 각 `package-info.java`에 적었다.

### 3.2 주요 클래스

| 클래스 | 역할 |
|---|---|
| `config.AppearanceConfig` | `AppearancePolicy` 등록, `appearanceClock` 빈 |
| `domain.AppearancePolicy` | 정책 수치와 계산 |
| `domain.AppearanceUser` | 출연 회원(복제 회원). `validateCanAppear()`로 참여 자격 확인 |
| `domain.AppearanceErrorCode`, `AppearanceException` | 오류 코드와 업무 예외 |
| `app.AppearanceUserService` | `sync()`: 회원 정보 반영 (없으면 생성, 있으면 갱신) / `getParticipant()`: 자격 확인 후 출연 회원 반환 |
| `in.AppearanceUserEventListener` | 회원 가입 이벤트 수신 → `sync()` |
| `out.AppearanceUserRepository` | 출연 회원 저장·조회 |
| `out.billing.BillingClient` | Billing과 주고받는 값(계약) |
| `out.billing.FakeBillingClient` | 계약 확정 전까지 쓰는 메모리 구현 |
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
| 회원 | 이벤트 수신 | `UserJoinedEvent` → 출연 회원 생성·갱신 | ✅ 가입만. 정보 변경·역할 변경·탈퇴 이벤트는 추가되면 반영 |
| 회원 (global) | 로그인 | `@LoginUserId`로 세션의 회원 ID | ✅ |
| 지갑+정산 (billing) | HTTP API | 보류, 반환, 지급, 일괄 정산 | 계약 초안 + Fake 구현 |
| 고객센터 | 이벤트 발행 | 이의 제기 (패널티 요청) | 예정 |

### 4.1 회원 정보 복제

```
회원 모듈 ──UserJoinedEvent──▶ AppearanceUserEventListener
                                  └─ AppearanceUserService.sync() ─▶ appearances.users
```

- `@ApplicationModuleListener`: 회원 모듈의 트랜잭션이 커밋된 뒤 새 트랜잭션에서 처리한다. 회원 가입이 롤백되면 복제도 만들지 않는다.
- 같은 회원의 이벤트를 다시 받으면 새로 만들지 않고 정보만 갱신한다.
- 처리에 실패하면 Modulith 이벤트 저장소에 남아 다시 처리된다.

### 4.2 Billing 연동 (계약 초안)

출연 모듈은 "언제, 어떤 금액·사유로 Billing을 호출하는지"만 책임진다. 계약은 `out/billing/BillingClient.java` 한 파일에 있다.

| 동작 | 요청 값 | 응답 값 |
|---|---|---|
| 보류 | idempotencyKey, refType, refId, hostId, amount | `HELD` / `INSUFFICIENT_BALANCE`, 보류 총액 |
| 반환 | idempotencyKey, refType, refId, hostId, amount, reason | 남은 보류 금액 |
| 지급 | idempotencyKey, refType, refId, appearanceId, guestId, amount, reason | 남은 보류 금액 |
| 일괄 정산 | idempotencyKey, refType, refId, items(지급/반환, 받는 사람, 출연 ID, 금액, 사유) | 남은 보류 금액 (전부 성공 또는 전부 실패) |

- `refType`: `APPEARANCE_REQUEST`(출연 요청서) / `APPEARANCE_RECRUIT`(출연 모집글). 보류는 이 단위로 1건이고, 반환·지급은 나눠서 처리한다.
- 멱등 키 형식: `{refType}-{refId}-{동작}-{순번}`. 같은 키로 다시 요청하면 처음 결과를 돌려받는다.
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
