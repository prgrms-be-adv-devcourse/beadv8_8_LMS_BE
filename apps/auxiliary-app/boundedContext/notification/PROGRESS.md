# 알림(notification) 모듈 진행 기록

> 문서 탐색: [알림 문서 INDEX](../../../../docs/notification/INDEX.md)

알림 모듈 작업을 이어받는 사람과 AI 에이전트가 작업 전에 읽는 **진행 상태** 문서다. 알림 모듈의 상태만 적는다. 챗봇 모듈과 auxiliary-app 전체 상태는 적지 않는다.

- 규칙은 [`AGENTS.md`](AGENTS.md), 결정은 [`docs/notification/decisions.md`](../../../../docs/notification/decisions.md)(`ND-*`)에 있다. 이 문서는 결정을 따로 관리하지 않는다.
- 이 문서와 코드·decisions.md가 다르면 코드와 decisions.md가 맞다. 확인한 뒤 이 문서를 고친다.
- 작업 하나를 끝내면 3장 상태 칸과 1장을 갱신한다.

마지막 갱신: 2026-11-10. 상태는 `dev` `0debc874`(N2 #801 병합, PR #819) 기준 코드 검색으로 확인했다.

## 1. 현재 상태

| 항목 | 상태 | 근거 |
| --- | --- | --- |
| auxiliary-app 생성 | 완료 | 커밋 `363dbeef`(#714, PR #736). 운영 준비 #726(PR #740·#741·#745) |
| 챗봇 모듈 | 있음 | `apps/auxiliary-app/boundedContext/chatbot` |
| notification Gradle 모듈 | 등록 | `settings.gradle.kts` `auxiliaryModules = listOf("chatbot", "notification")` |
| `boundedContext/notification` 디렉터리 | N1·N2 구현 | `build.gradle.kts`, `src/`(회원 복제본, Kafka 소비·DLT 설정, 알림 저장·REST·파기 닉네임). [module.md](../../../../docs/notification/module.md) 2장 |
| 결정 기록 `decisions.md` | 작성 완료 | 커밋 `e054d6f6`(#746, PR #747) |
| 나머지 알림 문서 | 작성 완료 (#750) | 2장 |
| 기능 구현 | N1·N2 완료, N3 구현(#829, dev 병합 전) | 알림 저장·REST·파기 닉네임은 N2, 알림 생성(이벤트 소비)은 N5 이후 |
| 테스트 | 실행 | [regression-log.md](../../../../docs/notification/regression-log.md) NR-001~NR-023 |
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
| N3 | P0 | STOMP, 연결 티켓, Redis Pub/Sub 전달 | 알림 | N1 | 진행 중 | #829. 정책 ND-055~ND-060, 구현·테스트 완료(dev 병합 전), 자원 측정 U13 |
| N4 | P0 | Envoy route(REST, WebSocket) | 알림 | X1 | 미착수 | `apps/auxiliary-app/k8s/base/gateway.yaml`에 알림 경로 없음. U14 |
| N5 | P0 | 이벤트 소비: 출연 요청 도착·모집 지원 도착, 지갑 7종(묶음 제외), 회원 가입·승인 추정 2종 | 알림 | N0, N2 | 미착수 | 코드 없음 |
| M1 | P0 | 출연: 출연 취소 요청 이벤트 3종 Kafka 발행 | 출연 | 없음 | 미착수 | 2026-11-06 조사: 출연 취소 요청 3종(`AppearanceCancelRequestedEvent`·`AppearanceCancelRequestAcceptedEvent`·`AppearanceCancelRequestRejectedEvent`)은 shared에 있고 출연 앱 JVM 안에서는 발행된다. `@Externalized`가 없어 Kafka로는 나가지 않는다(출연 앱은 `@Externalized`가 붙은 것만 내보낸다, `apps/appearance-app/src/main/java/com/hapbang/appearance/app/AppearanceExternalEvents.java:12`) |
| M2 | P0 | 강의: 이의 제기 이벤트 4종에 구매자·판매자 ID 추가 | 강의 | N0 | 미착수 | 2026-11-06 조사: 이의 제기 4종(`LectureObjectionResolved`·`ReturnCompleted`·`Withdrawn`·`ReturnActionRequiredEvent`)에 `buyerUserId`·`sellerUserId`·제목이 없다 |
| N6 | P0 | M1·M2 이벤트 소비 | 알림 | M1 또는 M2, N2 | 미착수 | 코드 없음 |
| M3 | P1 | 출연 새 이벤트 12종 | 출연 | N0 | 미착수 | 2026-11-06 조사: 12종 레코드가 shared·출연 앱에 없다. `AppearanceChangedEvent`(#713)는 AI 추천용 상태 사본(id, hostId, guestId, result, scheduledAt, version)이라 M3 전체를 대체할 수 없다(요청 수락/지원 수락, 일방/합의 취소, HOST/AUTO 확인을 구분할 수 없고 요청서·지원·모집글 단계 전이를 담지 않는다). 부분 충족 5, 미충족 7 |
| M4 | P1 | 강의 새 이벤트 7종 | 강의 | N0 | 미착수 | 2026-11-06 조사: 7종 레코드가 없다(`LectureObjectionFiledEvent` 없음, `LectureObjectionFiledDto`만 있음). #712 `LectureChangedEvent`·`LecturePurchaseChangedEvent`는 부분 재사용 후보다(제목·금액·검수 상태·사유가 부족). 구매 확정 전이는 동기화 이벤트도 없다(`apps/lecture-app/src/main/java/com/hapbang/lecture/app/LectureSyncEvents.java:59`). 부분 4, 미충족 3 |
| M5 | P1 | 지갑 새 이벤트 4종, 지급 이벤트 제목(ND-040) | 지갑 | N0 | 미착수 | 2026-11-06 조사: `ChargeFailed`·`RefundFailed`·`RefundManualReviewRequired`·`ExchangeApprovedEvent` 4종 모두 발행하지 않는다(미충족) |
| M6 | P1 | 회원 새 이벤트 3종(반려 2종, 채널 인증 승인) | 회원 | N0 | 미착수 | 2026-11-06 조사: 반려 2종은 발행하지 않는다(미충족). 채널 인증 승인은 새 이벤트(ND-042), 승급 승인은 `UserModifiedEvent` 역할 비교(ND-016) |
| M7 | P1 | 고객센터 새 이벤트 3종 | 고객센터 | N0 | 미착수 | 2026-11-06 조사: 3종 모두 발행하지 않는다(미충족) |
| N7 | P1 | M3~M7 이벤트 소비(모듈별) | 알림 | 해당 M, N2 | 미착수 | 코드 없음 |
| N8 | P1 | 강의 지급 하루 묶음 | 알림 | N2 | 미착수 | 코드 없음. U9 |
| M8 | P2 | 채팅: 단체방 이벤트 3종, `chat.events.v1` 토픽 | 채팅 | N0 | 미착수 | shared에 `Group*Event` 없음, 코드·설정에 `chat.events.v1` 없음. ND-043·ND-044 |
| N9 | P2 | M8 이벤트 소비 | 알림 | M8, N2 | 미착수 | 코드 없음 |
| N10 | P2 | 예약 배치와 예약 알림 5종 | 알림 | N2(종류별 N5·M3·M4) | 미착수 | 코드 없음. U6 |

- P0 완료 기준(“P0만으로 완료 증거 시연 가능”)은 구매 완료 알림이 P1(M4)에 걸려 성립하지 않는다. U2(T3)로 정할 때까지 P0 범위를 바꾸지 않는다.

## 4. 차단 요소와 팀 승인 필요

본문은 [decisions.md 10장](../../../../docs/notification/decisions.md#10-미결-항목)에 있다. 여기에는 번호와 결정 주체만 둔다.

| ID | 결정 주체 | 막는 작업 |
| --- | --- | --- |
| U2 (T3) | 알림 담당 + 강의 담당 | 작업 계획 확정 |
| U3 | 알림 담당(+ FE) | 각 종류를 소비하는 작업(N5 이후) |
| U5 | 알림 담당 | 이벤트 소비(N5·N6·N7) |
| U6 (T5) | 팀 | N10 |
| U7 (T5) | 알림 담당 + 팀 | N5 배포 |
| U8 | 팀(회원 정책) | N5 |
| U9 | 알림 담당 | N8 |
| U11 | 알림 + 채팅 담당 | 규칙 밖 종류 코드를 쓰는 생성(N5·N8·N10) |
| U12 | 팀 + FE | N5·N9 |
| U13 | 알림 + 챗봇 + 인프라 담당 (N1 값은 인프라 확인됨, N3는 알림 담당 제안) | N3·N10 자원 산정 |
| U14 | 알림 + 인프라 담당 | N4 |
| U15 | 알림 + 인프라 담당 | N4 |
| U16 | 알림 담당 (B안은 팀) | 예약 알림(N10) |
| U17 | 알림 담당 (+ 회원 내부 API를 쓰면 회원 담당) | N5 배포 |
| U19 | 알림 담당 (+ 회원 정책은 팀) | N2 구현 가능성 검증(구조는 ND-051) |
| T8 | 채팅 담당 | — (chat-app 불변식, decisions.md 12장) |

그 밖의 팀 승인 필요:

- `docs/policy.md`에 알림센터 장 추가, `docs/GLOSSARY.md`에 알림센터 용어 반영(팀 승인 후속).
- 원천 설계 `docs/architecture/infrastructure/notification.md`를 축소할지(팀 결정).
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
