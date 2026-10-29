# 2. PostgreSQL outbox·Redis Pub/Sub — 채팅 전달 pipeline

상위 문서: [최종 프로젝트 인프라 설계](../final-project-infrastructure.md). 결정 기록은 #369다.

## 역할 분리

**2026-10-29 결정:** 채팅 전달은 PostgreSQL outbox → Redis Pub/Sub으로 구성하고 Redis Streams는 구현하지 않는다. 기존 Streams 도입 결정을 이 절의 outbox relay 설계로 대체한다. 세부 설계와 FE 복구·참여 세대 계약은 [채팅 설계](../../../apps/chat-app/docs/final-design.md) 4~6장을 따른다.

- PostgreSQL: 채팅 메시지와 outbox의 source of truth. 작업 상태·선점·재시도·실패 격리를 보관한다
- DB outbox relay: 작은 배치로 작업을 선점하고 트랜잭션 밖에서 Pub/Sub으로 발행한다
- Redis Pub/Sub: 여러 app replica로 낮은 지연 fan-out
- 각 app의 내장 STOMP broker: 그 replica에 연결된 WebSocket session으로 최종 전달
- REST 이력 API·FE: offline·disconnect·Pub/Sub 유실을 복구하고, 연결이 유지된 마지막 메시지 유실도 주기적 대조·전송 결과 확인으로 발견한다

작업 적체를 Redis 메모리에 중복 보관하지 않고 기존 DB outbox에서 처리해 전달 단계를 줄인다. Pub/Sub은 수신 실패한 메시지를 재전달하지 않으므로 발행 재시도와 수신 유실 복구를 구분한다([Redis 공식 문서](https://redis.io/docs/latest/develop/pubsub/#delivery-semantics)). Redis 메모리가 두 기능 사용만으로 두 배가 되거나 Streams 제외만으로 OOM이 해결된다고 가정하지 않는다. Redis·앱 송신 버퍼와 PostgreSQL I/O·WAL·디스크·적체 해소 시간을 함께 측정한다.

## Redis 선택

**Redis를 쓴다**(팀 결정, 2026-10-28). Redis 8은 RSALv2·SSPLv1·AGPLv3 중 하나를 고르는 tri-license다([Redis licenses](https://redis.io/legal/licenses/)). 이 프로젝트는 Redis를 수정해 다른 사람에게 서비스로 제공하지 않으므로 license 제한에 걸리지 않는다.

쓰는 기능은 Pub/Sub과 필요한 짧은 수명 정보의 TTL이다. 전달 작업 큐의 consumer group·ACK·pending·claim·failed stream은 구현하지 않는다. vector 검색은 Elasticsearch가 맡으므로 Redis Query Engine은 쓰지 않는다. 앱은 `spring-boot-starter-data-redis`(Lettuce)로 접속한다. Redis 제품 선택은 유지한다. [3장 자원 예산](k3s.md#ec2-2노드-자원-예산)의 측정값은 Redis 8.2이며 Pub/Sub·소켓 부하를 포함한 운영 실측값으로 갱신한다.

## 메시지 흐름

1. STOMP 요청을 검증한다.
2. 한 PostgreSQL 트랜잭션에서 `chat_message`와 `chat_outbox`를 저장한다.
3. relay가 실행 시각이 된 작업을 짧은 DB 트랜잭션에서 선점해 `claimToken`·`leaseUntil`을 기록한다. 여러 relay의 경합은 `FOR UPDATE SKIP LOCKED` 등으로 제어한다([PostgreSQL 문서](https://www.postgresql.org/docs/18/sql-select.html#SQL-FOR-UPDATE-SHARE)).
4. DB 트랜잭션 밖에서 `PUBLISH`하고, 성공하면 자신의 처리권이 유효한 경우에만 `PUBLISHED`를 기록한다. Redis 발행 중 DB 행 잠금을 유지하지 않는다.
5. 실패는 backoff·jitter와 다음 시도 시각을 기록해 재시도한다. worker가 종료되면 lease 만료 작업을 다른 relay가 회수한다. 늦은 옛 worker의 결과는 claimToken 검사로 무시한다.
6. 최대 시도 초과는 DB에서 `DEAD`로 격리해 조회·운영자 재처리를 제공한다. 재처리 시 전달 ID와 누적 시도·운영자 이력을 보존한다.
7. 각 replica는 수신자별 현재 회원 자격·참여 세대·가시 범위를 확인하고 로컬 WebSocket으로 보낸다. 발행과 DB 결과 기록 사이 종료로 생기는 중복은 안정적인 전달 ID·메시지 ID로 흡수한다.
8. `PUBLISH` 성공·구독자 수는 브라우저 수신 완료가 아니다. 구독자 0명도 Redis 명령 실패와 구분하고 무한 재발행하지 않는다. 이력 동기화·마지막 유실 대조와 회원 상태 재검사를 유지한다.

## 만들어야 할 깊은 module

- DB outbox 저장소: 작업 등록·선점·처리권 만료 회수·완료·재시도·실패 격리를 소유한다. 실행 대상과 만료 처리권 조회에 맞는 인덱스를 둔다.
- outbox relay: DB 작업 실행과 결과 기록을 조정한다. polling·배치·동시성·lease·retry는 설정으로 관리한다.
- `RealtimeChatFanout` interface 뒤에 Redis Pub/Sub adapter를 둔다. 테스트용 in-memory adapter는 테스트 소스에 둔다.
- 메시지와 연결 제어의 outbox는 업무 변경과 각각 같은 chat DB 트랜잭션에 기록한다. 제어 발행 후 구독자가 놓친 종료 명령은 replica별 최신 회원 상태 재검사로 회복한다.

업무 code는 Redis 채널·직렬화·relay polling·처리권 만료 구현을 알지 않는다. 미발행·처리 중 작업을 보관 정책 때문에 지우지 않고 완료 기록의 아카이빙은 Soft Delete 원칙에 맞춘다.

## 완료 증거

- 동일 `clientMessageId` 재전송 시 DB 메시지와 WebSocket 결과가 중복되지 않는다.
- relay를 선점 직후 종료하면 lease 만료 작업을 다른 relay가 회수한다. 발행 직후 DB 기록 전 종료의 중복과 늦은 옛 claimToken 결과도 안전하게 처리한다.
- Redis를 중지해도 메시지는 PostgreSQL/outbox에 남고 복구 후 전달된다.
- Pub/Sub의 마지막 메시지만 유실시키고 WebSocket을 유지해도 FE 대조로 복구된다. 회원 종료 제어의 유실도 상태 재검사로 회복한다.
- 선택한 관측성 UI에서 미발행 outbox 수·최장 대기 시간·발행 지연·재시도·lease 회수·DEAD와 DB 부하·디스크·Redis 메모리를 볼 수 있다.
