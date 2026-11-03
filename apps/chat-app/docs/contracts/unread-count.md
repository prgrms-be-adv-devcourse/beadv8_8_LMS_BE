# 계약 (g): 메시지별 안 읽은 사람 수 (#615, Q36)

일반 메시지마다 "아직 읽지 않은 현재 참여자 수"를 보여 주는 계약이다. 설계는 [설계 문서](../final-design.md) 3.5와 8장 Q33·Q36, 2026-11-03 사용자 확정 계약이다. 모집용 단체방과 **1:1 방에 같은 식**을 쓴다(**1:1 신규 기능**: 1:1 방은 0 또는 1이다). 방 목록의 "안 읽은 메시지 수"(`unreadCount`), 전송 결과 확인, 누적 수신 ACK([수신 ACK 계약](receipt-ack.md))와 다른 값·다른 계약이다. 형식을 바꾸면 FE 세션에 먼저 알린다.

## 값의 정의

메시지 순번 s(일반 메시지 `messageType: TEXT`)의 값은 한 DB 스냅샷에서 아래를 모두 만족하는 참여 행 수다.

```
현재 참여 중(leftAt null, deletedAt null) AND userId != senderUserId AND visibleFromSequence <= s AND lastReadSequence < s
```

- **보낸 사람은 세지 않는다.**
- **늦게 초대된 회원·재입장한 회원은 그 전 메시지에 들지 않는다**(가시 범위가 그 뒤에서 시작한다). 나간·강퇴된 회원은 빠진다.
- **한 회원은 기기 수와 관계없이 한 명**이다. 한 기기에서 읽으면 그 회원이 읽은 것이다(읽음 위치는 회원 단위, [읽음 계약](read-position.md)). 수신 ACK·서버 발행 성공으로 읽음을 올리지 않는다.
- 채팅 자격(정지·강등)은 보지 않는다. 참여 중이면 센다.
- **시스템 안내(`SYSTEM`)·알림(`NOTIFICATION`)은 세지 않는다**: `unreadMemberCount`와 `readCountVersion`이 둘 다 null이다.
- 누가 읽었는지 명단·개인별 커서는 어디에도 내보내지 않는다.

### 값은 줄기만 한다

한 번 낸 값은 이후 같거나 작다. 읽음 순번은 같은 참여 세대에서 오르기만 하고(조건부 갱신), 나가기·강퇴는 사람을 빼고, 늦은 초대·재입장은 가시 범위가 그 메시지 뒤라 그 메시지의 수에 더해지지 않는다. 그래서 **FE는 같은 메시지에 대해 가진 값과 새 값 중 작은 값을 남긴다**(도착 순서·버전과 관계없이 안전하다). 값이 0이면 숫자를 숨긴다.

### 버전 `readCountVersion`

- 값을 센 문장의 DB 시각(`statement_timestamp()`)을 epoch 마이크로초로 낸 정수다. 저장하지 않는다(행·컬럼을 추가하지 않았다).
- 같은 응답·프레임 안의 값은 모두 같은 문장(같은 스냅샷)에서 셌다.
- 다른 스냅샷 사이의 순서를 대략 알려 주는 값이다(뒤에 센 값일수록 크다). 서로 다른 연결의 문장 시작 시각과 스냅샷 시점은 조금 어긋날 수 있으므로 정확성은 버전이 아니라 위의 "작은 값 남기기"가 지킨다. FE는 버전으로 값을 덮어쓰지 않고, 최신 정도 표시·진단에만 쓴다.
- 확정 계약의 예시는 `clock_timestamp()`였다. 구현은 문장의 스냅샷 시점에 더 가까운 `statement_timestamp()`(문장 시작 시각)를 쓴다. 단위·의미(센 시점의 DB 시각, epoch 마이크로초)는 같다.

## REST: 메시지 이력

`GET /api/v1/chats/{chatRoomId}/messages`

