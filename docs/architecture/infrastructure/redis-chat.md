# 2. PostgreSQL outbox·Redis Streams·Pub/Sub — 채팅 전달 pipeline

상위 문서: [최종 프로젝트 인프라 설계](../final-project-infrastructure.md). 결정 기록은 #369다.

## 역할 분리

**2026-10-29 사용자 변경 결정:** 같은 날의 Streams 미도입 결정을 대체하고 **PostgreSQL outbox → Redis Streams → consumer group → Redis Pub/Sub → 로컬 STOMP**를 도입한다. 채팅 앱은 replica 1개이고 Streams worker도 같은 앱의 제한된 실행기로 시작한다. 이는 목표 설계이며 코드·배포 적용 완료가 아니다. 구체 계약의 원본은 [채팅 설계](../../../apps/chat-app/docs/final-design.md) 4.2·4.5다.

- PostgreSQL: 메시지·참여·읽음과 전달 작업의 원본. 업무와 outbox를 원자 저장하고 Stream 적재 상태·그룹별 Pub/Sub 발행 체크포인트·실패를 보존한다.
- DB outbox relay: 짧게 선점한 뒤 DB 트랜잭션 밖에서 XADD하고 결과를 기록한다. DB 커밋과 Redis 적재의 공백을 회복한다.
- Redis Streams: 전달 작업의 참조를 보관하고 그룹의 소비 진행·PEL·ACK·회수를 지원한다. 채팅 원본 DB나 사용자 수신 확인을 대체하지 않는다.
- Stream consumer: 참조를 읽어 수신 대상을 펼치고 Pub/Sub에 발행한다. DB 발행 체크포인트를 기록한 뒤 ACK한다.
- Redis Pub/Sub과 내장 STOMP broker: 단일 chat-app 리스너가 현재 수신 자격을 확인하고 로컬 WebSocket에 보낸다. 복수 replica는 향후 확장 후보이며 이번 검증 전제가 아니다.
- REST 이력 API·FE: 오프라인·연결 단절·Pub/Sub 유실을 복구한다. 마지막 메시지 유실도 주기적 대조로 발견한다.

