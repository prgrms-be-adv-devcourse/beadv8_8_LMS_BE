# 계약 (c): outbox·체크포인트·수신자 집합 테이블과 Flyway 버전

테이블은 모두 chat DB의 `chats` 스키마다. 시각 컬럼은 기존 테이블과 같이 `TIMESTAMP(6) WITHOUT TIME ZONE`이다. 설계는 [설계 문서](../final-design.md) 4.4~4.6·5.3이다.

## Flyway 버전 (이슈별 분리)

| 버전 | 이슈 | 내용 |
| --- | --- | --- |
| `V2026102911xx` | #417 | 필요하면 백필 실행 기록. 범위 안에서 번호를 쓴다 |
| `V202610291200__create_chat_control_outbox.sql` | #418(공통 골격) | `chat_control_outbox`, `chat_publish_checkpoint` |
| `V202610291210`~`V2026102912xx` | #418 | `chat_outbox`, 불변 수신자 집합, `chat_room` 컬럼 추가와 기존 방의 초기 집합 |
| `V202610291300__add_chat_room_member_last_received_sequence.sql` | #420 | `chat_room_member.last_received_sequence` |
| `V2026102914xx` | #419 | 필요할 때만 |
| `V202610301511__add_chat_message_notification_data.sql` | #468 | `chat_message.notification_data`(jsonb, null 가능). 출연 알림 값([알림 값 계약](notification-data.md)) |

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

공통 컬럼에 더해 `id`(identity), `control_id uuid unique`, `control_type`(`RECHECK_USER`), `user_id`, `member_version`(null 가능), `reason`, `created_at`, `updated_at`. 중복 식별은 `unique nulls not distinct (user_id, member_version, control_type)`. 실행 대상(`PENDING`의 `next_attempt_at`), 만료 처리권(`PROCESSING`의 `lease_until`), 재구성 대상(`ENQUEUED`의 `id`) 부분 인덱스를 둔다.

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

| 경로 | 수신자 집합 | 알림 규칙 |
| --- | --- | --- |
| 일반 메시지 | 방의 현재 집합 | `RECIPIENTS_EXCEPT_SENDER` |
| 퇴장 안내 | 퇴장 반영 뒤 남은 참여자 집합 | `NONE` |
| 1:1 재입장 안내 | 재입장 전 남아 있던 참여자 집합 | `NONE` |
| 출연 알림(이관 전) | 알림 저장 시점의 현재 집합 | `RECIPIENTS_EXCEPT_SENDER` |

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

## `chat_room_member.last_received_sequence` (#420)

`BIGINT NULL`. 기존 행은 `NULL`(미확인)로 둔다. 재입장 때 같은 행에서 참여 세대 변경과 함께 `NULL`로 초기화한다.
