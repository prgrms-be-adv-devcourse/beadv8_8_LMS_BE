# 알림(notification) API

> 문서 탐색: [알림 문서 INDEX](../notification/INDEX.md)

> - 대상: auxiliary-app `notification` 모듈의 외부 API(알림센터). **구현 상태: REST 4개 구현(#801, dev 병합 #819), 연결 티켓·WebSocket 구현(#829, dev 병합 #834), Gateway route 구현(N4 #847, ND-062~ND-064·ND-067, dev 병합 #853). 이벤트 소비·Backfill 재실행 API는 N5(#859) 확정 To-Be**([모듈 문서](../notification/module.md) 2장).
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
| 역할별 접근 규칙(`@PreAuthorize`) | 회원이 쓰는 알림 API(목록·안 읽은 수·읽음·전체 읽음·연결 티켓)는 `USER`·`INFLUENCER`·`VERIFIED_INFLUENCER`만, 관리자는 403. Backfill 재실행 API는 `ADMIN`만 | ND-003, ND-052, ND-079 |
| Gateway route | REST `/api/v1/notifications`는 auxiliary-app 일반 route(30초·1MiB), WebSocket은 `GET /api/v1/notifications/ws` 전용 route(ND-062)이고 Gateway JWT 인증을 걸지 않아 티켓으로만 인증한다(ND-067) | N6.3, ND-062, ND-067 |
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
| 회원 복제본 Backfill 재실행 시작(관리자) | `POST /api/v1/notifications/admin/user-replica/backfill` | `ADMIN`만(그 밖 403, 비로그인 401). **비동기**: 202 실행 조회와 같은 모양(`status: RUNNING`, `finishedAt`·`result`·`failureCode`는 null)을 바로 주고 실행은 뒤에서 한다. 시작 응답은 작업 결과가 아니다. 다른 실행(기동 Backfill·다른 pod·다른 요청)이 돌고 있으면 409 `BACKFILL_RUNNING`, 실행 상태를 기록할 수 없으면 실행하지 않고 503 `BACKFILL_STATUS_UNAVAILABLE`. 운영자 도구이며 FE 알림센터가 부르지 않는다 | ND-077~ND-079, ND-089, ND-092 |
| 회원 복제본 Backfill 실행 조회(관리자) | `GET /api/v1/notifications/admin/user-replica/backfill/{runId}` | `ADMIN`만. 200 `{runId, status, startedAt, updatedAt, finishedAt, result, failureCode}`. `status`: RUNNING(페이지마다 `updatedAt` 갱신), COMPLETED(`result` = scanned·applied·unchanged·skipped·skippedUserIds), FAILED(`failureCode` = `BACKFILL_FAILED`, 결과 없음, 완료 아님. 다시 시작하면 된다), UNKNOWN(RUNNING인데 5분 넘게 갱신이 없어 실제 실행 여부를 알 수 없음). 보관 7일, 없으면 404 `BACKFILL_RUN_NOT_FOUND` | ND-089, ND-092 |

### 2.1 아직 정하지 않은 것 (모든 엔드포인트 공통)

| 항목 | 상태 |
| --- | --- |
| 요청·응답 DTO 필드 | REST 4개(N2, #801, 모두 `RsData`): 목록 항목 `{id, kind, message, targetType, targetId, occurredAt, readAt}`(값 payload는 보내지 않음, 시각은 ISO-8601 UTC), 안 읽은 수 `{count}`, 읽음은 바뀐 목록 항목 하나, 전체 읽음 `{updatedCount}`. 연결 티켓(N3, `RsData`): `{ticket, expiresInSeconds, connectionExpiresAt}`. WebSocket 프레임(STOMP MESSAGE 본문, `RsData` 아님): `{notification: 목록 항목과 같은 모양, unreadCount}`(ND-059) |
| HTTP 상태 코드(성공·실패) | 성공은 모두 200. 비로그인 401, 관리자 403, 남의·없는·밀려난(Soft Delete된) 알림 읽음 404, 이미 읽은 알림 다시 읽음 200(읽은 시각은 처음 값)(ND-052). 연결 티켓: 60초에 11번째 429 `TOO_MANY_REQUESTS`(공통 요청 한도 초과, `Retry-After: 60`), 티켓 저장소 장애 503 `CONNECTION_TICKET_UNAVAILABLE`(ND-058). WebSocket 핸드셰이크: 티켓 없음·틀림·만료·재사용 401 |
| 오류 코드(`NotificationErrorCode`)와 상황 | 정한 것(ND-052): 남의·없는·밀려난 알림은 모두 `NOTIFICATION_NOT_FOUND`(응답으로 구분할 수 없다). 그 밖은 구현 때 정함. 작성법은 [API 규칙](conventions.md) |
| 문구 형식 | 서버가 만든 완성 문장(`message`)을 그대로 보여 준다. 종류별 문구는 [알림 종류](../notification/notification-catalog.md) 0.5(N5 11종, ND-075)이고 나머지 종류는 그 종류를 소비하는 작업에서 정한다(ND-050, U3) |
| 연결 티켓 유효 시간·발급 제한 | 채팅 초기값(ND-058) |

### 2.2 이동 대상 (FE 계약, ND-074·ND-084·ND-111)

목록 항목의 `targetType`·`targetId`는 알림을 눌렀을 때 갈 업무 자원이다. 둘 다 `null`이면 이동할 곳이 없다. 종류별 값은 [알림 종류](../notification/notification-catalog.md) 각 행의 "이동 대상" 칸이 원본이다. N5(ND-074)·N6(ND-084)·N7 출연(ND-111)에서 쓰는 값은 아래와 같다.

| `targetType` | `targetId` | 쓰는 종류 |
| --- | --- | --- |
| `APPEARANCE_REQUEST` | 출연 요청 ID | 출연 요청 도착, 요청 거절·응답 전 취소·만료 |
| `APPEARANCE_RECRUIT` | 모집글 ID | 모집 지원 도착, 미선정, 모집 마감 |
| `CHARGE` | 충전 ID | 충전 완료 |
| `REFUND_REQUEST` | 환불 요청 ID | 환불 완료 |
| `EXCHANGE` | 환전 ID | 환전 거절·송금 완료·송금 실패·최종 실패 |
| `APPEARANCE` | 출연 ID(지급 완료는 `sourceId`, 원천이 요청이든 모집이든. 출연 취소 요청 3종은 `cancelRequest.appearanceId`, 요청 수락·지원 선정은 이벤트의 `appearanceId`) | 지급 완료, 출연 취소 요청 도착·수락·거절, 요청 수락, 지원 선정, 호스트 일방 취소, 자동 확인, 불참 |
| `LECTURE_ORDER_ITEM` | 주문 항목 ID(`orderItemId`) | 이의 제기 판단·반환 완료·철회·반환 지연(구매자·판매자 같은 값) |
| `null` | `null` | 가입 환영, 승급 승인 |

## 3. 실시간 연결

1. FE가 `POST /api/v1/notifications/ws-tickets`로 연결 티켓을 받는다(ND-028).
2. 티켓으로 `/api/v1/notifications/ws?ticket=...`에 STOMP 연결을 열고 `/user/queue/notifications`를 구독한다(ND-027). 다른 목적지 구독과 SEND는 ERROR 프레임과 함께 연결이 닫힌다.
3. 저장이 commit된 알림이 프레임으로 온다(읽음·전체 읽음 때는 오지 않는다, ND-059). 실시간 전달은 유실될 수 있으므로 재접속하면 `GET /api/v1/notifications`로 목록을 다시 맞춘다(ND-027).

| 항목 | 상태 |
| --- | --- |
| 채팅 연결과의 관계 | 알림 모듈 자체 STOMP. chat-app 연결을 빌리지 않는다(ND-027). 채팅의 `/user/queue/chats/notifications`는 채팅 소유이며 이 API가 아니다(ND-004) |
| 연결 만료 | 연결 티켓 API가 `X-User-Token-Exp`를 읽어 `min(exp, 발급 + 15분)`에 서버가 끊는다(ND-056). 정책상 종료(로그아웃·탈퇴 등)는 하지 않는다(ND-057). STOMP 규칙 위반(SEND, 다른 목적지 구독)과 전송 상한 초과(ND-058)는 Spring이 연결을 닫는다(ND-061). 받은 `connectionExpiresAt`이 이미 지났으면 연결 직후 4401로 닫히므로 FE는 토큰을 새로 받은 뒤 티켓을 다시 받는다. Gateway `claimToHeaders`의 `exp`는 ND-064(N4) |
| Gateway WebSocket 시간 제한·heart-beat | 전체 시간 제한 없음, stream idle 5분. 서버 STOMP heart-beat 25초(보내기·받기)로 알림이 없어도 연결 만료까지 유지한다. **FE 계약(ND-068)**: (1) CONNECT에서 heart-beat를 켠다(보내기 0이 아님) (2) 서버 heart-beat를 받을 수 있게 받기 간격을 0이 아닌 값으로 둔다. STOMP 협상상 받기가 0이면 서버도 보내지 않아 알림이 없을 때 5분마다 끊긴다(stompjs 기본 10초면 25초로 맞춰진다) (3) 비정상 종료(4401이 아닌 종료 포함)에는 새 티켓으로 다시 연결한다. 클라이언트 heart-beat가 받기 간격의 3배(운영 75초) 동안 없으면 서버가 4401이 아닌 코드로 닫는다. 서버는 받기 0인 CONNECT를 거절하지 않는다 (ND-063·ND-068) |
| 연결 종료 사유 표기·재연결 간격 | 연결 만료는 종료 코드 4401 `TOKEN_EXPIRED`(채팅과 같음). 만료로 끊기면 FE가 새 티켓으로 다시 연결한다(ND-056). 재연결 간격은 FE가 정한다 |

## 4. 변경 이력

| 날짜 | 내용 |
| --- | --- |
| 2026-11-06 | 처음 작성. N6.3과 ND-026을 옮겼다. 구현 전 |
| 2026-11-09 | ND-050·ND-052 반영(#801): 역할별 접근 규칙, 정한 상태 코드·오류 코드, 문구를 정하는 때 |
| 2026-11-10 | REST 4개 구현(#801): 응답 필드와 성공 상태 코드. Gateway route는 아직 없다(N4) |
| 2026-11-10 | ND-055~ND-059 반영(N3 사전 결정): 연결 만료, 티켓 수치, 프레임을 보내는 때 |
| 2026-11-10 | 연결 티켓·WebSocket 구현(#829): 티켓 응답·프레임 필드, 429·503·401, 종료 코드 4401. 자체 리뷰로 429를 공통 `TOO_MANY_REQUESTS`+`Retry-After`로 바꾸고 서버가 닫는 경우를 고쳐 적음 |
| 2026-11-10 | ND-062~ND-064·ND-067 반영(N4 사전 결정, #847): Gateway route, WebSocket 시간 제한·heart-beat, `exp` claim, WebSocket route에 JWT 인증 없음 |
| 2026-11-11 | ND-074·ND-075·ND-079 반영(N5 사전 결정, #859): 역할 규칙 범위, Backfill 재실행 API, 문구 형식, 2.2 이동 대상. 머리말·Gateway 행의 N4 병합 상태를 고쳤다 |
| 2026-11-11 | ND-089 반영(#859 구현): Backfill 재실행 API의 동기 응답 필드, 409 `BACKFILL_RUNNING`, 503 `BACKFILL_FAILED` |
| 2026-11-11 | ND-092 반영(#859 PR 리뷰): Backfill 재실행을 비동기(202·실행 ID)로 바꾸고 실행 조회 API를 더했다 |
| 2026-11-11 | ND-084 반영(N6 사전 결정, #862): 2.2 이동 대상에 N6 값(`APPEARANCE`, `LECTURE_ORDER_ITEM`)을 적었다 |
| 2026-11-12 | ND-111 반영(N7 출연 사전 결정, #908): 2.2 이동 대상의 쓰는 종류에 출연 10종을 더했다(새 `targetType` 없음) |
