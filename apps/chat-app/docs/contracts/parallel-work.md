# 병렬 작업 계약: 담당 범위와 공통 골격

채팅 고도화 이슈 #416~#420을 병렬로 구현할 때의 파일 담당과 공통 골격(skeleton) 규칙이다. 설계 근거는 [설계 문서](../final-design.md) 4·5장, 결정은 [결정 사항](../rules/decisions.md)이다. 단체방은 이번 범위가 아니다.

## worktree와 브랜치

모두 로컬 `feature/tkt-404`(72af5fa)에서 만들었다. #405 병합 뒤 `rebase --onto origin/dev`한다.

| 이슈 | 브랜치 | worktree |
| --- | --- | --- |
| #416 회원 이벤트 Kafka 발행 | `feature/tkt-416` | `../hapbang-backend.worktrees/tkt-416` |
| #417 회원 이벤트 소비·연결 제어 기록·백필 | `feature/tkt-417` | `../hapbang-backend.worktrees/tkt-417` |
| #418 outbox → Streams 적재·그룹 소비·회수·재구성 | `feature/tkt-418` | `../hapbang-backend.worktrees/tkt-418` |
| #419 Pub/Sub 전달·로컬 STOMP 자격 검사·RECHECK_USER·직접 전달 교체 | `feature/tkt-419` | `../hapbang-backend.worktrees/tkt-419` |
| #420 누적 수신 커서·클라이언트 ACK | `feature/tkt-420` | `../hapbang-backend.worktrees/tkt-420` |

## 공통 골격 (동결)

병렬 브랜치가 서로 컴파일되도록, 아래 파일을 착수 전에 같은 내용으로 #417·#418·#419 worktree에 넣는다. **세 브랜치 모두 이 파일을 고치지 않는다.** 같은 내용으로 추가된 파일이라 병합할 때 충돌하지 않는다. 바꿔야 하면 계약 문서를 먼저 고치고 세 worktree에 같은 내용으로 반영한다.

| 파일 (`apps/chat-app/src/main/...`) | 계약 |
| --- | --- |
| `java/.../domain/ChatControlType.java` | 제어 작업 종류 `RECHECK_USER` |
| `java/.../domain/ChatControlReason.java` | 제어 사유 `MEMBER_MODIFIED`, `MEMBER_BACKFILL` |
| `java/.../app/ChatConnectionControl.java` | [제어 작업 기록](control-recording.md) 인터페이스 |
| `java/.../out/JpaChatConnectionControl.java` | 위 인터페이스의 DB 구현. 데이터 접근을 Spring Data JPA로 바꾼 사용자 결정(2026-10-30)으로 `JdbcChatConnectionControl`에서 이름을 바꿨다. #418 브랜치에서 먼저 바꿨고, #417·#419 worktree에도 같은 내용으로 반영해야 한다 |
| `java/.../app/RealtimeChatFanout.java` | [실시간 전파](realtime-fanout.md) 인터페이스 |
| `java/.../app/dto/MessageFanout.java`, `ControlFanout.java`, `java/.../domain/ChatNotifyRule.java` | 실시간 전파 payload. 방 topic 결정(2026-10-30)으로 `MessageFanout`은 v2(수신자 목록 대신 수신자 집합 버전·알림 규칙)이고 `FanoutRecipient`는 없앴다([실시간 전파 계약](realtime-fanout.md)) |
| `java/.../out/redis/ChatRedisKeys.java` | Redis namespace·Stream·그룹·채널 이름 |
| `resources/db/migration/V202610291200__create_chat_control_outbox.sql` | 제어 outbox·발행 체크포인트 테이블 |

## 담당 파일

같은 파일을 두 이슈가 고치지 않는 것이 원칙이다. 아래 "겹치는 파일"만 예외로, 지정한 부분만 고치고 병합 때 둘 다 살린다.

