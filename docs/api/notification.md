# 알림(notification) API

> 문서 탐색: [알림 문서 INDEX](../notification/INDEX.md)

> - 대상: auxiliary-app `notification` 모듈의 외부 API(알림센터). **구현 상태: REST 4개 구현(#801, dev 병합 #819), 연결 티켓·WebSocket 구현(#829, dev 병합 전), Gateway route 없음(N4)**([모듈 문서](../notification/module.md) 2장).
> - 원천: [알림 설계](../architecture/infrastructure/notification.md) 6.3, 결정 [ND-026](../notification/decisions.md)·ND-023·ND-027·ND-028. 이 문서는 원천에 있는 사실만 옮긴다. 원천에 없는 값은 "미정(구현 때 정함)"으로 둔다.
> - 공통 규칙: 성공 응답 `RsData`, 오류 `ProblemDetail`(+ `code`)은 [API 규칙](conventions.md)을 따른다. 모듈은 `@RestControllerAdvice`를 만들지 않는다.
> - 개념(알림, 읽음, 안 읽은 수, 연결 티켓)은 [도메인 개념](../notification/CONTEXT.md)에 있다.

## 1. 공통

| 항목 | 내용 | 근거 |
| --- | --- | --- |
| 경로 | 모두 `/api/v1/notifications` 아래 | ND-026, 루트 AGENTS 3장 |
| 범위 | 로그인 회원 **본인의 알림만** 다룬다 | ND-026 |
| 인증 | Gateway가 JWT를 검증해 넘기는 사용자 헤더(`X-User-Id`, `X-User-Role`). auxiliary global `SecurityConfig`가 로그인을 요구한다 | 루트 AGENTS 2장, `apps/auxiliary-app/README.md` "권한" |
| 받는 역할 | 유저·인플루언서·인증된 인플루언서. 관리자는 알림센터를 쓰지 않는다 | ND-003 |
| 역할별 접근 규칙(`@PreAuthorize`) | `USER`·`INFLUENCER`·`VERIFIED_INFLUENCER`만. 관리자는 403 | ND-003, ND-052 |
| Gateway route | `/api/v1/notifications`(REST, WebSocket)를 auxiliary-app으로. As-Is에는 없다. WebSocket 경로의 시간 제한은 미결 | N6.3, **U14** |
| Swagger | auxiliary-app 한 명세(`/api/v1/auxiliary/swagger-ui.html`)에 컨트롤러 `@Tag`로 나뉜다 | `apps/auxiliary-app/README.md` "실행과 설정" |

## 2. 엔드포인트

| 기능 | 메서드·경로 | 비고 (원천) | 관련 ND |
| --- | --- | --- | --- |
| 목록 | `GET /api/v1/notifications` | 최신 20개. cursor 없음 | ND-021, ND-022, ND-026 |
| 안 읽은 수 | `GET /api/v1/notifications/unread-count` | | ND-022, ND-026 |
| 읽음 | `PATCH /api/v1/notifications/{notificationId}/read` | 알림을 눌렀을 때 부른다. 목록을 연 것만으로는 읽지 않는다 | ND-023 |
| 전체 읽음 | `PATCH /api/v1/notifications/read-all` | | ND-023 |
| 연결 티켓 | `POST /api/v1/notifications/ws-tickets` | 채팅 `/api/v1/chats/ws-tickets`와 같은 방식 | ND-028 |
| WebSocket | `/api/v1/notifications/ws` | 받는 곳 `/user/queue/notifications`. 프레임에 알림 한 건(묶음은 갱신된 알림)과 안 읽은 수를 담는다 | ND-027, ND-032 |

### 2.1 아직 정하지 않은 것 (모든 엔드포인트 공통)

| 항목 | 상태 |
| --- | --- |
| 요청·응답 DTO 필드 | REST 4개(N2, #801, 모두 `RsData`): 목록 항목 `{id, kind, message, targetType, targetId, occurredAt, readAt}`(값 payload는 보내지 않음, 시각은 ISO-8601 UTC), 안 읽은 수 `{count}`, 읽음은 바뀐 목록 항목 하나, 전체 읽음 `{updatedCount}`. 연결 티켓(N3, `RsData`): `{ticket, expiresInSeconds, connectionExpiresAt}`. WebSocket 프레임(STOMP MESSAGE 본문, `RsData` 아님): `{notification: 목록 항목과 같은 모양, unreadCount}`(ND-059) |
| HTTP 상태 코드(성공·실패) | 성공은 모두 200. 비로그인 401, 관리자 403, 남의·없는·밀려난(Soft Delete된) 알림 읽음 404, 이미 읽은 알림 다시 읽음 200(읽은 시각은 처음 값)(ND-052). 연결 티켓: 60초에 11번째 429 `TOO_MANY_REQUESTS`(공통 요청 한도 초과, `Retry-After: 60`), 티켓 저장소 장애 503 `CONNECTION_TICKET_UNAVAILABLE`(ND-058). WebSocket 핸드셰이크: 티켓 없음·틀림·만료·재사용 401 |
| 오류 코드(`NotificationErrorCode`)와 상황 | 정한 것(ND-052): 남의·없는·밀려난 알림은 모두 `NOTIFICATION_NOT_FOUND`(응답으로 구분할 수 없다). 그 밖은 구현 때 정함. 작성법은 [API 규칙](conventions.md) |
| 문구 형식 | 종류별 문구는 그 종류를 소비하는 작업(N5 이후)에서 정한다(ND-050). 실제 문구는 미결 U3 |
| 연결 티켓 유효 시간·발급 제한 | 채팅 초기값(ND-058) |

## 3. 실시간 연결

1. FE가 `POST /api/v1/notifications/ws-tickets`로 연결 티켓을 받는다(ND-028).
2. 티켓으로 `/api/v1/notifications/ws?ticket=...`에 STOMP 연결을 열고 `/user/queue/notifications`를 구독한다(ND-027). 다른 목적지 구독과 SEND는 ERROR 프레임과 함께 연결이 닫힌다.
3. 저장이 commit된 알림이 프레임으로 온다(읽음·전체 읽음 때는 오지 않는다, ND-059). 실시간 전달은 유실될 수 있으므로 재접속하면 `GET /api/v1/notifications`로 목록을 다시 맞춘다(ND-027).

| 항목 | 상태 |
| --- | --- |
| 채팅 연결과의 관계 | 알림 모듈 자체 STOMP. chat-app 연결을 빌리지 않는다(ND-027). 채팅의 `/user/queue/chats/notifications`는 채팅 소유이며 이 API가 아니다(ND-004) |
| 연결 만료 | 연결 티켓 API가 `X-User-Token-Exp`를 읽어 `min(exp, 발급 + 15분)`에 서버가 끊는다(ND-056). 정책상 종료(로그아웃·탈퇴 등)는 하지 않는다(ND-057). STOMP 규칙 위반(SEND, 다른 목적지 구독)과 전송 상한 초과(ND-058)는 Spring이 연결을 닫는다(ND-061). 받은 `connectionExpiresAt`이 이미 지났으면 연결 직후 4401로 닫히므로 FE는 토큰을 새로 받은 뒤 티켓을 다시 받는다. Gateway `claimToHeaders`의 `exp`는 N4(U15 남은 일) |
| Gateway WebSocket 시간 제한 | auxiliary-app route 기본은 30초·1MiB다. **미결 U14** |
| 연결 종료 사유 표기·재연결 간격 | 연결 만료는 종료 코드 4401 `TOKEN_EXPIRED`(채팅과 같음). 만료로 끊기면 FE가 새 티켓으로 다시 연결한다(ND-056). 재연결 간격은 FE가 정한다 |

## 4. 변경 이력

| 날짜 | 내용 |
| --- | --- |
| 2026-11-06 | 처음 작성. N6.3과 ND-026을 옮겼다. 구현 전 |
| 2026-11-09 | ND-050·ND-052 반영(#801): 역할별 접근 규칙, 정한 상태 코드·오류 코드, 문구를 정하는 때 |
| 2026-11-10 | REST 4개 구현(#801): 응답 필드와 성공 상태 코드. Gateway route는 아직 없다(N4) |
| 2026-11-10 | ND-055~ND-059 반영(N3 사전 결정): 연결 만료, 티켓 수치, 프레임을 보내는 때 |
| 2026-11-10 | 연결 티켓·WebSocket 구현(#829): 티켓 응답·프레임 필드, 429·503·401, 종료 코드 4401. 자체 리뷰로 429를 공통 `TOO_MANY_REQUESTS`+`Retry-After`로 바꾸고 서버가 닫는 경우를 고쳐 적음 |
