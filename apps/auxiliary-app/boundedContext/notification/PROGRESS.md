# 알림(notification) 모듈 진행 기록

> 문서 탐색: [알림 문서 INDEX](../../../../docs/notification/INDEX.md)

알림 모듈 작업을 이어받는 사람과 AI 에이전트가 작업 전에 읽는 **진행 상태** 문서다. 알림 모듈의 상태만 적는다. 챗봇 모듈과 auxiliary-app 전체 상태는 적지 않는다.

- 규칙은 [`AGENTS.md`](AGENTS.md), 결정은 [`docs/notification/decisions.md`](../../../../docs/notification/decisions.md)(`ND-*`)에 있다. 이 문서는 결정을 따로 관리하지 않는다.
- 이 문서와 코드·decisions.md가 다르면 코드와 decisions.md가 맞다. 확인한 뒤 이 문서를 고친다.
- 작업 하나를 끝내면 3장 상태 칸과 1장을 갱신한다.

마지막 갱신: 2026-11-13. 상태는 `dev` `c95d732d`(N9 #921 PR #927 병합 포함) 기준 git log로 확인했다.

## 1. 현재 상태

| 항목 | 상태 | 근거 |
| --- | --- | --- |
| auxiliary-app 생성 | 완료 | 커밋 `363dbeef`(#714, PR #736). 운영 준비 #726(PR #740·#741·#745) |
| 챗봇 모듈 | 있음 | `apps/auxiliary-app/boundedContext/chatbot` |
| notification Gradle 모듈 | 등록 | `settings.gradle.kts` `auxiliaryModules = listOf("chatbot", "notification")` |
| `boundedContext/notification` 디렉터리 | N1·N2 구현 | `build.gradle.kts`, `src/`(회원 복제본, Kafka 소비·DLT 설정, 알림 저장·REST·파기 닉네임). [module.md](../../../../docs/notification/module.md) 2장 |
| 결정 기록 `decisions.md` | 작성 완료 | 커밋 `e054d6f6`(#746, PR #747) |
| 나머지 알림 문서 | 작성 완료 (#750) | 2장 |
| 기능 구현 | N1~N9 완료, N10 진행 중(#928) | 알림 저장·REST·파기 닉네임은 N2, 알림 생성(이벤트 소비)은 N5부터 |
| 테스트 | 실행 | [regression-log.md](../../../../docs/notification/regression-log.md) NR-001~NR-082 |
| Flyway | 작성 | `V202611091500__create_notification_schema.sql`(schema, `notification_user`), `V202611101000__create_notification_table.sql`(`notification`), `V202611101100__add_notification_user_masked_at.sql`(파기 표시) |

## 2. 문서 구축 상태

| 문서 | 상태 |
| --- | --- |
| [`docs/notification/decisions.md`](../../../../docs/notification/decisions.md) | 작성 완료(#746) |
| [`docs/notification/CONTEXT.md`](../../../../docs/notification/CONTEXT.md) | 작성 완료 (#750) |
| [`docs/notification/module.md`](../../../../docs/notification/module.md) | 작성 완료 (#750) |
| [`docs/notification/notification-catalog.md`](../../../../docs/notification/notification-catalog.md) | 작성 완료 (#750) |
| [`docs/notification/test-spec.md`](../../../../docs/notification/test-spec.md) | 작성 완료 (#750) |
| [`docs/notification/regression-log.md`](../../../../docs/notification/regression-log.md) | 작성 완료 (#750) |
| [`docs/api/notification.md`](../../../../docs/api/notification.md) | 작성 완료 (#750) |
| [`AGENTS.md`](AGENTS.md), 이 문서 | 작성 완료 (#750) |
| [`docs/notification/INDEX.md`](../../../../docs/notification/INDEX.md) | 작성 완료 (#752) |
| [`docs/notification/event-contracts.md`](../../../../docs/notification/event-contracts.md) | 확정(발행 Issue 발송 전) |
| `docs/policy.md` 알림센터 장 | 후속(팀 승인 필요, 4장) |

## 3. 구현 작업 계획

원본은 [notification.md 8장](../../../../docs/architecture/infrastructure/notification.md#8-구현-순서)이다. 상태는 2026-11-06 git log와 코드 검색(`common/shared`, `apps/auxiliary-app`, M1~M7은 발행 앱 코드까지)으로 판정했다. 이벤트별 조사 상세는 [`notification-catalog.md`](../../../../docs/notification/notification-catalog.md)와 [`module.md`](../../../../docs/notification/module.md) "3.5 다른 앱 연동"에 있다.

| ID | 우선순위 | 작업 | 담당 | 선행 | 현재 상태 | 근거 |
| --- | --- | --- | --- | --- | --- | --- |
| X1 | P0 | auxiliary-app 생성(모듈 구조, k8s, CI) | 검색·챗봇·알림 공동 | 없음 | 완료 | #714(PR #736): 모듈 구조·`apps/auxiliary-app/k8s/{base,overlays}`·`.github/scripts/apps.sh`에 `auxiliary-app`. #726으로 prod replicas 1 |
| N0 | P0 | 이벤트 계약 합의, 문구 템플릿 확정 | 알림 | 없음 | 진행 중 | 계약 확정([`event-contracts.md`](../../../../docs/notification/event-contracts.md)). 발행 Issue 발송 전. 종류별 문구는 각 소비 작업(ND-050) |
| N1 | P0 | 모듈 뼈대: database, 테이블, 회원 복제본, Kafka 소비·DLT 설정 | 알림 | X1 | 완료 | #797(PR #803, dev `ab8f957d`). 테스트 NR-009, 로컬 기동·측정 NR-005. U13 N1 자원값은 인프라 확인, kafka.md 토픽 표 반영(#801). 범위 ND-046·ND-048, consumer group ND-047 |
| N2 | P0 | 알림 저장, 20개 유지, REST API | 알림 | N1 | 완료 | #801(PR #819, dev `0debc874`). 정책 ND-050~ND-054, 테스트 NR-015 |
| N3 | P0 | STOMP, 연결 티켓, Redis Pub/Sub 전달 | 알림 | N1 | 완료 | #829(PR #834, dev `40c94d7b`). 정책 ND-055~ND-061, 자원 U13 미결 |
| N4 | P0 | Envoy route(REST, WebSocket) | 알림 | X1 | 완료 | #847(PR #853, dev `671244aa`). 정책 ND-062~ND-068 |
| N5 | P0 | 이벤트 소비: 출연 요청 도착·모집 지원 도착, 지갑 7종(묶음 제외), 회원 가입·승급 승인 2종, 회원 복제본 Backfill | 알림 | N0, N2 | 완료 | #859(PR #865, dev `6a11400d`). 정책 ND-069~ND-081·ND-088~ND-095. U20은 ND-095로 해소 |
| M1 | P0 | 출연: 출연 취소 요청 이벤트 3종 Kafka 발행 | 출연 | 없음 | 완료 | #817(PR #839, dev `167f3ee6`). 계약은 [`event-contracts.md`](../../../../docs/notification/event-contracts.md) 1장 |
| M2 | P0 | 강의: 이의 제기 이벤트 4종에 구매자·판매자 ID 추가 | 강의 | N0 | 완료 | #818(PR #827, dev `1f2f353b`). 계약은 [`event-contracts.md`](../../../../docs/notification/event-contracts.md) 2장 |
| N6 | P0 | M1·M2 이벤트 소비 | 알림 | M1 또는 M2, N2 | 완료 | #862(PR #879, dev `c503d46e`). 정책 ND-082~ND-087·ND-096 |
| M3 | P1 | 출연 새 이벤트 12종 | 출연 | N0 | 완료 | #871. 12종 모두 `@Externalized`. 계약은 [`event-contracts.md`](../../../../docs/notification/event-contracts.md) 1장 |
| M4 | P1 | 강의 새 이벤트 7종 | 강의 | N0 | 완료 | #872. 7종 모두 `@Externalized`. 계약은 [`event-contracts.md`](../../../../docs/notification/event-contracts.md) 2장 |
| M5 | P1 | 지갑 새 이벤트 4종, 지급 이벤트 제목(ND-040) | 지갑 | N0 | 완료 | #873(PR #886, dev `69240da8`). 계약은 [`event-contracts.md`](../../../../docs/notification/event-contracts.md) 3장 |
| M6 | P1 | 회원 새 이벤트 3종(반려 2종, 채널 인증 승인) | 회원 | N0 | 완료 | #874. 3종 모두 `@Externalized`. 계약은 [`event-contracts.md`](../../../../docs/notification/event-contracts.md) 4장 |
| M7 | P1 | 고객센터 새 이벤트 2종(답변 수정은 발행하지 않음) | 고객센터 | N0 | 완료 | #875. 2종 모두 `@Externalized`. 계약은 [`event-contracts.md`](../../../../docs/notification/event-contracts.md) 5장. 답변 수정 알림은 폐기(ND-123) |
| N7 | P1 | M3~M7 이벤트 소비(모듈별) | 알림 | 해당 M, N2 | 완료 | 모듈별 Issue(ND-105). 지갑 4종 #907 완료(PR #912, 정책 ND-105~ND-109). 출연 10종 #908 완료(PR #916, 정책 ND-110~ND-113). 강의 7종 #910 완료(PR #920, 정책 ND-114~ND-117). 회원 3종 #915 완료(PR #922, 정책 ND-118~ND-121). 고객센터 2종 #918 완료(PR #926, 정책 ND-122~ND-128) |
| N8 | P1 | 강의 지급 하루 묶음 | 알림 | N2 | 완료 | #890(PR #903, dev `81a7347e`). 정책 ND-097~ND-104 |
| M8 | P2 | 채팅: 단체방 이벤트 3종, `chat.events.v1` 토픽 | 채팅 | N0 | 완료 | #897(PR #904, dev `973ccbe5`). 3종 모두 `@Externalized`(key 방 ID). 계약은 [`event-contracts.md`](../../../../docs/notification/event-contracts.md) 6장 |
| N9 | P2 | M8 이벤트 소비 | 알림 | M8, N2 | 완료 | #921(PR #927, dev `c95d732d`). 정책 ND-129~ND-132, 새 group `auxiliary-app.notification-chat-alert` |
| N10 | P2 | 예약 배치와 예약 알림 5종 | 알림 | N2(종류별 N5·M3·M4) | 진행 중 | #928(PR #929). 정책 ND-133~ND-149. 구현·테스트 완료(NR-082), Bot 리뷰 반영 중. U6 팀 확인 대기(구현은 ND-134) |

- P0 완료 기준(“P0만으로 완료 증거 시연 가능”)은 구매 완료 알림이 P1(M4)에 걸려 성립하지 않는다. U2(T3)로 정할 때까지 P0 범위를 바꾸지 않는다.

## 4. 차단 요소와 팀 승인 필요

본문은 [decisions.md 10장](../../../../docs/notification/decisions.md#10-미결-항목)에 있다. 여기에는 번호와 결정 주체만 둔다.

| ID | 결정 주체 | 막는 작업 |
| --- | --- | --- |
| U2 (T3) | 알림 담당 + 강의 담당 | 작업 계획 확정 |
| U3 | 알림 담당(+ FE) | 각 종류를 소비하는 작업(N7의 남은 묶음. N5는 ND-075, N6는 ND-085, N7 강의는 ND-117, N7 회원은 ND-121, N9 채팅은 ND-132, N10 예약은 ND-140) |
| U5 | 알림 담당 | 이벤트 소비(N7의 남은 묶음. N5는 ND-073, N6는 ND-083, N7 강의는 ND-115, N7 회원은 ND-119, N9 채팅은 ND-130, N10 예약은 ND-139) |
| U6 (T5) | 팀 확인 대기 (알림 쪽 ND-134) | N10 PR 리뷰 |
| U12 | 팀 + FE | 화면 표시(FE). 서버 생성은 막지 않는다 |
| U13 | 알림 + 챗봇 + 인프라 담당 (N1 값은 인프라 확인됨, N3는 알림 담당 제안, N4는 관측 ND-066) | N10 자원 산정 |
| U14 | 인프라 담당 확인 대기 (알림 쪽 ND-062·ND-063) | N4 PR 리뷰 |
| U15 | 인프라 담당 확인 대기 (알림 쪽 ND-064) | N4 PR 리뷰 |
| U19 | 알림 담당 (+ 회원 정책은 팀) | N2 구현 가능성 검증(구조는 ND-051) |
| T8 | 채팅 담당 | — (chat-app 불변식, decisions.md 12장) |

그 밖의 팀 승인 필요:

- `docs/policy.md`에 알림센터 장 추가, `docs/GLOSSARY.md`에 알림센터 용어 반영(팀 승인 후속).
- 원천 설계 `docs/architecture/infrastructure/notification.md`를 축소할지(팀 결정).
- `docs/architecture/infrastructure/kafka.md` 토픽 표의 고객센터 알림 소비 그룹(#918, ND-122): 알림 담당 승인으로 적었고 팀 검토는 PR 리뷰에서 받는다.
- 원천 설계 `docs/architecture/infrastructure/notification.md`의 즉시 알림 수(49종)와 답변 수정 알림은 ND-123과 다르다(팀 공유 문서, 승인 필요).
- `apps/auxiliary-app/README.md` "권한"·"실행과 설정"에 알림 웹소켓 핸드셰이크 경로 예외(ND-060)를 적는 일(팀 공유 문서, 승인 필요).
- Billing 쪽 T1(BD-127)·T6·T7은 [`docs/billing/decisions.md`](../../../../docs/billing/decisions.md) 10장에서 관리한다.

## 5. 관련 Issue

`gh`를 쓰지 않고 `git log`로 확인할 수 있는 것만 적는다.

| Issue | 내용 | 확인 |
| --- | --- | --- |
| #193 | 알림 기능 | git log에 커밋 없음(decisions.md ND-001 출처로만 확인). 다른 팀원의 향후 작업이며 알림 문서 구축의 차단 요소가 아니다 |
| #684 | 메모리 예산(검색·챗봇·알림을 앱 하나로 묶는 근거, README) | 커밋 `e872d3c1`(PR #686, 제목은 chat-app heap 상한) |
| #710 | 알림 최종 기획 반영 | 커밋 `83d564e8` |
| #714 | auxiliary-app 뼈대와 챗봇 모듈 자리 | 커밋 `363dbeef`(PR #736) |
| #746 | 알림 결정 기록 신설 | 커밋 `e054d6f6`(PR #747) |

## 6. 후속

- 알림 모듈 Gradle 등록과 구현은 4장의 차단 요소를 정한 뒤 시작한다(AGENTS.md 5장).
