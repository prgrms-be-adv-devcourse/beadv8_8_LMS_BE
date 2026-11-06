# 알림(notification) 모듈 — 설계·구현 구조

> 문서 탐색: [알림 문서 INDEX](INDEX.md)

> 문서 지도: [도메인 개념](CONTEXT.md) · **[설계·구현 구조](module.md)** · [결정 기록](decisions.md) · [알림 종류](notification-catalog.md) · [API](../api/notification.md) · [테스트 검증 기준](test-spec.md) · [회귀 실행 기록](regression-log.md). 문서 목록(INDEX)은 후속 단계에서 만든다.
>
> - 기준: 2026-11-06 `dev` e054d6f6. 이 시점에 알림 모듈 코드는 없다. 코드가 생기면 문서와 코드가 다를 때 **코드가 맞다**. 확인 후 이 문서를 고친다.
> - 이 문서는 모듈의 구조와 상태만 적는다. 결정과 이유는 [decisions.md](decisions.md)(`ND-*`), 개념은 [CONTEXT.md](CONTEXT.md), 구현 규칙은 [`apps/auxiliary-app/boundedContext/notification/AGENTS.md`](../../apps/auxiliary-app/boundedContext/notification/AGENTS.md), 진행 상황은 [`PROGRESS.md`](../../apps/auxiliary-app/boundedContext/notification/PROGRESS.md)다. 결정은 ND 번호로 가리키고 본문을 다시 쓰지 않는다.
> - 업무 정책 원본은 [`docs/policy.md`](../policy.md)다(알림센터 장은 팀 승인 뒤 후속). 원천 설계는 [알림 설계](../architecture/infrastructure/notification.md)(이하 N)다.

## 0. 상태 표시

| 표시 | 뜻 | 근거 |
| --- | --- | --- |
| **As-Is** | 지금 레포에서 직접 확인한 사실 | 파일 경로 |
| **확정 To-Be** | ND로 확정됐지만 구현되지 않은 목표 | ND 번호 |
| **제안 To-Be** | 이 문서가 제안하는 구조. 채택되지 않았다 | 없음(구현 때 결정) |
| **미결** | 결정이 더 필요하다. 임의로 정하지 않는다 | decisions.md 10장 `U*`·`T*` |

## 1. 범위

맡는 것과 맡지 않는 것은 [CONTEXT.md](CONTEXT.md) 1장에 있다(ND-001~004). 채팅 알림은 채팅 소유이며 이 모듈의 구조에 넣지 않는다(ND-004, decisions.md 12장).

## 2. As-Is (기준 커밋 e054d6f6)

| 사실 | 근거 |
| --- | --- |
| `auxiliary-app`이 있다. 실행 진입점 `:auxiliary-app`, 공통 `:auxiliary-global`, 업무 모듈 디렉터리 `boundedContext/` | `apps/auxiliary-app/`, `apps/auxiliary-app/README.md` "구조" |
| 업무 모듈은 `chatbot` 하나다(`package-info.java`만 있다) | `apps/auxiliary-app/boundedContext/chatbot/src/main/java/com/hapbang/auxiliary/chatbot/` |
| Gradle에 notification 모듈이 등록되지 않았다: `val auxiliaryModules = listOf("chatbot")` | `settings.gradle.kts` 22행 |
| `:auxiliary-app`은 `:chatbot`만 의존한다 | `apps/auxiliary-app/build.gradle.kts` |
| `apps/auxiliary-app/boundedContext/notification/`에는 AGENTS.md·PROGRESS.md만 둔다(이번 문서 단계에서 만든다. 기준 커밋에는 디렉터리가 없다). `build.gradle.kts`가 없어 Gradle 프로젝트가 아니다 | 디렉터리 목록 |
| notification Java 코드·Flyway SQL·테스트가 없다 | `apps/auxiliary-app` 아래 `*.java`·`*.sql`에서 notification 검색 결과 없음 |
| Gateway HTTPRoute `auxiliary-app`의 `matches`는 `/api/v1/chatbot`, `/api/v1/auxiliary` 둘뿐이다. `/api/v1/notifications` route가 없다. 시간 제한 `request: 30s`, 본문 1Mi | `apps/auxiliary-app/k8s/base/gateway.yaml` |
| Gateway `claimToHeaders`는 `X-User-Id`(`sub`), `X-User-Role`(`role`)만 넘긴다. `X-User-Token-Exp`가 없다 | `apps/auxiliary-app/k8s/base/gateway.yaml` |
| auxiliary global 필터는 `X-User-Id`·`X-User-Role`만 읽는다. `X-User-Token-Exp`가 없다(U15) | `apps/auxiliary-app/global/src/main/java/com/hapbang/auxiliary/global/auth/GatewayUserHeaderFilter.java`, README "실행과 설정" 끝 |
| 공통 `SecurityConfig`가 Swagger·`/error`·헬스체크·prometheus 밖의 모든 요청에 인증을 요구한다 | `apps/auxiliary-app/global/src/main/java/com/hapbang/auxiliary/global/config/SecurityConfig.java` |
| Modulith 발행 기록·Flyway 이력은 schema `auxiliary`, Flyway `out-of-order: true`, Hikari 5, 스케줄 풀 2 | `apps/auxiliary-app/src/main/resources/application.yaml` |
| Kafka·Redis 클라이언트는 루트 빌드가 모든 앱에 넣는다(`:kafka`, `:redis`). auxiliary-app 빌드 파일에 WebSocket starter는 없다 | `build.gradle.kts` 62~68행, `apps/auxiliary-app/build.gradle.kts`, `apps/auxiliary-app/global/build.gradle.kts` |
| `final-project-infrastructure.md`는 아직 `notification-app`(폐기 ND-903)으로 적혀 있다 | `docs/architecture/final-project-infrastructure.md` 38·39행, Phase 4. decisions.md 11장 |