| 이슈 | 담당 |
| --- | --- |
| #416 | `common/shared`의 `UserJoinedEvent`·`UserModifiedEvent`(애노테이션만), `apps/member-app/build.gradle.kts`, member-app 설정 YAML. 회원 업무 로직은 고치지 않는다 |
| #417 | `in/event/ChatUserEventListener`(Kafka 리스너로 교체), Kafka 소비 설정(`global/config` 또는 `in/event`), `ChatUserFacade`, 회원 동기화 서비스 인터페이스·구현(`app`), 회원 백필(`app`·`in`·관리자 API·member 내부 API 호출), `ChatUserUseCase`, chat-app `build.gradle.kts`의 Kafka 관련 줄, `V2026102911xx` 마이그레이션 |
| #418 | `app`의 `ChatDeliveryQueue`와 relay·consumer·회수·재구성 조정 서비스, `out/redis`의 Streams 구현, 전달 outbox·체크포인트 엔티티·Repository, 불변 수신자 집합(도메인·Repository·`ChatRoom` 필드), `ChatMessageUseCase`·`ChatRoomUseCase`·`ChatNotificationUseCase`의 outbox·수신자 집합 기록, `V202610291210`~`V2026102912xx` 마이그레이션 |
| #419 | `out/redis`의 `RedisRealtimeChatFanout`과 Pub/Sub 구독 설정, 구독 진입 어댑터(`in`), 로컬 STOMP 전달과 수신 자격 검사(`ChatMessageSender` 교체 포함), `ChatWebSocketSessionRegistry`, `ChatWebSocketConfig`(송신 버퍼·sync 큐), RECHECK_USER 처리와 주기적 재검사, 재동기화 프레임, `ChatMessageStompController`·`ApiV1ChatController`·`AppearanceNotificationEventListener`의 직접 전달 제거 |
| #420 | `ChatRoomMember`의 `lastReceivedSequence`, 수신 ACK API(`in`)와 서비스 인터페이스·구현(`app`), `ChatMessagePageResponse` 응답 필드 추가, `V202610291300` 마이그레이션 |

겹치는 파일:

| 파일 | 규칙 |
| --- | --- |
| `ChatRoomMemberRepository` | #418·#420이 각자 메서드만 추가한다. 기존 메서드는 고치지 않는다 |
| `ChatErrorCode` | 각자 상수를 추가만 한다. 기존 상수는 고치지 않는다 |
| `AppearanceNotificationEventListener` | #417이 Kafka 소비(`appearance.events.v1`)로 바꾼다. #419는 그 안의 직접 전달 호출만 제거한다 |
| `ApiV1ChatController`·`ApiV1ChatSpec` | #419는 직접 전달 호출만 제거하고, #420은 수신 ACK 메서드만 추가한다 |
| `application.yaml` | `hapbang.chat.*` 키 이름은 각자 자기 것만 쓴다(#417 `member-events`·`appearance-events`·`backfill`, #418 `delivery`, #419 `realtime`, #420 `receipt`). 기본값은 각자의 `@ConfigurationProperties` 코드 기본값으로 두고 `application.yaml`에는 넣지 않는다(2026-10-30 사용자 결정, #418은 [테이블 계약](tables-and-migrations.md#설정-위치-418-사용자-결정)). yaml에는 환경 변수 자리표시자 같은 환경 값만 둔다 |
| chat-app `build.gradle.kts` | 필요한 의존 줄만 추가한다 |

## 브랜치별 동작 범위와 통합

- #418 브랜치는 outbox 기록과 relay·consumer를 구현하지만 운영 `RealtimeChatFanout` 구현은 #419에 있다. relay·Stream worker·회수·재구성 빈은 `hapbang.chat.delivery.pipeline.enabled`가 `true`일 때만 등록한다(#418 코드 기본 `false`, 테스트 설정도 `false`). #419 통합에서 운영 `RealtimeChatFanout`과 함께 코드 기본값을 `true`로 바꾼다. #418 테스트는 테스트 소스의 `InMemoryRealtimeChatFanout`과 속성을 켜고 검증한다.
- #419 브랜치는 `RealtimeChatFanout`의 Redis 구현과 구독·로컬 전달을 구현하고 `RealtimeChatFanout`을 직접 호출해 검증한다. outbox가 없는 #419 단독 브랜치에서는 직접 전달을 제거하면 메시지가 전달되지 않으므로, **직접 전달 제거와 `pipeline.enabled=true` 전환은 #418·#419를 합친 통합 단계에서 한다**(#419 범위). 통합 검증은 #417·#418·#419·#420을 모두 합친 상태에서 받는다.
- #417 브랜치의 제어 작업은 공통 골격의 `JpaChatConnectionControl`(옛 이름 `JdbcChatConnectionControl`)로 DB에 기록까지 한다. 연결 종료 확인은 통합 검증에서 완료한다([작업 절차](../workflow/process.md) 1단계 기준).
- 설정 수치(polling·lease·claim idle·재시도·보존·버퍼 등)는 `hapbang.chat.*` 속성으로 빼고, 코드 기본값의 주석에 "미확정 초기값(Q12)"을 단다.
