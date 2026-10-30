# 계약 (d): 수신 ACK 경로·응답·오류 (#420, Q11 초안)

클라이언트의 누적 수신 ACK 계약이다. 설계는 [설계 문서](../final-design.md) 4.4다. **FE와 합의했다**(2026-10-29, FE beadv8_8_LMS_FE#127). 형식을 바꾸면 FE 세션에 먼저 알린다.

## 요청

`PUT /api/v1/chats/{chatRoomId}/received`

```json
{ "participationVersion": 3, "receivedSequence": 120 }
```

- 회원은 로그인 principal로 정한다. 요청 값으로 다른 회원의 커서를 지정할 수 없다.
- `receivedSequence`는 이 기기가 **현재 참여 세대의 가시 범위에서 빠짐없이 반영한 연속 구간의 끝**이다. 99까지 확인한 뒤 101만 받았으면 100을 확인하기 전에는 101을 보내지 않는다.
- 같은 방의 ACK는 기기에서 합쳐 보낸다. 최대 대기 시간·누적 건수 상한은 Q12에서 정하고 설정값으로 둔다. 종료 이벤트에만 의존하지 않는다.
- STOMP가 아니라 REST를 쓴다. 커밋 결과를 응답으로 확인하고 기존 오류 형식(`ProblemDetail` + `code`)을 그대로 쓰기 위해서다.

## 성공 응답

`200`, `RsData`:

```json
{ "statusCode": 200, "message": "수신 위치를 반영했습니다.", "data": { "chatRoomId": 12, "participationVersion": 3, "lastReceivedSequence": 120 } }
```

- `lastReceivedSequence`는 반영 뒤 서버의 회원 커서다. 다른 기기가 더 높게 올렸으면 요청 값보다 클 수 있다. 같은 값·낮은 값은 상태를 바꾸지 않고 현재 값으로 200이다(멱등).
- 서버 회원 커서는 "적어도 한 기기가 확인한 위치"다. FE는 이 값을 자기 기기의 확인 위치로 복사하지 않는다.

## 오류

| HTTP | code | 상황 | FE 처리 |
| --- | --- | --- | --- |
| 400 | `INVALID_REQUEST` | 필드 누락, `participationVersion` 1 미만, `receivedSequence` 음수 | 버그. 재시도하지 않는다 |
| 400 | `RECEIVED_SEQUENCE_OUT_OF_RANGE` (신규) | `receivedSequence < visibleFromSequence` 또는 방의 커밋된 마지막 순번 초과 | 재동기화 뒤 값을 다시 계산한다 |
| 403 | `FORBIDDEN` | 로그인 세션의 역할이 채팅 역할이 아님(강등 뒤 다시 로그인 등, 공통 권한 검사) | 재시도하지 않는다 |
| 403 | `CHAT_NOT_ALLOWED` | 정지·강등 등 채팅 불가 | 재시도하지 않는다 |
| 403 | `NOT_CHAT_ROOM_MEMBER` | 활성 참여가 아님(퇴장) | 그 방의 대기 ACK를 버린다 |
| 404 | `CHAT_ROOM_NOT_FOUND` | 방 없음·종료 | 그 방의 대기 ACK를 버린다 |
| 409 | `STALE_PARTICIPATION` (신규) | `participationVersion`이 현재 세대가 아님 | 이전 세대의 캐시·확인 위치를 버리고 방 목록과 이력을 다시 동기화한다 |
| 401 | - | 로그인 없음 | 기존 인증 처리 |

네트워크 오류·5xx·응답 유실이면 ACK를 미확인으로 두고, 같은 세대의 최신 누적 값으로 제한된 횟수만 다시 보낸다.

## 함께 바뀌는 응답

- `GET /api/v1/chats/{chatRoomId}/messages`의 `data`에 `participationVersion`과 `visibleFromSequence`를 추가한다. `cursor`로 부른 이전 페이지와 빈 페이지를 포함해 **모든 페이지에 싣는다**. FE는 세대가 다른 페이지를 기록하지 않는다. 기존 필드는 그대로다.

## FE 처리 (합의 내용)

| 응답 | FE 처리 |
| --- | --- |
| 200 | 확정으로 본다. `lastReceivedSequence`를 기기 확인 위치로 복사하지 않는다 |
| 409 `STALE_PARTICIPATION`, 400 `RECEIVED_SEQUENCE_OUT_OF_RANGE`, 403 `NOT_CHAT_ROOM_MEMBER`, 404 `CHAT_ROOM_NOT_FOUND` | 다시 보내지 않고 그 방의 확인 위치를 버린 뒤 방 목록·이력을 재동기화한다 |
| 400 `INVALID_REQUEST`, 403 `CHAT_NOT_ALLOWED`, 401, 그 밖의 4xx | 버린다 |
| 네트워크 오류·5xx | 지수 대기(2·4·8초)로 최대 3회, 같은 세대의 최신 누적 값을 보낸다 |

- 거절당한 값 이하는 같은 세대에서 다시 보내지 않고, `visibleFromSequence` 미만 값은 보내지 않는다.
- FE 잠정 수치(Q12 전): debounce 1초, 최대 대기 5초, 20건이면 즉시, 대기 방 50개 상한, pagehide·탭 숨김 때 즉시 전송.
- FE는 영속 저장이 없으므로, 확인 위치를 처음 구간까지 잇기 위해 읽는 오래된 페이지는 화면 이력 캐시에 넣지 않고 서버 이력과 대조 완료로만 기록한다. 화면 창 밖의 대조 완료를 [설계 문서](../final-design.md) 4.4의 '적용'으로 본다.

## 이번 범위 밖 (질문으로 남김)

- 다른 참여자의 수신 상태 조회·표시(권한·노출 범위·주기)는 구현하지 않는다. ACK마다 방 전체로 재방송하지 않는다.
