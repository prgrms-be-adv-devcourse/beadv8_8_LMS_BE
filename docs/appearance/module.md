# 출연(Appearance) 모듈: 정책과 구조

출연 모듈이 지키는 정책, 모듈 구조, 다른 모듈과의 연동, DB 스키마를 정리한 문서다.
현재 구현된 범위(#26, #31, #50, #98, #144, #137, #168, #175, #187, #195)를 기준으로 하며, 기능이 추가될 때마다 함께 갱신한다.

- 오류 코드: [error-codes.md](error-codes.md)
- 테스트 명세: [test-spec.md](test-spec.md)
- 팀 공통 규칙: [AGENTS.md](../../AGENTS.md)

---

## 1. 범위

### 1.1 출연 모듈이 하는 일

- 출연 요청(1:1), 출연 모집(1:N)과 지원, 출연 성립, 출연 취소 요청, 출연 확인, 출연 리뷰(원본)
- 호스트·게스트 자격 확인 (회원 정보 복제본 기준)
- HP 보류·반환·지급 시점 판단과 Billing 호출

### 1.2 출연 모듈이 하지 않는 일

- HP 잔액·원장 관리 → 지갑+정산(billing) 모듈
- 로그인·회원 가입 → 회원 모듈 (출연 모듈은 Gateway가 넘긴 사용자 헤더의 회원 ID와 회원 이벤트만 사용, 20장)
- 이의 제기 판정·패널티 → 고객센터 모듈

### 1.3 구현 현황

| 기능 | 상태 | 이슈 |
|---|---|---|
| 모듈 기반 (정책 설정, Clock, 오류 응답, Billing 계약) | ✅ | #26 |
| 출연 회원 복제, 호스트·게스트 자격 확인, 팀 공통 로그인 적용 | ✅ | #31 |
| 회원 정보 변경 이벤트 반영 | ✅ | #50 |
| 출연 요청서 보내기(HP 보류)·조회·기본 정보 수정 | ✅ | #98 |
| 출연 요청 API 성공 응답 `RsData` | ✅ | #144 |
| 출연 요청 응답(수락·거절·응답 전 취소), 출연 성립, HP 작업(반환) 기록·즉시 실행 | ✅ | #137 |
| HP 작업 재시도·격리, 수락 기한 만료, 보류 재확인 (배치) | ✅ | #187 |
| 보류 결과를 끝내 모르는 요청서·모집글을 보류 무효화로 종결 (재확인 배치 포기 단계) | ✅ | #536 |
| 출연 모집글 올리기(HP 보류)·조회·기본 정보 수정 | ✅ | #175 |
| 모집 지원·지원 취소·수락(출연 성립)·거절, 모집글 지원자 수 | ✅ | #195 |
| 모집 마감 (기한 마감 배치·조기 마감, 대기 지원 정리, 미달 인원분 반환) | ✅ | #222 |
| 출연 알림 이벤트 발행 (요청 도착·지원 도착, shared 이벤트) | ✅ | #227 |
| 출연 조회, 출연 취소 요청·수락(합의 취소)·거절·자동 거절, 취소 알림 이벤트 | ✅ | #231 |
| 호스트 일방 취소(위약금 30%)·모집 전체 취소 | ✅ | #239 |
| FE 모집 화면 응답 보강 (모집 상세 내 지원, 지원 행의 모집글 제목·일시·상태) | ✅ | #243 |
| 출연 확인(확인·불참)·D+7 자동 확인, 지급 HP 작업. 이의 제기는 고객센터 관할 | ✅ | #282 |
| Billing 실제 연동 (Fake Billing·`mode` 설정 삭제, 실제 Billing 통합 테스트) | ✅ | #289 |
| 정리: 작업 뒤 상세 조회 읽기 전용 트랜잭션, 충돌 변환 통합 | ✅ | #293 |
| 격리된 HP 작업 관리자 조회·다시 시도 | ✅ | #397 |
| 출연 리뷰 작성(블라인드 공개) | ✅ | #512 |
| 리뷰 공개 이벤트 Kafka 발행 (`AppearanceReviewPublishedEvent`) | ✅ | #513 |
| 출연 상세 리뷰 정보, 회원이 받은 리뷰 내부 조회 API | ✅ | #514 |
| 작성 기한 만료 리뷰 공개 배치 (기본 꺼 둠, 작성 기한을 켜면 동작) | ✅ | #515 |
| 리뷰 연동 정리 (회원 평점·프로필 목록, FE 계약) | ✅ | #516 |
| 모집글·지원 이력 내부 조회 API (채팅 모집용 단체방) | ✅ | #586 |
| 서비스 간 HP 대사 (요청서·모집글·출연 ↔ 지갑 보류·지급 비교, 매일 배치·관리자 API) | ✅ | #581 |
| 회원 탈퇴 막힌 이유 조회 내부 API, shared Client `getWithdrawalBlocks` | ✅ | #624 |
| 보류 처리 중·실패 요청서·모집글을 올린 호스트의 목록·상세에 표시 | ✅ | #306 |
| AI 추천용 출연 변경 이벤트(`AppearanceChangedEvent`)와 출연·공개 리뷰 동기화 내부 API | ✅ | #713 |

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

정책 수치는 코드에 직접 쓰지 않고 `AppearancePolicy`로만 사용한다. 값은 `apps/appearance-app/src/main/resources/appearance.yml`에 있고, `config.AppearancePolicyProperties`가 읽어 `AppearancePolicy`로 바꾼다.

| 항목 | 값 | 설정 키 | 계산 메서드 |
|---|---|---|---|
| 최저 출연료 (1인당) | 20,000 HP | `appearance.policy.minimum-fee` | `isBelowMinimumFee(fee)` |
| 호스트 일방 취소 위약금 | 출연료의 30%, 원 단위 미만 버림 | `appearance.policy.penalty-rate-percent` | `calculatePenalty(fee)` |
| 출연 확인 기간 | 방송 예정 일시 + 7×24시간. 지나면 자동 확인(지급) | `appearance.policy.confirm-period` | `confirmDeadline(scheduledAt)` |
| 출연 취소 요청 최대 횟수 | 출연 1건당 2회 (거절 후 1회 재요청) | `appearance.policy.max-cancel-request-count` | `maxCancelRequestCount()` → 판단은 `Appearance.requestCancel` |
| 모집 인원 | 1명 ~ 10명 (최소 1명은 고정, 최대는 설정) | `appearance.policy.max-recruit-count` | `isRecruitCountAllowed(count)` |

- 정책 값은 `appearance.yml`에서만 바꾼다 (PR 리뷰를 거치도록). 환경 변수 등 외부 설정으로 덮어쓰지 않는다.
- 잘못된 값(최저 출연료 0 이하, 위약금 비율 1~100 밖 등. 0%면 위약금 정산 작업을 만들 수 없어 막는다)이면 앱이 시작되지 않는다.

### 2.3 시간

- 출연 모듈의 시각은 모두 한국 시간(Asia/Seoul)으로 다룬다. DB는 `timestamp`(시간대 없음), Java는 `LocalDateTime`.
- 기한·마감처럼 로직에 쓰는 현재 시각은 `appearanceClock` 빈(`Clock`)으로만 얻는다. 도메인 단위 테스트는 시각을 값으로 넣고, 출연 통합 테스트는 실제 시각으로 흐르되 앞으로 돌릴 수 있는 테스트 시계(`AppearanceTestClock`)를 쓴다 (#297).

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
| `…Facade` | in 계층(컨트롤러·리스너)이 부르는 유일한 진입점. 로그인 역할 검사 `@PreAuthorize` | **기능 하나 = 트랜잭션 하나를 여기서 연다** |
| `…UseCase` | 기능 하나의 로직 (예: `AppearanceRequestCreateUseCase` = 요청서 저장) | 열지 않음 (Facade 트랜잭션에 참여). 예외는 아래 |
| `…Support` | 저장소(Repository) 접근과 공통 조회 (예: `getById`, 자격 확인 조회) | 열지 않음 |

- 예외: 외부 호출(Billing)을 사이에 두고 단계별로 따로 커밋해야 하는 기능(요청서 보내기)은 Facade가 트랜잭션을 열지 않고, 각 UseCase 메서드가 직접 연다.
- 로그인 역할 검사(`@PreAuthorize`)는 Facade에서, 출연 회원 자격(정지·강등)과 업무 규칙은 UseCase·도메인에서 확인한다.
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
| `app.AppearanceEventRepublishUseCase`, `AppearanceExternalEvents` | Kafka로 못 보낸 알림 이벤트 재발행 배치 / Kafka로 내보내는 이벤트 판단(외부화 설정과 재발행 배치가 같이 씀) (#393, 11.3) |
| `app.HpTaskMetrics` | HP 작업 지표(Micrometer) 등록·갱신 (#187) |
| `domain.HpTaskRetryPolicy`, `config.AppearanceBatchProperties` | 재시도 정책(지수 백오프+지터) / 배치 설정 바인딩 (#187) |
| `app.AppearanceSupport`, `HpTaskSupport` | 출연·HP 작업 저장소 접근 |
| `app.AppearanceRequestSupport` | 요청서 저장소 접근: `getById`, `existsInProgress`, `saveNow`, `findSent`, `findReceived` (최신순 고정) |
| `in.ApiV1AppearanceRequestController`, `ApiV1AppearanceRequestSpec` | 출연 요청서 REST API(Facade만 호출)와 Swagger 명세 (요청 값 검증은 Spec에) |
| `in.AppearanceUserEventListener`, `config.AppearanceKafkaConfig` | 회원 가입·변경 이벤트 Kafka 수신 → `AppearanceUserFacade` / 소비 그룹·DLT·재시도 (#394) |
| `in.ApiV1AppearanceUserBackfillAdminController`, `app.AppearanceUserBackfillFacade`, `AppearanceUserBackfillUseCase` | 관리자 기존 회원 Backfill: 회원 내부 API 키셋 순회 → 회원마다 `AppearanceUserSyncUseCase.applySnapshot` (#394, 4.1.1) |
| `out.AppearanceUserRepository` | 출연 회원 저장·조회 |
| `out.AppearanceRequestRepository` | 출연 요청서 저장·목록 조회 |
| `out.AppearanceRepository`, `HpTaskRepository` | 출연, HP 작업 저장·조회 |
| `out.billing.BillingClient` | 지갑 연동 창구. 요청은 shared DTO, 응답은 `BillingCallResult`(성공·거절), 결과를 받지 못하면 `BillingCallException` |
| `out.billing.BillingHttpClient` | 지갑 내부 HTTP API를 공용 `BillingApiClient`로 부르는 구현(유일한 구현, #274). 결과를 그대로 넘긴다 |
| (삭제, #71) | 출연 예외는 global의 공통 핸들러(`com.hapbang.global.exception.GlobalExceptionHandler`)가 Problem Detail로 응답한다 |

**계층별 책임 (2026-10-14 정리, ISSUE.md APR-25)**

| 계층 | 맡는 일 |
|---|---|
| 도메인 | 엔티티 자신의 값과 넘겨받은 값만으로 판단할 수 있는 규칙: 값 검증, 상태 전이, 행위자·기한 확인, 금액 계산. Repository·Billing·Spring 빈을 모른다 (현재 시각도 값으로 받는다) |
| UseCase | DB·다른 데이터·외부 시스템이 필요한 일과 단계 순서: 출연 회원 자격 확인, 중복 확인, Billing 호출, 낙관적 락 충돌 → 409, 저장 |
| Facade | 역할 검사(`@PreAuthorize`), 트랜잭션 경계, UseCase 조립 |
| Controller | 로그인 정보에서 회원 ID 추출(`@LoginUserId`), 요청 값 형식 검증(Spec의 `@Valid`), `RsData` 응답 |

### 3.3 설정 파일

| 파일 | 역할 |
|---|---|
| `apps/appearance-app/src/main/resources/appearance.yml` | 정책 수치, 배치 설정 |
| `apps/appearance-app/src/main/resources/application.yaml` | 출연 앱 실행 설정. `spring.config.import: classpath:appearance.yml`로 위 파일을 읽음 |
| `apps/appearance-app/src/test/resources/hapbang-module-test.yaml` | 테스트에서 위 파일을 읽음 (테스트 전용, 배포 미포함) |

---

## 4. 다른 모듈과의 연동

| 상대 | 방식 | 주고받는 것 | 상태 |
|---|---|---|---|
| 회원 | Kafka 이벤트 수신 (`member.events.v1`) | `UserJoinedEvent`, `UserModifiedEvent` → 출연 회원 생성·갱신, `UserMaskedEvent` → 호스트 채널 URL 파기 (4.1.2) | ✅ Kafka 수신 (#394, 파기 #675). 이전에는 같은 서버 안 `@ApplicationModuleListener` |
| 회원 | 회원 내부 HTTP API (`GET /api/v1/internal/members`, shared `MemberApiClient`) | 기존 회원 목록(키셋) → 관리자 Backfill로 출연 회원 생성·갱신 | ✅ #394 (4.1.1) |
| 회원 (global) | 로그인 | Gateway가 JWT를 검증해 넘긴 `X-User-Id`·`X-User-Role` 헤더 → `@LoginUserId`·`@PreAuthorize` (20장) | ✅ 앱 쪽 (#395). 회원 JWT 발급(#370) 전까지 운영에는 헤더가 오지 않는다 |
| 지갑+정산 (billing) | 지갑 내부 HTTP API (`/api/v1/internal/billing/*`, 공용 `BillingApiClient`) | 보류, 반환, 지급, 위약금 | ✅ HTTP 연동 (#274). 이전에는 shared `BillingPort`(같은 서버 안 Java 호출, #289) |
| 회원 | 출연 내부 HTTP API 제공 (`GET /api/v1/internal/appearances/users/{userId}/withdrawal-blocks`, shared `AppearanceApiClient`) | 회원 탈퇴 전 출연 쪽 막힌 이유 (26장) | ✅ 출연 쪽 (#624). 회원 앱 연결 코드와 기능 스위치는 회원 담당 |
| AI 추천 (auxiliary-app chatbot) | Kafka 이벤트 발행 (`appearance.events.v1`) + 출연 내부 HTTP API 제공 (`/api/v1/internal/appearances/sync/*`, shared `AppearanceApiClient`) | 출연 변경(공개 결과)·공개 리뷰 별점 → AI의 읽기용 사본 (28장) | ✅ 출연 쪽 (#713). 받는 쪽은 AI 이슈 |
| 고객센터 | 없음 | 불참 게스트 이의 제기(패널티)는 고객센터 문의로 접수한다. 출연은 기록하지 않는다 (APR-35 E4) | — |

### 4.1 회원 정보 복제 (Kafka 수신, #394)

회원 앱이 다른 프로세스가 되어, 회원 이벤트를 Kafka 토픽 `member.events.v1`에서 받는다. 규칙은 팀 공통 [`kafka.md`](../architecture/infrastructure/kafka.md)(#339)의 "소비"·"재시도와 DLT"이고, 강의 앱(#429)과 같은 방식이다.

```
회원 앱 ─(Kafka member.events.v1, key 회원 ID)─▶ AppearanceUserEventListener (그룹 appearance-app.member-replica)
   ├ UserJoinedEvent   ─▶ AppearanceUserFacade.syncJoined()   ─▶ createIfAbsent() ─▶ appearances.users
   ├ UserModifiedEvent ─▶ AppearanceUserFacade.syncModified() ─▶ update()
   ├ UserMaskedEvent   ─▶ AppearanceUserFacade.maskWithdrawn() ─▶ AppearanceHostChannelMaskUseCase.mask() (4.1.2)
   └ 그 밖의 회원 이벤트(로그아웃 등) ─▶ 무시하고 커밋
```

| 이벤트 | 발행 시점 (회원 앱) | 출연 처리 |
|---|---|---|
| `UserJoinedEvent` | 회원 가입 | 출연 회원 생성. **이미 있으면 무시** |
| `UserModifiedEvent` | 회원 정보(닉네임·역할·상태) 변경 | 있는 출연 회원 갱신. **없으면 `APPEARANCE_USER_NOT_FOUND`로 실패** → 재시도 뒤 DLT |
| `UserMaskedEvent` | 탈퇴 회원 개인정보 파기 (복구 기한 + 1시간 뒤, 매일 10:30, #594) | 그 회원이 호스트인 요청서·모집글의 채널 URL을 `withdrawn`으로 덮기 (4.1.2). 출연 회원이 없어도 실패하지 않는다 |

| 항목 | 내용 |
|---|---|
| 리스너 | 클래스 레벨 `@KafkaListener`에 가입·변경·파기 `@KafkaHandler`, 모르는 타입은 `@KafkaHandler(isDefault = true)`에서 무시 (토픽에 회원 이벤트 타입이 섞임, ADR 0001) |
| 역직렬화 | `ErrorHandlingDeserializer` + JSON(`__TypeId__` 헤더), 신뢰 패키지는 `com.hapbang.shared.member.event`만. 시작점 `earliest` |
| 재시도 | `DefaultErrorHandler` 블로킹 재시도 1초 → 2초 → 4초(최대 10초) 3회. 그동안 그 partition의 뒤 메시지는 기다림 |
| DLT | `member.events.v1.appearance-app.member-replica.DLT`. 출연 앱이 `NewTopic`으로 선언(partition 3, 보존 7일). 원본과 같은 partition 번호. 역직렬화 실패는 재시도 없이 원본 바이트 그대로 DLT. 재처리는 kafka.md runbook |
| 직렬화 설정 | Boot 기본 리스너 팩토리를 쓴다(강의와 같음). 이를 위해 `appearance.yml`에서 Modulith Kafka 모듈의 JSON 기본 세트를 끈다(`spring.modulith.events.kafka.enable-json=false`). 켜 두면 그 모듈의 바이트 JSON 변환기 빈이 기본 리스너 팩토리에도 들어가, 역직렬화기가 이미 만든 회원 이벤트 객체를 다시 바이트로 보고 변환하려다 모든 메시지가 실패한다(`Only String, Bytes, or byte[] supported`). 발행 직렬화는 `JacksonJsonSerializer`가 맡는다(11.3) |
| 로컬·테스트 | 로컬은 compose의 Kafka(`localhost:9092`)로 운영과 같이 받는다(회원 앱을 한 번 띄워 토픽을 만든다). 테스트에는 Kafka가 없어 리스너 자동 시작과 토픽 생성을 끈다(`spring.kafka.listener.auto-startup`·`spring.kafka.admin.auto-create` = false). Kafka 테스트만 Testcontainers로 켠다 |

- **출연 회원은 가입 이벤트로만 만든다.** 늦게 처리된 가입 이벤트가 이미 반영된 변경 정보를 덮어쓰지 않게 하기 위해서다 (채팅·강의와 같은 방식). 가입보다 먼저 온 변경은 DLT에 쌓이고 runbook대로 다시 넣는다(사용자 결정 10/30, 강의 #429와 같게).
    - 가입·변경이 각자 "없으면 생성"하면 늦게 처리된 쪽이 앞의 정보를 덮어쓸 수 있다. 복제 회원은 `@Version`이 있어 새 행 `save()`는 insert라, 같은 회원을 동시에 만들면 한쪽이 PK 충돌로 실패하고 재처리 때 정리된다.
- 멱등·순서: 공통 `ReplicaUser.syncFrom`이 회원 버전(`UserDto.version`)으로 이미 반영한 버전보다 크지 않은 정보(늦게 온·중복된 이벤트)를 무시한다 (회원 #292). Modulith 발행은 같은 key라도 순서가 바뀔 수 있어 이 비교가 필요하다.
- 반영이 커밋된 뒤 리스너가 끝나야 offset이 커밋된다. 커밋 전에 죽으면 같은 이벤트를 다시 받고 버전 비교가 흡수한다.
- 회원 앱이 Kafka로 보내기 전(#416)에 가입한 회원은 가입 이벤트가 없다. 이 회원들은 관리자 Backfill(4.1.1)로 채운다. 채우기 전에는 출연 API에서 `APPEARANCE_USER_NOT_FOUND`, 정보 변경 이벤트는 DLT로 간다(Backfill 뒤 DLT를 다시 넣으면 반영된다).
- 회원이 정지·탈퇴 상태로 바뀌면 이후 출연 거래 자격 확인에서 `APPEARANCE_USER_NOT_ACTIVE`가 된다.
- 한계: 가입보다 변경 이벤트가 먼저 오면, DLT를 다시 넣기 전까지 그 회원은 출연에서 "회원 없음"이다.

#### 4.1.1 기존 회원 Backfill (관리자 수동 실행, #394)

`POST /api/v1/appearances/admin/users/backfill?afterId=0&limit=100`. 지갑 Backfill(#324)과 같은 구조다.

```
관리자 ─▶ AppearanceUserBackfillFacade(@PreAuthorize ADMIN, 트랜잭션 없음)
        ─▶ AppearanceUserBackfillUseCase
             loop: MemberApiClient.getMembers(afterId, 500) ─▶ 키셋 계약 확인
                   회원마다 [트랜잭션] AppearanceUserSyncUseCase.applySnapshot(): 없으면 생성 / 회원 버전이 더 높으면 갱신 / 아니면 그대로
```

| 항목 | 내용 |
|---|---|
| 반영 규칙 | 회원 원본의 현재 정보(스냅샷, 회원 버전 포함)로 없으면 만들고, 있으면 `ReplicaUser.syncFrom` 버전 비교. 이벤트로 반영한 최신 정보를 덮지 않고, 다시 실행해도 결과가 같다. 이벤트의 "가입으로만 생성" 규칙과 달리 스냅샷은 버전이 있는 현재 정보라 생성해도 안전하다 |
| 범위 | 한 번에 최대 1,000명(기본 100), 회원 내부 API는 500명씩. 끝나지 않으면 `nextAfterId`로 이어서 실행. Gateway 응답 제한(30초) 안에 끝나도록 지갑(5,000)보다 낮게 두었다. 넘기면 504지만 서버는 끝까지 처리하고 결과는 로그에 남는다(그동안 다시 요청하면 409) |
| 실패 | 회원 하나의 반영 실패는 `failed`·`failedIds`(최대 100개)로 세고 계속. 회원 내부 API 장애·키셋 계약 위반은 멈추고 503 `MEMBER_SOURCE_UNAVAILABLE` / 거절은 `MEMBER_SOURCE_REJECTED`, Problem Detail에 집계와 `resumeAfterId`. 실행 중 다시 요청하면 409 `BACKFILL_ALREADY_RUNNING`(출연 앱 1대 전제) |
| 권한 | 보안 설정(`/api/v1/appearances/admin/**`)과 `@PreAuthorize`(Gateway 사용자 헤더의 역할). HP 작업 관리와 달리 복제 회원으로 관리자 자격을 다시 확인하지 않는다(Backfill 전에는 관리자 자신의 출연 회원이 없을 수 있다) |
| 설정 | `hapbang.clients.member.base-url`: 운영 `${MEMBER_API_BASE_URL}`(k8s `http://member-app:8081`), 로컬 `http://localhost:8081`. 주소가 없으면 Client 빈이 없고 실행하면 503 |
| 운영 절차 | 출연 앱 배포 뒤 관리자가 `completed = true`가 될 때까지 `nextAfterId`로 이어서 실행한다. 그 전에 쌓인 회원 변경 DLT는 Backfill 뒤 runbook대로 다시 넣는다 |

#### 4.1.2 탈퇴 회원 파기: 호스트 채널 URL (#675)

회원 앱이 복구 기한이 지난 탈퇴 회원의 개인정보를 파기하고 `UserMaskedEvent(userId, maskedAt)`를 보낸다(#594). 출연이 가진 그 회원의 파기 대상은 **호스트가 요청서·모집글을 쓸 때 직접 입력한 호스트 채널 URL**뿐이다(`docs/policy.md` 삭제 정책·파기 방법). 닉네임은 같은 때 오는 `UserModifiedEvent`로 바뀐다.

| 항목 | 내용 |
|---|---|
| 대상 | `appearances.requests`·`appearances.recruits`에서 `host_id` = 파기 회원인 행의 `host_channel_url` |
| 바꾸는 값 | `withdrawn` (`AppearanceBasicInfo.WITHDRAWN_HOST_CHANNEL_URL`). **응답 계약 값**이다: 상세 응답 `hostChannelUrl`이 이 값이면 FE는 링크 대신 "탈퇴한 회원의 채널"로 보여 준다. 그 밖에 http(s)가 아니면 링크로 그리지 않는다(FE 방어) |
| 방법 | 한 트랜잭션에서 update 쿼리 두 번(`maskHostChannelUrl`). 마이그레이션 없음(`NOT NULL` 유지). 형식 제한(http(s))은 생성·수정 때만 확인하므로 걸리지 않는다. 버전·수정 시각은 바꾸지 않는다 |
| 중복 | 이미 `withdrawn`인 행은 건드리지 않아 같은 이벤트를 여러 번 받아도 결과가 같다(회원 아웃박스·재발행 #421, 최소 한 번 전달) |
| 진행 중인 글 | 요청서 `HOLD_PENDING`·`REQUESTED`, 모집글 `HOLD_PENDING`·`RECRUITING`은 덮지 않고 error 로그 `[HOST_CHANNEL_MASK_SKIPPED] userId=…, activeRequests=…, activeRecruits=…`. 탈퇴 확인(26장)이 막았어야 하는 비정상 상태라 운영이 확인한다. 끝난 글은 그대로 덮는다 |
| 출연 회원 없음 | 실패하지 않는다(호스트로 쓴 글이 없으면 덮을 것도 없다) |

- 다른 복사본은 없다: 출연·지원·리뷰·HP 작업·대사 기록에는 채널 주소가 없고, Kafka 이벤트(`AppearanceRequestDto`)·이벤트 보관(`event_publication` archive)·로그에도 싣지 않는다.
- 결정 근거: 이슈 #675 댓글(a안, 고정 값). 비우기(null)는 마이그레이션과 응답 계약(`string`) 변경이 필요해 하지 않았다.
- **건너뛴 글 다시 덮기 (운영 조치)**: 파기 이벤트는 한 번만 오므로 `[HOST_CHANNEL_MASK_SKIPPED]`로 건너뛴 글을 자동으로 다시 덮는 경로는 없다. 로그의 거래가 끝난 뒤 같은 조건으로 직접 덮는다: `update appearances.requests set host_channel_url = 'withdrawn' where host_id = :userId and status not in ('HOLD_PENDING', 'REQUESTED') and host_channel_url <> 'withdrawn'` (모집글은 `appearances.recruits`, 제외 상태 `HOLD_PENDING`·`RECRUITING`).
- **배포 순서**: FE가 `withdrawn`을 글자로 보여 주는 변경(FE 이슈)이 첫 파기 전에 나가야 한다. 그 전에 파기되면 상세 화면에 `withdrawn`이 상대 링크로 보인다(데이터는 맞고 표시만 어긋남).

### 4.2 Billing 연동 (#168)

출연 모듈은 "언제, 어떤 금액으로 Billing을 호출하는지"만 책임진다. 출연 쪽 창구는 `out/billing/BillingClient.java`이고,
요청·응답은 지갑 내부 API 계약(`docs/api/internal-billing.md`, `shared.billing.dto.*`)과 같다
(Billing 담당과 정리한 기준: 위약금 처리·70:30 계산만 출연 방식, 나머지는 Billing 기준).
#274부터 지갑을 HTTP로 부른다: 구현 `BillingHttpClient`가 공용 `BillingApiClient`(#272)를 부르고, 응답은 `BillingCallResult`로 받는다.

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
- **지갑 응답은 결과 종류로 분기한다** (#274). 예외 클래스로 구분하지 않는다(지갑 업무 예외는 HTTP로 넘어오지 않는다).

  | 결과 | 보류 (요청서·모집글) | HP 작업 (반환·지급·위약금) |
  |---|---|---|
  | `Succeeded` (같은 요청을 다시 보냈으면 처음 결과) | `REQUESTED` / `RECRUITING` | `DONE` |
  | `Failed` 잔액 부족 | `HOLD_FAILED`, 409 `INSUFFICIENT_BALANCE`(출연 문구) | 바로 `DEAD` |
  | `Failed` 닫힌 지갑 (#648) | `HOLD_FAILED`, 409 `WALLET_CLOSED`, info 로그 | (HP 작업에는 나오지 않음: 닫힌 지갑도 반환·지급·위약금은 받는다) |
  | `Failed` 그 밖 (지갑 없음, 목록 밖 코드, 멱등 충돌 등) | `HOLD_FAILED`, 500 `HP_HOLD_FAILED`. 지갑 코드·상태·설명은 오류 로그에만 | 바로 `DEAD`, `last_error`에 코드·설명(목록 밖이면 원래 코드·HTTP 상태) |
  | `BillingCallException` (`UNKNOWN_RESULT`: 5xx·응답 시간 초과·응답 도중 끊김 / `RETRYABLE`: 연결 실패·408·425·429·회로 열림) | `HOLD_PENDING` + 503 `BILLING_UNAVAILABLE` → 재확인 배치가 같은 참조 대상으로 다시 보내고, 만든 지 1시간이 지나면 보류 무효화로 `HOLD_FAILED` (8.2, #536) | 재시도 정책대로 같은 요청 ID로 다시 보내고 10회째 `DEAD` |

- `RETRYABLE`은 공용 Client가 먼저 짧게(기본 3회) 다시 보내고, 그래도 실패하면 출연에 넘어온다 (#413). 출연은 HTTP 재시도를 따로 하지 않는다.
- 지갑 호출 전후의 예상하지 못한 예외(요청 만들기 오류 등)는 HP 작업이면 같은 요청 ID로 다시 보내면 되므로 재시도로 다룬다. 보류면 500으로 응답하고 `HOLD_PENDING`이 남아 재확인 배치가 다시 보낸다.
- 출연은 지갑을 **DB 트랜잭션 밖에서** 부른다. 지갑을 기다리는 동안 DB 연결을 잡지 않고, 업무 상태를 먼저 커밋해 호출 뒤 장애에도 재확인·재시도할 근거를 남긴다.
- 설정: `hapbang.clients.billing.base-url`(환경 변수 `BILLING_API_BASE_URL`, local은 `http://localhost:8082`)과 내부 API 키(`HAPBANG_INTERNAL_API_KEY`). 응답 대기(연결 2초·응답 5초)·Circuit Breaker·짧은 재시도는 공용 Client 기본값이다. 회로가 열려 있으면 배치는 회차를 건너뛴다(8.1, #439). 지갑 주소가 없으면 출연 앱이 뜨지 않는다.
- **반환·지급처럼 업무 상태 변경 뒤에 하는 Billing 호출은 HP 작업(`appearances.hp_tasks`)으로 기록하고 실행한다** (아래 4.3, #137).
- 출연 업무 코드는 `BillingClient`만 안다. 구현은 `BillingHttpClient` 하나다 (#274에서 shared `BillingPort`를 부르던 `BillingPortClient`를 없앴다).
- 출연 테스트: 업무 규칙은 Mockito 대역(기본 성공, `BillingStubs`), HTTP 응답 해석은 Mock Billing 서버(`MockBillingIntegrationTest`, test-spec 23·24장).

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
- 실패 기록(`last_error`): 지갑이 거절하면 `{지갑 오류 코드}: {설명}`(바로 `DEAD`), 결과를 받지 못하면 `{UNKNOWN_RESULT|RETRYABLE} [{HTTP 상태}] [{지갑 코드}]: {메시지}`(재시도). 8.4 참고.

---

## 5. DB 스키마 (`appearances`)

테이블은 Flyway 마이그레이션으로 만든다 ([docs/db-migration.md](../db-migration.md)). 위치: `apps/appearance-app/src/main/resources/db/migration/`

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
| `status` | VARCHAR(255) | `ESTABLISHED` / `CANCEL_REQUESTED` / `CONFIRMED` / `ABSENT` / `CANCELED_BY_HOST` / `CANCELED_BY_AGREEMENT` |
| `established_at` | TIMESTAMP(6) | 성립 시각 |
| `confirm_type`, `confirmed_at` | VARCHAR(255), TIMESTAMP(6) | 출연 확인 방식(`HOST`/`AUTO`)·시각 (#282) |
| `absent_at`, `absent_reason` | TIMESTAMP(6), VARCHAR(500) | 불참 처리 시각·사유 (#282, 예전 `no_show_at`) |
| `penalty_amount`, `host_cancel_reason` | BIGINT, VARCHAR(500) | 호스트 일방 취소 위약금·사유 (#239) |
| `canceled_at` | TIMESTAMP(6) | 취소 시각 (합의 취소·일방 취소) |
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
| `manual_retry_count`, `last_manual_retry_by`, `last_manual_retry_at` | INTEGER(기본 0), BIGINT, TIMESTAMP(6) | 관리자 다시 시도 횟수, 마지막으로 다시 시도한 관리자 회원 ID·시각 (#397). 셋 다 비었거나 셋 다 있다 |
| `version`, `created_at`, `updated_at` | | 낙관적 락, 생성·수정 시각 |

- 마이그레이션(5.3·5.4): `V202610141109__create_appearance_and_hp_task_tables.sql` (#137)
- 다시 시도 컬럼: `V202610291142__add_hp_tasks_manual_retry_columns.sql` (#397)
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

- 권한: 로그인(401은 보안 필터) → Facade `@PreAuthorize("hasAnyRole('INFLUENCER', 'VERIFIED_INFLUENCER')")`(거절 시 공통 403 `FORBIDDEN`) → UseCase에서 출연 회원 자격 재확인(`NOT_INFLUENCER`·`APPEARANCE_USER_NOT_ACTIVE`). 헤더 역할은 토큰 발급 시점 값(최대 15분 늦음)이라, 그 뒤 정지·강등은 서비스 확인이 막는다 (조회 포함).
- CSRF 토큰은 필요 없다. 쿠키가 아니라 Gateway 사용자 헤더로 인증한다 (#395, 20장).
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
       ├─ 지갑 거절 (Failed)            → HOLD_FAILED 기록 [트랜잭션 2] → 잔액 부족 409 INSUFFICIENT_BALANCE, 닫힌 지갑 409 WALLET_CLOSED, 그 밖 500 HP_HOLD_FAILED
       └─ 결과를 받지 못함 (BillingCallException) → HOLD_PENDING 그대로, 503 BILLING_UNAVAILABLE
```

- 저장을 먼저 커밋하는 이유: Billing을 기다리는 동안 DB 트랜잭션을 잡지 않고, 보류 결과와 관계없이 요청서 기록이 남게 하기 위해서다.
- `HOLD_PENDING`으로 남은 요청서는 보류 재확인 배치가 같은 요청서로 보류를 다시 보내 확인하고 `REQUESTED`/`HOLD_FAILED`로 정리한다 (8.2, #187). Billing은 같은 보류를 다시 받으면 처음 결과를 돌려준다.

### 6.3 규칙

| 규칙 | 내용 | 이유 |
|---|---|---|
| 진행 중 요청 1건 | 같은 호스트 → 같은 게스트에게 `HOLD_PENDING`·`REQUESTED` 요청서가 있으면 새 요청 차단 (409). 끝난 요청서(수락·거절·만료·취소·보류 실패)는 제외 | 출연이 성립하기 전까지 요청은 1건으로 하여 요청 중복을 확실히 막는다 |
| 보류 미완료·실패는 호스트만 | `HOLD_PENDING`·`HOLD_FAILED`는 보낸 호스트의 보낸 목록(기본 포함)·상세에만 보인다. 받은 목록·게스트 상세·모든 동작(수정·취소·수락·거절)은 404 (27장, #306) | 게스트에게는 도착하지 않은 요청서다. 호스트는 결과를 알아야 다시 보낼지 정할 수 있다 |
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
| GET | `.../sent?status=`, `.../received?status=` | 목록 상태 필터. 여러 번 보낼 수 있음, 생략하면 전체. 보낸 목록은 `HOLD_PENDING`·`HOLD_FAILED`도 고를 수 있고(생략하면 포함), 받은 목록에서 두 상태는 400 (#306) | 200 |

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
| GET | `/api/v1/appearances/recruits/{recruitId}` | 상세 (마감 반환 진행 상태 `hpReleaseStatus`는 호스트에게만, 조회한 회원의 지원 `myApplication` #243) | 로그인 회원 누구나 | 출연 모집글을 조회했습니다. |
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
       ├─ 지갑 거절 (Failed)            → HOLD_FAILED 기록 → 잔액 부족 409 INSUFFICIENT_BALANCE, 닫힌 지갑 409 WALLET_CLOSED, 그 밖 500 HP_HOLD_FAILED
       └─ 결과를 받지 못함 (BillingCallException) → HOLD_PENDING 그대로, 503 BILLING_UNAVAILABLE ("내 모집글 목록에서 확인")
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
| 상세·내 목록 | 다른 회원에게는 `RECRUITING`·`CLOSED`·`CANCELED`만. `HOLD_PENDING`·`HOLD_FAILED`는 올린 호스트의 내 목록(기본 포함)·상세·지원자 목록(빈 목록)에만 보이고, 지원·수정·마감·취소는 호스트도 404 (27장, #306) | 요청서와 같음 |
| 수정 | 호스트만, `RECRUITING`이고 모집 마감 일시 전까지. 동시 수정은 409 `CONCURRENT_UPDATE` | ISSUE.md APR-08 |
| 응답 | 성립 인원(`selectedCount`), 지원자 수(`applicantCount`, #195), 마감 방식·마감 시각(#222), 마감 반환 진행 상태(`hpReleaseStatus`, 호스트만, 마감 반환만 — 합의 취소 반환은 출연 상세, #222·#231), 버전 포함. 취소 시각은 모집 취소에서 채움 | |
| 미정 | 방송 플랫폼·카테고리 등 분류, 검색·필터, 비회원 공개, 정지 호스트 모집글 노출 | 필요할 때 결정 |

---

## 8. 출연 배치 (#187)

### 8.1 구조

```
in.AppearanceBatch (@ConditionalOnSchedulingEnabled, 매분 실행, KST)
  ├ 00초 AppearanceHoldRecheckUseCase.recheckDue()   보류 처리 중 요청서·모집글 재확인
  ├ 05초 AppearanceEventRepublishUseCase.republishDue()  Kafka로 못 보낸 알림 이벤트 재발행 (#393, 11.3)
  ├ 10초 AppearanceRecruitDeadlineCloseUseCase.closeDue()  모집 기한 마감 + 미달분 반환 (#222, 10장)
  ├ 15초 AppearanceReviewExpirePublishUseCase.publishDue()  리뷰 작성 기한 만료 공개 (#515, 21.7. 작성 기한이 없으면 아무것도 안 함)
  ├ 20초 AppearanceRequestExpireUseCase.expireDue()  수락 기한 만료 + 반환
  ├ 30초 AppearanceCancelAutoRejectUseCase.rejectDue()  출연 취소 요청 자동 거절 (#231, 12장)
  ├ 40초 HpTaskRetryUseCase.retryDue()               HP 작업 재시도·격리 + 지표 갱신
  └ 50초 AppearanceAutoConfirmUseCase.confirmDue()   출연 자동 확인 + 지급 (#282, 14장)
```

- 팀 공통 스케줄링 규칙(#157)을 따른다: `hapbang.scheduling.enabled=true`일 때만 배치 빈이 생기고(앱에서만 켜짐), 배치 클래스는 주기만 정하고 처리는 UseCase가 한다. 테스트는 UseCase를 직접 부른다.
- 배치는 로그인 회원이 아니라 시스템이 실행하므로 역할 검사(`@PreAuthorize`)가 있는 Facade를 거치지 않고 UseCase를 직접 부른다 (팀 강의 배치와 같은 방식, ISSUE.md APR-26).
- 한 번에 최대 100건(`batch-size`), 건마다 짧은 트랜잭션, 건마다 예외를 잡아 다음 건을 계속 처리한다. 같은 배치는 이전 실행이 끝나야 다음 실행이 시작된다.
- 설정: `appearance.yml`의 `appearance.batch.*` (주기, 건수, 대기 시간, 재시도 정책). `config.AppearanceBatchProperties`가 읽는다.
- **지갑 회로가 열려 있으면 지갑을 부르는 배치(보류 재확인·HP 작업 재시도)는 그 회차를 건너뛴다** (#439, 강의 D67과 같음).
  `BillingClient.isCircuitOpen()`(공용 Client 회로가 OPEN·FORCED_OPEN)이 `true`면 대상을 가져오지 않는다. 회차 시작 때만이 아니라 **건마다 보내기 전에** 본다(`app.BillingCircuitGate`):
  시험 중(HALF_OPEN)에는 회로가 정한 수만큼만 실제로 보내고, 시험이 실패하면 회차 도중에 다시 열리므로 남은 건은 다음 회차로 넘긴다.
  로그는 상태가 바뀔 때만 남긴다(멈춤 WARN, 재개 INFO).
  열린 회로에 보내면 요청은 나가지 않고 HP 작업 시도 횟수만 올라, 지갑이 오래 내려가 있으면 정상 작업이 `DEAD`가 되기 때문이다.
  회로는 호출이 없어도 열린 시간(기본 30초)이 지나면 시험 중(HALF_OPEN)으로 넘어가고, 시험 중에는 평소처럼 실행한다.
  사용자 요청 안의 실행(보류, 거절·취소 직후 HP 작업 1회)과 관리자 다시 시도는 회로를 보지 않고 그대로 부른다(회로가 열려 있으면 `RETRYABLE` → 보류 `HOLD_PENDING` + 503, HP 작업은 대기로 남아 배치가 이어받음).
  반환·지급을 부르는 다른 배치(모집 마감·수락 기한 만료·자동 거절·자동 확인)는 업무 상태를 먼저 확정하고 HP 작업을 1회 실행하므로 건너뛰지 않는다. 실패한 작업은 HP 작업 재시도가 이어받는다.

### 8.2 HP 보류 재확인

| 대상 | 처리 | 결과 |
|---|---|---|
| 만든 지 1분(`pending-grace`) 넘고 1시간(`hold-void-after`) 전인 `HOLD_PENDING` 요청서·모집글 | 같은 보류를 다시 요청 (보내기·올리기의 HoldUseCase 그대로) | 성공 → `REQUESTED`/`RECRUITING`, Billing 거절 → `HOLD_FAILED`, 여전히 알 수 없음 → 그대로 두고 다음 주기 |
| 만든 지 1시간(`hold-void-after`)이 지난 `HOLD_PENDING` 요청서·모집글 (**포기**, #536) | 보류 대신 **보류 무효화**를 보낸다 (`POST /api/v1/internal/billing/hold-voids`, `{userId: 호스트, referenceType: APPEARANCE_REQUEST·APPEARANCE_RECRUIT, referenceId}`) | `RELEASED`(묶였던 보류를 호스트에게 반환)·`VOIDED`(보류 없었음)·`ALREADY_CLOSED` → `HOLD_FAILED`. 결과를 모름 → 그대로 두고 다음 주기에 다시 무효화. 지갑 거절(예: `HOLD_OWNER_MISMATCH`) → 경고 로그, 그대로 (운영 확인) |

- Billing은 같은 참조 대상의 보류를 다시 받으면 처음 결과를 돌려준다(`replayed`). 처음 요청이 Billing에서 처리됐어도 두 번 보류되지 않는다.
- 1분 대기는 진행 중인 보내기·올리기 요청과 겹치지 않게 하는 장치다. 겹쳐도 Billing이 같은 결과를 주고, 보류 결과 반영(`markRequested`·`markRecruiting`·`markHoldFailed`)이 멱등이라 안전하다.
- 수락 기한이 이미 지난 요청서도 보류 결과대로 `REQUESTED`로 정리하고, 같은 분의 만료 배치가 `EXPIRED` + 반환한다 (APR-26 S5). 이 경우의 불편은 고객센터에서 처리한다. 포기 기준(1시간)이 지난 뒤면 수락 기한과 관계없이 같은 기준으로 무효화한다.

**포기 단계 (보류 무효화, #536)**

지갑이 계속 결과 불명을 주면(예: 500 `HOLD_STATE_INCONSISTENT`) 재확인만으로는 영원히 `HOLD_PENDING`이다. 그러면 호스트는 그 요청서·모집글을 정리할 수 없고(같은 게스트에게 새 요청서도 보낼 수 없다: 진행 중 1건 제한), 지갑에 보류가 실제로 생겼다면 HP가 묶여 있다. 그래서 포기 기준이 지나면 지갑의 보류 무효화로 끝낸다.

| 항목 | 내용 |
|---|---|
| 포기 기준 | 만든 시각(`created_at`)부터 `appearance.batch.hold-void-after`(기본 1시간). `pending-grace`보다 길어야 앱이 뜬다. 1시간 전에는 지금처럼 보류를 다시 보낸다(정상적으로 늦어진 건을 실패로 만들지 않는다) |
| 자동 | 관리자 수동 단계는 두지 않는다. 보류는 HP 작업이 아니라 관리자 HP 작업 API(#397)로 다룰 수 없다 |
| 무효화 결과 | 어떤 결과든 그 참조 대상에는 앞으로 보류가 만들어지지 않는다(지갑이 `HOLD_VOIDED`로 거절). 참조 대상당 한 번이고, 다시 보내면 처음 결과(`replayed`)를 받는다 |
| 겹칠 때 | 지갑이 보류와 무효화를 같은 락으로 처리한다. 무효화가 먼저면 늦게 도착한 보류는 `HOLD_VOIDED`로 거절돼 `HOLD_FAILED`(500 `HP_HOLD_FAILED`, 예상한 거절이라 info 로그), 보류가 먼저면 무효화가 반환(`RELEASED`)하고 `HOLD_FAILED`. 두 경우 상태가 같다 |
| 되돌릴 수 없음 | 무효화한 요청서·모집글로는 다시 보류할 수 없다. 호스트는 새로 보낸다(`HOLD_FAILED`는 진행 중이 아니라 같은 게스트에게 다시 보낼 수 있다) |
| 로그 | 포기 단계로 넘어갈 때 info(`HP 보류 포기`), `RELEASED`는 반환액과 함께 경고(지갑에 실제로 묶였던 HP), `ALREADY_CLOSED`는 경고(출연은 `HOLD_PENDING` 동안 반환·지급을 보내지 않아 예상 밖 상태, 강의 #535와 같음), `VOIDED`는 info, 무효화 거절은 경고 |
| 알림 | 호스트에게 따로 알리지 않는다 (알림 앱 #193 전) |
| 회로 | 지갑 회로가 열려 있으면 그 회차를 건너뛴다(무효화도 보내지 않음, #439) |

- 운영 확인 대상: 지갑이 무효화를 거절한 건(예: `HOLD_OWNER_MISMATCH`)은 `HOLD_PENDING`으로 남아 **매분 무효화를 다시 보내고 경고 로그 `HP 보류 무효화 거절`이 반복**된다. 한 번에 처리하는 `batch-size` 자리도 차지한다. 계약과 다른 상태라 운영자가 지갑과 함께 확인한다. 쌓이면 재전송 간격이나 별도 표시를 나중에 둔다.
- 배치 단일 실행 전제: 출연 앱은 `replicas: 1`이라 배치가 한 곳에서만 돈다. 다만 배포(RollingUpdate, `maxSurge: 1`) 중 잠깐 두 pod가 함께 떠 있으면, 같은 건이 1시간 경계에서 한쪽은 보류를 다시 보내 성공(`REQUESTED`)하고 다른 쪽은 무효화(`RELEASED`)해 **HP 보류 없는 `REQUESTED`**가 생길 수 있다(겹치는 몇 초 안에 정확히 1시간 경계여야 해 아주 드묾). 이때 무효화 쪽의 `HOLD_FAILED` 반영은 상태가 이미 `REQUESTED`라 실패하고 경고 로그 `HP 보류 재확인 중 예외`가 남으므로, 그 로그가 보이면 해당 요청서·모집글을 확인한다. replica를 늘리거나 배치를 여러 곳에서 돌리게 되면 분산 락 등 단일 실행 장치를 먼저 둔다.

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
| 지갑 거절 (`Failed`) | **바로 `DEAD`**. 다시 보내도 결과가 같으므로 재시도하지 않고 지갑 오류 코드·설명을 기록 (APR-26 S3) |
| 목록 밖 코드 중 401·403·404 (`Failed` `UNMAPPED`) | 내부 API 키 교체·경로 오류 같은 연동 장애라 고치면 같은 요청이 성공한다. 아래 재시도와 같게 다룬다 (#274) |
| 결과를 받지 못한 실패 (`BillingCallException`) | 지수 백오프 + 지터로 다음 시도 시각 결정: 1분 → 2분 → 4분 → … → 최대 1시간 (+0~20%). **10회 실패 시 `DEAD`** |
| 회로가 열려 보내지 못함 (`BillingCallException` rawCode `CIRCUIT_OPEN`) | 요청이 나가지 않았으므로 **시도 횟수를 올리지 않고** 다음 시도 시각만 1분(+지터) 뒤로 미룬다 (#439). 배치는 회로가 열려 있으면 실행하지 않지만, 시험 중에 사용자 요청과 배치가 시험 호출 몫을 나눠 쓰면 생길 수 있다 |

- 재시도 대상: `PENDING`이고 다음 시도 시각이 지났으며, 한 번 이상 시도했거나 만든 지 1분이 지난 작업. 거절·취소 직후 요청 안에서 바로 실행 중인 작업과 겹치지 않게 하기 위해서다.
- 같은 작업을 동시에 실행해도 같은 반환 요청 ID라 Billing은 한 번만 처리하고, 결과 기록은 대기 중일 때만 한다(버전 충돌이면 먼저 기록한 쪽을 따름).
- 재시도 정책: `domain.HpTaskRetryPolicy` (`appearance.batch.retry-*`).
- 지갑 회로가 열려 있으면 그 회차를 건너뛰고, 회차 도중에 열리면 남은 작업을 실행하지 않는다(8.1, #439). 실행하지 않은 작업의 시도 횟수는 그대로이고, 대기·격리 작업 수 지표는 갱신한다.

### 8.5 모니터링

| 장치 | 내용 | 확인 방법 |
|---|---|---|
| 격리 로그 | `DEAD`가 되는 순간 ERROR 로그 `[HP_TASK_DEAD] taskId=…, type=…, ref=APPEARANCE_REQUEST-12, memberId=…, amount=…, attempts=…, error=…` | `docker logs <컨테이너> 2>&1 \| grep HP_TASK_DEAD` |
| 지표 (Micrometer) | `appearance.hp_task.executions{result=done\|retry\|dead}`, `appearance.hp_task.pending`, `appearance.hp_task.dead` (재시도 배치 실행 시점 값) | 앱의 `MeterRegistry`에 등록됨. 보는 경로(`/actuator/metrics`, Prometheus)는 팀 합의 후 연다 |
| 관리자 API | 격리 작업 목록 `GET /api/v1/appearances/admin/hp-tasks`, 다시 시도 `POST …/{taskId}/retry` (19장, #397) | 관리자 로그인 |
| 다시 시도 로그 | 관리자가 다시 시도하는 순간 INFO 로그 `관리자가 DEAD HP 작업을 다시 시도하게 했다 taskId=…, adminId=…, manualRetryCount=…, previousAttempts=…, previousError=…` (다시 시도하면 시도 횟수가 0으로 돌아가므로 직전 값을 여기에 남긴다). alert 대상이 아니라 표식을 붙이지 않는다(#681, `docs/dev/logging.md`) | `grep "DEAD HP 작업을 다시 시도"` |
| DB | `select * from appearances.hp_tasks where status = 'DEAD'` | 언제든 |

- Prometheus + Grafana 대시보드·alert는 `docker/compose*.yaml` 변경이 필요해 팀 결정 사항이다.
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
- 지원 응답(목록 포함)에는 모집글의 **현재** 제목·방송 예정 일시·상태(`recruitTitle`·`recruitScheduledAt`·`recruitStatus`)가 있다. 지원 시점에 저장하지 않고 조회 때 모집글을 읽는다 (한 페이지의 모집글을 한 번에, #243 APR-33).
- 모집글 상세에는 조회한 회원의 지원 `myApplication`(같은 지원 응답 모양)이 있다. 재지원 불가라 최대 1건이고, 취소·미선정도 상태 그대로 담는다. 지원한 적이 없거나 호스트 본인이면 `null` (#243).

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
| 보류 처리 중 모집글 | 마감 배치 대상 아님. 보류 재확인(00초)이 `RECRUITING`으로 정리하면 다음 마감 배치(10초)가 마감. `HOLD_FAILED`는 그대로. 보류 결과를 모르는 동안은 반환하지 않음. 올린 지 1시간이 지나도 모르면 보류 무효화로 `HOLD_FAILED`(8.2, #536) | C5 |
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
- 위 예시는 같은 JVM 안에서 받을 때다. 앱이 나뉜 뒤 다른 앱은 11.3의 Kafka 토픽으로 받는다.

### 11.3 Kafka로 내보내기 (#393)

앱이 나뉘어 채팅이 다른 프로세스가 되었으므로, 요청 도착·지원 도착 두 이벤트를 Kafka로도 내보낸다. 발행 시점과 내용은 그대로이고 전송만 더했다. 토픽·key·헤더 규칙은 팀 공통 [`kafka.md`](../architecture/infrastructure/kafka.md)(#339, ADR 0001·0002)를 따른다.

| 항목 | 내용 |
|---|---|
| 방식 | Spring Modulith 이벤트 외부화(`spring-modulith-events-kafka`). 업무 트랜잭션 안에서 발행한 이벤트가 발행 기록(`event_publication`)에 남고, 커밋 뒤 비동기로 보낸다 |
| 대상 | shared 출연 이벤트 중 `@Externalized`가 붙은 것: `AppearanceRequestSentEvent`, `AppearanceApplicationSubmittedEvent`, `AppearanceReviewPublishedEvent`(#513, 21.6), `AppearanceChangedEvent`(#713, 28장). 출연 취소 요청 이벤트 3종은 받는 곳이 없어 붙이지 않았다(받는 쪽이 생기면 key `appearance:<출연 ID>`로 더한다). 판단은 `app.AppearanceExternalEvents` |
| 토픽 | `appearance.events.v1`(shared `AppearanceEventTopic.NAME`). 출연 이벤트 타입을 한 토픽에 섞는다. 출연 앱이 `NewTopic`으로 선언한다(partition 3개, 복제 1). 브로커 자동 생성은 꺼져 있다 |
| key | 이벤트가 다루는 대상 ID에 종류 접두어: 요청 도착 `appearance-request:<요청서 ID>`, 지원 도착 `appearance-application:<지원 ID>`, 리뷰 공개 `appearance-review-reviewee:<받은 회원 ID>`(받은 사람 단위로 모음, #513), 출연 변경 `appearance:<출연 ID>`(#713). shared 레코드의 `@Externalized("appearance.events.v1::#{…}")`로 정한다. 같은 key는 같은 partition으로 가지만, 커밋 뒤 비동기로 보내므로 순서는 보장하지 않는다 |
| 본문 | shared 이벤트 레코드를 그대로 JSON으로 싣는다(봉투 없음). 예: `{"request":{"requestId":1,"hostId":1,"guestId":2,"title":"…","fee":30000,"scheduledAt":"2026-11-05T17:54:44","acceptDeadline":"2026-10-31T17:54:44"}}`. 시각은 KST `LocalDateTime` |
| 헤더 | `eventType`(`AppearanceRequestSentEvent`·`AppearanceApplicationSubmittedEvent`·`AppearanceReviewPublishedEvent`·`AppearanceChangedEvent` 고정 값. 레코드 이름을 바꿔도 그대로), `eventVersion`(`1`). 값 직렬화기 `JacksonJsonSerializer`가 `__TypeId__`(레코드 전체 클래스 이름)를 붙인다. `eventId`·`occurredAt`은 두지 않는다 |
| 직렬화 | `appearance.yml`의 `spring.kafka.producer.value-serializer=JacksonJsonSerializer`. Modulith Kafka 모듈의 JSON 기본 세트(값 byte[] + 바이트 JSON 변환기)는 끈다(`spring.modulith.events.kafka.enable-json=false`, #394). 회원 앱 발행 설계와 같은 방식이고, 켜 두면 회원 이벤트 수신(4.1)이 실패한다 |
| 실패 | 보내기에 실패하면 발행 기록이 미완료로 남는다. 재발행 배치(매분 05초 `AppearanceEventRepublishUseCase`)가 발행한 지 1분(`pending-grace`)이 지난 미완료 기록을 오래된 것부터 한 번에 최대 100건(`batch-size`) 다시 보내고, 서버가 재시작될 때도 다시 보낸다(`republish-outstanding-events-on-restart`). 응답만 잃었거나 서버 여러 대가 같이 다시 보내면 같은 메시지가 두 번 갈 수 있다(최소 한 번 전달). 받는 쪽은 `(eventType, 대상 ID)`로 중복을 거른다 |
| 연결 | 브로커 주소는 `SPRING_KAFKA_BOOTSTRAP_SERVERS`(k8s 앱 설정에 이미 있음). 로컬(`local` 프로필)은 compose의 Kafka(`localhost:9092`)로 보낸다. 외부화를 끄면 토픽도 선언하지 않아 Kafka에 연결하지 않는다. 출연 테스트는 외부화를 끄고(`hapbang-module-test.yaml`), `AppearanceEventKafkaTest`만 Testcontainers Kafka로 켠다 |
| 받는 쪽 | 채팅 소비 그룹 `chat-app.appearance-notification`(요청·지원 알림), 회원 소비 그룹 `member-app.appearance-review`(리뷰 공개, #555), AI 추천(출연 변경·리뷰 공개, #713, 그룹은 AI 이슈) (kafka.md 토픽 목록). 각 소비자는 모르는 타입을 기본 처리기에서 무시한다 |

- 같은 버전 안에서는 선택 필드 추가만 한다. 깨지는 변경은 같은 토픽에 새 레코드 타입으로 더한다(kafka.md 이벤트 버전).
- 내보낼 이벤트를 더하려면 shared 레코드에 `@Externalized`를 붙이고 `out/event/AppearanceEventExternalizationConfig`에 `eventType` 헤더를 더한다. 받는 앱을 먼저 배포한다(ADR 0001).
- 같은 JVM 안의 리스너(11.2)는 그대로 받는다. Kafka는 다른 앱으로 보내는 통로다.

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
- 출연 상세에는 리뷰 정보(`reviewable`·`myReview`·`receivedReview`)도 담는다 (#514, 22.1).
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
- 제약 `ck_hp_tasks_penalty`: `PENALTY`면 받는 회원·출연 ID·위약금(1 이상, 기준액 이하) 필수, 그 밖은 비움. (#282에서 `ck_hp_tasks_receiver`로 바뀜, 14.3)
- 마이그레이션: `V202610151702__add_appearance_host_cancel_columns.sql` (`hp_tasks.receiver_id`·`payout_amount`, `appearances.host_cancel_reason`, `recruits.cancel_reason`)

## 14. 출연 확인 (#282)

### 14.1 API·배치

| 메서드 | 경로 | 설명 | 권한 | 성공 메시지 |
|---|---|---|---|---|
| POST | `/api/v1/appearances/{appearanceId}/confirm` | 출연 확인 → 게스트에게 출연료 지급 | 출연의 호스트 | 출연을 확인했습니다. |
| POST | `/api/v1/appearances/{appearanceId}/absent` | 불참 → 호스트에게 출연료 반환. 본문(선택) `{"reason": "…(500자 이하)"}` | 출연의 호스트 | 불참으로 처리했습니다. |
| 배치 | 매분 50초 `AppearanceAutoConfirmUseCase.confirmDue()` | 확인 기간이 끝난 성립 출연 자동 확인 + 지급 | 시스템 | — |

- 응답은 출연 상세. 추가 필드: `confirmType`(`HOST`/`AUTO`), `confirmedAt`, `absentAt`, `absentReason`, `hpPayoutStatus`(지급 진행 상태). 불참의 반환 진행 상태는 기존 `hpReleaseStatus`.
- 이름: AGENTS.md의 "노쇼(부정 응답)"를 코드·API에서는 **불참(`ABSENT`)**으로 쓴다 (APR-35 E1).

### 14.2 규칙

| 항목 | 규칙 | 근거 |
|---|---|---|
| 응답 기간 | 방송 예정 일시 ≤ 지금 < 방송 예정 일시 + `confirm-period`(7일). 전이면 409 `APPEARANCE_CONFIRM_NOT_OPEN`, 지나면 409 `APPEARANCE_CONFIRM_PERIOD_ENDED` | AGENTS.md, APR-05 |
| 응답할 수 있는 상태 | `ESTABLISHED`만. 이미 응답함·취소 요청 처리 중·취소됨은 409 `APPEARANCE_NOT_CONFIRMABLE`(`detail`로 이유 구분) | E3 |
| 번복 | 불가. 확인·불참 중 한 번만 (응답 즉시 돈이 움직임) | E3 |
| 응답하는 사람 | 출연의 호스트만(403 `NOT_APPEARANCE_HOST`, `detail` "출연의 호스트만 출연을 확인할 수 있습니다."). 다른 회원은 404 | |
| 회원 자격 | **보지 않는다.** 정지된 호스트도 응답할 수 있고 정지된 게스트에게도 지급한다 (거래 마무리). 응답도 자격 확인 없이 출연 상세를 돌려준다. 역할 검사(`@PreAuthorize`)는 그대로 | E5 |
| 불참 사유 | 선택 500자 | E2 |
| 자동 확인 | 매분 50초. `ESTABLISHED`이고 방송 예정 일시 + 7일 ≤ 지금인 출연 → `CONFIRMED`(`AUTO`) + 지급. 출연마다 잠금 뒤 다시 확인해 그사이 호스트가 응답했으면 건너뜀 | AGENTS.md |
| 취소 요청 처리 중 | 방송 예정 일시에 자동 거절 배치(30초)가 `ESTABLISHED`로 되돌린 뒤 응답·자동 확인 대상이 된다 (그 전 최대 1분은 409) | #231 |
| 잠금 | 출연 행만 배타 잠금. 모집글은 잠그지 않는다: 방송 예정 일시가 지나면 모집글을 바꾸는 처리(지원 수락·마감·합의 취소·모집 전체 취소)가 모두 막혀 있다 | |
| 지급·반환 실행 | 상태 변경 + HP 작업 기록을 한 트랜잭션에 커밋한 뒤 1회 실행. 실패하면 작업만 재시도·격리, 응답은 성공 | APR-22 |
| 이의 제기 | 출연 관할 아님. 불참 게스트 패널티는 고객센터 문의로 접수 (출연은 API·기록 없음, `disputed_at` 컬럼 삭제) | E4 |
| 알림 | 이벤트를 발행하지 않는다 | E6 |

### 14.3 HP 작업 (지급)

| 컬럼 | 지급(`PAYOUT`, 확인) | 반환(`RELEASE`, 불참) |
|---|---|---|
| `member_id` | 보류한 호스트 (지급하는 회원) | 반환받는 호스트 |
| `amount` | 출연료 | 출연료 |
| `receiver_id`, `payout_amount` | 게스트, 출연료 (= `amount`) | 비움 |
| `appearance_id` | 출연 ID (Billing 지급 원천 ID) | 비움 |
| 사유 | `APPEARANCE_CONFIRMED` | `APPEARANCE_ABSENT` |
| 멱등 키 (= Billing 요청 ID) | `{APPEARANCE_REQUEST·APPEARANCE_RECRUIT}-{보류 ID}-PAYOUT-APPEARANCE-{출연 ID}` | `…-RELEASE-APPEARANCE-{출연 ID}` (합의 취소와 같은 형식, 출연 하나는 하나로만 끝남) |

- Billing 요청: `PayoutCommand.partial(호스트, 보류 종류, 보류 ID, 지급 요청 ID, 게스트, 원천 종류, 출연 ID, 출연료)`. 원천 종류는 요청서 출처 `APPEARANCE_REQUEST`, 모집 출처 `RECRUIT_APPEARANCE`. 모집글 보류에는 다른 출연의 몫이 함께 있어 금액을 정해 지급한다.
- 제약 `ck_hp_tasks_receiver` (`ck_hp_tasks_penalty` 대체): `RELEASE`는 받는 회원·지급액 비움, `PENALTY`는 받는 회원·출연 ID 필수·위약금 1 이상 기준액 이하, `PAYOUT`은 받는 회원·출연 ID 필수·`payout_amount = amount`.
- 출연 업무 규칙은 출연 모듈 테스트(Billing 대역)로, 실제 지급·반환은 apps `AppearanceBillingFlowIntegrationTest`(실제 Billing)로 확인한다 (#289, 15장).
- 마이그레이션 `V202610161050__add_appearance_confirm_columns.sql`: `no_show_at` → `absent_at`, `absent_reason` 추가, `disputed_at` 삭제, 인덱스 `(status, scheduled_at)`, 제약 교체.

## 15. Billing 실제 연동 (#289)

### 15.1 바뀐 것

| 항목 | 전 | 후 |
|---|---|---|
| 출연이 부르는 Billing | `appearance.billing.mode` 기본값 `fake` → 출연 모듈 안의 메모리 구현 `FakeBillingClient`. 배포 서버에서도 출연 HP가 Billing 지갑에 기록되지 않음 | 항상 `BillingPortClient` → shared `BillingPort` → Billing 모듈 (실제 지갑·원장) |
| Fake Billing | `FakeBillingClient`·`FakeBillingErrorCode`·`FakeBillingException`·`BillingUnavailableException` (main) | 삭제 |
| 설정 | `appearance.billing.mode` (`fake`/`billing-port`) | 삭제 (구현이 하나뿐) |
| 출연 모듈 테스트 | Fake로 잔액·보류까지 확인 | `@AppearanceIntegrationTest`·`@AppearanceE2ETest`(실제 포트, #390)가 `BillingClient`를 Mockito 대역으로 바꿈. 업무 규칙과 **Billing에 보낸 요청 내용**(금액·요청 ID·원천)만 확인 (`support.BillingCalls`, 실패는 `support.BillingStubs`) |
| 실제 Billing 확인 | Billing 담당 `AppearanceBillingPortIntegrationTest`(보류·거절 반환 일부) | 출연 `AppearanceBillingFlowIntegrationTest`(apps)가 출연 API 전체 흐름을 실제 Billing으로 확인 (16장 test-spec) |

- 출연은 현금을 다루지 않는다(HP만). 현금은 Billing 충전(Toss 테스트 결제)으로만 들어온다. 실제 Billing에 연결해도 실제 현금은 오가지 않는다 (APR-36).
- 서버의 기존 출연 데이터(Fake 보류만 있던 건)는 조치하지 않는다 (H5). 그런 건을 반환·지급하면 Billing이 거절해 HP 작업이 `DEAD`가 된다.

### 15.2 Billing 담당 파일에서 바꾼 것 (Billing 담당과 합의, APR-36 H3)

| 파일 (만든 사람·커밋) | 바꾼 것 | 이유 |
|---|---|---|
| `appearance/out/billing/BillingPortClient` (Billing 담당, #182 `2b66849`) | `@ConditionalOnProperty(mode=billing-port)` 제거, 문서 주석 갱신. 위임 코드는 그대로 | 구현이 하나뿐이라 켜고 끄는 조건이 필요 없음 |
| `appearance/.../out/billing/BillingClientModeTest` (Billing 담당, #182) | 삭제 | `mode` 값에 따라 Fake·실제 구현을 고르는지 보는 테스트라 대상이 없어짐 |
| apps `AppearanceBillingPortIntegrationTest` (Billing 담당, #182) | `properties = "appearance.billing.mode=billing-port"` 제거, "Fake 빈이 없다" 확인 줄·`FakeBillingClient` import·쓰지 않게 된 `ApplicationContext` 제거, 첫 테스트 이름을 `출연_BillingClient로_BillingPortClient가_주입된다`로, 클래스 주석 갱신, `@DirtiesContext` 추가. 나머지 테스트는 그대로 | `FakeBillingClient` 클래스가 없어 컴파일이 깨지고, `mode` 설정이 없어짐. `@DirtiesContext`: `mode` 속성이 빠지며 `SecurityCookieTest`와 같은 컨텍스트를 쓰게 됐는데, `csrf()` 테스트 도구가 CSRF 저장소를 세션 방식으로 바꿔 끼워 `SecurityCookieTest`가 실패했다 (PR CI) |

## 16. 정리 (#293)

동작(응답·오류 코드·HP 처리)은 바꾸지 않은 정리다 (ISSUE.md APR-37).

| 항목 | 내용 |
|---|---|
| 작업 뒤 상세 조회 | 조회 UseCase의 상세 메서드(`AppearanceRequestQueryUseCase.getDetail`, `AppearanceRecruitQueryUseCase.getDetail`, `AppearanceQueryUseCase.getDetail`·`getDetailWithoutParticipantCheck`)가 `@Transactional(readOnly = true)`. Billing 호출 때문에 트랜잭션 없이 도는 Facade 메서드 10곳(요청서 보내기·거절·취소, 모집글 올리기·조기 마감·전체 취소, 취소 요청 수락, 일방 취소, 출연 확인, 불참)도 상세를 한 시점의 값으로 읽는다. Facade 트랜잭션 안이면 합류한다. "트랜잭션은 Facade가 연다" 규칙의 예외 |
| 동시 처리 충돌 → 409 | 낙관적 락 충돌을 `CONCURRENT_UPDATE`로 바꾸는 곳을 Support의 `flushOrConflict()`로 통일. 요청서 Support에 추가하고 모집글 수정·요청서 수정·수락·반환 기록 4곳의 try/catch를 없앴다 |
| 그대로 둔 것 | `HpTaskExecuteUseCase`의 결과 기록 충돌은 409가 아니라 "다른 실행이 먼저 기록함" 로그만 (R2) |
| 회원 이벤트 순서 | `AppearanceUserEventListener`의 `TODO(event)` 삭제. 공통 `ReplicaUser`가 회원 버전으로 옛 정보를 무시 (회원 #292) |

## 17. 시나리오 전수 확인 (#297)

운영 동작은 바꾸지 않았다 (ISSUE.md APR-38). 운영 코드는 `AppearanceConfig`의 Clock 설명 주석만 고쳤다.

| 항목 | 내용 |
|---|---|
| 테스트 시계 | `@AppearanceIntegrationTest`·`@AppearanceE2ETest`가 `AppearanceTestClock`을 끼운다: 운영 Clock 빈은 그대로 두고 같은 한정자(`appearanceClock`) + `@Primary`로 우선 주입. 실제 시각으로 흐르고, 앞으로만 돌릴 수 있고(`advance`·`advanceTo`), 테스트마다 처음으로 돌아간다(JUnit 확장). 빈 덮어쓰기 설정은 쓰지 않는다 |
| 시각 만들기 | 테스트는 DB의 시각 값(`scheduled_at`·`recruit_deadline`·`created_at`·`next_attempt_at`)을 고치지 않는다. 실제 순서대로 시계를 돌린다: 모집 마감(기한 마감 배치) → 방송 → 확인 기간 끝, 실패한 작업의 다음 시도 시각, 배치 대기 시간(1분). 남은 SQL 변경은 낙관적 락 충돌을 만드는 버전 올리기뿐 |
| 그대로 둔 것 | apps `AppearanceBillingFlowIntegrationTest`는 아직 방송 시각을 SQL로 옮긴다 (테스트 시계는 출연 모듈 테스트 코드라 apps에서 바로 쓸 수 없음) |
| 시나리오 표 | 저장소 밖 `hapbang_be/SCENARIOS.md` (1~10장 179개 시나리오 + 정책·제한 21개, 오류 코드 45개의 HTTP 확인 위치) |

## 18. 출연 수익 내부 조회 (#301)

Billing #295 "지급 예정 요약"이 게스트의 출연료·위약금과 지급 상태를 가져가는 **내부 전용** 조회다 (ISSUE.md APR-39). 읽기 전용이고, 출연 확인·취소·위약금·지급 로직은 바꾸지 않는다.

### 18.1 연동 방식

강의 내부 API(#300)와 같은 공통 규칙을 따른다 (docs/api/internal-earnings.md 1장, 출연 절은 6·7장). 출연만 다른 점은 표 아래에 모았다.

| 항목 | 내용 |
|---|---|
| 호출 | Billing이 shared `AppearanceApiClient`(18.6)로 부른다. 클러스터 안 Service 주소의 내부 포트(`http://appearance-app:8081`, billing 설정 `APPEARANCE_API_BASE_URL`)로 부르고 Gateway를 거치지 않는다 |
| 경로 | `GET /api/v1/internal/appearances/earnings/summary`, `GET /api/v1/internal/appearances/earnings` (공통 규칙 `/api/v1/internal/<제공 모듈>/...`) |
| 인증 | 헤더 `X-Internal-Api-Key`(`shared.internal.InternalApiHeaders`), 설정 `hapbang.internal-api.key`(환경변수 `HAPBANG_INTERNAL_API_KEY`). 맞으면 `ROLE_INTERNAL`, 틀리거나 없으면 본문 없는 401. 회원 로그인(사용자 헤더)으로는 부를 수 없고, 반대로 내부 키로 회원 API를 부를 수 없다. 필터·전용 보안 체인은 global(#300·#307) |
| 외부 노출 | Gateway에 `/api/v1/internal` route가 없고, 외부 포트(8080)로 오면 `InternalApiPortValve`가 404로 막는다(내부 포트 8081만, #372). Swagger에는 나온다(Spec `ApiV1AppearanceEarningInternalSpec`, 키 헤더 파라미터 표시). 운영 Swagger에도 나오지만 외부에서 호출하면 404다(#498) |
| 출연 쪽 방어 | ① 보안 체인의 키 검증(공통부) ② `AppearanceEarningFacade`의 `@PreAuthorize("hasRole('INTERNAL')")` ③ UseCase의 회원 ID 검증 |
| 회원 ID | 쿼리 파라미터 `userId` (게스트). 없거나 1 미만이면 400 `INVALID_REQUEST`. 키가 곧 권한이라 아무 회원이나 조회할 수 있으므로, 로그인 회원 본인인지는 Billing이 확인한다. 출연 쪽에 데이터가 없는 회원은 0원 요약·빈 목록 (회원 복제를 확인하지 않는다) |
| 응답 | 성공 `RsData`, 오류 Problem Detail + `code`(요청 값 오류는 `errors[{field, message}]`). 계약 타입은 shared `com.hapbang.shared.appearance`(`domain.AppearanceEarningStatus`·`AppearanceEarningType`, `dto.AppearanceEarning*Dto`). 값이 없을 수 있는 필드(`payoutScheduledAt`)는 계약대로 응답 JSON에서 빠진다(계약 DTO의 그 필드에만 `@JsonInclude(NON_NULL)`, #408. 출연 앱 전체 설정은 아니라 프론트용 API 응답은 그대로). 받는 쪽은 빠진 필드와 `null`을 같게 읽는다 |
| 시각 | `LocalDateTime`(**offset 없는** ISO 8601), 모두 Asia/Seoul (Billing 합의 확정, 강의 계약 뒤에도 유지). 작업 완료·확인·취소·불참 시각은 출연 Clock(Asia/Seoul)으로 기록하고, 출연 수정 시각(`updated_at`)은 JPA Auditing이 JVM 기본 시간대로 기록하므로 앱 컨테이너의 `TZ: Asia/Seoul`(compose dev·prod, CI)에 기댄다 |

**강의 계약과 다른 점** (#301 PR에 공유)

| 항목 | 강의 #300 | 출연 | 이유 |
|---|---|---|---|
| 시각 | `OffsetDateTime`(`+09:00`) | `LocalDateTime`(KST, offset 없음) | Billing과 먼저 합의(APR-39 N1). 기존 출연 API·DB 규칙·FE 해석과 같다. Billing이 합칠 때 출연 시각을 KST로 읽으면 된다 |
| 요약 `asOf` | 있음(분류 기준 시각) | 없음 | 출연 분류는 저장된 상태만 보고 조회 시각에 기대지 않는다. 강의 문서도 "무시해도 된다"는 추가 필드 |
| `earningType` 필터 | 없음(종류가 하나) | 있음(선택) | Billing 요청. 출연은 출연료·위약금 두 종류 |
| `SCHEDULED` | 쓴다 | 값만 있고 나오지 않는다 | 18.2 |

### 18.2 수익 분류 (`domain.AppearanceEarningRule`)

기준 회원은 게스트(수익을 받는 쪽)다. **출연 1건 = 수익 1건**이고, 지급·위약금 HP 작업(`PAYOUT`·`PENALTY`, 출연마다 많아야 1건)의 상태로 지급 상태를 정한다. 반환(`RELEASE`)은 호스트에게 돌아가는 돈이라 섞지 않는다.

| 출연 상태 | 작업 | 지급 상태 | 종류 | 금액 | 미지급 합계 |
|---|---|---|---|---|---|
| `ESTABLISHED` | - | `CONFIRMATION_PENDING` | 출연료 | `fee` | 포함 |
| `CANCEL_REQUESTED` | - | `ON_HOLD` | 출연료 | `fee` | 포함 |
| `CONFIRMED` | `PENDING` / `DEAD` / `DONE` | `PROCESSING` / `FAILED` / `COMPLETED` | 출연료 | `fee` | `COMPLETED`만 제외 |
| `CANCELED_BY_HOST` | `PENDING` / `DEAD` / `DONE` | `PROCESSING` / `FAILED` / `COMPLETED` | 위약금 | `penalty_amount` | `COMPLETED`만 제외 |
| `ABSENT`, `CANCELED_BY_AGREEMENT` | - | `CANCELED` | 출연료 | `fee` | 제외 |

- 공통 상태 중 `SCHEDULED`는 출연에 없다 (요약에서 항상 0). 확인하는 순간 지급을 실행해 "지급 시각은 정해졌는데 실행 전"인 단계가 없기 때문이다 (강의의 D+30 지급 예정과 다름).
- 일방 취소된 출연은 위약금 한 건으로만 센다 (출연료와 중복 집계 없음). 모집은 성립한 게스트마다 한 건이고, 모집글 보류 전체나 미선정 지원은 세지 않는다.
- 확인·일방 취소는 상태 변경과 지급·위약금 작업 기록을 한 트랜잭션에서 하므로 작업이 없을 수 없다. DB 제약이 이 짝을 보장하지 않으므로, 작업이 없으면 **`FAILED`로 보여 주고 `[EARNING_TASK_MISSING]` ERROR 로그**를 남긴다 (N2).
- 요약과 목록은 같은 규칙을 쓴다. 목록 조회 조건은 규칙을 모든 조합에 적용해 만들어(`conditionOf`) 분류와 어긋나지 않는다.

### 18.3 요약 (`summary`)

- 파라미터: `userId`(필수), `earningType`(선택, `APPEARANCE_FEE`·`APPEARANCE_PENALTY`)
- 응답: 미지급 5개 버킷 `confirmationPending`·`scheduled`(항상 0)·`onHold`·`processing`·`failed`, 각각 `amountHp`(HP)·`count`. 페이지와 상관없이 전체 기준. `COMPLETED`·`CANCELED`는 들어가지 않는다. `asOf`는 없다
- 쿼리: (출연 상태, 작업 상태)별 건수·출연료 합계·위약금 합계를 한 번에 읽고 Java에서 분류해 접는다
- 오류: `userId` 없음·1 미만, 없는 `earningType` → 400 `INVALID_REQUEST`

### 18.4 목록

- 파라미터: `userId`(필수), `status`(여러 번, 공통 7종), `earningType`(선택), `page`(0부터)·`size`(기본 20, 최대 100, 팀 공통)
- `status`를 보내지 않거나 비우면 미지급 상태만(요약과 같은 범위, 강의와 같다). `COMPLETED`·`CANCELED`는 고를 때만 나온다
- 출연에 없는 상태(`SCHEDULED`)만 고르면 400이 아니라 빈 결과 (N4). 계약에 없는 문자열(`PAID`, 다른 모듈 종류 `LECTURE_SALE` 등)은 400 `INVALID_REQUEST`(`errors[0].field` = `status`·`earningType`). Billing은 타입이 있는 Client로 부르므로 다른 모듈의 값을 보낼 수 없다
- 정렬: `statusUpdatedAt` 내림차순 → `sourceId`(출연 ID) 내림차순 고정. 요청의 `sort`는 무시한다(페이지 번호·크기만 쓴다). 출연 1건 = 1행이라 순서가 정해지고, 같은 시각이 페이지 경계에 걸려도 중복·누락이 없다. 강의 목록과 같은 키라 Billing이 두 목록을 같은 기준으로 합칠 수 있다
- 응답 `data`: `{content, page: {size, number, totalElements, totalPages}}`

| 필드 | 값 |
|---|---|
| `sourceType` | 항상 `APPEARANCE` (강의는 `LECTURE`) |
| `sourceId` | 출연 ID. Billing 지급·위약금 기록의 원천 ID와 같다. Billing 기록의 원천 종류는 요청 출처 `APPEARANCE_REQUEST`·모집 출처 `RECRUIT_APPEARANCE`로 나뉘지만 둘 다 출연 ID라 `sourceId` 하나로 맞춰 볼 수 있다 |
| `earningType` | `APPEARANCE_FEE` / `APPEARANCE_PENALTY` |
| `title` | 출처 요청서·모집글의 현재 제목 (내 출연 목록과 같음) |
| `amountHp` | 출연료, 위약금이면 위약금 |
| `status` | 18.2 지급 상태 |
| `payoutScheduledAt` | 항상 `null`이라 응답에서 빠진다 (호스트가 방송 뒤 7일 안에 언제든 확인할 수 있어 지급 시각이 정해지지 않음) |
| `statusUpdatedAt` | `coalesce(작업 완료 시각, 확인 시각, 취소 시각, 불참 시각, 출연 수정 시각)`: 완료·실패는 작업 완료 시각(`DONE`·`DEAD` 모두 기록), 처리 중은 확인·일방 취소 시각, 취소는 불참·합의 취소 시각, 확인 대기·보류는 출연 수정 시각 |

### 18.5 인덱스

`V202610161849__add_hp_tasks_appearance_id_index.sql`: `hp_tasks (appearance_id)`. 출연에 지급·위약금 작업을 붙여 읽는 조인용 (기존에는 `(ref_type, ref_id)`만 있었음).

### 18.6 Client (`shared.appearance.out.AppearanceApiClient`)

Billing은 URI·헤더를 직접 만들지 않고 이 Client를 주입받는다 (강의 `LectureApiClient`와 같은 방식, D62).

```java
AppearanceEarningSummaryDto getEarningSummary(long guestUserId);
AppearanceEarningSummaryDto getEarningSummary(long guestUserId, AppearanceEarningType earningType);
AppearanceEarningPageDto getEarnings(long guestUserId, Collection<AppearanceEarningStatus> statuses, int page, int size);
AppearanceEarningPageDto getEarnings(long guestUserId, Collection<AppearanceEarningStatus> statuses,
                                     AppearanceEarningType earningType, int page, int size);
```

| 설정 | 기본값 | 설명 |
|---|---|---|
| `hapbang.clients.appearance.base-url` | `http://localhost:${server.port:8080}` (apps) | 출연 서비스 주소. **이 값이 있을 때만 Client 빈이 생긴다**(모듈 테스트에는 없다) |
| `hapbang.clients.appearance.connect-timeout` | 2s | 연결 제한 시간 |
| `hapbang.clients.appearance.read-timeout` | 5s | 응답 제한 시간 |
| `hapbang.internal-api.key` | (환경 변수) | Client가 헤더에 붙인다 |

- 실패는 `AppearanceApiCallException`이다(shared는 global에 의존하지 않아 `BusinessException`이 아니다). `REJECTED`: 4xx(요청 값 오류, 키 오류 401), `UNAVAILABLE`: 연결 실패·시간 초과·5xx·읽을 수 없는 성공 응답(깨진 JSON, 계약에 없는 enum 값, 필수 필드 없음, `data` 없음). `getStatus()`·`getCode()`는 강의와 같다. 조회라 `UNAVAILABLE`은 다시 불러도 안전하다.
- `statuses`가 `null`이거나 비어 있으면 상태 조건 없이 보낸다(= 미지급만). `earningType`이 `null`이면 둘 다.

---

## 19. 격리된 HP 작업 관리자 조회·다시 시도 (#397)

격리된(`DEAD`) HP 작업은 업무 상태(거절·확인·취소)를 되돌리지 않는다. 관리자가 원인을 확인한 뒤 같은 작업을 같은 멱등 키로 다시 실행하는 것이 복구 경로다. 사용자에게는 다시 시도 기능이 없다.

### 19.1 API (관리자, `/api/v1/appearances/admin/**`)

| 메서드 | 경로 | 설명 |
|---|---|---|
| GET | `/api/v1/appearances/admin/hp-tasks?status=DEAD` | 상태별 작업 목록. `status` 생략 시 `DEAD`. 마지막으로 바뀐 작업부터, `page`·`size`(기본 20, 최대 100) |
| POST | `/api/v1/appearances/admin/hp-tasks/{taskId}/retry` | 격리 작업 다시 시도. 실행 뒤 작업을 200으로 돌려준다 |

- 응답 한 건(`HpTaskAdminResDto`): 작업 종류·대상(요청서·모집글, 출연 ID)·보류 회원·받는 회원·금액(위약금·지급액)·사유·멱등 키·상태·시도 횟수·다음 시도 시각·마지막 오류·완료(격리) 시각·생성 시각·다시 시도 기록 3개.
- 권한 (관리자 API 3중 방어): ① 보안 설정이 `/api/v1/*/admin/**`를 관리자만 통과시키고(그 밖 403, 비로그인 401) ② `HpTaskAdminFacade`의 `@PreAuthorize("hasRole('ADMIN')")`가 로그인 역할을 확인하고 ③ 복제한 회원 정보로 지금도 정상 상태의 관리자인지 확인한다(`AppearanceUserSupport.getAdmin`, 목록·다시 시도 모두). 헤더 역할은 토큰 발급 시점 값이라, 그 뒤 정지되면 403 `APPEARANCE_USER_NOT_ACTIVE`, 관리자 권한을 잃으면 403 `NOT_ADMIN`이다.

### 19.2 다시 시도 흐름

```
[트랜잭션] 관리자 자격 확인(정지 403 APPEARANCE_USER_NOT_ACTIVE / 강등 403 NOT_ADMIN) → 작업 조회(없으면 404 HP_TASK_NOT_FOUND) → DEAD 확인(아니면 409 HP_TASK_NOT_DEAD)
          → PENDING, 시도 횟수 0, 다음 시도 시각 = 지금, 완료 시각 비움, 다시 시도 기록(횟수+1·관리자·시각)
          → 바로 반영해 버전 충돌이면 409 CONCURRENT_UPDATE → 다시 시도 INFO 로그 (커밋)
[트랜잭션 밖] HpTaskExecuteUseCase.execute — 업무 처리 직후 실행과 같은 1회 실행
          → 성공 DONE / Billing 거절 DEAD / 결과 모름 PENDING(재시도 배치가 이어서)
[읽기 전용 트랜잭션] 실행 뒤 작업을 읽어 응답 (다시 실패해도 200, 결과는 status로 본다)
```

- 재시도 배치는 처음 시도 전 작업을 "시작한 지" 대기 시간(1분)이 지나야 가져간다. 시작 시각은 다시 시도한 작업이면 `last_manual_retry_at`, 아니면 `created_at`이다. 다시 시도하면 시도 횟수가 0이 되고 만든 시각은 오래전이라, 만든 시각만 보면 관리자 요청 안에서 실행 중인 작업을 배치가 함께 실행하기 때문이다. 실행 결과를 기록하지 못하고 끊기면 1분 뒤 배치가 이어받는다.
- 같은 작업이 두 곳에서 동시에 Billing에 가더라도 Billing이 지갑 잠금 뒤 같은 요청 ID를 확인해 처음 결과를 돌려주므로 HP는 한 번만 움직인다.

### 19.3 결정

| 결정 | 내용 | 이유 |
|---|---|---|
| 시도 횟수 | 다시 시도하면 0으로 되돌려 재시도 규칙(1분 → … → 최대 1시간, 10회)을 처음부터 적용 | 격리 작업은 이미 최대 횟수를 다 써서, 이어서 세면 한 번 실패하자마자 다시 격리된다. 일시 장애가 계속될 때 관리자가 여러 번 누르지 않아도 되게 한다 |
| 다시 시도 기록 | `hp_tasks`에 컬럼 3개(횟수, 마지막 관리자·시각) + 로그 | 관리자 목록 API에서 바로 보이고 감사 기록으로 남는다. 직전 시도 횟수·오류는 로그에 남긴다 |
| 배치 대기 기준 | 처음 시도 전 작업의 대기 시간을 `coalesce(last_manual_retry_at, created_at)`부터 센다 | 다시 시도한 작업을 관리자 요청 안의 실행과 배치가 함께 실행하지 않게 한다 (중복 이동은 멱등 키가 막지만 불필요한 중복 호출을 없앤다) |
| 마지막 오류 | 다시 시도해도 지우지 않고 다음 결과가 나올 때 바뀐다 (성공하면 지움) | 다시 시도 중에도 원인을 볼 수 있게 |
| 멱등 키 | 바꾸지 않는다 | 같은 요청 ID라 Billing이 처음 결과를 돌려주므로 HP가 두 번 움직이지 않는다 |
| alert | 기존 `[HP_TASK_DEAD]` 오류 로그와 `appearance.hp_task.dead` 지표를 그대로 쓴다. 이 지표로 Grafana alert "HP 작업 DEAD"를 보낸다(#781, [구성한 alert](../architecture/infrastructure/observability.md#구성한-alert-781)) | 출연 모듈은 신호를 남기고, 수집·경보 도구는 팀 공통 구성을 따른다 |

## 20. Gateway 사용자 헤더 인증 (#395)

회원 앱이 JWT를 발급하고(#370) Gateway(Envoy)가 서명·만료를 검증한 뒤 claim을 헤더로 넘긴다(`docs/architecture/infrastructure/api-gateway.md`). 출연 앱은 그 헤더로 로그인 상태를 만든다. 공용 필터(#371)가 생기기 전이라 출연 앱 안에 둔다.

| 항목 | 내용 |
|---|---|
| 헤더 | `X-User-Id`(회원 ID, 양의 정수), `X-User-Role`(역할 하나, `Role` 이름 그대로: `USER`·`INFLUENCER`·`VERIFIED_INFLUENCER`·`ADMIN`). **정확히 이 값만 받는다**(`ROLE_` 접두사·소문자는 형식 오류) |
| 인증 객체 | `global.auth.GatewayUserHeaderFilter`가 `getName()` = 회원 ID, 권한 = `ROLE_{역할}` 하나로 만든다. `@LoginUserId`·`@PreAuthorize`·`/api/v1/*/admin/**` 규칙은 그대로 |
| 헤더 없음 | 익명. 로그인이 필요한 API는 401 (본문 없음) |
| 한쪽만 있거나 형식 오류 | 인증하지 않음(익명 → 401) + 경고 로그 `사용자 헤더 형식 오류로 인증하지 않음`. Gateway는 검증한 claim만 넘기므로 설정 오류 신호다 |
| 세션·CSRF | 세션을 만들거나 읽지 않는다(`STATELESS`, 요청 속성 저장소). 프론트가 `Authorization: Bearer`로 보내고 쿠키로 인증하지 않으므로 CSRF를 끈다 |
| 위조 방지 | 앱은 서명을 다시 검증하지 않는다. Gateway가 외부 요청의 `X-User-*`를 먼저 지우고, NetworkPolicy가 외부 API 포트(8080)에 Gateway만 들어오게 한다(`k8s/base/networkpolicy.yaml`) |
| 역할 재확인 | 토큰의 역할은 발급 시점 값(최대 15분). 정지·강등은 복제한 회원 정보로 다시 막는다(`validateCanAppear`·`validateIsAdmin`, 그대로) |
| 내부 API | `/api/v1/internal/**`은 따로 있는 체인이 `X-Internal-Api-Key`로만 인증한다. 사용자 헤더는 읽지 않는다 |

- 테스트: MockMvc는 `support.GatewayLogin.as(회원 ID, 역할)`, E2E는 `E2ELogin.as(…)`가 같은 헤더를 싣는다. 팀 공통 `TestLogin`(OAuth2 로그인 흉내)은 출연 테스트에서 쓰지 않는다.
- ⚠️ 형식 계약: JWT `role` claim 값이 `Role` 이름 그대로라는 것은 아직 회원(#370)과 확정하지 않았다(인프라 문서는 "문자열 하나"까지만 정함). 다르면 운영 출연 API가 모두 401이 되므로 #370 발급 형식과 맞춘다.
- 배포 순서: 이 변경이 운영(main)에 나가면 세션 로그인이 없어지므로, 회원 JWT 발급(#370)이 운영에 나간 뒤 함께 또는 그 뒤에 반영한다.
- 남은 일: 회원 JWT 발급(#370)이 끝나야 운영에서 헤더가 온다. 공용 필터(#371)가 생기면 이 필터를 그것으로 바꾼다. 프론트는 `X-XSRF-TOKEN` 대신 `Authorization: Bearer`를 보낸다(#370과 함께).

---

## 21. 출연 리뷰 작성과 블라인드 공개 (#512)

출연 확인(`CONFIRMED`)이 끝난 출연에서 호스트와 게스트가 서로 리뷰·별점을 남긴다. 리뷰 원본은 출연이 갖는다.
회원 평점 계산은 회원 모듈이 출연의 공개 이벤트를 받아 한다(이벤트는 #513, 조회 API는 #514, 22장).

### 21.1 정책

| 항목 | 내용 |
|---|---|
| 작성 조건 | 출연이 `CONFIRMED`(호스트 확인·자동 확인 모두)일 때만. 성립·취소 요청 중·불참·취소는 쓸 수 없다 |
| 작성자 | 그 출연의 호스트·게스트만, 방향별 1개(출연당 최대 2개) |
| 작성 기한 | `appearance.review.write-period`. 비면 무기한(지금 값). 값이 있으면 출연 확인 시각 + 기간 전까지 |
| 회원 자격 | 작성자는 출연 거래와 같은 자격(정상 상태, 인플루언서 이상). 출연은 인플루언서끼리만 해서 일반 유저는 쓸 수 없다. 상대는 정상(`ACTIVE`) 상태만 본다 |
| 내용 | 별점 1~5 정수(필수). 본문 선택, 앞뒤 공백을 지우고 1,000자 이하, 공백뿐이면 null |
| 수정·삭제 | 없다 (상호 평가라 고쳐 쓰지 못하게 함) |
| 공개 | 블라인드. 처음 쓴 리뷰는 `published_at`이 null이고, 상대가 쓰는 트랜잭션에서 두 리뷰의 `published_at`을 같은 시각으로 채운다 |
| 로그인 역할 | `@PreAuthorize` `INFLUENCER`·`VERIFIED_INFLUENCER` (일반 유저·관리자는 공통 `FORBIDDEN`). 토큰 발급 뒤 강등은 복제한 회원 정보로 다시 막는다 |

### 21.2 API

`POST /api/v1/appearances/{appearanceId}/reviews` `{rating, content}` → 201 `RsData` `{id}`. 응답에 공개 여부는 담지 않는다(상대가 썼는지 알게 되면 블라인드가 깨진다).

확인 순서 (앞 단계 오류가 먼저 난다):

1. 출연이 없거나 그 출연의 호스트·게스트가 아니면 404 `APPEARANCE_NOT_FOUND` (다른 출연 API처럼 다른 회원의 출연은 있다는 사실도 알리지 않는다)
2. 작성자의 출연 회원 정보가 없으면 404 `APPEARANCE_USER_NOT_FOUND`(가입 이벤트 미반영), 정지·탈퇴면 403 `APPEARANCE_USER_NOT_ACTIVE`, 일반 유저로 강등됐으면 403 `NOT_INFLUENCER`
3. 출연이 `CONFIRMED`가 아니면 409 `APPEARANCE_NOT_REVIEWABLE`
4. 작성 기한이 지났으면 409 `REVIEW_PERIOD_ENDED`
5. 상대가 정지·탈퇴면(복제 회원 정보가 없어도) 409 `REVIEWEE_NOT_ACTIVE`
6. 이미 썼으면 409 `ALREADY_REVIEWED`

- 별점·본문 형식 오류는 400 `INVALID_REQUEST`(요청 DTO 검증, 도메인도 같은 규칙을 다시 확인).
- 별점은 정수다(강의 리뷰와 같음). JSON에 소수(`4.5`)가 오면 Spring의 JSON 변환(Jackson)이 정수 칸에 맞춰 소수점 아래를 버린다(`4`). 프론트는 정수로 보낸다.

### 21.3 구조

| 계층 | 클래스 | 역할 |
|---|---|---|
| in | `ApiV1AppearanceReviewController` / `ApiV1AppearanceReviewSpec`, `WriteAppearanceReviewReqDto` | 매핑·Swagger·요청 검증 |
| app | `AppearanceReviewFacade` | 역할 검사, 트랜잭션 |
| app | `AppearanceReviewWriteUseCase` | 저장, 블라인드 공개 |
| app | `AppearanceReviewEligibility` | 위 확인 순서. 출연 상세의 `reviewable`도 같은 확인을 쓴다(#514) |
| app | `AppearanceReviewSupport` | 리뷰 저장소 접근 |
| domain | `AppearanceReview` | 별점·본문 규칙, 받는 사람·역할, 공개 시각 |
| domain | `Appearance.validateViewableBy`·`validateReviewable`·`counterpartOf` | 당사자(기존), 확인된 출연·작성 기한, 상대 |
| domain / config | `AppearanceReviewPolicy` / `AppearanceReviewProperties` | 작성 기한 (`appearance.review.write-period`) |

### 21.4 동시 작성

- 작성 때 출연 행을 배타 잠금(`SELECT … FOR UPDATE`, `AppearanceSupport.getForUpdate`)으로 읽는다. 호스트·게스트가 동시에 써도 차례로 처리돼, 나중 작성이 먼저 저장된 리뷰를 보고 둘 다 공개한다.
- 같은 회원이 동시에 쓰면 나중 요청은 잠금이 풀린 뒤 `ALREADY_REVIEWED`다. 유니크 제약 `uk_appearance_reviews_appearance_reviewer` 위반도 같은 오류로 바꾼다(최종 방어).
- 출연 행은 바꾸지 않아 출연 버전은 오르지 않는다.

### 21.5 `appearances.reviews` 스키마

| 컬럼 | 타입 | 설명 |
|---|---|---|
| `id` | BIGINT, PK, identity | |
| `appearance_id` | BIGINT, FK → `appearances.appearances` | 출연 |
| `reviewer_id` / `reviewee_id` | BIGINT | 쓴 사람 / 받은 사람 (회원 ID, FK 없음) |
| `reviewee_role` | VARCHAR(255) | 받은 사람의 그 출연 역할 `HOST`·`GUEST` (`AppearanceRole`) |
| `rating` | INTEGER | 1~5 (`ck_appearance_reviews_rating`) |
| `content` | VARCHAR(1000), null 허용 | 본문 |
| `published_at` | TIMESTAMP(6), null 허용 | 공개 시각. 공개 전 null |
| `version` | BIGINT | 낙관적 락 |
| `created_at`, `updated_at` | TIMESTAMP(6) | JPA Auditing |

- 유니크 `uk_appearance_reviews_appearance_reviewer (appearance_id, reviewer_id)`, 체크 `ck_appearance_reviews_reviewer_reviewee (reviewer_id <> reviewee_id)`
- 인덱스 `idx_appearance_reviews_reviewee_id_published_at (reviewee_id, published_at)`: 회원이 받은 공개 리뷰 조회(#514)
- 마이그레이션: `V202611021050__create_appearance_review_table.sql`

### 21.6 공개 이벤트 (#513)

리뷰가 공개되면 회원 모듈이 받은 사람의 평점을 계산할 수 있도록 `AppearanceReviewPublishedEvent`를 `appearance.events.v1`로 발행한다. 전송 방식(발행 기록, 커밋 뒤 Kafka, 재발행 배치)은 11.3과 같다.

| 항목 | 내용 |
|---|---|
| 시점 | 리뷰가 공개되는 트랜잭션. 공개된 리뷰 하나에 한 번 발행하므로 블라인드 공개면 2개가 나간다. 한쪽만 쓴 동안·작성 실패·롤백이면 나가지 않는다 |
| 본문 | `reviewId`, `appearanceId`, `reviewerUserId`, `revieweeUserId`, `revieweeRole`(shared `AppearanceParticipantRole` `HOST`·`GUEST`), `rating`(1~5 정수), `publishedAt`(KST, DB와 같은 마이크로초). 리뷰 본문은 담지 않는다 |
| key | `appearance-review-reviewee:<revieweeUserId>`. 같은 사람이 받은 리뷰가 같은 partition으로 간다(순서는 보장하지 않음, 11.3) |
| 헤더 | `eventType` `AppearanceReviewPublishedEvent`, `eventVersion` `1` |
| 중복 | 최소 한 번 전달이라 받는 쪽(회원)은 `reviewId`로 중복을 거른다. 평점은 별점 합계·개수로 계산한다(별점이 정수여도 평균은 소수) |
| 보존 | 토픽 보존은 72시간이다. 회원 소비자(#555)는 처음 붙을 때 토픽 맨 앞부터 읽었고, 초기 적재는 하지 않았다(23.1) |

- 배포 메모: 같은 토픽을 받는 채팅 앱은 새 타입의 클래스(shared)가 있으면 기본 처리기에서 무시하고, 없으면 역직렬화에 실패해 DLT로 보낸다(ADR 0001). dev 병합 뒤 배포에서는 shared 변경으로 모든 앱이 다시 배포되지만, 앱이 출연 → 강의 → 채팅 순서로 올라가므로 그사이 공개된 리뷰 이벤트는 옛 채팅 앱에서 DLT로 갈 수 있다(알림·데이터 영향 없음, 채팅이 올라가면 멈춤). 중간 앱 배포가 실패해 채팅이 옛 버전으로 남아도 같다.

### 21.7 작성 기한 만료 공개 배치 (#515)

작성 기한을 켜면 기한이 지난 뒤에는 상대가 더 쓸 수 없다. 그래서 한쪽만 쓴 리뷰가 영영 비공개로 남지 않도록 배치가 공개한다.

| 항목 | 내용 |
|---|---|
| 켜고 끄기 | 작성 기한 설정 `appearance.review.write-period` 하나로 정한다. 비어 있으면(지금, 무기한) 배치는 대상을 찾지도 않고 끝난다. 값을 넣으면 작성 제한과 이 배치가 함께 켜진다 |
| 주기 | 매분 15초 (`appearance.batch.review-expire-publish-cron`, 다른 배치와 겹치지 않는 초) |
| 대상 | 공개 전 리뷰가 있고 출연 확인 시각 + 작성 기한 ≤ 지금인 출연. 출연 확인이 오래된 순으로 한 번에 최대 `batch-size`(100)개 출연, 나머지는 다음 주기 |
| 처리 | 출연마다 [트랜잭션] 출연 배타 잠금 → 기한이 지났는지 다시 확인 → 공개 전 리뷰를 지금 시각으로 공개하고 리뷰마다 공개 이벤트(21.6) 1개 발행. 출연마다 예외를 잡아 다음 출연을 계속 처리 |
| 동시 작성 | 작성 API와 같이 출연 행을 먼저 잠근다. 작성과 겹쳐도 차례로 처리돼 리뷰마다 한 번만 공개된다. 배치가 먼저 공개한 리뷰는 상대가 나중에 써도(기한 직전 요청이 잠금을 기다린 경우) 공개 시각을 바꾸지 않고 이벤트를 다시 보내지 않는다(작성 쪽은 이번에 공개된 리뷰만 발행) |
| 인덱스 | 따로 두지 않았다. 기한을 켤 때만 돌고, 대상은 공개 전 리뷰라 많지 않다 |

- 기한을 실제로 켤 때 확인할 것
    - 프론트에 작성 기한 안내가 필요하다.
    - 대상 조회(`published_at is null` + 출연 `confirmed_at` 조인·정렬)가 매분 돈다. 켜기 전에 운영 데이터로 실행 계획을 보고, 느리면 공개 전 리뷰만 담는 부분 인덱스(`where published_at is null`)를 더한다.
    - 같은 출연이 매번 실패하면 오래된 순이라 계속 맨 앞에 남는다. 그런 출연이 `batch-size`개 쌓이면 뒤 출연은 처리되지 않는다. 지금은 매번 실패할 경로가 없지만, 켜면 경고 로그 `작성 기한 만료 리뷰 공개 중 예외`가 반복되는지 지켜본다.
- 구조: `app.AppearanceReviewExpirePublishUseCase`, `Appearance.isReviewWritePeriodEnded`, `AppearanceReviewRepository.findAppearanceIdsWithUnpublishedConfirmedUpTo`, 배치 메서드 `AppearanceBatch.publishExpiredReviews`

### 21.8 남은 일

- 출연 상세의 리뷰·회원용 받은 리뷰 목록 조회는 22장(#514)
- 회원·FE 연동 정리는 23장(#516)

---

## 22. 출연 리뷰 조회 (#514)

출연 화면에서 리뷰 작성 가능 여부와 내 리뷰·받은 리뷰를 보여 주고, 회원 모듈이 회원이 받은 리뷰 목록을 가져갈 내부 API를 둔다. 읽기 전용이다.

### 22.1 출연 상세의 리뷰 정보

출연 상세(`GET /api/v1/appearances/{appearanceId}`)와 상세를 돌려주는 모든 출연 API 응답(`AppearanceResDto`)에 세 필드를 더했다.

| 필드 | 타입 | 내용 |
|---|---|---|
| `reviewable` | boolean | 지금 내가 이 출연에 리뷰를 쓸 수 있는지. 작성 API(21.2)의 확인과 **같은 클래스**(`AppearanceReviewEligibility`)로 판단한다: 확인된 출연, 작성 기한 안, 나와 상대가 정상 회원(나는 인플루언서 이상), 아직 쓰지 않음 |
| `myReview` | `AppearanceReviewResDto` 또는 null | 내가 쓴 리뷰. 쓰지 않았으면 null. 상대가 아직 안 썼으면 `published=false`, `publishedAt=null` |
| `receivedReview` | `AppearanceReviewResDto` 또는 null | 상대가 나에게 쓴 리뷰. **두 리뷰가 함께 공개된 뒤에만** 채운다. 상대만 쓴 동안에는 null (블라인드: 상대가 썼다는 사실도 알리지 않는다) |

- `AppearanceReviewResDto`: `id`, `rating`, `content`(없으면 null), `published`, `publishedAt`, `createdAt`
- 작성자 자격을 보지 않는 출연 확인·불참 응답(APR-35 E5)에서도 `reviewable`은 같은 기준이다. 정지된 호스트가 확인하면 응답의 `reviewable`은 false다.
- 쓸 수 없는 이유는 상세에 담지 않는다. 화면은 `reviewable`로 버튼만 정하고, 이유는 작성 API의 오류 코드로 안다.
- 리뷰는 출연당 최대 2건이라 상세마다 `appearance_id`로 한 번 더 읽는다 (유니크 제약의 인덱스). `reviewable` 판단에 회원 복제·리뷰 존재 조회가 더 붙는다.

### 22.2 회원이 받은 공개 리뷰 (내부 API)

`GET /api/v1/internal/appearances/users/{userId}/reviews?page=&size=` → `RsData<Page<AppearanceReceivedReviewDto>>`

| 항목 | 내용 |
|---|---|
| 호출 | 회원 모듈이 프로필의 리뷰 목록을 만들 때 shared `AppearanceApiClient.getReceivedReviews`(22.3)로 부른다. 내부 포트(`http://appearance-app:8081`), Gateway를 거치지 않는다. 회원 쪽 구현은 #555 (23장) |
| 인증·노출 | 수익 내부 조회(18.1)와 같다: `X-Internal-Api-Key`, 틀리거나 없으면 본문 없는 401, 회원 로그인으로는 401, 외부 포트는 404 |
| 출연 쪽 방어 | ① 보안 체인의 키 검증 ② `AppearanceReviewInternalFacade`의 `@PreAuthorize("hasRole('INTERNAL')")` ③ UseCase의 회원 ID 검증 |
| 범위 | 그 회원이 **받은** 리뷰 중 **공개된 것만**. 공개 전 리뷰는 받은 사람에게도 보이지 않으므로 뺀다. 쓴 사람이 그 뒤 정지·탈퇴해도 이미 공개된 리뷰는 그대로 준다 |
| 정렬 | 최신 공개순 `publishedAt desc, reviewId desc`로 고정, 요청의 `sort`는 무시한다 (수익 목록과 같은 방식). 함께 공개된 두 리뷰는 받은 사람이 달라 한 목록에 같이 나오지 않는다 |
| 페이지 | `page`·`size`(기본 20, 최대 100. 출연 앱 공통 `PageConfig`). 응답은 `{content, page: {size, number, totalElements, totalPages}}` |
| 회원 ID | 경로 `userId`. 1 미만이거나 숫자가 아니면 400 `INVALID_REQUEST`. 리뷰가 없는 회원은 빈 페이지 (회원 복제를 확인하지 않는다) |
| 항목 | `reviewId`, `appearanceId`, `reviewerUserId`, `revieweeRole`(`HOST`·`GUEST`, 조회한 회원이 그 출연에서 맡은 역할), `rating`, `content`(없으면 null, 필드는 남는다), `publishedAt`(offset 없는 KST) |
| 계약 타입 | shared `dto.AppearanceReceivedReviewDto`·`AppearanceReceivedReviewPageDto`, 역할 `dto.AppearanceParticipantRole`(공개 이벤트 #513과 같은 enum) |
| 인덱스 | `idx_appearance_reviews_reviewee_id_published_at (reviewee_id, published_at)` (21.5, #512에서 미리 만듦) |

### 22.3 Client

```java
AppearanceReceivedReviewPageDto getReceivedReviews(long userId, int page, int size);
```

- 설정·실패 규칙은 18.6과 같다 (`REJECTED`: 4xx, `UNAVAILABLE`: 연결 실패·시간 초과·5xx·읽을 수 없는 성공 응답). 필수 필드(`content` 본문 제외)가 빠지거나 계약에 없는 역할 값이면 `UNAVAILABLE`이다.

### 22.4 구조

| 계층 | 클래스 | 역할 |
|---|---|---|
| in | `ApiV1AppearanceReviewInternalController` / `ApiV1AppearanceReviewInternalSpec` | 내부 API 매핑·Swagger |
| app | `AppearanceReviewInternalFacade` | 내부 역할 검사, 읽기 전용 트랜잭션 |
| app | `AppearanceReviewQueryUseCase` | 받은 공개 리뷰 목록, 회원 ID 검증, shared DTO로 변환 |
| app | `AppearanceQueryUseCase` (기존) | 출연 상세에 리뷰 정보 추가 |
| app | `AppearanceReviewEligibility` | 작성 가능 여부 (작성 API와 상세가 같이 씀) |
| app | `AppearanceReviewSupport` (기존) | 출연의 리뷰, 받은 공개 리뷰 페이지 조회 추가 |

---

## 23. 리뷰 연동 정리 (#516)

리뷰 원본은 출연이 갖고, 다른 쪽은 이벤트와 API로만 읽는다. 받는 쪽이 할 일을 한곳에 모았다. 형식의 기준은 21~22장과 shared 레코드다. 회원 쪽 구현은 #555다.

| 받는 쪽 | 쓰는 것 | 용도 |
|---|---|---|
| 회원 모듈 | `AppearanceReviewPublishedEvent` (`appearance.events.v1`, 21.6) | 회원별 출연 평점(평균·개수) 집계 |
| 회원 모듈 | 내부 API `getReceivedReviews` (22.2·22.3) | 프로필의 받은 리뷰 목록 (평점 초기 적재가 필요해지면 같은 API) |
| FE | 작성 API (21.2), 출연 상세의 리뷰 필드 (22.1) | 출연 상세 화면의 리뷰 작성·표시 |

### 23.1 회원 모듈: 평점 집계

회원 쪽 구현은 #555(PR #572)다. 출연 계약(토픽·key·`__TypeId__`·필드)은 바꾸지 않고 그대로 받는다.

- 공개 이벤트를 소비 그룹 `member-app.appearance-review`로 받는다. 같은 토픽의 다른 타입(요청·지원 알림)은 기본 처리기에서 무시한다. 회원이 처리하지 못한 메시지(필수 값 누락, 회원 없음 등)는 회원 DLT `appearance.events.v1.member-app.appearance-review.DLT`에 쌓인다.
- **중복 제거**: 최소 한 번 전달이라 같은 `reviewId`가 다시 올 수 있다. 처리한 `reviewId`를 저장하고(유니크) 이미 있으면 건너뛴다.
- **집계 값**: 받은 회원(`revieweeUserId`)마다 `revieweeRole`(`HOST`·`GUEST`)별로 별점 합계·개수를 쌓는다(회원 `members.appearance_rating`, 반영한 리뷰는 `members.appearance_review_receipt`). 평균은 읽을 때 `합계 / 개수`로 내고, 서버가 소수 첫째 자리에서 반올림한다(4.25 → 4.3). 리뷰가 없으면 `average: null, count: 0`.
- **응답 모양 (회원 API)**: 프로필 상세 `GET /api/v1/members/{id}`의 `appearanceRating`은 `total`·`asHost`·`asGuest` 각각 `{average, count}`다. 인플루언서 목록(검색)에는 전체만 `{average, count}`로 넣는다. FE는 이 값을 그대로 보여 주고 계산하지 않는다.
- **이벤트 수**: 블라인드 공개면 출연 하나에 2개(받은 사람이 서로 다름)가 나간다. 작성 기한을 켜면 만료 공개 배치(21.7, #515)가 한쪽 리뷰만 1개 나갈 수 있다. 어느 경우든 이벤트 1개 = 공개 리뷰 1건으로 센다.
- **순서**: key가 받은 회원 단위라 같은 회원의 이벤트는 한 partition에 모이지만, 재발행 때문에 순서는 보장하지 않는다. 합계·개수는 순서와 상관없다.
- **수정·삭제 없음**: 리뷰는 고치거나 지우지 않으므로 공개된 리뷰를 빼는 이벤트는 없다. 쓴 사람이나 받은 사람이 뒤에 정지·탈퇴해도 공개된 리뷰는 그대로다.
- **초기 적재: 하지 않았다.** 회원 배포 시점에 FE 리뷰 작성 화면이 없어 운영에 공개된 리뷰가 사실상 없었고, 회원 그룹은 처음 붙을 때 토픽 맨 앞(`earliest`)부터 읽었다. 회원 소비자가 72시간 넘게 멈추는 등 이벤트를 놓쳤다면, 받은 리뷰 내부 API로 회원별 공개 리뷰를 읽어 같은 방식(`reviewId` 중복 제거)으로 다시 채운다. 회원 평점은 출연 원본에서 만든 집계본이라 언제든 이 방법으로 다시 만들 수 있다.
- **출연이 평점이 필요해질 때**: 화면 표시용은 FE가 회원 API로 붙인다. 서버 판단용(정렬·필터 등)은 출연 리뷰 원본(`appearances.reviews`의 공개 리뷰를 `reviewee_id`·`reviewee_role`별로)으로 계산한다. 원본이 출연에 있으므로 회원 API를 동기로 부르지 않는다.

### 23.2 회원 모듈: 프로필 리뷰 목록

- `AppearanceApiClient.getReceivedReviews(userId, page, size)`로 부른다. 최신 공개순 고정, `size` 최대 100, 리뷰가 없으면 빈 페이지다.
- 회원 API는 `GET /api/v1/members/{id}/appearance-reviews?page=&size=`이고, page·size를 출연 내부 API에 그대로 넘긴다(최대 100).
- 항목에 쓴 사람 닉네임은 없다(`reviewerUserId`만). 회원이 항목마다 `reviewer {id, nickname, profileImageUrl}`을 붙인다. 쓴 사람이 탈퇴했거나 없으면 "탈퇴한 회원", 정지 회원은 그대로 보여 준다.
- 실패는 `AppearanceApiCallException`의 `REJECTED`(4xx)·`UNAVAILABLE`(연결 실패·5xx·읽을 수 없는 응답)로 온다(18.6). 회원은 둘 다 회원 503 `APPEARANCE_REVIEW_UNAVAILABLE`로 바꾸고, `REJECTED`는 error 로그로 남긴다(내부 API 키·계약이 어긋나면 회원 로그로 보임).
- 프로필 주인이 정상 회원이 아니면 회원이 프로필과 같이 404를 주고, 이때는 출연 내부 API를 부르지 않는다.

### 23.3 FE: 출연 상세 화면

- 상세 응답의 `reviewable`이 true면 작성 버튼을 보인다. 쓸 수 없는 이유는 담지 않는다.
- 작성: `POST /api/v1/appearances/{appearanceId}/reviews` `{rating: 1~5 정수, content?: 1,000자}` → 201 `{id}`. 성공 뒤 상세를 다시 읽는다(응답에 공개 여부가 없다).
- `myReview`가 있고 `published=false`면 "상대가 쓰면 함께 공개" 상태, `receivedReview`는 둘 다 공개된 뒤에만 온다.
- 작성 오류: 400 `INVALID_REQUEST`, 403 `APPEARANCE_USER_NOT_ACTIVE`·`NOT_INFLUENCER`, 404 `APPEARANCE_NOT_FOUND`, 409 `APPEARANCE_NOT_REVIEWABLE`·`REVIEW_PERIOD_ENDED`·`REVIEWEE_NOT_ACTIVE`·`ALREADY_REVIEWED` ([error-codes.md](error-codes.md)). 409는 화면 상태가 오래된 경우라 상세를 다시 읽으면 버튼이 사라진다.
- 2026-11-02 기준 FE의 `Appearance` 타입에는 리뷰 필드가 없다. 필드만 더해진 것이라 기존 화면은 깨지지 않는다. 리뷰 화면은 FE #161로 요청했다(타입·API·화면 상태·오류 처리 명세).
- 작성 기한(`REVIEW_PERIOD_ENDED`)은 지금 꺼져 있다. 켤 때는 FE에 기한 안내가 필요하다(#515).

---

## 24. 모집글·지원 이력 내부 조회 (#586)

채팅 앱이 모집용 단체방을 만들고 초대할 때 출연의 모집글·지원 이력을 확인하는 내부 API다. 채팅은 모집·지원을 복제하지 않고 그때그때 부른다(채팅 결정 Q35, 채팅 설계 문서 3.4). 읽기 전용이다.

- 호출하는 쪽(채팅)이 보는 계약: [docs/api/internal-appearances.md](../api/internal-appearances.md)

### 24.1 공통

| 항목 | 내용 |
|---|---|
| 호출 | 채팅 앱이 자기 `@HttpExchange` Client로 부른다(shared Client 없음). 내부 포트(`http://appearance-app:8081`), Gateway를 거치지 않는다 |
| 인증·노출 | 수익 내부 조회(18.1)와 같다: `X-Internal-Api-Key`, 틀리거나 없으면 본문 없는 401, 회원 로그인으로는 401, 외부 포트는 404 |
| 출연 쪽 방어 | ① 보안 체인의 키 검증 ② `AppearanceRecruitInternalFacade`의 `@PreAuthorize("hasRole('INTERNAL')")` ③ UseCase의 값 검증 |
| 권한 | 호스트 확인은 하지 않는다. 채팅이 응답의 `hostId`로 확인한다 |
| 오류 | 없는 모집글 404 `APPEARANCE_RECRUIT_NOT_FOUND`, 값 오류·숫자 아님 400 `INVALID_REQUEST` (회원 API와 같은 코드) |

### 24.2 API

| API | 요청 | 응답 `data` |
|---|---|---|
| `GET /api/v1/internal/appearances/recruits/{recruitId}` | | `{recruitId, hostId, title, status}` |
| `GET …/recruits/{recruitId}/applicants` | `afterUserId`(생략하거나 0이면 처음부터, 음수는 400. 회원 내부 API의 `afterId`와 같음), `size`(기본 100, 1~500) | `{userIds, nextAfterUserId}`. 다음 페이지가 있으면 `nextAfterUserId`에 이 페이지의 마지막 ID, 마지막 페이지면 키가 빠진다 |
| `GET …/recruits/{recruitId}/applicants/exists` | `userIds` 1~100개(중복 포함 개수). `userIds=1,2` 또는 `userIds=1&userIds=2` | `{appliedUserIds}`. 요청한 회원 중 지원 이력이 있는 회원, 오름차순·중복 없음 |

- **모집글 상태는 그대로 준다.** 회원 API는 HP 보류 처리 중(`HOLD_PENDING`)·실패(`HOLD_FAILED`) 모집글을 호스트 외에는 없는 것처럼 숨기지만(404), 내부 API는 다섯 상태(`HOLD_PENDING`·`HOLD_FAILED`·`RECRUITING`·`CLOSED`·`CANCELED`)를 모두 그대로 주고 방을 만들 수 있는지는 채팅이 판단한다(채팅 담당과 합의, 11/03).
- **지원 이력은 지원 상태와 관계없다**(`APPLIED`·`SELECTED`·`NOT_SELECTED`·`WITHDRAWN`). 같은 게스트는 같은 모집글에 다시 지원할 수 없어(유니크 제약 `uk_appearance_applications_recruit_guest`) 회원마다 한 번만 나온다. 지원 행은 지우지 않으므로 이력은 늘기만 한다.
- 회원 복제를 확인하지 않는다. 정지·탈퇴 회원도 지원 이력이 있으면 나온다(채팅 자격은 채팅이 본다).
- 지원자 ID 목록은 한 건 더 읽어 다음 페이지가 있는지 판단한다. 딱 맞게 끝나는 페이지도 `nextAfterUserId`가 없다.
- 인덱스: 두 조회 모두 유니크 제약 `(recruit_id, guest_id)`의 인덱스를 쓴다.

### 24.3 구조

| 계층 | 클래스 | 역할 |
|---|---|---|
| in | `ApiV1AppearanceRecruitInternalController` / `ApiV1AppearanceRecruitInternalSpec` | 내부 API 매핑·Swagger |
| app | `AppearanceRecruitInternalFacade` | 내부 역할 검사, 읽기 전용 트랜잭션 |
| app | `AppearanceRecruitInternalQueryUseCase` | 값 검증, 모집글 확인, 키셋 페이지 |
| app | `AppearanceApplicationSupport` (기존) | `findGuestIdsAfter`(키셋)·`findAppliedGuestIds`(지원 이력) 추가. 출연 안에서는 지원의 게스트 ID(`guestId`)라 하고 API에서는 회원 ID(`userId`)라 한다 |
| out | `AppearanceApplicationRepository` (기존) | `findGuestIdsAfter`, `findAppliedGuestIds` |
| app/dto | `AppearanceRecruitInternalResDto`, `AppearanceRecruitApplicantIdsResDto`, `AppearanceRecruitAppliedUserIdsResDto` | 응답 (출연 앱 안, shared 계약 없음) |

## 25. 서비스 간 HP 대사 (#581)

출연과 지갑은 database가 따로라 출연 상태와 지갑의 보류·지급을 한 트랜잭션으로 맞출 수 없다. 대사의 주체는 업무 모듈이고 지갑은 조회 API만 준다(지갑 #577, `docs/api/internal-billing.md` 4장, BD-137). 출연은 매일 자기 요청서·모집글·출연을 지갑 기록과 비교해 어긋난 건을 남긴다. **고치지 않는다.** 정정은 원인을 확인한 관리자가 기존 수단(HP 작업 다시 시도 19장, 보류 무효화 8.2)으로 한다.

### 25.1 비교 방식: 출연 기록(HP 작업) 기준 두 단계

| 단계 | 비교 | 잡는 문제 |
|---|---|---|
| ① 상태 → HP 작업 | 출연 상태로 계산한 남은 보류·지급액 = 끝난(`DONE`) HP 작업의 합 | 상태는 바뀌었는데 작업을 남기지 않은 출연 쪽 누락 |
| ② HP 작업 → 지갑 | 끝난 작업의 반환 합·지급 합·지급 건 = 지갑 조회 결과 | 지갑 쪽 반영 누락·중복, 지갑에만 있는 보류 |

상태별 공식은 ①에만 있다(`HpReconciliationTarget`). 지갑과는 작업의 합으로만 비교하므로 모집 중 취소·빈자리 반환·위약금이 모두 같은 식이다. 보류 상태 이름(`HELD`·`MIXED` 등)은 보지 않고 금액만 본다.

- **최초 보류**: 보류 요청에 쓴 금액. 요청서는 출연료, 모집글은 1인당 출연료 × 모집 인원. 보류한 회원은 호스트
- **상태로 계산한 남은 보류**
  - 요청서: 요청 중 = 출연료, 수락 뒤 = 출연이 진행 중(성립·취소 요청 중)이면 출연료 아니면 0, 거절·만료·응답 전 취소 = 0
  - 모집글: 모집 중 = 최초 보류 − 끝난 출연(확인·불참·호스트 일방 취소)의 출연료(모집 중 합의 취소는 자리가 다시 열려 반환하지 않는다, 13장), 마감·전체 취소 뒤 = 진행 중인 출연의 출연료 합(빈자리는 반환됐다)
- **상태로 계산한 지급액**: 확인된 출연의 출연료 + 호스트 일방 취소의 위약금
- **작업의 합**: 반환 = 반환 작업 금액 + 위약금 작업에서 호스트에게 돌려준 몫, 지급 = 지급 작업 + 위약금, 남은 보류 = 최초 − 반환 − 지급
- **지급 건**: 끝난 지급·위약금 작업마다 지갑 지급 건 하나(원천 유형 `APPEARANCE_REQUEST`·`RECRUIT_APPEARANCE`, 원천 ID = 출연 ID, `NORMAL`·`PENALTY`, 받는 회원 = 게스트, 금액 = 작업의 지급액, `COMPLETED`)

### 25.2 규칙 (`HpReconciliationRule`)

| 규칙 | 단계 | 어긋남 |
|---|---|---|
| `HP_TASK_MISMATCH` | ① | 상태로 계산한 남은 보류·지급액 ≠ 끝난 작업의 합. 보류 실패 건은 작업이 있으면 |
| `HOLD_MISSING` | ② | 보류가 있어야 하는데 지갑에 없음 |
| `HOLD_MISMATCH` | ② | 최초 보류액이나 보류한 회원이 다름 |
| `HOLD_BALANCE_MISMATCH` | ② | 반환·지급·확정 차감(0이어야 함)·남은 보류가 작업의 합과 다름 (예: 거절했는데 `HELD`, 요청 중인데 반환됨, 마감 뒤 빈자리 반환 누락) |
| `HOLD_NOT_CLOSED` | ② | 보류 실패(`HOLD_FAILED`)인데 남은 보류·지급·확정 차감이 있음. 보류 없음·무효화·전부 반환은 정상 |
| `VOIDED_WHILE_ACTIVE` | ② | 보류 실패가 아닌데 무효화 기록이 있음 (#536 배포 중 두 pod 경합 등) |
| `SETTLEMENT_MISSING` | ② | 끝난 지급·위약금 작업의 지급 건이 없음 (출연 ID를 남긴다) |
| `SETTLEMENT_MISMATCH` | ② | 지급 건의 받는 회원·금액·상태가 작업과 다름 |
| `SETTLEMENT_UNEXPECTED` | ② | 출연 작업에 없는 지급 건 |
| `WALLET_ONLY_HOLD` | ② | 지갑 보류 목록의 참조 ID가 출연에 없음 |
| `HP_TASK_DEAD` | 표시 | 격리된 HP 작업이 있어 비교하지 않음 (어긋난 건 수에서 뺀다, 관리자 다시 시도 대상) |

### 25.3 실행

- **대상**: ① 출연 쪽은 비교 범위(`lookback`, 7일) 안에 요청서·모집글·그 출연·그 HP 작업 중 하나라도 바뀐 것, ② 지갑 쪽은 저장한 커서 뒤에 바뀐 보류(`listHolds`, 끝까지 읽음). 둘을 합쳐 500개씩 `queryHolds`로 조회한다.
- **빼는 것(진행 중)**: 보류 결과 대기(`HOLD_PENDING`), 재시도를 기다리는 HP 작업(`PENDING`), 마지막 변경이 진행 중 유예(`settle-grace`, 2시간) 안인 것. 유예는 HP 작업 재시도 최대 간격·보류 포기 기준(1시간)보다 길다.
- **커서**: 참조 유형마다 `hp_reconciliation_cursors` 한 행. 끝까지 비교한 실행만 앞으로 옮긴다(지갑 `Instant`를 KST `LocalDateTime`으로 저장). 처음에는 처음부터 읽는다.
- **비교하는 사이의 변경**: 출연 쪽을 읽은 뒤 지갑을 조회하기 전에 다른 배치(HP 작업 재시도 40초·자동 확인 50초)가 작업을 실행하면 거짓 어긋남이 생길 수 있다. 어긋남이 나온 건은 다시 읽어 마지막 변경 시각이 달라졌으면 이번에는 남기지 않는다(비교 수에도 넣지 않고 다음 실행이 다시 본다).
- **지갑 조회 실패**(회로 열림·결과 불명·거절·응답에서 ID 누락): 그 실행을 `INCOMPLETE`로 끝내고 커서를 옮기지 않는다. 그때까지 찾은 어긋남은 남긴다. 다음 실행이 같은 범위를 다시 본다.
- **주기**: 매일 04:00:25(KST, `appearance.batch.hp-reconciliation-cron`). 매분 배치들이 쓰지 않는 25초다.
- **동시 실행**: 같은 앱에서 한 번만 돈다(실행 중 표시는 앱 메모리, 출연 앱 1대 전제). 배치는 실행 중이면 건너뛰고 관리자 실행은 409 `HP_RECONCILIATION_RUNNING`. 앱이 멈춰 `RUNNING`으로 남은 실행은 다음 실행이 시작될 때 `INCOMPLETE`("앱이 멈춰 끝나지 못한 실행")로 정리한다. 롤링 배포로 두 pod가 겹치는 동안 새 pod가 실행을 시작하면 옛 pod의 정상 실행도 이렇게 정리될 수 있다(매일 한 번이라 드묾). replica를 늘리면 단일 실행 장치를 먼저 둔다.
- **설정** (`appearance.hp-reconciliation.*`): `settle-grace` 2h, `lookback` 7d(유예보다 길어야 기동), `page-size` 500(1~500).

### 25.4 결과

- `hp_reconciliation_runs`(실행 1회: 계기·실행한 관리자·상태·비교 수·어긋난 수·DEAD 수·중단 이유), `hp_reconciliation_mismatches`(대상·출연 ID·규칙·출연 상태 요약·지갑 상태 요약). 오래된 기록은 지우지 않는다. 마이그레이션 `V202611031100`
- 경고 로그: 어긋난 건마다 `[HP_RECONCILIATION_MISMATCH] rule=… runId=… referenceType=… referenceId=… appearanceId=… appearance=[…] wallet=[…]`. 끝까지 하지 못하면 경고 한 줄(이유 포함)
- 지표: `appearance.hp_reconciliation.mismatches{rule}`(마지막으로 끝까지 비교한 실행의 규칙별 건수, 중단된 실행은 값을 바꾸지 않음), `appearance.hp_reconciliation.runs{status}`(끝난 실행 횟수). 이 지표에 Grafana alert "서비스 간 HP 대사 불일치"·"대사가 돌지 않음"이 걸려 있다(#781, [구성한 alert](../architecture/infrastructure/observability.md#구성한-alert-781))

### 25.5 API (관리자, `/api/v1/appearances/admin/hp-reconciliations`)

| 메서드·경로 | 동작 | 응답 |
|---|---|---|
| `POST` | 지금 실행하고 끝날 때까지 기다린다. 처음 실행은 지갑 목록을 처음부터 읽어 오래 걸릴 수 있고, 응답 전에 연결이 끊겨도 서버에서는 끝까지 실행되므로 `GET /latest`로 확인한다 | 200 실행 결과(`HpReconciliationRunResDto`, 어긋난 건 포함). 지갑 조회가 실패해도 200 `INCOMPLETE` + `failureReason`. 실행 중이면 409 `HP_RECONCILIATION_RUNNING` |
| `GET /latest` | 마지막으로 시작한 실행과 어긋난 건 | 200. 실행 기록이 없으면 404 `HP_RECONCILIATION_NOT_FOUND`. 실행 중이면 `RUNNING`(어긋난 건은 끝난 뒤 남는다) |

관리자 확인은 19장과 같다(보안 설정 → `@PreAuthorize` → 복제 회원 정보로 지금도 관리자인지).

### 25.6 구조

| 클래스 | 역할 |
|---|---|
| `HpReconciliationUseCase` | 실행 흐름(지갑 목록 → 대상 모음 → 500개씩 조회·비교 → 기록), 동시 실행 막기, 로그·지표 |
| `HpReconciliationTarget` | 비교 대상 하나: 상태로 계산한 기대값과 작업의 합 |
| `HpReconciliationComparer` | ①·② 비교 규칙 (DB·지갑을 부르지 않는 순수 비교), 상태 요약 문자열 |
| `HpReconciliationSupport` | 트랜잭션 단위 DB 읽기·기록 (지갑 호출은 트랜잭션 밖) |
| `HpReconciliationAdminFacade` | 관리자 API 진입점 |
| `BillingClient.queryHolds`·`listHolds` | 공용 `BillingApiClient`에 그대로 넘긴다 (회로·재시도 정책 같음) |

## 26. 회원 탈퇴 막힌 이유 조회 (#624)

회원 탈퇴(#593)는 회원 앱이 ① 출연 → ② 강의 → ③ 지갑 닫기 순서로 막힌 이유를 모아 회원에게 한 번에 보여 준다. 출연은 ①이고 **읽기만 한다**(아무것도 취소·환불·변경하지 않는다). 막힌 이유가 있으면 회원이 안내를 보고 직접 정리한 뒤 다시 탈퇴한다.

- 계약(원본): [docs/api/internal-withdrawals.md](../api/internal-withdrawals.md) 2장(공통 DTO)·3장(조회). 설계 결정은 ISSUE.md APR-49 WB1~WB5
- 탈퇴가 끝나면 기존 `UserModifiedEvent(WITHDRAWN)`로 출연 회원이 탈퇴 상태가 되고, 이미 있는 "정지·탈퇴 회원과 거래 막기" 확인이 그대로 동작한다. 이벤트 처리는 바꾸지 않았다.
- 복구 기한이 지나 회원 앱이 파기하면 `UserMaskedEvent`로 호스트 채널 URL을 덮는다(4.1.2, #675).

### 26.1 API

`GET /api/v1/internal/appearances/users/{userId}/withdrawal-blocks` → `RsData<WithdrawalBlocksDto>` (메시지 "탈퇴 확인을 마쳤습니다.")

| 항목 | 내용 |
|---|---|
| 호출 | 회원 앱이 shared `AppearanceApiClient.getWithdrawalBlocks(userId)`로 부른다. 실패는 기존 `AppearanceApiCallException`(`REJECTED`·`UNAVAILABLE`)이고, 회원은 어떤 실패든 탈퇴를 멈춘다(읽기라 되돌릴 것이 없다) |
| 인증·노출 | 수익 내부 조회(18.1)와 같다: `X-Internal-Api-Key`, 틀리거나 없으면 본문 없는 401, 회원 로그인으로는 401, 외부 포트는 404 |
| 출연 쪽 방어 | ① 보안 체인의 키 검증 ② `AppearanceWithdrawalInternalFacade`의 `@PreAuthorize("hasRole('INTERNAL')")` ③ UseCase의 회원 ID 검증 |
| 응답 | 회원 공통 DTO `shared.member.dto.WithdrawalBlocksDto` `{reasons: [{code, message, availableAt}]}`를 그대로 쓴다. 막힌 이유가 없으면 빈 목록 |
| 빈 목록 | 끝난 건만 있는 회원, 출연 기록이 없는 회원, 복제 회원 정보가 없는 회원 (회원 복제를 확인하지 않는다) |
| 오류 | `userId` 1 미만·숫자 아님 400 `INVALID_REQUEST` |

### 26.2 막힌 이유

판단 기준: **탈퇴한 뒤에도 출연 때문에 그 회원 지갑으로 HP가 드나들 일이 남았으면 막는다.** 닫힌 지갑도 보류 반환·지급은 받으므로(#623), 막지 않으면 HP가 탈퇴 회원의 닫힌 지갑에 들어가 아무도 쓸 수 없다. 돈 문제는 없어도 **상대가 그 회원의 응답을 기다리는 건**도 막는다(WB1).

조건마다 건수를 세어 이유 하나를 만든다(건마다 만들지 않는다). 순서는 아래 표 순서이고(`AppearanceWithdrawalBlock` 선언 순서), 건수는 천 단위 쉼표다.

| code | 역할 | 조건 | message |
|---|---|---|---|
| `APPEARANCE_REQUEST_SENT` | 호스트 | 요청서 `REQUESTED` | 응답을 기다리는 출연 요청 N건이 있어요. 요청을 취소한 뒤 탈퇴할 수 있어요. |
| `APPEARANCE_REQUEST_RECEIVED` | 게스트 | 요청서 `REQUESTED` | 받은 출연 요청 N건에 아직 응답하지 않았어요. 요청한 회원이 기다리고 있으니 거절한 뒤 탈퇴할 수 있어요. |
| `APPEARANCE_RECRUITING` | 호스트 | 모집글 `RECRUITING` | 모집 중인 출연 모집 N건이 있어요. 모집을 마감하거나 취소한 뒤 탈퇴할 수 있어요. |
| `APPEARANCE_APPLICATION_PENDING` | 게스트 | 지원 `APPLIED` | 결과를 기다리는 출연 지원 N건이 있어요. 호스트가 선정할 수 있으니 지원을 취소한 뒤 탈퇴할 수 있어요. |
| `APPEARANCE_IN_PROGRESS` | 둘 다 | 출연 `ESTABLISHED`·`CANCEL_REQUESTED` | 성립된 출연 N건이 진행 중이라 탈퇴할 수 없어요. 출연이 끝나거나 취소된 뒤 탈퇴할 수 있어요. |
| `APPEARANCE_HP_PROCESSING` | 둘 다 | 요청서·모집글 `HOLD_PENDING`(호스트) + HP 작업 `PENDING`(보류한 회원 또는 받는 회원) | 출연 HP를 처리하고 있어요. 잠시 뒤 다시 시도해 주세요. |
| `APPEARANCE_HP_CHECK_REQUIRED` | 둘 다 | HP 작업 `DEAD`(보류한 회원 또는 받는 회원) | 출연 HP 처리에 문제가 있어 확인하고 있어요. 확인이 끝난 뒤 탈퇴할 수 있어요. |

- 막지 않는 끝난 상태: 요청서 `REJECTED`·`EXPIRED`·`CANCELED`·`HOLD_FAILED`·`ACCEPTED`(출연으로 넘어감), 모집글 `CLOSED`·`CANCELED`·`HOLD_FAILED`, 지원 `SELECTED`·`NOT_SELECTED`·`WITHDRAWN`, 출연 `CONFIRMED`·`ABSENT`·`CANCELED_*`, HP 작업 `DONE`. 마감한 모집글의 남은 인원분 반환은 HP 작업으로 센다.
- 출연 취소 요청 처리 중(`CANCEL_REQUESTED`)은 진행 중 출연에 포함한다(따로 적지 않는다).
- **`availableAt`은 주지 않는다**(항상 null, WB2). D+7 자동 확인일이 있지만 지급 지연·취소로 어긋날 수 있어 날짜를 약속하지 않는다.
- **격리된 HP 작업(`DEAD`)도 막는다**(WB3). 운영자가 다시 시도하면 HP가 움직이기 때문이다. "잠시 뒤 다시 시도"로 풀리지 않으므로 처리 중과 코드를 나눴다.
- 이의 제기: 출연에는 이의 제기 기능이 없다(고객센터 관할, APR-35 E4). 계약 3장 출연 행에서 뺐다.

### 26.3 알려진 한계 (확인과 지갑 닫기 사이)

①(출연 확인)과 ③(지갑 닫기) 사이(보통 1초 미만)에 회원은 아직 로그인 상태다(WB4).

| 빈틈에 생긴 일 | 처리 |
|---|---|
| 호스트로 새 요청서·모집글을 올림 → HP 보류가 남은 채 지갑이 닫힐 수 있음. 만료·마감되면 반환 HP가 닫힌 지갑으로 | 감당한다. 지갑은 닫을 때 출연·강의 보류를 보지 않는다(같은 보류를 두 번 적지 않는 계약 2장, #623) |
| 게스트로 받은 요청을 수락함 → 탈퇴 게스트와 출연이 성립, 확인하면 지급금이 닫힌 지갑으로 | 감당한다. 막으려면 출연 확인·지급 흐름을 바꿔야 해 범위 밖이다 |

두 경우 모두 HP는 닫힌 지갑에 남아 잃지 않는다(회원이 복구하면 다시 쓸 수 있다). 지갑이 닫힌 지갑에 들어온 HP를 warn 로그와 지표(`hapbang.billing.wallet.closed.with.balance`)로 알리므로(#623) 운영자가 찾을 수 있다. 닫힌 뒤 새 보류는 지갑이 409 `WALLET_CLOSED`로 거절하고, 출연은 지금처럼 보류 실패(`HOLD_FAILED`)로 처리한다.

### 26.4 구조

| 계층 | 클래스 | 역할 |
|---|---|---|
| in | `ApiV1AppearanceWithdrawalInternalController` / `ApiV1AppearanceWithdrawalInternalSpec` | 내부 API 매핑·Swagger |
| app | `AppearanceWithdrawalInternalFacade` | 내부 역할 검사, 읽기 전용 트랜잭션 하나(여러 건수를 한 시점 값으로) |
| app | `AppearanceWithdrawalBlockQueryUseCase` | 회원 ID 검증, 조건별 건수 → 이유 |
| domain | `AppearanceWithdrawalBlock` | 이유 코드(이름)·문구·순서 |
| app | `AppearanceRequestSupport`·`AppearanceRecruitSupport`·`AppearanceApplicationSupport`·`AppearanceSupport`·`HpTaskSupport` (기존) | 상태별 건수 메서드 추가 |
| out | 각 Repository (기존) | `countByHostIdAndStatus`·`countByGuestIdAndStatus`, 출연 `countByParticipantAndStatusIn`, HP 작업 `countByParticipantAndStatus` |
| shared | `AppearanceApiClient.getWithdrawalBlocks` | 회원 앱이 쓰는 Client 메서드 (`WITHDRAWAL_BLOCKS_PATH`) |

- 인덱스: 요청서·모집글·지원·출연은 기존 `host_id`·`guest_id` 인덱스를 쓴다. HP 작업은 `(status, next_attempt_at)` 인덱스로 `PENDING`·`DEAD`(적은 행)만 읽고 회원으로 거른다. 새 마이그레이션은 없다.

## 27. 보류 처리 중·실패 상태 호스트 조회 (#306)

HP 보류가 끝나지 않았거나(`HOLD_PENDING`) 실패한(`HOLD_FAILED`) 요청서·모집글을 **올린 호스트에게만** 목록·상세로 보여 준다. 출연은 상태값만 주고, 화면 표시와 실패 글 정리는 FE가 정한다(FE #170).

### 27.1 바뀐 점

| API | 전 | 후 |
|---|---|---|
| 내 모집글 목록 `GET /recruits/mine` | 두 상태 숨김, `status=HOLD_*`는 400 | `status`를 생략하면 두 상태도 포함, `status=HOLD_PENDING`·`HOLD_FAILED`로 거를 수 있음 |
| 보낸 요청 목록 `GET /requests/sent` | 같음 | 같음 |
| 모집글 상세 `GET /recruits/{id}` | 두 상태는 누구에게나 404 | 올린 호스트는 200, 다른 회원은 404 |
| 지원자 목록 `GET /recruits/{id}/applications` | 두 상태는 호스트에게도 404 | 올린 호스트는 빈 목록 200, 다른 회원은 404 (상세 화면이 호스트에게 지원자 목록을 함께 불러오므로 상세와 맞춤) |
| 요청서 상세 `GET /requests/{id}` | 두 상태는 호스트·게스트 모두 404 | 호스트는 200, 게스트·다른 회원은 404 |
| 그대로 | | 받은 요청 목록(두 상태 없음, 필터하면 400), 공개 모집 목록(`RECRUITING`만), 두 상태 글에 대한 모든 동작(지원·수정·마감·취소·수락·거절, 404) |

- 응답 필드는 그대로다. 두 상태의 상세는 `appearanceId`·`hpReleaseStatus`·`myApplication`이 null이다.
- 거절 사유(잔액 부족·닫힌 지갑 등)는 저장하지 않는다. 보낼 때 409·500 응답으로 이미 알렸고, 재확인 배치에서 실패한 건은 "보류 실패"로만 보인다.

### 27.2 설계

| 결정 | 내용 | 이유 |
|---|---|---|
| 확인을 둘로 나눔 | 상세·지원자 목록은 `validateDetailViewableBy(userId)`(새로 추가), 동작은 기존 `validateVisible()`·`validateViewableBy(userId)` | 보기와 다루기를 한 메서드로 묶으면 상세를 열 때 동작도 같이 열린다. 동작 쪽 코드는 손대지 않아 두 상태로 수정·취소·수락이 열릴 일이 없다 |
| 호스트만 | 게스트·다른 회원에게는 계속 404 | 보류에 성공해야 게스트에게 도착한 요청서·공개된 모집글이 된다. 실패한 글은 호스트의 기록일 뿐이다 |
| 목록 기본값에 포함 | `status`를 생략하면 모든 상태 | 호스트가 실패를 놓치지 않게. 대신 실패 글이 목록에 쌓이고, 숨기거나 따로 보여 줄지는 FE가 상태로 거른다(FE #170) |
| 새 컬럼 없음 | 거절 사유·실패 시각을 저장하지 않음 | FE 요구는 "상태를 알 수 있게"다. 사유는 보낼 때 응답으로 이미 알렸다. 마이그레이션 없이 끝낸다 |
| 기다리는 시간 | 결과를 모르면(503) 보류 재확인 배치(1분마다, 1분 지난 건)가 보통 1~2분 안에 정리하고, 1시간이 지나도 모르면 무효화해 `HOLD_FAILED`(#536) | FE는 목록에 `HOLD_PENDING` 글이 있을 때만 다시 불러오면 된다 |

### 27.3 구조

| 계층 | 클래스 | 변경 |
|---|---|---|
| domain | `AppearanceRecruit.validateDetailViewableBy`, `AppearanceRequest.validateDetailViewableBy` | 새 메서드. 공개 상태가 아니면 호스트만 통과 |
| domain | `AppearanceRecruitStatus.VISIBLE`, `AppearanceRequestStatus.VISIBLE` | 값은 그대로, 뜻을 "누구에게나 보이고 다룰 수 있는 상태"로 좁힘 |
| app | `AppearanceRecruitQueryUseCase`, `AppearanceRequestQueryUseCase` | 내 목록·보낸 목록은 필터가 없으면 모든 상태, 상세는 새 확인 메서드. 받은 목록은 그대로 |
| app | `AppearanceApplicationQueryUseCase.getApplicants` | 모집글 확인을 상세와 같은 새 메서드로. 호스트가 아니면 403은 그대로 |
| in | `ApiV1AppearanceRecruitSpec`, `ApiV1AppearanceRequestSpec` | 상태 필터 설명을 보낸·받은 목록으로 나누고, 올리기·목록·상세 설명 갱신 |

## 28. AI 추천용 출연 동기화 (#713)

AI 추천(auxiliary-app chatbot)이 출연 데이터의 **읽기용 사본**을 둘 수 있게, 성립 이후의 출연과 공개된 리뷰 별점을 이벤트와 키셋 내부 API로 준다. 평상시는 이벤트로 반영하고, 매시(#717, 처음엔 주 1회) 내부 API로 처음부터 끝까지 읽어 이벤트 누락을 바로잡는다(첫 실행이 곧 초기 적재). 이벤트와 내부 API는 같은 DTO를 쓴다. 계약은 [`docs/api/internal-appearance-sync.md`](../api/internal-appearance-sync.md).

### 28.1 공개 결과

AI는 출연 내부 상태 6개를 해석하지 않는다. 출연이 줄인 공개 결과(shared `AppearanceResult`)로 보낸다. 매핑은 `AppearanceStatus.result()` 한 곳의 `switch`라 상태를 더하면 컴파일이 잡는다.

| 내부 상태 | 공개 결과 |
|---|---|
| `ESTABLISHED`, `CANCEL_REQUESTED` | `SCHEDULED` |
| `CONFIRMED` | `COMPLETED` |
| `CANCELED_BY_HOST`, `CANCELED_BY_AGREEMENT` | `CANCELED` |
| `ABSENT` | `NO_SHOW` |

- 금액(출연료·위약금), 취소·불참 사유, 리뷰 본문, 출처(요청·모집), 리뷰 쓴 사람은 보내지 않는다. 노쇼한 쪽 같은 정보도 보내지 않는다(노쇼 감점은 기획 범위 밖).

### 28.2 DTO (`shared.appearance.dto`)

| DTO | 필드 | 버전 |
|---|---|---|
| `AppearanceSyncDto` | `id`, `hostId`, `guestId`, `result`, `scheduledAt`(KST, 마이크로초까지), `version` | 출연 `@Version`. 받는 쪽은 큰 것만 반영 |
| `AppearanceReviewSyncDto` | `reviewId`, `appearanceId`, `revieweeUserId`, `rating` | 없음. 리뷰는 공개 뒤 바뀌지 않아 `reviewId`로 한 번만 넣는다 |

- 만드는 곳은 `Appearance.toSyncDto()`·`AppearanceReview.toSyncDto()` 한 곳이다. 이벤트와 내부 API가 같은 메서드를 써서, 같은 출연·같은 version이면 값이 같다(AI가 DTO 해시로 어긋남을 비교).
- `toSyncDto()`는 ID·version이 없으면(DB에 반영 전) `IllegalStateException`. 리뷰는 공개 전이면 같은 예외.
- 리뷰 사본의 필드 4개는 기존 공개 이벤트(`AppearanceReviewPublishedEvent`, 21.6)의 같은 이름 필드와 값이 같다.

### 28.3 이벤트 `AppearanceChangedEvent(AppearanceSyncDto appearance)`

| 항목 | 내용 |
|---|---|
| 언제 | 모든 상태 전이에서 바뀐 뒤 한 번씩: 성립(요청 수락·모집 지원 수락), 취소 요청, 취소 요청 거절(호스트·자동 거절 배치), 합의 취소, 확인(호스트·D+7 자동 확인 배치), 불참, 호스트 일방 취소(단건·모집 전체 취소는 출연마다) |
| 공개 결과가 같은 전이 | `ESTABLISHED ↔ CANCEL_REQUESTED`도 version이 오르므로 발행한다(단순함 우선) |
| version | `@Version`은 flush 때 오른다. 그래서 **flush 바로 뒤에** 만든다: 성립은 `saveNow()` 뒤, 그 밖은 `AppearanceSupport.flushOrConflict()` 뒤, 모집 전체 취소는 `AppearanceRecruitSupport.flushOrConflict()` 뒤(루프 뒤 한 번에 flush하므로 `AppearanceHostCancelRecorder` 안이 아니다). flush 전에 만들면 오르기 전 version이 실려 AI가 오래된 이벤트로 버린다 |
| Kafka | 토픽 `appearance.events.v1`, key `appearance:<출연 ID>`, 헤더 `eventType=AppearanceChangedEvent` (11.3) |
| 리뷰 | 새 이벤트를 만들지 않는다. 기존 리뷰 공개 이벤트로 충분하다(21.6). 리뷰 수정·블라인드가 생기면 그때 변경 이벤트와 version을 더한다 |
| 순서 | 보장하지 않는다. 출연 이벤트끼리(커밋 뒤 비동기 전송·재발행), 출연과 리뷰 사이(key가 달라 다른 partition) 모두. AI는 출연을 version으로, 리뷰를 `reviewId`로 멱등 처리한다 |

### 28.4 내부 API (내부 포트 8081, `X-Internal-Api-Key`)

| 경로 | `data` |
|---|---|
| `GET /api/v1/internal/appearances/sync/appearances?afterId=&size=` | `AppearanceSyncPageDto(items, nextAfterId)`: 모든 출연(지우지 않음, 탈퇴 회원의 출연 포함) |
| `GET /api/v1/internal/appearances/sync/reviews?afterId=&size=` | `AppearanceReviewSyncPageDto(items, nextAfterId)`: 공개된 리뷰만 |

- 쿼리 규칙은 회원 내부 API와 같다: `afterId` 0 이상·생략 가능, `size` 1~500 기본 100, ID 오름차순, 마지막 페이지 `nextAfterId` null. 범위 밖은 400 `INVALID_REQUEST`, 키가 없거나 틀리면 401.
- 리뷰는 `published_at IS NOT NULL`로 DB에서 걸러 키셋이 끊기지 않는다. 스캔 뒤 공개된 작은 ID 리뷰는 공개 이벤트가 채운다.
- shared Client: `AppearanceApiClient.getSyncAppearances(afterId, size)`·`getSyncReviews(afterId, size)`.
- 받는 앱은 출연 NetworkPolicy의 내부 API label(`hapbang.io/internal-api-client: 'true'`)이 있어야 8081에 닿는다(auxiliary-app 배포 파일).

### 28.5 구조

| 계층 | 클래스 | 변경 |
|---|---|---|
| shared | `domain.AppearanceResult`, `dto.AppearanceSyncDto`·`AppearanceReviewSyncDto`·`AppearanceSyncPageDto`·`AppearanceReviewSyncPageDto`, `event.AppearanceChangedEvent`, `out.AppearanceApiClient` | 새 계약, Client 메서드 2개 |
| domain | `AppearanceStatus.result()`, `Appearance.toSyncDto()`·`toChangedEvent()`, `AppearanceReview.toSyncDto()` | 매핑·사본 생성 |
| app | 전이 UseCase 9개(`AppearanceRequestAcceptUseCase`, `AppearanceApplicationRespondUseCase`, `AppearanceCancelRequestUseCase`, `AppearanceCancelRespondUseCase`, `AppearanceCancelAutoRejectUseCase`, `AppearanceConfirmUseCase`, `AppearanceAutoConfirmUseCase`, `AppearanceHostCancelUseCase`, `AppearanceRecruitCancelUseCase`) | flush 뒤 발행 11곳 |
| app | `AppearanceSyncQueryUseCase`, `AppearanceSyncInternalFacade`(`ROLE_INTERNAL`) | 키셋 조회 |
| in | `ApiV1AppearanceSyncInternalSpec`·`Controller` | 내부 API 2개, Swagger에 내부 키 헤더 |
| out | `AppearanceRepository.findByIdGreaterThanOrderByIdAsc`, `AppearanceReviewRepository.findByIdGreaterThanAndPublishedAtIsNotNullOrderByIdAsc`, `AppearanceEventExternalizationConfig`(`eventType` 헤더) | 마이그레이션 없음 |

### 28.6 배포 순서

- shared가 바뀌어 모든 앱이 다시 배포된다(`.github/scripts/apps.sh`).
- 새 이벤트 클래스가 없는 옛 빌드의 소비자: chat-app은 `MissingTypeEvent`로 건너뛴다. member-app은 역직렬화에 실패해 `appearance.events.v1.member-app.appearance-review.DLT`로 보낸다. 회원이 쓰지 않는 이벤트라 되살릴 것은 없고, 모든 앱이 새 빌드로 바뀌는 짧은 틈에만 생긴다. 새 빌드에서는 두 앱 모두 기본 처리기로 무시한다(코드 확인, 다른 앱 코드 변경 없음).
- **첫 배포 직후 member-app DLT에 쌓인 `AppearanceChangedEvent`는 버려도 된다**(회원은 이 이벤트를 쓰지 않는다). 다시 보낼 필요가 없다.
- 소비자는 auxiliary-app chatbot 모듈이다(#716, 그룹 `auxiliary-app.chatbot-appearance-copy`, DLT `appearance.events.v1.auxiliary-app.chatbot-appearance-copy.DLT`). 소비자가 출연보다 먼저 배포될 필요는 없고(받는 쪽이 없으면 토픽에 쌓이기만 한다), 주간 동기화 첫 실행이 그 전 출연을 채운다.
