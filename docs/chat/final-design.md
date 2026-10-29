# 채팅 모듈 고도화 설계 검토 — 다대다 채팅방, Redis Pub/Sub 전달, 회원 이벤트 Kafka 전환

앱 분리(#363) 뒤 독립 서비스가 된 `chat-app`을 Final 범위에서 고도화하기 위한 검토 문서다. 현재 코드(dev `da834b1`)를 기준으로 무엇이 끊겼고, 다대다 채팅방·Redis Pub/Sub·Kafka 회원 이벤트를 어떤 순서와 구조로 넣을지 제안한다. 구현 전 팀이 정할 항목은 8장에 모았다.

- 상위 결정: [최종 프로젝트 인프라 설계](../architecture/final-project-infrastructure.md) 1장(Kafka)·2장(Redis Streams)·8장(알림 모듈 분리)
- 관련 이슈: #339(Kafka 구축), #340(Redis 구축), #370·#371(JWT 인증 전환), #193(알림 모듈), #262·#310(보류)
- 공통 규칙: [AGENTS.md](../../AGENTS.md), [모듈 연동](../dev/module-integration.md), [테스트](../dev/testing.md)

---

## 1. 요약

| 주제 | 제안 | 근거 |
| --- | --- | --- |
| 회원 이벤트 | `member.events.v1` 토픽으로 `UserJoined`·`UserModified`를 Kafka 발행하고 chat-app이 `eventId` inbox로 멱등 소비한다. 기존 회원은 내부 API로 1회 적재한다 | 앱 분리로 회원 복제가 멈춰 신규 회원이 채팅을 못 쓴다(2.2). 채팅이 다시 동작하는 최소 조건이라 **가장 먼저** 한다 |
| 실시간 전달 | 메시지 전달·연결 제어를 Redis Pub/Sub 채널 2개(`chat:fanout:v1`, `chat:control:v1`)로 모든 replica에 퍼뜨리고, 로컬 세션이 있는 replica만 STOMP로 보낸다 | 내장 simple broker와 세션 목록이 프로세스 로컬이라 replica 2개부터 전달이 깨진다(2.3) |
| 전달 보장 | 1차는 Pub/Sub만 쓴다. 유실은 이미 있는 FE 순번 간격 복구와 재연결 재조회로 메운다. Redis Streams 작업 큐(#340)는 2차로 미룬다 | PostgreSQL이 진실의 원천이고 FE 복구가 이미 있어, Streams 없이도 "유실 → 재조회"가 성립한다(4.4). 인프라 문서 2장과 다르므로 팀 확인이 필요하다(8장 Q6) |
| 다대다 방 | `ChatRoomType.GROUP`을 추가하고 방장·제목·최대 인원을 둔다. 참여자·순번·읽음·재입장 모델은 그대로 N명에 쓴다 | `chat_room_member`가 이미 방·회원당 1행이고 순번·가시 범위·읽음이 참여자 단위라 N명에 그대로 맞는다(3장). 바뀌는 것은 방 생성·초대·전송 규칙이다 |
| 전달 모델 | 방 topic을 열지 않고 지금처럼 회원별 큐(`/user/queue/chats/messages`)를 유지한다. Redis에는 방당 1회 발행하고 payload에 수신자 ID를 담는다 | 퇴장자 차단을 서버가 보장하는 현재 보안 모델을 유지하면서 발행 횟수는 1회로 줄인다(4.2) |
| 로그아웃·강제 종료 | 세션 ID 기반 `UserLoggedOutEvent`는 JWT 전환과 함께 버린다. 정지·강등은 Kafka 회원 이벤트 → 제어 채널로 모든 replica의 연결을 닫는다 | 세션이 사라지므로(#370) 세션 ID로 연결을 찾을 수 없다(5.3) |
| 출연 알림 | 채팅에서 받지 않는다. 알림 모듈(8장, `notification-app`)로 옮길 때까지 중단 상태임을 공지한다 | 앱 분리로 이미 도착하지 않는다(2.2). 채팅이 다른 모듈 이벤트를 Kafka로 다시 받으면 8장 결정과 어긋난다 |

진행 순서는 7장에 있다. 회원 이벤트 → Pub/Sub → 다대다 방 → Streams·관측 → 알림 이관 순이다.

## 2. 현재 상태

### 2.1 구조

| 영역 | 현재 구현 |
| --- | --- |
| 테이블(`chats`) | `chat_user`(회원 복제본), `chat_room`(1:1, `direct_key` 유일, `last_sequence`), `chat_room_member`(`visible_from_sequence`, `participation_version`, `last_read_sequence`), `chat_message`(`room_sequence` 유일, `client_message_id`), `chat_room_log` |
| 전송 | STOMP `/pub/chats/{roomId}/messages` → `ChatMessageFacade.send`가 방을 `PESSIMISTIC_WRITE`로 잠그고 순번 발급·저장 → 커밋 뒤 `ChatMessageSender`가 현재 참여자에게 회원별 큐로 전달 |
| 수신 | 회원별 큐 `/user/queue/chats/messages`·`/errors`(FE 구독), `/notifications`(FE 미구독). 방 topic 없음. `ChatStompInterceptor`가 자기 큐 구독과 `/pub` 전송만 허용 |
| 브로커 | `enableSimpleBroker` 내장 브로커. 서버 1대 전제 |
| 연결 제어 | `ChatWebSocketSessionRegistry`가 이 프로세스의 WebSocket 세션만 기억하고, 로그아웃(세션 ID)·정지·강등(회원 ID)에 닫는다 |
| 회원 복제 | `UserJoinedEvent`로 생성, `UserModifiedEvent`로 갱신. `ReplicaUser.memberVersion`으로 옛 이벤트를 무시한다(#262의 버전 비교는 이미 구현됨) |
| 알림 | 출연 이벤트 2종을 1:1 방의 `NOTIFICATION` 메시지로 남긴다 |
| 멱등·복구 | `clientMessageId` 재전송 방지(#261, #311), `participationVersion`으로 옛 퇴장 요청 무시, 읽음은 조건부 UPDATE로 단조 증가. FE는 `appliedIds`로 중복을 거르고 `roomSequence` 간격이 보이면 이력을 다시 조회한다(`incoming.ts`), 재연결마다 채팅 데이터를 다시 부른다(`ChatConnection.tsx`) |

### 2.2 앱 분리로 끊긴 것

Spring Event와 `@ApplicationModuleListener`는 같은 프로세스 안에서만 전달된다(모듈 연동 문서). chat-app이 혼자 뜨면 아래가 도착하지 않는다.

| 이벤트 | 발행 | 채팅의 처리 | 끊긴 결과 |
| --- | --- | --- | --- |
| `UserJoinedEvent` | member-app(가입, 테스트 계정) | `chat_user` 생성 | 새 회원은 복제본이 없어 `CHAT_USER_NOT_FOUND`. STOMP CONNECT도 거절된다 |
| `UserModifiedEvent` | member-app(프로필·역할·상태 변경) | 복제본 갱신, 채팅 불가가 되면 연결 닫기 | 정지·탈퇴·강등이 채팅에 반영되지 않는다 |
| `UserLoggedOutEvent` | member-app(세션 삭제 직전) | 그 세션의 연결 닫기 | 로그아웃해도 연결이 남는다. JWT 전환(#370) 뒤에는 세션 ID 자체가 없다 |
| `AppearanceRequestSentEvent`·`AppearanceApplicationSubmittedEvent` | appearance-app | 1:1 방에 알림 메시지 | 출연 알림이 채팅에 남지 않는다 |

이 중 회원 2종은 채팅이 동작하기 위한 전제라 Kafka 전환의 첫 대상이다. 로그아웃은 설계를 바꾸고(5.3), 출연 알림은 알림 모듈로 옮긴다(인프라 문서 8장).

### 2.3 replica가 2개 이상이면 깨지는 것

Envoy Gateway는 WebSocket 연결을 세션 어피니티 없이 아무 replica로 보낸다. 보낸 사람과 받는 사람이 다른 replica에 붙어 있으면 다음이 실패한다.

- `SimpMessagingTemplate.convertAndSendToUser`는 이 프로세스의 simple broker에만 보낸다. 다른 replica의 세션은 받지 못한다.
- `ChatWebSocketSessionRegistry.closeByUserId`는 이 프로세스의 세션만 닫는다.
- `AppearanceNotificationEventListener`처럼 이벤트를 받은 replica가 전달까지 맡는 구조도 같은 한계다.

메시지 저장·순번·읽음은 PostgreSQL에서 하므로 replica 수와 무관하게 안전하다. 바꿀 것은 "커밋 뒤 전달"과 "연결 닫기" 두 지점이다.

## 3. 다대다(그룹) 채팅방

### 3.1 지금 모델에서 그대로 쓰는 것

- `chat_room_member`: 방·회원당 1행. `visible_from_sequence`(입장 이후 메시지만 보임), `participation_version`(재입장 세대), `last_read_sequence`(읽음)가 모두 참여자 단위라 N명에 그대로 맞는다.
- `chat_message.room_sequence`: 방 단위 단조 순번. 그룹에서도 FE 간격 복구·커서 조회·안 읽은 수 계산의 기준이 된다.
- `countUnread`·`findLastVisible`: 회원 기준으로 방마다 집계하므로 참여자 수에 비례해 비용이 늘지 않는다.
- 재입장 시 `clearClientMessageIds`와 재입장 안내, 퇴장 안내 시스템 메시지.

### 3.2 바꾸는 것

| 항목 | 변경 |
| --- | --- |
| `ChatRoomType` | `GROUP` 추가. `direct_key`는 `DIRECT`만 쓰고 `GROUP`은 null이다. PostgreSQL 유일 제약은 null을 여러 개 허용하므로 DDL 변경이 없다 |
| `chat_room` 컬럼 | `title`(그룹 제목, nullable), `owner_user_id`(방장, nullable), `max_members`(nullable, 기본값은 정책) 추가. Flyway `V<시각>__add_chat_room_group_columns.sql` |
| `ChatRoomEventType` | `MEMBER_INVITED`, `MEMBER_REMOVED`, `ROOM_CLOSED` 추가(방장 강퇴·폐쇄 이력) |
| 생성 | `ChatRoomUseCase.createGroup(ownerId, title, memberIds)`: 방장 포함 전원이 채팅 가능 회원인지 확인, 방장 포함 최대 인원 검사, 전원 `ChatRoomMember.join`. 1:1의 `openDirectRoom`은 그대로 둔다 |
| 초대 | `invite(roomId, actorId, userIds)`: 방을 잠그고 활성 참여자 수 + 초대 수 ≤ 최대 인원 확인. 이미 나간 회원이면 `rejoin`과 같은 규칙(이전 메시지 안 보임). 누가 초대할 수 있는지는 Q1 |
| 강퇴·폐쇄 | `remove(roomId, ownerId, userId)`는 `leave`와 같은 처리에 이력 종류만 다르다. `close(roomId, ownerId)`는 전원 퇴장 + `ROOM_ENDED` |
| 전송 규칙 | `ChatMessageUseCase.send`의 "상대가 없으면 `CHAT_PARTNER_LEFT`"는 1:1 규칙이다. 그룹은 혼자 남아도 보낼 수 있게 할지 Q2. 수신자(`deliverToUserIds`)·알림 대상(`notifyUserIds`)은 지금처럼 활성 참여자 목록에서 계산한다 |
| 종료 | 마지막 참여자가 나가면 `ENDED`(현행 유지). 그룹은 방장 폐쇄도 `ENDED` |
| 응답 | `ChatRoomResponse`에 `roomType`이 이미 있어 FE가 분기할 수 있다. `title`, `ownerUserId`, `maxMembers`를 추가한다. `members`는 N명이 된다 |
| 관리자 기록 | `getHistory`는 방 종류와 무관하게 동작한다. 강퇴·폐쇄 이력이 `chat_room_log`에 들어가면 그대로 보인다 |

API 초안(기존 `/api/v1/chats` 아래, [API 규칙](../api/conventions.md) 준수):

| 메서드·경로 | 역할 |
| --- | --- |
| `POST /api/v1/chats` | 1:1 시작(현행) |
| `POST /api/v1/chats/groups` | 그룹 생성 `{title, memberIds}` |
| `POST /api/v1/chats/{roomId}/members` | 초대 `{userIds}` |
| `DELETE /api/v1/chats/{roomId}/members/{userId}` | 강퇴(방장) |
| `DELETE /api/v1/chats/{roomId}/members/me` | 나가기(현행, 그룹도 동일) |
| `DELETE /api/v1/chats/{roomId}` | 폐쇄(방장) |

### 3.3 동시성과 비용

- 순번 발급은 방 잠금(`findByIdForUpdate`) 아래에서만 한다. 그룹은 전송 빈도가 높아져 방 단위 직렬화가 더 자주 겹친다. 트랜잭션이 이미 짧고(검증·저장만) 전달은 커밋 뒤라 이 구조를 유지한다. 수백 명 방은 범위 밖이며 최대 인원으로 막는다(Q3).
- 초대·강퇴·나가기는 모두 방을 잠근 뒤 참여자 수를 센다. 동시 초대로 최대 인원을 넘지 않는다.
- 전달은 메시지당 참여자 수만큼 `convertAndSendToUser`가 호출된다. Redis 발행은 방당 1회로 줄인다(4.2).

### 3.4 1:1 방과의 관계

- 1:1 방의 `direct_key` 재사용·재입장 규칙은 바꾸지 않는다.
- 같은 두 사람이 그룹에도 함께 있을 수 있다. 그룹은 `direct_key`가 없으므로 충돌하지 않는다.
- 알림(`NOTIFICATION` 메시지)은 1:1 방에만 남긴다. 알림 모듈 이관 전까지만 유효한 규칙이다.

## 4. 실시간 전달: Redis Pub/Sub

### 4.1 역할

| 구성 요소 | 역할 |
| --- | --- |
| PostgreSQL | 메시지·참여·읽음의 진실의 원천(현행) |
| Redis Pub/Sub | 커밋된 메시지와 연결 제어 명령을 모든 chat-app replica에 퍼뜨린다 |
| 각 replica의 simple broker | 그 replica에 붙은 WebSocket 세션으로 최종 전달(현행) |
| REST 이력 API + FE 복구 | Pub/Sub 유실·오프라인 구간의 최종 복구(현행) |

Pub/Sub은 fire-and-forget이다. 구독자가 없거나 Redis가 끊긴 순간의 메시지는 사라진다. 이 설계는 그것을 받아들이고 복구를 PostgreSQL 이력에 맡긴다. 인프라 문서 2장의 Streams 작업 큐는 "전달 시도 자체를 영속화"하는 추가 단계이며 4.4에서 다룬다.

### 4.2 채널과 payload

| 채널 | payload | 구독자 동작 |
| --- | --- | --- |
| `chat:fanout:v1` | `{type: MESSAGE, roomId, recipients: [userId...], message: ChatMessageResponse, notifyUserIds: [...]}` | `recipients` 중 로컬 세션이 있는 회원에게 `/user/queue/chats/messages`, `notifyUserIds` 중 로컬 세션이 있는 회원에게 `/user/queue/chats/notifications` |
| `chat:control:v1` | `{type: CLOSE_USER, userId, reason: CHAT_NOT_ALLOWED}` | 로컬 세션 중 그 회원의 연결을 닫는다 |

- 방당 1회 발행한다. 수신자 목록은 발행 시점에 UseCase가 확인한 활성 참여자라, 구독한 replica는 DB를 다시 조회하지 않는다.
- 방 topic 구독 모델로 바꾸지 않는다. 회원별 큐를 유지하면 퇴장자 차단·참여 검증이 서버에만 있고, `ChatStompInterceptor`의 SUBSCRIBE 규칙도 그대로다.
- 단일 채널이라 모든 replica가 모든 메시지를 받는다. replica 1~2개 규모에서는 비용이 작고 구독 관리가 없다. replica가 늘어 비용이 문제가 되면 방별 채널(`chat:room:{id}`)과 로컬 구독 관리로 바꾼다. 아래 인터페이스 뒤에서 바뀌므로 업무 코드는 영향이 없다.
- 직렬화는 JSON. `type`과 채널 이름의 `v1`으로 호환성을 관리한다.
- 보낸 사람 본인에게 `clientMessageId`를 돌려주는 현행 규칙은 유지한다. payload의 `message`는 `clientMessageId`가 없는 공용 응답이고, 보낸 사람 분은 `senderClientMessageId` 필드로 따로 싣는다.

### 4.3 코드 변경 지점

| 현재 | 변경 |
| --- | --- |
| `ChatMessageSender`(SimpMessagingTemplate 직접 호출) | `RealtimeChatFanout` 인터페이스(인프라 문서 2장)로 바꾼다. 구현 2개: `RedisPubSubChatFanout`(발행 + 구독 → 로컬 전달), `LocalChatFanout`(단일 프로세스·테스트) |
| `ChatMessageStompController`, `AppearanceNotificationEventListener` | `ChatMessageSender` 대신 `RealtimeChatFanout.publish(...)`를 부른다. 커밋 뒤 호출이라는 현재 위치는 유지한다 |
| `ChatWebSocketSessionRegistry` | `userId → sessions` 인덱스를 추가해 `hasSession(userId)`를 O(1)로 만든다. 지금은 전체 순회다 |
| `ChatUserEventListener`의 연결 닫기 | `chat:control:v1`에 발행하고, 구독자가 로컬 세션을 닫는다. 이벤트를 받은 replica만 닫던 것을 모든 replica로 넓힌다 |
| 설정 | `common/redis`의 `RedisConfig`는 비어 있다. `RedisMessageListenerContainer`와 채널 구독은 chat-app 안(`out/redis` 패키지)에 둔다. 키·채널 규칙은 채팅이 소유한다(공통 모듈 README 규칙) |
| health | Boot의 Redis health는 이미 켜져 있다. 발행 실패 횟수·구독 끊김 지표를 Micrometer로 더한다(#343) |

Redis 발행 실패는 전송 실패가 아니다. 메시지는 이미 커밋됐으므로 보낸 사람에게 오류를 보내지 않고 기록만 남긴다(현행 `ChatMessageSender`의 원칙과 같다). FE는 다음 메시지의 순번 간격이나 재연결 때 이력을 다시 읽어 채운다.

### 4.4 Redis Streams(#340)를 2차로 미루는 이유

인프라 문서 2장은 `chat_outbox` → Streams(`XADD`/`XREADGROUP`/`XACK`/`XAUTOCLAIM`) → Pub/Sub 순서를 그린다. Streams가 주는 것은 "전달 시도의 영속화와 재시도"다. 지금 구조에서 그 값이 얼마나 되는지 따져 보면 다음과 같다.

- 메시지 자체는 PostgreSQL에 커밋되어 있고, FE가 `roomSequence` 간격과 재연결로 이력을 다시 읽는다. 실시간 전달이 빠져도 최종 상태는 같다.
- Pub/Sub 발행 실패는 Redis가 끊긴 순간에만 생긴다. 그 구간의 메시지는 어차피 Streams에도 `XADD`하지 못한다. Streams가 메우는 것은 "Redis는 살아 있는데 구독 replica가 처리 중 죽은" 경우다.
- 포트폴리오 증거(pending claim, failed stream, consumer lag 대시보드)는 Streams에서 나온다.

그래서 순서를 "Pub/Sub으로 다중 replica 전달을 먼저 완성 → Streams를 outbox·관측과 함께 추가"로 제안한다. 인터페이스는 처음부터 둘을 나눈다. `RealtimeChatFanout`(fan-out)과 `ChatDeliveryQueue`(enqueue/consume/ack/fail)다. 1차에서는 `ChatDeliveryQueue` 없이 UseCase 커밋 뒤 `RealtimeChatFanout`을 직접 부르고, 2차에서 그 호출을 Streams consumer로 옮긴다. 이 순서가 #340·인프라 문서와 다르므로 팀 확인이 필요하다(Q6).

## 5. 회원 이벤트 Kafka 전환

### 5.1 계약

인프라 문서 1장의 원칙을 그대로 적용한다.

| 항목 | 값 |
| --- | --- |
| 토픽 | `member.events.v1` |
| key | 회원 ID. 같은 회원의 가입·변경 순서를 보장한다(단일 broker·파티션 1에서도 key를 둬야 나중에 파티션을 늘릴 수 있다) |
| envelope | `eventId`(UUID), `eventType`(`MemberJoined`, `MemberModified`), `eventVersion`(1), `occurredAt`, `aggregateId`(회원 ID), `correlationId`, `payload` |
| payload | 현재 `UserDto`와 같다: `id`, `nickname`, `role`, `status`, `version`. `version`은 회원 원본 `@Version`이며 복제본의 옛 이벤트 무시에 쓴다 |
| 스키마 | 레포에 JSON Schema 파일을 두고 CI 호환성 검사(인프라 문서 7장). registry 서버는 두지 않는다 |

`UserLoggedOutEvent`는 토픽에 싣지 않는다(5.3).

### 5.2 발행(member-app)

- Spring Modulith event externalization(`spring-modulith-events-kafka`, Modulith BOM 2.1.1에 있음)을 쓴다. 지금 member-app의 publication registry가 그대로 outbox가 되어, 업무 트랜잭션과 같은 DB에 발행 기록이 남고 broker가 내려가 있어도 재기동 뒤 다시 발행된다(`republish-outstanding-events-on-restart: true`는 이미 켜져 있다).
- `UserJoinedEvent`·`UserModifiedEvent` 레코드에 `@Externalized("member.events.v1::#{#this.user().id()}")`를 붙이는 것이 가장 짧다. 다만 이 레코드는 `common/shared`에 있어 다른 앱도 컴파일한다. externalization 의존이 shared로 번지지 않게, member-app 안에서 shared 이벤트를 envelope 레코드로 감싸 외부화하는 어댑터를 둔다. 인프라 문서 1장의 `IntegrationEventPublisher` seam이 이 어댑터다.
- 같은 프로세스의 member 내부 리스너(`MemberChannelSupport` 등)는 지금처럼 Spring Event로 받는다. Kafka는 다른 앱에만 보내는 통로다.

### 5.3 소비(chat-app)

| 항목 | 설계 |
| --- | --- |
| 리스너 | `@KafkaListener(topics = "member.events.v1", groupId = "chat-app")`. `common/kafka`의 자동 설정이 `ConsumerFactory`를 만들고, 역직렬화·그룹·오류 처리 정책은 chat-app이 정한다 |
| 멱등 | `chats.processed_event(event_id PK, event_type, processed_at)` inbox. 이벤트 처리와 inbox INSERT를 한 트랜잭션에서 한다. 같은 `eventId`가 다시 오면 아무것도 하지 않는다 |
| 순서 | key가 회원 ID라 같은 회원의 이벤트는 한 파티션에서 순서대로 온다. `MemberModified`가 `MemberJoined`보다 먼저 오는 경우는 재처리·DLT 재전송 때만 생긴다. 현행 규칙(변경 이벤트는 복제본을 만들지 않고 실패로 남김)을 유지하고, 재시도 뒤 DLT로 보낸다 |
| 버전 | `ReplicaUser.syncFrom`의 `memberVersion` 비교가 이미 옛 이벤트를 무시한다. inbox와 함께 두 겹이 된다 |
| 재시도·DLT | 블로킹 재시도 3회(backoff), 실패는 `member.events.v1.DLT`. 재처리 명령과 runbook은 #339 범위 |
| 연결 닫기 | `syncModified`가 "채팅 불가가 됨"을 돌려주면 `chat:control:v1`에 `CLOSE_USER`를 발행한다(4.2) |
| 기존 리스너 | `ChatUserEventListener`의 `@ApplicationModuleListener` 2개는 Kafka 리스너로 대체한다. `ChatUserFacade`·`ChatUserUseCase`는 그대로 쓴다 |

로그아웃 처리는 설계를 바꾼다.

- 현재는 HTTP 세션 ID로 그 세션의 WebSocket만 닫는다. #370으로 세션이 없어지면 세션 ID도 없다.
- 제안: 로그아웃 즉시 끊기는 하지 않는다. refresh token 폐기(#370) + access token 만료(15분) + STOMP CONNECT 때 JWT 검증(#371)으로 "다시 연결할 수 없음"을 보장한다. 열린 연결은 토큰 만료 시점에 서버가 닫는다(연결 수립 시 `exp`를 세션 속성에 기록하고 스케줄러가 만료된 세션을 닫는다. #310의 "세션 자연 만료" 항목이 이 방식으로 풀린다).
- 즉시 끊어야 하는 정지·탈퇴·강등은 5.3의 제어 채널로 처리한다.
- `UserLoggedOutEvent`와 `ChatLogoutEventListener`, `HttpSessionHandshakeInterceptor`는 #371과 함께 제거한다.

### 5.4 기존 회원 적재

Kafka 전환 시점 이전의 회원은 이벤트가 없다. billing의 `BillingUserBackfill`과 같은 방식으로, member-app 내부 API([internal-members.md](../api/internal-members.md))를 페이지로 읽어 `chat_user`를 만든다. 적재는 `createIfAbsent`라 여러 번 실행해도 안전하다. 적재 뒤에 들어오는 이벤트는 `memberVersion` 비교로 정리된다.

### 5.5 출연 알림 이벤트

`AppearanceRequestSentEvent`·`AppearanceApplicationSubmittedEvent`는 Kafka로 채팅에 다시 연결하지 않는다. 인프라 문서 8장은 알림을 `notification-app`으로 떼고 채팅의 `AppearanceNotificationEventListener`를 제거하는 방향이다. 그때까지 출연 알림은 멈춘 상태이며, 이 사실을 프론트와 팀에 공지한다(Q7). 채팅 자체의 "새 메시지 알림"(`/user/queue/chats/notifications`)은 채팅에 남는다.

## 6. 인증 전환과의 접점(#371)

- STOMP CONNECT의 `Principal.getName()`이 회원 ID이고 authorities에 `ROLE_*`이 있다는 현행 전제를 JWT 검증 필터가 그대로 만족시키면 `ChatStompInterceptor`는 바뀌지 않는다.
- 브라우저 WebSocket 업그레이드에는 `Authorization` 헤더를 실을 수 없다. #371대로 STOMP CONNECT 프레임의 토큰을 chat-app이 member-app JWKS로 검증한다. 핸드셰이크 단계의 `SecurityConfig` 인증 요구는 익명 허용으로 바꾸고 CONNECT에서 거절한다.
- 허용 출처 검사(`hapbang.chat.websocket.allowed-origins`)는 유지한다.

## 7. 진행 순서와 티켓 후보

| 순서 | 작업 | 이슈 | 완료 증거 |
| --- | --- | --- | --- |
| 1 | member-app `member.events.v1` 발행(externalization 어댑터, JSON Schema) | #339 하위 | 발행 직후 앱을 끄고 재기동해도 토픽에 한 번 이상 도착 |
| 2 | chat-app 회원 이벤트 소비(inbox, 재시도·DLT) + 기존 회원 적재 | 신규 `[Chat]` | 새 회원이 가입 직후 채팅 CONNECT 성공. 같은 이벤트 2회 소비 시 결과 동일. 정지 회원의 연결이 모든 replica에서 닫힘 |
| 3 | `RealtimeChatFanout` + Redis Pub/Sub 채널 2개 + 세션 인덱스 | #340 하위 | replica 2개(k3d)에서 서로 다른 replica에 붙은 두 회원이 대화. Pub/Sub 유실을 일부러 내도 재접속 후 이력 동일 |
| 4 | 그룹 채팅방 도메인·API·FE 연동 | 신규 `[Chat]` | 생성·초대·강퇴·폐쇄·나가기 시나리오, 최대 인원 동시 초대, 재입장 가시 범위 |
| 5 | `chat_outbox` + Redis Streams 작업 큐 + 관측 지표 | #340 | XACK 직전 종료 시 pending 회수, failed stream 재처리, 대시보드 |
| 6 | 출연 알림 이관, `AppearanceNotificationEventListener` 제거, 로그아웃 리스너 제거 | #193, #371 | 채팅에 다른 모듈 이벤트 리스너가 없음 |

1·2는 채팅이 분리 환경에서 동작하기 위한 최소 조건이라 다른 작업보다 앞선다. 3과 4는 독립이라 병행할 수 있다. 그룹 방 전달도 4.2의 fan-out을 쓰므로 3이 먼저 병합되는 편이 테스트가 단순하다.

## 8. 결정 필요 사항

[AGENTS.md](../../AGENTS.md) 10·11장에 따라 추측으로 구현하지 않는다. 정해지면 이 문서를 갱신한다.

| # | 질문 | 이 문서의 제안 |
| --- | --- | --- |
| Q1 | 그룹 방을 누가 만들고 누가 초대할 수 있는가 | 생성은 채팅 가능 회원(인플루언서 이상·관리자). 초대는 방장만 |
| Q2 | 그룹에서 혼자 남았을 때 메시지를 보낼 수 있는가 | 보낼 수 있다(1:1의 `CHAT_PARTNER_LEFT`는 1:1에만 적용) |
| Q3 | 그룹 최대 인원 | 50명. 방 잠금 직렬화와 메시지당 전달 횟수의 상한이 된다 |
| Q4 | 그룹에서 나간 회원이 다시 들어오는 방법 | 방장 초대로만. 자유 재입장은 두지 않는다 |
| Q5 | 그룹 방에도 `NOTIFICATION` 메시지를 남기는가 | 남기지 않는다. 알림 모듈 이관 전까지 1:1만 |
| Q6 | Redis Streams를 Pub/Sub보다 뒤로 미루는 순서(4.4) | 미룬다. #340과 인프라 문서 2장의 순서를 이 문서대로 바꾼다 |
| Q7 | 알림 모듈이 생기기 전까지 출연 알림을 멈춘 상태로 둘지 | 멈춘다. Kafka로 채팅에 다시 연결하지 않는다 |
| Q8 | 로그아웃 즉시 연결 끊기를 포기하고 토큰 만료로 대체할지(5.3) | 대체한다. 즉시 끊기는 정지·탈퇴·강등만 |
| Q9 | `member.events.v1`의 소비자 공통 코드(inbox, 재시도·DLT)를 어느 모듈이 소유할지 | 첫 소비자인 chat-app이 만들고, 두 번째 소비자가 생길 때 `common/kafka`로 올린다 |

## 9. 테스트 계획

[테스트 문서](../dev/testing.md)의 Kafka·Redis Testcontainers(#380)를 쓴다.

- 회원 이벤트: `@IntegrationTest` + `@Import(KafkaTestcontainersConfig.class)`로 토픽에 envelope를 넣고 `chat_user`와 inbox를 확인한다. 같은 `eventId` 2회, 옛 `version`, `Modified`가 먼저 오는 경우를 포함한다.
- Pub/Sub: `@Import(RedisTestcontainersConfig.class)`로 `RedisPubSubChatFanout`을 띄우고, 테스트가 별도 구독자로 "다른 replica"를 흉내 내 로컬 세션이 없는 회원에게는 보내지 않는지 확인한다. 실제 replica 2개 전달은 k3d E2E(#373)에서 본다.
- 그룹 방: 기존 `ChatRoomConcurrencyTest`·`ChatRoomReadTest` 패턴으로 동시 초대·강퇴·전송을 검증한다.
- 장애: Redis 중지 중 전송 → 커밋은 성공, 복구 후 이력 조회로 동일 상태. Kafka 중지 중 가입 → publication 보존, 재기동 후 복제본 생성. Toxiproxy 지연·단절은 #344 D 범위.

## 10. 이 문서가 바꾸지 않는 것

- 메시지 저장·순번·읽음·재전송 방지·관리자 기록의 현재 설계
- `chats` 스키마 소유권과 앱별 DB 전환(#338)
- 알림 모듈의 종류·보존 기간(8장, #193)
- Elasticsearch 검색·RAG(9장)
