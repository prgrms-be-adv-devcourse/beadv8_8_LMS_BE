# 계약 (f): 알림 메시지 값 `notificationData` (#468)

출연 알림 메시지(`messageType: NOTIFICATION`)의 문구를 FE가 만들도록 서버가 사실만 넘기는 계약이다(사용자 결정 2026-10-30). 설계는 [설계 문서](../final-design.md) 5.5, 원칙은 상위 [알림 문서](../../../../docs/architecture/infrastructure/notification.md)의 "이벤트에는 문구가 아니라 사실을 담는다"다. 형식을 바꾸면 FE 세션에 먼저 알린다.

## 원칙

- 서버는 이 두 알림의 한국어 문구를 만들지 않는다. 알림 종류(`notificationKind`)와 종류별 값(`notificationData`)만 저장하고 보낸다.
- FE는 `notificationKind`별 템플릿에 `notificationData`를 넣어 문구를 만든다.
- 값은 `chat_message.notification_data`(`jsonb`, null 가능)에 저장한다. 버전은 `V202610301511__add_chat_message_notification_data.sql`이다.

## 나오는 곳

`ChatMessageResponse`의 필드라서 이 응답을 쓰는 곳에 모두 담긴다. 기존 필드는 바뀌지 않고 `notificationData`만 더해진다.

| 경로 | 위치 |
| --- | --- |
| REST 메시지 이력 `GET /api/v1/chats/{chatRoomId}/messages` | `data.messages[].notificationData` |
| REST 방 목록 `GET /api/v1/chats` | `data[].lastMessage.notificationData` |
| STOMP `/user/queue/chats/messages`, `/user/queue/chats/notifications` | 프레임 payload의 `notificationData` |
| 관리자 기록 `GET /api/v1/chats/admin/{chatRoomId}/history` | `data.messages[].notificationData` (`ChatMessageHistoryResponse`) |
| 실시간 전파 payload(`MessageFanout.message`) | 같은 `ChatMessageResponse`라 그대로 실린다 |

## 형식

- 모든 종류에 형식 버전 `v`(숫자)를 담는다. 현재 `1`이다. 키를 빼거나 뜻·형식을 바꾸면 `v`를 올리고, 키를 더하는 것은 같은 버전에서 한다. FE는 모르는 키를 무시하고, 모르는 `v`·`notificationKind`면 아래 호환 규칙의 대체 표시를 쓴다.
- 일시는 KST 기준의 **오프셋 없는** `yyyy-MM-dd'T'HH:mm:ss` 문자열이다(예: `"2026-11-20T21:00:00"`). 정책 문서의 "모든 기간·마감·일시는 KST 기준이다"와 출연 이벤트(shared `AppearanceRequestDto`, KST `LocalDateTime`)를 따르고, `sentAt` 등 API의 다른 일시 필드와 같은 형식이다. 초 아래는 버린다. 시간대 변환은 하지 않는다.
- 금액은 숫자(HP, 정수)다. 천 단위 구분·단위 표기는 FE가 한다.
- 닉네임은 알림을 남길 때 chat-app의 채팅 회원 복제본(`chat_user`) 값이다. 출연 이벤트에 닉네임이 없어서다. 이후 닉네임이 바뀌어도 저장된 값은 바뀌지 않는다(알림 당시 값).

### `APPEARANCE_REQUEST_SENT` (출연 요청 도착, 게스트가 받는다)

`senderUserId` = 호스트, `referenceId` = 출연 요청서 ID, `parentReferenceId` = null.

```json
{ "v": 1, "hostNickname": "호스트", "title": "금요일 밤 합방", "fee": 50000,
  "scheduledAt": "2026-11-20T21:00:00", "acceptDeadline": "2026-11-13T23:59:00" }
```

| 키 | 타입 | 값 |
| --- | --- | --- |
| `v` | number | 형식 버전 `1` |
| `hostNickname` | string | 요청을 보낸 호스트 닉네임(채팅 회원 복제본) |
| `title` | string | 요청서 제목(이벤트) |
| `fee` | number | 출연료 HP(이벤트) |
| `scheduledAt` | string | 방송 예정 일시, KST(이벤트) |
| `acceptDeadline` | string | 게스트 수락 기한, KST(이벤트) |

### `APPEARANCE_APPLICATION_SUBMITTED` (출연 모집 지원 도착, 호스트가 받는다)

`senderUserId` = 게스트, `referenceId` = 지원 ID, `parentReferenceId` = 모집글 ID.

```json
{ "v": 1, "guestNickname": "게스트", "recruitTitle": "주말 게임 합방 모집" }
```

| 키 | 타입 | 값 |
| --- | --- | --- |
| `v` | number | 형식 버전 `1` |
| `guestNickname` | string | 지원한 게스트 닉네임(채팅 회원 복제본) |
| `recruitTitle` | string | 모집글 제목(이벤트) |

## `content`와 호환 규칙

- `notificationData`가 있는 알림의 `content`는 **빈 문자열** `""`이다. `chat_message.content`의 `NOT NULL`은 그대로 둔다.
- **`notificationData`가 null이거나 없으면 FE는 `content`를 그대로 보여 준다.** 일반·시스템 메시지와, 이 변경 전에 문구로 남긴 기존 알림이 여기에 해당한다. 기존 행은 고치지 않는다.
- `notificationData`가 있는데 템플릿으로 만들 수 없으면(모르는 `notificationKind`·`v`, 필요한 키 없음, 타입 다름) FE는 `content`가 비어 있지 않으면 `content`를, 비어 있으면 일반 문구를 쓴다. 일반 문구는 아는 종류면 종류별("출연 요청이 도착했습니다.", "출연 모집 지원이 도착했습니다."), 모르는 종류면 "새 알림이 도착했습니다."다. 요청서·모집글 링크는 `referenceId`·`parentReferenceId`로 단다(FE 합의, 2026-10-30).
- 관리자 기록 조회도 같은 규칙이다. 관리자 화면은 회원 화면과 같은 템플릿으로 문구를 만든다. 서버가 관리자용 문구를 따로 만들지 않는다.

## 확장

- 이 필드는 출연 알림 전용이 아니다. 퇴장·재입장 같은 시스템 메시지(`messageType: SYSTEM`)도 이후 같은 방식(종류 + 값 + `v`, 서버 문구 없음)으로 이 필드를 쓴다(#470에서 종류 이름과 값 형식을 정한다).
- 그래서 FE는 `notificationData`를 `NOTIFICATION`일 때만 읽지 말고, 있으면 `messageType`과 종류로 템플릿을 고르고, 모르는 조합은 위 호환 규칙(content → 일반 문구)을 따른다.

## 바뀌지 않는 것

- 멱등 키 `(notification_kind, reference_id)`와 유일 인덱스 `uk_chat_message_notification`, 같은 이벤트를 다시 받아도 알림이 하나라는 규칙
- 전달 경로(1:1 방, `/user/queue/chats/messages`·`/user/queue/chats/notifications`)와 Kafka offset 처리