- `data.messages[]`의 각 메시지: 일반 메시지면 `unreadMemberCount`(정수)와 `readCountVersion`(정수), 아니면 둘 다 null.
- `data.readCountVersion`(정수, 신규): 이 페이지의 값을 센 시각. 일반 메시지가 없는 페이지(빈 페이지 포함)도 조회 시각을 싣는다.
- 페이지의 일반 메시지를 **집계 문장 하나**로 센다(메시지마다 참여자를 읽지 않는다). 기존 필드는 그대로다.
- 읽기 전용·일시 중지 단체방도 조회 만료 전까지 같다(Q33).

```json
{ "messages": [ { "messageId": 501, "roomSequence": 88, "messageType": "TEXT", "content": "…", "sender": { "userId": 3, "nickname": "호스트", "withdrawn": false }, "unreadMemberCount": 4, "readCountVersion": 1762149600123456, "…": "…" } ],
  "nextCursor": 87, "participationVersion": 1, "visibleFromSequence": 1, "readCountVersion": 1762149600123456 }
```

싣지 않는 곳(null): 방 목록·방 상세·모집글의 단체방 응답의 `lastMessage`(목록 미리보기에는 숫자를 쓰지 않아 집계하지 않는다), 관리자 기록, 같은 전송 ID 재전송의 응답(이미 받은 값을 그대로 둔다).

## 실시간

### 새 메시지 프레임

방 topic(`/topic/chats/rooms/{roomId}`)의 `MESSAGE` 프레임과 전송 결과 확인(`/user/queue/chats/messages`)의 `ChatMessageResponse`에 **전달 시점의 값**을 싣는다. 전달 파이프라인이 전달 원본(메시지·발신자)을 읽을 때 집계 문장 하나로 센다(메시지마다 한 번, 받는 회원마다가 아니다). 같은 값이 `ROOM_ACTIVITY`의 `lastMessage`에도 실릴 수 있다(FE는 쓰지 않아도 된다). 재처리로 다시 발행하면 그때의 값이다.

### 갱신 프레임 `READ_COUNTS`

방 topic 구독으로 오는 프레임이다. 헤더 `chat-frame-type: READ_COUNTS`, `participation-version` = 그 구독의 세대.

```json
{ "type": "READ_COUNTS", "roomId": 12, "readCountVersion": 1762149601000123, "fromSequence": 41, "toSequence": 140,
  "counts": [ { "roomSequence": 120, "unreadMemberCount": 3 }, { "roomSequence": 140, "unreadMemberCount": 7 } ] }
```

| 필드 | 규칙 |
| --- | --- |
| `fromSequence` | 이 프레임이 다루는 첫 순번. 방의 마지막 100개 순번(코드 기본값, 아래 설정)의 시작과 그 구독 회원의 `visibleFromSequence` 중 큰 값 |
| `toSequence` | 이 프레임이 다루는 끝 순번. 센 시점의 방 마지막 순번 |
| `counts` | 구간 안 일반 메시지 중 **값이 0보다 큰 것만**, 순번 오름차순 |
| `readCountVersion` | 센 시각(위 "버전") |

