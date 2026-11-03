# 계약 (c): outbox·체크포인트·수신자 집합 테이블과 Flyway 버전

테이블은 모두 chat DB의 `chats` 스키마다. 시각 컬럼은 기존 테이블과 같이 `TIMESTAMP(6) WITHOUT TIME ZONE`이다. 설계는 [설계 문서](../final-design.md) 4.4~4.6·5.3이다.

## Flyway 버전 (이슈별 분리)

| 버전 | 이슈 | 내용 |
| --- | --- | --- |
| `V2026102911xx` | #417 | 필요하면 백필 실행 기록. 범위 안에서 번호를 쓴다 |
| `V202610291200__create_chat_control_outbox.sql` | #418(공통 골격) | `chat_control_outbox`, `chat_publish_checkpoint` |
| `V202610291210`~`V2026102912xx` | #418 | `chat_outbox`, 불변 수신자 집합, `chat_room` 컬럼 추가와 기존 방의 초기 집합 |
| `V202610291230__create_chat_delivery_reprocess_audit.sql` | #418 | 운영 재처리 감사 테이블, outbox·체크포인트의 누적 횟수 컬럼 |
| `V202610291240__add_chat_delivery_reprocess_requested_by.sql` | #418 | 감사 테이블 `requested_by`(관리자 회원 ID), 관리자 격리 목록용 `DEAD` 부분 인덱스 |
| `V202610291300__add_chat_room_member_last_received_sequence.sql` | #420 | `chat_room_member.last_received_sequence` |
| `V2026102914xx` | #419 | 필요할 때만 |
| `V202610301511__add_chat_message_notification_data.sql` | #468 | `chat_message.notification_data`(jsonb, null 가능). 출연 알림 값([알림 값 계약](notification-data.md)) |
| `V202611021013__allow_repeated_chat_logout_control.sql` | #501 | `chat_control_outbox`의 중복 식별 유일 제약을 재검사(`RECHECK_USER`) 행에만 거는 부분 유일 인덱스로 바꾼다. 로그아웃(`LOGOUT_USER`)은 이벤트마다 한 행이다 |
| `V202611021600__add_chat_control_outbox_logged_out_at.sql` | #548 | `chat_control_outbox.logged_out_at`(timestamptz, null 가능). 로그아웃 작업이 닫을 연결의 기준 시각. 재검사 행과 이전 로그아웃 행은 null |
| `V202611030922__add_chat_group_room.sql` | #584 | 모집용 단체방: `chat_room`의 단체방 컬럼·검사 제약·현재 방 부분 유일 인덱스, `chat_room_log.target_user_id`, `chat_group_invite_request`(아래 "모집용 단체방") |
| `V202611031434__add_chat_group_cleanup_indexes.sql` | #616 | 조회 만료 배치가 대상을 찾는 부분 인덱스 `idx_chat_room_group_history_expiry`, 초대 결과 기록 정리가 지난 행을 고르는 `idx_chat_group_invite_request_recorded_at (recorded_at, id)`(아래 "정리 배치") |

`out-of-order: true`라 병합 순서와 무관하게 적용된다. 이미 배포한 마이그레이션은 고치지 않는다.

## 적재 상태 공통 컬럼 (`chat_outbox`, `chat_control_outbox`)

| 컬럼 | 의미 |
| --- | --- |
| `status` | `PENDING → PROCESSING → ENQUEUED`, 적재 상한 초과는 `DEAD`. `ENQUEUED`는 `XADD` 성공이다 |
| `attempt_count`, `next_attempt_at`, `last_error` | 적재 재시도와 backoff |
| `claim_token`, `lease_until` | relay 선점과 만료 회수. 처리권이 유효할 때만 결과를 기록한다 |
| `stream_entry_id`, `enqueued_at` | 마지막 적재 결과 |
| `requeue_count`, `last_requeued_at` | Redis 유실 재구성의 재적재 횟수·cooldown |
| `schema_version` | Stream 항목 스키마 버전(초기 1) |

## `chat_control_outbox` (공통 골격, #418 소유)

