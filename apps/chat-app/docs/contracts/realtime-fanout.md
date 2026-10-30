# 계약 (b): 실시간 전파 `RealtimeChatFanout` (#418 → #419)

Stream consumer(#418)가 커밋된 작업을 Pub/Sub으로 내보내는 계약이다. 설계는 [설계 문서](../final-design.md) 4.2·4.5다. 인터페이스·payload·이름은 [공통 골격](parallel-work.md#공통-골격-동결)이다.

방 topic 결정(사용자, 2026-10-30)에 따른 v2 계약이다. 메시지는 방마다 Redis 채널로 내보내고, payload에 수신자 목록 대신 수신자 집합 버전을 싣는다. 결정 내용은 설계 문서 Q14~Q30이다.

## 인터페이스

```java
package com.hapbang.chat.app;

public interface RealtimeChatFanout {

    /**
     * 메시지 전달 작업을 발행한다. 늘 구독하는 서버 채널에 알림 재료를 먼저 내고, 구독자가 있으면 방 채널에 메시지를 낸다.
     * @return 서버 채널 발행 명령이 성공한 순간의 구독자 수(0이면 자기 구독 단절이며 방 채널은 발행하지 않았다). 실패하면 예외
     */
    long publishMessage(MessageFanout fanout);

    /** 연결 제어 작업을 발행한다. @return 발행 명령이 성공한 순간의 구독자 수. 실패하면 예외 */
    long publishControl(ControlFanout fanout);
}
```

- `publishMessage`는 서버 채널 `chat:activity:v1`에 알림 재료를 **먼저** 발행한다. 구독자가 1명 이상이면 방 채널 `chat:room:{roomId}:v1`에 메시지를 발행한다. 둘 중 하나라도 명령이 실패하면 예외다.
- 반환값은 **서버 채널**의 `PUBLISH` 구독자 수다. 서버 채널은 모든 앱이 늘 구독하므로 0명은 자기 구독 단절이다. 서버 채널이 0명이면 방 채널은 발행하지 않고 0을 돌려준다. 방 채널의 구독자 0명은 정상(그 방을 연 세션이 없음)이며 성공 판단에 쓰지 않는다. 브라우저 수신자 수나 처리 완료 수가 아니다.
- **구독자 0명은 발행 완료가 아니다**(사용자 결정, 2026-10-29). #418은 체크포인트를 `PUBLISHED`로 바꾸지 않고 `XACK`하지 않는다. 인프라 장애이므로 시도 횟수에 넣지 않고, 그 큐의 새 작업 읽기를 멈춘 뒤 재확인한다(2026-10-30 사용자 결정, [테이블 계약](tables-and-migrations.md)의 "인프라 장애와 시도 횟수"). 구독자 0명 지표는 남긴다. 구독자가 1명 이상이면 오프라인 회원과 관계없이 발행 완료다. 방 topic 전환으로 바뀐 것은 반환값이 가리키는 채널뿐이고, #418의 규칙과 코드는 그대로다.
- 발행 실패는 `RuntimeException`으로 알린다. #418은 예외면 체크포인트를 `PUBLISHED`로 바꾸지 않고 `XACK`하지 않는다. Redis 연결 불가를 `ChatDeliveryUnavailableException`으로 알리면 시도 횟수에 넣지 않는다.
- 재시도·재적재로 같은 작업이 두 번 발행될 수 있다. 받는 쪽은 `messageId`(알림은 `lastSequence`)로 중복을 버린다.
- `publishControl`은 제어 채널 `chat:control:v1`(늘 구독)에 발행한다.
- 운영 구현은 #419의 `out/redis/RedisRealtimeChatFanout`, 테스트 구현은 테스트 소스의 `InMemoryRealtimeChatFanout`이다. in-memory 구현을 운영 대체 경로로 등록하지 않는다.
- 호출은 DB 트랜잭션 밖에서 한다(#418 책임).

## payload (JSON, 채널 이름의 `v1`과 `type`으로 호환성 관리)

방 채널 `chat:room:{roomId}:v1` ← `MessageFanout`(v2). 수신자 목록·알림 대상을 싣지 않는다.

```json
{
  "deliveryId": "9b0c…",
  "type": "MESSAGE",
  "roomId": 12,
  "recipientSetVersion": 7,
  "message": { "messageId": 501, "chatRoomId": 12, "roomSequence": 88, "senderUserId": 3, "messageType": "TEXT", "content": "…", "sentAt": "2026-10-29T10:00:00", "clientMessageId": null, "notificationKind": null, "referenceId": null, "parentReferenceId": null },
  "notifyRule": "RECIPIENTS_EXCEPT_SENDER",
  "senderClientMessageId": "c-123"
}
```

| 필드 | 규칙 |
| --- | --- |
| `deliveryId` | outbox의 안정 ID. 재시도·재적재해도 같다 |
| `recipientSetVersion` | 메시지 저장 때 가리킨 불변 수신자 집합의 방 안 버전(`set_version`). 참여 변경마다 1씩 는다. 받는 앱이 방 참여 캐시를 다시 읽을지 정하는 기준이다. 현재 참여의 버전으로 바꾸지 않는다 |
| `message` | `clientMessageId`가 null인 공용 `ChatMessageResponse` |
| `notifyRule` | `NONE`(퇴장·재입장 안내) 또는 `RECIPIENTS_EXCEPT_SENDER`(일반 메시지·출연 알림). outbox에 저장한 규칙(`ChatNotifyRule`)을 그대로 싣는다 |
| `senderClientMessageId` | 보낸 사람 세션의 프레임에만 붙일 전송 ID. 메시지 행의 값이며 없으면 null |

- consumer(#418)는 집합 항목을 읽지 않고 집합 헤더의 버전만 읽는다. 메시지나 집합 헤더가 없으면 발행하지 않고 원본 누락(`SOURCE_MISSING`)으로 격리한다. 항목 행은 계속 기록하지만 실시간 경로에서 쓰지 않는다(알림 대상은 Redis 접속 정보, 설계 문서 Q21).

서버 채널 `chat:activity:v1` ← `RoomActivityFanout`(알림 재료). `RedisRealtimeChatFanout`이 `MessageFanout`에서 만든다.

```json
{ "deliveryId": "9b0c…", "roomId": 12, "recipientSetVersion": 7, "roomSequence": 88, "notifyRule": "RECIPIENTS_EXCEPT_SENDER", "senderUserId": 3, "lastMessage": { "messageId": 501, "chatRoomId": 12, "roomSequence": 88, "senderUserId": 3, "messageType": "TEXT", "content": "…", "sentAt": "2026-10-29T10:00:00", "clientMessageId": null, "notificationKind": null, "referenceId": null, "parentReferenceId": null }, "senderClientMessageId": "c-123" }
```

- 보낸 사람의 전송 결과 확인과 방별로 묶은 새 메시지 알림의 재료다. 모든 앱이 늘 구독하므로 보낸 사람이 그 방을 구독하지 않은 앱에서도 결과 확인을 보낼 수 있다.
- `notifyRule`이 `NONE`인 작업도 발행한다(자기 구독 단절 신호를 겸한다). 알림 묶음에는 넣지 않는다.

제어 채널 `chat:control:v1` ← `ControlFanout`(변경 없음)

```json
{ "controlId": "1f3e…", "type": "RECHECK_USER", "userId": 7, "memberVersion": 5, "reason": "MEMBER_MODIFIED" }
```

- `MessageFanout`·`ControlFanout`·`ChatNotifyRule`은 공통 골격이다. 바꿀 때 이 계약과 [병렬 작업 계약](parallel-work.md)을 먼저 고친다.

## 이름 (`ChatRedisKeys`)

`hapbang.chat.redis.namespace`(기본 `local`) 뒤에 붙인다. 테스트는 고유한 namespace를 쓴다.

| 종류 | 이름 |
| --- | --- |
| 전달 Stream / 그룹 | `<ns>:chat:delivery:stream:v1` / `chat-delivery-v1` |
| 제어 Stream / 그룹 | `<ns>:chat:control:stream:v1` / `chat-control-v1` |
| 방 채널 | `<ns>:chat:room:{roomId}:v1` |
| 서버 채널(알림 재료) | `<ns>:chat:activity:v1` |
| 제어 채널 | `<ns>:chat:control:v1` |
| 접속 정보 | `<ns>:chat:presence:online`, `<ns>:chat:presence:user:{userId}` (#419) |

옛 전달 채널 `<ns>:chat:fanout:v1`과 수신자 목록 DTO `FanoutRecipient`는 없앴다. 발행·구독을 같은 배포에서 바꾸고 옛 채널과 함께 쓰지 않는다.

## 구독자 동작 (#419)

- **구독**: 서버 채널 두 개(`activity`·`control`)는 시작 때 구독해 둔다. 방 채널은 그 방의 로컬 방 topic 구독이 0 → 1이 될 때 `RedisMessageListenerContainer`(구독 연결 하나)에 더하고, 1 → 0이 된 뒤 유예(초기값 60초, Q29) 뒤 뺀다. 유예 중 다시 구독하면 그대로 쓴다. 방 채널 추가는 비동기이고 구독 성공 프레임은 없다(구독 순간 공백 허용, Q23). 참조 수는 잠금 안에서 Redis 명령 없이 세고(해제 대기 방도 같은 값의 상한으로 센다), 컨테이너 추가·제거는 스레드 하나인 실행기가 방마다 맞춘다(구독 확인을 잠금에서 기다리지 않는다). 방 채널 명령이 실패하거나 시간을 넘기면 구독 컨테이너를 재설정해(멈춤 → 서버 채널 + 원하는 방 채널로 등록 다시 쓰기 → 시작) 새 연결에서 다시 구독하고, 서버 채널 재구독으로 `RESYNC(REALTIME_RESUBSCRIBED)`가 간다. 앱이 멈출 때는 컨테이너보다 먼저 방 채널 구독을 멈춘다. 앱 전체 방 채널 수 상한(Q30)에 닿으면 먼저 유예 중인 채널을 정리하고, 그래도 넘으면 그 SUBSCRIBE를 `SUBSCRIPTION_REJECTED(SERVER_BUSY)`로 거절한다.
- **처리 실행기**: 방 채널과 알림 재료는 방 ID로 고른 방별 처리 실행기에서, 제어와 구독 복구 처리는 제어 실행기에서 처리한다(Q22, 순서 보장은 아니다). 대기열이 넘쳐 방 메시지를 버리면 그 방의 로컬 구독 회원에게 `RESYNC(DELIVERY_DROPPED)`를 보낸다. 버린 알림 재료는 FE 주기 대조, 버린 제어는 주기 재검사가 메운다.
- **재구독 감지**: 서버 채널로만 한다. 알림 재료 채널이 다시 구독되면 모든 로컬 연결에 `RESYNC(REALTIME_RESUBSCRIBED)`를, 제어 채널이면 로컬 연결 회원 전체 재검사를 한 번 한다. 방 채널은 같은 구독 연결로 함께 다시 구독되며, 해제 뒤 다시 더하면 확인이 또 오므로 재구독 판단에 쓰지 않는다.
- **메시지(방 채널)**: 그 방의 로컬 방 topic 구독을 고른다(없으면 DB를 읽지 않는다). 방 참여 캐시(활성 참여자 전원과 그 상태를 읽기 직전의 방 수신자 집합 버전)가 `recipientSetVersion`보다 낮으면 한 번 다시 읽는다(방 현재 버전을 먼저, 참여 행을 나중에). 구독 세대 == 현재 세대·활성 참여·`roomSequence >= visibleFromSequence`·채팅 가능인 구독에만 `/topic/chats/rooms/{roomId}` 프레임을 쓴다. 맞지 않는 구독이 있으면 회수하기 전에 DB를 한 번 더 읽고, 그래도 나갔거나 더 새 세대가 있는 구독만 지우고 `SUBSCRIPTION_REVOKED`를 쓴다. 자격 상실·가시 범위 밖은 그 메시지만 건너뛴다. 보낸 사람 회원(`message.senderUserId`)의 구독에 쓰는 프레임에만 `senderClientMessageId`를 `clientMessageId`로 싣는다(Q24). 직렬화는 공용 1회 + 보낸 사람용 1회다. 조회가 실패하면 그 방 누구에게도 쓰지 않고 그 방의 로컬 구독 회원에게 `RESYNC(DELIVERY_DROPPED)`를 보낸다.
- **전송 결과 확인(서버 채널)**: 회원이 보낸 일반 메시지(`lastMessage.messageType == TEXT`, 시스템 안내·알림 제외)이고 보낸 사람 회원의 로컬 연결이 있으면, 방 참여 캐시(같은 버전 규칙)로 보낸 사람의 현재 세대·가시 범위·자격을 확인한 뒤 `senderClientMessageId`를 `clientMessageId`로 붙인 메시지를 `/user/queue/chats/messages`로 바로 보낸다(묶지 않는다, 헤더 `participation-version`). 방 구독과 무관하다. 전송 ID는 선택이라 없으면 `clientMessageId: null`로 보낸다(FE는 `messageId`로 중복을 버린다). 조회가 실패하면 버리고 FE가 이력에서 전송 ID를 찾는다.
- **새 메시지 알림(서버 채널)**: `notifyRule`이 `RECIPIENTS_EXCEPT_SENDER`인 재료만 방별로 모아 묶음 주기(초기값 1초, Q12)마다 `ROOM_ACTIVITY`를 `/user/queue/chats/notifications`로 보낸다. 대상은 방 참여 캐시(묶음의 가장 높은 수신자 집합 버전으로 버전 규칙 적용)의 채팅 가능한 활성 참여자 중 이 앱에 연결이 있고 Redis 접속 정보(`ZMSCORE <ns>:chat:presence:online`)로 접속 중인 회원의, 그 방 topic을 구독하지 않은 이 앱 세션이다(Q21). `messageCount`는 그 회원에게 보이고 그 회원이 보내지 않은 메시지 수이며, 0이면 보내지 않는다. 알림은 최선 노력이라 조회가 실패하면 그 묶음을 버린다.
- **제어**: 최신 채팅 회원 상태를 조회해 지금도 채팅 불가일 때만 로컬 연결을 닫는다. 연결을 닫으면 그 연결의 방 구독도 지운다.
- 프레임 형식은 [재동기화 계약](resync-frame.md)을 따른다.
