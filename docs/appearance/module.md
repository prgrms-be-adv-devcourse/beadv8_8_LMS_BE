# 출연(Appearance) 모듈: 정책과 구조

출연 모듈이 지키는 정책, 모듈 구조, 다른 모듈과의 연동, DB 스키마를 정리한 문서다.
현재 구현된 범위(#26, #31, #50, #98, #144, #137, #168, #175, #187, #195)를 기준으로 하며, 기능이 추가될 때마다 함께 갱신한다.

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
| 출연 요청 API 성공 응답 `RsData` | ✅ | #144 |
| 출연 요청 응답(수락·거절·응답 전 취소), 출연 성립, HP 작업(반환) 기록·즉시 실행 | ✅ | #137 |
| HP 작업 재시도·격리, 수락 기한 만료, 보류 재확인 (배치) | ✅ | #187 |
| 출연 모집글 올리기(HP 보류)·조회·기본 정보 수정 | ✅ | #175 |
| 모집 지원·지원 취소·수락(출연 성립)·거절, 모집글 지원자 수 | ✅ | #195 |
| 모집 마감 (기한 마감 배치·조기 마감, 대기 지원 정리, 미달 인원분 반환) | ✅ | #222 |
| 출연 알림 이벤트 발행 (요청 도착·지원 도착, shared 이벤트) | ✅ | #227 |
| 출연 조회, 출연 취소 요청·수락(합의 취소)·거절·자동 거절, 취소 알림 이벤트 | ✅ | #231 |
| 호스트 일방 취소(위약금 30%)·모집 전체 취소 | ✅ | #239 |
| 출연 확인·이의 제기 | ⬜ 예정 | — |

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

정책 수치는 코드에 직접 쓰지 않고 `AppearancePolicy`로만 사용한다. 값은 `boundedContext/appearance/src/main/resources/appearance.yml`에 있고, `config.AppearancePolicyProperties`가 읽어 `AppearancePolicy`로 바꾼다.

| 항목 | 값 | 설정 키 | 계산 메서드 |
|---|---|---|---|
| 최저 출연료 (1인당) | 20,000 HP | `appearance.policy.minimum-fee` | `isBelowMinimumFee(fee)` |
| 호스트 일방 취소 위약금 | 출연료의 30%, 원 단위 미만 버림 | `appearance.policy.penalty-rate-percent` | `calculatePenalty(fee)` |
| 출연 확인 기간 | 방송 예정 일시 + 7×24시간. 지나면 자동 확인(지급) | `appearance.policy.confirm-period` | `confirmDeadline(scheduledAt)` |
| 출연 취소 요청 최대 횟수 | 출연 1건당 2회 (거절 후 1회 재요청) | `appearance.policy.max-cancel-request-count` | `canRequestCancel(count)` |
| 모집 인원 | 1명 ~ 10명 (최소 1명은 고정, 최대는 설정) | `appearance.policy.max-recruit-count` | `isRecruitCountAllowed(count)` |

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
- 이벤트 리스너는 로그인 정보가 없으므로 `@PreAuthorize`가 없는 별도 Facade를 쓴다 (예: `AppearanceUserFacade`).
- 배치(`in.AppearanceBatch`)는 시스템이 실행하므로 Facade를 거치지 않고 UseCase를 직접 부른다 (팀 강의 배치와 같은 방식, 8장).
- `global`, `shared` 패키지는 팀 공통 방식이 정해지지 않아 출연 모듈 안에 임시로 둔 것이다. 옮길 조건은 각 `package-info.java`에 적었다.

### 3.2 주요 클래스

| 클래스 | 역할 |
|---|---|
| `config.AppearanceConfig` | `AppearancePolicyProperties`를 도메인 `AppearancePolicy`로 바꿔 등록, `appearanceClock` 빈 |
| `config.AppearancePolicyProperties` | `appearance.policy.*` 설정 바인딩 (`@ConfigurationProperties`). 도메인이 Spring을 모르도록 여기서만 바인딩 |
| `domain.AppearancePolicy` | 정책 수치와 계산 (Spring 의존 없는 순수 record, 값 검증) |
| `domain.AppearanceUser` | 출연 회원(복제 회원). `validateCanAppear()`로 참여 자격 확인 |
| `domain.AppearanceErrorCode`, `AppearanceException` | 오류 코드와 업무 예외 |
| `app.AppearanceUserFacade` | 회원 이벤트 리스너의 진입점: `syncJoined()` / `syncModified()` (트랜잭션, 역할 검사 없음) |
| `app.AppearanceUserSyncUseCase` | `createIfAbsent()`: 가입 정보로 생성 (있으면 무시) / `update()`: 변경 정보로 갱신 (없으면 실패) |
| `app.AppearanceUserSupport` | `getParticipant()`: 자격 확인 후 출연 회원 반환 / `getGuest()`: 게스트 기준 메시지로 자격 확인 |
| `domain.AppearanceRequest`, `AppearanceRequestStatus` | 출연 요청서와 상태. 생성 시 값 검증, 보류 결과 반영, 기본 정보 수정, 조회 권한 확인 |
| `domain.AppearanceBasicInfo` | 요청서·모집글 공통 기본 정보(제목·채널 URL·설명) 값 객체(`@Embeddable`). `of()`로 만들 때, `withChanges()`로 보낸 값만 바꿀 때 형식 검증 |
| `domain.AppearanceContentRules` | 요청서·모집글 공통 입력 값 길이·형식 제한 (도메인과 요청 DTO 검증이 같이 사용) |
| `domain.AppearanceRequestRules` | 요청서 전용 제한 (거절 사유 500자) |
| `domain.AppearanceRecruit`, `AppearanceRecruitStatus`, `AppearanceRecruitCloseType`, `AppearanceRecruitContent` | 출연 모집글과 상태·마감 방식. 생성 시 값 검증, 보류 금액, 보류 결과 반영, 기본 정보 수정, 공개 여부 확인 (#175), 조기·기한 마감과 반환 금액 (#222) |
| `app.AppearanceRecruitFacade` | 출연 모집글 API 진입점. 메서드별 `@PreAuthorize`(공개 조회는 없음), 기능별 트랜잭션, 올리기 순서 조립 (#175) |
| `app.AppearanceRecruitCreateUseCase`, `AppearanceRecruitHoldUseCase` | 올리기 1단계 저장 / 2·3단계 보류 요청·결과 반영 (#175) |
| `app.AppearanceRecruitQueryUseCase`, `AppearanceRecruitUpdateUseCase`, `AppearanceRecruitSupport` | 공개·내 목록·상세 / 기본 정보 수정 / 저장소 접근 (#175) |
| `app.AppearanceRecruitCloseUseCase`, `AppearanceRecruitDeadlineCloseUseCase`, `AppearanceRecruitCloseRecorder` | 조기 마감 / 기한 마감 배치 / 마감 뒷정리(대기 지원 미선정, 반환 작업 기록) (#222) |
| `domain.AppearanceCancelRequest`, `AppearanceCancelRequestStatus`, `AppearanceRole` | 출연 취소 요청(순번·사유·거절 사유·상태) / 내 출연 목록의 참여 쪽 (#231) |
| `app.AppearanceFacade`, `AppearanceQueryUseCase`, `AppearanceCancelRequestUseCase`, `AppearanceCancelRespondUseCase`, `AppearanceCancelAutoRejectUseCase`, `AppearanceCancelRequestSupport`, `AppearanceTitleSupport` | 출연 API 진입점 / 조회 / 취소 요청 / 수락·거절 / 자동 거절 배치 / 저장소 접근 / 출처 제목 (#231) |
| `in.ApiV1AppearanceController`, `ApiV1AppearanceSpec` | 출연 조회·취소 요청·호스트 일방 취소 REST API와 Swagger 명세 (#231, #239) |
| `app.AppearanceHostCancelUseCase`, `AppearanceRecruitCancelUseCase`, `AppearanceHostCancelRecorder`, `domain.AppearanceCancelReasonRules` | 출연 일방 취소 / 모집 전체 취소 / 출연 1건 일방 취소 + 위약금 정산 작업 기록(둘이 함께 씀) / 취소 사유 규칙 (#239) |
| `in.ApiV1AppearanceRecruitController`, `ApiV1AppearanceRecruitSpec` | 출연 모집글 REST API와 Swagger 명세 (#175) |
| `out.AppearanceRecruitRepository` | 출연 모집글 저장·목록 조회 (#175) |
| `domain.AppearanceApplication`, `AppearanceApplicationStatus` | 출연 모집 지원과 상태. 지원·취소·수락·거절 (#195) |
| `app.AppearanceApplicationFacade` | 출연 모집 지원 API 진입점 (`@PreAuthorize`, 기능별 트랜잭션) (#195) |
| `app.AppearanceApplicationApplyUseCase`, `…WithdrawUseCase`, `…RespondUseCase`, `…QueryUseCase`, `AppearanceApplicationSupport` | 지원 / 지원 취소 / 수락·거절(출연 성립) / 지원자·내 지원 목록 / 저장소 접근·지원자 수 (#195) |
| `in.ApiV1AppearanceApplicationController`, `ApiV1AppearanceApplicationSpec` | 출연 모집 지원 REST API와 Swagger 명세 (#195) |
| `out.AppearanceApplicationRepository` | 지원 저장·목록·지원자 수(모집글별 묶음 조회) (#195) |
| `app.AppearanceRequestFacade` | 출연 요청서 API 진입점. `@PreAuthorize`, 기능별 트랜잭션, 보내기의 "저장 → HP 보류 → 상태 변경" 순서 조립 |
| `app.AppearanceRequestCreateUseCase` | 보내기 1단계: 자격·값·중복 확인 후 `HOLD_PENDING` 저장 (자체 트랜잭션) |
| `app.AppearanceRequestHoldUseCase` | 보내기 2·3단계: Billing 보류 요청(트랜잭션 밖), 결과 반영 `markRequested`/`markHoldFailed` (자체 트랜잭션) |
| `app.AppearanceRequestQueryUseCase` | 보낸·받은 목록, 상세 (출연 회원 자격과 조회 권한 확인) |
| `app.AppearanceRequestUpdateUseCase` | 기본 정보 수정 |
| `domain.Appearance`, `AppearanceStatus`, `AppearanceSourceType` | 출연(성립 후 공통 단위). 출연료·방송 예정 일시 스냅샷 |
| `domain.HpTask`, `HpTaskStatus`·`HpTaskType`·`HpTaskRefType`·`HpTaskReason` | HP 작업(반환 등 Billing 호출 한 건). 기록·성공·실패 |
| `app.AppearanceRequestAcceptUseCase` | 수락: 요청서 `ACCEPTED` + 출연 성립 (Facade 트랜잭션) |
| `app.AppearanceRequestRejectUseCase`, `AppearanceRequestCancelUseCase` | 거절·응답 전 취소: 상태 변경 + 반환 작업 기록 (자체 트랜잭션) |
| `app.AppearanceRequestReleaseRecorder` | 거절·취소 공통: 상태 반영(버전 충돌 409) + 반환 작업 기록 |
| `app.HpTaskExecuteUseCase` | HP 작업 1회 실행: Billing 호출(트랜잭션 밖) → 성공 `DONE` / Billing 거절 `DEAD` / 그 밖 실패는 재시도 정책대로 (`TransactionTemplate`) |
| `in.AppearanceBatch` | 배치 주기 정의(`@ConditionalOnSchedulingEnabled`). UseCase를 직접 호출 (#187) |
| `app.AppearanceHoldRecheckUseCase`, `AppearanceRequestExpireUseCase`, `HpTaskRetryUseCase` | 보류 재확인 / 수락 기한 만료 / HP 작업 재시도 (#187) |
| `app.HpTaskMetrics` | HP 작업 지표(Micrometer) 등록·갱신 (#187) |
| `domain.HpTaskRetryPolicy`, `config.AppearanceBatchProperties` | 재시도 정책(지수 백오프+지터) / 배치 설정 바인딩 (#187) |
| `app.AppearanceSupport`, `HpTaskSupport` | 출연·HP 작업 저장소 접근 |
| `app.AppearanceRequestSupport` | 요청서 저장소 접근: `getById`, `existsInProgress`, `saveNow`, `findSent`, `findReceived` (최신순 고정) |
| `in.ApiV1AppearanceRequestController`, `ApiV1AppearanceRequestSpec` | 출연 요청서 REST API(Facade만 호출)와 Swagger 명세 (요청 값 검증은 Spec에) |
| `in.AppearanceUserEventListener` | 회원 가입·변경 이벤트 수신 → `AppearanceUserFacade` |
| `out.AppearanceUserRepository` | 출연 회원 저장·조회 |
| `out.AppearanceRequestRepository` | 출연 요청서 저장·목록 조회 |
| `out.AppearanceRepository`, `HpTaskRepository` | 출연, HP 작업 저장·조회 |
| `out.billing.BillingClient` | Billing 연동 창구. 메서드·요청·응답은 shared `BillingPort`와 같다 |
| `out.billing.BillingPortClient` | 실제 Billing 연동 구현. `appearance.billing.mode=billing-port`일 때만 등록되어 shared `BillingPort`로 그대로 넘긴다 (Billing #182) |
| `out.billing.FakeBillingClient`, `FakeBillingErrorCode`, `FakeBillingException` | 외부 연동 교체 전까지 쓰는 메모리 구현. Billing 규칙·오류 코드를 흉내 냄 (테스트에서 잔액·지갑·무응답 조절) |
| `out.billing.BillingUnavailableException` | 처리 여부를 알 수 없는 실패(무응답 등). 같은 요청으로 다시 확인해야 함 |
| (삭제, #71) | 출연 예외는 global의 공통 핸들러(`com.hapbang.global.exception.GlobalExceptionHandler`)가 Problem Detail로 응답한다 |

**계층별 책임 (2026-10-14 정리, ISSUE.md APR-25)**

| 계층 | 맡는 일 |
|---|---|
| 도메인 | 엔티티 자신의 값과 넘겨받은 값만으로 판단할 수 있는 규칙: 값 검증, 상태 전이, 행위자·기한 확인, 금액 계산. Repository·Billing·Spring 빈을 모른다 (현재 시각도 값으로 받는다) |
| UseCase | DB·다른 데이터·외부 시스템이 필요한 일과 단계 순서: 출연 회원 자격 확인, 중복 확인, Billing 호출, 낙관적 락 충돌 → 409, 저장 |
| Facade | 역할 검사(`@PreAuthorize`), 트랜잭션 경계, UseCase 조립 |
| Controller | 세션에서 회원 ID 추출(`@LoginUserId`), 요청 값 형식 검증(Spec의 `@Valid`), `RsData` 응답 |

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
| 지갑+정산 (billing) | shared 연동 계약 (`BillingPort`) | 보류, 반환, 지급, 위약금 | 계약 맞춤 + Fake 사용 중. 실제 연동 구현(`BillingPortClient`)은 Billing #182로 추가됨, `mode` 전환은 외부 연동 교체 단계 |
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

### 4.2 Billing 연동 (#168)

출연 모듈은 "언제, 어떤 금액으로 Billing을 호출하는지"만 책임진다. 출연 쪽 창구는 `out/billing/BillingClient.java`이고,
메서드·요청·응답은 Billing이 shared에 둔 연동 계약(`shared.billing.port.BillingPort`, `shared.billing.dto.*`)과 같다
(Billing 담당과 정리한 기준: 위약금 처리·70:30 계산만 출연 방식, 나머지는 Billing 기준).

| 동작 | 요청 (shared DTO) | 응답 |
|---|---|---|
| 보류 | `HoldCommand(userId, referenceType, referenceId, amountHp)` | `HoldResultDto(heldHp, replayed)` |
| 반환 | `ReleaseCommand(userId, referenceType, referenceId, releaseRequestId, amountHp \| null=전체)` | `ReleaseResultDto(releasedHp, remainingHeldHp, replayed)` |
| 지급 | `PayoutCommand(payerUserId, referenceType, referenceId, payoutRequestId, receiverUserId, sourceType, sourceId, amountHp \| null)` | `PayoutResultDto(paidHp, remainingHeldHp, replayed)` |
| 위약금 | `PenaltyCommand(payerUserId, referenceType, referenceId, cancellationId, receiverUserId, sourceType, sourceId, releaseAmountHp, payoutAmountHp)` | `PenaltyResultDto(releasedHp, paidHp, remainingHeldHp, replayed)` |

- 참조 유형(`BillingReferenceType`): `APPEARANCE_REQUEST`(출연 요청서) / `APPEARANCE_RECRUIT`(출연 모집글). 보류는 이 단위로 1건이다.
- **보류 멱등 키는 Billing이 만든다** (`HOLD:{참조 유형}:{참조 ID}`). 같은 대상·같은 회원·같은 금액으로 다시 보내면 처음 보류를 돌려준다(`replayed=true`).
- **반환·지급·위약금은 출연이 만든 요청 ID로 구분한다.** 형식 `{대상}-{대상 ID}-{동작}-{순번}` (예: `APPEARANCE_REQUEST-12-RELEASE-1`, `HpTask.requestId()`).
  영문·숫자·`-`·`_`만 쓴다. 재시도할 때 같은 값을 쓰므로 HP가 두 번 움직이지 않는다.
- 반환·지급 요청에는 **사유가 없다.** 사유(`HpTaskReason`)는 출연 HP 작업에만 기록한다.
- 지급 원천(`SettlementSourceType`)은 **출연 계약 ID**다: 1:1은 `APPEARANCE_REQUEST` + 출연 ID(요청서 ID 아님), 1:N은 `RECRUIT_APPEARANCE` + 게스트별 출연 ID. 한 원천은 정상 지급·위약금을 합쳐 한 번만 정산된다.
- 위약금은 출연이 계산한다 (`AppearancePolicy.calculatePenalty`, 출연료의 30%, 끝전 버림). Billing은 받은 반환액·위약금을 한 트랜잭션으로 옮긴다.
- **Billing이 거절하면 Billing 업무 예외**(`BusinessException` 하위)가 난다. 출연은 코드·메시지를 바꾸지 않고 그대로 응답한다
  (예: 잔액 부족 409 `INSUFFICIENT_BALANCE`, 지갑 없음 404 `WALLET_NOT_FOUND`).
- 그 밖의 예외(`BillingUnavailableException` 등)는 처리됐는지 알 수 없는 실패다. 대상을 그대로 두고 같은 요청으로 다시 확인한다.
- 출연은 Billing을 **DB 트랜잭션 밖에서** 부른다. Billing은 호출한 쪽 트랜잭션에 참여하므로, 트랜잭션 안에서 부르고 예외를 잡으면 롤백 전용 트랜잭션을 이어 쓰게 된다.
- **반환·지급처럼 업무 상태 변경 뒤에 하는 Billing 호출은 HP 작업(`appearances.hp_tasks`)으로 기록하고 실행한다** (아래 4.3, #137).
- `BillingClient`는 `BillingPort`를 상속하지 않는다. 상속하면 출연의 Fake가 앱 전체의 `BillingPort` 빈이 되어 다른 모듈에 주입될 수 있다.
- 구현체는 `appearance.billing.mode`로 고른다. **외부 연동 교체 단계 전까지 기본값 `fake`(메모리 구현)를 쓴다.**
  `billing-port`로 바꾸면 실제 Billing을 shared `BillingPort`로 부르는 `BillingPortClient`가 등록된다 (Billing 담당이 #182로 추가). 값을 잘못 쓰면 어느 구현도 등록되지 않아 앱이 뜨지 않는다.
  Fake는 Billing 구현의 규칙(보류 1건, 요청 ID 재전송, 거절 코드·메시지)을 흉내 내며, 오류 코드는 `FakeBillingErrorCode`에 Billing 값을 그대로 옮겨 두었다.

### 4.3 HP 작업 (실패한 Billing 호출의 기록·재처리, #137)

Billing 호출이 실패하는 경우(순간 장애, 긴 장애, 결과를 모름, 커밋 직후 서버 종료, 영구 실패, 중복 실행)를 출연 모듈 안에서 처리한다. 멘토링 답변("모듈 내부에서 처리, DLQ 등")과 팀 의견("모듈마다 실패 처리 방식이 다를 수 있다")에 따랐다.

```
[거절·응답 전 취소]
  트랜잭션 ┌ 요청서 → REJECTED / CANCELED
          └ hp_tasks에 반환 작업 PENDING 기록 (멱등 키 APPEARANCE_REQUEST-{id}-RELEASE-1)
  커밋 → 반환 작업 1회 실행 (트랜잭션 밖에서 Billing 호출)
          ├ 성공 → DONE
          └ 실패 → PENDING 유지, 시도 횟수 +1, 마지막 오류, 다음 시도 시각 기록 (사용자 요청은 성공으로 응답)
          (Billing 거절이면 바로 DEAD)
[모집 마감, #222]
  같은 흐름. 모집글 → CLOSED + 대기 지원 NOT_SELECTED + 성립하지 못한 인원분 반환 작업 (멱등 키 APPEARANCE_RECRUIT-{id}-RELEASE-1)
[HP 작업 재시도 배치, #187]
  다음 시도 시각이 지난 PENDING 작업 실행, 지수 백오프로 간격 늘리기, 10회 실패 시 DEAD(격리) + 오류 로그 (8.4)
```

| 상태 | 의미 | 화면 (`hpReleaseStatus`) |
|---|---|---|
| `PENDING` | 처리 대기 또는 재시도 중 | "HP 반환 처리 중" |
| `DONE` | 완료 | "반환 완료" |
| `DEAD` | Billing 거절 또는 최대 재시도 초과, 격리 | "반환 지연, 고객센터 문의" |

- 왜 이 방식인가: Modulith 이벤트 재실행은 시도 횟수·격리를 업무 기준으로 관리하기 어렵고, 요청서에 상태 컬럼을 두면 Billing 호출이 생기는 테이블마다 같은 컬럼·재시도 로직이 반복된다. HP 작업 테이블 하나로 반환·지급·정산을 같은 방식으로 처리한다.
- 멱등 키(= Billing 반환 요청 ID)가 같아 여러 번 실행해도 HP가 두 번 움직이지 않는다 (같은 키로 작업을 두 번 기록하는 것도 DB가 막음).
- 실패 기록(`last_error`): Billing이 거절하면 `{Billing 오류 코드}: {메시지}`(바로 `DEAD`), 결과를 알 수 없으면 `{예외 종류}: {메시지}`(재시도). 8.4 참고.

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
| `reject_reason` | VARCHAR(500) | 거절 사유 (선택) |
| `responded_at`, `canceled_at` | TIMESTAMP(6) | 게스트 응답(수락·거절) 시각, 호스트 응답 전 취소 시각 |
| `version` | BIGINT | 낙관적 락 |
| `created_at`, `updated_at` | TIMESTAMP(6) | 생성·수정 시각 (JPA Auditing) |

- 마이그레이션: `V202610131039__create_appearance_request_table.sql` (#98)
- 제약: 자기 자신에게 요청 불가(`host_id <> guest_id`), `fee > 0`, `expected_minutes > 0`, `accept_deadline < scheduled_at`
- **부분 유니크 인덱스** `uk_appearance_requests_pending_host_guest`: `(host_id, guest_id) where status in ('HOLD_PENDING', 'REQUESTED')`. 같은 호스트·게스트의 진행 중 요청서를 1건으로 제한한다 (동시 요청 차단).
- 인덱스: 보낸 목록 `(host_id, created_at)`, 받은 목록 `(guest_id, created_at)`

### 5.3 `appearances.appearances` — 출연 (#137)

| 컬럼 | 타입 | 설명 |
|---|---|---|
| `id` | BIGINT, PK (identity) | 출연 ID |
| `source_type` | VARCHAR(255) | `REQUEST` / `RECRUIT` |
| `request_id` / `application_id` | BIGINT, 각각 유니크 | 출처 요청서 / 모집 지원 (요청서·지원 하나에 출연 하나) |
| `recruit_id` | BIGINT | 모집글 (모집 처리에서 사용) |
| `host_id`, `guest_id` | BIGINT | 회원 ID |
| `fee` | BIGINT | 출연료 **스냅샷** (지급·위약금·반환 기준) |
| `scheduled_at` | TIMESTAMP(6) | 방송 예정 일시 **스냅샷** (D+7·취소 요청 마감 기준) |
| `status` | VARCHAR(255) | `ESTABLISHED` (이후 상태는 출연 취소 요청·확인·취소 처리에서) |
| `established_at` | TIMESTAMP(6) | 성립 시각 |
| `confirm_type`, `penalty_amount`, `confirmed_at`, `no_show_at`, `disputed_at`, `canceled_at` | | 출연 확인·취소 처리에서 사용 (현재 비어 있음) |
| `version`, `created_at`, `updated_at` | | 낙관적 락, 생성·수정 시각 |

- 제약: 출처는 하나만 (`REQUEST`면 `request_id`만, `RECRUIT`면 `application_id`만), `fee > 0`
- 스냅샷을 두는 이유: 바뀌어서가 아니라 계산 기준을 출연 한 곳에 두기 위해서다. 출연은 요청서(`fee`)와 모집(1인당 출연료) 양쪽에서 생기므로, 출처 구분이나 다른 테이블 조회 없이 출연만 보고 계산한다.

### 5.4 `appearances.hp_tasks` — HP 작업 (#137)

| 컬럼 | 타입 | 설명 |
|---|---|---|
| `id` | BIGINT, PK (identity) | 작업 ID |
| `task_type` | VARCHAR(255) | `RELEASE` (지급·정산은 이후 추가) |
| `ref_type`, `ref_id` | VARCHAR(255), BIGINT | 대상(HP 보류 단위): `APPEARANCE_REQUEST` + 요청서 ID 등 |
| `appearance_id` | BIGINT | 관련 출연 (지급·위약금에서 사용) |
| `member_id` | BIGINT | HP를 받는 회원 (반환이면 호스트) |
| `amount` | BIGINT | 금액 |
| `reason` | VARCHAR(255) | 사유 (`REQUEST_REJECTED`, `REQUEST_CANCELED` 등). 출연 내부 기록이며 Billing에는 보내지 않는다 |
| `idempotency_key` | VARCHAR(255), 유니크 | Billing 요청 ID(반환 요청 ID 등)로 쓰는 멱등 키 |
| `status` | VARCHAR(255) | `PENDING` / `DONE` / `DEAD` |
| `attempt_count`, `next_attempt_at`, `last_error` | INTEGER, TIMESTAMP(6), VARCHAR(1000) | 시도 횟수, 다음 시도 시각, 마지막 오류 |
| `completed_at` | TIMESTAMP(6) | 완료 시각 |
| `version`, `created_at`, `updated_at` | | 낙관적 락, 생성·수정 시각 |

- 마이그레이션(5.3·5.4): `V202610141109__create_appearance_and_hp_task_tables.sql` (#137)
- 인덱스: 재시도 대상 `(status, next_attempt_at)`, 대상별 조회 `(ref_type, ref_id)`

### 5.5 `appearances.recruits` — 출연 모집글 (#175)

| 컬럼 | 타입 | 설명 |
|---|---|---|
| `id` | BIGINT, PK | |
| `host_id` | BIGINT | 호스트 회원 ID |
| `title`, `host_channel_url`, `content` | VARCHAR(100), VARCHAR(255), TEXT | 수정 가능 |
| `scheduled_at`, `expected_minutes` | TIMESTAMP, INTEGER | 방송 예정 일시, 예상 출연 시간(분) |
| `fee_per_person`, `recruit_count` | BIGINT, INTEGER | 1인당 출연료, 모집 인원 |
| `selected_count` | INTEGER | 현재 성립 인원 (지원 수락에서 늘어남) |
| `recruit_deadline` | TIMESTAMP | 모집 마감 일시 |
| `status` | VARCHAR(255) | `HOLD_PENDING`/`HOLD_FAILED`/`RECRUITING`/`CLOSED`/`CANCELED` |
| `close_type`, `closed_at`, `canceled_at` | VARCHAR(255), TIMESTAMP, TIMESTAMP | 마감 방식(`DEADLINE`/`EARLY`)·마감 시각(기한 마감은 모집 마감 일시, 조기 마감은 마감한 시각, #222)·취소 시각 |
| `version`, `created_at`, `updated_at` | | 낙관적 락, 공통 시각 |

- 제약: `fee_per_person > 0`, `recruit_count > 0`, `0 <= selected_count <= recruit_count`, `expected_minutes > 0`, `recruit_deadline < scheduled_at`
- 인덱스: 공개 목록 `(status, created_at)`, 내 목록 `(host_id, created_at)`, 기한 마감 배치 `(status, recruit_deadline)` (#222)
- 마이그레이션: `V202610141400__create_appearance_recruit_table.sql`, `V202610151153__add_appearance_recruit_deadline_index.sql`

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
  ② HoldUseCase.requestHold  [트랜잭션 밖]  BillingClient.hold (보류 키는 Billing이 요청서로 만듦)
       ├─ 보류 성공                    → ③ HoldUseCase.markRequested [트랜잭션 2] → REQUESTED, 201
       ├─ Billing 거절 (업무 예외)      → HOLD_FAILED 기록 [트랜잭션 2] → Billing 오류 그대로 (예: 409 INSUFFICIENT_BALANCE)
       └─ 결과를 알 수 없음 (그 밖 예외) → HOLD_PENDING 그대로, 503 BILLING_UNAVAILABLE
```

- 저장을 먼저 커밋하는 이유: Billing을 기다리는 동안 DB 트랜잭션을 잡지 않고, 보류 결과와 관계없이 요청서 기록이 남게 하기 위해서다.
- `HOLD_PENDING`으로 남은 요청서는 보류 재확인 배치가 같은 요청서로 보류를 다시 보내 확인하고 `REQUESTED`/`HOLD_FAILED`로 정리한다 (8.2, #187). Billing은 같은 보류를 다시 받으면 처음 결과를 돌려준다.

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

### 6.4 수락·거절·응답 전 취소 (#137)

| 메서드 | 경로 | 설명 | 성공 |
|---|---|---|---|
| POST | `/api/v1/appearances/requests/{requestId}/accept` | 수락 (게스트). 본문 `{"version": n}` | 200 "출연 요청을 수락했습니다." |
| POST | `/api/v1/appearances/requests/{requestId}/reject` | 거절 (게스트). 본문 `{"reason": "..."}` (선택, 500자) | 200 "출연 요청을 거절했습니다." |
| POST | `/api/v1/appearances/requests/{requestId}/cancel` | 응답 전 취소 (호스트). 본문 없음 | 200 "출연 요청을 취소했습니다." |
| GET | `.../sent?status=`, `.../received?status=` | 목록 상태 필터. 여러 번 보낼 수 있음, 생략하면 전체, 숨김 상태는 400 | 200 |

| 규칙 | 내용 | 이유 |
|---|---|---|
| 확인 순서 | 행위자(게스트/호스트) → 응답 대기(`REQUESTED`) → 수락 기한 → (수락) 버전 | |
| 수락 기한 | DB 상태와 관계없이 **현재 시각 ≥ 수락 기한이면** 수락·거절·취소를 막는다 (409 `APPEARANCE_REQUEST_EXPIRED`) | 만료 처리(스케줄러)가 조금 늦어도 기한 지난 수락이 통과하지 않게. 막는 건 이벤트가 아니라 각 요청 안의 시각 비교다 |
| 수락 버전 | 게스트가 본 요청서 `version`을 본문으로 보내고, 다르면 409 `APPEARANCE_REQUEST_MODIFIED` | 게스트가 본 조건 그대로 동의하도록. HTTP 표준 ETag/If-Match 대신 본문 필드로 받아 프론트가 다루기 쉽게 |
| 호스트 정지 | 요청을 보낸 뒤 호스트가 정지·탈퇴·강등됐으면 수락 불가 (403) | 거래할 수 없는 회원과 출연을 맺지 않음 |
| 수락 시 HP | 이동 없음. 요청서의 보류를 출연이 그대로 쓴다 | 지급·반환은 이후 출연 단위로 |
| 거절·취소 시 HP | 보류 전액 반환. HP 작업으로 기록하고 커밋 후 실행 (4.3). 반환이 실패해도 거절·취소는 성공으로 응답하고 `hpReleaseStatus`로 진행 상태를 알린다 | |
| 동시 처리 | 같은 요청서를 동시에 수락·거절·취소하면 먼저 반영된 쪽만 성공, 나머지는 409 (`CONCURRENT_UPDATE` 또는 `APPEARANCE_REQUEST_NOT_AWAITING_RESPONSE`) | 먼저 반영된 쪽 우선 (낙관적 락) |
| 같은 행동 반복 | 이미 응답한 요청서에 다시 응답하면 409 | |
| 상세 응답 추가 | `version`, `rejectReason`, `respondedAt`, `canceledAt`, `appearanceId`(수락 시), `hpReleaseStatus`(거절·취소 시) | |
| 미정 | 게스트 일정 겹침 확인 안 함, 상대방 알림 범위 밖 | |

- 이번 "취소"는 **응답 전 호스트 취소**다. 출연 성립 후의 출연 취소 요청(게스트)·호스트 일방 취소(위약금)와 다르다.
- 보내기 응답의 `version`이 DB 값과 같도록, 보류 성공(`REQUESTED`) 반영 후 바로 DB에 반영한 뒤 응답을 만든다 (테스트로 찾은 문제).

- 요청서 상세의 `hpReleaseStatus`는 거절·응답 전 취소·만료 반환(`APPEARANCE_REQUEST-{id}-RELEASE-1`)만 보여 준다. 성립 뒤 합의 취소 반환은 출연 상세에서 본다 (#231).

---

## 7. 출연 모집글 (#175)

### 7.1 API

| 메서드 | 경로 | 설명 | 권한 | 성공 메시지 |
|---|---|---|---|---|
| POST | `/api/v1/appearances/recruits` | 모집글 올리기 (HP 보류), 201 | 인플루언서 이상 | 출연 모집글을 올렸습니다. |
| GET | `/api/v1/appearances/recruits` | 모집 중인 모집글 공개 목록 | 로그인 회원 누구나 | 모집 중인 출연 모집글 목록을 조회했습니다. |
| GET | `/api/v1/appearances/recruits/mine?status=` | 내가 올린 모집글 목록 (상태 필터, 여러 개) | 인플루언서 이상 | 내 출연 모집글 목록을 조회했습니다. |
| GET | `/api/v1/appearances/recruits/{recruitId}` | 상세 (마감 반환 진행 상태 `hpReleaseStatus`는 호스트에게만) | 로그인 회원 누구나 | 출연 모집글을 조회했습니다. |
| PATCH | `/api/v1/appearances/recruits/{recruitId}` | 기본 정보(제목·콘텐츠 설명·호스트 채널 URL) 수정 | 인플루언서 이상, 호스트만 | 출연 모집글을 수정했습니다. |
| POST | `/api/v1/appearances/recruits/{recruitId}/close` | 조기 마감 (#222, 10장) | 인플루언서 이상, 호스트만 | 출연 모집을 마감했습니다. |

- 목록은 `Pageable`(page·size, 기본 20, 최대 100), 최신순 고정. 성공 응답은 `RsData`.
- 역할 검사는 Facade 메서드마다 `@PreAuthorize`를 붙인다 (공개 조회와 호스트 기능의 권한이 달라서). 공개 조회는 출연 회원 자격도 확인하지 않는다.
- **비회원 공개 목록은 아직 열지 않았다.** global `SecurityConfig`에 `GET /api/v1/appearances/recruits` 허용을 추가해야 해서 팀 합의 후 진행한다 (지금은 401).

### 7.2 모집글 올리기 흐름

```
Controller ─▶ AppearanceRecruitFacade.create (@PreAuthorize, 트랜잭션 없음)
  ① RecruitCreateUseCase.createPending  [트랜잭션 1, 커밋]
       호스트 자격 → 값 검증(형식, 최저 출연료, 모집 인원 1~10, 현재 < 모집 마감 < 방송 예정 일시) → HOLD_PENDING 저장
  ② RecruitHoldUseCase.requestHold  [트랜잭션 밖]  BillingClient.hold(APPEARANCE_RECRUIT, 모집글 ID, 1인당 출연료 × 모집 인원)
       ├─ 보류 성공                    → ③ markRecruiting [트랜잭션 2] → RECRUITING, 201
       ├─ Billing 거절 (업무 예외)      → HOLD_FAILED 기록 → Billing 오류 그대로 (예: 409 INSUFFICIENT_BALANCE)
       └─ 결과를 알 수 없음 (그 밖 예외) → HOLD_PENDING 그대로, 503 BILLING_UNAVAILABLE ("내 모집글 목록에서 확인")
```

### 7.3 규칙

| 항목 | 규칙 | 근거 |
|---|---|---|
| 모집 인원 | 1명 ~ 10명 (`appearance.policy.max-recruit-count`) | 사용자 결정 (2026-10-14): 요청은 게스트 한 명을 지정하지만 모집은 게스트가 먼저 지원하는 방식이라 1명 모집도 허용 |
| 1인당 출연료 | 최저 출연료(20,000 HP) 이상 | AGENTS.md 5장 |
| 보류 금액 | 1인당 출연료 × 모집 인원, 모집글 단위 1건 | ISSUE.md APR-02 |
| 모집 마감 일시 | 현재 < 모집 마감 < 방송 예정 일시 | 요청서 수락 기한과 같은 규칙 |
| 중복 | 같은 호스트의 모집글 수 제한 없음 | 사용자 결정 (2026-10-14) |
| 입력 제한 | 제목 100자, 채널 URL http(s) 255자, 설명 5,000자, 예상 출연 시간 1~1,440분 | 요청서와 같음 (`AppearanceBasicInfo`, `AppearanceContentRules`) |
| 공개 목록 | `RECRUITING`이고 모집 마감 일시가 지나지 않은 모집글 (마감 처리 전이어도 시각으로 거름) | |
| 상세·내 목록 | `RECRUITING`·`CLOSED`·`CANCELED`. `HOLD_PENDING`·`HOLD_FAILED`는 올린 호스트에게도 숨김(404) | 요청서와 같음 |
| 수정 | 호스트만, `RECRUITING`이고 모집 마감 일시 전까지. 동시 수정은 409 `CONCURRENT_UPDATE` | ISSUE.md APR-08 |
| 응답 | 성립 인원(`selectedCount`), 지원자 수(`applicantCount`, #195), 마감 방식·마감 시각(#222), 마감 반환 진행 상태(`hpReleaseStatus`, 호스트만, 마감 반환만 — 합의 취소 반환은 출연 상세, #222·#231), 버전 포함. 취소 시각은 모집 취소에서 채움 | |
| 미정 | 방송 플랫폼·카테고리 등 분류, 검색·필터, 비회원 공개, 정지 호스트 모집글 노출 | 필요할 때 결정 |

---

## 8. 출연 배치 (#187)

### 8.1 구조

```
in.AppearanceBatch (@ConditionalOnSchedulingEnabled, 매분 실행, KST)
  ├ 00초 AppearanceHoldRecheckUseCase.recheckDue()   보류 처리 중 요청서·모집글 재확인
  ├ 10초 AppearanceRecruitDeadlineCloseUseCase.closeDue()  모집 기한 마감 + 미달분 반환 (#222, 10장)
  ├ 20초 AppearanceRequestExpireUseCase.expireDue()  수락 기한 만료 + 반환
  ├ 30초 AppearanceCancelAutoRejectUseCase.rejectDue()  출연 취소 요청 자동 거절 (#231, 12장)
  └ 40초 HpTaskRetryUseCase.retryDue()               HP 작업 재시도·격리 + 지표 갱신
```

- 팀 공통 스케줄링 규칙(#157)을 따른다: `hapbang.scheduling.enabled=true`일 때만 배치 빈이 생기고(앱에서만 켜짐), 배치 클래스는 주기만 정하고 처리는 UseCase가 한다. 테스트는 UseCase를 직접 부른다.
- 배치는 로그인 회원이 아니라 시스템이 실행하므로 역할 검사(`@PreAuthorize`)가 있는 Facade를 거치지 않고 UseCase를 직접 부른다 (팀 강의 배치와 같은 방식, ISSUE.md APR-26).
- 한 번에 최대 100건(`batch-size`), 건마다 짧은 트랜잭션, 건마다 예외를 잡아 다음 건을 계속 처리한다. 같은 배치는 이전 실행이 끝나야 다음 실행이 시작된다.
- 설정: `appearance.yml`의 `appearance.batch.*` (주기, 건수, 대기 시간, 재시도 정책). `config.AppearanceBatchProperties`가 읽는다.

### 8.2 HP 보류 재확인

| 대상 | 처리 | 결과 |
|---|---|---|
| 만든 지 1분(`pending-grace`) 넘게 `HOLD_PENDING`인 요청서·모집글 | 같은 보류를 다시 요청 (보내기·올리기의 HoldUseCase 그대로) | 성공 → `REQUESTED`/`RECRUITING`, Billing 거절 → `HOLD_FAILED`, 여전히 알 수 없음 → 그대로 두고 다음 주기 |

- Billing은 같은 참조 대상의 보류를 다시 받으면 처음 결과를 돌려준다(`replayed`). 처음 요청이 Billing에서 처리됐어도 두 번 보류되지 않는다.
- 1분 대기는 진행 중인 보내기·올리기 요청과 겹치지 않게 하는 장치다. 겹쳐도 Billing이 같은 결과를 주고, 보류 결과 반영(`markRequested`·`markRecruiting`·`markHoldFailed`)이 멱등이라 안전하다.
- 수락 기한이 이미 지난 요청서도 보류 결과대로 `REQUESTED`로 정리하고, 같은 분의 만료 배치가 `EXPIRED` + 반환한다 (APR-26 S5). 이 경우의 불편은 고객센터에서 처리한다.

### 8.3 수락 기한 만료

| 대상 | 처리 |
|---|---|
| `REQUESTED`이고 수락 기한이 지난 요청서 | [트랜잭션] `EXPIRED` + 반환 작업 기록(사유 `REQUEST_EXPIRED`) → [트랜잭션 밖] 반환 작업 1회 실행 |

- 거절·응답 전 취소와 같은 흐름이다. 반환이 실패해도 만료는 확정되고 작업은 재시도 대기로 남는다.
- 수락·거절·취소는 기한이 지나면 막히므로(#137 D3) 만료와 동시에 일어나지 않는다.

### 8.4 HP 작업 재시도·격리

| 결과 | 처리 |
|---|---|
| 성공 | `DONE` |
| Billing 거절 (업무 예외) | **바로 `DEAD`**. 다시 보내도 결과가 같으므로 재시도하지 않고 Billing 오류 코드·메시지를 기록 (APR-26 S3) |
| 결과를 알 수 없는 실패 | 지수 백오프 + 지터로 다음 시도 시각 결정: 1분 → 2분 → 4분 → … → 최대 1시간 (+0~20%). **10회 실패 시 `DEAD`** |

- 재시도 대상: `PENDING`이고 다음 시도 시각이 지났으며, 한 번 이상 시도했거나 만든 지 1분이 지난 작업. 거절·취소 직후 요청 안에서 바로 실행 중인 작업과 겹치지 않게 하기 위해서다.
- 같은 작업을 동시에 실행해도 같은 반환 요청 ID라 Billing은 한 번만 처리하고, 결과 기록은 대기 중일 때만 한다(버전 충돌이면 먼저 기록한 쪽을 따름).
- 재시도 정책: `domain.HpTaskRetryPolicy` (`appearance.batch.retry-*`).

### 8.5 모니터링

| 장치 | 내용 | 확인 방법 |
|---|---|---|
| 격리 로그 | `DEAD`가 되는 순간 ERROR 로그 `[HP_TASK_DEAD] taskId=…, type=…, ref=APPEARANCE_REQUEST-12, memberId=…, amount=…, attempts=…, error=…` | `docker logs <컨테이너> 2>&1 \| grep HP_TASK_DEAD` |
| 지표 (Micrometer) | `appearance.hp_task.executions{result=done\|retry\|dead}`, `appearance.hp_task.pending`, `appearance.hp_task.dead` (재시도 배치 실행 시점 값) | 앱의 `MeterRegistry`에 등록됨. 보는 경로(`/actuator/metrics`, Prometheus)는 팀 합의 후 연다 |
| DB | `select * from appearances.hp_tasks where status = 'DEAD'` | 언제든 |

- Prometheus + Grafana 대시보드·알림은 `docker/compose*.yaml` 변경이 필요해 팀 결정 사항이다.
- 배치 조회용 인덱스: `V202610141700__add_appearance_batch_indexes.sql` (요청서 `(status, accept_deadline)`, `(status, created_at)`)

---

## 9. 출연 모집 지원과 수락 (#195)

### 9.1 API

| 메서드 | 경로 | 설명 | 권한 | 성공 메시지 |
|---|---|---|---|---|
| POST | `/api/v1/appearances/recruits/{recruitId}/applications` | 지원 (메시지 선택, 500자), 201 | 게스트 | 출연 모집에 지원했습니다. |
| GET | `/api/v1/appearances/recruits/{recruitId}/applications?status=` | 지원자 목록 | 모집글 호스트만 | 지원자 목록을 조회했습니다. |
| GET | `/api/v1/appearances/applications/mine?status=` | 내 지원 목록 | 게스트 | 내 지원 목록을 조회했습니다. |
| POST | `/api/v1/appearances/applications/{applicationId}/withdraw` | 지원 취소 | 지원한 게스트 | 지원을 취소했습니다. |
| POST | `/api/v1/appearances/applications/{applicationId}/accept` | 수락 → 출연 성립 | 모집글 호스트 | 지원을 수락했습니다. |
| POST | `/api/v1/appearances/applications/{applicationId}/reject` | 거절 (사유 없음) | 모집글 호스트 | 지원을 거절했습니다. |

- 모두 인플루언서 이상(Facade 클래스 `@PreAuthorize`), 출연 회원 자격(정지·강등) 확인. 목록은 `Pageable`, 최신순 고정, 상태 필터는 여러 개.
- 모집글 응답(목록·상세)에 **지원자 수 `applicantCount`**를 담는다: 게스트가 취소한 지원만 뺀 전체 지원(대기·수락·미선정). 화면에 보일지는 화면이 정한다 (ISSUE.md APR-27 A6).
- 지원 응답(목록 포함)에는 수락된 경우 성립한 출연 ID(`appearanceId`)가 있다 (한 페이지를 한 번에 조회).

### 9.2 상태

```
APPLIED ─호스트 수락─▶ SELECTED (출연 성립, 모집글 성립 인원 +1)
        ├호스트 거절 또는 응답 없이 모집 마감(#222)─▶ NOT_SELECTED
        └게스트 취소─▶ WITHDRAWN
```

### 9.3 규칙

| 항목 | 규칙 | 근거 |
|---|---|---|
| 지원 가능 | 모집 중(`RECRUITING`)이고 모집 마감 일시 전, 자기 모집글 아님 (400 `CANNOT_APPLY_OWN_RECRUIT` / 409 `APPEARANCE_RECRUIT_NOT_OPEN`) | |
| 1회 지원 | 모집글·게스트당 1건(DB 유니크 `uk_appearance_applications_recruit_guest`). 취소·거절돼도 재지원 불가 (409 `DUPLICATE_APPLICATION`), 동시 지원도 DB가 막음 | AGENTS.md 5장, APR-09 |
| 정원이 찬 뒤 | **지원은 받고 수락만 막는다** (409 `RECRUIT_FULL`). 자동 마감하지 않는다 | APR-27 A2: 출연 취소 요청으로 마감 전까지 빈자리가 생길 수 있음 |
| 지원 취소 | 지원한 게스트만(403 `NOT_APPLICATION_GUEST`), 호스트 응답 전(`APPLIED`)만 (409 `APPLICATION_NOT_PENDING`) | |
| 수락 | 확인 순서: 호스트 자격 → 호스트·모집 중·마감 전 → 대기 중 지원 → 정원 → 게스트 자격(A5). 지원 `SELECTED` + 성립 인원 +1 + 출연 성립이 한 트랜잭션, HP 이동 없음(모집글 보류 유지) | APR-27 |
| 동시 수락 | 모집글 배타 잠금(#222 C9)으로 차례로 처리되고 버전(낙관적 락)도 확인 → 나머지는 409 `RECRUIT_FULL`(또는 `CONCURRENT_UPDATE`), 정원을 넘지 않음 | A3 |
| 거절 | 사유 없이 `NOT_SELECTED` | A4 |
| 출연 | 출처 `RECRUIT`, `application_id`(유니크)·`recruit_id`, 출연료 = 1인당 출연료, 방송 예정 일시 스냅샷. 지급 원천은 `RECRUIT_APPEARANCE` + 출연 ID | APR-23 |
| 일정 겹침 | 확인하지 않음 | A7 |
| 모집 마감 | 대기 중인 지원은 `NOT_SELECTED`(응답 시각 없음), 미달 인원분 반환 (10장) | #222 |

### 9.4 `appearances.applications` 스키마

| 컬럼 | 타입 | 설명 |
|---|---|---|
| `id` | BIGINT, PK | |
| `recruit_id` | BIGINT, FK → `appearances.recruits` | 모집글 |
| `guest_id` | BIGINT | 게스트 회원 ID |
| `message` | VARCHAR(500) | 지원 메시지 (선택) |
| `status` | VARCHAR(255) | `APPLIED`/`SELECTED`/`NOT_SELECTED`/`WITHDRAWN` |
| `responded_at`, `withdrawn_at` | TIMESTAMP | 호스트 응답 시각(모집 마감으로 미선정되면 비어 있음), 게스트 취소 시각 |
| `version`, `created_at`, `updated_at` | | 낙관적 락, 공통 시각 |

- 유니크: `uk_appearance_applications_recruit_guest (recruit_id, guest_id)`
- 인덱스: 지원자 목록·지원자 수 `(recruit_id, created_at)`, 내 지원 목록 `(guest_id, created_at)`
- 마이그레이션: `V202610151050__create_appearance_application_table.sql`

---

## 10. 출연 모집 마감 (#222)

### 10.1 API·배치

| 진입 | 대상 | 마감 방식 | 마감 시각 |
|---|---|---|---|
| `POST /api/v1/appearances/recruits/{recruitId}/close` (호스트, 인플루언서 이상) | 모집 중이고 마감 일시 전 (성립 0명이어도 가능) | `EARLY` | 마감한 시각 |
| 배치 매분 10초 `AppearanceRecruitDeadlineCloseUseCase.closeDue()` | 모집 중이고 마감 일시가 지난 모집글 (마감 일시 이른 순, 최대 100건) | `DEADLINE` | 모집 마감 일시 |

### 10.2 흐름

```
[조기 마감] AppearanceRecruitFacade.closeEarly (@PreAuthorize, 트랜잭션 없음)
  ① RecruitCloseUseCase.closeEarly  [트랜잭션, 커밋]
       호스트 자격 → 보이는 모집글(보류 처리 중·실패는 404) → 호스트·모집 중·마감 전 → CLOSED(EARLY)
       → RecruitCloseRecorder: 대기 지원 NOT_SELECTED → flush(버전 충돌 409) → 반환 작업 기록(금액 > 0일 때)
  ② HpTaskExecuteUseCase.execute  [트랜잭션 밖]  반환 1회 실행 (실패해도 마감은 성공, 작업은 재시도 대기)
  ③ 상세 조회로 응답 (hpReleaseStatus 포함)
[기한 마감 배치] 모집글마다 [트랜잭션] 같은 상태 확인 → CLOSED(DEADLINE) → RecruitCloseRecorder (커밋) → [트랜잭션 밖] 반환 1회 실행
```

### 10.3 규칙

| 항목 | 규칙 | 근거 |
|---|---|---|
| 반환 금액 | 1인당 출연료 × (모집 인원 − 성립 인원). 성립 0명이면 전액. 금액을 정해 부분 반환(`ReleaseCommand.partial`) | APR-06, APR-28 C3 |
| 정원이 다 찬 채 마감 | 돌려줄 금액이 0이라 반환 작업을 만들지 않음 (`hpReleaseStatus` 없음). 보류는 출연 확인·취소 때 쓰임 | C7 |
| 성립 0명 | 모집 취소가 아니라 `CLOSED` + 전액 반환 | APR-06 |
| 대기 중 지원 | 모두 `NOT_SELECTED`. 호스트가 거절한 것이 아니라 응답 시각은 비워 둠 | C2 |
| 성립한 지원·출연 | 그대로 (`SELECTED`, 출연 유지). 보류에는 성립 인원 × 1인당 출연료가 남음 | |
| 반환 작업 | 사유 `RECRUIT_CLOSED`, 멱등 키 `APPEARANCE_RECRUIT-{id}-RELEASE-1`. 실행·재시도·격리는 요청서 반환과 같음 (4.3, 8.4) | |
| 반환 진행 상태 | 모집글 상세 `hpReleaseStatus`, 호스트에게만 | C4 |
| 보류 처리 중 모집글 | 마감 배치 대상 아님. 보류 재확인(00초)이 `RECRUITING`으로 정리하면 다음 마감 배치(10초)가 마감. `HOLD_FAILED`는 그대로. 보류 결과를 모르는 동안은 반환하지 않음 | C5 |
| 동시 처리 | 모집글 행 잠금으로 순서를 정한다: 지원은 공유 잠금(`FOR SHARE`, 지원끼리는 막지 않음), 마감·수락은 배타 잠금(`FOR UPDATE`). 마감은 진행 중인 지원·수락이 커밋된 뒤의 최신 값으로 처리하고, 그 뒤의 지원·수락은 409 `APPEARANCE_RECRUIT_NOT_OPEN`. 마감과 수락은 둘 다 모집글 → 지원 순서로 잠가 교착 상태가 없다. 지원 취소와 겹치면 지원 버전으로 409 `CONCURRENT_UPDATE` | C9 |
| 배치 실패 | 한 모집글 처리 중 예외(버전 충돌 등)가 나면 그 건만 롤백·건너뛰고 다음 주기에 다시 | C8 |
| 마감 뒤 | 지원·수락·거절·조기 마감 409 `APPEARANCE_RECRUIT_NOT_OPEN`, 미선정된 지원 취소 409 `APPLICATION_NOT_PENDING`. 공개 목록에서 빠지고 상세·내 목록에는 `CLOSED`로 보임 | |
| 알림 | 마감·미선정 안내는 이번 범위 밖 (나중 기능) | C2 |
| 남은 일 | 마감 후 출연 취소(합의)로 빈자리가 생기면 그 출연료 반환 — 출연 취소 요청 이슈. 같은 모집글 보류에서 반환이 더 생기면 반환 요청 ID 순번을 나눈다 | AGENTS.md "출연 취소 요청" |

---

## 11. 출연 알림 이벤트 (#227)

출연 쪽에서 일어난 일을 `shared` 이벤트로 알린다. 출연은 받는 쪽(채팅 등)을 모른다. 받는 쪽 구현은 각 모듈 담당이 한다 (ISSUE.md APR-29).

### 11.1 이벤트

| 이벤트 (`shared.appearance.event`) | 담는 DTO (`shared.appearance.dto`) | 언제 발행 | 누가 알면 되나 |
|---|---|---|---|
| `AppearanceRequestSentEvent(AppearanceRequestDto request)` | `requestId`, `hostId`, `guestId`, `title`, `fee`, `scheduledAt`, `acceptDeadline` | 요청서가 `HOLD_PENDING` → `REQUESTED`로 **실제로 바뀔 때 한 번** (보내기 요청 또는 보류 재확인 배치) | 게스트 (`guestId`) |
| `AppearanceApplicationSubmittedEvent(AppearanceApplicationDto application)` | `applicationId`, `recruitId`, `recruitTitle`, `hostId`, `guestId`, `appliedAt` | 지원이 저장됐을 때 (`APPLIED`) | 호스트 (`hostId`) |
| `AppearanceCancelRequestedEvent(AppearanceCancelRequestDto cancelRequest)` | `cancelRequestId`, `appearanceId`, `title`, `hostId`, `guestId`, `sequence`, `scheduledAt` | 게스트가 출연 취소를 요청했을 때 (#231) | 호스트 (`hostId`) |
| `AppearanceCancelRequestAcceptedEvent(AppearanceCancelRequestDto cancelRequest)` | 〃 | 호스트가 수락해 합의 취소됐을 때 | 게스트 (`guestId`) |
| `AppearanceCancelRequestRejectedEvent(AppearanceCancelRequestDto cancelRequest, boolean autoRejected)` | 〃 + `autoRejected` | 호스트가 거절했을 때(`false`), 방송 예정 일시까지 응답이 없어 자동 거절됐을 때(`true`) | 게스트 (`guestId`) |

- 발행: UseCase가 `ApplicationEventPublisher.publishEvent` (요청서 `AppearanceRequestHoldUseCase.markRequested`, 지원 `AppearanceApplicationApplyUseCase`). 트랜잭션 안에서 발행한다.
- DTO는 엔티티의 `toDto()`로 만든다. 지원 메시지는 담지 않는다. 닉네임은 받는 쪽의 회원 복제본으로 찾는다.
- 보류가 실패한 요청서(`HOLD_FAILED`)·결과를 모르는 요청서(`HOLD_PENDING`)·막힌 지원은 발행하지 않는다.

### 11.2 받는 쪽 예시 (참고)

```java
// {모듈}/in/event/{모듈}AppearanceEventListener.java
@ApplicationModuleListener   // 출연 트랜잭션 커밋 뒤, 새 트랜잭션·비동기. 실패하면 Modulith 발행 기록에 남아 재처리
public void on(AppearanceRequestSentEvent event) {
    AppearanceRequestDto request = event.request();
    facade.notifyAppearanceRequested(request.guestId(), request.hostId(), request.requestId(), request.title());
}

@ApplicationModuleListener
public void on(AppearanceApplicationSubmittedEvent event) {
    AppearanceApplicationDto application = event.application();
    facade.notifyRecruitApplied(application.hostId(), application.guestId(), application.recruitId(),
            application.recruitTitle());
}
```

- 출연 처리가 롤백되면 이벤트는 받는 쪽에 가지 않는다. 받는 쪽이 실패해도 출연 처리는 되돌려지지 않는다 (최종 일관성).
- 같은 이벤트가 재처리로 두 번 올 수 있으니, 받는 쪽은 `requestId`·`applicationId`로 중복을 거를 수 있다.

---

## 12. 출연 조회와 출연 취소 요청 (#231)

### 12.1 API

| 메서드 | 경로 | 설명 | 권한 | 성공 메시지 |
|---|---|---|---|---|
| GET | `/api/v1/appearances?role=HOST\|GUEST&status=` | 내 출연 목록 (`role` 필수, 상태 필터 여러 개), 최신순 | 인플루언서 이상 | 내 출연 목록을 조회했습니다. |
| GET | `/api/v1/appearances/{appearanceId}` | 출연 상세 + 취소 요청 이력 | 호스트·게스트만 (그 밖은 404) | 출연을 조회했습니다. |
| POST | `/api/v1/appearances/{appearanceId}/cancel-requests` | 취소 요청 (사유 필수) | 게스트 | 출연 취소를 요청했습니다. |
| POST | `/api/v1/appearances/{appearanceId}/cancel-requests/accept` | 처리 중인 요청 수락 (합의 취소) | 호스트 | 출연 취소 요청을 수락했습니다. |
| POST | `/api/v1/appearances/{appearanceId}/cancel-requests/reject` | 처리 중인 요청 거절 (거절 사유 선택, 본문 없어도 됨) | 호스트 | 출연 취소 요청을 거절했습니다. |

- 출연 ID는 숫자만 받는다 (`/requests`·`/recruits`·`/applications` 경로와 섞이지 않도록). 응답은 모두 출연 상세(`AppearanceResDto`).
- 제목은 출처(요청서·모집글)의 제목이다 (출연은 제목을 따로 저장하지 않는다).
- 처리 중인 요청은 출연당 하나라, 수락·거절은 요청 ID가 아니라 출연 ID로 부른다.

### 12.2 상태

```
출연:      ESTABLISHED ─취소 요청─▶ CANCEL_REQUESTED ─수락─▶ CANCELED_BY_AGREEMENT
                 ▲                        │
                 └──거절·자동 거절────────┘   (1회 더 요청 가능, 출연당 최대 2회)
취소 요청: REQUESTED → ACCEPTED / REJECTED / AUTO_REJECTED
```

### 12.3 규칙

| 항목 | 규칙 | 근거 |
|---|---|---|
| 요청 | 출연의 게스트만(403 `NOT_APPEARANCE_GUEST`), `ESTABLISHED`일 때(처리 중이면 409 `CANCEL_REQUEST_ALREADY_PENDING`, 그 밖은 409 `APPEARANCE_NOT_CANCELABLE`), 방송 예정 일시 전(409 `APPEARANCE_SCHEDULE_PASSED`), 사유 필수 500자 | AGENTS.md, APR-31 K2 |
| 횟수 | 출연 1건당 2번(`appearance.policy.max-cancel-request-count`), 다시 요청은 거절된 뒤에만 (409 `CANCEL_REQUEST_LIMIT_EXCEEDED`) | AGENTS.md |
| 철회 | 게스트는 보낸 요청을 철회할 수 없다 | K3 |
| 응답 | 출연의 호스트만(403 `NOT_APPEARANCE_HOST`), 처리 중인 요청이 있을 때(409 `CANCEL_REQUEST_NOT_PENDING`), 방송 예정 일시 전 | AGENTS.md |
| 수락 HP — 요청서 | 출연료 전액 반환. HP 작업 `RELEASE`, 사유 `CANCEL_AGREED`, 반환 요청 ID `APPEARANCE_REQUEST-{요청서 ID}-RELEASE-APPEARANCE-{출연 ID}` | AGENTS.md |
| 수락 HP — 모집, 마감 전 | 모집글 성립 인원 −1, 반환 없음(보류 유지). 빈자리에 다른 지원자 수락 가능, 다시 채우지 않으면 마감 반환에 함께 들어감. 지원은 `SELECTED` 그대로 | AGENTS.md, K4 |
| 수락 HP — 모집, 마감 후 | 성립 인원 −1 + 그 자리 출연료 바로 반환 (반환 요청 ID `APPEARANCE_RECRUIT-{모집글 ID}-RELEASE-APPEARANCE-{출연 ID}`, 마감 반환 `…-RELEASE-1`과 겹치지 않음) | AGENTS.md |
| 거절 | 출연 `ESTABLISHED`로 유지, 거절 사유 선택 500자. HP 이동 없음 | K2 |
| 자동 거절 | 매분 30초 배치. 처리 중이고 방송 예정 일시와 같거나 지난 요청 → `AUTO_REJECTED`, 출연 유지, HP 이동 없음 | AGENTS.md, K5 |
| 동시 처리 | 출연 행 배타 잠금(`FOR UPDATE`)으로 요청·수락·거절·자동 거절을 차례로 처리. 모집 출처 수락은 모집글 → 출연 순서로 잠가 지원 수락·마감과 교착 상태가 없다 (APR-28 C9와 같은 순서) | |
| 알림 | 요청 도착(→ 호스트), 수락·거절·자동 거절(→ 게스트) 이벤트 발행 (11장) | K7 |
| 반환 진행 상태 | 출연 상세 `hpReleaseStatus` = 이 출연 단위 반환(합의 취소)만. 요청서 상세는 거절·취소·만료 반환만, 모집글 상세는 마감 반환만 보여 서로 섞이지 않는다 (반환 요청 ID로 정확히 찾음). 모집글 마감 전 합의 취소는 반환이 없어 null | 코드리뷰 (APR-31 K8) |

### 12.4 `appearances.cancel_requests` 스키마

| 컬럼 | 타입 | 설명 |
|---|---|---|
| `id` | BIGINT, PK | |
| `appearance_id` | BIGINT, FK → `appearances.appearances` | 출연 |
| `host_id`, `guest_id` | BIGINT | 호스트·게스트 (출연과 같음) |
| `sequence` | INTEGER | 출연에서 몇 번째 요청인지 (1부터) |
| `reason` | VARCHAR(500) | 취소 사유 (필수) |
| `status` | VARCHAR(255) | `REQUESTED`/`ACCEPTED`/`REJECTED`/`AUTO_REJECTED` |
| `reject_reason` | VARCHAR(500) | 거절 사유 (선택) |
| `responded_at` | TIMESTAMP | 응답·자동 거절 시각 (요청 시각은 `created_at`) |
| `version`, `created_at`, `updated_at` | | 낙관적 락, 공통 시각 |

- 유니크: `(appearance_id, sequence)`, 처리 중인 요청은 출연당 하나 `uk_appearance_cancel_requests_pending (appearance_id) where status = 'REQUESTED'`
- 출연 목록 인덱스 변경: `(host_id)`·`(guest_id)` → `(host_id, created_at)`·`(guest_id, created_at)`
- 마이그레이션: `V202610151521__create_appearance_cancel_request_table.sql`
- Soft Delete: 출연 모듈은 Delete API·`deleted_at` 없이 상태로 끝낸다 (팀 합의, ISSUE.md APR-31)

---

## 13. 호스트 일방 취소·모집 전체 취소 (#239)

### 13.1 API

| 메서드 | 경로 | 설명 | 권한 | 성공 메시지 |
|---|---|---|---|---|
| POST | `/api/v1/appearances/{appearanceId}/host-cancel` | 출연 일방 취소 (위약금 30%) | 출연의 호스트 | 출연을 취소했습니다. |
| POST | `/api/v1/appearances/recruits/{recruitId}/cancel` | 모집 전체 취소 | 모집글 호스트 | 출연 모집을 취소했습니다. |

- 요청 본문(선택): `{"reason": "…(500자 이하)", "confirmPendingCancelRequest": true}`. 본문 없이 보내도 된다.
- 응답: 일방 취소는 출연 상세(`penaltyAmount`, `hostCancelReason`, `hpPenaltyStatus` 추가), 모집 전체 취소는 모집글 상세(`cancelReason` 추가, `hpReleaseStatus`는 빈자리 반환).
- 경로 이름은 요청서의 `/cancel`(응답 전 취소), 출연의 `/cancel-requests`(게스트 요청)와 구분한다 (APR-32 H6).

### 13.2 규칙

| 항목 | 규칙 | 근거 |
|---|---|---|
| 일방 취소 조건 | 출연의 호스트(403 `NOT_APPEARANCE_HOST`), `ESTABLISHED` 또는 `CANCEL_REQUESTED`(그 밖 409 `APPEARANCE_NOT_CANCELABLE`), 방송 예정 일시 전(409 `APPEARANCE_SCHEDULE_PASSED`) | AGENTS.md |
| 위약금 | 출연료 × `penalty-rate-percent`(30%), 원 단위 미만 버림. 나머지는 호스트에게 반환. Billing `settlePenalty` 한 번에 (보류에서 위약금 지급 + 반환) | AGENTS.md, APR-23 |
| 처리 중인 취소 요청 | `confirmPendingCancelRequest: true` 없으면 409 `CANCEL_REQUEST_CONFIRM_REQUIRED`(아무것도 바뀌지 않음). 확인하면 요청은 `CLOSED_BY_HOST_CANCEL`로 끝나고 게스트에게 위약금 지급 | AGENTS.md, APR-04, H2 |
| 모집 출처 자리 | 일방 취소해도 성립 인원을 줄이지 않는다 (그 자리 보류는 정산되어 다시 열 수 없음). 합의 취소(보류 유지라 다시 열림)와 다르다 | H3 |
| 사유 | 선택 500자. 모집 전체 취소는 모집글과 모든 출연에 같은 사유 | H1 |
| 모집 전체 취소 조건 | 모집글 호스트(403 `NOT_RECRUIT_HOST`), `RECRUITING`·`CLOSED`(그 밖 409 `APPEARANCE_RECRUIT_NOT_CANCELABLE`, 보류 처리 중·실패는 404), 방송 예정 일시 전 | APR-06 |
| 모집 전체 취소 처리 | 모집글 `CANCELED` + 진행 중 출연(성립·취소 요청 중)마다 일방 취소 + 대기 지원 `NOT_SELECTED` + 빈자리 반환. 하나라도 처리 중인 취소 요청이 있으면 확인 값 필요(먼저 모두 확인한 뒤 바꾼다) | AGENTS.md, APR-06 |
| 빈자리 반환 | 모집 중이면 1인당 출연료 × (모집 인원 − 성립 인원) — 합의 취소로 빈 자리 포함, 일방 취소된 자리 제외. 마감 뒤면 마감 때 반환해 없음. 반환 요청 ID는 마감 반환과 같은 `APPEARANCE_RECRUIT-{id}-RELEASE-1`(모집글당 하나), 사유 `RECRUIT_CANCELED` | H4 |
| 정산 실행 | 출연마다 위약금 작업 + 빈자리 반환 1건을 같은 트랜잭션에 기록하고 커밋 뒤 각각 1회 실행. 실패한 작업만 재시도·격리, 취소는 성공 응답 | H4 |
| 동시 처리 | 잠금 순서 모집글 → 출연(ID 순). 지원 수락·마감·합의 취소와 같은 순서라 교착 상태 없음 | APR-28 C9 |
| 알림 | 이번에는 이벤트를 발행하지 않는다 | H5 |

### 13.3 HP 작업 (위약금 정산)

| 컬럼 | 반환(`RELEASE`) | 위약금 정산(`PENALTY`) |
|---|---|---|
| `member_id` | 반환받는 호스트 | 보류한 호스트 (나머지를 반환받음) |
| `amount` | 반환액 | 정산 기준액 = 출연료 |
| `receiver_id`, `payout_amount` | 비움 | 게스트, 위약금 (호스트 반환 몫 = `amount − payout_amount`) |
| `appearance_id` | 비움 | 출연 ID (Billing 지급 원천 ID) |
| 멱등 키 (= Billing 요청 ID) | `…-RELEASE-1` / `…-RELEASE-APPEARANCE-{출연 ID}` | `{APPEARANCE_REQUEST·APPEARANCE_RECRUIT}-{보류 ID}-PENALTY-APPEARANCE-{출연 ID}` (= 취소 ID) |

- 지급 원천: 요청서 출처 `APPEARANCE_REQUEST`, 모집 출처 `RECRUIT_APPEARANCE` (+ 출연 ID).
- 제약 `ck_hp_tasks_penalty`: `PENALTY`면 받는 회원·출연 ID·위약금(1 이상, 기준액 이하) 필수, 그 밖은 비움.
- 마이그레이션: `V202610151702__add_appearance_host_cancel_columns.sql` (`hp_tasks.receiver_id`·`payout_amount`, `appearances.host_cancel_reason`, `recruits.cancel_reason`)
