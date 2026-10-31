# 계약 (e): 재동기화 신호 프레임과 수신 메타데이터 (#419, Q11 초안)

실시간 경로가 끊겼다 복구됐을 때 FE에 이력 재동기화를 알리는 계약과, 방 topic·회원별 큐로 오는 프레임의 형식이다. 설계는 [설계 문서](../final-design.md) 4.2·4.4, 방 topic 결정은 Q14~Q30이다. **FE와 합의했다**(2026-10-29, 방 topic 전환은 2026-10-30, FE beadv8_8_LMS_FE#127). 형식을 바꾸면 FE 세션에 먼저 알린다.

## 목적지

방 메시지는 방 topic으로 보내고, 회원별 큐는 그대로 둔다(사용자 결정, 2026-10-30). 알림 모듈이 생기면 안 읽은 수·알림·개인 오류를 그쪽으로 옮기고 chat의 `/user` 경로를 없앤다.

| 목적지 | FE | 내용 |
| --- | --- | --- |
| `/topic/chats/rooms/{roomId}` | 열어 둔 방만 구독(Q14). "열어 둔 방"은 화면 경로가 그 방인 것이다. 탭이 숨겨져도 구독을 유지한다 | 그 방의 새 메시지. 같은 구독으로 그 세션에만 구독 거절·회수 프레임도 온다(성공 프레임은 없다) |
| `/user/queue/chats/messages` | 구독 | **자기가 보낸 메시지의 전송 결과 확인만**(전송 ID 포함. 전송 ID 없이 보냈으면 `clientMessageId: null`. 자기 모든 세션. 같은 전송 ID 재전송의 응답 포함). 남의 메시지는 오지 않는다 |
| `/user/queue/chats/notifications` | 구독(Q20) | 방별로 묶은 새 메시지 알림 `ROOM_ACTIVITY`. 그 방 topic을 구독한 세션에는 오지 않는다 |
| `/user/queue/chats/sync` | 구독 | `RESYNC` |
| `/user/queue/chats/errors` | 구독 | 전송 오류 |

전송은 `SEND /pub/chats/{roomId}/messages`다.

## 재동기화 신호

- 목적지: `/user/queue/chats/sync` (회원별 큐. `ChatStompInterceptor`의 `/user/` 구독 규칙 안이다)
- 보내는 때: 아래 `reason`별로 정한 대상에게 한 번씩 보낸다.

```json
{ "type": "RESYNC", "reason": "REALTIME_RESUBSCRIBED", "occurredAt": "2026-10-29T10:00:00" }
```

| 필드 | 값 |
| --- | --- |
| `type` | `RESYNC` |
| `reason` | 아래 사유 표의 값. 이후 사유를 추가할 수 있으므로 FE는 모르는 값도 재동기화로 처리한다 |
| `occurredAt` | 서버 시각 |

| `reason` | 보내는 때·대상 | 합의 |
| --- | --- | --- |
| `REALTIME_RESUBSCRIBED` | chat-app의 Redis 구독(서버 채널)이 끊겼다가 다시 연결된 직후, 그 앱의 **모든 로컬 연결**에 | FE 합의(2026-10-29) |
| `DELIVERY_DROPPED` | chat-app의 실시간 처리 실행기가 넘쳐 방 메시지를 버렸거나 전달 직전 참여 상태 조회가 실패했을 때, 그 방 topic을 구독한 로컬 회원에게만 | FE 합의(2026-10-29). FE 코드 변경 없음(모르는 사유와 같이 재동기화) |

FE 처리: 방 목록과 열어 둔 방의 이력을 자기 확인 위치부터 다시 대조한다. 이 신호도 유실될 수 있으므로 FE의 주기적 대조를 유지한다(주기는 Q12).

## 방 topic 구독

- SUBSCRIBE에 헤더 `participation-version`(필수, 1 이상)을 붙인다. 값은 이력 응답 또는 방 목록의 내 세대다. 헤더가 없거나 1 미만이면 경로 형식 위반과 같이 ERROR + 연결 종료다.
- 같은 방을 한 세션에서 두 번 구독하지 않는다. 방을 닫으면(화면 경로가 바뀌면) UNSUBSCRIBE한다. 탭 숨김은 닫기가 아니다. 세션당 구독 수 상한(초기값 Q12)이 있다.
- 재연결하면 열어 둔 방을 다시 구독한다. 세대가 바뀌면(재입장) 새 세대로 다시 구독한다. 다시 구독할 때마다 새 구독 ID를 쓴다.
- 서버가 모르는 구독 ID의 UNSUBSCRIBE(`SUBSCRIPTION_REJECTED`·`SUBSCRIPTION_REVOKED` 뒤, 재연결 경합 등)는 ERROR·연결 종료 없이 무시한다(설계 문서 4.2 인터셉터 규칙의 "모르는 ID는 무시").
- 구독 성공을 알리는 프레임은 없다. `SUBSCRIPTION_REJECTED`가 오지 않으면 성공이다. 서버는 구독을 등록하기 전에는 그 구독 ID로 `MESSAGE` 프레임을 쓰지 않는다.
- **구독 순간의 공백(허용)**: 구독하는 순간 전후에 저장된 메시지는 그 구독으로 오지 않을 수 있다(2026-10-30 사용자 결정, 설계 문서 Q23). FE는 주기 대조·순번 간격 확인·`RESYNC`로 메운다. 방을 열 때 이력을 읽으므로 대부분은 이력에 들어 있다.

방 topic 구독으로 오는 프레임은 STOMP 헤더 `chat-frame-type`으로 구분한다. 헤더가 없으면 `MESSAGE`다.

| `chat-frame-type` | payload | FE 처리 |
| --- | --- | --- |
| (없음) `MESSAGE` | `ChatMessageResponse`. 헤더 `participation-version` = 그 구독의 세대. `clientMessageId`는 **보낸 사람 회원의 세션에 쓰는 프레임에만** 싣고 다른 참여자 프레임에는 null이다(R1, Q24) | `messageId` 중복 제거, `roomSequence` 정렬, 아래 "프레임의 참여 세대" 규칙을 따른다. 자기 메시지는 `clientMessageId`로 보내는 중 항목과 맞춘다(내용으로 맞추지 않는다) |
| `SUBSCRIPTION_REJECTED` | `{type, roomId, reason, currentParticipationVersion?}`. `reason`: `NOT_MEMBER`·`STALE_PARTICIPATION`·`ROOM_NOT_FOUND`·`CHAT_NOT_ALLOWED`·`TOO_MANY_SUBSCRIPTIONS`·`SERVER_BUSY`(서버의 방 채널 수 상한, 설계 문서 Q30. 구독 확인 조회 실패도 이 사유다). `currentParticipationVersion`은 `STALE_PARTICIPATION`일 때만 싣는 선택 필드로 자기 현재 세대다(남의 정보가 아니다) | 서버는 구독을 등록하지 않았다. 연결은 유지된다(Q18). 아래 "거절·회수 사유별 FE 처리" |
| `SUBSCRIPTION_REVOKED` | `{type, roomId, reason, currentParticipationVersion?}`. `reason`: `LEFT`·`REMOVED`·`STALE_PARTICIPATION`·`ROOM_CLOSED`(`REMOVED`·`ROOM_CLOSED`는 강퇴·폐쇄가 생기면 쓴다). `currentParticipationVersion`은 `STALE_PARTICIPATION`일 때만 싣는다 | 서버가 구독을 지웠다. 바로 UNSUBSCRIBE하고 그 방의 실시간 반영을 멈춘다. 이후 그 구독으로 온 메시지는 적용하지 않는다. 아래 "거절·회수 사유별 FE 처리" |

**거절·회수 사유별 FE 처리(FE 합의 내용)**

| 사유 | FE |
| --- | --- |
| `STALE_PARTICIPATION` | 방 목록(또는 `currentParticipationVersion`)이 더 높은 세대를 보여 줄 때만 그 세대로 **한 번** 다시 구독한다. 아니면 다시 구독하지 않고 방 목록·이력을 다시 받는다 |
| `NOT_MEMBER`·`ROOM_NOT_FOUND`·`CHAT_NOT_ALLOWED`·`LEFT`·`REMOVED`·`ROOM_CLOSED` | 다시 구독하지 않는다. 방 목록·이력을 다시 받는다 |
| `TOO_MANY_SUBSCRIPTIONS` | 열려 있지 않은 방의 구독을 정리한 뒤 **한 번** 다시 구독한다 |
| `SERVER_BUSY` | 그 방은 실시간 없이 `ROOM_ACTIVITY`·이력으로 쓰고, 일정 시간(초기값 Q12) 뒤 **한 번** 다시 구독한다 |

- 모르는 `chat-frame-type`·`reason`은 재동기화로 처리한다.
- 경로 형식 위반, 다른 회원 큐·`/queue`·그 밖의 `/topic` 구독, `/topic`·`/queue`·`/user`로의 SEND는 ERROR + 연결 종료다(클라이언트 오류).
- 퇴장 커밋 뒤 저장된 메시지는 오지 않는다. 퇴장 전에 저장된 메시지는 퇴장 뒤에 올 수 있다(Q19).

## 프레임의 참여 세대

- 방 topic `MESSAGE` 프레임의 헤더 `participation-version`은 그 구독의 세대다. `/user/queue/chats/messages`(전송 결과 확인) 프레임의 같은 헤더는 보낸 사람의 현재 세대다.
- payload(`ChatMessageResponse`)는 바꾸지 않는다.
- FE는 헤더 값이 그 방에서 알고 있는 현재 세대보다 낮으면 프레임을 적용하지 않는다. 더 높으면 그 프레임은 적용하지 않고, 그 탭의 옛 세대 이력·보내는 중 표시·확인 위치를 버린 뒤 방 목록·이력을 새로 받는다(그 메시지는 새 세대 이력으로 들어온다).
- `ROOM_ACTIVITY`에는 헤더를 붙이지 않고 payload의 `participationVersion`으로 비교한다(아래).

## 새 메시지 알림 `ROOM_ACTIVITY`

`/user/queue/chats/notifications`:

```json
{ "type": "ROOM_ACTIVITY", "chatRoomId": 12, "participationVersion": 3, "lastSequence": 88, "messageCount": 5, "lastMessage": { "messageId": 501, "chatRoomId": 12, "roomSequence": 88, "senderUserId": 3, "messageType": "TEXT", "content": "…", "sentAt": "2026-10-29T10:00:00", "clientMessageId": null, "notificationKind": null, "referenceId": null, "parentReferenceId": null } }
```

- 방마다 묶음 주기(초기값 1초, Q12) 동안의 새 메시지를 하나로 합친다. 알림 대상 규칙(`RECIPIENTS_EXCEPT_SENDER`)을 따르므로 퇴장·재입장 안내만 있는 구간은 오지 않는다. `messageCount`는 받는 회원에게 보이고 그 회원이 보내지 않은 메시지 수이며, 0이면 보내지 않는다(자기 메시지만 있는 구간은 오지 않는다).
- 그 방 topic을 구독한 세션에는 보내지 않는다. 같은 회원의 다른 세션(구독하지 않은 탭·기기)은 받는다.
- FE는 방 목록의 마지막 메시지를 `lastMessage`로 바꾸고 안 읽은 수를 `messageCount`만큼 올린다(추정치). 방을 열 때·방 목록 재조회·`RESYNC` 때 서버 값으로 맞춘다.
- 세대는 **payload의 `participationVersion`**으로 비교한다. 비교 규칙(낮으면 버림, 높으면 재동기화)은 메시지 프레임과 같다. 알림은 메시지 전체가 아니므로 수신 ACK의 근거로 쓰지 않는다.
- FE 처리(FE 합의 내용): `lastSequence`가 그 방에서 이미 알고 있는 마지막 순번 이하이면 중복으로 버린다. 안 읽은 수는 `min(messageCount, lastSequence − 알고 있는 마지막 순번)`만큼 올린다(묶음이 겹치거나 순서가 바뀌어도 두 번 세지 않게).
- 예전의 `NEW_MESSAGE` 알림은 없앴다(Q20).

## 프레임 순서

- 서버는 한 STOMP 세션 안의 프레임 순서를 보장하지 않는다. 메시지·알림·`RESYNC`·오류 프레임이 보낸 순서와 다르게 도착할 수 있다(오케스트레이터 결정·FE 합의, 2026-10-30).
- 클라이언트→서버: 한 STOMP 세션에서 보낸 프레임(CONNECT·SUBSCRIBE·SEND 등)은 서버가 받은 순서대로 하나씩 처리한다. 같은 세션에서 빠르게 연달아 보낸 두 SEND는 보낸 순서대로 저장된다(방 순번도 그 순서다). 다른 세션(다른 탭·기기) 사이의 순서는 보장하지 않는다.
- **연결별 처리 대기 프레임 상한**: 한 세션에서 처리를 시작하지 않은 채 기다리는 프레임은 64개까지다(초기값, Q12 미확정). 넘으면 넘긴 프레임은 버리고 그 세션만 `4500` + `INBOUND_OVERFLOW`로 닫는다. 이미 받은 프레임(상한 안의 프레임)은 처리되고, 버린 프레임은 처리되지 않는다.
- **클라이언트 동시 전송 상한**: 클라이언트는 결과를 받지 못한(미확인) SEND를 한 세션에서 동시에 10개까지만 보낸다. 재연결 뒤 미확인 전송을 다시 보낼 때도 이 상한을 지킨다(한꺼번에 몰아 보내 상한을 넘기지 않게).
- **DISCONNECT 뒤 프레임 없음**: STOMP 규약대로 DISCONNECT 뒤에는 프레임을 보내지 않는다. 서버는 한 세션의 DISCONNECT를 받은 뒤(처리 전이라도) 그 세션의 다른 프레임(SUBSCRIBE·SEND 등)을 처리하지 않고 버린다. 연결이 끊긴 뒤 늦게 도착한 프레임도 같다. 다시 연결하면 새 세션이므로 영향이 없다.
- FE 처리: `messageId`로 중복을 버리고(방 topic과 전송 결과 확인 사이도) `roomSequence`로 정렬한다.
- **순번 간격**: FE는 순번 간격을 보면 잠정 1초(Q12, 미확정) 기다린 뒤 간격이 남아 있을 때만 대조한다. 이력을 불러온 방이면 방 목록을 먼저 받고 그 방을 최신 페이지부터 다시 읽는다(동시에 최대 2개). 그 밖에는 방 목록만 다시 받는다. 순서만 바뀐 경우에는 다시 조회하지 않는다. FE #127에 반영했다.
- **RESYNC 처리 순서 수정(FE #127)**: 방 대조는 방 목록 응답이 끝난 뒤 시작한다. 문서의 순서 "ACK 다시 열기 → 방 목록 → 열어 둔 방 대조"를 지킨다.

## FE 처리 (합의 내용)

- `RESYNC`를 받으면 사유와 관계없이 방 목록과 열어 둔 방의 이력을 바로 대조한다. 주기 대조(잠정 30초 ±20%)는 계속 돈다.
- `RESYNC`를 받으면 사유와 관계없이 차례로 한다.
  1. 재시도를 모두 써 버린 수신 ACK를 다시 연다.
  2. 방 목록을 다시 받는다. 열지 않은 방의 마지막 메시지·안 읽은 수는 여기서 바로잡힌다.
  3. 열어 둔 방은 최신 페이지부터 건너뛰지 않고 다시 읽은 뒤 대조한다.
- 열지 않은 방에서 버려진 메시지는 그 방을 열 때 이력으로 받는다.
- 재연결: 정책 종료 2종을 뺀 종료는 3초부터 두 배씩 최대 30초, 매번 0~1초 무작위 대기를 더한다. 연결되면 전체 재조회와 대조를 한다.
- 전송 결과 확인: 10초 안에 자기 메시지 큐(`/user/queue/chats/messages`)로 같은 `clientMessageId` 응답이 없으면 최신 2페이지에서 찾는다. 없으면 "결과 미확인"으로 두고 같은 ID 재시도를 제공한다. 소켓 단절은 실패가 아니라 결과 미확인이다.

## 연결 종료 사유

| 종료 | 의미 | FE |
| --- | --- | --- |
| `1008` + `LOGGED_OUT` | 로그아웃(기존) | 재연결하지 않는다 |
| `1008` + `CHAT_NOT_ALLOWED` | 정지·강등·탈퇴(기존, 이제 RECHECK_USER로도 닫힌다) | 재연결하지 않는다 |
| 그 밖의 종료(느린 수신자의 송신 버퍼 초과, 앱 전체 송·수신 대기열 초과 `4500` + `OUTBOUND_OVERFLOW`·`INBOUND_OVERFLOW` 포함) | 서버가 연결을 유지할 수 없음 | backoff·jitter로 재연결하고 재동기화한다. 수신 대기열 초과로 버려진 전송은 결과 미확인이므로 같은 `clientMessageId`로 다시 보낸다 |

연결이 닫히면 서버는 그 연결의 방 topic 구독을 모두 지운다.
