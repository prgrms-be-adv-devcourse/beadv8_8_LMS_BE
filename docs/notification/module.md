# 알림(notification) 모듈 — 설계·구현 구조

> 문서 탐색: [알림 문서 INDEX](INDEX.md)

> 문서 지도: [도메인 개념](CONTEXT.md) · **[설계·구현 구조](module.md)** · [결정 기록](decisions.md) · [알림 종류](notification-catalog.md) · [API](../api/notification.md) · [테스트 검증 기준](test-spec.md) · [회귀 실행 기록](regression-log.md). 문서 목록은 [INDEX](INDEX.md).
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

## 2. As-Is (기준: dev `35735bf1`(N2 PR #819, N3 PR #834, N4 PR #853 병합) + N5 #859 브랜치 `feature/tkt-859`(PR 리뷰 중, dev 병합 전))

| 사실 | 근거 |
| --- | --- |
| `auxiliary-app`이 있다. 실행 진입점 `:auxiliary-app`, 공통 `:auxiliary-global`, 업무 모듈 디렉터리 `boundedContext/` | `apps/auxiliary-app/`, `apps/auxiliary-app/README.md` "구조" |
| 업무 모듈은 `chatbot`·`notification`·`search` 셋이다 | `apps/auxiliary-app/boundedContext/*/src/main/java/com/hapbang/auxiliary/*/package-info.java` |
| Gradle 등록: `val auxiliaryModules = listOf("chatbot", "notification", "search")`. `:auxiliary-app`이 업무 모듈을 의존한다 | `settings.gradle.kts` 22행, `apps/auxiliary-app/build.gradle.kts` |
| `:notification`은 `:auxiliary-global`과 `spring-boot-starter-kafka`만 의존한다. 업무 모듈은 `:kafka` 프로젝트를 의존할 수 없어 starter를 직접 쓰고, common/kafka의 처리 로그 인터셉터는 복사했다 | `boundedContext/notification/build.gradle.kts`, `.../in/event/kafka/EventLogContextRecordInterceptor.java` |
| 모듈 `@ApplicationModule(displayName = "Notification", allowedDependencies = "global")` | `.../notification/package-info.java` |
| Flyway `V202611091500__create_notification_schema.sql`: schema `notification`, 테이블 `notification.notification_user` 하나(ND-046). 나머지 테이블 셋은 없다 | `boundedContext/notification/src/main/resources/db/migration/` |
| 회원 복제본: `NotificationUser`(shared `ReplicaUser` 상속), `NotificationUserSyncService`(가입은 없으면 생성·있으면 건너뜀, 변경은 없으면 그 정보로 생성(ND-049)·있으면 더 큰 버전만, 복제할 수 없는 정보는 `INVALID_MEMBER_EVENT`로 재시도 없이 DLT) | `.../domain/NotificationUser.java`, `.../app/NotificationUserSyncService.java` |
| 회원 이벤트 소비자 `NotificationMemberEventListener`: 토픽 `member.events.v1`, 그룹 `auxiliary-app.notification-member-replica`(ND-047), `UserJoinedEvent`·`UserModifiedEvent`(복제본 + 가입 환영·승급 승인, 아래 N5 행)와 `UserMaskedEvent`(파기 닉네임, ND-051), N7 회원 3종(`InfluencerChannelApprovedEvent`·`InfluencerChannelRejectedEvent`·`InfluencerUpgradeRequestRejectedEvent`, 알림만, 아래 N7 회원 행)을 처리하고 그 밖 종류·tombstone은 건너뛴다. Backfill을 기다리지 않는다(ND-080). `hapbang.notification.member-events.enabled`(기본 켜짐)로 켜고 끈다 | `.../in/event/NotificationMemberEventListener.java` |
| 알림 생성(N5, #859): 초안 `AlertDrafts`(11종의 받는 사람·원본 대상 ID·이동 대상·값, 발생 시각은 이벤트 사건 시각이고 출연 요청 도착·회원 이벤트는 Kafka 레코드 timestamp, ND-073·ND-074·ND-081), 문구 `AlertTemplate`(enum 11개, 카탈로그 0.5, `NotificationTemplates`가 빈 템플릿과 함께 등록, ND-075), 저장 전 `AlertStartPolicy`(`hapbang.notification.alert.start-at` 필수, 이른 사건은 커밋만, ND-076). `NotificationAlertService`가 출연 2종은 받는 사람이 받을 수 있는 회원인지 먼저 보고, 언급 회원 닉네임을 복제본에서 읽어(없으면 재시도 가능한 `MENTIONED_NOT_REPLICATED`, 재시도 뒤 DLT, ND-091) 저장 서비스에 넘긴다. 강의 원천 지급은 하루 묶음(N8)으로 넘긴다(ND-013), 지급 제목은 없다(ND-070) | `.../app/AlertDrafts.java`, `AlertTemplate.java`, `AlertStartPolicy.java`, `NotificationAlertService.java` |
| 출연·지갑 소비자(N5): `NotificationAppearanceEventListener`(`appearance.events.v1`, 그룹 `auxiliary-app.notification-appearance-alert`, 출연 요청 도착·모집 지원 도착), `NotificationBillingEventListener`(`billing.events.v1`, `auxiliary-app.notification-billing-alert`, 지갑 7종). 그 밖 종류는 건너뛴다. DLT·재시도는 위 공통 설정, 컨테이너는 `autoStartup=false`. `hapbang.notification.{appearance,billing}-events.enabled`(기본 켜짐) | `.../in/event/Notification{Appearance,Billing}EventListener.java`, `.../in/event/kafka/NotificationKafkaConfig.java` |
| 회원 알림(N5): `NotificationMemberAlertService`가 한 트랜잭션에서 반영 전 역할을 읽고 → 복제본 반영 → 가입 환영(항상, 받는 사람 정책·시작 시각으로 거름)·승급 승인(반영됨 + 이전 인플루언서 + 새 인증된 인플루언서, 원본 대상 `<회원 ID>:<버전>`)을 저장한다(ND-071·ND-080) | `.../app/NotificationMemberAlertService.java` |
| 회원 복제본 Backfill(N5): `NotificationUserBackfillService`가 `NotificationMemberSource`(운영 `MemberApiNotificationMemberSource` → `MemberApiClient.getMembers`, 회원 API 주소가 있을 때만)를 키셋으로 마지막 페이지까지 읽고 회원마다 `upsertFromBackfill`(native `INSERT … ON CONFLICT DO UPDATE … WHERE masked_at IS NULL AND 버전 규칙`, 낙관적 락 버전 +1)로 반영한다. 복제할 수 없는 회원은 건너뜀(ID 기록), 장애는 예외(완료 아님). global `JobLock`(`NotificationJob.USER_BACKFILL` 859001)으로 하나만 돈다(못 잡으면 `BACKFILL_RUNNING` 409). 페이지 크기 `hapbang.notification.user-backfill.page-size`(기본 500). 지표 `hapbang.notification.user.backfill.runs{outcome}`·`.members{result}` (ND-077~ND-079) | `.../app/NotificationUserBackfillService.java`, `.../out/NotificationUserRepository.java`, `.../out/MemberApiNotificationMemberSource.java` |
| 시작 순서(N5): `NotificationAlertListenerStarter`가 `ApplicationReadyEvent` 뒤 가상 스레드에서 Backfill을 돌리고 성공하면 출연·지갑·강의·채팅 컨테이너를 시작한다. 실패·잠금 경합은 백오프(기본 5초에서 두 배씩 상한 5분) 뒤 다시 시도한다. 처음 시도부터 `stall-alert-after`(기본 30분)가 지나도 마치지 못하면 `[NOTIFICATION_BACKFILL_STALLED]` ERROR를 남긴다. 대기 게이지 `hapbang.notification.alert.listeners.waiting`(pod마다 1이면 대기, 시작하면 0)과 공용 alert `hapbang-notification-backfill-waiting`(앱별 max가 30분 넘게 1, critical)이 있다(ND-093). `hapbang.notification.user-backfill.on-startup=false`면 바로 시작한다(모듈 테스트) | `.../in/event/NotificationAlertListenerStarter.java` |
| 관리자 API(N5): `POST /api/v1/notifications/admin/user-replica/backfill`(202 `BackfillRun`), `GET …/backfill/{runId}`, `NotificationAdminFacade` `@PreAuthorize("hasRole('ADMIN')")` + global `SecurityConfig` `/api/v1/*/admin/**` ADMIN. 비동기(ND-092): 요청 스레드에서 `JobLock` 잡기(409) → `RedisBackfillRunStore`에 RUNNING(실패하면 잠금 풀고 503 `BACKFILL_STATUS_UNAVAILABLE`) → 가상 스레드에서 `runLocked`(페이지마다 갱신 시각) → COMPLETED(결과)·FAILED(`BACKFILL_FAILED`) 저장 → 잠금 해제. 조회는 RUNNING인데 5분 넘게 갱신이 없으면 UNKNOWN, 없으면 404 `BACKFILL_RUN_NOT_FOUND`. Redis 키 `<접두어>:backfill:run:<id>`, TTL 7일 | `.../in/ApiV1NotificationAdminController.java` |
| 알림 시작 시각(ND-095): `AlertStartPolicy`가 빈을 만들 때(Kafka 소비자 시작 전) 정한다. 설정 `hapbang.notification.alert.start-at`이 있으면 그 값(`OffsetDateTime`으로 직접 읽고, 잘못되면 기동 실패), 없으면 `JdbcAlertStartStore`가 `notification.notification_runtime_setting`(Flyway `V202611111530`)에 키 `alert.start-at`으로 지금 시각을 `ON CONFLICT DO NOTHING` 기록한 뒤 다시 읽은 값. 운영 `application-prod.yaml`에는 값이 없고, `application-local.yaml`·모듈 테스트는 2000-01-01 | `.../app/AlertStartPolicy.java`, `.../out/JdbcAlertStartStore.java` | `apps/auxiliary-app/src/main/resources/application-{prod,local}.yaml` |
| Kafka 소비·DLT 공통 설정(채팅 복사, ND-028): 블로킹 재시도 3회(1·2·4초, 상한 10초) 뒤 `<토픽>.<그룹>.DLT`, 역직렬화·검증 실패는 재시도 없이 DLT, DLT는 원본과 같은 partition 번호·3개·7일 보존으로 모듈이 선언, AckMode.RECORD, 동시성 1(U13 제안), `auto-offset-reset=earliest` | `.../in/event/kafka/NotificationKafka{Constants,Config,ErrorHandlerConfig,TopicConfig}.java` |
| Flyway `V202611101000__create_notification_table.sql`(N2): `notification.notification`(ND-025 칸 + 언급 회원 ID `bigint[]`, 발생·읽은·삭제 시각 TIMESTAMPTZ). 유일 인덱스 (원본 이벤트 종류, 원본 대상 ID, 받는 사람)는 삭제된 행 포함, 목록·보존용 (받는 사람, 발생 시각 desc, id desc) 삭제되지 않은 행, GIN(언급 회원 ID) | `boundedContext/notification/src/main/resources/db/migration/` |
| Flyway `V202611120950__create_notification_payout_item.sql`(N8, ND-103): `notification.notification_payout_item`(PK 지급 건 ID, 묶음 알림 ID FK, 받는 사람, 지급 HP CHECK >= 0, 지급 시각)과 `notification`의 묶음 유일 인덱스 `uk_notification_bundle`(종류, 받는 사람, 묶음 키; `bundle_key IS NOT NULL`만 조건이라 삭제된 행 포함) | `boundedContext/notification/src/main/resources/db/migration/` |
| 하루 묶음(N8 #890, ND-032·ND-097·ND-101~ND-103): 지갑 리스너가 강의 원천 `PayoutCompletedEvent`를 `DailyPayout`(묶음 키 = `paidAt`의 KST 날짜 `yyyy-MM-dd`)으로 바꿔 `NotificationAlertService.dailyPayout`에 넘긴다(시작 시각 전이면 커밋만, ND-076). `NotificationWriter.addDailyPayout`이 한 트랜잭션에서 받는 사람 행 잠금(ND-053) → 받는 사람 확인 → 같은 지급이면 `DUPLICATE`(묶음 불변) → 그 날짜 묶음(삭제된 행 포함)이 없으면 만들고(`Notification.createBundle`, 원본 대상 ID·이동 대상 없음) 있으면 받는 사람의 보이는 알림과 묶음 행을 한 번에 ID 오름차순으로 잠근 뒤(밀려날 행이 이 안에 있어 전체 읽음과 잠금 순서가 같다) 칸만 바꾸는 UPDATE로 합계·건수·문구·발생 시각(늦은 값)·안 읽음·되살림(`UPDATED`) → 지급 기록(`JdbcPayoutItemStore`) → 21번째부터 Soft Delete → commit 뒤 실시간 전달. 문구는 `PayoutDailyTemplate`(값 `v`·`date`·`totalPaidAmountHp`·`count`, ND-100) | `.../app/DailyPayout.java`, `.../app/PayoutDailyTemplate.java`, `.../app/NotificationWriter.java`, `.../app/PayoutItemStore.java`, `.../out/JdbcPayoutItemStore.java`, `.../out/NotificationRepository.java`, `.../in/event/NotificationBillingEventListener.java` |
| 알림 저장 `NotificationWriter`(N2): 받는 사람 복제본 행 `SELECT … FOR UPDATE`(ND-053) → 복제본 없으면 `RECEIVER_NOT_REPLICATED`(ND-035) → `ReceiverPolicy`(탈퇴·정지·관리자 제외, ND-018·ND-072·ND-003. 결과 `SKIPPED_RECEIVER`) → 같은 사건이 있으면(삭제된 행 포함) `DUPLICATE` → 종류 템플릿으로 문구를 만들어 저장 → 같은 트랜잭션에서 21번째부터 Soft Delete. 종류별 템플릿(`NotificationTemplate`)은 운영 코드에 아직 없다(ND-050) | `.../app/NotificationWriter.java`, `.../app/ReceiverPolicy.java`, `.../app/NotificationTemplates.java` |
| N6 변환·문구(#862): `AppearanceCancelRequestAlerts`·`LectureObjectionAlerts`가 출연 취소 요청 3종·이의 제기 결과 4종을 알림 초안으로 바꾼다(받는 사람, 원본 대상 ID ND-083, 이동 대상 ND-084, 발생 시각은 사건 시각, 필수 값이 비면 `IllegalArgumentException`). `AppearanceCancelRequestTemplates`·`LectureObjectionTemplates`(enum)가 값만으로 문구를 만들고(ND-085, 값 `v` 1, 취소 요청 도착은 게스트 `nicknames`) `NotificationTemplates`가 N5 템플릿과 함께 등록한다. `ObjectionLegacyFormat`이 원본 JSON의 #818 추가 필드 키로 이전 형식·판별 불가·지금 형식을 나눈다(ND-086) | `.../app/{AppearanceCancelRequest,LectureObjection}{Alerts,Templates}.java`, `.../app/NotificationTemplates.java`, `.../in/event/kafka/ObjectionLegacyFormat.java` |
| N6 소비자(#862, ND-087·ND-096): 출연 취소 요청 3종은 `NotificationAppearanceEventListener`(N5 그룹 `auxiliary-app.notification-appearance-alert`)의 처리기다. 취소 요청 도착은 `NotificationAlertService.cancelRequested`가 시작 시각·받는 사람을 먼저 본 뒤 게스트 닉네임을 읽고, 수락·거절은 `create(draft)`다. 초안으로 바꿀 때 필수 값이 비면 `RejectedEventException`(재시도 없이 DLT, 저장 단계 오류는 그대로 재시도). 이의 제기 결과 4종은 `NotificationLectureEventListener`(`lecture.events.v1`, 그룹 `auxiliary-app.notification-lecture-alert`, DLT `<토픽>.<그룹>.DLT`)가 값을 바이트로 받아 `__TypeId__`가 없거나 신뢰 패키지(`com.hapbang.shared.lecture.event`) 밖이면 DLT, 패키지 안 다른 종류는 건너뛰고, 4종은 `ObjectionLegacyFormat`으로 이전 형식(로그는 종류마다 1·2·4…번째만 INFO, 지표 `hapbang.notification.alert.legacy.skipped` 뒤 커밋)·판별 불가(값 `null`, 일부 키 없음, JSON 객체가 아닌 본문 → DLT)·지금 형식(Jackson으로 레코드로 바꿔 `create`)을 나눈다. 강의 컨테이너도 `NotificationAlertListenerStarter`가 첫 Backfill 뒤에 시작한다 | `.../in/event/NotificationAppearanceEventListener.java`, `.../in/event/NotificationLectureEventListener.java`, `.../in/event/NotificationAlertListenerStarter.java`, `.../in/event/kafka/NotificationKafka{Config,ErrorHandlerConfig,TopicConfig,Constants}.java`, `.../app/NotificationAlertService.java` |
| N7 지갑 4종(#907, ND-105~ND-108): `WalletAlerts`가 충전 실패·환불 실패·환불 운영자 확인 필요·환전 승인을 알림 초안으로 바꾼다(받는 사람 `userId`, 원본 대상과 이동 대상이 같은 자원 ID `CHARGE`·`REFUND_REQUEST`·`EXCHANGE`, 발생 시각은 사건 시각, 필수 값이 비면 `IllegalArgumentException`). 값에는 `v`와 문구에 쓰는 값만 있다(충전 실패의 결제 금액은 저장하지 않는다, ND-109). 문구는 `WalletAlertTemplates`(enum 4개, 카탈로그 0.7, 환전 승인의 송금 예정일은 값의 `payoutScheduledAt`(UTC ISO 문자열)을 KST `M월 d일`로)이고 `NotificationTemplates`가 등록한다. 소비는 N5의 `NotificationBillingEventListener`(그룹 `auxiliary-app.notification-billing-alert`)에 처리기 4개를 더했고, 변환의 검증 실패는 `RejectedEventException`(재시도 없이 DLT)으로 바꾼 뒤 `NotificationAlertService.create`로 넘긴다(시작 시각·받는 사람 정책·멱등은 N5와 같다) | `.../app/WalletAlerts.java`, `.../app/WalletAlertTemplates.java`, `.../app/NotificationTemplates.java`, `.../in/event/NotificationBillingEventListener.java` |
| N7 출연 10종(#908, ND-110~ND-113): `AppearanceProgressAlerts`가 M3 이벤트 10종을 알림 초안으로 바꾼다. 원본 대상 ID(ND-111)와 이동 대상(ND-112)은 따로 계산한다(값은 catalog 1장 각 행). 보내지 않는 경우(catalog 1장)는 빈 `Optional`이고, 요청 만료는 호스트용·게스트용 두 초안이다. 필수 값이 비면 `IllegalArgumentException`. `AppearanceProgressTemplates`(enum, `v`=1)가 값만으로 문구(catalog 0.8)를 만들고 `NotificationTemplates`에 등록된다. 소비는 `NotificationAppearanceEventListener`(N5 그룹)의 처리기 10개다. 행동한 상대가 있는 6종은 `NotificationAlertService.createMentioning`이 시작 시각·받는 사람을 먼저 본 뒤 상대 닉네임을 읽고, 나머지는 `create(draft)`다. 변환 검증 실패는 `RejectedEventException`(재시도 없이 DLT). 모집 시작·모집 전체 취소는 처리기가 없어 기본 처리기가 넘긴다(ND-041) | `.../app/AppearanceProgressAlerts.java`, `.../app/AppearanceProgressTemplates.java`, `.../app/NotificationAlertService.java`, `.../in/event/NotificationAppearanceEventListener.java` |
| N7 강의(#910, ND-114~ND-117): `LectureProgressAlerts`가 M4 7종(검수 승인·반려, 구매 완료, 결제 실패, 구매 취소, 구매 확정, 이의 제기 접수)을 알림 초안으로 바꾼다(받는 사람, 원본 대상 ID ND-115, 이동 대상 `LECTURE`·`LECTURE_ORDER`·`LECTURE_ORDER_ITEM` ND-116, 발생 시각은 사건 시각, 필수 값이 비면 `IllegalArgumentException`). 구매 확정은 `AUTO`만 초안을 만들고 `DIRECT`·`FIRST_PLAY`는 빈 목록, 이의 제기 접수는 구매자·판매자 2건이다. `LectureProgressTemplates`(enum, 값 `v` 1)가 값만으로 문구를 만들고(ND-117, 주문 2종은 첫 항목 제목과 나머지 수, 구매 취소는 반환 HP 0이면 HP 없는 문구) `NotificationTemplates`가 등록한다. `NotificationLectureEventListener`가 같은 그룹에서 7종을 이전 형식 판별 없이 레코드로 읽어 `create`하고, 변환 검증 실패는 `RejectedEventException`(재시도 없이 DLT)이다 | `.../app/LectureProgress{Alerts,Templates}.java`, `.../app/NotificationTemplates.java`, `.../in/event/NotificationLectureEventListener.java` |
| N7 회원 3종(#915, ND-118~ND-121): `MemberReviewAlerts`가 채널 인증 승인·반려, 승급 반려를 알림 초안으로 바꾼다(받는 사람 `userId`, 원본 대상 ID `<channelId>:<reviewSequence>`·`upgradeRequestId`, 이동 대상 없음, 발생 시각은 사건 시각, 필수 값이 비거나 반려 사유가 공백뿐이면 `IllegalArgumentException`). 값에는 `v`와 반려 2종의 `rejectionReason`만 있다(승인 방식 `autoApproved`는 저장하지 않는다). 문구는 `MemberReviewTemplates`(enum 3개, 카탈로그 0.10)이고 `NotificationTemplates`가 등록한다. 소비는 N5의 `NotificationMemberEventListener`(그룹 `auxiliary-app.notification-member-replica`)에 처리기 3개를 더했다. 복제본은 바꾸지 않고, 변환의 검증 실패는 `RejectedEventException`(재시도 없이 DLT)으로 바꾼 뒤 `NotificationAlertService.create`로 넘긴다(시작 시각·받는 사람 정책·멱등은 N5와 같다). 이 리스너는 Backfill을 기다리지 않아 받는 사람 복제본이 없으면 저장이 실패해 재시도 뒤 DLT로 간다(ND-035) | `.../app/MemberReviewAlerts.java`, `.../app/MemberReviewTemplates.java`, `.../app/NotificationTemplates.java`, `.../in/event/NotificationMemberEventListener.java` |
| N7 고객센터 2종(#918, ND-122~ND-127): `CustomerSupportAlerts`가 문의 답변 도착·이의 제기 접수 불가를 알림 초안으로 바꾼다(받는 사람 `userId`·`buyerUserId`, 원본 대상 ID `replyId`·`inquiryId`, 이동 대상은 둘 다 `INQUIRY`/`inquiryId`, 발생 시각 `answeredAt`·`fileRejectedAt`, 필수 값이 비면 `IllegalArgumentException`. 답변 도착의 제목은 공백뿐이어도 비었다고 보고(`isBlank`), 정상 제목은 자르지 않는다(ND-127)). `CustomerSupportAlertTemplates`(enum, `v`=1, 값은 답변 도착의 `title`뿐)가 문구(catalog 0.11)를 만들고 `NotificationTemplates`에 등록된다. 소비는 새 `NotificationCustomerSupportEventListener`(`customersupport.events.v1`, 그룹 `auxiliary-app.notification-customersupport-alert`, DLT `<토픽>.<그룹>.DLT`, 신뢰 패키지 `com.hapbang.shared.customersupport.event`, `hapbang.notification.customersupport-events.enabled` 기본 켜짐, `autoStartup=false`)다. 처리기 2개는 변환 검증 실패를 `RejectedEventException`(재시도 없이 DLT)으로 바꾼 뒤 `NotificationAlertService.create`로 넘기고, 이의 제기 명령 2종은 기본 처리기가 건너뛴다. `NotificationAlertListenerStarter`가 첫 Backfill 뒤에 시작한다 | `.../app/CustomerSupportAlerts.java`, `.../app/CustomerSupportAlertTemplates.java`, `.../app/NotificationTemplates.java`, `.../in/event/NotificationCustomerSupportEventListener.java`, `.../in/event/NotificationAlertListenerStarter.java`, `.../in/event/kafka/NotificationKafka{Config,ErrorHandlerConfig,TopicConfig,Constants}.java` |
| N9 채팅 단체방 3종(#921, ND-129~ND-132): `GroupRoomAlerts`가 초대·강퇴·폐쇄를 알림 초안으로 바꾼다. 받는 사람은 초대 `invitedUserIds` 각각, 강퇴 `kickedUserId`, 폐쇄 `memberUserIds`에서 `hostUserId`를 뺀 회원이다(호스트만 있으면 빈 목록). 원본 대상 ID는 초대 `<chatRoomId>:<inviteRequestId>`, 강퇴 `<chatRoomId>:<kickedUserId>:<participationVersion>`, 폐쇄 `chatRoomId`(ND-130), 이동 대상은 초대·폐쇄 `CHAT_ROOM`/방 ID, 강퇴 없음(ND-131). 필수 값(방·사건 회차 ID, 제목, 받는 사람, 사건 시각, 폐쇄의 호스트)이 비거나 받는 사람 목록이 비거나 참여 세대가 1보다 작으면 `IllegalArgumentException`. 값은 `v`와 `title`뿐이고 받는 사람마다 따로 만든다. `GroupRoomAlertTemplates`(enum 3개, 카탈로그 0.12)가 문구를 만들고 `NotificationTemplates`가 등록한다. 소비는 `NotificationChatEventListener`(`chat.events.v1`, 그룹 `auxiliary-app.notification-chat-alert`, DLT `<토픽>.<그룹>.DLT`, 신뢰 패키지 `com.hapbang.shared.chat.event`, 컨테이너 `autoStartup=false`로 Backfill 뒤 시작, `hapbang.notification.chat-events.enabled` 기본 켜짐)가 초안마다 `NotificationAlertService.create`를 부른다. 변환 실패는 `RejectedEventException`(재시도 없이 DLT), 저장 단계 오류(복제본 없음 등)는 재시도 뒤 DLT다. 레코드 하나가 처리 단위라 재처리 때 먼저 저장한 받는 사람은 `DUPLICATE`로 건너뛴다 | `.../app/GroupRoomAlert{s,Templates}.java`, `.../in/event/NotificationChatEventListener.java`, `.../in/event/kafka/NotificationKafka{Constants,Config,ErrorHandlerConfig,TopicConfig}.java`, `.../in/event/NotificationAlertListenerStarter.java` |
| N10 예약 테이블(#928): Flyway `V202611131000__create_notification_schedule.sql`이 `notification.notification_schedule`을 만든다(종류, 대상 ID, 받는 사람, 상태 `SCHEDULED`·`SENT`·`CANCELED`·`SKIPPED`, 보낼 시각(취소 먼저 행은 null), 업무 기한 NOT NULL, 값 jsonb, 처리 시각, 실패 횟수 기본 0). 유일 (종류, 대상 ID, 받는 사람), CHECK 상태 값과 "예약이면 보낼 시각·값 있음", 부분 인덱스 발송용(`SCHEDULED`의 보낼 시각)·기한 경과용(`SCHEDULED`의 업무 기한)·정리용(끝 상태의 업무 기한) | `.../db/migration/V202611131000__create_notification_schedule.sql` |
| N10 예약 입력(#928, ND-133·ND-138·ND-143·ND-145·ND-146): `ScheduleInputs`가 출연·강의 이벤트를 `ScheduleCommand`(`Create`·`Cancel`)로 바꾸고 `ScheduleTimes`가 보낼 시각·업무 기한·대체 기한을 KST로 계산한다(`ScheduleKind` 5종, 이동 대상 종류 포함). 출연·강의 리스너는 예약 명령이 있는 레코드를 `ScheduleService.applyWith`로 처리한다: 한 트랜잭션에서 예약 명령을 먼저 쓰고 같은 레코드의 즉시 알림을 나중에 저장한다(예약이 없는 레코드는 이전처럼 알림마다 저장). 모집 시작·모집 전체 취소 처리기를 더했다(알림 없음). 생성은 사건 시각이 알림 시작 시각 전이면 넘기고, 받는 사람 복제본이 없으면 `RECEIVER_NOT_REPLICATED`(재시도 뒤 DLT), 받을 수 없는 회원이면 넘기고, 업무 기한이 지났으면 `SKIPPED` 행, 아니면 `SCHEDULED` 행을 `INSERT … ON CONFLICT DO NOTHING`으로 넣는다. 취소는 받는 사람을 보지 않고 `INSERT … ON CONFLICT DO UPDATE … WHERE status = 'SCHEDULED'`로 "취소" 행을 넣거나 바꾼다. 구매 취소 마감 임박은 구매 취소·확정과 이의 제기 접수(ND-149)가 취소한다. 변환 실패는 `RejectedEventException`(재시도 없이 DLT) | `.../app/Schedule{Inputs,Command,Service,Store}.java`, `.../domain/Schedule{Times,Kind}.java`, `.../out/JdbcScheduleStore.java`, `.../in/event/Notification{Appearance,Lecture}EventListener.java` |
| N10 예약 배치(#928, ND-029·ND-134·ND-135·ND-139·ND-142·ND-144): `NotificationScheduleBatch`(`@ConditionalOnSchedulingEnabled`, cron `hapbang.notification.schedule.cron` 기본 매분 35초 KST)가 `ScheduleDispatcher.runOnce`를 부른다. `JobLock`(`NotificationJob.SCHEDULE_DISPATCH` 928_001)을 못 잡으면 건너뛴다. `runOnce(Clock)`은 회차 시작 시각으로 기한이 지난 `SCHEDULED`를 `batch-size`만큼 한 문장으로 `SKIPPED`로 끝내고(`FOR UPDATE SKIP LOCKED`, 부분 인덱스 `ix_notification_schedule_expiring`. 상태만 바꾸고 외부 효과가 없어 항목별 트랜잭션 규칙의 예외), 보낼 시각이 지났고 기한이 남은 `SCHEDULED`를 보낼 시각 순으로 `batch-size`(기본 100)만큼 읽는다. 예약마다 트랜잭션 하나: 행 `FOR UPDATE` → 끝 상태면 끝 → 잠근 뒤 시계로 읽은 시각으로 업무 기한 `now >= 기한`이면 `SKIPPED` → `NotificationWriter.save`(원본 이벤트 종류 = 예약 종류 코드, 원본 대상 ID·이동 대상 ID = 대상 ID, 발생 시각 = 잠근 뒤 읽은 시각(보낸 시각, ND-148)) → `SKIPPED_RECEIVER`면 `SKIPPED`, 아니면 `SENT`. 실패한 예약은 롤백돼 `SCHEDULED`로 남고, `REQUIRES_NEW` 트랜잭션에서 `failure_count`를 1 늘린다(WARN). 3이 되면 같은 트랜잭션에서 `SKIPPED`로 끝내고 ERROR `[NOTIFICATION_SCHEDULE_FAILED]`(ND-147). 배치(`NotificationScheduleBatch`)는 `Clock.systemUTC()`를 넘기고, 시험은 정한 시계를 넘긴다. 이어서 끝 상태이고 업무 기한 + `retention-days`(기본 7)일이 지난 행을 `cleanup-batch-size`(기본 100)만큼 지운다. `ScheduleAlertTemplates`(enum 5개, 카탈로그 0.13)가 문구를 만든다 | `.../app/ScheduleDispatcher.java`, `.../app/NotificationJob.java`, `.../app/ScheduleAlertTemplates.java`, `.../in/NotificationScheduleBatch.java` |
| 값 규칙: `v`(필수), `nicknames`(회원 ID → 닉네임). `nicknames`의 키가 언급 회원 ID가 된다. 파기되면 `탈퇴회원#{ID}`로 바꾸고 문구를 템플릿으로 다시 만든다(멱등). 파기(ND-054): `notification_user.masked_at`(Flyway `V202611101100`, 역할 null 허용)에 파기 표시를 남기고 복제본이 없으면 파기 상태로 만든다. 파기된 복제본은 가입·변경으로 바뀌지 않는다. 저장은 언급 회원마다 회원 단위 advisory lock 공유(ID 순), 파기는 배타 잠금 `(801, 회원 ID 해시)`를 잡고, 저장 때 파기된 언급 회원 닉네임을 바꾼다 | `.../domain/Notification.java`, `.../domain/NotificationPayload.java`, `.../domain/NotificationUser.java`, `.../app/NotificationMaskingService.java`, `.../out/MemberMaskLocks.java` |
| REST `ApiV1NotificationController`(`/api/v1/notifications`, N2): 목록·안 읽은 수·읽음·전체 읽음. `@PreAuthorize`는 `USER`·`INFLUENCER`·`VERIFIED_INFLUENCER`(관리자 403), 남의·없는·밀려난 알림 읽음은 404 `NOTIFICATION_NOT_FOUND`(ND-052). 외부 경로는 Gateway route(N4, #847) | `.../in/ApiV1NotificationController.java`, `.../app/NotificationQueryFacade.java` |
| 실시간 전달(N3, #829): `NotificationWriter`가 저장 뒤 `NotificationRealtimeRelay`에 맡기고, Relay는 트랜잭션 `afterCommit`에서만 전용 실행기(`notificationRealtimeExecutor`, 스레드 1·줄 1000, 차면 버림)에 넘긴다. 실행기가 알림을 다시 읽어(밀려난 알림이면 건너뜀) 프레임 `{notification, unreadCount}`을 만들고 Redis 채널 `<prefix>:realtime`에 발행한다. 다른 회원의 닉네임이 든 알림은 파기 순서 공유 잠금(ND-054) 안에서 발행하고, 없는 알림은 트랜잭션을 끝낸 뒤 발행한다. 발행이 실패하면 5초(설정) 동안 DB 조회 전에 생략한다. 버린 프레임은 지표 `hapbang.notification.realtime.dropped{reason}`로 세고 저장을 되돌리지 않는다(ND-027·ND-059, 3.8). 구독자(`NotificationRealtimeRedisListener`, 전용 `RedisMessageListenerContainer`)가 `convertAndSendToUser(회원ID, /queue/notifications)`로 보낸다 | `.../app/NotificationRealtimeRelay.java`, `.../app/NotificationRealtimeExecutorConfig.java`, `.../out/redis/RedisNotificationRealtimePublisher.java`, `.../in/redis/NotificationRealtime{RedisListener,RedisConfig}.java` |
| 연결 티켓(N3): `POST /api/v1/notifications/ws-tickets`(`@PreAuthorize` 받는 역할). 30초·1회용(Redis `GETDEL`, 키는 SHA-256), 회원마다 60초 10번(Lua INCR+PEXPIRE, 넘으면 공통 `RateLimitExceededException` → 429 `TOO_MANY_REQUESTS`·`Retry-After: 60`), 저장소 없음·실패 503 `CONNECTION_TICKET_UNAVAILABLE`. 연결 만료 `min(X-User-Token-Exp, 발급 + 15분)`, 헤더는 컨트롤러만 읽는다(ND-056·ND-058) | `.../app/NotificationConnection{TicketService,Facade}.java`, `.../in/ApiV1NotificationConnectionController.java`, `.../out/redis/RedisNotificationConnectionTicketStore.java`, `src/main/resources/notification/redis/ws-ticket-rate.lua` |
| 자체 STOMP(N3): `@EnableWebSocketMessageBroker`, 엔드포인트 `/api/v1/notifications/ws`(출처 검사 → 티켓, 없거나 쓴 티켓 401), 메모리 브로커 `/queue`, 사용자 목적지 `/user`. 수신 검사는 CONNECT를 결합된 회원만, SUBSCRIBE를 `/user/queue/notifications`만 받고 SEND는 모두 거절한다. 연결별 만료 예약 + 30초 주기 대조로 4401 `TOKEN_EXPIRED` 종료(ND-057). 수신·송신 채널 실행기 각 2스레드(설정이 소유, 기동 때 미리 띄우고 컨텍스트가 닫힐 때 종료), 전송 10초·512KB. 서버 STOMP heart-beat 보내기·받기 `hapbang.notification.realtime.websocket.heartbeat`(기본 25초, ND-063) — 스케줄러 1스레드(같은 방식으로 소유·종료). STOMP 협상이라 클라이언트가 받기 0이면 서버도 보내지 않고(FE 계약, ND-068), 클라이언트 보내기가 끊기면 서버가 연결을 닫는다. 설정값이 0 이하이거나 5분(`MAX_HEARTBEAT`, Gateway stream idle) 이상이면 생성자가 `IllegalArgumentException`으로 기동을 막는다(ND-068, #847) | `.../in/websocket/Notification{WebSocketConfig,TicketHandshakeInterceptor,HandshakeHandler,StompInterceptor,WebSocketSessions}.java` |
| `hapbang.notification.realtime.enabled`(기본 켜짐)로 위 빈을 켜고 끈다. 모듈 테스트 설정은 끄고 `@NotificationRealtimeTest`만 켠다. 모듈 빌드에 `spring-boot-starter-websocket`·`spring-boot-starter-data-redis` | `.../app/ConditionalOnNotificationRealtime.java`, `boundedContext/notification/build.gradle.kts`, `src/test/resources/hapbang-module-test.yaml` |
| auxiliary global `SecurityConfig`가 `GET /api/v1/notifications/ws`만 `permitAll`이다(ND-060). 허용 출처는 `hapbang.notification.websocket.allowed-origins`(local·prod 프로필). Redis 명령 시간 제한 `spring.data.redis.timeout: 10s`(앱 공통) | `apps/auxiliary-app/global/.../config/SecurityConfig.java`, `apps/auxiliary-app/src/main/resources/application{,-local,-prod}.yaml` |
| 테스트(Unit·PG·PG+Kafka·MockMvc, 순서를 강제한 경합 테스트 포함). 건수와 실행 결과는 [regression-log.md](regression-log.md)에만 둔다 | `boundedContext/notification/src/test/` |
| Gateway HTTPRoute `auxiliary-app`의 `matches`에 `/api/v1/notifications`가 있다(챗봇·검색·Swagger와 함께, 30초·본문 1Mi, JWT SecurityPolicy 대상). 알림 웹소켓은 HTTPRoute `auxiliary-app-notification-websocket`(경로 Exact `/api/v1/notifications/ws` + `GET`, `timeouts` 0s)과 BackendTrafficPolicy(`requestTimeout: 0s`, `streamIdleTimeout: 300s`)이고 JWT SecurityPolicy·본문 제한 대상이 아니다(ND-062·ND-063·ND-067, #847) | `apps/auxiliary-app/k8s/base/gateway.yaml` |
| Gateway `claimToHeaders`는 `X-User-Id`(`sub`), `X-User-Role`(`role`), `X-User-Token-Exp`(`exp`, #847 ND-064)를 넘긴다. 외부에서 보낸 같은 헤더는 공용 Gateway `ClientTrafficPolicy`가 모든 route에서 지운다 | `apps/auxiliary-app/k8s/base/gateway.yaml`, `k8s/gateway/base/gateway.yaml` |
| auxiliary global 필터는 `X-User-Id`·`X-User-Role`만 읽는다. `X-User-Token-Exp`가 없다. 바꾸지 않고 알림 연결 티켓 API가 직접 읽는다(ND-056) | `apps/auxiliary-app/global/src/main/java/com/hapbang/auxiliary/global/auth/GatewayUserHeaderFilter.java`, README "실행과 설정" 끝 |
| 공통 `SecurityConfig`가 Swagger·`/error`·헬스체크·prometheus와 알림 웹소켓 핸드셰이크(`GET /api/v1/notifications/ws`, ND-060) 밖의 모든 요청에 인증을 요구한다 | `apps/auxiliary-app/global/src/main/java/com/hapbang/auxiliary/global/config/SecurityConfig.java` |
| Modulith 발행 기록·Flyway 이력은 schema `auxiliary`, Flyway `out-of-order: true`, Hikari 5, 스케줄 풀 2 | `apps/auxiliary-app/src/main/resources/application.yaml` |
| Kafka·Redis 클라이언트는 루트 빌드가 모든 앱에 넣는다(`:kafka`, `:redis`). WebSocket starter와 업무 모듈용 Redis starter·micrometer-core는 알림 모듈 빌드가 직접 넣는다(N3, 업무 모듈은 `:redis` 프로젝트를 의존할 수 없다) | `build.gradle.kts` 62~68행, `boundedContext/notification/build.gradle.kts` |

## 3. 확정 To-Be (ND로 확정. 구현된 부분은 2장 As-Is)

### 3.1 배포 단위와 저장소

| 항목 | 내용 요약 | ND |
| --- | --- | --- |
| 모듈 | auxiliary-app 안 업무 모듈 `notification`(Gradle `:notification`, 패키지 `com.hapbang.auxiliary.notification`). `:auxiliary-global`·`:shared`만 의존 | ND-006 |
| 저장소 | database `auxiliary`, schema `notification`. 엔티티 `@Table(schema = "notification")`. 첫 SQL은 schema 생성 | ND-007 |
| 기록 위치 | Flyway 이력·Modulith 발행 기록은 schema `auxiliary` | ND-007 |
| 경계 | 다른 모듈 schema와 JOIN·FK·같은 트랜잭션 쓰기 금지 | ND-007 |
| 테이블 | `notification`, `notification_payout_item`, `notification_schedule`, `notification_user` 4개와 운영 설정 `notification_runtime_setting`(ND-095). 처음 쓰는 작업에서 만든다(N1 `notification_user`, N2 `notification`, N5 `notification_runtime_setting`, N8 `notification_payout_item`, N10 `notification_schedule`) | ND-025, ND-046, ND-095 |
| consumer group | `auxiliary-app.notification-<목적>`(회원 복제본 `auxiliary-app.notification-member-replica`), DLT `<토픽>.<그룹>.DLT` | ND-047, ND-010 |
| 첫 배포 순서 | N1은 알림을 만들지 않는 회원 복제본 소비자만 배포한다(ND-048). N5는 알림 시작 시각 이전 사건을 알림 없이 커밋하고(ND-076), 회원 복제본 Backfill이 끝난 뒤 알림을 만드는 소비자를 시작한다(ND-077·ND-078, 5.4) | ND-048, ND-076~ND-078 |

### 3.2 처리 규칙

| 항목 | ND |
| --- | --- |
| 입력은 Kafka integration event뿐, 채팅 Redis Streams 구독 안 함 | ND-008, ND-009 |
| 실패 메시지 DLT, 받지 않는 타입은 무시하고 커밋 | ND-010 |
| 받는 사람 확인(알림 회원 복제본, 탈퇴·정지 제외) | ND-018, ND-072 |
| 복제본 만들기(가입, 또는 복제본이 없을 때 온 변경. 있으면 더 큰 버전만) | ND-018, ND-049 (첫 배포 Backfill은 ND-077, 5.4) |
| 복제본이 아직 없는 받는 사람(블로킹 재시도 뒤 DLT) | ND-035 (첫 배포 Backfill은 ND-077·ND-078) |
| 중복 판정(유일 인덱스, 삭제된 행 포함) | ND-019, ND-033, ND-045 (새로 요청하는 이벤트의 사건 ID는 [이벤트 계약 요청](event-contracts.md), N5 11종은 ND-073, 그 밖의 이미 발행 중인 이벤트의 필드는 U5) |
| 문구·값 저장 | ND-020, ND-039, ND-050 (종류별 문구는 그 종류를 소비하는 작업에서. N5 11종은 ND-075, 나머지는 U3) |
| 이동 대상(업무 자원 단위, 종류별 값은 알림 종류 문서) | ND-074 |
| 알림 시작 시각 이전 사건은 알림 없이 커밋 | ND-076 |
| 사건 시각 필드가 없는 이벤트는 Kafka 레코드 timestamp를 대체 시각으로(사건 시각과 같다고 보장하지 않음) | ND-081 |
| 가입 환영·승급 승인은 회원 복제본 리스너 안, 반영 전 역할로 승급 판단 | ND-080 |
| 파기된 회원의 닉네임(언급 회원 ID 칸 `bigint[]`·GIN, 값 속 닉네임 교체 뒤 문구 재생성, 멱등) | ND-036, ND-051 |
| 파기 뒤 새로 저장되는 알림(복제본 파기 표시, 저장 때 파기된 언급 회원 닉네임 교체, 회원 단위 advisory lock으로 순서) | ND-054 |
| 발생 시각 정렬 | ND-021 |
| 최신 20개 보존, Soft Delete | ND-022 (밀려난 묶음 되살림 ND-097) |
| 보존 동시성(받는 사람 `notification_user` 행 비관적 락으로 받는 사람별 저장 직렬화) | ND-053 |
| 같은 알림 행의 동시 쓰기(아래 "동시성 설계") | ND-022, ND-051, ND-053, ND-054, #801 PR 리뷰 |
| N6 이의 제기 결과에서 #818 추가 필드가 빠진 이벤트(키가 모두 없으면 건너뜀·커밋, 값이 `null`이거나 일부만 없으면 DLT) | ND-086 |
| N6 이벤트는 N5 알림 생성 리스너·consumer group에 종류별 처리기로 더함((group, topic)마다 컨테이너 하나, 시작 시각·Backfill 뒤 시작 적용) | ND-087 |
| N7 고객센터 2종(문의 답변 도착·이의 제기 접수 불가)은 `customersupport.events.v1`의 새 알림 생성 리스너·group `auxiliary-app.notification-customersupport-alert`(DLT `<토픽>.<그룹>.DLT`)로 받는다. 같은 토픽의 이의 제기 명령 2종은 건너뛴다. 첫 Backfill 뒤 시작하고 알림 시작 시각 필터를 그대로 적용한다. 새 group이라 `earliest`로 보존 기간 안의 지난 M7 이벤트를 늦게 알림으로 만든다. 원본 대상 ID·이동 대상·문구는 ND-124~ND-126 | ND-122, ND-124~ND-126 |
| N6 강의 알림 리스너는 값을 바이트로 받아 신뢰 타입·#818 이전 형식을 본 뒤 레코드로 바꿈(출연·지갑 리스너는 객체 수신 그대로) | ND-096 |

**동시성 설계 (#801 PR 리뷰 반영, 구현됨)**

같은 `notification` 행을 저장(보존 정리), 읽음, 전체 읽음, 파기가 함께 쓴다. 엔티티로 읽어 두고 커밋 때 행 전체를 쓰면 그사이 커밋된 다른 처리의 칸(삭제 시각·읽은 시각·값·문구)을 옛 값으로 되돌린다(밀려난 알림이 되살아나 20개 불변식이 깨지거나, 파기된 닉네임이 돌아온다).

| 처리 | 바꾸는 칸 | 방식 |
| --- | --- | --- |
| 보존 정리(저장 안) | `deleted_at` | 밀어낼 행 ID만 조회 → 그 행을 ID 오름차순 `FOR UPDATE` → `deleted_at`만 채우는 벌크 UPDATE(`deleted_at is null` 조건) |
| 읽음 | `read_at` | `id·받는 사람·deleted_at is null·read_at is null` 조건 UPDATE, 바뀐 건수 반환. 0건이면 새로 조회해 이미 읽음(200)과 남의·없는·밀려난 알림(404)을 가른다 |
| 전체 읽음 | `read_at` | 보이고 안 읽은 행을 ID 오름차순 `FOR UPDATE` → 같은 조건 UPDATE, 바뀐 건수 반환 |
| 파기 | `payload`·`message` | 그 회원이 언급된 행을 ID 오름차순 `FOR UPDATE`로 가져와 잠금 뒤의 최신 값에서 바꾼다. 이 트랜잭션은 알림 엔티티를 미리 불러오지 않아 영속성 컨텍스트에 오래된 엔티티가 없다. `@DynamicUpdate`로 바뀐 칸만 쓴다 |
| 하루 묶음 갱신·되살림(N8, ND-103) | `payload`·`message`·`occurred_at`·`read_at`·`deleted_at` | 받는 사람 행 잠금 뒤, 받는 사람의 보이는 알림과 묶음 행을 한 번에 ID 오름차순 `FOR UPDATE` → 잠금 뒤 값으로 새 값을 만들어 조건 없는 UPDATE. 이어지는 보존 정리가 밀어낼 행은 이 잠금 집합 안에 있어 다시 기다리지 않는다 |

- 잠금 획득 순서(모든 경로 공통): 회원 advisory lock(ND-054, 저장은 언급 회원 공유·ID 순, 파기는 배타) → 회원 복제본 행(저장은 받는 사람, 파기는 그 회원) → 알림 행(ID 오름차순). 알림 행을 잡은 뒤에는 회원 잠금·복제본 행을 잡지 않는다. 그래서 교착 상태가 생기지 않는다. 하루 묶음은 묶음 행만 먼저 잡고 보존 정리에서 더 작은 ID를 잡으면 이 순서가 깨지므로, 보이는 알림과 묶음 행을 함께 잡는다(#890 리뷰. 그 전 코드도 밀어냄이 일어나는 경우에는 묶음 행이 전체 읽음의 잠금 대상이 아니어서 교착에 이르지 않았다).
- `@DynamicUpdate`만으로는 같은 칸을 쓰는 파기끼리(한 알림에 B·C가 함께 언급)의 덮어쓰기를 막지 못한다. 행 잠금을 빼면 그 경합 테스트가 실패하는 것을 확인했다(regression-log NR-015).
| REST 권한·오류(회원용 알림 API는 세 역할만, 관리자 403. 남의·없는·밀려난 알림 읽음 404 `NOTIFICATION_NOT_FOUND`, 다시 읽음 200) | ND-052, ND-079 |
| Backfill 관리자 재실행 API(`ADMIN`만) | ND-079 |
| 읽음은 누를 때, 전체 읽음 | ND-023 |
| 대상 삭제 시 알림 유지 | ND-024 |
| API 6개 | ND-026, 상세는 [API 문서](../api/notification.md) |
| commit 뒤 Redis Pub/Sub → STOMP, REST로 재동기화 | ND-027 |
| 채팅 코드 복사(연결 티켓, Pub/Sub, Kafka·DLT, 유일 인덱스) | ND-028 |
| 예약 알림·예약 배치 | ND-029~031, 범위·입력 리스너 ND-133, 운영 시간 ND-134(팀 확인 대기 U6), 보내기 직전 확인·"건너뜀"·업무 기한 ND-135, 기한 지난 생성 ND-136, 종류 코드 ND-137, 원본 대상 ID·이동 대상 ND-139, 문구 ND-140, 배포 전 사건 ND-141 |
| 예약보다 먼저 온 취소(취소 상태 행을 먼저 남김) | ND-034, 5종 모두 적용 ND-138 (짝 키는 [이벤트 계약 요청](event-contracts.md) 0.3) |
| 예약 행 정리 | 최종 상태·업무 기한 + 7일 ND-142, 구매 취소 마감 임박 "취소" 먼저 행의 대체 기한 ND-143 |
| 하루 묶음 | ND-032, ND-097(되살림), ND-098(종류 코드), ND-100·ND-104(문구, 제목 없음), ND-101(발생 시각), ND-102(이동 대상 없음) |

### 3.3 처리 흐름 (설계, 미구현)

```text
발행 앱 ──Kafka integration event──▶ notification Kafka Listener        (ND-008, group 이름 ND-047)
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

- **입력**: 받는 토픽·이벤트 목록과 상태는 [kafka.md](../architecture/infrastructure/kafka.md) "토픽 목록"과 [알림 종류](notification-catalog.md)를 따른다. 새 이벤트 요청은 알림 담당이 정해 [이벤트 계약 요청](event-contracts.md)에 둔다(ND-037). 기존 이벤트의 한계와 발행 앱과 정할 계약은 [5장](#5-다른-앱-이벤트-계약-2026-11-06-조사)에 있다.
- **출력**: 다른 앱으로 내보내는 이벤트나 내부 HTTP API는 없다. 발행 모듈은 알림을 모른다(ND-008).
- **회원 정보**: 알림을 만들 때는 회원 이벤트로 만든 복제본만 쓴다(ND-018). 알림 처리 중에는 회원 앱을 동기 호출하지 않는다. 복제본이 아직 없는 받는 사람은 ND-035를 따른다(DLT로 간 메시지는 [3.7](#37-실패와-재시도)의 재처리 runbook으로 다시 넣는다). 회원 앱 호출은 복제본 Backfill에서만 회원 목록 내부 API로 한다(ND-077, [5.4](#54-회원-복제본-첫-배포-backfill)).
- **같은 앱의 다른 모듈**: 연동이 필요하면 Spring 이벤트로만 한다(auxiliary README "모듈 사이 이벤트"). 지금 설계에는 연동이 없다.

### 3.6 인증·권한

- 사용자 인증은 Gateway가 JWT를 검증해 넘기는 사용자 헤더(`X-User-Id`, `X-User-Role`)로 한다(루트 AGENTS 2장, `GatewayUserHeaderFilter`).
- auxiliary global `SecurityConfig`가 업무 API 전부에 로그인을 요구한다. 예외는 알림 웹소켓 핸드셰이크 `GET /api/v1/notifications/ws` 하나이고, 인증은 연결 티켓으로 한다(ND-060). auxiliary README "권한"의 "모듈이 공개 경로를 따로 열지 않는다"에 이 예외를 적는 일은 팀 승인 후속이다(PROGRESS 4장).
- 역할 검사는 모듈 Facade의 `@PreAuthorize`로 한다(auxiliary README "권한"). 받는 사람은 유저·인플루언서·인증된 인플루언서다(ND-003). 모든 API는 본인 알림만 다룬다(ND-026).
- WebSocket 연결은 연결 티켓으로 인증한다(ND-028). 연결 만료는 ND-056, 서버가 끊는 경우는 ND-057, 티켓·전송 수치는 ND-058, 프레임을 보내는 때는 ND-059다. Gateway route는 ND-062, WebSocket 시간 제한과 서버 STOMP heart-beat는 ND-063, `exp` claim은 ND-064, WebSocket route를 JWT SecurityPolicy에서 빼는 것은 ND-067이다(N4, #847).

### 3.7 실패와 재시도

- Kafka 재시도·DLT 이름·DLT 선언·재처리 runbook은 [kafka.md](../architecture/infrastructure/kafka.md) "재시도와 DLT"·"DLT 재처리 runbook"을 따른다(ND-010).
- DLT 재처리로 같은 메시지가 다시 와도 유일 인덱스로 한 번만 만든다(ND-019).
- 실시간 전달 유실은 재시도하지 않고 REST 재동기화로 맞춘다(ND-027).

### 3.8 실시간 전달 구현 설계 (N3, #829)

ND-027·ND-059를 구현하면서 정한 세부다. 결정(ND)이 아니라 구현 설계이며, 값은 설정으로 바꿀 수 있다.

| 항목 | 설계 | 이유·영향 |
| --- | --- | --- |
| 발행 위치 | 저장 트랜잭션의 `afterCommit`에서 전용 실행기에 넘기기만 한다. 넘기기 실패도 예외를 밖으로 내지 않는다 | commit 전 발행을 막고, 이미 commit된 저장을 되돌리지 않는다 |
| 발행 실행기 | 스레드 1개, 줄 1,000건(`hapbang.notification.realtime.publish.queue-capacity`). 스레드가 예외로 죽어도 풀이 새 스레드를 만든다 | Redis가 멈추거나 느려도 저장(이벤트 소비) 스레드는 기다리지 않는다 |
| 줄 포화·발행 실패·생략 | 그 프레임을 버린다(유실, 다시 보내지 않는다). 지표 `hapbang.notification.realtime.dropped{reason}`로 이유를 나눠 센다: `queue_full`(줄 포화), `read_failed`(DB 다시 읽기·파기 순서 잠금 실패, 생략 시간을 걸지 않음), `publish_failed`(Redis 발행 실패만, 생략 시간 시작), `skipped`(발행 실패 뒤 생략 시간). 로그는 버리기 시작할 때 WARN·다시 보낼 때 INFO만 남기고, 생략은 DEBUG다 | 유실은 ND-027이 허용하고 FE는 REST로 맞춘다. 프레임마다 로그를 남기지 않는다(docs/dev/logging.md) |
| 발행 실패 뒤 생략 | Redis 발행이 실패하면 `hapbang.notification.realtime.publish.failure-backoff`(기본 5초) 동안은 **알림 DB 조회와 파기 순서 잠금 전에** 작업을 끝낸다. 시간이 지나면 다음 작업이 다시 시도하고, 성공하면 정상으로 돌아온다 | Redis 장애가 이어지는 동안 DB 연결·잠금 점유를 줄인다(#829 Bot 리뷰) |
| Redis 대기 | 발행 스레드는 Redis 명령 응답을 기다린다. auxiliary-app `spring.data.redis.timeout: 10s`(chat-app과 같은 값, 2026-11-10 알림 담당) | 티켓 발급·사용 요청도 최대 10초 기다린 뒤 503·거절이다. **남는 위험**: Redis가 계속 응답하지 않으면 생략 시간이 끝날 때마다 시도 1건이 최대 10초 기다린다. 그 1건이 언급 회원이 있는 알림이면 그동안 DB 연결 1개(Hikari 5개 중)와 그 회원들의 파기 순서 잠금을 쥐어, 그 회원의 파기가 최대 10초 늦어진다. 시도는 발행 스레드 1개에서만 일어나므로 동시에 쥐는 연결은 최대 1개다 |
| 파기와의 순서 | 다른 회원의 닉네임이 든 알림(언급 회원 칸 또는 값의 닉네임 키가 하나라도 있음)은 언급 회원의 파기 순서 공유 잠금(ND-054, `MemberMaskLocks`)을 잡은 읽기 트랜잭션에서 다시 읽고 **그 안에서** 발행한다. 다른 회원의 닉네임이 없는 알림은 잠금 없이 읽고 **트랜잭션을 끝낸 뒤** 발행한다 | 파기가 진행 중이면 끝난 뒤 파기된 닉네임으로 보낸다(ND-054 보장 유지). 닉네임이 없는 알림은 파기로 바뀔 값이 없어 잠금이 필요 없고 DB 연결을 쥐지 않는다. 언급 회원 칸만 보지 않고 값의 닉네임도 함께 봐서, 칸이 비어도 닉네임이 있으면 잠금 경로로 간다 |
| 다시 읽기 | 밀려난(Soft Delete된) 알림은 보내지 않는다. 안 읽은 수는 REST와 같은 쿼리로 그때 센다 | 20개 보존·중복 방지(ND-019·ND-022)와 맞춘다 |
| 구독 쪽 | 전용 `RedisMessageListenerContainer`(구독 스레드에서 바로 메모리 브로커로 넘김), 끊기면 Lettuce가 다시 연결하고 5초마다 다시 구독 | 끊긴 동안의 프레임은 유실, FE는 REST로 맞춘다 |
| 연결 정리 | 핸드셰이크 전에 티켓을 `GETDEL`로 쓴다(업그레이드가 실패해도 그 티켓은 다시 못 쓴다). 연결이 닫히면 결합과 만료 예약을 지운다. 결합하지 못했거나 이미 만료인 연결은 STOMP 처리기에 넘기지 않고 닫는다 | 서버 쪽에 남는 연결·예약이 없다 |
| 서버가 닫는 경우 | 정책상 종료는 연결 만료(4401)뿐이다(ND-057). 그 밖에 STOMP 규칙 위반(결합 안 된 CONNECT, SEND, 다른 목적지 구독)은 Spring이 ERROR 프레임과 함께 닫고, 전송 상한(10초·512KB, ND-058)을 넘긴 연결도 Spring이 닫는다 | ND-057의 범위는 정책상 종료이고, 규칙 위반·전송 상한 종료는 별개다(ND-061) |

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
| 1. `auxiliaryModules`에 등록 | `"notification"` 추가 (N1 #797에서 등록) |
| 2. 모듈 `build.gradle.kts` | WebSocket 등 필요한 라이브러리만. 라이브러리 추가는 `docs/dev/local.md` 확인 |
| 3. `:auxiliary-app` 의존 추가 | |
| 4. `package-info.java` `@ApplicationModule` | |
| 5. 첫 SQL | ND-007 |
| 6. `gateway.yaml` 경로 추가 | REST와 WebSocket 경로(ND-062), 시간 제한(ND-063), `exp` claim(ND-064), WebSocket route는 JWT SecurityPolicy 대상 아님(ND-067). N4(#847)에서 한다(ND-055) |
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
| M1 출연 취소 요청 3종 | 2026-11-10 #817로 3종이 `@Externalized`(key `appearance:<출연 ID>`)로 나가고 사건 시각(`requestedAt`·`acceptedAt`·`rejectedAt`)이 붙었다. 2026-11-06 조사 때는 `@Externalized`가 없었다 | `apps/appearance-app/src/main/java/com/hapbang/appearance/app/AppearanceExternalEvents.java` 12행 근처, 발행 `AppearanceCancelRequestUseCase`·`AppearanceCancelRespondUseCase`·`AppearanceCancelAutoRejectUseCase` |
| M2 이의 제기 4종 | 2026-11-10 #818로 `buyerUserId`·`sellerUserId`·`lectureTitle`·사건 시각(반환 지연은 `returnDelaySequence`도)을 더했다. #818 배포 전에 저장된 발행 기록의 재전송분은 새 필드가 null이다 | `common/shared` 강의 이벤트 레코드, [이벤트 계약 요청](event-contracts.md) 2장 |
| M3 출연 🆕 12종 | 2026-11-12 확인: #871로 12종 shared 레코드(`common/shared/.../appearance/event`)가 생겼고 모두 `@Externalized`로 나간다(key·필드는 [이벤트 계약 요청](event-contracts.md) 1장). 수락·선정 이벤트는 성립한 출연 ID(`appearanceId`)를 싣는다. 2026-11-06 조사 때는 레코드가 없었고, 같은 이름 레코드 없음. 대체 후보 `AppearanceChangedEvent`(#713)는 `AppearanceSyncDto`(id, hostId, guestId, result, scheduledAt, version)만 담고, 상태 6개를 결과 4개로 줄이며, 순서를 보장하지 않는다. 요청/지원 수락, 일방/합의 취소, 호스트/자동 확인, 성립/취소 요청을 구분하지 못하고 요청서·지원·모집글 단계 전이는 다루지 않는다. **M3 전체를 대체할 수 없다**. 부분 충족 5종(요청 수락·지원 수락·일방 취소·자동 확인·불참), 미충족 7종 | `common/shared/src/main/java/com/hapbang/shared/appearance/dto/AppearanceSyncDto.java`, `apps/appearance-app/src/main/java/com/hapbang/appearance/domain/AppearanceStatus.java` 34~40행, [internal-appearance-sync.md](../api/internal-appearance-sync.md) 4장 |
| M4 강의 🆕 7종 | 레코드 없음. 대체 후보 #712 `LectureChangedEvent`(제목·`sellerUserId` 있음, 검수 상태·반려 사유·발생 시각 없음, `onSale`만), `LecturePurchaseChangedEvent`(`buyerUserId`·`purchasedAt`, 결과 `OWNED`·`FAILED`·`CANCELED`, 제목·금액 없음, 항목 단위). 구매 확정·지급 전이는 동기화 이벤트도 없다. 부분 충족 4종(검수 승인·구매 완료·결제 실패·구매 취소), 미충족 3종(검수 반려·자동 구매 확정·이의 제기 접수). **2026-11-11 #872로 7종을 `@Externalized`로 발행한다**(필드는 [이벤트 계약 요청](event-contracts.md) 2장, 강의 D85). 지급 요청에는 구매 때 강의 제목을 출처 제목으로 넘긴다(ND-040) | `apps/lecture-app/src/main/java/com/hapbang/lecture/app/LectureSyncEvents.java` 59행 근처, `apps/lecture-app/src/main/java/com/hapbang/lecture/app/OrderObjectionFileUseCase.java` |
| M5 지갑 🆕 4종 | 충전 실패·환불 실패·환불 운영자 확인·환전 승인 모두 발행 없음(미충족). 기존 7종이 같은 방식의 본보기 | `apps/billing-app/src/main/java/com/hapbang/billing/app/BillingEventPublisher.java` 39~81행, `ChargeProcessing`·`RefundProcessing`·`ExchangeReviewUseCase` |
| M6 회원 🆕 2종 | 채널 인증 반려·승급 반려 발행 없음(미충족). 승인 2종은 `UserModifiedEvent`(`UserDto`: id, nickname, role, status, version, 이전 역할 없음) + 복제본 비교. 승급 승인은 판별 가능, 채널 인증 승인은 새 이벤트로 요청(ND-042). **2026-11-11 #874로 반려 2종과 채널 인증 승인 `InfluencerChannelApprovedEvent`를 `@Externalized`로 발행한다**(필드는 [이벤트 계약 요청](event-contracts.md) 4장) | `apps/member-app/src/main/java/com/hapbang/member/app/MemberChannelReviewUseCase.java`, `apps/member-app/src/main/java/com/hapbang/member/app/MemberUpgradeReviewUseCase.java` |
| M7 고객센터 🆕 3종 | 2026-11-12 확인: #875로 답변 도착·접수 불가 2종 shared 레코드(`common/shared/.../customersupport/event`)가 생겼고 `@Externalized`(key `inquiry:<문의 ID>`)로 나간다(필드는 [이벤트 계약 요청](event-contracts.md) 5장). 답변 수정은 고객센터 담당 결정으로 발행하지 않아 알림 종류를 폐기했다(ND-123). 2026-11-06 조사 때는 3종 모두 발행 없음(미충족), 재시도 배치 경로는 `delete=false`로 식별 가능 | `apps/customer-support-app/src/main/java/com/hapbang/customersupport/app/InquiryUseCase.java`, `apps/customer-support-app/src/main/java/com/hapbang/customersupport/app/LectureObjectionFiler.java` |
| M8 채팅 🆕 3종 | 2026-11-12 확인: #897로 3종 shared 레코드(`common/shared/.../chat/event`)가 생겼고 `chat.events.v1`에 key 방 ID로 나간다(필드는 [이벤트 계약 요청](event-contracts.md) 6장). 2026-11-06 조사 때는 레코드와 토픽이 없었다 | `apps/chat-app/src/main/java/com/hapbang/chat/out/event/ChatEventExternalizationConfig.java`, 발행 `DefaultChatGroupRoomService` |

### 5.3 발행 앱과 정할 계약

> 이벤트 이름·key·담을 값과 계약 상태의 원본은 [이벤트 계약 요청](event-contracts.md)이다(ND-037, 발행 공통 규칙은 그 문서 0.2). 아래는 조사로 드러난 발행 앱별 구현 위치다. 알림 쪽에서 이벤트·DTO를 만들지 않는다.

| 묶음 | 발행 앱 | 필요 작업 |
| --- | --- | --- |
| M1 | appearance-app | 취소 요청 3종 Kafka 외부화(필드는 있음) |
| M2 | lecture-app | 이의 제기 4종에 `buyerUserId`·`sellerUserId`·강의 제목 추가 |
| M3 | appearance-app | 완료(#871, 12종). 소비는 N7 출연(#908). 아래는 2026-11-06 조사 기록이다: 출연 🆕 이벤트 신설. `AppearanceChangedEvent`로 대신할지는 사건 구분 불가로 어렵다. 출연 전 단계 8종은 발행 지점 자체가 없다(`AppearanceRequestRejectUseCase`, `AppearanceRequestCancelUseCase`, `AppearanceRequestExpireUseCase`, `AppearanceApplicationRespondUseCase.reject`, `AppearanceRecruitHoldUseCase.markRecruiting`, `AppearanceRecruitCloseUseCase`, `AppearanceRecruitDeadlineCloseUseCase`, `AppearanceRecruitCloseRecorder`, `AppearanceRecruitCancelUseCase`) |
| M4 | lecture-app | 완료(#872, 7종). 소비는 N7 강의(#910, ND-114~ND-117). 아래는 2026-11-06 조사 기록이다: 강의 🆕 이벤트 신설(계약 요청 2장). 구매 확정은 예약 취소에도 필요 |
| M5 | billing-app | 🆕 4종을 `BillingEventPublisher`와 같은 방식으로 발행, `PayoutCompletedEvent`에 `sourceTitle`(ND-040, 출연·강의의 지급 요청 포함) |
| M6 | member-app | 완료(#874, 3종). 소비는 N7 회원(#915, ND-118~ND-121). 아래는 2026-11-06 조사 기록이다: 반려 2종과 `InfluencerChannelApprovedEvent`(ND-042) 신설 |
| M7 | customer-support-app | 완료(#875, 답변 도착·접수 불가 2종, 답변 수정은 발행하지 않음). 소비는 N7 고객센터(#918) |
| M8 | chat-app | 완료(#897, 3종, 새 토픽 `chat.events.v1`, ND-044). 소비는 N9(#921, ND-129~ND-132) |

### 5.4 회원 복제본 첫 배포 Backfill

확정 To-Be(ND-077~ND-079). 구현은 N5(#859)에서 한다. 아래는 결정을 설계로 옮긴 것이고 결정 본문은 decisions.md에 있다.

| 항목 | 설계 | ND |
| --- | --- | --- |
| 읽는 곳 | 회원 목록 내부 API([internal-members.md](../api/internal-members.md) 1장)를 `afterId` 키셋으로 마지막 페이지까지. 공통 HTTP Client(`shared.member`)만 쓴다 | ND-077 |
| 반영 | 회원마다 한 문장: 없으면 만들고, 있으면 버전이 더 클 때만, 파기 표시가 있으면 바꾸지 않음. Kafka 회원 이벤트와 겹쳐도 가장 큰 버전이 남는다 | ND-049, ND-054, ND-077 |
| 실행 | 앱이 뜬 뒤 비동기로 자동 실행 + 관리자 재실행 API. 기동 실행과 재실행은 같은 advisory lock | ND-078, ND-079 |
| 리스너 제어 | 출연·지갑 알림 소비자 컨테이너만 첫 Backfill이 끝날 때까지 시작하지 않는다. 앱 기동·회원 복제본 리스너(가입 환영·승급 승인 포함)·챗봇·REST·WebSocket은 기다리지 않는다 | ND-078, ND-080 |
| 실패 | 일시 장애는 미완료로 두고 백오프로 다시 시도. 복제할 수 없는 회원은 건너뜀(회원 ID 기록), 완료 판정을 막지 않음. 결과 수는 로그·메트릭·관리자 실행 조회(ND-092) | ND-078 |

- 선례: 지갑 관리자 회원 Backfill([지갑 결정 기록](../billing/decisions.md) BD-132·BD-133·BD-138).
- 관련 테스트: `NT-USR-04`(ND-035), `NT-USR-06`·`NT-USR-08`~`NT-USR-10`(ND-077~ND-079) ([test-spec.md](test-spec.md)).

## 6. 미결 설계

본문은 [decisions.md](decisions.md) 10장에 있다. 여기서는 이 모듈 설계에 걸리는 항목만 가리킨다.

| ID | 걸리는 설계 | 막는 작업 |
| --- | --- | --- |
| U5 | N5 밖의 이미 발행 중인 이벤트의 원본 대상 ID 필드(원칙은 ND-033·ND-045, N5는 ND-073) | 각 이벤트 소비 작업(N6 이후)의 유일 인덱스 값 |
| U6 / T5 | EC2 운영 시간과 예약 배치. 알림 담당 결정 ND-134로 구현, 팀 확인 대기 | — (#928 PR 리뷰에서 팀 확인) |
| U13 | Hikari·스케줄 스레드·메모리 예산(챗봇과 공유). N1은 지금 값 유지·소비자 동시성 1(2026-11-09 인프라 담당 확인). N3는 예산 유지·연결 수 상한 없음·연결 수 단계별 측정(알림 담당 제안, 확정 전). N4는 상한 없이 운영에 열고 관측(ND-066) | N3·N10 자원 산정 |
| U14 | WebSocket 경로 Gateway 시간 제한. 알림 쪽은 ND-062·ND-063, 남은 것은 인프라 담당 확인(ND-065) | Envoy route(N4) 병합 |
| U15 | Gateway `claimToHeaders`에 `exp` 추가. 알림 쪽은 ND-055·ND-056·ND-064, 남은 것은 인프라 담당 확인(ND-065) | Envoy route(N4) 병합 |
| U3 | N5 밖 종류별 실제 문구(원칙은 ND-039·ND-050, N5는 ND-075) | 각 종류를 소비하는 작업(N6 이후) |
| U2 / T3 | P0 완료 기준 | 작업 계획 |
| U12 | 채팅과 동시 표시(화면, FE 안건). 규칙 밖 종류 코드 U11은 ND-137로 끝났다 | FE |
| T8 | chat-app 불변식과 ND-004의 방향 차이 | 채팅 소유, 이 모듈 구조에는 영향 없음 |
| U19 | 구조는 ND-051로 정했다. 남은 것은 구현 가능성 검증(`NT-USR-07`) | 알림 저장(N2, #801) 구현 |

## 7. 갱신 이력

| 날짜 | 내용 |
| --- | --- |
| 2026-11-13 | ND-133~ND-143 반영(N10 사전 결정, #928): 3.2 예약 행을 결정 참조로 고치고 예약 행 정리 행을 더했다. 6장에서 U16 행을 지우고 U6·U11 행을 결정 범위로 고쳤다 |
| 2026-11-13 | N10 구현(#928, ND-144~ND-148 포함): 2장 As-Is에 예약 테이블·예약 입력·예약 배치 행을 더했다(S6 리뷰 뒤 실패 횟수·발생 시각 반영). PR #929 Bot 리뷰 뒤 이의 제기 접수 취소(ND-149), 기한 지난 예약 먼저 건너뜀·기한 남은 예약만 선택, 잠근 뒤 시각으로 판정·발생 시각을 반영했다 |
| 2026-11-12 | N7 출연 구현(#908, ND-110~ND-113): 2장에 출연 10종 변환·문구·소비 행을 더했다. 5.2·5.3 M3 행을 #871 발행으로 고쳤다 |
| 2026-11-12 | ND-122~ND-126 반영(N7 고객센터 사전 결정, #918): 3.2에 고객센터 새 리스너·group 행을 더하고, 5.2·5.3 M7 행을 #875 발행과 답변 수정 폐기(ND-123)로 고쳤다 |
| 2026-11-12 | N7 고객센터 구현(#918): 2장에 고객센터 2종 변환·문구·새 소비자 행을 더했다. S6 리뷰 뒤 빈 제목 검사(ND-127)를 적었다 |
| 2026-11-06 | 처음 작성(e054d6f6 기준). As-Is는 코드·설정 파일로 확인 |
| 2026-11-12 | N7 회원 사전 결정(#915, ND-118~ND-121): 5.2·5.3 M6 행을 #874 발행 완료와 소비 Issue로 고쳤다. 구현 뒤 2장 As-Is에 N7 회원 행을 더했다 |
| 2026-11-12 | N7 지갑 4종 구현(#907, ND-105~ND-108): 2장에 N7 지갑 행(변환·문구·소비자 처리기)을 더했다. PR 리뷰 뒤 값 형식(ND-109)을 적었다 |
| 2026-11-12 | ND-097~ND-102 반영(N8 사전 결정, #890): 3.2 보존·하루 묶음 행을 결정 참조로, 6장에서 U9를 지우고 U11 행을 예약 5종으로 고쳤다 |
| 2026-11-12 | N8 구현(#890, ND-103): 2장에 지급 항목 Flyway·하루 묶음 행을 더하고, 알림 생성 행의 강의 원천 서술과 N2 Flyway 행의 "묶음 키 인덱스 없음"을 고쳤다 |
| 2026-11-11 | ND-069~ND-081 반영(N5 사전 결정, #859): 3.1 첫 배포 순서, 3.2 받는 사람·복제본·중복·문구·이동 대상·알림 시작 시각·REST 권한 행, 3.5 회원 정보, 5.4를 확정 To-Be로 고쳤다. 6장에서 U7·U8·U17을 지우고 U3·U5·U11·U12에 결정 범위를 적었다 |
| 2026-11-11 | N6 A단계 구현(#862): 2장에 변환·문구·이전 형식 판별 코드(소비 연결 전) 행을 더했다 |
| 2026-11-11 | ND-086·ND-087 반영(N6 사전 결정, #862): 3.2에 #818 추가 필드 판별과 N5 리스너 재사용 행을 더하고, 5.2 M1 행을 #817 발행으로 고쳤다 |
| 2026-11-11 | N6 B단계 구현(#862, ND-096): 2장 N6 행을 등록 상태로 고치고 N6 소비자 행을 더했다 |
| 2026-11-10 | ND-062~ND-067 반영(N4 사전 결정, #847): 2장 기준을 N3 병합(PR #834) 뒤 dev로, 3장 Gateway 서술·4.2 6단계·6장 U13~U15를 결정 참조로 고쳤다. #847 구현 뒤 2장 REST·STOMP·Gateway·`claimToHeaders` 행을 코드 기준으로 고쳤다 |
| 2026-11-10 | ND-055~ND-059 반영(N3 사전 결정): 2장 기준을 N2 병합(#819)으로, global 필터 행과 3.6·4.2·6장 U13·U15를 결정 참조로 고쳤다 |
| 2026-11-06 | 5장 신설: 발행 앱 코드(dev) 조사로 기존 이벤트 한계(M1~M7), 발행 앱과 정할 계약(합의 전), 회원 복제본 첫 배포 Backfill(U17, 미결)을 적었다. 미결 설계 표에 U16·U17·U18 추가. 결정은 바꾸지 않았다 |
| 2026-11-06 | 미결 설계 표에 U19(닉네임 파기)를 더했다 |
| 2026-11-09 | ND-033~ND-036 반영: 3.2 처리 규칙 표에 참조를 더하고, 3.5·5.4의 복제본 없음 서술을 ND-035 참조로 바꿨다(5.4의 U17 선택지 목록은 decisions.md와 중복이라 지웠다). 6장 U5·U16·U17·U19는 남은 미결만 남겼다. 결정은 바꾸지 않았다 |
| 2026-11-09 | ND-049 반영(#797 리뷰): 2장 As-Is 동기화 서비스 설명과 3.2 복제본 만들기 행을 고쳤다 |
| 2026-11-09 | N1(#797) 구현으로 2장 As-Is를 고쳤다: 모듈 등록, schema·`notification_user`, 회원 복제본 소비자, Kafka 소비·DLT 공통 설정 |
| 2026-11-09 | ND-046~ND-048 반영: 3.1에 테이블 생성 시점·consumer group·첫 배포 순서를 더하고, 3.3 group 이름을 ND-047로 바꿨다. 6장에서 U1을 지우고 U7·U17의 막는 작업을 N5 배포로, U13에 알림 담당 제안을 적었다 |
| 2026-11-09 | #794 리뷰 반영: 3.2 예약보다 먼저 온 취소 행과 6장 U16에 예약 짝 키 위치(event-contracts 0.3)를 더했다 |
| 2026-11-09 | ND-045 반영: 3.2 중복 판정 행과 6장 U5에 회차 번호 규칙과 이벤트 계약 요청 위치를 더했다 |
| 2026-11-09 | ND-037~ND-044 반영: 5.3을 이벤트 계약 요청 참조로 바꾸고 M4·M5·M6 작업을 고치고 M8을 더했다. 3.2·3.5·5.2·6장의 U3·U4·U10·U11·U18 서술을 결정 참조로 바꿨다 |
| 2026-11-09 | PR 리뷰 반영(#785): 3.5의 복제본 없음 서술에 DLT 재처리 경로(3.7) 참조를 더했다 |
| 2026-11-09 | ND-050~ND-053 반영(#801 사전 결정): 3.2에 문구·파기 닉네임·보존 동시성·REST 권한 행을 고치거나 더하고, 6장 U3·U8·U19를 맞췄다 |
| 2026-11-10 | N2(#801) 구현으로 2장 As-Is에 notification 테이블, 저장 서비스, 값 규칙·파기 처리, REST를 더했다 |
| 2026-11-10 | ND-054 반영(#801): 2장 값 규칙 행에 파기 표시·파기 상태 복제본·회원 단위 잠금, 3.2에 파기 뒤 저장 행을 더했다 |
| 2026-11-10 | #801 PR 리뷰 반영: 3.2에 같은 알림 행의 동시 쓰기와 "동시성 설계"(칸별 조건부 UPDATE, ID 오름차순 행 잠금, 잠금 순서)를 더하고, 2장 As-Is의 테스트 수치를 지워 regression-log를 가리키게 했다 |
| 2026-11-12 | N7 강의 사전 결정(#910, ND-114~ND-117): 5.3 M4 행을 #872 발행 완료와 소비 Issue로 고쳤다. 구현 뒤 2장 As-Is에 N7 강의 행을 더했다 |
| 2026-11-12 | N9 채팅 사전 결정(#921, ND-129~ND-132): 5.2에 M8 행을 더하고 5.3 M8 행을 #897 발행 완료와 소비 Issue로 고쳤다. 구현 뒤 2장 As-Is에 N9 채팅 행을 더하고 시작 순서 행의 컨테이너 목록을 고쳤다 |