- **[`fromSequence`, `toSequence`] 안의 일반 메시지 중 `counts`에 없는 순번은 0이다.** 구간 밖(더 오래된 메시지, `toSequence`보다 새 메시지)은 이 프레임으로 바꾸지 않는다. `toSequence`는 확정 계약의 payload `{roomId, readCountVersion, fromSequence, counts}`에 구현에서 더한 필드다: 갱신을 센 뒤 도착한 새 메시지를 0으로 잘못 보지 않게 한다.
- FE 적용: 그 구독의 세대(헤더)가 아는 현재 세대와 다르면 버린다(메시지 프레임과 같은 규칙). 구간 안의 메시지마다 `min(가진 값, 프레임 값)`을 남긴다. 모르는 메시지(아직 받지 않은 순번)는 무시한다.
- **보내는 때**: 읽음 위치가 실제로 오른 읽음 처리(같은 값·낮은 값은 보내지 않는다), 참여·방 상태 변경(초대·강퇴·나가기·폐쇄·호스트 자격 상실)이 **커밋된 뒤**. 방마다 묶음 주기(최대 1초) 동안의 변경을 한 번으로 합친다. 개별 읽음을 방 전체에 다시 보내지 않는다.
- **받는 구독**: 그 방을 연(방 topic을 구독한) 세션 중 구독 세대 == 현재 세대·활성 참여·채팅 가능한 구독. 메시지 프레임과 같은 방 참여 캐시·버전 확인 규칙을 쓴다. 맞지 않는 구독은 이 프레임을 받지 않을 뿐이고 회수는 메시지 전달이 한다.
- 읽기 전용 방도 조회 만료 전까지 갱신을 받는다(Q33). 방을 열지 않은 세션에는 보내지 않는다(그 방을 열 때 이력으로 받는다).
- **최선 노력**: 신호·프레임은 유실될 수 있다(outbox를 쓰지 않는다). 재접속·`RESYNC`·방 열기·순번 간격 확인 때 메시지 이력을 다시 읽어 보정한다. 마지막 100개보다 오래된 메시지의 값은 이력 재조회로만 갱신된다.

## 서버 내부 (참고)

- 신호: 변경 트랜잭션의 커밋 뒤(롤백이면 내지 않는다) 늘 구독하는 서버 채널 `<ns>:chat:read:v1`에 `ReadChangedFanout {roomId, recipientSetVersion}`을 발행한다. `recipientSetVersion`은 참여 변경이 만든 수신자 집합 버전(읽음·폐쇄·호스트 자격 상실은 0)이다. 커밋 뒤 콜백은 상한이 있는 전용 발행 실행기에 넣기만 한다. 그래서 Redis가 느려도 요청·Kafka 소비 스레드와 DB 연결을 붙잡지 않는다. 대기열이 가득 차면 그 신호를 버리고 기록한다(최선 노력).
- 묶음: 받는 앱은 그 방의 로컬 방 topic 구독이 있을 때만 방을 표시하고, 묶음 주기마다 표시된 방 하나에 집계 문장 하나로 센다. 그 문장은 같은 스냅샷의 방 현재 수신자 집합 버전도 읽고, 참여자가 볼 수 없는 방(없음·종료된 1:1 방·조회 만료된 단체방, `ChatRoom.isViewable`과 같은 경계)은 세지 않는다. 그런 방에는 보내지 않는다. 센 **뒤에** 방 참여 캐시를 그 스냅샷 버전과 묶음의 가장 높은 `recipientSetVersion` 중 큰 값 이상으로 맞추고 구독을 고른다. 그래서 센 값에 들어간 강퇴 뒤의 새 메시지가 강퇴된 회원에게 가지 않는다. 같은 가시 범위의 구독은 payload를 한 번만 만든다. [실시간 전파 계약](realtime-fanout.md).
- 설정(코드 기본값, `application.yaml`에 두지 않는다. Q12 실측 때 확정): `hapbang.chat.realtime.read-counts.batch-interval`(1s), `hapbang.chat.realtime.read-counts.window`(100), `hapbang.chat.realtime.read-counts.publish-workers`(1), `hapbang.chat.realtime.read-counts.publish-queue-capacity`(1000).
- 성능: 페이지·전달 원본·묶음마다 집계 문장 하나다. `chat_room_member (chat_room_id, user_id)` 유일 인덱스와 `chat_message (chat_room_id, room_sequence)` 유일 인덱스로 찾으므로 새 인덱스·마이그레이션은 없다. 비용은 (센 메시지 수 × 방 참여자 수) 행 비교이며 정원 실측(#574)에서 함께 측정한다.
- 배포 순서: 지금 FE는 모르는 `chat-frame-type`을 재동기화로 처리한다([재동기화 계약](resync-frame.md)). `READ_COUNTS`를 처리하는 FE를 먼저(또는 함께) 배포해야 읽음마다 재동기화가 일어나지 않는다.