## 3. 확정 To-Be (ND로 확정, 미구현)

### 3.1 배포 단위와 저장소

| 항목 | 내용 요약 | ND |
| --- | --- | --- |
| 모듈 | auxiliary-app 안 업무 모듈 `notification`(Gradle `:notification`, 패키지 `com.hapbang.auxiliary.notification`). `:auxiliary-global`·`:shared`만 의존 | ND-006 |
| 저장소 | database `auxiliary`, schema `notification`. 엔티티 `@Table(schema = "notification")`. 첫 SQL은 schema 생성 | ND-007 |
| 기록 위치 | Flyway 이력·Modulith 발행 기록은 schema `auxiliary` | ND-007 |
| 경계 | 다른 모듈 schema와 JOIN·FK·같은 트랜잭션 쓰기 금지 | ND-007 |
| 테이블 | `notification`, `notification_payout_item`, `notification_schedule`, `notification_user` 4개 | ND-025 |

### 3.2 처리 규칙

| 항목 | ND |
| --- | --- |
| 입력은 Kafka integration event뿐, 채팅 Redis Streams 구독 안 함 | ND-008, ND-009 |
| 실패 메시지 DLT, 받지 않는 타입은 무시하고 커밋 | ND-010 |
| 받는 사람 확인(알림 회원 복제본, 탈퇴 제외) | ND-018 (정지는 U8) |
| 중복 판정(유일 인덱스, 삭제된 행 포함) | ND-019 (원본 대상 ID는 U5) |
| 문구·값 저장 | ND-020 (템플릿 U3) |
| 발생 시각 정렬 | ND-021 |
| 최신 20개 보존, Soft Delete | ND-022 (묶음과 겹침 U9) |
| 읽음은 누를 때, 전체 읽음 | ND-023 |
| 대상 삭제 시 알림 유지 | ND-024 |
| API 6개 | ND-026, 상세는 [API 문서](../api/notification.md) |
| commit 뒤 Redis Pub/Sub → STOMP, REST로 재동기화 | ND-027 |
| 채팅 코드 복사(연결 티켓, Pub/Sub, Kafka·DLT, 유일 인덱스) | ND-028 |
| 예약 알림·예약 배치 | ND-029~031 (운영 시간 U6) |
| 하루 묶음 | ND-032 |

### 3.3 처리 흐름 (설계, 미구현)

```text
발행 앱 ──Kafka integration event──▶ notification Kafka Listener        (ND-008, group 이름 U1)
                                        │ 처리 실패 → <topic>.<group>.DLT  (ND-010)
                                        ▼
                      ┌─ 알림 회원 복제본으로 받는 사람 확인 ─┐              (ND-018)
                      │  종류 결정 · 중복 판정(유일 인덱스)   │              (ND-012, ND-019)
                      │  [로컬 트랜잭션]                      │
                      │   알림 저장 / 예약 저장 / 묶음 갱신   │              (ND-020, ND-029, ND-032)
                      │   21번째부터 Soft Delete              │              (ND-022)
                      └──────────────┬──────────────────────┘
                                     │ commit 뒤
                                     ▼
                              Redis Pub/Sub 발행                           (ND-027)
                                     ▼
                     notification STOMP ─▶ /user/queue/notifications ─▶ FE
                                                                     │ 유실·재접속
                                                                     ▼
                                          REST GET /api/v1/notifications (DB가 원천)

예약 배치(1분마다) ─▶ 보낼 시각 지난 예약 ─▶ 상태 재확인 ─▶ 위 저장 단계와 같이 저장  (ND-029)
```

