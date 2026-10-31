# 계약: 읽음 처리의 참여 세대 조건 (#420)

기존 읽음 처리(`PUT /api/v1/chats/{chatRoomId}/read`)에 참여 세대 조건을 더하는 계약이다(사용자 결정, 2026-10-29). 지연 도착한 이전 세대의 읽음 요청이 재입장 뒤 새 세대의 읽음 위치를 올리지 않게 한다. 읽음과 수신 ACK([수신 ACK 계약](receipt-ack.md))는 계속 독립이다. **FE와 합의했다**(2026-10-29, FE beadv8_8_LMS_FE#127). 형식을 바꾸면 FE 세션에 먼저 알린다.

## 요청

`PUT /api/v1/chats/{chatRoomId}/read`

```json
{ "lastReadSequence": 120, "participationVersion": 3 }
```

| 필드 | 필수 | 규칙 |
| --- | --- | --- |
| `lastReadSequence` | O | 기존과 같다. 0 이상. 방의 마지막 순번을 넘으면 마지막 순번까지만 반영한다(기존 동작) |
| `participationVersion` | X (신규) | 1 이상. 메시지 이력 응답의 `participationVersion`을 보낸다 |

## 동작

- `participationVersion`을 보내면 현재 참여 세대와 같을 때만 반영한다. 다르면 아무것도 바꾸지 않고 409 `STALE_PARTICIPATION`이다.
- 보내지 않으면(전환 전 FE 호환) 서버가 요청 처리 시점의 현재 세대로 본다. 지연 도착한 옛 요청은 구별할 수 없으므로 FE는 전환 뒤 항상 보낸다.
- 어느 경우든 읽음 갱신 UPDATE에 "활성 참여 + 확인한 세대와 같음" 조건을 넣는다. 확인과 갱신 사이에 퇴장·재입장이 커밋되면 반영하지 않는다(409 `STALE_PARTICIPATION` 또는 403 `NOT_CHAT_ROOM_MEMBER`).
- 읽음 순번은 기존처럼 줄지 않는다(조건부 UPDATE). 같은 값·낮은 값은 바꾸지 않고 204다.

## 응답

성공은 기존과 같이 본문 없는 204다.

| HTTP | code | 상황 |
| --- | --- | --- |
| 400 | `INVALID_REQUEST` | `lastReadSequence` 누락·음수, `participationVersion` 1 미만 |
| 403 | `FORBIDDEN` / `CHAT_NOT_ALLOWED` | 채팅 역할이 아님 / 정지·강등 등 채팅 불가 |
| 403 | `NOT_CHAT_ROOM_MEMBER` | 활성 참여가 아님 |
| 404 | `CHAT_ROOM_NOT_FOUND` | 방 없음·종료 |
| 409 | `STALE_PARTICIPATION` (신규 응답) | 보낸 `participationVersion`이 현재 세대가 아님, 또는 처리 중 재입장 |

## FE 처리 (합의 내용)

- 항상 `{ lastReadSequence, participationVersion }`을 보낸다. `participationVersion`은 이력 첫 페이지 응답의 값을 쓰고, 없으면 방 목록의 내 세대를 쓴다. 세대가 바뀌면(재입장) 보낸·확인된 순번 기억을 지우고 새로 센다.
- 409 `STALE_PARTICIPATION`, 403 `NOT_CHAT_ROOM_MEMBER`, 404 `CHAT_ROOM_NOT_FOUND`: 다시 보내지 않고, 그 방의 이전 세대 이력·보내는 중 표시를 버린 뒤 방 목록·이력을 다시 받는다.
- 그 밖의 실패: 기존처럼 3초×횟수 간격으로 최대 3번 다시 보낸다.
