# 알림(notification) 모듈 — 회귀 실행 기록

> 문서 탐색: [알림 문서 INDEX](INDEX.md)

> 문서 지도: [모듈](module.md) · [결정 기록](decisions.md) · [알림 종류](notification-catalog.md) · [API 계약](../api/notification.md) · [테스트 검증 기준](test-spec.md) · **[회귀 실행 기록](regression-log.md)** · [진행](../../apps/auxiliary-app/boundedContext/notification/PROGRESS.md)
>
> - **역할**: 이 문서는 알림 모듈 테스트를 **실제로 실행한** 기록만 쌓는다: 언제, 어느 커밋에서, 무슨 명령을 돌렸고, 결과가 어땠고, 실패는 무엇 때문이었나. 무엇을 검증해야 하는지(합격 기준)는 [test-spec.md](test-spec.md)의 `NT-*`에 있다.
> - **현재 상태**: 2026-11-09 N1(#797) 모듈 뼈대 테스트를 실행했고(NR-001~NR-004, dev 병합 뒤 NR-006·NR-007, 리뷰 반영 NR-008·NR-009, N2 NR-010~NR-015) 로컬 기동·자원을 측정했다(NR-005). 알림 생성 코드는 아직 없다.
> - **설계 문서의 완료 증거를 실행 기록으로 옮기지 않는다.** [알림 설계](../architecture/infrastructure/notification.md) 11장의 완료 증거와 test-spec 3장의 매핑은 "무엇을 보여야 하는가"이지 실행 결과가 아니다. 증거를 실제로 실행·시연했을 때만 이 문서에 기록을 남긴다.

## 1. 작성 규칙과 기록 양식

### 1.1 작성 규칙

1. **실행하지 않은 것은 성공으로 적지 않는다.** 계획했지만 돌리지 않은 단계는 `미실행`으로 적는다.
2. 건수는 테스트 결과 XML(`build/test-results/test/TEST-*.xml`, 알림 모듈은 `apps/auxiliary-app/boundedContext/notification/build/...` 또는 실행한 프로젝트의 경로)에서 센다. 콘솔 요약이나 기억으로 적지 않는다. `UP-TO-DATE`·`FROM-CACHE`로 건너뛴 태스크는 새 실행이 아니다(`--rerun-tasks` 또는 `cleanTest`).
3. 실행마다 새 ID(`NR-001`, `NR-002`, …)를 쓴다. 새 기록은 3장 맨 위에 더하고, 지난 기록은 고치지 않는다. 잘못 적었으면 새 기록에 정정을 남긴다.
4. 실패마다 원인 분류(2.1)를 하나 붙인다. 분류하지 못했으면 `미분류`로 두고 4장 미해결 표에 올린다.
5. 관련 NT ID를 적는다. 명세 상태가 "미결"인 NT를 실행했으면 결과를 합격·불합격으로 판정하지 않고 `F-SPEC` 또는 메모로 남긴다.
6. 환경 정보에 비밀 값(내부 API 키, DB 비밀번호, JWT, 연결 티켓 값)을 적지 않는다.
7. 커밋하지 않은 변경이 있으면 "작업 트리 변경"에 파일 목록을 적는다. 커밋 해시만으로는 재현할 수 없다.
8. E2E(k3d)·수동 시연도 실행 기록이다. 명령 대신 절차와 확인 방법(로그·lag·화면)을 적는다. 운영 cluster(`hapbang-k3s`)에서 확인했으면 그렇게 적는다.
9. [test-spec.md](test-spec.md)는 고치지 않는다(실행 결과를 적지 않는 문서다).

### 1.2 기록 양식 (복사용)

<!--
#### NR-NNN · YYYY-MM-DD · <결과>

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-NNN |
| 실행 날짜 | YYYY-MM-DD HH:mm~HH:mm (KST) |
| 실행자 | 사람 / Claude Code / Codex CLI |
| 브랜치 | |
| 커밋 | (git rev-parse --short HEAD) |
| 작업 트리 변경 | 없음 / 파일 목록 |
| 대상 기능 | |
| 관련 NT ID | NT-…, NT-… (명세 상태가 미결인 것은 표시) |
| 명령 | (그대로 복사. E2E·수동은 절차) |
| 실행 환경 | OS, JDK, Gradle, Docker 버전·실행 여부, 실제로 뜬 컨테이너(Testcontainers postgres / kafka / redis), k3d 여부, `TZ`, 메모리. 비밀 값 금지 |
| 결과 | 통과 / 실패 / 부분 / 중단 / 미실행 |
| 건수 | 성공 n, 실패 n, 오류 n, 건너뜀 n (XML 기준) |
| 실패 원인 분류 | 실패마다 2.1 코드 하나. 실패 없으면 "실패 없음" |
| 수정 내용 | 고친 코드·테스트·설정과 이유. 없으면 "없음" |
| 재실행 결과 | 다시 돌린 명령과 결과. 없으면 "재실행 안 함" |
| 미검증 항목 | 이번에 확인하지 못한 NT와 이유(이벤트 미발행, 미결 U, 환경) |
| 관련 변경 | Issue·PR 번호 |

**실패 목록**

| 테스트 (클래스#메서드) | NT | 오류 요약 (첫 줄) | 원인 분류 | 근거 | 조치·Issue |
| --- | --- | --- | --- | --- | --- |
| | | | | | |

**메모**: 재현 조건, 다음 실행에서 확인할 것
-->

## 2. 분류 기준

### 2.1 실패 원인 분류

| 코드 | 분류 | 뜻 | 후속 |
| --- | --- | --- | --- |
| F-CODE | 코드 결함 | 알림 모듈 운영 코드가 합격 기준과 다르다(진짜 회귀). 예: 중복 이벤트로 알림 2건, 다른 회원 알림 노출, 21번째 저장 뒤 20개 초과 | Issue 생성, 수정 |
| F-BND | 경계 위반 | `AuxiliaryModularityTest`·`verifyModuleStructure` 실패, 다른 schema 참조, 다른 업무 모듈 직접 참조 | 의존 제거. 경계 규칙을 바꿔야 하면 먼저 질문 |
| F-CONTRACT | 계약 불일치 | 받은 이벤트의 필드·`eventType` 헤더·토픽·key가 shared 계약이나 [kafka.md](../architecture/infrastructure/kafka.md) 토픽 표와 다르다. API 응답이 [API 계약](../api/notification.md)과 다르다 | 발행 모듈 담당 또는 계약 문서와 대조 |
| F-COMM | 미구현 연동 | 필요한 발행 이벤트(N 8장 M1~M8), Gateway route, 토큰 헤더 등이 아직 없다 | 해당 작업 Issue에 연결. 코드 결함으로 세지 않는다 |
| F-SPEC | 명세 미결 | 합격 기준이 미결 U에 달려 있어 판정할 수 없다 | [decisions.md](decisions.md) 10장 해당 U에 연결 |
| F-TEST | 테스트 결함 | 테스트의 가정·데이터·타이밍 문제. 비동기 소비·STOMP 수신 대기 시간 부족 같은 불안정 포함 | 테스트 수정 Issue |
| F-ENV | 환경 | Docker 없음, Testcontainers 기동 실패, 메모리 부족, 포트 충돌, k3d 문제 | 환경을 기록하고 재실행 |
| F-CFG | 설정 | 필수 환경 변수·프로필·yaml·Flyway 위치 차이 | auxiliary-app README "실행과 설정"과 대조 |
| 미분류 | — | 원인을 아직 모른다 | 4장 미해결 표에 올린다 |

### 2.2 결과 표기

| 표기 | 뜻 |
| --- | --- |
| 통과 | 실행했고 실패·오류 0 |
| 실패 | 실패 또는 오류가 1건 이상 |
| 부분 | 일부 테스트·NT만 실행했다(관련 NT ID 칸에 적는다) |
| 중단 | 빌드·컨텍스트·환경 문제로 끝까지 실행하지 못했다 |
| 미실행 | 계획했지만 실행하지 않았다 |

## 3. 실행 기록

_새 기록은 1.2 양식을 복사해 이 줄 바로 아래(가장 최근 기록 위)에 붙인다._

#### NR-043 · 2026-11-11 · 통과 (#859 PR CI 실패 원인 수정)

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-043 |
| 실행 날짜 | 2026-11-11 15:15~15:25 (KST) |
| 실행자 | Claude Code (CI 실패는 GitHub Actions, 사용자가 로그 전달) |
| 브랜치 | `feature/tkt-859` |
| 커밋 | `5bf3e3be`(CI 실패 커밋) + 작업 트리 |
| 작업 트리 변경 | `NotificationAlertListenerStarterTest`: 대기 게이지 0 단정을 컨테이너 시작과 같은 `untilAsserted` 안으로 옮김(운영 코드 변경 없음) |
| 대상 기능 | 시험 경합(CI에서만 실패) |
| 관련 NT ID | NT-USR-09 |
| 명령 | 재현: `[DEBUG-859a]` 임시 지연(컨테이너 시작과 게이지 0 사이 1.5초)을 넣고 `./gradlew :notification:test --tests "*NotificationAlertListenerStarterTest"` → 수정 뒤 같은 명령 → 지연 제거 뒤 같은 명령 → `./gradlew :notification:test verifyModuleStructure :auxiliary-app:check` |
| 실행 환경 | 로컬은 NR-036과 같다. CI는 GitHub Actions(ubuntu) |
| 결과 | CI 실패 → 로컬 재현(지연 주입, 같은 90행·`expected 0.0 but was 1.0`) → 수정 뒤 지연 있는 채로 통과 → 지연 제거 뒤 통과 → 3명령 통과(Gradle 종료 코드 0) |
| 건수 | 3명령: `:notification` 성공 178, 실패 0, 오류 0, 건너뜀 0(새로 실행, XML 기준). `:auxiliary-app` test는 `UP-TO-DATE`(NR-042 결과 13, 실패 0) |
| 실패 원인 분류 | 시험 경합. Starter는 출연·지갑 컨테이너를 시작한 **뒤에** 대기 게이지를 0으로 바꾸는데, 시험은 컨테이너가 실행 중인 것만 기다리고 게이지 0을 곧바로 단정했다. 느린 CI에서 단정이 게이지 갱신보다 먼저 실행됐다. 기각한 가설: CI의 여러 MeterRegistry(OTLP), Backfill 재실행으로 게이지가 다시 1 |
| 수정 내용 | 게이지 0도 함께 기다리게 시험을 고쳤다. 운영 순서(시작 뒤 0)는 "기다리는 중"의 뜻에 맞아 그대로 둔다. 임시 지연은 지웠다(`DEBUG-859a` 검색 결과 없음) |
| 재실행 결과 | 위와 같다 |
| 미검증 항목 | CI 재실행 결과(Push 뒤 확인). NR-042의 미검증 항목은 그대로 |
| 관련 변경 | #859 |

#### NR-042 · 2026-11-11 · 통과 (#859 ND-095 알림 시작 시각 첫 기동 기록, 두 번째 실행)

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-042 |
| 실행 날짜 | 2026-11-11 14:49~14:51(1차, 실패), 14:51~14:54(2차, 통과) (KST, 끝 시각은 실행 로그 파일 기준) |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-859` |
| 커밋 | `01fbac8b` + 작업 트리 |
| 작업 트리 변경 | Flyway `V202611111530__create_notification_runtime_setting.sql`, 새 `AlertStartStore`·`JdbcAlertStartStore`, `AlertStartPolicy`(설정 우선, 없으면 빈 초기화 때 DB 기록), `application-prod.yaml`(임시 값 제거). 시험: `AlertStartPolicyConfigTest`(4), 새 `JdbcAlertStartStoreTest`(2), `AlertStartPolicyTest`, `NotificationModuleIntegrationTest`(테이블 목록), `AuxiliaryApplicationTest`(설정 없이 기동·기록 행). 문서 |
| 대상 기능 | ND-095(U20 해소) |
| 관련 NT ID | NT-KAF-04 |
| 명령 | `./gradlew :notification:test verifyModuleStructure :auxiliary-app:check` |
| 실행 환경 | NR-036과 같다 |
| 결과 | 1차 실패(Gradle 종료 코드 1), 2차 통과(종료 코드 0, 2분 41초) |
| 건수 | 2차: `:notification` 178, `:auxiliary-app` 13(첫 기동 기록 시험 포함), 실패 0(새로 실행). `:auxiliary-global`·`:chatbot`·`:search` test는 `UP-TO-DATE` |
| 실패 원인 분류 | 1차: `NotificationModuleIntegrationTest#테이블은 처음 쓰는 작업에서 만든다`가 notification schema 테이블 목록을 둘(`notification`, `notification_user`)로 단정해, ND-095로 생긴 `notification_runtime_setting` 때문에 실패(결정이 바꾼 기대값, 시험 갱신 누락) |
| 수정 내용 | 그 시험의 기대값에 `notification_runtime_setting`을 더하고 이름을 N5 기준으로 고쳤다 |
| 재실행 결과 | 위 명령 2차 통과 |
| 미검증 항목 | 운영 첫 배포에서 실제로 행이 생기는지(E2E). 여러 pod 동시 첫 기동은 같은 DB에 8개 스레드로 흉내 냈다. NR-040의 미검증 항목은 그대로 |
| 관련 변경 | #859 |

#### NR-041 · 2026-11-11 · 통과 (#859 PR 리뷰 반영 뒤 origin/dev 병합)

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-041 |
| 실행 날짜 | 2026-11-11 14:25~14:28 (KST, 끝 시각은 실행 로그 파일 기준) |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-859` |
| 커밋 | `d53257ca`(`d33f12d3` + origin/dev `7690abd4` 병합: #861 쿠폰, #864 검색 기능 스위치 제거) |
| 작업 트리 변경 | 없음 |
| 대상 기능 | dev 병합 뒤 회귀. 겹친 파일 `AuxiliaryApplicationTest`(검색 소비 끄기 줄 추가)·`application-prod.yaml`(검색 주석)은 자동 병합, 알림 설정 줄과 검색 줄이 함께 남은 것을 확인 |
| 관련 NT ID | NR-040과 같다 |
| 명령 | `./gradlew :notification:test verifyModuleStructure :auxiliary-app:check` |
| 실행 환경 | NR-036과 같다 |
| 결과 | 통과(종료 코드 0, 2분 48초) |
| 건수 | `:auxiliary-app` 12, `:search` 159 새로 실행·실패 0(병합으로 입력이 바뀜). `:notification` 175·`:auxiliary-global` 108·`:chatbot` 135는 `UP-TO-DATE`(NR-040 결과, 실패 0). `verifyModuleStructure` 새로 실행·통과 |
| 실패 원인 분류 | 실패 없음 |
| 수정 내용 | — |
| 재실행 결과 | — |
| 미검증 항목 | NR-040과 같다 |
| 관련 변경 | #859 |

#### NR-040 · 2026-11-11 · 통과 (#859 PR 리뷰 반영 뒤)

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-040 |
| 실행 날짜 | 2026-11-11 14:18~14:21 (KST, 끝 시각은 실행 로그 파일 기준) |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-859` |
| 커밋 | `ca66ec01` + 작업 트리 |
| 작업 트리 변경 | 관리자 Backfill 비동기(ND-092): 새 `BackfillRun`·`BackfillRunStatus`·`BackfillRunStore`·`RedisBackfillRunStore`·`NotificationAdminBackfillExecutorConfig`, `NotificationAdminFacade`(시작·조회, 잠금·기록 실패 처리), `ApiV1NotificationAdminController`(202·GET), `NotificationUserBackfillService`(`acquire`·`runLocked`), `NotificationErrorCode`(`BACKFILL_STATUS_UNAVAILABLE`·`BACKFILL_RUN_NOT_FOUND`). 대기 게이지·정지 ERROR(ND-093): `NotificationAlertListenerStarter`, `k8s/platform/base/alerting/rules.yaml`(`hapbang-notification-backfill-waiting`), `docs/architecture/infrastructure/observability.md` 표. 시험: `ApiV1NotificationAdminControllerTest`(7), `NotificationAlertListenerStarterTest`(게이지·STALLED), `NotificationMemberAlertTest`(+저장 실패 롤백). 문서 |
| 대상 기능 | PR 리뷰 6건 반영(U20 확정 방식 ND-094, 비동기 Backfill, 롤백 시험, 정지 alert, AGENTS·PROGRESS) |
| 관련 NT ID | NT-USR-09·10, NT-GEN-16 |
| 명령 | `./gradlew :notification:test verifyModuleStructure :auxiliary-app:check`, `kubectl kustomize k8s/platform/base` |
| 실행 환경 | NR-036과 같다 |
| 결과 | 통과(종료 코드 0, 2분 33초). kustomize 빌드 통과 |
| 건수 | `:notification` 175, 실패 0(새로 실행). `:auxiliary-app` 12, 실패 0(새로 실행). `:auxiliary-global`·`:chatbot`·`:search` test는 `UP-TO-DATE` |
| 실패 원인 분류 | 실패 없음(이 실행). 구현 중 RED: 정지 ERROR 표식 없음(시간 초과), 관리자 API 컨텍스트 실패(실행 상태 저장소 없음)·대기 게이지 없음 |
| 수정 내용 | 위 작업 트리 변경 |
| 재실행 결과 | — |
| 미검증 항목 | ND-093 여러 pod에서의 지표 의미(앱별 max·30분 지속)는 규칙·문서로만 두고 실행 검증하지 않았다(Grafana alert 평가는 운영에서만). pod 재시작으로 RUNNING이 남는 경우는 오래된 기록을 직접 넣어 UNKNOWN을 봤다(재시작 재현 아님). NR-038의 미검증 항목(동시 경합, 환불·환전 Kafka 경로, 운영 E2E)은 그대로 |
| 관련 변경 | #859 PR 리뷰 |

**메모(정정)**: NR-038의 실행 날짜는 실제로 13:39~13:42, NR-039는 13:50이다(실행 로그 파일의 끝 시각 기준). 두 기록은 시각을 어림해 적었다. 지난 기록은 고치지 않고 여기 남긴다(1.1 규칙 3).

#### NR-039 · 2026-11-11 · 통과 (#859 origin/dev 병합 뒤, 시험 태스크 UP-TO-DATE)

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-039 |
| 실행 날짜 | 2026-11-11 14:05 (KST) |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-859` |
| 커밋 | `a9efa88b`(`56634220` + origin/dev `e9a37bbd` 병합) |
| 작업 트리 변경 | 없음 |
| 대상 기능 | dev 병합(#858: k8s kustomization·인프라 문서·alert 규칙, 알림 코드와 겹치는 파일 없음) 뒤 회귀 |
| 관련 NT ID | NR-038과 같다 |
| 명령 | `./gradlew :notification:test verifyModuleStructure :auxiliary-app:check` |
| 실행 환경 | NR-036과 같다 |
| 결과 | 통과(종료 코드 0, 6초) |
| 건수 | 새로 실행한 시험 없음: `:notification:test`·`:auxiliary-app:test`·`:auxiliary-global`·`:chatbot`·`:search` test 모두 `UP-TO-DATE`(병합이 시험 입력을 바꾸지 않음. 결과 XML은 NR-038의 `:notification` 171·`:auxiliary-app` 12, 실패 0). `verifyModuleStructure`는 새로 실행·통과 |
| 실패 원인 분류 | 실패 없음 |
| 수정 내용 | — |
| 재실행 결과 | — |
| 미검증 항목 | NR-038과 같다 |
| 관련 변경 | #859 |

#### NR-038 · 2026-11-11 · 통과 (#859 S6 리뷰 반영 뒤)

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-038 |
| 실행 날짜 | 2026-11-11 13:43~13:46 (KST) |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-859` |
| 커밋 | `35735bf1` + 작업 트리 |
| 작업 트리 변경 | NR-037 목록 + `NotificationAlertService`(받는 사람 확인을 언급 회원 닉네임 조회보다 먼저, 로그 형식), `NotificationAdminFacade`(장애는 503 `BACKFILL_FAILED`, ND-089), `NotificationErrorCode`(`BACKFILL_FAILED`), `NotificationAlertListenerStarter`(종료 신호 뒤 시작 안 함, 잠금 경쟁은 INFO), `NotificationUserBackfillService`(로그 ID 20개, JVM 시각), `NotificationUserRepository`(시각 파라미터), `AlertDrafts`(`appliedAt`), `AlertTemplate`(`PayoutType`), 리스너 import·로그. 시험: `NotificationAlertEventListenerTest`(+받을 수 없는 회원·언급 복제본 없음, +탈퇴), `NotificationMemberAlertTest`(+시작 시각 이전 승급), `ApiV1NotificationAdminControllerTest`(+503), 새 `NotificationMentionRetryTest`(ND-091 재시도) |
| 대상 기능 | S6 리뷰 결함(받을 수 없는 회원인데 언급 회원 복제본 없음으로 DLT) 수정, ND-089~ND-091 |
| 관련 NT ID | NT-GEN-07·16, NT-KAF-07, NT-USR-03·09·10 |
| 명령 | `./gradlew :notification:test verifyModuleStructure :auxiliary-app:check` |
| 실행 환경 | NR-036과 같다 |
| 결과 | 통과(종료 코드 0, 2분 40초) |
| 건수 | `:notification` 171, 실패 0(새로 실행). `:auxiliary-app` 12, 실패 0(새로 실행). `:auxiliary-global`·`:chatbot`·`:search` test는 `UP-TO-DATE`(입력 변경 없음, 마지막 새 실행 NR-036) |
| 실패 원인 분류 | 실패 없음(이 실행). 수정 전 RED: `받을_수_없는_회원이면_언급_회원_복제본이_없어도_DLT_없이_건너뛴다`(결함 재현), `끝까지_읽지_못하면_결과_없이_503_BACKFILL_FAILED다`(500). `NotificationMentionRetryTest`는 수정 전부터 통과(재시도 가능 분류 확인용) |
| 수정 내용 | 위 작업 트리 변경 |
| 재실행 결과 | — |
| 미검증 항목 | NT-USR-08 실제 동시 경합(순서 실행만), NT-GEN-16의 알림 저장 실패 때 복제본 롤백(저장 실패를 일으킬 수단이 없어 시험하지 않음), 환불·환전 4종 Kafka 경로(단위 시험만), 운영 회원 API Backfill·첫 배포(E2E) |
| 관련 변경 | #859 |

#### NR-037 · 2026-11-11 · 통과 (ND-088 시작 시각 설정 검증, 대상 시험만)

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-037 |
| 실행 날짜 | 2026-11-11 13:23~13:25 (KST) |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-859` |
| 커밋 | `35735bf1` + 작업 트리 |
| 작업 트리 변경 | NR-036 목록 + `AlertStartPolicy`(설정 문자열을 `OffsetDateTime`으로 직접 읽음), 새 `AlertStartPolicyConfigTest`, `AlertStartPolicyTest`(생성자 두 개), `application-prod.yaml` 주석 |
| 대상 기능 | 알림 시작 시각 값이 없거나 잘못되면 기동 실패, 운영 형식(`+09:00`) 읽기 |
| 관련 NT ID | NT-KAF-04 |
| 명령 | `./gradlew :notification:test --tests "*AlertStartPolicyConfigTest" --tests "*AlertStartPolicyTest" --tests "*NotificationAlertEventListenerTest"` |
| 실행 환경 | NR-036과 같다 |
| 결과 | 통과 |
| 건수 | 3 + 3 + 9, 실패 0 |
| 실패 원인 분류 | 첫 실행에서 `운영과_같은_형식의_값은_그_시각을_그대로_쓴다` 실패: 빈 컨텍스트에 Boot 변환 서비스가 없어 `String → Instant` 변환 불가(시험 환경). 운영 형식 변환을 Spring에 기대지 않게 `AlertStartPolicy`가 직접 읽도록 고쳤다. 두 번째 실행은 `AlertStartPolicyTest`의 `new AlertStartPolicy(null)` 모호 호출로 컴파일 실패(시험 코드) → 형 지정 |
| 수정 내용 | 위와 같다 |
| 재실행 결과 | 위 명령 통과 |
| 미검증 항목 | 전체 3명령은 Phase 4 리뷰 반영 뒤 다시 돌린다 |
| 관련 변경 | #859 |

#### NR-036 · 2026-11-11 · 통과 (N5 #859 이벤트 소비 구현)

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-036 |
| 실행 날짜 | 2026-11-11 13:00~13:07 (KST) |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-859` |
| 커밋 | `35735bf1` + 작업 트리 |
| 작업 트리 변경 | 알림 모듈: 새 `AlertDrafts`·`AlertTemplate`·`AlertStartPolicy`·`NotificationAlertService`·`NotificationMemberAlertService`·`NotificationUserBackfillService`·`NotificationMemberSource`·`NotificationJob`·`BackfillResult`·`NotificationAdminFacade`·`ApiV1NotificationAdminController`·`NotificationAppearanceEventListener`·`NotificationBillingEventListener`·`NotificationAlertListenerStarter`·`MemberApiNotificationMemberSource`, 고친 `ReceiverPolicy`·`NotificationWriter`·`NotificationWriteResult`(`SKIPPED_RECEIVER`)·`NotificationTemplates`·`NotificationErrorCode`·`NotificationMemberEventListener`·`NotificationKafka{Config,Constants,ErrorHandlerConfig,TopicConfig}`·`NotificationUserRepository`. 시험: 새 `AlertDraftsTest`·`AlertStartPolicyTest`·`ReceiverPolicyTest`·`NotificationUserBackfillServiceTest`·`ApiV1NotificationAdminControllerTest`·`NotificationAlertEventListenerTest`·`NotificationMemberAlertTest`·`NotificationAlertListenerStarterTest`·`AlertEventKafka`·`FakeMemberSource`, 고친 `NotificationWriterTest`·`MemberEventKafka`·`hapbang-module-test.yaml`. 앱: `application-{prod,local}.yaml`(`start-at`), `AuxiliaryApplicationTest`(새 스위치·`start-at`). 문서 |
| 대상 기능 | N5 11종 알림 생성(출연·지갑·회원), 알림 시작 시각·대체 시각, 받는 사람 정책, 회원 복제본 Backfill·시작 순서·관리자 재실행 API |
| 관련 NT ID | NT-GEN-01·03·06·07·10·12·13·15·16, NT-KAF-04·07, NT-IDEM-05, NT-USR-03·04·05·06·08·09·10 (NT-GEN-12는 승급 승인 이름만, 나머지는 미결 U11) |
| 명령 | `./gradlew :notification:test verifyModuleStructure :auxiliary-app:check` |
| 실행 환경 | NR-016과 같다(Windows 10, JDK 25, Docker 29.8.0 Testcontainers PostgreSQL·Redis·Kafka) |
| 결과 | 통과(종료 코드 0, 6분 30초) |
| 건수 | `:notification` 163, 실패 0, 오류 0, 건너뜀 0(새로 실행). `:auxiliary-app` 12, 실패 0(새로 실행). `:auxiliary-global`·`:chatbot`·`:search` test도 새로 실행·통과 |
| 실패 원인 분류 | 실패 없음(이 실행). 구현 중 TDD RED는 기대한 실패라 기록하지 않는다. Docker가 꺼져 `NotificationWriterTest`가 `Could not find a valid Docker environment`로 실패한 적이 있다(환경, Docker를 켜고 다시 실행해 통과) |
| 수정 내용 | — |
| 재실행 결과 | — |
| 미검증 항목 | NT-USR-08의 Backfill과 Kafka 변경이 **동시에** 겹치는 경우는 순서대로(Backfill 뒤 Kafka) 실행해서만 봤다(행 잠금 안 조건부 upsert에 기댐). 환불·환전 4종은 Kafka 경로를 종류마다 돌리지 않고 초안·문구 단위 시험으로 봤다(Kafka 경로는 충전·지급·송금 실패로 확인). `MENTIONED_NOT_REPLICATED` 재시도 뒤 DLT는 시험하지 않았다. 운영 회원 API를 실제로 부르는 Backfill과 운영 첫 배포(E2E)는 실행하지 않았다. 지급 제목(NT-GEN-11, M5 전) |
| 관련 변경 | #859 |

#### NR-035 · 2026-11-10 · 통과 (#847 재리뷰 반영)

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-035 |
| 실행 날짜 | 2026-11-10 20:58~21:01 (KST) |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-847` |
| 커밋 | `24b1a2f6` + 작업 트리(NR-034 뒤 시험 1개 변경) |
| 작업 트리 변경 | NR-034 목록 + `NotificationWebSocketConfigTest`(스레드 스냅샷을 이 설정의 접두사 `notification-ws-in-`·`-out-`·`-heartbeat-`로만 셈, 생성 도우미로 중복 제거) |
| 대상 기능 | 재리뷰(S6) 지적: 연결 만료 스케줄러(`notification-ws-expiry-`)가 스냅샷에 섞여 거짓 실패할 위험 |
| 관련 NT ID | NT-RT-07 |
| 명령 | `./gradlew :notification:test`, `./gradlew verifyModuleStructure`, `./gradlew :auxiliary-app:check` |
| 실행 환경 | NR-016과 같다 |
| 결과 | 통과(세 명령 모두 종료 코드 0) |
| 건수 | `:notification` 123, 실패 0(새로 실행). `:auxiliary-app:check`의 test 태스크는 모두 `UP-TO-DATE` |
| 실패 원인 분류 | 실패 없음 |
| 수정 내용 | — |
| 재실행 결과 | — |
| 미검증 항목 | NR-032와 같다 |
| 관련 변경 | #847 |

#### NR-034 · 2026-11-10 · 통과 (#847 Bot 리뷰 반영 뒤 자체 리뷰)

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-034 |
| 실행 날짜 | 2026-11-10 20:50~20:53 (KST) |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-847` |
| 커밋 | `24b1a2f6` + 작업 트리 |
| 작업 트리 변경 | NR-033 목록 + `NotificationWebSocketConfigTest`(잘못된 값이면 스레드를 만들지 않음 단정, 올바른 값 시험의 정리를 try/finally로), `NotificationWebSocketConfig` Javadoc |
| 대상 기능 | 자체 리뷰(S6) 지적 반영 |
| 관련 NT ID | NT-RT-07 |
| 명령 | `./gradlew :notification:test`, `./gradlew verifyModuleStructure`, `./gradlew :auxiliary-app:check` |
| 실행 환경 | NR-016과 같다 |
| 결과 | 통과(세 명령 모두 종료 코드 0) |
| 건수 | `:notification` 123, 실패 0(새로 실행). `:auxiliary-app:check`의 test 태스크는 모두 `UP-TO-DATE`(운영 코드 변경은 Javadoc뿐, 마지막 실행 NR-033·NR-032) |
| 실패 원인 분류 | 실패 없음 |
| 수정 내용 | — |
| 재실행 결과 | — |
| 미검증 항목 | NR-032와 같다 |
| 관련 변경 | #847 |

#### NR-033 · 2026-11-10 · 통과 (#847 PR Bot 리뷰 반영)

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-033 |
| 실행 날짜 | 2026-11-10 20:45~20:49 (KST) |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-847` |
| 커밋 | `24b1a2f6` + 작업 트리 |
| 작업 트리 변경 | `NotificationWebSocketConfig`(heart-beat 설정값 0 이하·5분 이상이면 생성자에서 `IllegalArgumentException`, 실행기 생성 전 검사, `MAX_HEARTBEAT`), `NotificationWebSocketConfigTest`(2건 추가), `gateway.yaml` 주석, decisions(ND-068)·api·module·test-spec·이 문서·PROGRESS |
| 대상 기능 | heart-beat 설정값 검증(ND-068) |
| 관련 NT ID | NT-RT-07 |
| 명령 | RED: `./gradlew :notification:test --tests "*NotificationWebSocketConfigTest"`. 회귀: `./gradlew :notification:test`, `./gradlew verifyModuleStructure`, `./gradlew :auxiliary-app:check` |
| 실행 환경 | NR-016과 같다 |
| 결과 | 통과(세 명령 모두 종료 코드 0) |
| 건수 | `:notification` 123(2건 추가), 실패 0. `:auxiliary-app:check`에서 `:auxiliary-app:test` 새로 실행 12, 실패 0. `:auxiliary-global`·`:chatbot`·`:search`는 `UP-TO-DATE`(마지막 실행 NR-032), `:notification:test`는 바로 앞 명령 결과로 `UP-TO-DATE` |
| 실패 원인 분류 | 최종 실패 없음 |
| 수정 내용 | — |
| 재실행 결과 | — |
| 미검증 항목 | NR-032와 같다 |
| 관련 변경 | #847 |

**메모**
- RED: 잘못된 값(0s·-1s·5m·10m) 시험이 "Expecting code to raise a throwable"로 실패했다. 올바른 값(1ms·25s·5분-1ms) 시험은 기존 동작이라 처음부터 통과했다.

#### NR-032 · 2026-11-10 · 통과 (#847 origin/dev 병합 뒤)

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-032 |
| 실행 날짜 | 2026-11-10 19:59~20:07 (KST) |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-847` |
| 커밋 | `aa18750b`(origin/dev `2ed68b3a` 병합, #850 포함) |
| 작업 트리 변경 | 없음 |
| 대상 기능 | 병합 뒤 회귀. NR-031 뒤 `NotificationHeartbeatTest`의 닫힘 뒤 `isOpen()` 단정을 상태가 바뀔 때까지 기다리게 고친 것(#849와 같은 이유) 포함 |
| 관련 NT ID | NR-029와 같다 |
| 명령 | `./gradlew :notification:test`, `./gradlew verifyModuleStructure`, `./gradlew :auxiliary-app:check` |
| 실행 환경 | NR-016과 같다 |
| 결과 | 통과(세 명령 모두 종료 코드 0) |
| 건수 | `:notification` 121, 실패 0. `:auxiliary-app:check`에서 새로 실행: `:auxiliary-app` 12, `:auxiliary-global` 108, `:chatbot` 113, `:search` 160, 실패 0. `:notification:test`는 바로 앞 명령 결과로 `UP-TO-DATE` |
| 실패 원인 분류 | 실패 없음 |
| 수정 내용 | 병합 충돌은 이 문서뿐이었다(#849의 NR-026~NR-028과 이 브랜치의 기록). 두 쪽을 모두 남기고 이 브랜치 기록을 NR-029~NR-031로 바꿨다 |
| 재실행 결과 | — |
| 미검증 항목 | 전체 `./gradlew check`(PR CI), kubeconform(CI manifests job), 병합 뒤 Gateway E2E(manifest 변경 없음, NR-030) |
| 관련 변경 | #847 |

#### NR-031 · 2026-11-10 · 통과 (#847 자체 리뷰 반영)

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-031 |
| 실행 날짜 | 2026-11-10 (KST) |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-847` |
| 커밋 | `c67aa7b6` + 작업 트리 |
| 작업 트리 변경 | NR-029 목록 + `NotificationWebSocketConfig`(heart-beat 값을 `Duration`으로 두고 브로커 설정 때 배열을 만듦), `RawStomp`(`connectAndSubscribe`가 `connect`를 씀), api·INDEX 문서 |
| 대상 기능 | 자체 리뷰(S6) 지적 반영 |
| 관련 NT ID | NR-029와 같다 |
| 명령 | `./gradlew :notification:test`, `./gradlew verifyModuleStructure`, `./gradlew :auxiliary-app:check` |
| 실행 환경 | NR-016과 같다 |
| 결과 | 통과(세 명령 모두 종료 코드 0) |
| 건수 | `:notification` 121, 실패 0(새로 실행). `:auxiliary-app:check`에서 `:auxiliary-app:test` 새로 실행 12, 실패 0. `:auxiliary-global`·`:chatbot`·`:search`·`:notification` test는 `UP-TO-DATE`(마지막 실행 NR-029·바로 앞 명령) |
| 실패 원인 분류 | 실패 없음 |
| 수정 내용 | — |
| 재실행 결과 | — |
| 미검증 항목 | NR-029와 같다. Gateway E2E(NR-030)는 manifest를 바꾸지 않아 다시 돌리지 않았다 |
| 관련 변경 | #847 |

#### NR-030 · 2026-11-10 · 통과 (#847 Gateway E2E, 로컬 k3d)

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-030 |
| 실행 날짜 | 2026-11-10 (KST), 3회 실행 중 마지막 |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-847` |
| 커밋 | `c67aa7b6` + 작업 트리 |
| 작업 트리 변경 | NR-029와 같다 |
| 대상 기능 | Gateway route·인증 경계·`exp` claim·WebSocket stream idle(ND-062~ND-064·ND-067) |
| 관련 NT ID | NT-RT-07(Gateway 쪽), NT-RT-09 |
| 명령 | 레포 밖 스크립트(시험용 backend + `run_e2e.py`, 커밋하지 않음). `k3d cluster create --config k8s/local/k3d.yaml` → `k8s/cluster/namespaces.yaml` → `bash k8s/gateway/tools/install-local.sh` → auxiliary-app local overlay의 HTTPRoute·SecurityPolicy·BackendTrafficPolicy를 경로 그대로 복제해 backend와 JWKS만 시험용으로 바꿔 적용 |
| 실행 환경 | k3d `hapbang`(k3s v1.36.5, 2 node), Envoy Gateway v1.9.3(`install/envoy-gateway`), Docker Desktop 8GiB. platform 없이 Gateway만 설치. tracing backend `hapbang-platform/alloy`가 없어 Gateway가 `Accepted=False`라 이 cluster에만 빈 대역 Service·pod(`alloy-standin`, busybox)를 두었다 |
| 결과 | 통과 21/21(종료 코드 0) |
| 건수 | 상태 7(route 3·SecurityPolicy 1·BackendTrafficPolicy 2 Accepted, WebSocket route가 SecurityPolicy 대상 아님), E1·E1b, E2 2건, E3·E3b, E4 3건, E6 3건, E5 2건 |
| 실패 원인 분류 | 최종 실패 없음. 이전 두 실행의 실패는 시험 장치(아래 메모) |
| 수정 내용 | 시험 스크립트만 고쳤다(제품 코드·manifest 변경 없음) |
| 재실행 결과 | 3회째 21/21 |
| 미검증 항목 | 실제 auxiliary-app·member를 거친 E2E(backend는 앱 동작을 흉내 낸 시험 서버), 실제 15분 만료까지 Envoy를 거친 4401(앱 쪽은 NR-029, Gateway 유지는 E5로 나눠 확인), WebSocket route에서 위조 헤더가 실제로 지워지는지(업그레이드만 확인, 지우는 정책은 Gateway 전체 `ClientTrafficPolicy`라 E4 REST 결과와 같다), 운영 EC2 |
| 관련 변경 | #847 |

**메모**
- 확인 결과: JWT 없는 `GET /api/v1/notifications/ws`와 잘못된 `Authorization`이 실린 요청 모두 Gateway를 지나 101(E1·E1b). 티켓 없음·틀림은 backend 401이 그대로 전달(E2). 티켓 발급 `POST`에 `X-User-Token-Exp` = 토큰 `exp`, 외부 위조 값은 지워지고 JWT 없는 요청에는 헤더가 없음(E3·E4). 잘못된 토큰의 발급 요청은 Envoy 401(E3b). `POST /ws`·`GET /ws/x`는 일반 route(E6).
- E5: 25초마다 양쪽이 heart-beat를 보낸 연결은 330초 뒤에도 열려 있었고(서버 heart-beat 13개), heart-beat 없는 대조 연결은 300.99초에 끊겼다(stream idle 5분).
- 1회째: `GET /ws/x` 웹소켓 업그레이드가 401이 아니라 403이었다. 일반 route는 웹소켓 업그레이드를 받지 않아 Envoy가 403으로 거절한다(본문 제한 정책이 걸린 route로 보임, 원인 확정 안 함). WebSocket route가 받지 않는다는 목적은 맞아 기대값을 "업그레이드 없음 → JWT 401, 업그레이드 → 101 아님"으로 고쳤다.
- 2회째: E3·E4(JWT 요청)가 401이었다. 스크립트가 실행마다 서명 키를 새로 만들고 ConfigMap을 바꾸지만, 이미 떠 있던 backend pod의 마운트가 늦게 갱신되어 이전 JWKS를 내주었다(실행 뒤 마운트와 ConfigMap이 같아진 것을 확인). 시험 자원을 지우고 다시 실행해 통과했다.
- Gateway 전체(상위)에 걸린 SecurityPolicy는 없다(`k8s/gateway/base`는 `kafka-ui` route의 basicAuth뿐). 그래서 WebSocket route를 targetRefs에서 빼면 JWT 필터가 붙지 않는다(ND-067).

#### NR-029 · 2026-11-10 · 통과 (#847 heart-beat 구현)

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-029 |
| 실행 날짜 | 2026-11-10 (KST) |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-847` |
| 커밋 | `c67aa7b6` + 작업 트리 |
| 작업 트리 변경 | `NotificationWebSocketConfig`(STOMP heart-beat 25초, 스케줄러 1스레드 소유·종료), 테스트 `NotificationHeartbeatTest`(새)·`NotificationWebSocketConfigTest`·`RawStomp`(heart-beat 보조), `apps/auxiliary-app/k8s/base/gateway.yaml`, 알림 문서(decisions·module·test-spec·api·PROGRESS·INDEX·AGENTS·이 문서) |
| 대상 기능 | 서버 STOMP heart-beat(ND-063) |
| 관련 NT ID | NT-RT-07(앱 쪽), NT-RT-05·06 회귀 |
| 명령 | RED·GREEN: `./gradlew :notification:test --tests "*NotificationHeartbeatTest"`(+ `*NotificationWebSocketConfigTest`). 회귀: `./gradlew :notification:test`, `./gradlew verifyModuleStructure`, `./gradlew :auxiliary-app:check` |
| 실행 환경 | NR-016과 같다 |
| 결과 | 통과(세 명령 모두 종료 코드 0) |
| 건수 | `:notification` 121, 실패 0. `:auxiliary-app:check`에서 새로 실행: `:auxiliary-app` 12, `:auxiliary-global` 108, `:chatbot` 113, `:search` 132, 실패 0. `:notification:test`는 바로 앞 명령 결과로 `UP-TO-DATE` |
| 실패 원인 분류 | 최종 실패 없음 |
| 수정 내용 | — |
| 재실행 결과 | — |
| 미검증 항목 | 전체 `./gradlew check`, kubeconform(로컬에 없음, CI manifests job), Gateway 경유(NR-030) |
| 관련 변경 | #847 |

**메모**
- RED: `NotificationHeartbeatTest` 5건 중 3건이 기대한 이유로 실패했다(CONNECTED `heart-beat:0,0`, 서버 heart-beat 0개, 클라이언트 heart-beat가 끊겨도 닫히지 않음). 받기 0이면 서버 heart-beat 없음·heart-beat 중 4401은 기존 동작이라 처음부터 통과했다(회귀 보호).
- `NotificationWebSocketConfigTest`는 생성자 인자가 늘어 구현 전에는 컴파일되지 않는 상태로 고쳤고, 실패 출력은 따로 보지 않았다. 구현 뒤 스레드 5개(수신 2·송신 2·heart-beat 1)가 모두 끝나는 것을 확인했다.

#### NR-028 · 2026-11-10 · 통과 (#849 PR 리뷰 3차 반영, NR-027 근거 보충)

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-028 |
| 실행 날짜 | 2026-11-10 17:35~17:37 (KST) |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-849` |
| 커밋 | `6dafcd48` |
| 작업 트리 변경 | 있음(커밋 전). `NotificationRealtimeIntegrationTest`의 구독 거절 확인: 실패 메시지에 실제 대기 시간(6초 = WAIT 5초 + 닫힘 대기 1초) 표시, 설명을 `RawStomp` 큐 동작(닫히기 전에 받은 프레임은 남는다)에 맞게 고침. 이 문서 |
| 대상 기능 | #849 PR 리뷰 3차 |
| 관련 NT ID | NR-026과 같다 |
| 명령 | 두 클래스 `--rerun` 1회 + `./gradlew :notification:test verifyModuleStructure :auxiliary-app:check` |
| 실행 환경 | NR-026과 같다 |
| 결과 | 통과 |
| 건수 | 두 클래스 통과(17:35~17:36). 검증 명령(17:36~17:37): `:notification` 116 새 실행·통과, 나머지 `UP-TO-DATE`(새 실행 아님, 마지막 실행 NR-026 통과), 종료 코드 0 |
| 실패 원인 분류 | 실패 없음 |
| 수정 내용 | 메시지·주석만(시험 판정 동작 변경 없음) |
| 재실행 결과 | — |
| 미검증 항목 | NR-026과 같다 |
| 관련 변경 | #849 |

**메모 — 1002 근거(NR-027 보충)**
- 확인한 대상: spring-websocket 7.0.9 jar의 `org.springframework.web.socket.messaging.StompSubProtocolHandler`, `javap -c -p`로 바이트코드를 봤다.
- `private void sendErrorMessage(WebSocketSession, Throwable)`가 `CloseStatus.PROTOCOL_ERROR`를 읽어 `WebSocketSession.close(CloseStatus)`를 부르는 곳이 3곳 있다. 클라이언트 프레임 처리 실패는 `handleError(WebSocketSession, Throwable, Message)` → `sendErrorMessage`로 이 경로를 탄다.
- `private void sendToClient(WebSocketSession, StompHeaderAccessor, byte[])`도 ERROR 프레임을 보낸 뒤 같은 코드로 닫는 곳이 3곳 있다.
- 알림 모듈에는 별도 STOMP 오류 처리기(`StompSubProtocolErrorHandler` 재정의)가 없다.

#### NR-027 · 2026-11-10 · 통과 (#849 PR 리뷰 반영, NR-026 정정)

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-027 |
| 실행 날짜 | 2026-11-10 17:15~17:20 (KST) |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-849` |
| 커밋 | `7e4aa64d` |
| 작업 트리 변경 | 있음(커밋 전). `NotificationRealtimeIntegrationTest`: 중복·밀려난 알림 시험이 앞서 받은 20건의 `targetId`가 `new-0`~`new-19`와 정확히 같은지 확인, 구독 거절 시험의 닫힘 판정을 `PROTOCOL_ERROR`(1002)로 좁힘. 이 문서 |
| 대상 기능 | #849 PR 리뷰 지적 2건 |
| 관련 NT ID | NR-026과 같다 |
| 명령 | 두 클래스 `--rerun` 3회 + `./gradlew :notification:test verifyModuleStructure :auxiliary-app:check` |
| 실행 환경 | NR-026과 같다 |
| 결과 | 통과 |
| 건수 | 두 클래스 3회 모두 통과(`NotificationRealtimeIntegrationTest` 13건). 검증 명령: `:notification` 116 새 실행·통과, 나머지 `UP-TO-DATE`(새 실행 아님, 마지막 실행 NR-026 통과). 종료 코드 0 |
| 실패 원인 분류 | 실패 없음 |
| 수정 내용 | 아래 메모 |
| 재실행 결과 | 자체 리뷰(import 순서, 저장 목록을 한 번만 만들기) 반영 뒤 17:24~17:25 검증 명령 다시 실행: `:notification` 116 새 실행·통과(`NotificationRealtimeIntegrationTest` 13건), 나머지 `UP-TO-DATE`, 종료 코드 0 |
| 미검증 항목 | NR-026과 같다(CI 재실행 없이 연속 3회, B·C 로컬 재현 못 함) |
| 관련 변경 | #849 |

**메모**
- 1002 근거: spring-websocket 7.0.9 `StompSubProtocolHandler`는 클라이언트 프레임 처리 실패 때(`handleError` → `sendErrorMessage`) ERROR를 보내고 `CloseStatus.PROTOCOL_ERROR`로 닫는다(바이트코드 확인). 알림 모듈에는 별도 STOMP 오류 처리기가 없다.
- **NR-026 정정**: NR-026 메모의 줄 번호(196·117·343행)는 수정 전 파일 기준이라 수정 뒤 파일과 맞지 않는다. 메서드 이름으로 읽는다: A `중복과_밀려난_알림은_프레임을_보내지_않고_중복_방지와_20개_보존은_그대로다`, B `연결_만료가_되면_서버가_연결을_4401로_닫는다`, C `자기_알림_목적지_말고는_구독할_수_없다`, 추가 `Redis가_멈춰도_저장은_끝나고_되돌려지지_않으며_재접속_뒤_목록으로_맞춘다`.

#### NR-026 · 2026-11-10 · 통과 (#849 CI 간헐 실패 시험 수정)

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-026 |
| 실행 날짜 | 2026-11-10 16:20~17:02 (KST) |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-849`(worktree, origin/dev `6d8b1688` 기준) |
| 커밋 | `6d8b1688` |
| 작업 트리 변경 | 있음(커밋 전). 시험만: `NotificationRealtimeIntegrationTest`(중복·밀려난 알림은 먼저 연결하고 앞선 20건을 받아 비움, Redis 멈춤은 멈춘 동안 발행 실패가 기록된 뒤 되살림, 구독 거절은 ERROR 또는 연결 닫힘을 거절로 보고 거절된 연결로 다른 회원 프레임이 오지 않는지 확인), `NotificationConnectionExpiryTest`(만료 뒤 `isOpen()`이 바뀔 때까지 기다림). 운영 코드 변경 없음 |
| 대상 기능 | CI에서 간헐 실패한 실시간 시험(#849) |
| 관련 NT ID | NT-RT-01~06·08(시험 안정성), NT-RET-02·NT-IDEM-01 실시간 부분 |
| 명령 | ① 재현: `Relay.deliver` 앞에 150ms 지연을 임시로 넣고 `:notification:test --tests '*NotificationRealtimeIntegrationTest.중복*'` ② 지연을 둔 채 `--tests '*NotificationRealtimeIntegrationTest' --tests '*NotificationConnectionExpiryTest' --rerun` 3회 ③ 지연을 지우고 같은 명령 5회 ④ `./gradlew :notification:test verifyModuleStructure :auxiliary-app:check` |
| 실행 환경 | Windows 10, JDK 25, Docker Testcontainers(PostgreSQL, Redis 8, Kafka) |
| 결과 | 통과 |
| 건수 | ④ 새 실행(worktree 첫 빌드): `:notification` 116, `:auxiliary-app` 12, `:auxiliary-global` 108, `:chatbot` 113, `:search` 132. 합계 481, 실패 0. ②는 3회 모두, ③은 5회 모두 두 클래스 통과 |
| 실패 원인 분류 | F-TEST(시험이 비동기 전달·세션 상태 전환 시점에 기댐). 운영 코드 결함 아님 |
| 수정 내용 | 아래 메모 |
| 재실행 결과 | 위 ②③④. 자체 리뷰(S6) 반영 뒤 17:05~17:08: 두 클래스 `--rerun` 1회 통과, ④ 다시 실행(`:notification` 116 새 실행·통과, 나머지 `UP-TO-DATE`), 종료 코드 0 |
| 미검증 항목 | CI에서 재실행 없이 연속 3회 통과(이슈 완료 조건, PR 뒤 확인), B·C는 로컬에서 재현하지 못함 |
| 관련 변경 | #849 |

**메모 — 원인**
- A(`중복과_밀려난_알림은…` 196행): 지연 주입으로 재현했다. 받은 프레임은 `"targetId":"new-4"`였다. 연결 전에 저장한 20건의 프레임이 연결 뒤 늦게 도착한 것으로 확정했다. 먼저 연결하고 20건을 받은 뒤 확인하도록 바꿨다.
- 추가(`Redis가_멈춰도…`, 이슈 "공통" 점검 중 발견): 지연 주입 때 함께 실패했다. 발행 시도가 Redis를 되살린 뒤에 일어나면 성공해 `while-down` 프레임이 재접속한 연결로 왔다. 멈춘 동안 `hapbang.notification.realtime.dropped{reason=publish_failed}`가 오를 때까지 기다린 뒤 되살리도록 바꿨다(이 지표 이름·태그에 기댄다).
- B(`연결_만료가_되면…` 117행): 닫힘 콜백 안에서 깨어나 `isOpen()`을 바로 보는 경합으로 추정. 상태가 바뀔 때까지 기다리게 바꿨다(재현 못 함).
- C(`자기_알림_목적지_말고는…` 343행, 이슈 추가 보고): 로그가 없어 원인을 확정하지 못했다. ERROR 프레임 또는 정상 종료(1000)가 아닌 코드의 연결 닫힘을 거절로 보고, 둘 다 없으면 목적지를 실패 메시지에 남기게 바꿨다. 거절된 연결로 다른 회원의 프레임이 오지 않는지 확인을 더했다. `RawStomp`는 닫히기 전에 받은 프레임을 큐에 남긴다(확인).
- 같은 파일의 다른 "프레임 없음" 시험(롤백·commit 전·다른 회원·읽음·파기·SEND)은 앞선 저장이 없거나 먼저 비우므로 같은 위험이 없다.

#### NR-025 · 2026-11-10 · 통과 (#829 Bot 리뷰 2차 뒤 자체 리뷰)

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-025 |
| 실행 날짜 | 2026-11-10 14:51~14:53 (KST) |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-829` |
| 커밋 | `006010c2` |
| 작업 트리 변경 | NR-024 목록 + `NotificationRealtimeRelay`(롤백 실패 `TransactionSystemException` 안의 발행 실패도 `publish_failed`로 분류, `publishCause()` 이름), `NotificationRealtimeRelayTest`(언급 회원 없는 경로 발행 실패, 롤백 실패 2건 추가), test-spec |
| 대상 기능 | 자체 리뷰(S6) 지적 반영 |
| 관련 NT ID | NR-022와 같다 |
| 명령 | `./gradlew :notification:test verifyModuleStructure :auxiliary-app:check` 2회 |
| 실행 환경 | NR-016과 같다 |
| 결과 | 통과(두 번째 실행 종료 코드 0, BUILD SUCCESSFUL) |
| 건수 | 첫 실행에서 새로 실행: `:notification` 116, `:auxiliary-app` 12, 실패 0. 두 번째는 모든 테스트 태스크가 `UP-TO-DATE`. `:auxiliary-global`·`:chatbot`·`:search`는 두 번 모두 `UP-TO-DATE`(마지막 실행 NR-022 통과) |
| 실패 원인 분류 | 최종 실패 없음 |
| 수정 내용 | 아래 메모 |
| 재실행 결과 | — |
| 미검증 항목 | NR-022와 같다 |
| 관련 변경 | #829 |

**메모**
- 롤백 실패 시험은 고치기 전 실패했다(191행, `publish_failed` 0). 언급 회원 없는 경로의 발행 실패 시험은 이미 맞는 동작이라 처음부터 통과했다(빈 곳 보강).
- 첫 실행은 출력 줄 수 제한(`Select-Object -First`)이 Gradle 출력을 끊어 종료 코드가 255로 보였다. 제한 없이 다시 실행해 종료 코드 0을 확인했다.

#### NR-024 · 2026-11-10 · 통과 (#829 Bot 리뷰 2차 반영)

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-024 |
| 실행 날짜 | 2026-11-10 14:44~14:47 (KST) |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-829` |
| 커밋 | `006010c2` |
| 작업 트리 변경 | 있음(커밋 전). `NotificationRealtimeRelay`(잠금 경로에서 Redis 발행 실패만 `publish_failed`·생략, 잠금·DB 실패는 `read_failed`), `NotificationWebSocketConfig`(채널 실행기를 소유하고 `destroy()`에서 종료, 미리 띄움), 테스트 `NotificationRealtimeRelayTest`(1건 추가)·`NotificationWebSocketConfigTest`(새), module·test-spec·이 문서·PROGRESS |
| 대상 기능 | Bot 리뷰 2차 2건 |
| 관련 NT ID | NR-022와 같다 |
| 명령 | `./gradlew :notification:test verifyModuleStructure :auxiliary-app:check` |
| 실행 환경 | NR-016과 같다 |
| 결과 | 통과 |
| 건수 | 새로 실행: `:notification` 114, `:auxiliary-app` 12, 실패 0. `:auxiliary-global`(108)·`:chatbot`(112)·`:search`(93)는 `UP-TO-DATE`(새 실행 아님, 마지막 실행 NR-022 통과) |
| 실패 원인 분류 | 최종 실패 없음 |
| 수정 내용 | 아래 메모 |
| 재실행 결과 | — |
| 미검증 항목 | NR-022와 같다 |
| 관련 변경 | #829 |

**메모 — 테스트 먼저(red 확인)**
- `NotificationWebSocketConfigTest`는 처음에 `destroy()`가 없어 컴파일에 실패했다. 빈 `destroy()` 껍데기로 바꾼 뒤 실행하자 미리 띄운 스레드가 없어 실패했다(27행).
- 잠금 경로 `read_failed` 시험은 고치기 전 실패했다(175행, 잠금 실패를 `publish_failed`로 세고 생략함).
- 채널 실행기 시험은 같은 JVM의 다른 시험 컨텍스트 스레드와 섞이지 않게, 이 설정이 만든 스레드만 추적하도록 썼다.

#### NR-023 · 2026-11-10 · 통과 (#829 Bot 리뷰 반영 뒤 자체 리뷰)

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-023 |
| 실행 날짜 | 2026-11-10 14:30~14:32 (KST) |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-829` |
| 커밋 | `d03ef12c` |
| 작업 트리 변경 | NR-022 목록 + `NotificationRealtimeRelay`(DB 다시 읽기 실패를 `read_failed`로 따로 세고 생략 시간을 걸지 않음, 생략 로그 분리, 언급 회원 한 번만 계산), `NotificationRealtimeRelayTest`(read_failed, 언급 회원 칸이 빈 경우 잠금 경로), module 3.8, test-spec |
| 대상 기능 | 자체 리뷰(S6) 지적 반영 |
| 관련 NT ID | NR-022와 같다 |
| 명령 | `./gradlew :notification:test verifyModuleStructure :auxiliary-app:check`(사용자 지시로 cleanTest 없음) |
| 실행 환경 | NR-016과 같다 |
| 결과 | 통과 |
| 건수 | 새로 실행: `:notification` 112, `:auxiliary-app` 12, 실패 0. `:auxiliary-global`(108)·`:chatbot`(112)·`:search`(93)는 `UP-TO-DATE`로 건너뜀(입력 변경 없음, 새 실행 아님. 마지막 실행은 NR-022 통과) |
| 실패 원인 분류 | 최종 실패 없음 |
| 수정 내용 | 아래 메모 |
| 재실행 결과 | — |
| 미검증 항목 | NR-022와 같다 |
| 관련 변경 | #829 |

**메모**
- `read_failed` 시험은 고치기 전 실패했다(`publish_failed`로 셈). 언급 회원 칸이 빈 경우의 잠금 경로 시험은 이미 있던 방어 동작이라 처음부터 통과했다.

#### NR-022 · 2026-11-10 · 통과 (#829 Bot 리뷰 반영)

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-022 |
| 실행 날짜 | 2026-11-10 14:21~14:26 (KST) |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-829` |
| 커밋 | `d03ef12c` |
| 작업 트리 변경 | 있음(커밋 전). `NotificationRealtimeRelay`(언급 회원 없는 알림은 잠금 없이 commit 뒤 발행, 발행 실패 뒤 생략 시간·`skipped` 지표, 복구 로그), 테스트 `NotificationRealtimeRelayTest`(다시 씀)·`NotificationRealtimeIntegrationTest`(출처 403·티켓 미소모, 생략 시간 반영)·`RawStomp`(출처 지정), 문서(module 3.8·As-Is, test-spec, 이 문서, PROGRESS) |
| 대상 기능 | Bot 리뷰 3건: Redis 무응답 때 DB 연결·잠금 점유, `publish_failed`·상태 전환 테스트, 비허용 출처 403·티켓 미소모 |
| 관련 NT ID | NT-RT-01~06·08, NT-READ-05, NT-USR-07(실시간 부분) |
| 명령 | `./gradlew :notification:cleanTest :auxiliary-global:cleanTest :chatbot:cleanTest :search:cleanTest :auxiliary-app:cleanTest :notification:test verifyModuleStructure :auxiliary-app:check` |
| 실행 환경 | NR-016과 같다 |
| 결과 | 통과 |
| 건수 | `:notification` 110, `:auxiliary-app` 12, `:auxiliary-global` 108, `:chatbot` 112, `:search` 93. 합계 435, 실패·오류·건너뜀 0 (XML 기준, 새 실행) |
| 실패 원인 분류 | 최종 실패 없음 |
| 수정 내용 | 아래 메모 |
| 재실행 결과 | — |
| 미검증 항목 | 운영 Redis 장애 상황에서 생략·재시도 동작(단위·통합 테스트로만 확인), NR-016과 같은 항목 |
| 관련 변경 | #829 |

**메모 — 테스트 먼저(red 확인)**
- 고치기 전에 Relay 단위 테스트 2건이 실패했다: 발행 실패 뒤 생략 없음(`skipped` 0), 언급 회원 없는 알림의 잠금 없는 경로 없음. 출처 시험(403·티켓 미소모)은 이미 있는 동작이라 처음부터 통과했다(빈 곳 보강).
- 중간에 껍데기 생성자가 둘이라 Spring이 생성자를 고르지 못해 통합 테스트가 컨텍스트 기동에 실패했다(F-TEST, `@Autowired`로 해결).
- 변형 확인: ① 모든 알림을 잠금 없는 경로로 보내자 파기 경합 통합 시험(ND-054)과 잠금 순서 단위 시험이 실패했다 ② 생략 판단을 빼자 생략 단위 시험이 실패했다. 되돌린 뒤 위 명령으로 통과를 확인했다.

#### NR-021 · 2026-11-10 · 통과 (origin/dev 병합 뒤, NR-020 재실행)

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-021 |
| 실행 날짜 | 2026-11-10 13:59~14:08 (KST) |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-829` |
| 커밋 | `0ee1b1aa`(`0a3c46d3`에 origin/dev `d055d191` 병합, 충돌 없음) |
| 작업 트리 변경 | 이 문서(NR-020·NR-021)만 |
| 대상 기능 | dev 병합 뒤 N3 회귀(검색 모듈 #828, 챗봇 싱크 #831 등 포함) |
| 관련 NT ID | NR-019와 같다 |
| 명령 | NR-020과 같다 |
| 실행 환경 | NR-016과 같다. 사용자가 Docker Desktop을 다시 시작한 뒤 실행 |
| 결과 | 통과 |
| 건수 | `:notification` 106, `:auxiliary-app` 12, `:auxiliary-global` 108, `:chatbot` 112, `:search` 93. 합계 431, 실패·오류·건너뜀 0 (XML 기준, 새 실행) |
| 실패 원인 분류 | 실패 없음 |
| 수정 내용 | 없음 |
| 재실행 결과 | NR-020(F-ENV)의 재실행 |
| 미검증 항목 | 전체 `./gradlew check`(PR CI에 맡김), Gateway를 거친 E2E(N4) |
| 관련 변경 | #829 |

#### NR-020 · 2026-11-10 · 실패 (F-ENV, origin/dev 병합 뒤)

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-020 |
| 실행 날짜 | 2026-11-10 13:57~13:58 (KST) |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-829` |
| 커밋 | `0ee1b1aa`(`0a3c46d3`에 origin/dev `d055d191` 병합) |
| 작업 트리 변경 | 없음(이 기록 제외) |
| 대상 기능 | dev 병합 뒤 N3 회귀 |
| 관련 NT ID | NR-019와 같다 |
| 명령 | `./gradlew :notification:cleanTest :auxiliary-global:cleanTest :chatbot:cleanTest :search:cleanTest :auxiliary-app:cleanTest :notification:test verifyModuleStructure :auxiliary-app:check` |
| 실행 환경 | Docker Desktop이 응답하지 않음(`docker version`: "Docker Desktop is unable to start") |
| 결과 | 실패 |
| 건수 | `:notification` 106 중 71 실패(Testcontainers가 필요한 통합 테스트 전부), 35 통과. `:notification:test`가 실패해 `:auxiliary-app:check`의 나머지 프로젝트는 돌지 않았다 |
| 실패 원인 분류 | F-ENV. 컨텍스트 기동 실패 `PostgresTestcontainersConfig` → `IllegalStateException: Could not find a valid Docker environment` |
| 수정 내용 | 없음(코드 원인 아님) |
| 재실행 결과 | Docker 복구 뒤 재실행 필요 |
| 미검증 항목 | 병합 뒤 전체 범위(통합 테스트, chatbot·search·global·app) |
| 관련 변경 | #829 |

#### NR-019 · 2026-11-10 · 통과 (#829 Redis 명령 시간 제한)

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-019 |
| 실행 날짜 | 2026-11-10 12:46~12:50 (KST) |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-829` |
| 커밋 | `0debc874` |
| 작업 트리 변경 | NR-018 목록 + `apps/auxiliary-app/src/main/resources/application.yaml`(`spring.data.redis.timeout: 10s`), decisions(ND-061, ND-057 일부 보완됨), module·api |
| 대상 기능 | 앱 공통 Redis 명령 시간 제한(알림 담당 결정 Q1 b), ND-061 문서 반영 |
| 관련 NT ID | NR-018과 같다 |
| 명령 | `./gradlew :notification:cleanTest :auxiliary-global:cleanTest :chatbot:cleanTest :auxiliary-app:cleanTest :notification:test verifyModuleStructure :auxiliary-app:check` |
| 실행 환경 | NR-016과 같다 |
| 결과 | 통과 |
| 건수 | `:notification` 106, `:auxiliary-app` 11, `:auxiliary-global` 108, `:chatbot` 72. 합계 297, 실패·오류·건너뜀 0 (XML 기준, 새 실행) |
| 실패 원인 분류 | 실패 없음 |
| 수정 내용 | 없음 |
| 재실행 결과 | — |
| 미검증 항목 | NR-018과 같다. 10초 시간 제한을 Redis 장애 상황에서 따로 재지 않았다(앱 설정은 모듈 테스트가 읽지 않는다) |
| 관련 변경 | #829 |

#### NR-018 · 2026-11-10 · 통과 (#829 자체 리뷰 반영)

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-018 |
| 실행 날짜 | 2026-11-10 12:37~12:41 (KST) |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-829` |
| 커밋 | `0debc874` |
| 작업 트리 변경 | NR-016 목록 + `NotificationRealtimeRelay`(파기 공유 잠금 안에서 읽기·발행, 버린 프레임 지표·상태 변화 로그), `NotificationConnectionTicketService`(공통 `RateLimitExceededException`), `NotificationErrorCode`(`TOO_MANY_TICKET_REQUESTS` 삭제), `NotificationWebSocketSessions`(닫은 연결은 STOMP 처리기에 넘기지 않음, 미사용 메서드 삭제), build.gradle.kts(micrometer-core), 테스트 `NotificationRealtimeRelayTest`·`NotificationStompInterceptorTest`(새)와 기존 3개 수정, 문서(module 3.8, api, test-spec, PROGRESS, 이 문서) |
| 대상 기능 | 자체 리뷰 지적 반영: 429 공통 처리, 유실 관측, 파기와 실시간 전달의 순서, 수신 검사 빈 곳 |
| 관련 NT ID | NT-RT-01~06·08, NT-READ-05, NT-USR-07(실시간 부분) |
| 명령 | `./gradlew :notification:cleanTest :auxiliary-global:cleanTest :chatbot:cleanTest :auxiliary-app:cleanTest :notification:test verifyModuleStructure :auxiliary-app:check` |
| 실행 환경 | NR-016과 같다 |
| 결과 | 통과 |
| 건수 | `:notification` 106, `:auxiliary-app` 11, `:auxiliary-global` 108, `:chatbot` 72. 합계 297, 실패·오류·건너뜀 0 (XML 기준, 새 실행) |
| 실패 원인 분류 | 최종 실패 없음 |
| 수정 내용 | 아래 메모 |
| 재실행 결과 | — |
| 미검증 항목 | NR-016과 같다. 발행 실패(`publish_failed`) 지표는 단위 테스트가 없다. `origin/dev`의 새 커밋(#828·#830)을 병합한 뒤의 실행은 아직 없다 |
| 관련 변경 | #829 |

**메모 — 테스트 먼저(red 확인)**
- 고치기 전에 4건이 실패했다: 티켓 429가 공통 예외가 아님(단위), `Retry-After` 헤더·`TOO_MANY_REQUESTS` 없음(통합), 버린 프레임 지표 없음(단위), 파기 진행 중 옛 닉네임 프레임이 나감(통합, `whileMasking` not null).
- `NotificationStompInterceptorTest` 7건은 이미 있는 동작의 빈 곳(결합 안 된 CONNECT, 끝에 / 붙인 목적지, UNSUBSCRIBE·ACK)을 메운 시험이라 처음부터 통과했다.
- 변형 확인: Relay에서 파기 공유 잠금을 잠깐 빼자 파기 시험이 다시 실패했다(260줄). 되돌린 뒤 위 명령으로 통과를 확인했다.

#### NR-017 · 2026-11-10 · 측정 (U13, 판정 안 함)

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-017 |
| 실행 날짜 | 2026-11-10 11:59~12:07 (KST) |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-829` |
| 커밋 | `0debc874` |
| 작업 트리 변경 | NR-016과 같다 |
| 대상 기능 | STOMP 연결 수에 따른 메모리·스레드·연결 유지·프레임 지연(U13 근거) |
| 관련 NT ID | NT-RES-03(U13, 판정 안 함) |
| 명령 | `JAVA_TOOL_OPTIONS=-Xmx256m ./gradlew :auxiliary-app:bootRun`(local, compose의 postgres·kafka·redis) + 측정 스크립트(Node 24 내장 WebSocket, 저장소에 넣지 않음) 2회: ① 단계마다 60초 유지 ② 단계마다 10초 유지 후 `jcmd GC.run` 뒤 측정 |
| 실행 환경 | Windows 10, JDK 25, 앱 JVM heap 상한 256MiB(Old Gen max 268435456 확인), Hikari 최대 5(지표 확인). bootRun이라 `-XX:TieredStopAtLevel=1`·devtools 재시작 클래스로더가 붙어 운영 컨테이너와 다르다. 704Mi limit은 로컬에서 걸 수 없어 RSS(WorkingSet)로 비교만 했다 |
| 결과 | 800 연결까지 중단 조건(연결 실패 5% 초과, heap 90% 초과, 도착률 95% 미만, 연결 유지 95% 미만) 없음. OOM 없음 |
| 건수 | 연결 단계 50→100→200→400→800, 단계마다 표본 20명 전달 |
| 실패 원인 분류 | 실패 없음 |
| 수정 내용 | 없음 |
| 재실행 결과 | — |
| 미검증 항목 | 운영 컨테이너(704Mi limit) 안의 RSS, Gateway(N4)를 거친 연결, 실제 알림 생성(N5) 부하와 동시 실행, 15분 연결 만료가 돌 만큼 긴 유지, 800 초과 |
| 관련 변경 | #829 |

**측정 ①(유지 60초, GC 강제 없음)**

| 연결 | 연결 유지율 | 전달(표본) | 지연 p50/최대(ms) | heap used/committed(MiB) | RSS(MiB) | 스레드 | GC 횟수·누적 정지 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 0(기동 직후) | — | — | — | 119.3 / 182 | 463.9 | 63 | 2 · 0.015s |
| 50 | 100% | 20/20 | 157 / 170 | 152.3 / 184 | 483.9 | 67 | 3 · 0.024s |
| 100 | 100% | 20/20 | 118 / 118 | 138.7 / 184 | 493.3 | 67 | 4 · 0.028s |
| 200 | 100% | 20/20 | 114 / 114 | 151.7 / 194 | 521.5 | 68 | 5 · 0.038s |
| 400 | 100% | 20/20 | 127 / 127 | 185.9 / 229 | 574.1 | 70 | 7 · 0.047s |
| 800 | 100% | 20/20 | 118 / 118 | 197.5 / 256 | 650.1 | 67 | 12 · 0.080s |
| 모두 닫은 직후 | — | — | — | 205.5 / 256 | 571.9 | 201 | 12 · 0.080s |

**측정 ②(유지 10초, 측정 전 `GC.run` = live heap)**

| 연결 | live heap(MiB) | RSS(MiB) | 스레드 | 지연 p50(ms) |
| --- | --- | --- | --- | --- |
| 0 | 82.0 | 568.5 | 81 | — |
| 50 | 87.8 | 573.2 | 82 | 121 |
| 100 | 93.3 | 577.9 | 82 | 127 |
| 200 | 104.4 | 587.1 | 82 | 116 |
| 400 | 126.3 | 606.6 | 82 | 112 |
| 800 | 170.8 | 647.7 | 82 | 132 |
| 모두 닫은 뒤 | 82.4 | 573.9 | 203 | — |

**메모**
- 지연은 측정 스크립트가 `docker exec … redis-cli PUBLISH` 직전에 넣은 시각과 브라우저 쪽 수신 시각의 차이라 `docker exec` 오버헤드(약 100ms)가 들어간 상한이다. 연결 수와 상관없이 110~170ms로 일정했다.
- live heap은 연결당 약 0.11MiB(800 연결에 +88.8MiB) 늘고, 모두 닫으면 기동 직후 값(82MiB)으로 돌아왔다(연결 정리 누수 없음).
- 스레드는 연결 수와 관계없이 일정했다(STOMP 수신·송신 각 2, 만료 예약 1, 발행 1). 800 연결을 한꺼번에 닫으면 Tomcat 작업 스레드가 일시적으로 늘었다(201). 모두 `TIMED_WAITING`(풀 대기)이었고 약 1분 뒤 82로 줄었다.
- **800 연결에서 RSS가 648~650MiB로 운영 메모리 limit 704Mi의 약 92%였다.** heap committed도 상한(256MiB)에 닿았다. 이 측정은 로컬 bootRun 조건(devtools·`TieredStopAtLevel=1`, 컨테이너 limit 없음)이라 운영 RSS와 같다고 볼 수 없고, **운영 환경에서 800 연결을 보장한다는 뜻이 아니다.** 실제 알림 생성 부하(N5)·챗봇·검색 요청이 함께 돌면 여유는 더 적다.
- 연결 수 상한과 메모리 예산 변경은 이 기록에서 정하지 않았다. 이 숫자로 다시 검토하고, U13은 인프라·챗봇 담당 확인 전까지 미결이다.
- 측정용 앱 JVM은 끝난 뒤 종료했고(bootRun 작업 중지, 8087 해제 확인), compose 컨테이너는 그대로 두었다.

#### NR-016 · 2026-11-10 · 통과 (#829 실시간 전달)

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-016 |
| 실행 날짜 | 2026-11-10 11:12~12:12 (KST) |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-829` |
| 커밋 | `0debc874` |
| 작업 트리 변경 | 있음(커밋 전). 알림 모듈: build.gradle.kts, `NotificationWriter`, `NotificationErrorCode`, 새 파일(app: `ConditionalOnNotificationRealtime`, `NotificationConnection{TicketService,TicketStore,Facade}`, `NotificationRealtime{Relay,Publisher,ExecutorConfig}`, dto `ConnectionTicket`·`ConnectionTicketResponse`·`NotificationFrame`·`RealtimeMessage` / in: `ApiV1NotificationConnectionController`, `in/websocket/*` 5개, `in/redis/*` 2개 / out: `out/redis/*` 3개, Lua 1개), 테스트 5개(새)·`hapbang-module-test.yaml`. auxiliary global `SecurityConfig`, `application-{local,prod}.yaml`. 문서: decisions·module·api·CONTEXT·INDEX·test-spec·이 문서·AGENTS·PROGRESS |
| 대상 기능 | 연결 티켓, 자체 STOMP, commit 뒤 Redis Pub/Sub 전달, 연결 만료 |
| 관련 NT ID | NT-RT-01~06·08, NT-READ-05, NT-RET-02·NT-IDEM-01(실시간 부분) |
| 명령 | `./gradlew :notification:cleanTest :auxiliary-global:cleanTest :chatbot:cleanTest :auxiliary-app:cleanTest verifyModuleStructure :auxiliary-app:check` |
| 실행 환경 | Windows 10, JDK 25, Docker Testcontainers(PostgreSQL, Redis 8, Kafka) |
| 결과 | 통과 |
| 건수 | `:notification` 96, `:auxiliary-app` 11, `:auxiliary-global` 108, `:chatbot` 72. 합계 287, 실패·오류·건너뜀 0 (XML 기준, 새 실행) |
| 실패 원인 분류 | 최종 실패 없음. 중간 실패 1건은 F-TEST(아래 메모) |
| 수정 내용 | 아래 메모 |
| 재실행 결과 | 위 명령 12:08~12:12 통과 |
| 미검증 항목 | Gateway(N4)를 거친 연결·`exp` claim, 이벤트 소비로 만든 알림의 실시간 전달(N5), 여러 replica |
| 관련 변경 | #829 |

**메모 — 테스트 먼저(red 확인)**
- 테스트 5개를 먼저 써서 컴파일 오류 25건을 확인했다. 시그니처만 둔 껍데기로 바꾼 뒤 28건이 모두 실패했다: 단위 10건은 `UnsupportedOperationException`, 통합 18건은 실시간 빈(티켓 저장소, `SimpUserRegistry`)이 없어 실패했다.
- 구현 뒤 27/28이 통과했다. 남은 1건(`Redis가_멈춰도…`)은 재접속 단계의 시험 도우미가, 닫은 연결의 구독이 서버 목록에서 빠지기 전에 개수를 센 경합이었다(F-TEST). 빠질 때까지 기다리게 고친 뒤 통과했다. 운영 코드 결함이 아니다.
- 변형 확인: ① Relay가 commit을 기다리지 않게 하면 `commit되기_전에는…`을 포함한 6건이 실패했다. ② STOMP SEND를 허용하면 `클라이언트_SEND로…`가 실패했다(다른 회원 연결이 주입된 프레임을 받음). 되돌린 뒤 위 명령으로 통과를 확인했다.

#### NR-015 · 2026-11-10 · 통과 (#801 PR 리뷰: 같은 알림 행의 동시 쓰기)

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-015 |
| 실행 날짜 | 2026-11-10 10:17~10:21 (KST) |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-801` |
| 커밋 | `5be88868` |
| 작업 트리 변경 | 있음(커밋 전). `NotificationRepository`(조건부 벌크 UPDATE, ID 오름차순 `FOR UPDATE`), `NotificationWriter`(보존 정리), `NotificationQueryFacade`(읽음·전체 읽음), `NotificationMaskingService`(행 잠금 조회), `Notification`(`@DynamicUpdate`, 엔티티 읽음·삭제 메서드 제거), 테스트 `NotificationConcurrencyTest`(새)·`NotificationTest`(읽음 단위 테스트 1건 제거), module·test-spec·이 문서·PROGRESS |
| 대상 기능 | 보존 정리·읽음·전체 읽음·파기가 같은 알림 행을 쓸 때 서로 덮어쓰지 않기 |
| 관련 NT ID | NT-RET-05, NT-USR-07(+ NR-013과 같다) |
| 명령 | `./gradlew :notification:cleanTest :auxiliary-global:cleanTest :chatbot:cleanTest :auxiliary-app:cleanTest :auxiliary-app:check` |
| 실행 환경 | NR-013과 같다 |
| 결과 | 통과 |
| 건수 | `:notification` 68, `:auxiliary-app` 11, `:auxiliary-global` 108, `:chatbot` 72. 합계 259, 실패·오류·건너뜀 0 (XML 기준, 모두 새 실행) |
| 실패 원인 분류 | 실패 없음 |
| 수정 내용 | 아래 메모 |
| 재실행 결과 | 고친 뒤 10:12 `:notification:test` 68건 통과 |
| 미검증 항목 | NR-013과 같다 |
| 관련 변경 | #801 |

**메모 — 경합 테스트(먼저 쓰고 실패 확인, latch로 순서 강제)**
- 구현 전 `NotificationConcurrencyTest` 4건이 모두 실패했다(F-CODE): 보존 정리가 파기된 닉네임을 되돌림(`원래닉네임님이 …`), 전체 읽음이 센 수와 실제 읽은 수가 다름(`expected: 20 but was: 19`), 읽음 처리가 밀려난 알림의 삭제 시각을 비움(`not to be null`), 두 파기 중 하나의 닉네임이 되돌아옴.
- 고친 뒤 4건 통과. 각 테스트가 약 3초(3.0~3.6초)인 것은 먼저 시작한 쪽이 커밋 전 기다리는 상한(3초)까지 기다렸다는 뜻으로, 나중 쪽이 행 잠금에 실제로 막혔음을 보여 준다.
- 변형 확인: `@DynamicUpdate`는 둔 채 파기 조회의 `FOR UPDATE`만 잠깐 빼고 이 클래스만 돌리니 "두 파기" 테스트가 실패했다. 되돌린 뒤 위 명령으로 통과를 확인했다(`@DynamicUpdate`만으로는 해결되지 않는다).

#### NR-014 · 2026-11-10 · 통과 (#801 PR 리뷰)

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-014 |
| 실행 날짜 | 2026-11-10 10:17 (KST, NR-015 전) |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-801` |
| 커밋 | `5be88868` |
| 작업 트리 변경 | NR-015와 같다 |
| 대상 기능 | Gradle 모듈 의존 경계 |
| 관련 NT ID | NT-BND-02 |
| 명령 | `./gradlew verifyModuleStructure` |
| 실행 환경 | NR-015와 같다 |
| 결과 | 통과 |
| 건수 | 해당 없음(BUILD SUCCESSFUL) |
| 실패 원인 분류 | 실패 없음 |
| 수정 내용 | 없음 |
| 재실행 결과 | 재실행 안 함 |
| 미검증 항목 | 없음 |
| 관련 변경 | #801 |

#### NR-013 · 2026-11-10 · 통과 (N2 + ND-054 파기 표시, #801)

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-013 |
| 실행 날짜 | 2026-11-10 09:48~09:51 (KST) |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-801` |
| 커밋 | `ab8f957d` |
| 작업 트리 변경 | NR-011 + ND-054: Flyway `V202611101100__add_notification_user_masked_at.sql`, `NotificationUser`(파기 표시), `MemberMaskLocks`(회원 단위 advisory lock), `NotificationWriter`·`NotificationMaskingService`·`NotificationUserSyncService` 수정, 테스트(`NotificationUserTest`·`NotificationUserSyncServiceTest`·`NotificationMaskingServiceTest`·`NotificationMemberEventListenerTest`, fixtures) |
| 대상 기능 | ND-054: 파기 표시, 복제본 없는 회원의 파기 상태 복제본, 파기 뒤 저장되는 알림의 닉네임, 파기·저장 순서 잠금 |
| 관련 NT ID | NT-USR-07 외 NR-011과 같다 |
| 명령 | `./gradlew :notification:cleanTest :auxiliary-global:cleanTest :chatbot:cleanTest :auxiliary-app:cleanTest :auxiliary-app:check` |
| 실행 환경 | NR-011과 같다 |
| 결과 | 통과 |
| 건수 | `:notification` 65, `:auxiliary-app` 11, `:auxiliary-global` 108, `:chatbot` 72. 합계 256, 실패·오류·건너뜀 0 (XML 기준, 모두 새 실행) |
| 실패 원인 분류 | 실패 없음 |
| 수정 내용 | 구현 전 `:notification:compileTestJava` 실패(새 메서드 없음)를 먼저 확인했다. 첫 `:notification:test`에서 "복제본 없는 회원의 파기는 아무것도 남기지 않는다"는 기존 리스너 단언이 실패해(ND-054로 파기 상태 복제본이 생김) 단언을 고쳤다 |
| 재실행 결과 | 고친 뒤 `:notification:test` 65건 통과(09:45) |
| 미검증 항목 | NR-011의 미검증 항목 중 "파기 뒤 새로 저장되는 알림의 옛 닉네임"은 ND-054로 정해 이번에 검증했다. 나머지는 NR-011과 같다 |
| 관련 변경 | #801 |

**메모 — 동시 처리 테스트가 빈틈을 잡는지 확인(변형 확인)**: `NotificationWriter`의 공유 잠금(`maskLocks.lockShared`) 한 줄을 잠깐 막고 `NotificationMaskingServiceTest`만 돌리니 "복제본이 없는 회원의 파기와 저장이 동시에" 테스트가 15번 중 15번 옛 닉네임이 남아 실패했다(`expected: 0L but was: 15L`). 줄을 되돌린 뒤 위 명령으로 통과를 확인했다.

#### NR-012 · 2026-11-10 · 통과 (N2 + ND-054, #801)

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-012 |
| 실행 날짜 | 2026-11-10 09:48 (KST, NR-013 전) |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-801` |
| 커밋 | `ab8f957d` |
| 작업 트리 변경 | NR-013과 같다 |
| 대상 기능 | Gradle 모듈 의존 경계 |
| 관련 NT ID | NT-BND-02 |
| 명령 | `./gradlew verifyModuleStructure` |
| 실행 환경 | NR-013과 같다 |
| 결과 | 통과 |
| 건수 | 해당 없음(BUILD SUCCESSFUL) |
| 실패 원인 분류 | 실패 없음 |
| 수정 내용 | 없음 |
| 재실행 결과 | 재실행 안 함 |
| 미검증 항목 | 없음 |
| 관련 변경 | #801 |

#### NR-011 · 2026-11-10 · 통과 (N2 알림 저장·REST, #801)

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-011 |
| 실행 날짜 | 2026-11-10 09:24~09:28 (KST) |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-801` |
| 커밋 | `ab8f957d` |
| 작업 트리 변경 | 있음(커밋 전). 알림 모듈 `src/main`(Flyway `V202611101000__create_notification_table.sql`, `Notification`·`NotificationDraft`·`NotificationPayload`, `NotificationWriter`·`ReceiverPolicy`·`NotificationTemplate(s)`·`NotificationQueryFacade`·`NotificationMaskingService`·dto, `ApiV1NotificationController`, 리스너 파기 처리, 오류 코드, 저장소), `src/test`(새 테스트 5개 클래스·support, 기존 2개 수정), `docs/architecture/infrastructure/kafka.md`, 알림 문서 |
| 대상 기능 | N2: 알림 저장(중복·탈퇴 제외·20개 보존·받는 사람별 락), REST 4개, `UserMaskedEvent` 파기 닉네임 |
| 관련 NT ID | NT-QRY-01~04, NT-READ-01~06, NT-RET-01~05, NT-IDEM-01·02(저장 서비스 수준), NT-GEN-13, NT-USR-03·04(저장 서비스 수준)·07, NT-BND-03 |
| 명령 | `./gradlew :notification:cleanTest :auxiliary-global:cleanTest :chatbot:cleanTest :auxiliary-app:cleanTest :auxiliary-app:check` |
| 실행 환경 | Windows 10 Pro, JDK 25.0.4.1, Gradle 9.7.1, Docker 29.8.0. Testcontainers postgres·kafka(챗봇 테스트 컨테이너 포함) |
| 결과 | 통과 |
| 건수 | `:notification` 58, `:auxiliary-app` 11, `:auxiliary-global` 108, `:chatbot` 72. 합계 249, 실패·오류·건너뜀 0 (XML 기준, 모두 새 실행) |
| 실패 원인 분류 | 실패 없음 |
| 수정 내용 | 구현 전 `:notification:compileTestJava`가 새 타입이 없어 44건 오류로 실패하는 것을 먼저 확인했다. 첫 `:notification:test`에서 N1의 "테이블은 `notification_user` 하나" 단언이 실패해(N2에서 `notification`이 생김, ND-046 의도) 단언을 N2 기준으로 고쳤다 |
| 재실행 결과 | 고친 뒤 `:notification:test` 58건 통과(09:22) |
| 미검증 항목 | 이벤트 소비로 알림 만들기(N5 이후)의 Kafka 경로 NT-IDEM·NT-RET-02, 정지 회원(U8, NT-USR-05), 파기 뒤 새로 저장되는 알림에 옛 닉네임이 들어오는 경우(정책 미결, 보고서 참고), Gateway route(N4) |
| 관련 변경 | #801 |

**메모**: 동시 저장 테스트(같은 회원 6건 + 다른 회원 1건)는 Hikari 5로 일부가 연결을 기다리지만 받는 사람 행 잠금으로 줄을 서 정확히 20개를 남겼다.

#### NR-010 · 2026-11-10 · 통과 (N2, #801)

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-010 |
| 실행 날짜 | 2026-11-10 09:24 (KST, NR-011 전) |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-801` |
| 커밋 | `ab8f957d` |
| 작업 트리 변경 | NR-011과 같다 |
| 대상 기능 | Gradle 모듈 의존 경계 |
| 관련 NT ID | NT-BND-02 |
| 명령 | `./gradlew verifyModuleStructure` |
| 실행 환경 | NR-011과 같다 |
| 결과 | 통과 |
| 건수 | 해당 없음(BUILD SUCCESSFUL) |
| 실패 원인 분류 | 실패 없음 |
| 수정 내용 | 없음 |
| 재실행 결과 | 재실행 안 함 |
| 미검증 항목 | 없음 |
| 관련 변경 | #801 |

#### NR-009 · 2026-11-09 · 통과 (#797 PR 리뷰 반영)

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-009 |
| 실행 날짜 | 2026-11-09 17:18~17:21 (KST) |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-797` |
| 커밋 | `3e6aff2a` |
| 작업 트리 변경 | 있음(커밋 전). `NotificationUserSyncService`·`NotificationUserSyncResult`·`NotificationErrorCode`·`NotificationMemberEventListener`(ND-049 변경 이벤트 upsert), 테스트 `NotificationUserSyncServiceTest`·`NotificationMemberEventListenerTest`·`NotificationMemberEventDeadLetterTest`·`NotificationModuleIntegrationTest`·`AuxiliaryApplicationTest`, 알림 문서(decisions·module·test-spec·CONTEXT·AGENTS·이 문서) |
| 대상 기능 | ND-049(복제본 없을 때 온 변경 이벤트로 생성), Flyway 단언 보강(모듈 SQL 성공 적용, 앱에서 notification schema·테이블·이력) |
| 관련 NT ID | NT-USR-01, NT-USR-02, NT-ERR-01, NT-ERR-02, NT-ERR-03, NT-BND-01, NT-BND-03 |
| 명령 | `./gradlew :notification:cleanTest :auxiliary-global:cleanTest :chatbot:cleanTest :auxiliary-app:cleanTest :auxiliary-app:check` |
| 실행 환경 | NR-007과 같다 |
| 결과 | 통과 |
| 건수 | `:notification` 30, `:auxiliary-app` 11, `:auxiliary-global` 108, `:chatbot` 72. 합계 221, 실패·오류·건너뜀 0 (XML 기준, 모두 새 실행) |
| 실패 원인 분류 | 실패 없음 |
| 수정 내용 | 없음(구현 전 `:notification:compileTestJava`가 `Outcome.CREATED_FROM_MODIFIED` 없음으로 실패하는 것을 먼저 확인했다) |
| 재실행 결과 | 같은 코드로 17:16 `:notification:test` 30건 통과 |
| 미검증 항목 | 동시 처리 테스트는 매 실행에서 충돌이 실제로 나는지 보장하지 않는다(충돌이 나면 재처리, 나지 않으면 바로 최종 상태를 확인). Flyway 단언 보강은 이미 있는 동작을 확인하는 것이라 실패 단계를 거치지 않았다 |
| 관련 변경 | #797 |

#### NR-008 · 2026-11-09 · 통과 (#797 PR 리뷰 반영)

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-008 |
| 실행 날짜 | 2026-11-09 17:18 (KST, NR-009 전) |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-797` |
| 커밋 | `3e6aff2a` |
| 작업 트리 변경 | NR-009와 같다 |
| 대상 기능 | Gradle 모듈 의존 경계 |
| 관련 NT ID | NT-BND-02 |
| 명령 | `./gradlew verifyModuleStructure` |
| 실행 환경 | NR-009와 같다 |
| 결과 | 통과 |
| 건수 | 해당 없음(BUILD SUCCESSFUL) |
| 실패 원인 분류 | 실패 없음 |
| 수정 내용 | 없음 |
| 재실행 결과 | 재실행 안 함 |
| 미검증 항목 | 없음 |
| 관련 변경 | #797 |

#### NR-007 · 2026-11-09 · 통과 (dev 병합 뒤)

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-007 |
| 실행 날짜 | 2026-11-09 17:00~17:07 (KST) |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-797` |
| 커밋 | `1da5eb40` (병합 `722a939a`: `311f679a` + origin/dev `657bcfa6`, 그 뒤 `-parameters` 커밋) |
| 작업 트리 변경 | 없음 |
| 대상 기능 | N1이 dev(챗봇 #798·#800 등)와 합쳐진 상태, 알림 모듈 `-parameters` |
| 관련 NT ID | NR-004와 같다 |
| 명령 | `./gradlew :notification:cleanTest :auxiliary-global:cleanTest :chatbot:cleanTest :auxiliary-app:cleanTest :auxiliary-app:check` |
| 실행 환경 | NR-004와 같다(Testcontainers. 챗봇 테스트의 컨테이너 포함). 로컬 compose(postgres·kafka·redis) 실행 중, 측정용 앱은 종료됨 |
| 결과 | 통과 |
| 건수 | `:notification` 25, `:auxiliary-app` 11, `:auxiliary-global` 108, `:chatbot` 72. 합계 216, 실패·오류·건너뜀 0 (XML 기준, 모두 새 실행) |
| 실패 원인 분류 | 실패 없음 |
| 수정 내용 | 병합 충돌(`AuxiliaryApplicationTest` properties)을 알림·챗봇 설정을 모두 남겨 해결 |
| 재실행 결과 | 재실행 안 함 |
| 미검증 항목 | NR-002와 같다 |
| 관련 변경 | #797 |

#### NR-006 · 2026-11-09 · 통과 (dev 병합 뒤)

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-006 |
| 실행 날짜 | 2026-11-09 17:00 (KST, NR-007 전) |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-797` |
| 커밋 | `1da5eb40` |
| 작업 트리 변경 | 없음 |
| 대상 기능 | Gradle 모듈 의존 경계 |
| 관련 NT ID | NT-BND-02 |
| 명령 | `./gradlew verifyModuleStructure` |
| 실행 환경 | NR-007과 같다 |
| 결과 | 통과 |
| 건수 | 해당 없음(BUILD SUCCESSFUL) |
| 실패 원인 분류 | 실패 없음 |
| 수정 내용 | 없음 |
| 재실행 결과 | 재실행 안 함 |
| 미검증 항목 | 없음 |
| 관련 변경 | #797 |

#### NR-005 · 2026-11-09 · 통과 (로컬 기동·자원 측정, 수동)

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-005 |
| 실행 날짜 | 2026-11-09 16:36~16:39 (KST) |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-797` |
| 커밋 | `7b6beb9a` |
| 작업 트리 변경 | NR-004와 같다 |
| 대상 기능 | N1 앱 기동, 회원 복제본 소비자 연결, 자원 측정(인프라 Issue·U13 근거) |
| 관련 NT ID | NT-KAF-06, NT-ERR-03, NT-RES-01·03(U13, 판정 안 함) |
| 명령 | 1) `docker compose -f docker/compose.yaml up -d postgres kafka redis`(기존 볼륨 유지) 2) 로컬 Kafka에 `member.events.v1`을 member-app 선언과 같게 생성(partition 3, 복제 1, `cleanup.policy=delete`) 3) `./gradlew :auxiliary-app:bootRun`(local 프로필) 4) `kafka-consumer-groups.sh --describe --group auxiliary-app.notification-member-replica` 5) `GET /actuator/health`, `GET /actuator/prometheus` 10초 간격 3회 |
| 실행 환경 | Windows 10 Pro, JDK 25.0.4.1, Gradle 9.7.1, Docker 29.8.0. compose: postgres:18, apache/kafka:4.4.0, redis:8. JVM 옵션 지정 없음(운영 `-Xmx256m`·limit 704Mi 아님), devtools 재시작 클래스로더 사용. 이벤트 유입 없음(유휴) |
| 결과 | 통과 |
| 건수 | 해당 없음(수동 확인) |
| 실패 원인 분류 | 실패 없음 |
| 수정 내용 | 없음 |
| 재실행 결과 | 재실행 안 함 |
| 미검증 항목 | 이벤트 처리 중 부하 값, 운영 JVM 옵션에서의 값, 운영 cluster |
| 관련 변경 | #797 |

**확인한 것**
- 기동 10.54초, Flyway가 `202611061400`(chatbot)·`202611091500`(notification) 두 버전을 schema `auxiliary` 이력에 적용, `/actuator/health` UP.
- 소비 그룹 `auxiliary-app.notification-member-replica`가 `member.events.v1` partition 0·1·2를 모두 받았다(lag 0, 이벤트 없음).
- 모듈이 DLT `member.events.v1.auxiliary-app.notification-member-replica.DLT`를 partition 3, `retention.ms=604800000`으로 선언했다.

**측정값** (`/actuator/prometheus`, 3회 같은 값 또는 범위)

| 지표 | 값 |
| --- | --- |
| `hikaricp_connections` (total) | 5 |
| `hikaricp_connections_active` | 0 |
| `hikaricp_connections_idle` | 5 |
| `hikaricp_connections_max` / `_min` / `_pending` | 5 / 5 / 0 |
| JVM heap used (heap pool 합) | 71.6 → 75.6 → 77.6 MiB |
| JVM heap committed | 104.0 MiB |
| JVM heap max | 4058.0 MiB(로컬 기본값, 물리 메모리 1/4. 운영은 `-Xmx256m`) |
| JVM non-heap used | 146.0~147.9 MiB |
| live threads | 39 |
| 프로세스 작업 집합(tasklist) | 약 347 MB |

**메모**: 측정 뒤 bootRun 백그라운드 작업을 멈췄지만 앱 JVM(PID 22636)이 남았고, 일반 종료 요청(`taskkill`, /F 없음)은 Windows가 거부했다. 사용자 확인(2026-11-09) 뒤 `taskkill /F /PID 22636`으로 그 프로세스만 강제 종료했다(포트 8087 해제 확인, 이벤트 처리 중이 아니었다). compose 컨테이너(postgres·kafka·redis)는 그대로 둔다.

#### NR-004 · 2026-11-09 · 통과

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-004 |
| 실행 날짜 | 2026-11-09 16:39~16:41 (KST) |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-797` |
| 커밋 | `7b6beb9a` |
| 작업 트리 변경 | NR-002 + `apps/auxiliary-app/src/test/java/com/hapbang/auxiliary/{AuxiliaryModularityTest,AuxiliaryApplicationTest}.java` |
| 대상 기능 | N1 마무리: 모듈 경계 단언에 notification 추가, 앱 기동 테스트에서 알림 회원 소비자 끄기 |
| 관련 NT ID | NR-002와 같다 + NT-BND-01(전부) |
| 명령 | `./gradlew :notification:cleanTest :auxiliary-global:cleanTest :chatbot:cleanTest :auxiliary-app:cleanTest :auxiliary-app:check` |
| 실행 환경 | NR-002와 같다. 로컬 compose(postgres·kafka·redis)와 측정용 앱(8087)이 떠 있었다(테스트는 Testcontainers·임의 포트라 영향 없음) |
| 결과 | 통과 |
| 건수 | `:notification` 25, `:auxiliary-app` 10, `:auxiliary-global` 89, `:chatbot` 1. 합계 125, 실패·오류·건너뜀 0 (XML 기준, 모두 새 실행) |
| 실패 원인 분류 | 실패 없음 |
| 수정 내용 | 없음 |
| 재실행 결과 | 재실행 안 함 |
| 미검증 항목 | NR-002와 같다 |
| 관련 변경 | #797 |

**메모**: `AuxiliaryApplicationTest` 55.5초(NR-002) → 20.7초, `localhost:9092` 연결 경고 51회 → 0회.

#### NR-003 · 2026-11-09 · 통과

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-003 |
| 실행 날짜 | 2026-11-09 16:39 (KST, NR-004 전) |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-797` |
| 커밋 | `7b6beb9a` |
| 작업 트리 변경 | NR-004와 같다 |
| 대상 기능 | Gradle 모듈 의존 경계 |
| 관련 NT ID | NT-BND-02 |
| 명령 | `./gradlew verifyModuleStructure` |
| 실행 환경 | NR-004와 같다 |
| 결과 | 통과 |
| 건수 | 해당 없음(BUILD SUCCESSFUL) |
| 실패 원인 분류 | 실패 없음 |
| 수정 내용 | 없음 |
| 재실행 결과 | 재실행 안 함 |
| 미검증 항목 | 없음 |
| 관련 변경 | #797 |

#### NR-002 · 2026-11-09 · 통과

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-002 |
| 실행 날짜 | 2026-11-09 16:27~16:29 (KST) |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-797` |
| 커밋 | `7b6beb9a` |
| 작업 트리 변경 | 있음(커밋 전). 알림 모듈 등록(`settings.gradle.kts`, `apps/auxiliary-app/build.gradle.kts`), `apps/auxiliary-app/boundedContext/notification/{build.gradle.kts,src/**}`, 알림 문서(decisions·module·test-spec·INDEX·AGENTS·PROGRESS·이 문서) |
| 대상 기능 | N1 모듈 뼈대: 모듈 등록, schema·`notification_user`, 회원 복제본 소비자, Kafka 소비·DLT 공통 설정 (#797) |
| 관련 NT ID | NT-USR-01, NT-USR-02, NT-USR-04, NT-KAF-06, NT-ERR-02, NT-ERR-03, NT-BND-01, NT-BND-03 |
| 명령 | `./gradlew :notification:cleanTest :auxiliary-global:cleanTest :chatbot:cleanTest :auxiliary-app:cleanTest :auxiliary-app:check` |
| 실행 환경 | Windows 10 Pro, JDK 25.0.4.1, Gradle 9.7.1, Docker 29.8.0 실행 중. Testcontainers postgres·kafka(apache/kafka:4.2.1)·redis(앱 기동 테스트) |
| 결과 | 통과 |
| 건수 | `:notification` 성공 25(실패 0, 오류 0, 건너뜀 0), `:auxiliary-app` 10, `:auxiliary-global` 89, `:chatbot` 1. 합계 125 (XML 기준, 네 태스크 모두 새 실행) |
| 실패 원인 분류 | 실패 없음 |
| 수정 내용 | 없음 |
| 재실행 결과 | 재실행 안 함. 같은 날 16:23 `./gradlew :notification:test` 첫 실행도 25건 통과였다(같은 코드) |
| 미검증 항목 | 알림 생성·REST·STOMP(N2 이후), NT-USR-03·05·06·07(복제본 사용·U8·U17·U19), 로컬 `bootRun` 자원 측정(인프라 Issue용, 미실행) |
| 관련 변경 | #797 |

**메모**
- `AuxiliaryApplicationTest`(운영 설정으로 앱 전체 기동)는 Kafka broker 없이 띄워 알림 회원 소비자가 `localhost:9092` 연결 경고를 반복한다(통과, 55초). 테스트 결과에는 영향이 없다.
- 모듈 테스트 로그의 `OtlpMeterRegistry ... ConnectException`은 로컬 OTLP 수신기가 없어 나는 경고다(모듈 테스트는 앱 `application.yaml`의 지표 내보내기 끄기 설정을 읽지 않는다).

#### NR-001 · 2026-11-09 · 통과

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-001 |
| 실행 날짜 | 2026-11-09 16:24경 (KST, NR-002 전) |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-797` |
| 커밋 | `7b6beb9a` |
| 작업 트리 변경 | NR-002와 같다 |
| 대상 기능 | Gradle 모듈 의존 경계(`:notification`은 `:auxiliary-global`·`:shared`만) |
| 관련 NT ID | NT-BND-02 |
| 명령 | `./gradlew verifyModuleStructure` (`--parallel` 없이 따로) |
| 실행 환경 | NR-002와 같다 |
| 결과 | 통과 |
| 건수 | 해당 없음(Gradle 검사 태스크, BUILD SUCCESSFUL) |
| 실패 원인 분류 | 실패 없음 |
| 수정 내용 | 없음 |
| 재실행 결과 | 재실행 안 함 |
| 미검증 항목 | 없음 |
| 관련 변경 | #797 |

## 4. 미해결 항목

실행 결과로 생긴 미해결 항목을 이 표에서 관리한다. 해결되면 지우지 않고 "해결" 칸에 실행 ID나 Issue를 적는다.

| 번호 | 처음 발견 | 테스트 / NT | 내용 | 원인 분류 | 관련 Issue | 상태 | 해결 |
| --- | --- | --- | --- | --- | --- | --- | --- |

아직 없음.

변경 이력
- 2026-11-06 처음 작성: 실행 기록 없음. 형식은 [지갑 회귀 실행 기록](../billing/regression-log.md)을 알림 모듈에 맞게 줄였다.
- 2026-11-09 NR-001·NR-002(N1, #797)를 더했다.
- 2026-11-09 NR-003·NR-004(N1 마무리 재실행), NR-005(로컬 기동·자원 측정)를 더했다.
- 2026-11-09 NR-006·NR-007(dev 병합 뒤 재실행)을 더했다.
- 2026-11-09 NR-008·NR-009(#797 PR 리뷰 반영: ND-049, Flyway 단언 보강)를 더했다.
- 2026-11-10 NR-010·NR-011(N2 알림 저장·REST, #801)을 더했다.
- 2026-11-10 NR-012·NR-013(ND-054 파기 표시, #801)을 더했다.
- 2026-11-10 NR-014·NR-015(#801 PR 리뷰: 같은 알림 행의 동시 쓰기)를 더했다.