### 3.4 트랜잭션 경계

- 한 이벤트의 알림 저장, 20개 초과분 Soft Delete, 예약 저장·취소, 묶음 갱신은 `notification` schema 안의 로컬 트랜잭션이다(ND-007, ND-022, ND-032).
- 실시간 전달(Redis Pub/Sub 발행)은 commit 뒤에 한다. 실시간 전달 실패는 저장 결과를 되돌리지 않고, FE는 REST로 맞춘다(ND-027).
- 같은 앱의 다른 모듈(chatbot 등)과 트랜잭션을 나누지 않는다(ND-007). 다른 앱 database에는 연결하지 않는다(루트 AGENTS 3장).
- Kafka offset 커밋과 DB 트랜잭션의 순서, 리스너 트랜잭션 설정은 구현 때 [kafka.md](../architecture/infrastructure/kafka.md) "소비"·"재시도와 DLT"에 맞춰 정한다. 같은 이벤트 재수신은 유일 인덱스로 흡수한다(ND-019).

### 3.5 다른 앱 연동

- **입력**: 받는 토픽·이벤트 목록과 상태는 [kafka.md](../architecture/infrastructure/kafka.md) "토픽 목록"과 [알림 종류](notification-catalog.md)를 따른다. 새 이벤트 요청은 각 발행 모듈과 계약 합의 전이다(decisions.md 8장). 기존 이벤트의 한계와 발행 앱과 정할 계약은 [5장](#5-다른-앱-이벤트-계약-2026-11-06-조사)에 있다.
- **출력**: 다른 앱으로 내보내는 이벤트나 내부 HTTP API는 없다. 발행 모듈은 알림을 모른다(ND-008).
- **회원 정보**: 회원 이벤트로 만든 복제본만 쓴다(ND-018). 회원 앱을 동기 호출하지 않는다. 복제본이 없는 받는 사람과 첫 배포 Backfill은 미결이다(U17, [5.4](#54-회원-복제본-첫-배포-backfill-미결-u17)).
- **같은 앱의 다른 모듈**: 연동이 필요하면 Spring 이벤트로만 한다(auxiliary README "모듈 사이 이벤트"). 지금 설계에는 연동이 없다.

### 3.6 인증·권한

- 사용자 인증은 Gateway가 JWT를 검증해 넘기는 사용자 헤더(`X-User-Id`, `X-User-Role`)로 한다(루트 AGENTS 2장, `GatewayUserHeaderFilter`).
- auxiliary global `SecurityConfig`가 업무 API 전부에 로그인을 요구한다. 모듈이 공개 경로를 따로 열지 않는다(auxiliary README "권한").
- 역할 검사는 모듈 Facade의 `@PreAuthorize`로 한다(auxiliary README "권한"). 받는 사람은 유저·인플루언서·인증된 인플루언서다(ND-003). 모든 API는 본인 알림만 다룬다(ND-026).
- WebSocket 연결은 연결 티켓으로 인증한다(ND-028). 토큰 만료 헤더는 U15.

### 3.7 실패와 재시도

- Kafka 재시도·DLT 이름·DLT 선언·재처리 runbook은 [kafka.md](../architecture/infrastructure/kafka.md) "재시도와 DLT"·"DLT 재처리 runbook"을 따른다(ND-010).
- DLT 재처리로 같은 메시지가 다시 와도 유일 인덱스로 한 번만 만든다(ND-019).
- 실시간 전달 유실은 재시도하지 않고 REST 재동기화로 맞춘다(ND-027).

## 4. 제안 To-Be (미채택)

> **미채택.** 아래 구조는 이 문서의 제안일 뿐 결정이 아니다. 구현 때 auxiliary README의 "모듈 추가 방법", `chatbot` 모듈 구조, 팀 컨벤션(루트 AGENTS 11장: 패키지 구조는 미정)과 비교해 정한다.

### 4.1 패키지 구조 (제안)

| 계층 | 담을 것 (제안) |
| --- | --- |
| `in` | Kafka Listener(토픽별), REST Controller, STOMP 연결·연결 티켓 |
| `app` | 생성(받는 사람·종류·문구 결정, 중복), 보존(20개), 읽음, 예약(배치), 하루 묶음 |
| `domain` | `Notification`, `NotificationSchedule`, `NotificationPayoutItem`, `NotificationUser` |
| `out` | Repository, Redis Publisher |

### 4.2 구현 때 할 일 (auxiliary README "모듈 추가 방법" 7단계 참조)

README의 1~7단계를 그대로 따른다. 이 모듈에 해당하는 점만 적는다.

| 단계 | 이 모듈에서 확인할 것 |
| --- | --- |
| 1. `auxiliaryModules`에 등록 | `"notification"` 추가 (As-Is: 미등록) |
| 2. 모듈 `build.gradle.kts` | WebSocket 등 필요한 라이브러리만. 라이브러리 추가는 `docs/dev/local.md` 확인 |
| 3. `:auxiliary-app` 의존 추가 | |
| 4. `package-info.java` `@ApplicationModule` | |
| 5. 첫 SQL | ND-007 |
| 6. `gateway.yaml` 경로 추가 | REST와 WebSocket 경로, 시간 제한(U14), 토큰 만료 헤더(U15) |
| 7. `verifyModuleStructure :auxiliary-app:check` | `AuxiliaryModularityTest`가 새 모듈을 찾는지 |

## 5. 다른 앱 이벤트 계약 (2026-11-06 조사)

행별 판정(충족·부분 충족·미충족)과 근거는 [알림 종류](notification-catalog.md) 각 표의 "2026-11-06 판정"이 원본이다. 여기서는 모듈 설계에 걸리는 요약만 둔다. 코드 경로는 `dev` 기준이다.

### 5.1 As-Is: 발행 방식 공통

| 사실 | 근거 |
| --- | --- |
| 각 앱은 Modulith outbox + shared 레코드 `@Externalized`로 Kafka에 발행하고, 헤더 `eventType`·`eventVersion=1`을 붙이며, 앱별 재발행 배치가 있다 | [kafka.md](../architecture/infrastructure/kafka.md) "발행", 각 앱 외부화 설정 |
| 이벤트 ID가 없다. 받는 쪽은 (eventType, 대상 ID)로 중복을 거른다 | kafka.md "소비" |
| customer-support-app은 `lecture.events.v1`의 모르는 타입을 무시해 새 강의 이벤트가 생겨도 DLT로 가지 않는다 | `apps/customer-support-app/src/main/java/com/hapbang/customersupport/in/LectureEventTypeResolver.java` |

### 5.2 As-Is: 기존 이벤트의 한계

| 묶음 | 지금 상태 | 근거 |
| --- | --- | --- |
| M1 출연 취소 요청 3종 | shared 레코드가 있고 JVM 안에서는 발행되지만 `@Externalized`가 없어 Kafka로 나가지 않는다(🟡 유지) | `apps/appearance-app/src/main/java/com/hapbang/appearance/app/AppearanceExternalEvents.java` 12행 근처, 발행 `AppearanceCancelRequestUseCase`·`AppearanceCancelRespondUseCase`·`AppearanceCancelAutoRejectUseCase` |
| M2 이의 제기 4종 | (commandId?, inquiryId, orderItemId[, accepted])만 있다. `buyerUserId`·`sellerUserId`·제목이 없다(⚠️ 유지) | `common/shared` 강의 이벤트 레코드 |
| M3 출연 🆕 12종 | 같은 이름 레코드 없음. 대체 후보 `AppearanceChangedEvent`(#713)는 `AppearanceSyncDto`(id, hostId, guestId, result, scheduledAt, version)만 담고, 상태 6개를 결과 4개로 줄이며, 순서를 보장하지 않는다. 요청/지원 수락, 일방/합의 취소, 호스트/자동 확인, 성립/취소 요청을 구분하지 못하고 요청서·지원·모집글 단계 전이는 다루지 않는다. **M3 전체를 대체할 수 없다**. 부분 충족 5종(요청 수락·지원 수락·일방 취소·자동 확인·불참), 미충족 7종 | `common/shared/src/main/java/com/hapbang/shared/appearance/dto/AppearanceSyncDto.java`, `apps/appearance-app/src/main/java/com/hapbang/appearance/domain/AppearanceStatus.java` 34~40행, [internal-appearance-sync.md](../api/internal-appearance-sync.md) 4장 |
| M4 강의 🆕 7종 | 레코드 없음. 대체 후보 #712 `LectureChangedEvent`(제목·`sellerUserId` 있음, 검수 상태·반려 사유·발생 시각 없음, `onSale`만), `LecturePurchaseChangedEvent`(`buyerUserId`·`purchasedAt`, 결과 `OWNED`·`FAILED`·`CANCELED`, 제목·금액 없음, 항목 단위). 구매 확정·지급 전이는 동기화 이벤트도 없다. 부분 충족 4종(검수 승인·구매 완료·결제 실패·구매 취소), 미충족 3종(검수 반려·자동 구매 확정·이의 제기 접수) | `apps/lecture-app/src/main/java/com/hapbang/lecture/app/LectureSyncEvents.java` 59행 근처, `apps/lecture-app/src/main/java/com/hapbang/lecture/app/OrderObjectionFileUseCase.java` |
| M5 지갑 🆕 4종 | 충전 실패·환불 실패·환불 운영자 확인·환전 승인 모두 발행 없음(미충족). 기존 7종이 같은 방식의 본보기 | `apps/billing-app/src/main/java/com/hapbang/billing/app/BillingEventPublisher.java` 39~81행, `ChargeProcessing`·`RefundProcessing`·`ExchangeReviewUseCase` |
| M6 회원 🆕 2종 | 채널 인증 반려·승급 반려 발행 없음(미충족). 승인 2종은 `UserModifiedEvent`(`UserDto`: id, nickname, role, status, version, 이전 역할 없음) + 복제본 비교. 승급 승인은 판별 가능, 채널 인증 승인은 U18 | `apps/member-app/src/main/java/com/hapbang/member/app/MemberChannelReviewUseCase.java`, `apps/member-app/src/main/java/com/hapbang/member/app/MemberUpgradeReviewUseCase.java` |
| M7 고객센터 🆕 3종 | 답변 등록·답변 수정·접수 불가 발행 없음(미충족). 재시도 배치 경로는 `delete=false`로 식별 가능 | `apps/customer-support-app/src/main/java/com/hapbang/customersupport/app/InquiryUseCase.java`, `apps/customer-support-app/src/main/java/com/hapbang/customersupport/app/LectureObjectionFiler.java` |

### 5.3 발행 앱과 정할 계약 (합의 전)

> **미결.** 아래는 조사로 드러난 필요 작업이다. 이벤트 이름·값은 N 7장·decisions.md 8장 요청 그대로이며 발행 모듈과 합의(N0) 전이다. 알림 쪽에서 이벤트·DTO를 만들지 않는다.

공통(새 이벤트마다): (1) shared 레코드에 `@Externalized` (2) 발행 앱 외부화 설정에 eventType 헤더 (3) [kafka.md](../architecture/infrastructure/kafka.md) 토픽 표 갱신.

| 묶음 | 발행 앱 | 필요 작업 |
| --- | --- | --- |
| M1 | appearance-app | 취소 요청 3종 Kafka 외부화(필드는 있음) |
| M2 | lecture-app | 이의 제기 4종에 `buyerUserId`·`sellerUserId`·강의 제목 추가 |
| M3 | appearance-app | 출연 🆕 이벤트 신설. `AppearanceChangedEvent`로 대신할지는 사건 구분 불가로 어렵다. 출연 전 단계 8종은 발행 지점 자체가 없다(`AppearanceRequestRejectUseCase`, `AppearanceRequestCancelUseCase`, `AppearanceRequestExpireUseCase`, `AppearanceApplicationRespondUseCase.reject`, `AppearanceRecruitHoldUseCase.markRecruiting`, `AppearanceRecruitCloseUseCase`, `AppearanceRecruitDeadlineCloseUseCase`, `AppearanceRecruitCloseRecorder`, `AppearanceRecruitCancelUseCase`) |
| M4 | lecture-app | 강의 🆕 이벤트 신설 또는 #712 동기화 이벤트에 사건 구분·필드 추가(어느 쪽인지 미정). 구매 확정은 예약 취소에도 필요 |
| M5 | billing-app | 🆕 4종을 `BillingEventPublisher`와 같은 방식으로 발행 |
| M6 | member-app | 반려 2종 신설. 채널 인증 승인 사건 이벤트 신설 여부는 U18 |
| M7 | customer-support-app | 답변 등록·수정, 재시도 배치 접수 불가 이벤트 신설 |

### 5.4 회원 복제본 첫 배포 Backfill (미결, U17)

> **미결.** ND-018은 복제본으로 탈퇴 여부를 확인한다고만 정했다. Kafka 보존(72시간)보다 먼저 가입한 회원은 `UserJoinedEvent`가 토픽에 없어 복제본이 생기지 않는다(U7과 관련).

- 검토 선택지(decisions.md U17): (A) 첫 배포 Backfill + 이후 경쟁은 블로킹 재시도·DLT(조사 추천) / (B) 재시도·DLT만 / (C) 복제본 없이 생성 / (D) 보류 테이블. 확정하지 않았다.
- 선례(알림 결정 아님): 지갑은 첫 배포 때 회원 내부 API로 Backfill했다([지갑 결정 기록](../billing/decisions.md) BD-132·BD-133, [internal-members.md](../api/internal-members.md)). 이 선례를 쓰면 회원 내부 API 동기 호출이 생겨 3.5 "회원 앱을 동기 호출하지 않는다"와 맞춰야 하므로 회원 담당과 함께 정한다.
- 관련 테스트: `NT-USR-04`, `NT-USR-06`([test-spec.md](test-spec.md)).

## 6. 미결 설계

본문은 [decisions.md](decisions.md) 10장에 있다. 여기서는 이 모듈 설계에 걸리는 항목만 가리킨다.

| ID | 걸리는 설계 | 막는 작업 |
| --- | --- | --- |
| U1 / T2 | consumer group 이름 | Kafka Listener·DLT 이름 |
| U5 | 이벤트별 원본 대상 ID | `notification` 유일 인덱스 값 |
| U6 / T5 | EC2 운영 시간과 예약 배치 | 예약 배치 |
| U7 / T5 | 첫 배포 때 과거 이벤트 처리 | 첫 배포 |
| U9 | 하루 묶음과 20개 보존 겹침 | 하루 묶음 |
| U13 | Hikari·스케줄 스레드·메모리 예산(챗봇과 공유) | 모듈 추가 PR |
| U14 | WebSocket 경로 Gateway 시간 제한 | Envoy route |
| U15 | `X-User-Token-Exp` 헤더 | 연결 티켓·STOMP |
| U3, U4 | 문구 템플릿, 지급 알림 제목 | 문구 생성 |
| U8 | 정지 회원 | 받는 사람 확인 |
| U2 / T3 | P0 완료 기준 | 작업 계획 |
| U10, U11 / T4, U12 | 출연·채팅 이벤트 계약, 종류 코드, 채팅과 동시 표시 | 해당 이벤트 소비 |
| T8 | chat-app 불변식과 ND-004의 방향 차이 | 채팅 소유, 이 모듈 구조에는 영향 없음 |
| U16 | 예약 취소 이벤트가 생성 이벤트보다 먼저 도착할 때(ND-029, `NT-SCH-06`) | `notification_schedule` 상태·유일 키, 예약 배치 |
| U17 | 받는 사람 복제본 없음, 첫 배포 Backfill 검토(ND-018, `NT-USR-04`·`NT-USR-06`, 5.4) | 회원 복제본·Kafka 소비, 첫 배포 |
| U18 | ND-016과 회원 코드 불일치(관리자 인플루언서 지정, 이미 인플루언서인 회원의 채널 승인). 회원+알림 담당 협의 | 회원 이벤트 소비(채널 인증 승인) |
| U19 | 저장한 알림 문구·값의 다른 회원 닉네임과 `policy.md` 7장 파기 규칙(ND-018·ND-020, `NT-USR-07`) | 문구 생성·저장(문구를 저장할지 조회 때 만들지), `UserMaskedEvent` 소비 |

## 7. 갱신 이력

| 날짜 | 내용 |
| --- | --- |
| 2026-11-06 | 처음 작성(e054d6f6 기준). As-Is는 코드·설정 파일로 확인 |
| 2026-11-06 | 5장 신설: 발행 앱 코드(dev) 조사로 기존 이벤트 한계(M1~M7), 발행 앱과 정할 계약(합의 전), 회원 복제본 첫 배포 Backfill(U17, 미결)을 적었다. 미결 설계 표에 U16·U17·U18 추가. 결정은 바꾸지 않았다 |
| 2026-11-06 | 미결 설계 표에 U19(닉네임 파기)를 더했다 |
