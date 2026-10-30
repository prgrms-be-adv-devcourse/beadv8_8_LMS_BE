# 계약 (b): 실시간 전파 `RealtimeChatFanout` (#418 → #419)

Stream consumer(#418)가 커밋된 작업을 Pub/Sub으로 내보내는 계약이다. 설계는 [설계 문서](../final-design.md) 4.2·4.5다. 인터페이스·payload·이름은 [공통 골격](parallel-work.md#공통-골격-동결)이다.

## 인터페이스

```java
package com.hapbang.chat.app;

public interface RealtimeChatFanout {

    /** 메시지 전달 작업을 발행한다. @return 발행 명령이 성공한 순간의 구독자 수. 실패하면 예외 */
    long publishMessage(MessageFanout fanout);

    /** 연결 제어 작업을 발행한다. @return 발행 명령이 성공한 순간의 구독자 수. 실패하면 예외 */
    long publishControl(ControlFanout fanout);
}
```

- 반환값은 Redis `PUBLISH` 응답의 구독자 수다. 브라우저 수신자 수나 처리 완료 수가 아니다. 구현은 실제 값을 그대로 돌려준다.
- **구독자 0명은 발행 실패로 본다**(사용자 결정, 2026-10-29). replica 1개에서는 자기 구독이 끊겼다는 뜻이다. #418은 체크포인트를 `PUBLISHED`로 바꾸지 않고 `XACK`하지 않으며, PEL에 남겨 구독 복구 뒤 backoff·횟수 상한 안에서 다시 발행한다. 상한을 넘으면 설계 4.5 7번의 격리 절차를 따른다. 구독자 0명 지표는 남긴다. 구독자가 1명 이상이면 오프라인 회원과 관계없이 발행 완료다.
- 발행 실패는 `RuntimeException`으로 알린다. #418은 예외면 체크포인트를 `PUBLISHED`로 바꾸지 않고 `XACK`하지 않는다.
- 운영 구현은 #419의 `out/redis/RedisRealtimeChatFanout`, 테스트 구현은 테스트 소스의 `InMemoryRealtimeChatFanout`이다. in-memory 구현을 운영 대체 경로로 등록하지 않는다.
- 호출은 DB 트랜잭션 밖에서 한다(#418 책임).

## payload (JSON, 채널 이름의 `v1`과 `type`으로 호환성 관리)

`chat:fanout:v1` ← `MessageFanout`

```json
{
  "deliveryId": "9b0c…",
  "type": "MESSAGE",
  "roomId": 12,
  "recipients": [{ "userId": 3, "participationVersion": 2 }, { "userId": 7, "participationVersion": 1 }],
  "message": { "messageId": 501, "chatRoomId": 12, "roomSequence": 88, "senderUserId": 3, "messageType": "TEXT", "content": "…", "sentAt": "2026-10-29T10:00:00", "clientMessageId": null, "notificationKind": null, "referenceId": null, "parentReferenceId": null, "appearanceRequestSent": null, "appearanceApplicationSubmitted": null },
  "notifyUserIds": [7],
  "senderClientMessageId": "c-123"
}
```

| 필드 | 규칙 |
| --- | --- |
| `deliveryId` | outbox의 안정 ID. 재시도·재적재해도 같다 |
| `recipients` | 메시지 저장 때 확정한 불변 수신자 집합 버전의 항목 전체. 현재 참여자 목록으로 바꾸지 않는다 |
| `message` | `clientMessageId`가 null인 공용 `ChatMessageResponse` |
| `notifyUserIds` | 새 메시지 알림 대상. 전달 규칙이 `NONE`이면 빈 배열, `RECIPIENTS_EXCEPT_SENDER`면 수신자 중 발신자 제외 |
| `senderClientMessageId` | 발신자 본인 프레임에만 붙일 전송 ID. 없으면 null |

`chat:control:v1` ← `ControlFanout`

```json
{ "controlId": "1f3e…", "type": "RECHECK_USER", "userId": 7, "memberVersion": 5, "reason": "MEMBER_MODIFIED" }
```

## 이름 (`ChatRedisKeys`)

`hapbang.chat.redis.namespace`(기본 `local`) 뒤에 붙인다. 테스트는 고유한 namespace를 쓴다.

| 종류 | 이름 |
| --- | --- |
| 전달 Stream / 그룹 | `<ns>:chat:delivery:stream:v1` / `chat-delivery-v1` |
| 제어 Stream / 그룹 | `<ns>:chat:control:stream:v1` / `chat-control-v1` |
| 전달 채널 | `<ns>:chat:fanout:v1` |
| 제어 채널 | `<ns>:chat:control:v1` |

## 구독자 동작 (#419)

- 메시지: 수신자 중 **로컬 연결이 있는 회원**만 골라 회원 자격·현재 활성 참여·`participationVersion` 일치·`roomSequence >= visibleFromSequence`를 배치 조회로 확인하고, 맞으면 `/user/queue/chats/messages`(발신자에게는 `senderClientMessageId`를 붙인 메시지)와 `/user/queue/chats/notifications`로 보낸다. 불일치·조회 실패면 버린다.
- 제어: 최신 채팅 회원 상태를 조회해 지금도 채팅 불가일 때만 로컬 연결을 닫는다.
- 프레임 메타데이터와 재동기화 신호는 [재동기화 계약](resync-frame.md)을 따른다.
