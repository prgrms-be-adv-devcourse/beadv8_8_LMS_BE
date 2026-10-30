# 계약 (b): 실시간 전파 `RealtimeChatFanout` (#418 → #419)

Stream consumer(#418)가 커밋된 작업을 Pub/Sub으로 내보내는 계약이다. 설계는 [설계 문서](../final-design.md) 4.2·4.5다. 인터페이스·payload·이름은 [공통 골격](parallel-work.md#공통-골격-동결)이다.

> 방 topic 결정(사용자, 2026-10-30)으로 v2 계약이다. 메시지는 방마다 Redis 채널로 내보내고, payload에 수신자 목록 대신 수신자 집합 버전을 싣는다. 이 문서는 #418이 지키는 부분(발행 결과의 의미, payload, 이름)을 적는다. 방 채널·서버 채널 발행과 구독자 동작은 #419 구현이며 여기에는 이해에 필요한 만큼만 적는다. 미정 항목은 설계 문서 Q14~Q30이다.

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

- `publishMessage`의 반환값은 **서버 채널**(`chat:activity:v1`)의 `PUBLISH` 구독자 수다. 서버 채널은 모든 앱이 늘 구독하므로 0명은 자기 구독 단절이다. 방 채널(`chat:room:{roomId}:v1`)의 구독자 0명은 정상(그 방을 연 세션이 없음)이며 성공 판단에 쓰지 않는다. 브라우저 수신자 수나 처리 완료 수가 아니다.
- **구독자 0명은 발행 완료가 아니다**(사용자 결정, 2026-10-29). #418은 체크포인트를 `PUBLISHED`로 바꾸지 않고 `XACK`하지 않는다. 인프라 장애이므로 시도 횟수에 넣지 않고, 그 큐의 새 작업 읽기를 멈춘 뒤 재확인한다(2026-10-30 사용자 결정, [테이블 계약](tables-and-migrations.md)의 "인프라 장애와 시도 횟수"). 구독자 0명 지표는 남긴다. 구독자가 1명 이상이면 오프라인 회원과 관계없이 발행 완료다. 방 topic 전환으로 바뀐 것은 반환값이 가리키는 채널뿐이고, #418의 규칙과 코드는 그대로다.
- 발행 실패는 `RuntimeException`으로 알린다. #418은 예외면 체크포인트를 `PUBLISHED`로 바꾸지 않고 `XACK`하지 않는다. Redis 연결 불가를 `ChatDeliveryUnavailableException`으로 알리면 시도 횟수에 넣지 않는다.
- 재시도·재적재로 같은 작업이 두 번 발행될 수 있다. 받는 쪽은 `messageId`(알림은 `roomSequence`)로 중복을 버린다.
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
| `recipientSetVersion` | 메시지 저장 때 가리킨 불변 수신자 집합의 방 안 버전(`set_version`). 참여 변경마다 1씩 는다. 받는 앱이 방 참여 상태를 다시 읽을지 정하는 기준이다. 현재 참여의 버전으로 바꾸지 않는다 |
| `message` | `clientMessageId`가 null인 공용 `ChatMessageResponse` |
| `notifyRule` | `NONE`(퇴장·재입장 안내) 또는 `RECIPIENTS_EXCEPT_SENDER`(일반 메시지·출연 알림). outbox에 저장한 규칙을 그대로 싣는다 |
| `senderClientMessageId` | 보낸 사람 세션의 프레임에만 붙일 전송 ID. 메시지 행의 값이며 없으면 null |

- consumer(#418)는 집합 항목을 읽지 않고 집합 헤더의 버전만 읽는다. 메시지나 집합 헤더가 없으면 발행하지 않고 원본 누락(`SOURCE_MISSING`)으로 격리한다. 항목 행은 계속 기록하지만 실시간 경로에서 쓰지 않는다(알림 대상은 Redis 접속 정보, 설계 문서 Q21).
- 서버 채널 `chat:activity:v1`의 알림 재료는 #419 구현이 `MessageFanout`에서 만든다(보낸 사람 전송 결과 확인과 방별로 묶은 새 메시지 알림의 재료).

제어 채널 `chat:control:v1` ← `ControlFanout`(변경 없음)

```json
{ "controlId": "1f3e…", "type": "RECHECK_USER", "userId": 7, "memberVersion": 5, "reason": "MEMBER_MODIFIED" }
```

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

옛 전달 채널 `<ns>:chat:fanout:v1`은 없앴다. 발행·구독을 같은 배포에서 바꾸고 옛 채널과 함께 쓰지 않는다.

## 구독자 동작 (#419)

- 서버 채널(`activity`·`control`)은 시작 때 구독하고, 방 채널은 그 방을 연 로컬 세션이 있을 때만 구독한다.
- 메시지: 방 채널을 받은 앱이 그 방을 구독한 로컬 세션 중 현재 참여·참여 세대·가시 범위·자격이 맞는 세션에만 보낸다. 참여 캐시가 `recipientSetVersion`보다 낮으면 다시 읽는다.
- 알림: 서버 채널의 알림 재료로 보낸 사람 전송 결과를 확인하고, `notifyRule`이 `RECIPIENTS_EXCEPT_SENDER`이면 그 방을 구독하지 않은 접속 중인 참여자에게 방별로 묶은 알림을 보낸다.
- 제어: 최신 채팅 회원 상태를 조회해 지금도 채팅 불가일 때만 로컬 연결을 닫는다.
- 세부 규칙과 프레임 형식은 #419의 설계 문서 4장과 [재동기화 계약](resync-frame.md)을 따른다.