공통 컬럼에 더해 `id`(identity), `control_id uuid unique`, `control_type`(`RECHECK_USER`·`LOGOUT_USER`), `user_id`, `member_version`(null 가능), `reason`, `logged_out_at`(timestamptz, null 가능, 로그아웃 작업의 기준 시각, #548), `created_at`, `updated_at`. 중복 식별은 재검사 행에만 둔다: 부분 유일 인덱스 `uk_chat_control_outbox_user_version_type (user_id, member_version, control_type) nulls not distinct where control_type = 'RECHECK_USER'`(#501에서 제약을 바꿨다. 재검사 기록의 `ON CONFLICT`는 같은 조건을 적는다). 로그아웃 행(`LOGOUT_USER`, `member_version` null)은 원본 버전이 없고 같은 회원이 여러 번 로그아웃하므로 이벤트마다 넣는다. 실행 대상(`PENDING`의 `next_attempt_at`), 만료 처리권(`PROCESSING`의 `lease_until`), 재구성 대상(`ENQUEUED`의 `id`) 부분 인덱스를 둔다.

## `chat_publish_checkpoint` (공통 골격, #418 소유)

`(consumer_group, job_id)`별 Pub/Sub 발행 체크포인트. 사용자 수신 이력이 아니다.

| 컬럼 | 의미 |
| --- | --- |
| `consumer_group`, `job_id uuid` | 그룹과 `deliveryId`·`controlId`. 유일 |
| `status` | `PENDING / PUBLISHED / DEAD` |
| `generation` | 운영 재처리 세대(초기 0). 옛 세대 결과는 새 상태를 덮지 않는다 |
| `attempt_count`, `next_attempt_at`, `last_error` | 발행 재시도 |
| `published_at`, `last_subscriber_count` | 발행 성공 시각과 그때의 구독자 수(관측용) |
| `dead_reason`, `dead_stream_entry_id`, `dead_at` | 격리 원인과 원본 entry |

outbox 행과 **같은 트랜잭션**에 `PENDING`으로 만든다. 재구성 대상은 "outbox `ENQUEUED` + 체크포인트가 `PUBLISHED/DEAD`가 아님"이다.

## `chat_outbox` (#418)

메시지·시스템 안내 전달 작업(`V202610291210__create_chat_outbox.sql`). 공통 컬럼에 더해 `delivery_id uuid unique`, `delivery_type varchar(20)`(`MESSAGE`), `message_id`(FK `chat_message`), `chat_room_id`(FK `chat_room`), `room_sequence`, `recipient_set_id`(FK `chat_recipient_set`, 1220에서 추가), `notify_rule varchar(40)`(`NONE`, `RECIPIENTS_EXCEPT_SENDER`). 부분 인덱스는 `(next_attempt_at)` PENDING, `(lease_until)` PROCESSING, `(id)` ENQUEUED다. 메시지 저장과 같은 트랜잭션에 쓰고, 그룹 `chat-delivery-v1` 체크포인트도 함께 만든다.

같은 마이그레이션에서 `chat_publish_checkpoint`에 `idx_chat_publish_checkpoint_pending (consumer_group, id) WHERE status = 'PENDING'`을 추가한다(재구성 대조가 완료 행을 훑지 않도록).

형식이 잘못된 Stream 항목·원본 누락 작업의 격리(`DEAD`)도 `chat_publish_checkpoint`에 남긴다. 형식이 잘못된 항목은 `그룹:entryId` 이름 기반 UUID를 `job_id`로 쓴다. 별도 실패 테이블은 두지 않는다.

격리 사유(`dead_reason`)는 `PUBLISH_RETRY_EXHAUSTED`(발행 시도 상한), `MALFORMED_ENTRY`(형식 오류), `SOURCE_MISSING`(원본·수신자 집합 누락·불일치), `REQUEUE_EXHAUSTED`(Redis 유실 재적재 상한)다. 발행 실패 횟수 증가와 상한 도달 시 `DEAD` 전이는 한 UPDATE로 기록하고, `DEAD`가 커밋된 뒤에만 `XACK`한다.

## 운영 재처리 감사와 누적 횟수 (#418, `V202610291230`)

- `chats.chat_delivery_reprocess_audit`: 재처리마다 한 행을 INSERT만 한다. `job_stream`(`DELIVERY`/`CONTROL`), `consumer_group`, `job_id`, 재처리 전의 `previous_generation`, `previous_publish_status`, `previous_publish_attempt_count`, `previous_dead_reason`, `previous_dead_stream_entry_id`, `previous_publish_error`, `previous_outbox_status`, `previous_enqueue_attempt_count`, `previous_requeue_count`, `previous_stream_entry_id`, `previous_enqueue_error`, `reprocessed_at`. 인덱스 `(consumer_group, job_id)`.
- 재처리로 초기화되지 않는 누적 횟수: `chat_outbox`·`chat_control_outbox`의 `total_attempt_count`·`total_requeue_count`, `chat_publish_checkpoint`의 `total_attempt_count`(연결된 Redis에서의 발행 실패. 구독자 0명·연결 불가·쓰기 거절은 세지 않는다). 모두 `INTEGER NOT NULL DEFAULT 0`이다(동결된 제어 기록 구현이 이 컬럼 없이 INSERT하므로 기본값을 둔다).
- 재처리는 감사 행 기록과 상태 초기화(세대 +1)를 한 트랜잭션으로 하고, 재적재는 커밋 뒤 트랜잭션 밖에서 한다. `last_error`에는 현재 오류만 두고 이전 이력은 감사 테이블에 남긴다.
- 재처리 트랜잭션은 outbox 행 → 체크포인트 행 순서로 `FOR UPDATE` 잠근 뒤 감사 스냅숏을 남긴다. 다른 경로는 한 문장에 한 행만 바꾸므로 이 순서와 엇갈리지 않는다.
- outbox 행이 없는 격리(`MALFORMED_ENTRY`, 작업 ID에 해당하는 outbox·체크포인트가 없어 새로 만든 `SOURCE_MISSING`)는 재처리 대상이 아니다(재처리가 false를 돌려준다). 운영자 조사 전용이다. outbox 행이 있는 `SOURCE_MISSING`(메시지·수신자 집합 누락·불일치)은 재처리할 수 있지만 원본을 복구하지 않으면 다시 격리된다.

## 관리자 재처리 요청자와 격리 목록 인덱스 (#418, `V202610291240`)

- `chat_delivery_reprocess_audit.requested_by BIGINT NULL`: 관리자 API(`POST /api/v1/chats/admin/delivery-jobs/{stream}/{jobId}/reprocess`)로 재처리하면 요청한 관리자 회원 ID를 남긴다. 그 밖의 재처리는 null이다.
- 관리자 격리 목록(`GET /api/v1/chats/admin/delivery-jobs`)은 두 격리를 각자의 부분 인덱스로 키셋 조회해 합친다: `idx_chat_publish_checkpoint_dead (consumer_group, id) WHERE status = 'DEAD'`, `idx_chat_outbox_dead (id)`·`idx_chat_control_outbox_dead (id) WHERE status = 'DEAD'`.

## 인프라 장애와 시도 횟수 (#418, 2026-10-30 사용자 결정)

- Redis 연결 불가(연결 거부·연결/명령 시간 초과·연결 끊김·I/O 오류, `LOADING`·`READONLY`·`MASTERDOWN`·`BUSY`·`CLUSTERDOWN`·`NOAUTH` 응답), Redis 쓰기 거절(`noeviction`의 `OOM`, 영속화 실패의 `MISCONF` 응답, backpressure), 자기 구독 단절(발행 구독자 0명)은 인프라 장애다. 적재·발행·재적재 시도 횟수(`attempt_count`·`requeue_count`와 누적 횟수)에 넣지 않고 격리(`DEAD`)로 몰지 않는다. relay는 선점을 시도 없이 되돌리고, 재구성은 재적재 처리권을 되돌린다. 체크포인트에는 `last_error`·`last_subscriber_count`만 남긴다.
- 쓰기 거절 동안에는 적재(relay·재적재)만 멈추고 미적재 작업을 outbox에 남긴다(설계 4.5). 읽기·ACK·발행은 이어 가서 적체를 비운다. OOM에서도 PING은 성공하므로 재확인 시각마다 한 건의 XADD로 쓰기 경로를 확인하고, 성공하면 적재를 이어 간다.
- 자기 구독 단절로 멈춘 큐는 새 작업을 읽지 않고, 재확인 시각마다 미확인 작업 한 건을 시험 발행한다. 시험 발행할 작업이 없거나 시험 발행이 구독자 수 없이 끝나면(격리·중복 확인) 회수 한 바퀴 뒤 큐를 잠정적으로 다시 열어 다음 발행이 재확인이 된다.
- 재확인 간격은 장애의 첫 보고에서 `pause.probe-initial`이고, 같은 장애를 여러 호출이 보고해도 늘리지 않는다. 재확인이 실패할 때마다 두 배로 늘려 `pause.probe-max`에서 멈춘다. 잠정적으로 다시 연 큐가 다시 구독자 0명을 받으면 재확인 실패로 센다. 종료 중 인터럽트는 작업 실패도 장애도 아니다(시도로 세지 않는다).
- 재확인 권한은 장애마다 한 번에 하나다. 허락 결과의 토큰을 가진 호출만 결과를 알리고 권한을 돌려준다. 게이트가 열려 있을 때 시작해 늦게 끝난 호출은 게이트를 열거나(성공) 닫을(실패) 수는 있지만 다른 호출의 재확인 권한을 지우지 않는다. 장애가 끝나면 그 장애의 권한은 무효다.
- Redis 연결 재확인은 PING이 아니라 namespace 아래 TTL 10초의 작은 쓰기(`SET <전달 Stream 키>:probe 1 PX 10000`)로 한다. READONLY(복제본·failover)·LOADING·MASTERDOWN에서도 PING은 성공해 열고 닫기를 되풀이하기 때문이다. 이 쓰기가 OOM·MISCONF로만 거절되면 연결은 정상으로 보고 열고, 적재는 쓰기 게이트가 따로 멈춘다.
- 지표: 멈춘 횟수 `chat.delivery.paused{target}`, 지금 멈춤 상태 게이지 `chat.delivery.paused.state{target}`(1이면 멈춤). `target`은 `redis`(연결 불가), `redis-write`(쓰기 거절: OOM·MISCONF), `subscription-DELIVERY`·`subscription-CONTROL`(자기 구독 단절). 구독자 0명 발행 수는 `chat.delivery.publish.zero_subscribers{stream}`이다.
- `DEAD`는 작업 자체의 문제(형식 오류·원본 누락·수신자 집합 불일치)와 연결된 Redis에서 반복되는 실패(WRONGTYPE·ERR 등 위에 없는 명령 오류, 발행 예외)에만 쓴다.

## 연결 풀 (#418, 미확정 초기값 Q12)

chat-app의 `spring.datasource.hikari.maximum-pool-size` 기본값은 20이다. 전달 파이프라인이 동시에 쓸 수 있는 연결은 최대 11개다(큐별 주기 작업 4개 × 전달·제어 2 = 8, 새 작업 worker 전달 2 + 제어 1 = 3). 나머지 9개를 REST·STOMP 요청과 이벤트 리스너가 쓴다. worker·주기 작업 수를 늘리면 함께 늘린다. 테스트는 컨텍스트 수 때문에 5로 줄인다(`src/test/resources/hapbang-module-test.yaml`).

## 설정 위치 (#418, 사용자 결정)

- `hapbang.chat.delivery.*`의 기본값은 `ChatDeliveryProperties`의 `@DefaultValue`가 원본이다. application.yaml에 두지 않는다. `pipeline.enabled`의 코드 기본값은 `true`다(속성이 없으면 켠 것으로 본다). #418 단독 브랜치에서는 운영 `RealtimeChatFanout` 구현이 없어 `false`였고, 그 구현이 있는 #419 통합에서 `true`로 바꿨다. 테스트 설정은 `false`를 명시하고, 파이프라인을 검증하는 테스트만 `@TestPropertySource`로 켠다.
- chat-app의 Spring·라이브러리 설정(`spring.datasource.hikari.maximum-pool-size=20`, `spring.data.redis.timeout=10s`)은 chat-app `application.yaml`에 둔다(2026-10-30 사용자 결정: Spring·라이브러리 설정은 yaml, `hapbang.chat.*`는 코드 기본값). 근거는 yaml 주석에 있다. 모듈 테스트 설정(`hapbang-module-test.yaml`)은 pool 5와 같은 timeout을 따로 둔다. Redis 명령 시간 제한의 근거(consumer BLOCK 2s보다 길고 lease·min-idle 30s보다 짧게)가 채팅 전달 설정에 묶여 있어 `common/redis`가 아니라 chat-app에 둔다.
- 모든 코드 기본값은 yaml·프로필·환경 변수(예: `SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE`, `HAPBANG_CHAT_DELIVERY_RELAY_BATCH_SIZE`)로 덮을 수 있다. Redis 주소·인증·TLS와 `hapbang.chat.redis.namespace`처럼 환경마다 다른 값은 환경 변수·k8s 설정으로 준다.

| 경로 | 수신자 집합 | 알림 규칙 |
| --- | --- | --- |
| 일반 메시지 | 방의 현재 집합 | `RECIPIENTS_EXCEPT_SENDER` |
| 퇴장 안내 | 퇴장 반영 뒤 남은 참여자 집합 | `NONE` |
| 1:1 재입장 안내 | 재입장 전 남아 있던 참여자 집합 | `NONE` |
| 출연 알림(이관 전) | 알림 저장 시점의 현재 집합 | `RECIPIENTS_EXCEPT_SENDER` |
| 단체방 초대 안내 | 초대한 회원을 넣은 뒤의 집합(초대된 회원도 받는다) | `NONE` |
| 단체방 강퇴·나가기 안내 | 내보내거나 나간 뒤 남은 참여자 집합(남은 사람이 없으면 작업 없음) | `NONE` |
| 단체방 폐쇄 안내 | 폐쇄 시점의 현재 집합(참여는 그대로다) | `NONE` |

같은 `clientMessageId` 재전송은 outbox를 만들지 않는다.

## 불변 수신자 집합 (#418)

`V202610291220__create_chat_recipient_set.sql`.

- `chat_recipient_set(id, chat_room_id FK, set_version int, member_count int, created_at)`, `unique (chat_room_id, set_version)`.
- `chat_recipient_set_member(recipient_set_id FK, user_id, participation_version)`, PK `(recipient_set_id, user_id)`.
- `chat_room`에 `current_recipient_set_id`(null 가능, FK), `active_member_count int not null`을 추가한다. 종료되지 않은 기존 방은 활성 참여로 버전 1 집합을 만들고, 모든 방의 집계를 채운다.
- 퇴장으로 남은 사람이 없으면(방 종료) 빈 집합은 만들되 퇴장 안내 outbox는 만들지 않는다.
- 집합이 없는 기존 방은 `openDirectRoom`이 현재 활성 참여로 집합을 만들어 복구하고(경고 로그), 그 전의 전송은 `CHAT_ROOM_NOT_READY`(409)로 거절한다.
- 참여 변경(생성·퇴장·재입장)은 방 잠금 아래 참여 행·집계·새 집합·현재 포인터를 원자적으로 갱신한다. 기존 버전의 항목을 덮어쓰지 않는다.
- 마이그레이션에서 종료되지 않은 기존 방의 활성 참여로 초기 집합과 집계를 만든다. 집합이 없는 방은 전송 대상으로 열지 않는다.
- 방 topic 결정(2026-10-30) 뒤에는 실시간 전달이 집합의 **버전**(`set_version`)만 쓴다. 메시지가 가리킨 버전이 받는 앱의 방 참여 캐시 버전보다 크면 참여 상태를 다시 읽는 기준이다. 항목 행(`chat_recipient_set_member`)은 본문 전달에도 새 메시지 알림 대상에도 쓰지 않는다(알림 대상은 Redis 접속 정보, 설계 문서 Q21). 행은 감사·운영 재처리 근거로 지금처럼 만들고 스키마를 바꾸지 않는다.

## `chat_room_member.last_received_sequence` (#420)

`BIGINT NULL`. 기존 행은 `NULL`(미확인)로 둔다. 재입장 때 같은 행에서 참여 세대 변경과 함께 `NULL`로 초기화한다.

## 모집용 단체방 (#584, `V202611030922`)

설계는 [설계 문서](../final-design.md) 3장이다. 1:1 방과 같은 `chat_room`·참여·메시지·불변 수신자 집합·누적 수신 컬럼을 쓰고, 단체방 전용 값만 더했다. 다른 앱 테이블을 참조하지 않는다(모집글·호스트는 ID만).

| 대상 | 컬럼·제약 | 의미 |
| --- | --- | --- |
| `chat_room` | `recruit_id`, `owner_user_id`(BIGINT) | 모집글 ID와 호스트 회원 ID. 호스트는 생성 때 출연 앱에서 확인한 값이다 |
| `chat_room` | `title`(VARCHAR 255) | 모집글 제목. 생성 때 복사하고 초대 후보 검색 때 모집글 제목으로 맞춘다(제목 컬럼만 조건부 UPDATE) |
| `chat_room` | `max_guest_members`(INTEGER) | 게스트 정원(호스트 제외). 생성 때 `hapbang.chat.group.max-guest-members`(코드 기본값 500, Q12 실측 전)를 저장한다 |
| `chat_room` | `status_reason`(VARCHAR 40) | 읽기 전용 사유 `CLOSED_BY_HOST`·`HOST_UNAVAILABLE`. 그 밖에는 null |
| `chat_room` | `read_only_at`, `history_expires_at` | 읽기 전용 전환 시각과 참여자 조회 만료 시각(전환 + 30×24시간). 만료는 조회 때 `now >= history_expires_at`로 정한다(그 시각 포함). 조회 만료 배치가 뒤에 상태를 `EXPIRED`로 바꿔 저장한다(아래 "정리 배치"). 노출 차단은 배치와 관계없이 이 시각으로 한다 |
| `chat_room` | `status_version`(INTEGER NOT NULL DEFAULT 0) | 상태가 바뀔 때마다 1 늘린다(지금은 폐쇄와 조회 만료). 늦은 상태 변경을 걸러 낼 기준이다. 1:1 방과 기존 행은 0이다 |
| `chat_room` | `ck_chat_room_group_columns` | `room_type = 'GROUP'`이면 모집글·호스트·제목·정원이 있어야 한다. 1:1 방은 모두 null이다 |
| `chat_room` | `uk_chat_room_group_current (recruit_id) where room_type = 'GROUP' and status in ('ACTIVE', 'SUSPENDED')` | 모집글마다 현재 방 하나. 읽기 전용으로 남은 이전 방과 새 방은 공존한다. 동시 생성은 이 인덱스에 걸린 쪽이 다시 시도해 먼저 만든 방을 돌려준다 |
| `chat_room_log` | `target_user_id`(BIGINT, null 가능) | 행위 대상. 초대(`MEMBER_INVITED`)·강퇴(`MEMBER_KICKED`)는 행위자가 호스트, 대상이 회원이다. 그 밖에는 null |
| `chat_room_log` | `event_type = 'HISTORY_EXPIRED'` | 조회 만료(#616). 배치가 방마다 한 번 남긴다. 시스템 전환이지만 `actor_user_id`가 NOT NULL이라 방의 호스트(`owner_user_id`)를 남기고, `occurred_at`은 배치 실행 시각이 아니라 `history_expires_at`이다 |
| `chat_group_invite_request` | `id`, `chat_room_id`(FK), `request_id`(UUID), `requester_user_id`, `results`(JSONB), `recorded_at`, `unique (chat_room_id, request_id)` | 초대 요청의 회원별 결과(Q34). 보존 기간(`hapbang.chat.group.invite-request-retention`, 코드 기본값 24시간, Q12 실측 전) 안의 같은 요청 ID는 이 결과를 그대로 돌려준다. 지난 요청 ID는 새 요청으로 처리하고 이 행을 새 결과로 바꾼다. 방을 잠근 초대 트랜잭션에서 참여 행·안내·전달 작업과 함께 쓴다(이 행도 잠가 읽는다). 보존 기간이 지난 행은 정리 배치가 물리 삭제한다(아래 "정리 배치") |

- 상태: 1:1 방은 `ACTIVE`·`ENDED`, 단체방은 `ACTIVE`·`SUSPENDED`·`READ_ONLY`·`EXPIRED`를 저장한다. `EXPIRED`는 조회 만료 배치가 저장하고, 배치 전의 만료된 `READ_ONLY` 방도 응답(관리자 기록)에서는 `EXPIRED`다. 만료 뒤에도 `status_reason`·`read_only_at`·`history_expires_at`은 그대로 둔다. `EXPIRED`는 현재 방이 아니라 `uk_chat_room_group_current`에 들지 않는다. 단체방은 모두 나가도 `ENDED`가 되지 않는다.
- 단체방 안내는 `chat_message`의 `message_type = 'SYSTEM'`, `content = ''`, `notification_kind`(종류), `notification_data`(값)에 남긴다. `reference_id`는 null이라 출연 알림의 `uk_chat_message_notification`에 걸리지 않는다([알림 값 계약](notification-data.md)).
- 읽기 전용 방에는 순번을 발급하지 않는다. 폐쇄 안내가 마지막 메시지이고, 읽기 전용 방에서 나가면 기록·수신자 집합만 남긴다.
- `SUSPENDED`·`HOST_UNAVAILABLE`로 바꾸는 기능은 아직 없다(상태 값과 컬럼만 둔다).

### 정리 배치 (#616)

`hapbang.scheduling.enabled`일 때만 돈다(`ChatGroupRoomBatch`). 주기·묶음 크기는 미확정 코드 기본값이다.

| 배치 | 대상·처리 | 기본값 |
| --- | --- | --- |
| 조회 만료 상태 기록 (`ChatGroupHistoryExpiryService`) | `room_type = 'GROUP' and status = 'READ_ONLY' and history_expires_at <= now`인 방을 방 ID 키셋으로 한 묶음씩 읽고(잠그지 않음), 방마다 짧은 트랜잭션에서 방을 잠근 뒤 같은 조건을 다시 확인해 `EXPIRED`로 바꾸고 `status_version`을 1 늘리며 `HISTORY_EXPIRED` 기록을 남긴다. 메시지·참여 행·기록은 지우지 않고 안내 메시지·전달 작업도 만들지 않는다(참여자에게 이미 보이지 않는 방이다). 한 방의 실패는 그 방만 롤백하고 다음 실행이 다시 한다. 다시 실행하거나 여러 실행이 겹쳐도 방마다 한 번만 바뀐다 | 주기 `hapbang.chat.group.history-expiry-interval` 10m, 묶음 `history-expiry-batch-size` 100 |
| 초대 결과 기록 정리 (`ChatGroupInviteRequestCleaner`) | `recorded_at <= now − invite-request-retention`인 `chat_group_invite_request` 행을 `delete ... where id = any(array(select id ... order by recorded_at, id limit n for update skip locked))`로 묶음마다 한 트랜잭션에서 물리 삭제한다. 기준은 초대의 보존 판정(`isRetained`: `now < recorded_at + retention`)과 같아 보존 기간 안의 행은 지우지 않는다. 초대 트랜잭션은 이 행을 잠가 읽으므로, 지난 행을 새 결과로 바꾸는 중이면 정리가 건너뛰고, 정리가 먼저 잠갔으면 초대는 빈 값을 받아 새 행을 만든다 | 주기 `invite-request-cleanup-interval` 1h, 묶음 `invite-request-cleanup-batch-size` 500 |

- 조회 만료 배치의 대상 조회는 부분 인덱스 `idx_chat_room_group_history_expiry (history_expires_at) where room_type = 'GROUP' and status = 'READ_ONLY'`를 쓴다. 만료로 바뀐 방은 인덱스에서 빠지므로 크기는 조회 기간 안의 읽기 전용 방 수에 묶인다. 초대 결과 기록 정리는 `idx_chat_group_invite_request_recorded_at (recorded_at, id)` 순서로 지난 행만 골라, 보존 중인 행을 묶음마다 다시 훑지 않는다. 바깥 삭제는 고른 ID 배열로 기본 키를 찾는다.
- 초대 결과 기록을 물리 삭제하는 이유(사용자 결정 2026-11-03): 재시도 판정용 기술 데이터라 업무 의미가 없다. 초대된 회원은 `chat_room_log`(`MEMBER_INVITED`)와 초대 안내 메시지에 계속 남는다. [정책](../../../../docs/policy.md) 7장의 Soft Delete 규칙(Hard Delete 금지)은 업무 데이터에 대한 것이다.