Streams는 후속 작업을 독립 소비하기 위한 선택이다. 기존 DB polling·방 잠금·클라이언트 누락을 자동 해결하지 않는다. Redis 메모리/PEL/AOF·앱 송신 버퍼와 DB 체크포인트 I/O·WAL·디스크를 함께 측정한다. [Pub/Sub은 수신 실패를 재전달하지 않는다](https://redis.io/docs/latest/develop/pubsub/#delivery-semantics).

## Redis 선택

**Redis를 쓴다**(팀 결정, 2026-10-28). Redis 8은 RSALv2·SSPLv1·AGPLv3 중 하나를 고르는 tri-license다([Redis licenses](https://redis.io/legal/licenses/)). 이 프로젝트는 Redis를 수정해 다른 사람에게 서비스로 제공하지 않으므로 license 제한에 걸리지 않는다.

쓰는 기능은 Streams의 consumer group·ACK·pending 회수, Pub/Sub, 필요한 짧은 수명 정보의 TTL이다. Stream 키에는 TTL을 걸지 않으며 실패 격리는 DB에 남긴다. 별도 failed Stream은 추가하지 않는다. vector 검색은 Elasticsearch가 맡으므로 Redis Query Engine은 쓰지 않는다. 앱은 `spring-boot-starter-data-redis`(Lettuce)로 접속한다. Redis 제품 선택은 유지한다. [3장 자원 예산](k3s.md#ec2-2노드-자원-예산)의 측정값은 Redis 8.2이며 Streams 적체·PEL·AOF·Pub/Sub·소켓 부하를 포함한 운영 실측값으로 갱신한다.

## 메시지 흐름과 완료 경계

1. 요청 검증 뒤 메시지·불변 수신자 집합 참조·outbox·초기 발행 체크포인트를 같은 chat DB 트랜잭션에 저장한다.
2. relay가 작은 배치를 선점해 claimToken·lease를 기록하고 커밋한 뒤 XADD한다. 성공 응답을 확인한 경우에만 유효한 처리권으로 적재 상태를 ENQUEUED로 바꾼다. 적재 실패·종료는 DB 재시도·lease 회수로 복구한다.
3. 메시지 전달은 `chat:delivery:stream:v1`/`chat-delivery-v1`, 연결 제어는 `chat:control:stream:v1`/`chat-control-v1`로 분리한다. 실제 키에는 환경 namespace를 붙인다. 그룹은 최초에 `0-0`으로 생성하고 기존 그룹의 진행 위치를 보존한다.
4. consumer가 XREADGROUP으로 참조를 받고 DB의 불변 수신자 집합과 본문으로 payload를 만든다. Pub/Sub 발행 성공 → DB에 그룹별 PUBLISHED 체크포인트 커밋 → XACK 순서다. PUBLISHED·DEAD를 확인한 중복 entry는 해당 처리 세대의 계약에 따라 ACK한다.
5. 읽은 뒤 종료한 작업과 발행 실패는 PEL 회수 루프에서 처리한다. XAUTOCLAIM·backoff·횟수 상한을 두고, 상한 초과/해석 불가능한 작업은 DB 실패 기록의 커밋을 확인한 뒤 ACK한다. XACK는 브라우저 수신 완료도 Stream 본문 삭제도 아니다.
6. XADD 응답 유실, PUBLISH 성공 뒤 DB 기록 전 종료, 늦은 옛 worker에서 중복이 가능하다. 안정적인 deliveryId/controlId·처리 세대·조건부 상태 갱신과 FE의 messageId 중복 제거·roomSequence 복구로 처리한다. 같은 방의 완료 순서를 worker 수로 보장하지 않는다.
7. Pub/Sub 리스너는 현재 회원 자격·활성 참여·참여 세대·가시 범위를 확인하고 로컬 STOMP로 보낸다. 구독자 0명을 명령 실패로 무한 재시도하지 않으며, 브라우저 유실은 이력 복구로 처리한다. 연결 제어 유실은 최신 회원 상태 재검사로 회복한다.

## 내구성·정리·과부하

- ENQUEUED 뒤 Redis 데이터가 없어지는 상황도 고려한다. outbox와 DB의 미완료 발행 체크포인트를 제한된 주기로 대조해 같은 안정 ID로 재적재한다. 선점·cooldown·시도 상한을 두고 정상 Stream backlog·PEL과 겹치는 재적재를 제한한다. PUBLISHED/DEAD는 자동 재적재하지 않는다. 상세 실패 경계는 [채팅 설계 4.5](../../../apps/chat-app/docs/final-design.md#45-outbox--streams--pubsub의-실패-경계)를 따른다.
- AOF·fsync·영속 볼륨·noeviction·메모리 예산을 배포 담당과 확정하고 강제 종료/재시작을 시험한다. 단일 Redis는 HA가 아니다. 이 문서 변경으로 배포 설정을 적용하지 않는다.
- XACK 뒤에도 Stream 본문은 남는다. 모든 필수 그룹의 미전달·pending 경계를 보호하고 보존 기간을 함께 적용해 정리한다. 길이 상한만으로 미처리 데이터를 지우거나 Stream에 TTL을 걸지 않는다. 작업 키 eviction·OOM을 관측하고 메모리 임계점에서 적재와 입력을 제한한다.
- DB 원본·복구 메타데이터·실패 격리 기록은 Soft Delete 규칙을 유지한다. 재구성 가능한 Redis 전달 버퍼 정리를 DB hard delete 허용으로 해석하지 않는다.
- Q12의 polling·claim idle·retry·정리·재구성 주기·메모리·복구 SLO가 확정되고 부하 시험을 통과해야 출시 준비 완료다.

## 구현 경계와 알림 모듈

- `app`에 역할 중심 `ChatDeliveryQueue`(적재·소비·회수·ACK), `RealtimeChatFanout`(Pub/Sub) 계약을 둔다. 기술 구현은 `out/redis`, 소비 진입은 `in`, 조정은 `app`에 두고 Facade는 인터페이스만 사용한다.
- 알림 모듈의 integration event 입력은 Kafka를 유지한다([8장](notification.md)). 채팅의 내부 Stream 참조를 다른 모듈이 받아 chat DB를 조회하게 하지 않는다.
- 향후 같은 공개 이벤트를 독립 기능들이 소비한다면 그룹을 분리한다. 같은 그룹 worker는 작업을 분담하고, 별도 그룹은 이벤트마다 각자 처리·ACK한다. 새 그룹에는 계약·초기 cursor·보존 범위·DB 재구성 책임·자원 예산을 먼저 정의한다. 이번 도입으로 알림 그룹을 선제 생성하지 않는다.
- Kafka와 Redis 두 목적지로 발행할 업무가 생기면 목적지별 outbox 작업/결과를 관리한다. 한쪽 성공으로 다른 쪽을 완료 처리하지 않는다.

## 완료 증거

- 실제 PostgreSQL·Redis Testcontainers로 업무/outbox 원자성, XADD 전후 종료·응답 유실·DB lease 회수를 검증한다.
- XREADGROUP 이후 종료·PEL 회수, PUBLISH/DB 체크포인트/XACK 사이 종료와 중복, 실패 격리·재처리 세대를 검증한다.
- 같은 그룹 분담·별도 그룹 독립 ACK는 최소 계약 테스트로 확인하며, chat-app E2E는 replica 1개로 일반·시스템·연결 제어를 검증한다.
- ENQUEUED 뒤 Stream/그룹 유실·재구성, 미처리 보존 정리, noeviction/OOM·AOF 복구·적체 해소를 시험한다. DB 원본 유실까지 복구한다고 주장하지 않는다.
- Pub/Sub의 마지막 프레임 유실은 FE 대조, 제어 유실은 회원 상태 재검사로 회복한다.
- outbox 대기, 그룹 lag·PEL/idle·ACK 지연·claim, DB 미완료/격리/재적재, Redis 메모리/eviction/AOF·DB I/O/WAL·송신 버퍼를 관측한다.
