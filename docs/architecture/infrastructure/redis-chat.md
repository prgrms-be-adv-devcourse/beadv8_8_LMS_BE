# 2. Redis Streams — 채팅 전달 pipeline

상위 문서: [최종 프로젝트 인프라 설계](../final-project-infrastructure.md). 결정 기록은 #369다.

## 역할 분리

Redis Streams를 채팅 이력 DB나 STOMP broker처럼 사용하지 않는다.

- PostgreSQL: 채팅 메시지와 outbox의 source of truth
- Redis Streams: 저장된 메시지의 비동기 전달 작업 queue
- Redis Pub/Sub: 여러 app replica로 낮은 지연 fan-out
- 각 app의 내장 STOMP broker: 그 replica에 연결된 WebSocket session으로 최종 전달
- REST 이력 API: offline·disconnect·Pub/Sub 유실 시 최종 복구

Redis Streams는 append-only log, consumer group, 명시적 ACK, pending/claim을 제공한다([공식 문서](https://redis.io/docs/latest/develop/data-types/streams/)). Pub/Sub은 fire-and-forget이지만 여기서는 PostgreSQL 이력으로 다시 읽을 수 있으므로 실시간 fan-out에 사용할 수 있다.

## Redis 선택

**Redis를 쓴다**(팀 결정, 2026-10-28). Redis 8은 RSALv2·SSPLv1·AGPLv3 중 하나를 고르는 tri-license다([Redis licenses](https://redis.io/legal/licenses/)). 이 프로젝트는 Redis를 수정해 다른 사람에게 서비스로 제공하지 않으므로 license 제한에 걸리지 않는다.

쓰는 기능은 Streams, consumer group, `XAUTOCLAIM`, Pub/Sub, TTL이다. vector 검색은 Elasticsearch가 맡으므로 Redis Query Engine은 쓰지 않는다. 앱은 `spring-boot-starter-data-redis`(Lettuce)로 접속한다. Valkey(Redis 7.2에서 갈라진 BSD fork)도 같은 명령과 RESP protocol을 지원하므로, 나중에 바꾸더라도 앱 코드는 그대로 두고 접속 주소만 바꾸면 된다([Valkey history](https://valkey.io/topics/history/)). [3장 자원 예산](k3s.md#ec2-2노드-자원-예산)의 측정값은 Redis 8.2다.

## 메시지 흐름

1. STOMP 요청을 검증한다.
2. 한 PostgreSQL 트랜잭션에서 `chat_message`와 `chat_outbox`를 저장한다.
3. publisher가 outbox를 Redis Stream에 `XADD`한다.
4. `chat-delivery` consumer group이 `XREADGROUP`으로 받는다.
5. 수신자별 channel에 Pub/Sub으로 발행한다.
6. app replica들이 구독하고, 로컬에 해당 사용자의 session이 있을 때 STOMP로 보낸다.
7. Pub/Sub 발행까지 성공하면 `XACK`; 실패하면 pending에 남긴다.
8. idle pending은 `XAUTOCLAIM`으로 다른 worker가 회수하고, 최대 횟수 초과는 별도 failed stream으로 옮긴다.
9. 재연결 client는 마지막 `roomSequence` 이후 이력을 PostgreSQL에서 조회한다.

## 만들어야 할 깊은 module

- `ChatDeliveryQueue` interface: `enqueue`, `consume`, `ack`, `fail`의 작은 interface로 Redis 명령을 숨긴다.
- Redis Streams adapter와 in-memory adapter를 둔다.
- `RealtimeChatFanout` interface 뒤에 Redis Pub/Sub adapter와 단일-node adapter를 둔다.

업무 code는 stream key, consumer name, claim timeout, serialization을 알지 않는다.

## 완료 증거

- 동일 `clientMessageId` 재전송 시 DB 메시지와 WebSocket 결과가 중복되지 않는다.
- consumer를 메시지 처리 중 종료하면 pending message를 다른 consumer가 회수한다.
- Redis를 중지해도 메시지는 PostgreSQL/outbox에 남고 복구 후 전달된다.
- Pub/Sub 전달을 일부 유실시켜도 재접속 history sync로 동일한 최종 상태가 된다.
- 선택한 관측성 UI에서 stream length, pending count/age, claim, failed stream, 전달 latency를 볼 수 있다.
