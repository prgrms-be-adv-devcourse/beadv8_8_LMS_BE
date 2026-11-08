# 알림(notification) API

> 문서 탐색: [알림 문서 INDEX](../notification/INDEX.md)

> - 대상: auxiliary-app `notification` 모듈의 외부 API(알림센터). **구현 상태: 미구현**(2026-11-06 `dev` e054d6f6 기준 코드·Gateway route 없음, [모듈 문서](../notification/module.md) 2장).
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
| 역할별 접근 규칙(`@PreAuthorize`) | 미정(구현 때 정함) | — |
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
| 요청·응답 DTO 필드(목록 항목, 안 읽은 수, 티켓 응답, WebSocket 프레임) | 미정(구현 때 정함) |
| HTTP 상태 코드(성공·실패) | 미정(구현 때 정함) |
| 오류 코드(`NotificationErrorCode`)와 상황(남의 알림, 없는 알림, 삭제된 알림 읽음 등) | 미정(구현 때 정함). 작성법은 [API 규칙](conventions.md) |
| 문구 형식 | 미결 U3 |
| 연결 티켓 유효 시간·발급 제한 | 미정(구현 때 정함). 채팅 방식을 복사한다(ND-028) |

## 3. 실시간 연결

1. FE가 `POST /api/v1/notifications/ws-tickets`로 연결 티켓을 받는다(ND-028).
2. 티켓으로 `/api/v1/notifications/ws`에 STOMP 연결을 열고 `/user/queue/notifications`를 받는다(ND-027).
3. 저장이 commit된 알림이 프레임으로 온다. 실시간 전달은 유실될 수 있으므로 재접속하면 `GET /api/v1/notifications`로 목록을 다시 맞춘다(ND-027).

| 항목 | 상태 |
| --- | --- |
| 채팅 연결과의 관계 | 알림 모듈 자체 STOMP. chat-app 연결을 빌리지 않는다(ND-027). 채팅의 `/user/queue/chats/notifications`는 채팅 소유이며 이 API가 아니다(ND-004) |
| 토큰 만료 헤더 `X-User-Token-Exp` | auxiliary global 필터와 Gateway `claimToHeaders`에 없다. **미결 U15** |
| Gateway WebSocket 시간 제한 | auxiliary-app route 기본은 30초·1MiB다. **미결 U14** |
| 연결 종료 사유·재연결 간격 | 미정(구현 때 정함) |

## 4. 변경 이력

| 날짜 | 내용 |
| --- | --- |
| 2026-11-06 | 처음 작성. N6.3과 ND-026을 옮겼다. 구현 전 |
