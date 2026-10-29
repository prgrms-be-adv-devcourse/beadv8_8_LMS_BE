# 채팅 모듈 고도화 설계 검토 — 전달 복구, 회원 이벤트 Kafka 전환, 그룹 채팅 제안

앱 분리(#363) 뒤 독립 서비스가 된 `chat-app`을 Final 범위에서 고도화하기 위한 검토 문서다. 현재 코드(dev `da834b1`)를 기준으로 회원 이벤트·Redis 전달·인증의 전환을 설계한다. 그룹 채팅은 승인 전 확장 제안이며 현행 지원 범위는 1:1이다. 현재 구현, 목표 기술 설계, 팀 결정이 필요한 정책을 구분하고, 미정 사항은 8장에 모았다.

- 상위 결정: [최종 프로젝트 인프라 설계](../../../docs/architecture/final-project-infrastructure.md) 1장(Kafka)·2장(PostgreSQL outbox·Redis Pub/Sub)·8장(알림 모듈 분리)
- 관련 이슈: #339(Kafka 구축), #340(Redis 구축), #370·#371(JWT 인증 전환), #193(알림 모듈), #262·#310(보류)
- 공통 규칙: [AGENTS.md](../../../AGENTS.md), [모듈 연동](../../../docs/dev/module-integration.md), [테스트](../../../docs/dev/testing.md)
- 설계 검토 반영: 마지막 메시지 유실, 연결 제어 유실, 참여 세대, 백필 갱신, JWT 세션 매핑, 그룹 방장·행위 대상 기록, REST 시스템 메시지 전환을 보완했다. 이 수정은 문서 변경이며 업무 코드의 구현 완료를 뜻하지 않는다.
- 전달 경로 결정(2026-10-29): PostgreSQL outbox가 전달 작업 보관·선점·재시도·회수·실패 격리를 맡고 Redis는 Pub/Sub으로 replica에 전파한다. Redis Streams는 구현하지 않는다. 기존의 Streams 선행 도입 제안을 이 결정으로 대체한다.

---

## 1. 요약

| 주제 | 제안 | 근거 |
| --- | --- | --- |
| 회원 이벤트 | `common/shared`의 `UserJoinedEvent`·`UserModifiedEvent` 레코드를 그대로 `member.events.v1`에 싣는다(팀 결정, 2026-10-29). chat-app은 `createIfAbsent`·`memberVersion` 비교로 멱등 소비한다. 기존 회원은 생성·버전 기반 갱신을 지원하는 백필로 대조한다 | Kafka 수신과 백필은 채팅 재개 전제다(5장) |
| 실시간 전달 | 메시지와 `chat_outbox`를 함께 저장하고 DB relay → Redis Pub/Sub → 로컬 STOMP로 전달한다 | Redis 장애·프로세스 종료 후의 재시도·작업 회수는 PostgreSQL outbox에서 수행한다. Streams는 구현하지 않는다(4장) |
| 전달 보장 | at-least-once 전달 시도와 이력 재동기화를 함께 사용한다. 마지막 유실을 발견하는 주기적 대조·전송 결과 확인을 FE에 추가한다 | 기존 순번 간격·재연결 복구만으로는 연결이 유지된 마지막 유실을 발견하지 못한다(4.4) |
| 그룹 방 | 승인 뒤 `GROUP`·방장·제목·최대 인원을 추가하는 제안이다. 방장 퇴장·초대·강퇴 정책과 행위 대상 기록을 먼저 확정한다 | 현행 용어집은 1:1만 지원한다. 승인 전 코드·API·정책에 넣지 않는다(3장, 8장) |
| 전달 모델 | 회원별 큐를 유지하고 수신자별 회원 ID·참여 세대를 작업에 담는다. 실제 전달 직전에 현재 참여·가시 범위를 다시 확인한다 | 이전 참여의 지연 메시지가 퇴장·재입장 뒤 전달되는 것을 막는다(4.2) |
| 로그아웃·강제 종료 | 회원 상태 반영과 연결 제어 작업을 같은 DB 트랜잭션에 기록한다. Pub/Sub과 replica별 상태 재검사를 함께 쓴다 | 회원 반영 커밋 뒤 제어 발행 실패도 복구해야 한다. JWT 기기별 로그아웃 식별 계약은 미정이다(5.3, 6장) |
| 출연 알림 | 알림 모듈·FE 이관 완료 전까지 기존 기능을 유지하는 전환 경로를 마련한다 | 서비스 분리로 끊긴 상태는 복구 대상이다. 별도 팀 결정 없이 기능을 중단하지 않는다(5.5) |

진행 순서는 7장에 있다. 회원 이벤트·백필과 PostgreSQL outbox relay·Redis Pub/Sub을 각각 개발하고, JWT 연결 관리·이력 복구·장애 검증을 통합한 뒤 서비스를 전환한다. 그룹 채팅은 별도 승인 뒤 진행한다.

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

목표 Envoy 배포에서 WebSocket 연결이 서로 다른 replica에 배정될 수 있다. 보낸 사람과 받는 사람이 다른 replica에 붙으면 현재 코드의 다음 경로가 실패한다. 이는 목표 배포의 전환 조건이며 현재 Envoy 배포가 완료됐다는 뜻은 아니다.

- `SimpMessagingTemplate.convertAndSendToUser`는 이 프로세스의 simple broker에만 보낸다. 다른 replica의 세션은 받지 못한다.
- `ChatWebSocketSessionRegistry.closeByUserId`는 이 프로세스의 세션만 닫는다.
- `AppearanceNotificationEventListener`처럼 이벤트를 받은 replica가 전달까지 맡는 구조도 같은 한계다.

메시지 저장·순번·읽음은 PostgreSQL에서 하므로 replica 수와 무관하게 안전하다. 바꿀 것은 "커밋 뒤 전달"과 "연결 닫기" 두 지점이다.

## 3. 다대다(그룹) 채팅방

**승인 전 제안이다.** 현행 용어집과 정책은 1:1 채팅을 기준으로 한다. 그룹 범위 승인, 용어·권한·운영 정책, FE API 합의를 마친 뒤 별도 이슈로 구현한다. 이 문서의 50명·방장 전용 초대 등은 확정 정책이 아니다.

### 3.1 지금 모델에서 그대로 쓰는 것

- `chat_room_member`: 방·회원당 1행. `visible_from_sequence`(입장 이후 메시지만 보임), `participation_version`(재입장 세대), `last_read_sequence`(읽음)가 모두 참여자 단위라 N명에 그대로 맞는다.
- `chat_message.room_sequence`: 방 단위 단조 순번. 그룹에서도 FE 간격 복구·커서 조회·안 읽은 수 계산의 기준이 된다.
- `countUnread`·`findLastVisible`: 회원 기준 집계 구조를 재사용한다. 참여자·메시지 수 증가에 따른 비용은 별도 실행 계획과 부하 테스트로 확인하며, 현재 쿼리가 N명에서도 같은 비용이라고 가정하지 않는다.
- 재입장 시 `clearClientMessageIds`와 재입장 안내, 퇴장 안내 시스템 메시지.

### 3.2 바꾸는 것

| 항목 | 변경 |
| --- | --- |
| `ChatRoomType` | `GROUP` 추가. `direct_key`는 `DIRECT`만 쓰고 `GROUP`은 null이다. PostgreSQL 유일 제약은 null을 여러 개 허용하므로 DDL 변경이 없다 |
| `chat_room` 컬럼 | `title`(그룹 제목, nullable), `owner_user_id`(방장, nullable), `max_members`(nullable, 기본값은 정책) 추가. Flyway `V<시각>__add_chat_room_group_columns.sql` |
| `ChatRoomEventType` | `MEMBER_INVITED`, `MEMBER_REMOVED`, `ROOM_CLOSED` 추가(방장 강퇴·폐쇄 이력) |
| 생성 | `ChatRoomUseCase.createGroup(ownerId, title, memberIds)`: 방장 포함 전원이 채팅 가능 회원인지 확인, 방장 포함 최대 인원 검사, 전원 `ChatRoomMember.join`. 1:1의 `openDirectRoom`은 그대로 둔다 |
| 초대 | `invite(roomId, actorId, userIds)`: 방을 잠그고 활성 참여자 수 + 초대 수 ≤ 최대 인원 확인. 이미 나간 회원이면 `rejoin`과 같은 규칙(이전 메시지 안 보임). 누가 초대할 수 있는지는 Q1 |
| 강퇴·폐쇄 | 행위자가 현재 활성 참여자이자 방장인지 검사하고 그룹 방에서만 허용한다. 강퇴는 대상 참여 세대를 검증하고, 폐쇄는 전원 퇴장과 방 종료를 한 트랜잭션으로 처리한다. 방장 자신에 대한 강퇴·방장 퇴장은 Q10 결정 전 구현하지 않는다 |
| 전송 규칙 | `ChatMessageUseCase.send`의 "상대가 없으면 `CHAT_PARTNER_LEFT`"는 1:1 규칙이다. 그룹은 혼자 남아도 보낼 수 있게 할지 Q2. 수신자(`deliverToUserIds`)·알림 대상(`notifyUserIds`)은 지금처럼 활성 참여자 목록에서 계산한다 |
| 종료 | 마지막 참여자가 나가면 `ENDED`(현행 유지). 그룹은 방장 폐쇄도 `ENDED` |
| 응답 | `ChatRoomResponse`에 `roomType`이 이미 있어 FE가 분기할 수 있다. `title`, `ownerUserId`, `maxMembers`를 추가한다. `members`는 N명이 된다 |
| 관리자 기록 | `chat_room_log`에 nullable `target_user_id`를 추가하고 초대·강퇴의 행위자와 대상을 구분한다. 기존 기록은 null을 유지한다. `ChatRoomLogResponse`와 Swagger·FE 관리자 기록도 함께 확장하며, enum 추가만으로 감사 기록이 완성된다고 보지 않는다 |

API 초안(기존 `/api/v1/chats` 아래, [API 규칙](../../../docs/api/conventions.md) 준수):

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
- 초대 목록의 중복·이미 활성인 회원을 정리한 뒤 실제 새 참여 인원을 센다. 방장 퇴장은 위임·퇴장 제한·방 폐쇄 중 Q10에서 정한 정책을 같은 방 잠금 아래 적용한다. 방장 없는 활성 그룹을 허용할지 임의로 결정하지 않는다.
- 전달은 메시지당 참여자 수만큼 `convertAndSendToUser`가 호출된다. Redis 발행은 방당 1회로 줄인다(4.2).

### 3.4 1:1 방과의 관계

- 1:1 방의 `direct_key` 재사용·재입장 규칙은 바꾸지 않는다.
- 같은 두 사람이 그룹에도 함께 있을 수 있다. 그룹은 `direct_key`가 없으므로 충돌하지 않는다.
- 알림(`NOTIFICATION` 메시지)은 1:1 방에만 남긴다. 알림 모듈 이관 전까지만 유효한 규칙이다.

## 4. 실시간 전달: PostgreSQL outbox → Redis Pub/Sub

### 4.1 역할

| 구성 요소 | 역할 |
| --- | --- |
| PostgreSQL | 메시지·참여·읽음의 진실의 원천. 메시지와 전달 outbox를 같은 트랜잭션에 기록하고 작업 상태·처리권·재시도·실패 내역을 관리한다 |
| DB outbox relay | 처리할 작업을 작은 배치로 선점하고 DB 트랜잭션 밖에서 Pub/Sub으로 발행한다. 실패·처리권 만료는 DB 작업 상태로 회복한다 |
| Redis Pub/Sub | 커밋된 메시지와 연결 제어 작업을 모든 chat-app replica에 퍼뜨린다. 수신 완료를 보장하지 않는다 |
| 각 replica의 simple broker | 그 replica에 붙은 WebSocket 세션으로 최종 전달(현행) |
| REST 이력 API + FE 복구 | 기존 순번 간격·재연결 복구에 마지막 유실 대조와 전송 결과 확인을 추가한다 |

2026-10-29 결정에 따라 Redis Streams는 구현하지 않는다. 기존 PostgreSQL outbox가 작업 큐 역할까지 맡아 Redis에 메시지 보관·소비 그룹 상태를 중복으로 쌓지 않는다. 이는 Redis 메모리 적체와 전달 단계를 줄이는 선택이며, DB 조회·상태 갱신·WAL·디스크 사용량은 함께 측정해야 한다. [Redis Pub/Sub](https://redis.io/docs/latest/develop/pubsub/#delivery-semantics)은 수신 실패한 메시지를 다시 보내지 않는다. outbox의 발행 완료도 Redis 명령 성공을 뜻하며 replica·브라우저 수신 완료가 아니다.

### 4.2 채널과 payload

| 채널 | payload | 구독자 동작 |
| --- | --- | --- |
| `chat:fanout:v1` | `{deliveryId, type: MESSAGE, roomId, recipients: [{userId, participationVersion}], message: ChatMessageResponse, notifyUserIds: [...], senderClientMessageId}` | 로컬 연결이 있는 수신자의 현재 회원 자격·참여 세대·가시 범위를 확인한 뒤 회원별 메시지·알림 큐로 보낸다 |
| `chat:control:v1` | `{controlId, type: RECHECK_USER, userId, memberVersion, reason}` | 최신 채팅 회원 상태를 조회하고 채팅 불가일 때 그 회원의 로컬 연결을 닫는다. 옛 제어 명령으로 복구된 자격을 다시 취소하지 않는다 |

- 신규 메시지의 수신자 목록과 참여 세대는 메시지 저장 트랜잭션에서 확정한다. 원래 수신자가 아니었던 새 참여자를 나중에 추가하지 않는다. 알림 대상도 동일한 수신 자격 검사를 거친다.
- 구독 replica는 로컬 STOMP 전달 직전에 자기 chat DB에서 회원의 채팅 가능 여부, 현재 활성 참여, `participationVersion` 일치, `roomSequence >= visibleFromSequence`를 검사한다. 불일치하면 메시지·알림 모두 버린다. 전달 판단 뒤 이미 소켓으로 전송 중인 프레임을 회수하는 보장은 하지 않는다. FE도 현재 참여 세대와 맞지 않는 실시간 응답을 적용하지 않도록 수신 계약을 맞춘다.
- 방 topic 구독 모델로 바꾸지 않는다. 회원별 큐를 유지하면 퇴장자 차단·참여 검증이 서버에만 있고, `ChatStompInterceptor`의 SUBSCRIBE 규칙도 그대로다.
- 단일 채널이라 모든 replica가 모든 메시지를 받는다. replica 1~2개 규모에서는 비용이 작고 구독 관리가 없다. replica가 늘어 비용이 문제가 되면 방별 채널(`chat:room:{id}`)과 로컬 구독 관리로 바꾼다. 아래 인터페이스 뒤에서 바뀌므로 업무 코드는 영향이 없다.
- 직렬화는 JSON. `type`과 채널 이름의 `v1`으로 호환성을 관리한다. 수신자 참여 세대는 STOMP 수신 메타데이터에도 전달하는 계약 초안이며 FE·Swagger 합의 후 확정한다. 재시도해도 `deliveryId`·`controlId`는 유지한다.
- 보낸 사람 본인에게 `clientMessageId`를 돌려주는 현행 규칙은 유지한다. payload의 `message`는 `clientMessageId`가 없는 공용 응답이고, 보낸 사람 분은 `senderClientMessageId` 필드로 따로 싣는다.
- 한 메시지의 동일 payload를 모든 replica에 발행한다. 각 replica는 로컬 세션에만 전달한다. Redis 발행과 STOMP 전달이 성공해도 브라우저의 처리 완료로 기록하지 않는다.

### 4.3 코드 변경 지점

| 현재 | 변경 |
| --- | --- |
| 메시지·시스템 안내를 저장하는 UseCase | 같은 트랜잭션에 수신자 참여 세대를 포함한 `chat_outbox`를 저장한다. DB 저장 뒤 Controller가 직접 발행하는 공백을 제거한다 |
| `ChatMessageStompController` | 신규 메시지의 직접 상대 전달을 제거한다. 같은 `clientMessageId` 재전송은 기존 메시지를 보낸 사람에게만 다시 응답하며 상대 알림·새 업무 메시지를 만들지 않는다 |
| `ApiV1ChatController` | 방 생성의 재입장 안내와 나가기의 퇴장 안내도 동일한 outbox 경로를 사용한다. REST 요청 replica와 수신 소켓 replica가 달라도 동작해야 한다 |
| `AppearanceNotificationEventListener` | 이관 전에는 출연 알림·재입장 안내를 저장하는 트랜잭션에 outbox도 기록한다. 이관 완료 뒤에만 제거한다(5.5) |
| `ChatMessageSender`·어댑터 | outbox 저장소가 선점·결과 기록·재시도·실패 격리를, relay가 작업 실행을, `RealtimeChatFanout`이 Pub/Sub 발행을 맡는다. Streams 전용 `ChatDeliveryQueue`·consumer group·ACK 어댑터를 만들지 않는다. 로컬 STOMP sender는 수신 자격 검사 뒤 최종 전달만 한다. in-memory 어댑터는 테스트 소스에 둔다 |
| `ChatWebSocketSessionRegistry` | 인증 성공 뒤 `userId → STOMP sessionId → 실제 소켓`을 매핑한다. JWT·로그인 식별자·만료 정보와 제거 규칙은 6장을 따른다 |
| 회원 이벤트의 연결 닫기 | 회원 상태·처리 기록(inbox를 둔다면)과 같은 트랜잭션에 제어 outbox를 남긴다. 발행과 replica별 재검사는 5.3을 따른다 |
| 설정 | `../../../common/redis`의 `RedisConfig`는 비어 있다. `RedisMessageListenerContainer`와 채널 구독은 chat-app 안(`out/redis` 패키지)에 둔다. 키·채널 규칙은 채팅이 소유한다(공통 모듈 README 규칙) |
| 관측 | 기존 health 노출 설정을 확인하고 미발행 outbox 수·최장 대기 시간·발행 지연·재시도·처리권 만료 회수·실패 격리·구독 끊김·제어 재검사 실패와 DB 부하·디스크 사용량을 계측한다(#343) |

DB 커밋 뒤 Redis 발행 실패는 업무 저장 실패로 돌려주지 않는다. 미발행 outbox를 재시도하고, FE는 응답을 못 받은 메시지를 '실패 확정'이 아닌 결과 미확인 상태로 다룬다. 이후 이력 확인 또는 같은 `clientMessageId` 재시도로 저장 결과를 확인한다.

### 4.4 마지막 유실과 재접속 이력 복구

기존 FE는 새 메시지의 순번 간격과 WebSocket 재연결 때 이력을 다시 읽는다. 마지막 메시지만 유실되거나 Redis 구독만 끊기고 브라우저 연결이 유지되면 두 조건 모두 발생하지 않는다. 기존 기능만으로 최종 수렴한다고 가정하지 않는다.

- FE는 채팅 연결 중 방 목록과 현재 열어 둔 방의 이력을 유한한 주기로 대조한다. 재연결·화면 복귀·결과 미확인 전송의 확인 시간 초과에도 동기화한다. 주기·시간 초과·페이지 크기는 Q12에서 복구 목표와 부하를 정한 뒤 설정한다.
- 기존 최신순 `GET /api/v1/chats/{roomId}/messages?cursor=...`를 사용한다. 한 페이지로 이전 확인 순번에 도달하지 못하면 이전 페이지를 이어 읽는다. `messageId`로 중복을 제거하고 `roomSequence`로 정렬한다. 조회 범위 대조 전에는 늦게 도착한 한 메시지의 높은 순번을 '중간 구간도 확인한 순번'으로 취급하지 않는다.
- 참여 세대가 바뀌면 이전 세대의 캐시·보내는 중 상태·확인 순번을 폐기하고 새 가시 범위에서 동기화한다. 숨겨진 순번이 있다는 이유로 무한 재조회하지 않는다. 원본 조회가 성공하고 동기화가 완료된 뒤에만 복구 완료로 표시한다.
- Redis 구독 재연결 때 replica는 해당 로컬 연결에 재동기화가 필요함을 알린다. 제어 프레임의 경로·형식은 FE 계약에 포함한다. 이 알림도 유실될 수 있어 주기적 대조를 유지한다.
- 송신 응답 확인 시간이 지나면 이력에서 자신의 `clientMessageId`를 확인한다. 확인되지 않으면 결과 미확인으로 표시하고 같은 ID 재시도를 제공한다. 재시도해도 DB 메시지·상대 알림·안 읽은 수는 중복 증가하지 않는다.

이는 FE 변경이 필요한 추가 설계다. 네트워크 단절 중의 복구 시간을 보장하거나 exactly-once 수신을 주장하지 않는다. 소스 재조회가 가능한 상태에서 마지막 유실까지 발견하고 수렴하는지를 시험한다.

### 4.5 PostgreSQL outbox relay의 실패 경계

1. 메시지·시스템 안내·전달 outbox를 chat DB의 같은 트랜잭션으로 저장한다. 롤백하면 모두 남지 않는다. 전달 ID·메시지 ID·수신자 참여 세대는 재시도 중에도 유지한다.
2. 작업은 `PENDING → PROCESSING → PUBLISHED`로 진행한다. 처리권 확보 시 `claimToken`, `leaseUntil`, 시도 횟수를 기록한다. 재시도 가능한 실패는 `nextAttemptAt`과 함께 `PENDING`으로, 최대 시도 초과나 복구가 필요한 실패는 `DEAD`로 격리한다. 이 상태는 전달 작업의 상태이며 채팅 메시지 저장 성공 여부와 구분한다.
3. 작은 배치와 인덱스로 실행 시각이 된 미발행 작업·처리권이 만료된 작업만 조회한다. `FOR UPDATE SKIP LOCKED` 등으로 짧은 DB 트랜잭션에서 선점·처리권을 기록한 뒤 commit한다. Redis I/O 동안 DB 행 잠금이나 트랜잭션을 유지하지 않는다. [PostgreSQL 문서](https://www.postgresql.org/docs/18/sql-select.html#SQL-FOR-UPDATE-SHARE)
4. relay는 DB 트랜잭션 밖에서 `PUBLISH`하고, 응답을 확인한 뒤 별도의 짧은 트랜잭션으로 `PUBLISHED`를 기록한다. 실패 시 backoff·jitter를 적용하고, 결과 기록은 자신의 `claimToken`이 아직 유효할 때만 허용한다. 만료된 옛 worker는 새 작업 상태를 덮지 못한다.
5. worker 종료 시 `PROCESSING` 작업은 lease 만료 뒤 다른 worker가 회수한다. 발행 성공 직후 DB 결과 기록 전에 종료되거나 앞 worker의 응답이 늦으면 같은 `deliveryId`가 중복 발행될 수 있다. 수신 측은 새 업무 메시지를 저장하지 않고 FE는 `messageId`로 중복을 제거한다. 도착 순서도 방 순번으로 정리한다.
6. `PUBLISH`가 반환하는 구독자 수는 브라우저 수신자 수나 처리 완료 수가 아니다. 구독자 0명은 Redis 명령 실패와 구분하고 오프라인 회원을 위해 무한 재발행하지 않는다. 발행 뒤 구독자가 놓친 메시지는 4.4의 이력 복구로, 연결 제어는 5.3의 상태 재검사로 회복한다.
7. `DEAD` 작업은 조회·원인 확인·권한 있는 운영자의 재처리 경로를 제공한다. 재처리 때 전달 ID와 누적 시도·운영자 이력을 보존한다. 미발행·처리 중 작업을 보관 정책 때문에 지우지 않는다. 완료 기록의 보관·아카이빙은 Soft Delete 원칙을 따른다.
8. `status`·`nextAttemptAt` 및 만료 처리권 조회에 맞는 인덱스를 설계하고 polling 주기·배치 크기·동시 처리 수·lease·재시도 상한·보관 정책을 Q12에서 확정한다. Redis 장애 적체와 복구 후 따라잡기를 시험하며 PostgreSQL의 I/O·WAL·테이블 크기와 Redis·앱 송신 버퍼를 함께 측정한다.

## 5. 회원 이벤트 Kafka 전환

### 5.1 계약

토픽·회원 ID key와 원본 버전 비교는 인프라 문서 1장을 따른다. shared 레코드를 그대로 직렬화하고 별도 envelope를 만들지 않는 아래 결정은 상위 문서의 envelope·eventId 설명과 정합화해야 한다. 이 수정에서는 아래 shared 계약 결정을 유지한다.

| 항목 | 값 |
| --- | --- |
| 토픽 | `member.events.v1` |
| key | 회원 ID. 같은 회원을 같은 파티션으로 보내되 relay·재처리 순서는 원본 버전으로 방어한다. 파티션 증설 시 재배치·이행 순서는 별도 운영 절차로 검증한다 |
| payload | `common/shared`의 `UserJoinedEvent(UserDto)`·`UserModifiedEvent(UserDto)` 레코드를 그대로 JSON으로 싣는다(팀 결정, 2026-10-29). 예: `{"user":{"id":1,"nickname":"…","role":"INFLUENCER","status":"ACTIVE","version":3}}`. 별도 envelope 레코드를 만들지 않는다 |
| 타입 구분 | Spring Kafka `JsonSerializer`가 붙이는 `__TypeId__` 헤더(레코드 클래스 이름). 소비자는 `JsonDeserializer`에 실제 이벤트 패키지 `com.hapbang.shared.member.event`를 신뢰 패키지로 지정해 같은 레코드로 되돌린다. 상위 패키지 이름이 하위 패키지까지 자동 허용한다고 가정하거나 전체 `*`를 신뢰하지 않는다. 두 앱이 같은 shared 클래스를 쓰므로 별도 타입 매핑은 두지 않는다 |
| 멱등 키 | `user.id` + `user.version`. `version`은 회원 원본 `@Version`이라 변경마다 1씩 오른다. 별도 `eventId`는 두지 않는다 |
| 추적 헤더(선택) | `occurredAt`, `correlationId`는 Modulith `EventExternalizationConfiguration.headers(...)`로 Kafka 헤더에 넣을 수 있다. payload를 바꾸지 않으므로 관측성(#343) 단계에서 더한다 |
| 스키마 | 계약은 shared 레코드 자체다. 레코드 변경은 shared 변경이라 모든 앱이 함께 컴파일된다. JSON Schema 파일과 CI 호환성 검사(인프라 문서 7장)는 레코드에서 생성한다 |

기존 `UserLoggedOutEvent`는 HTTP 세션 ID를 담으므로 그대로 JWT 계약에 재사용하지 않는다. 전환기 세션 로그아웃 전달과 JWT 로그인 식별자·종료 이벤트 계약은 6장 및 Q8에서 다룬다.

### 5.2 발행(member-app)

- Spring Modulith event externalization(`spring-modulith-events-kafka`, Modulith BOM 2.1.1에 있음)을 쓴다. 지금 member-app의 publication registry가 그대로 outbox가 되어, 업무 트랜잭션과 같은 DB에 발행 기록이 남고 broker가 내려가 있어도 재기동 뒤 다시 발행된다(`republish-outstanding-events-on-restart: true`는 이미 켜져 있다).
- shared의 `UserJoinedEvent`·`UserModifiedEvent` 레코드에 `@Externalized("member.events.v1::#{#this.user().id()}")`를 붙인다. 루트 `build.gradle.kts`가 모든 서브프로젝트에 `spring-modulith-events-api`를 넣으므로 shared에 새 의존이 생기지 않는다. 발행하는 앱은 member-app뿐이라 다른 앱에는 외부화 구성이 없어도 된다.
- shared 레코드에 애노테이션을 두지 않으려면 member-app에 `EventExternalizationConfiguration` 빈을 두고 `selectByType(...)`·`route(...)`·`routeKey(...)`로 같은 토픽·key를 지정한다. 둘 중 하나를 고른다(Q13).
- 직렬화는 member-app의 `spring.kafka.producer.value-serializer=JsonSerializer`가 맡는다. 발행 코드(`MemberSupport`, `MemberOAuthLoginUseCase` 등)는 바뀌지 않는다.
- 같은 프로세스의 member 내부 리스너(`MemberChannelSupport` 등)는 지금처럼 Spring Event로 받는다. Kafka는 다른 앱에만 보내는 통로다.
- 회원 변경과 외부 발행 기록이 같은 member DB 트랜잭션에 남는지 확인한다. 업무 커밋 직후 프로세스 종료 시험을 두고, 비동기 콜백에서 처음 발행 기록을 만들어 커밋 사이 공백이 생기지 않도록 한다.
- 재시작 재발행 설정만으로 broker 복구 후 실행 중인 앱의 자동 재발행까지 보장된다고 보지 않는다. 실행 중 미완료 발행의 재시도·stale 회수는 #339·#266에서 정책을 맞추고, broker만 재시작하는 시험과 앱을 재시작하는 시험을 각각 둔다.

### 5.3 소비(chat-app)

| 항목 | 설계 |
| --- | --- |
| 리스너 | `@KafkaListener(topics = "member.events.v1", groupId = "chat-app")`로 shared 레코드를 받아 아래 원자적 처리 서비스에 위임한다. 기존 회원 Facade·UseCase를 재사용하되 단순 호출 후 외부 발행만 붙이지 않는다. 역직렬화(`JsonDeserializer`, 5.1의 신뢰 패키지)·그룹·오류 처리 정책은 chat-app이 정한다 |
| 멱등 | 가입은 생성 전용, 변경은 `memberVersion` 비교를 유지한다. 별도 처리 서비스의 로컬 트랜잭션에서 회원 반영과 필요한 제어 outbox 기록을 함께 수행한다. inbox를 두면 `(user_id, member_version)` 키를 같은 트랜잭션에 기록하고 실패 시 함께 롤백한다. Kafka offset은 이 DB 커밋 뒤 처리한다 |
| 순서 | 회원 ID key로 같은 파티션에 보낸다. 발행 relay의 순서·초기 적재·DLT 재전송까지 key만으로 해결되지는 않는다. 변경 이벤트에 복제본이 없으면 생성하지 않고 실패로 남긴다. 초기 적재 중의 실패는 5.4에서 재처리한다 |
| 재시도·DLT | 제한된 블로킹 재시도 뒤 chat 소비자용 DLT로 보낸다. 다른 소비자와 실패 처리를 섞지 않도록 이름·원본 메타데이터·횟수·backoff를 #339와 확정한다. DLT 발행 실패를 처리 성공으로 간주하지 않는다 |
| 연결 닫기 | 최신 버전 반영 결과가 채팅 불가이면 동일 트랜잭션에 `chat_control_outbox`를 기록한다. 기존의 `lostChat` boolean만으로 후속 제어 작업 성공 여부를 판단하지 않는다 |
| 기존 리스너 | 진입점을 Kafka 수신으로 옮긴다. 회원 생성·갱신 로직을 재사용하되 적용 여부·현재 버전·채팅 자격을 처리 결과로 구분하고 제어 작업을 원자적으로 기록하도록 보완한다 |

**연결 제어의 내구성과 각 replica의 재검사**

- 회원 반영과 같은 DB 트랜잭션에 안정적인 `controlId`, 회원 ID·원본 버전·사유를 기록한다. 제어 작업은 회원·버전·종류 기준으로 중복을 식별한다. 백필이 최신 채팅 불가 상태를 반영할 때도 같은 경로를 쓴다.
- relay는 미발행 제어 작업을 다시 보낸다. 발행 성공은 Redis에 보냈다는 뜻이며 모든 replica가 연결을 닫았다는 뜻이 아니다. 회원 버전이나 inbox 때문에 이벤트 재적용을 건너뛰더라도 제어 outbox의 재시도는 독립적으로 계속된다.
- 구독 replica는 자기 chat DB의 최신 상태를 확인해 현재도 채팅 불가인 회원만 닫는다. 자격이 복구된 회원을 지연된 옛 명령으로 닫지 않는다. 상태 조회 실패를 자격 확인 성공으로 취급하지 않는다.
- 각 replica는 로컬 연결을 가진 회원을 묶어서 주기적으로 재검사하고, Redis 구독 복구 직후에도 재검사한다. 구독자가 놓친 명령은 이 경로로 회복한다. 연결 종료는 반복해도 안전해야 한다.
- 회원 반영 커밋 직후 종료, Redis 발행 실패, 특정 replica의 구독 단절, 자격 복구 뒤 옛 명령 도착을 각각 시험한다. Pub/Sub 수신만으로 '모든 replica에서 즉시 종료'를 보장한다고 표현하지 않는다.

### 5.4 기존 회원 적재

Kafka 전환 이전의 회원과 전환 중 놓친 변경을 member-app 내부 API([internal-members.md](../../../docs/api/internal-members.md))의 키셋 페이지로 대조한다. `createIfAbsent`는 기존 행을 바로 무시하므로 오래된 복제본 갱신에는 사용할 수 없다. 실제 Billing 백필도 생성·버전 기반 갱신·실패 집계를 수행한다.

- 백필은 회원 원본 스냅샷을 사용하는 전용 초기 적재 경로다. 없는 회원은 생성하고 기존 회원은 `ReplicaUser.syncFrom`의 버전 규칙으로 갱신한다. 가입 이벤트의 생성 전용·변경 이벤트의 갱신 전용 규칙을 일반 upsert로 바꾸지 않는다. 백필을 Kafka 가입 이벤트 소비 완료로 기록하지도 않는다.
- 내부 HTTP 조회 중 chat DB 트랜잭션을 열지 않는다. 회원마다 짧은 트랜잭션에서 생성·버전 비교·필요한 제어 작업을 처리한다. Kafka와 동시 생성·갱신 충돌은 유일 제약·낙관적 락으로 감지하고 재조회·제한된 재시도로 수렴시킨다.
- 권한 있는 운영자가 페이지 단위로 실행하고 생성·갱신·옛 버전 무시·실패 건수, 실패 회원 ID·재개 커서를 기록한다. 실패가 남으면 완료로 표시하지 않는다. 재실행은 최신 값을 과거로 되돌리지 않아야 한다.
- 전환 순서는 회원 Kafka 발행 시작·기준 offset 기록 → chat 소비 시작 → 기존 회원 백필 → 실패 회원·가입 전 변경 이벤트/DLT 재처리 → 원본 버전 대조·미처리 작업 확인 → 사용자 요청 전환이다. 페이지 조회는 전체 회원의 단일 시점 스냅샷이 아니므로 이 경계를 이벤트 수신과 함께 검증한다.
- 최신 이벤트 뒤 옛 백필 페이지 도착, 오래된 기존 복제본 갱신, 백필과 가입 이벤트 동시 생성, 중간 실패 후 재개를 필수 시험으로 둔다.

### 5.5 출연 알림 이벤트

목표 책임은 `notification-app`으로 옮기되 상위 인프라 문서 8장의 이관 순서를 유지한다. 앱 분리로 현재 전달되지 않는 것은 복구 대상이며, 공지만으로 기능 중단을 승인한 것으로 보지 않는다.

1. 분리 앱을 먼저 운영해야 하면 기존 출연 이벤트 2종을 Kafka로 받아 채팅 알림을 유지하는 전환 어댑터를 #339·출연 담당자와 맞춘다. 중복 알림은 기존 `(notificationKind, referenceId)` 제약으로 흡수하고 알림 메시지와 전달 outbox를 함께 기록한다.
2. 알림 모듈이 같은 사실을 소비하기 시작하더라도 FE 전환 전에는 채팅 알림을 제거하지 않는다. FE가 알림 API·destination으로 바뀐 뒤 채팅 쪽 신규 소비·기록을 중단한다. cutover 시점·재처리 범위와 중복 화면 노출 방지는 이관 계약에 남긴다.
3. 기존 `NOTIFICATION` 채팅 메시지는 대화 이력으로 보존한다. 채팅 자체의 새 메시지 알림은 채팅에 남긴다.

전환 어댑터 없이 알림 모듈과 한 번에 전환할지, 중간 기능 공백을 허용할지는 Q7의 팀 결정이다. 결정 전 기본은 기존 기능 유지이며, 끊긴 상태를 정상 완료로 배포하지 않는다.

## 6. 인증 전환과의 접점(#371)

- 브라우저 WebSocket 업그레이드에는 임의의 `Authorization` 헤더를 실을 수 없다. 핸드셰이크의 해당 경로만 익명 허용하고 CONNECT·STOMP 프레임의 Bearer 토큰을 member-app JWKS로 검증한다. 인증 전에는 애플리케이션 SEND·SUBSCRIBE를 허용하지 않는다.
- JWT 인증 ChannelInterceptor가 토큰을 검증하고 `accessor.setUser(Authentication)`으로 회원 ID·`ROLE_*`을 설정한 다음 기존 채팅 자격 검사가 실행되게 한다. 인터셉터 순서를 명시하고 CONNECT·STOMP 양쪽의 우회·재인증 시도를 시험한다.
- Spring은 STOMP 인증 사용자를 별도로 관리하므로 transport `WebSocketSession.getPrincipal()`이 바뀐다고 가정하지 않는다. 연결 decorator는 실제 소켓을 WebSocket/STOMP session ID로 보관하고, 인증 성공 경로에서 `userId`, 검증된 `exp`, 확정된 로그인 식별자를 결합한다. 이후 회원별 로컬 연결 인덱스에 넣고 종료·인증 실패 시 모든 인덱스를 제거한다.
- 인증과 세션 매핑이 끝나기 전에는 CONNECT를 완료하지 않는다. 매핑 직후 최신 회원 자격을 재검사하고 이후에도 5.3의 상태 재검사를 적용한다.
- 검증한 `exp`로 연결 만료를 예약하고 주기적 대조도 수행한다. 임의의 프레임 속성으로 만료를 연장하지 않는다. FE는 refresh로 받은 새 토큰을 CONNECT에 실어 재연결하고 이력을 동기화한다. 만료 종료 사유와 재연결 동작은 FE 계약에 포함한다.
- refresh token 폐기만으로 아직 유효한 access token의 재연결을 막을 수는 없다. 기존 '로그아웃한 기기의 연결 종료, 다른 기기 유지'를 보존하려면 #370에서 안정적인 로그인 식별자·폐기 상태 확인·종료 이벤트 발행 계약을 정해야 한다(Q8). 종료 사실은 내구성 있게 전달하고 폐기 상태 재대조 경로도 정한다. 이 문서에서 JWT claim을 임의로 추가하거나 즉시 종료를 폐기하지 않는다.
- `UserLoggedOutEvent`·`ChatLogoutEventListener`·`HttpSessionHandshakeInterceptor`는 세션 방식이 사용되는 동안 유지한다. JWT 대체 경로와 기기별 로그아웃 시험이 통과한 뒤 #370·#371 전환과 함께 제거한다. 만료까지만 기다리는 방식은 Q8에서 별도로 승인될 때만 적용한다.
- 허용 출처 검사(`hapbang.chat.websocket.allowed-origins`)는 유지한다.
- REST 사용자 헤더 인증·STOMP CONNECT JWT·기존 세션 방식의 전환 시점을 #371·Envoy·FE와 맞춘다. 새 인증이 준비되기 전에 기존 인증만 제거하는 중간 배포를 하지 않는다.

## 7. 진행 순서와 티켓 후보

| 순서 | 작업 | 이슈 | 완료 증거 |
| --- | --- | --- | --- |
| 1 | shared 회원 이벤트 직렬화·호환성, member-app externalization 방식·재시도 확정 | #339, Q13 | 업무 커밋 직후 종료해도 발행 기록 보존. broker만 복구해도 미처리 발행 재개 |
| 2A | chat 회원 소비·처리 트랜잭션·제어 outbox·버전 기반 백필 | #339 하위 | 중복·역순·동시 생성·오래된 복제본 갱신·백필 재개 검증. 이 단계만으로 실시간 종료 완료를 선언하지 않음 |
| 2B | 메시지·시스템 안내 outbox, DB 선점·lease 회수·재시도·실패 격리, Pub/Sub·로컬 전달·관측 | #340 하위 | 선점 직후·발행 직후 종료 복구, 옛 claimToken 결과 무시, Redis 장애 뒤 적체 해소, replica 간 일반·시스템 메시지 전달, 제어 유실 뒤 상태 재검사 |
| 3 | JWT 세션 매핑·만료·로그아웃, FE CONNECT·전송 결과 확인·이력 동기화 | #370·#371·#310, FE | 토큰 인증과 실제 소켓 결합, 마지막 유실 복구, 참여 세대 변경, 기기별 종료·재연결 계약 검증 |
| 4 | 분리 DB와 서비스 경계 통합·전환 리허설 | #338·#373·#344 | 기존 데이터·Flyway·발행 이력 보존, 백필·DLT 대조, replica 2개 시험과 전환·복귀 절차 확인 |
| 5 | 알림 모듈·FE 이관 뒤 출연 알림 신규 소비 종료 | #193 | 이관 완료 확인 뒤에만 기존 리스너 제거. 기존 NOTIFICATION 이력 보존 |
| 별도 승인 뒤 | 그룹 채팅 도메인·API·FE·관리자 기록 확장 | 신규 `[Chat]` | 그룹 범위와 Q1~5·Q10 승인, 용어·정책 갱신 후 동시 초대·방장 퇴장·행위 대상 기록 검증 |

2A·2B는 인터페이스를 맞춘 뒤 병렬 개발할 수 있다. 통합 완료 조건은 양쪽과 3·4를 함께 검증한다. 개발 중에는 공통 Testcontainers를 사용하고 운영 인프라 준비를 기다리지 않는다. 그룹 범위 확대나 기능 중단을 기술 전환의 선행 조건으로 묶지 않는다.

## 8. 결정 필요 사항

[AGENTS.md](../../../AGENTS.md) 10·11장에 따라 추측으로 구현하지 않는다. 정해지면 이 문서를 갱신한다.

Q1~5·Q10은 승인 전 그룹 제안이다. 기존 1:1·출연 알림·기기별 로그아웃 동작을 유지하는 것이 기본이다. Q6은 2026-10-29 사용자 결정으로 확정했다. 그 밖의 미정 계약·수치는 구현 담당자와 FE·관련 모듈이 확정한 뒤 결과를 여기에 기록한다.

| # | 질문 | 이 문서의 제안 |
| --- | --- | --- |
| Q1 | 그룹 방을 누가 만들고 누가 초대할 수 있는가 | 생성은 채팅 가능 회원(인플루언서 이상·관리자). 초대는 방장만 |
| Q2 | 그룹에서 혼자 남았을 때 메시지를 보낼 수 있는가 | 보낼 수 있다(1:1의 `CHAT_PARTNER_LEFT`는 1:1에만 적용) |
| Q3 | 그룹 최대 인원 | 1000명. 방 잠금 직렬화와 메시지당 전달 횟수의 상한이 된다 |
| Q4 | 그룹에서 나간 회원이 다시 들어오는 방법 |  자유 재입장은 가능 |
| Q5 | 그룹 방에도 `NOTIFICATION` 메시지를 남기는가 | 남기지 않는다. 알림 모듈 이관 전까지 1:1만 |
| Q6 | 채팅 전달에 Redis Streams를 도입할지 | **도입하지 않는다(2026-10-29 결정).** PostgreSQL outbox가 보관·선점·재시도·회수·실패 격리를 맡고 Redis Pub/Sub으로 replica에 전파한다. 마지막 유실 복구·연결 상태 재검사는 유지한다 |
| Q7 | 알림 모듈 이관 전 출연 알림을 유지하는 방법 | 기존 기능 유지가 기본이다. 임시 Kafka 어댑터 또는 알림 모듈·FE 동시 전환을 정한다. 기능 공백은 별도 팀 승인 없이는 허용하지 않는다 |
| Q8 | JWT에서 기기별 로그아웃·폐기 상태 확인·기존 연결 종료를 어떻게 표현할지 | #370과 로그인 식별자·종료 이벤트·재접속 거절·상태 재대조 계약을 확정한다. 만료까지 기다리는 방식은 승인 전 제안으로만 남긴다 |
| Q9 | `member.events.v1`의 소비자 공통 코드(역직렬화 설정, 재시도·DLT)를 어느 모듈이 소유할지 | 첫 소비자인 chat-app이 만들고, 두 번째 소비자가 생길 때 `common/kafka`로 올린다 |
| Q10 | 그룹 방장 퇴장·강퇴·자격 상실 때 권한을 어떻게 유지할지 | 위임·퇴장 제한·폐쇄 중 정책을 결정한다. 방장 없는 활성 그룹 허용 여부와 자기 강퇴도 함께 정하고 용어·정책에 반영한다 |
| Q11 | 참여 세대·재동기화·전송 결과 미확인을 FE에 어떻게 전달할지 | 기존 회원별 큐와 이력 API를 유지하면서 수신 메타데이터·재동기화 신호·UI 상태를 FE와 확정한다(4.2·4.4) |
| Q12 | 복구 주기·전송 확인 시간 초과·백필 제한·DB polling·batch·lease·재시도·보관 수치는 얼마인지 | 복구 목표와 Redis 메모리·DB I/O 부하 시험으로 상한을 정해 설정·runbook에 기록한다. 무한 재시도·미처리 작업 삭제로 대체하지 않는다 |
| Q13 | 토픽·key 지정을 shared 레코드의 `@Externalized`로 할지, member-app의 `EventExternalizationConfiguration`으로 할지(5.2) | shared 레코드의 `@Externalized`. 계약과 토픽이 한곳에 보인다 |

## 9. 테스트 계획

[테스트 문서](../../../docs/dev/testing.md)의 Kafka·Redis Testcontainers(#380)를 쓴다.

| 경계 | 필수 시나리오·완료 증거 |
| --- | --- |
| 회원 이벤트 | Kafka 컨테이너에 실제 shared 레코드를 직렬화해 넣고 가입·변경·중복·옛 버전·가입 전 변경을 검증한다. 상태·처리 기록·제어 작업이 함께 커밋·롤백되는지 확인한다 |
| 연결 제어 | 회원 반영 커밋 직후 종료, 제어 발행 실패, 한 replica의 구독 단절 후에도 relay·상태 재검사로 연결이 닫힌다. 새 자격 반영 뒤 옛 제어 명령은 연결을 닫지 않는다 |
| 백필 | 기존 오래된 행 갱신, 최신 이벤트 뒤 옛 페이지, 가입과 동시 생성, 실패 회원 재실행·커서 재개가 최신 상태로 수렴한다 |
| 메시지 outbox relay | 메시지 롤백 시 outbox 없음, 커밋·선점 직후 종료 시 작업 보존·lease 회수, Pub/Sub 발행 뒤 DB 결과 기록 실패 시 중복 흡수, 옛 claimToken 결과 무시, DEAD 격리·권한 있는 재처리가 동작한다. Redis 장애 적체와 복구 후 DB polling·발행 지연·메모리를 측정한다 |
| 수신 자격 | 이전 참여의 메시지를 지연시킨 뒤 퇴장·재입장·강퇴하면 현재 세대·가시 범위에 맞지 않는 메시지와 알림이 전달되지 않는다. 같은 메시지는 FE에 한 번 반영된다 |
| 일반·시스템 메시지 | 실제 chat-app 2개에 서로 다른 사용자를 연결하고 일반 전송·REST 퇴장·재입장·이관 전 출연 알림을 확인한다. 별도 Redis 구독자만 띄운 시험을 replica 2개 검증으로 간주하지 않는다 |
| 마지막 유실·FE 복구 | 다음 메시지 없이 마지막 프레임만 버리고 WebSocket은 유지한다. 주기적 대조로 수신 이력이 복구되고 송신의 결과 미확인이 정리된다. 여러 페이지 누락·역순 도착·참여 세대 변경도 포함한다 |
| JWT | 익명 핸드셰이크 후 CONNECT·STOMP 인증, 위조·만료·토큰 누락, 인터셉터 순서, transport principal 없이 회원별 종료, exp 만료, 새 토큰 재연결, 기기별 로그아웃을 검증한다 |
| broker·Redis 장애 | broker 중단 중 회원 변경 기록 보존, broker만 복구한 뒤 재발행, Redis 중단 중 메시지·outbox 보존과 재발행, 구독만 복구한 뒤 재동기화를 구분해 확인한다 |
| 승인 뒤 그룹 | 동시 초대·초대 중복·최대 인원, 방장 퇴장·자격 상실, 행위자와 대상이 구분되는 감사 기록, 강퇴 뒤 지연 메시지·재입장 가시 범위를 검증한다 |

Kafka·Redis 테스트는 필요한 공통 설정만 Import한다. 실제 두 앱 검증은 각 앱의 엔티티를 다른 앱 테스트에 끌어오지 않고 공개 API·이벤트·ID로 준비한다. FE는 제어 가능한 타이머로 복구를 검증하고 Toxiproxy 지연·단절은 #344 D에 연결한다. 재시도·재조회·비동기 대기에는 제한 시간을 둔다.

## 10. 이 문서가 바꾸지 않는 것

- PostgreSQL 메시지 원본, 방 순번·읽음의 단조 증가, 전송 ID·참여 세대에 따른 재전송 방지, 기존 1:1 방·관리자 기록의 보존 규칙
- 이 문서가 추가로 제안하는 outbox·제어 작업·복구 메타데이터·승인 후 그룹 감사 필드 외의 업무 정책
- `chats` 스키마 소유권과 앱별 DB 전환(#338)
- 알림 모듈의 종류·보존 기간(8장, #193)
- Elasticsearch 검색·RAG(9장)
