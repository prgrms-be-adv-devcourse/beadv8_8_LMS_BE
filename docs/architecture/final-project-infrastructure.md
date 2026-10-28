# 최종 프로젝트 포트폴리오 인프라 설계

작성일: 2026-10-22
최종 갱신: 2026-10-28

전제: 팀의 **AWS EC2 2대(t3.large·t3.medium)와 S3 버킷 1개**에 실제로 배포해 시연하는 포트폴리오 프로젝트다. 로컬 k3d는 같은 Kubernetes manifest(Kustomize·Helm)를 개발 PC와 CI에서 검증하는 보조 환경이다. 이 문서의 결정 기록은 #369다. AWS node 역할과 배치의 기준 문서는 [AWS 2노드 k3s 배치](../deploy/aws-k3s.md)다.

이 문서는 최종 목표 구조의 기준 문서다. Elasticsearch 통합의 근거와 대안 비교는 [Elasticsearch 통합 범위 조사](./elasticsearch-consolidation-research.md)에 남기고, 충돌할 때는 이 문서의 결론을 따른다. 별도의 Draw.io 파일은 현재 관리하지 않으며 아래 텍스트 구조와 각 절의 완료 증거를 아키텍처 기준으로 사용한다.

### 배포 환경 제약

2026-10-22에 `aws-usage.md`와 AWS 읽기 전용 조회로 확인했다. 계정 ID, 버킷 이름, 인스턴스 ID, IP는 이 문서에 적지 않는다.

| 항목 | 현재 상태 | 설계에 주는 제약 |
| --- | --- | --- |
| EC2 | t3.large 1대 + t3.medium 1대, 각각 Ubuntu 24.04·gp3 50 GB·EIP 1개 | large는 `core-data`, medium은 `app-observe`로 사용한다. 상세 배치는 전용 문서를 따른다 |
| CPU credit | **standard** 모드 | 기준선 이상 부하가 이어져 credit을 다 쓰면 CPU가 기준선으로 제한된다. 부하 test 결과에 credit 잔량을 함께 기록한다 |
| 운영 시간 | 09:00~18:00 자동 시작·중지 | 매일 instance가 중지된다. 그 시간에 잡힌 `@Scheduled` 작업은 실행되지 않고, 종료 처리가 시연이 아니라 실제 요구다 |
| 환경 | 현재 Compose는 prod·dev가 large에 함께 있다. 앱별 구조 전환(#363) 뒤 자동 배포는 멈춰 있다 | k3s에는 **prod(main)만** 둔다. dev 서버는 없애고 개발 확인은 로컬과 PR k3d에서 한다. 현재와 목표 상태를 섞지 않는다 |
| network | 프로젝트 전용 VPC 1개, public subnet 1개, Internet Gateway. private subnet·NAT 없음 | 두 node는 같은 subnet에서 private IP로 cluster 통신한다 |
| security group | 22·80·443 inbound 전체 허용 | k3s API(6443), Grafana, Elasticsearch, Kafka, Redis port는 열지 않는다 |
| 진입점 | edge nginx + Let's Encrypt(certbot). ELB·ACM 없음 | 외부 TLS 종료, 큰 본문 업로드, 내부 API 차단이 edge nginx에 있다. k3s에서는 이 책임을 Envoy Gateway와 cert-manager가 넘겨받고 nginx는 쓰지 않는다 |
| 이미지 | GHCR private. ECR 없음 | cluster는 GHCR pull secret이 필요하다 |
| S3 | 버킷 1개, 사진·영상 업로드 전용(`dev/*`·`prod/*` prefix). 인스턴스 역할로 접근 | metrics·logs·traces·vector 저장 같은 다른 용도로 쓰려면 IAM 정책과 팀 합의를 바꿔야 한다 |
| instance metadata | IMDSv2 required, hop limit 2 | container·pod 안에서도 인스턴스 역할 자격 증명을 받을 수 있다 |

AGENTS.md 8장에 따라 EC2 2대, VPC 1개, S3 버킷 1개 범위에서만 운영한다. EKS, RDS, ElastiCache, MSK, ELB, Amazon Managed Prometheus·Grafana 같은 managed service로 바꾸는 것은 이 설계의 범위가 아니다.

## 결론

포트폴리오 목적이라면 다음 구성을 채택한다.

| 기술 | 선택 | 보여 줄 역량 |
| --- | --- | --- |
| Kafka | 도입 | 모듈 간 비동기 이벤트, outbox, 멱등 소비, retry/DLT, replay |
| Redis Streams | 채팅 모듈에 도입 | consumer group, ACK·pending·claim, 실시간 전달 실패 복구 |
| Redis Pub/Sub·TTL | 채팅·알림 보조로 도입 | 다중 앱 replica fan-out, 접속 상태·짧은 수명 데이터 |
| 앱 구조 | 앱 1개 = bounded context 1개 = database 1개. #363의 앱 6개에 `notification-app`·`search-app`을 더해 8개 | 서비스별 빌드·배포·장애 격리 |
| 알림(notification) | 채팅에서 분리해 `notification-app`으로 (8장) | 이벤트 기반 독립 context, 멱등 소비, 실시간 전달과 복구 |
| Elasticsearch + `search-app` | **도입 확정** (9장, #234) | 이벤트 기반 색인, nori 한국어 검색, alias 재색인, 고객센터 hybrid RAG |
| k3s(server 1 + agent 1) + Kustomize + Helm | 도입. 로컬·CI는 같은 manifest를 k3d로 검증. 잘 관리되는 공식·준공식 차트만 Helm으로 | 실제 서버의 Kubernetes 배포, rolling update, probe, node별 workload 분리와 자원 예산 관리 |
| 모듈별 PostgreSQL database | 도입. instance는 1개, database만 앱별로 (10장) | database-per-bounded-context, 앱별 계정·migration·outbox |
| API Gateway (Envoy Gateway) | 도입. edge nginx와 k3s 내장 Traefik은 쓰지 않음 | TLS 종료, 모듈별 routing, JWT 서명 검증, 내부 API 차단, trace 시작점 |
| JWT 인증 | 세션을 대체. member-app이 발급(RS256·JWKS), Envoy가 검증, 앱이 `@PreAuthorize`로 인가 | stateless 인증, Gateway 인증 위임과 헤더 위조 방지 |
| Resilience4j | 각 앱의 rate limit과 앱 간 HTTP 호출의 timeout·circuit breaker | 남용 경로 보호, 장애 전파 차단 |
| Graceful Shutdown | 모든 workload에 도입 | 신규 작업 차단, in-flight drain, 종료 순서와 제한 시간 |
| Grafana observability stack | 도입 | Prometheus metrics, Alloy 수집, Loki logs, Tempo traces, Grafana dashboard·alert |
| Mimir·Elastic Observability | 제외 | 단일 Prometheus와 기능이 겹치고 2노드 메모리·디스크를 추가로 사용 |
| k6 | 도입 | 기술 도입 전후 성능을 수치로 설명 |
| SonarQube Cloud | 도입 | PR quality gate, 취약점·code smell·중복·coverage 추적 |
| Gradle `java-test-fixtures` | 모듈별 도입 | fixture 공개 범위와 test dependency를 variant로 명시 |
| Spring AI + Elasticsearch | 고객센터 hybrid RAG에 도입 | nori BM25 + dense vector + RRF, 출처가 있는 답변, 검색 실패 시 답변 거절 |
| JFR + JDK Mission Control | 도입 | 부하 구간의 JVM recording과 재현 가능한 사후 분석 |
| Terraform | 현행 AWS 구성만 코드화 | 이미 배포된 EC2·network·S3·IAM 구성을 import해 재현 |

핵심은 기술을 나열하는 것이 아니라 다음 다섯 가지 이야기를 끝까지 완성하는 것이다.

1. PostgreSQL 트랜잭션에서 발생한 도메인 이벤트가 Kafka까지 유실 없이 전달된다.
2. 채팅 메시지는 PostgreSQL에 보존되고, Redis 장애·중복 소비가 있어도 다시 전달하거나 이력으로 복구된다.
3. 모듈 앱을 EC2 2노드 k3s에 나눠 띄우고, 장애를 낸 뒤 지표·로그·trace와 재처리로 복구한다.
4. 고객센터 AI는 공개 문서에서 찾은 근거만 답하고, 근거가 없으면 답변을 거절하며 실제 검색 문서를 출처로 반환한다.
5. rolling update가 시작되면 기존 pod는 새 일을 받지 않고 진행 중인 요청과 메시지 처리를 끝낸 뒤 종료하고, 남은 메시지는 새 pod가 이어서 처리한다.

## 현재 레포에서 출발할 수 있는 기반

- #363으로 업무 모듈이 독립 Spring Boot 앱 6개(`apps/*-app`)와 공통 라이브러리(`common/*`)로 나뉘었다. 컨텍스트 간 연결이 ID와 Event로 제한되어 있어 Kafka seam을 만들기 좋다. 다만 앱 사이 통신(#280, #339)과 인증 전환(#370)이 끝나기 전에는 일부 앱이 단독으로 기동하지 못한다(`docs/architecture/app-modules.md`).
- Spring Modulith JDBC event publication registry, 미완료 이벤트 재발행, 완료 archive가 이미 설정되어 있다. Spring Modulith는 업무 트랜잭션 안에 publication을 기록하고 broker externalization 실패도 재제출할 수 있다([공식 문서](https://docs.spring.io/spring-modulith/reference/events.html#fundamentals-of-event-externalization)).
- 채팅은 메시지를 PostgreSQL에 먼저 저장하고 커밋 뒤 내장 STOMP broker로 보낸다. `clientMessageId`와 방별 sequence도 있어 중복·순서 테스트의 기반이 있다.
- EC2의 Docker Compose 배포, healthcheck, CI, 이미지 롤백이 있어 k3s 전환 전후를 비교할 기준선이 있다.

## 목표 구조

```text
Client
  ├─ HTTP (React·API, Authorization: Bearer) ─┐
  └─ STOMP/WebSocket ─────────────────────────┤
                                              ▼  :443 (large EIP)
   ── 이하 EC2 t3.large(server/core-data) + t3.medium(agent/app-observe) k3s cluster ──
                         │
               ServiceLB (large에서만 80·443)
                         │
         Envoy Gateway (Gateway API HTTPRoute)
           TLS(cert-manager) · JWT 서명 검증 → X-User-* 헤더
           모듈별 route · 내부 API 차단 · traceparent
                         │
                         ▼
          모듈 앱 8개 (앱마다 replica 1개) + React 정적 서버
          member · billing · appearance · lecture · chat
          customer-support · notification · search
            │  X-User-* 헤더로 @PreAuthorize, Resilience4j rate limit
            │
            ├──── PostgreSQL  ← 업무·HP 원장·채팅 이력의 source of truth
            │
            ├─ domain event → Modulith publication/outbox → Kafka
            │                                            ├─ 모듈 projection 소비자
            │                                            ├─ notification 모듈·감사 소비자
            │                                            └─ search 모듈 → Elasticsearch index
            │
            └─ chat outbox → Redis Streams → chat delivery worker
                                               │
                                               └─ Redis Pub/Sub → 각 app의 로컬 WebSocket session

PostgreSQL instance 1개 (prod만, 10장)
  ├─ member DB ─────── 회원, refresh token
  ├─ appearance DB ─── 출연
  ├─ lecture DB ────── 강의
  ├─ billing DB ────── 지갑·정산
  ├─ chat DB ───────── 채팅
  ├─ customersupport DB ─ 고객센터 원문·색인 상태
  ├─ notification DB ─ 알림
  └─ search DB ─────── 색인 inbox·상태 (색인 자체는 ES)

Prometheus ← app·infrastructure metrics scrape
Alloy ─┬─ pod logs ─→ Loki
       └─ OTLP traces → Tempo
Grafana ← Prometheus·Loki·Tempo dashboard·alert

Kafka → search/RAG indexer → Elasticsearch
개발자 관리 도구 → port-forward → Elasticvue

GitHub Actions → test + JaCoCo + SonarScanner → SonarQube Cloud quality gate

고객센터 공개 지식 → Spring AI ETL → customersupport 소유 Elasticsearch index
사용자 질문 → nori BM25 + dense vector + RRF → 근거가 있는 답변 + sources[]
```

PostgreSQL이 Kafka나 Redis로 대체되지 않는다. HP 원장과 채팅 이력은 계속 PostgreSQL이 진실의 원천이다.

## 1. Kafka — 모듈 간 integration event

### 적용 범위

Kafka에는 모든 Spring Event를 보내지 않고, 컨텍스트 밖에서 의미가 있는 integration event만 보낸다.

시연하기 좋은 흐름은 다음과 같다.

1. 회원 상태·역할 변경 → 출연·강의·채팅의 회원 복제 데이터 갱신
2. 출연·강의·지갑·회원·고객센터 이벤트 → notification 모듈의 알림 생성(8장)과 감사 projection
3. 강의 구매 확정·지급 예정 → 정산 projection

HP 원장을 Kafka consumer가 직접 확정하게 만들지는 않는다. 원장 변경은 PostgreSQL 트랜잭션과 멱등키가 책임지고, Kafka는 완료된 사실을 다른 컨텍스트에 전달한다.

### 구현 원칙

- Spring Modulith event externalization을 사용해 기존 publication registry를 outbox seam으로 활용한다.
- event envelope에 `eventId`, `eventType`, `eventVersion`, `occurredAt`, `aggregateId`, `correlationId`를 둔다.
- topic은 기술 이름이 아니라 도메인 사실 기준으로 나눈다. 예: `member.events.v1`, `appearance.events.v1`, `lecture.events.v1`.
- key는 같은 aggregate의 순서를 보장할 ID를 사용한다.
- consumer는 `eventId` 기반 inbox/processed-event 테이블로 멱등 처리한다.
- retry 횟수와 backoff를 제한하고 최종 실패는 DLT로 보낸다. DLT 재처리 명령과 runbook을 함께 만든다.
- payload 변경은 version을 올리고 최소 한 버전의 하위 호환 consumer contract test를 둔다.
- **Kafka instance는 1개만 띄운다.** EC2(와 로컬 k3d)에서 broker와 controller를 한 process로 합친 single-node KRaft로 실행한다. prod와 dev가 함께 쓰게 되면 topic 이름에 환경 prefix를 붙여 나눈다. broker가 하나뿐이므로 topic replication factor는 1이다. 내부 topic 설정(`offsets.topic.replication.factor`, `transaction.state.log.replication.factor`)도 1로 맞춘다. 이 둘은 기본값이 3이라 그대로 두면 broker 1개에서 내부 topic을 만들지 못한다. 그러면 consumer group과 transaction이 동작하지 않는다. `min.insync.replicas`는 1로 둔다.
- 이것이 고가용성 구성이 아님을 README에 명시한다. broker가 내려가 있는 동안 발행할 이벤트는 Modulith publication(outbox)에 남아 있다가 재기동 후 다시 발행되므로 유실되지 않는다. 다만 consumer 쪽 처리는 그만큼 늦어진다. Kafka 공식 문서도 combined broker/controller mode를 개발·소규모 용도로 보고 중요 환경에서는 역할 분리를 권고한다([KRaft 문서](https://kafka.apache.org/42/operations/kraft/#deploying-considerations)).

### broker 선택 — Apache Kafka와 Redpanda

Redpanda는 Kafka API와 호환되는 broker다. 앱의 Kafka client(Spring Kafka, Modulith externalization)는 그대로 붙는다. 둘 다 "Kafka"라는 프로토콜을 쓰고, 고르는 것은 그 뒤의 서버 구현이다.

| 항목 | Apache Kafka (KRaft) | Redpanda |
| --- | --- | --- |
| 구현 | JVM. KRaft로 ZooKeeper 없이 broker 1개 실행 | C++(Seastar), 단일 binary. JVM·ZooKeeper 없음 |
| schema registry | 별도 서버 필요. 이 문서는 registry 없이 레포 schema 파일과 CI 검사로 대신한다(7장) | **broker에 내장**(port 8081). Avro, Protobuf, JSON Schema |
| 공식 메모리 요구 | 운영 최소치를 정하지 않음. heap은 설정으로 조절(이 문서 예산은 heap 512 MiB에 700 MiB) | 운영 broker는 **core당 최소 2 GB, core 최소 2개**. development mode는 최소 메모리를 강제하지 않는다. Docker 실행은 개발·test 용도라고 명시 |
| license | Apache 2.0 | Community Edition은 BSL. Redpanda를 남에게 상업 streaming·queue 서비스로 제공하는 것만 금지하고, 각 코드는 4년 뒤 Apache 2.0으로 바뀐다 |
| 생태계 | Kafka 공식 문서, 운영 지표, Testcontainers | Kafka client 호환, Testcontainers Redpanda module, 자체 CLI(`rpk`)와 Console |

근거: Redpanda production requirements("at least 2 GB of memory per core", "at least two physical CPU cores")([문서](https://docs.redpanda.com/current/deploy/redpanda/manual/production/requirements/)), development mode("No minimal memory limits are enforced")([문서](https://docs.redpanda.com/current/reference/rpk/rpk-redpanda/rpk-redpanda-mode/)), Docker quickstart("only for development and testing purposes")([문서](https://docs.redpanda.com/current/get-started/quick-start/)), schema registry 내장([문서](https://docs.redpanda.com/current/manage/schema-reg/schema-reg-overview/)), license([문서](https://docs.redpanda.com/current/get-started/licensing/overview/)).

**구성 요소 수로는 Redpanda가 가볍다.** broker와 schema registry가 한 binary다. 다만 이 문서는 registry 서버를 쓰지 않으므로(7장) 이 장점이 지금은 크지 않다. **메모리로는 공식 문서상 더 가볍다고 할 수 없다.**
- Redpanda의 운영 최소치는 2 core × 2 GB = 4 GB다. t3.large 8 GiB의 절반이라 3장 예산에 들어가지 않는다.
- t3.large에 맞추려면 development mode에 `--smp 1`과 작은 `--memory`로 돌려야 한다. 문서가 개발·test 용도라고 한 구성을 운영 서버에 두는 셈이고, 이 경우 필요한 메모리를 문서로 정할 수 없다.
- Kafka는 heap을 직접 정하므로 예산을 설정으로 맞출 수 있다(현재 700 MiB, heap 256 MiB면 더 줄일 수 있다).

판단: **broker는 Apache Kafka(KRaft, heap 256~512 MiB)를 유지한다.** Redpanda는 다음 경우에 다시 본다.
- 모듈을 별도 service로 떼어 내 runtime schema registry가 필요해질 때. 이때는 Kafka와 별도 registry 서버 대신 Redpanda 하나로 끝나므로 비교 결과가 달라진다.
- 로컬·CI에서만 쓸 때. Testcontainers Redpanda module은 JVM 기동이 없어 test 시작이 빠를 수 있다. 다만 운영 broker와 test broker가 다르면 동작 차이를 놓칠 수 있으므로, Kafka 고유 동작(transaction, consumer group rebalance)에 대한 test는 Kafka로 한다.

어느 쪽이든 앱 code는 Kafka client만 알고, broker는 설정(`bootstrap-servers`)으로만 바뀐다.

### 만들어야 할 깊은 module

- `IntegrationEventPublisher` interface: 업무 module이 알아야 할 것은 integration event 발행뿐이다.
- Kafka adapter: serialization, topic, header, retry 같은 구현을 숨긴다.
- in-memory/test adapter: 동일 interface로 contract와 업무 테스트를 수행한다.

Kafka template을 각 업무 module에서 직접 호출하면 topic·직렬화·오류 처리가 퍼져 얕은 module이 된다. 이 복잡성은 한 adapter에 모은다.

### 완료 증거

- 앱을 publication 저장 직후 종료해도 재기동 후 Kafka에 한 번 이상 전달된다.
- 같은 event를 두 번 소비해도 projection과 HP 원장 결과가 한 번 처리한 것과 같다.
- consumer를 중지했다 재시작하면 lag가 줄어들고 DLT를 선택적으로 재처리할 수 있다.
- Grafana에서 produce/consume rate, consumer lag, retry, DLT를 볼 수 있다. Kafka도 consumer max lag와 fetch rate 감시를 권장한다([공식 모니터링 문서](https://kafka.apache.org/42/operations/monitoring/)).

## 2. Redis Streams — 채팅 전달 pipeline

### 역할 분리

Redis Streams를 채팅 이력 DB나 STOMP broker처럼 사용하지 않는다.

- PostgreSQL: 채팅 메시지와 outbox의 source of truth
- Redis Streams: 저장된 메시지의 비동기 전달 작업 queue
- Redis Pub/Sub: 여러 app replica로 낮은 지연 fan-out
- 각 app의 내장 STOMP broker: 그 replica에 연결된 WebSocket session으로 최종 전달
- REST 이력 API: offline·disconnect·Pub/Sub 유실 시 최종 복구

Redis Streams는 append-only log, consumer group, 명시적 ACK, pending/claim을 제공한다([공식 문서](https://redis.io/docs/latest/develop/data-types/streams/)). Pub/Sub은 fire-and-forget이지만 여기서는 PostgreSQL 이력으로 다시 읽을 수 있으므로 실시간 fan-out에 사용할 수 있다.

### Redis 선택

**Redis를 쓴다**(팀 결정, 2026-10-28). Redis 8은 RSALv2·SSPLv1·AGPLv3 중 하나를 고르는 tri-license다([Redis licenses](https://redis.io/legal/licenses/)). 이 프로젝트는 Redis를 수정해 다른 사람에게 서비스로 제공하지 않으므로 license 제한에 걸리지 않는다.

쓰는 기능은 Streams, consumer group, `XAUTOCLAIM`, Pub/Sub, TTL이다. vector 검색은 Elasticsearch가 맡으므로 Redis Query Engine은 쓰지 않는다. 앱은 `spring-boot-starter-data-redis`(Lettuce)로 접속한다. Valkey(Redis 7.2에서 갈라진 BSD fork)도 같은 명령과 RESP protocol을 지원하므로, 나중에 바꾸더라도 앱 코드는 그대로 두고 접속 주소만 바꾸면 된다([Valkey history](https://valkey.io/topics/history/)). 3장 자원 예산의 측정값은 Redis 8.2다.

### 메시지 흐름

1. STOMP 요청을 검증한다.
2. 한 PostgreSQL 트랜잭션에서 `chat_message`와 `chat_outbox`를 저장한다.
3. publisher가 outbox를 Redis Stream에 `XADD`한다.
4. `chat-delivery` consumer group이 `XREADGROUP`으로 받는다.
5. 수신자별 channel에 Pub/Sub으로 발행한다.
6. app replica들이 구독하고, 로컬에 해당 사용자의 session이 있을 때 STOMP로 보낸다.
7. Pub/Sub 발행까지 성공하면 `XACK`; 실패하면 pending에 남긴다.
8. idle pending은 `XAUTOCLAIM`으로 다른 worker가 회수하고, 최대 횟수 초과는 별도 failed stream으로 옮긴다.
9. 재연결 client는 마지막 `roomSequence` 이후 이력을 PostgreSQL에서 조회한다.

### 만들어야 할 깊은 module

- `ChatDeliveryQueue` interface: `enqueue`, `consume`, `ack`, `fail`의 작은 interface로 Redis 명령을 숨긴다.
- Redis Streams adapter와 in-memory adapter를 둔다.
- `RealtimeChatFanout` interface 뒤에 Redis Pub/Sub adapter와 단일-node adapter를 둔다.

업무 code는 stream key, consumer name, claim timeout, serialization을 알지 않는다.

### 완료 증거

- 동일 `clientMessageId` 재전송 시 DB 메시지와 WebSocket 결과가 중복되지 않는다.
- consumer를 메시지 처리 중 종료하면 pending message를 다른 consumer가 회수한다.
- Redis를 중지해도 메시지는 PostgreSQL/outbox에 남고 복구 후 전달된다.
- Pub/Sub 전달을 일부 유실시켜도 재접속 history sync로 동일한 최종 상태가 된다.
- 선택한 관측성 UI에서 stream length, pending count/age, claim, failed stream, 전달 latency를 볼 수 있다.

## 3. k3s on EC2 + Kustomize·Helm — 실제 서버의 Kubernetes 배포

### k3s와 k3d의 역할

k3d는 k3s와 다른 배포판이 아니다. 같은 k3s를 Docker container 안에서 실행하는 wrapper다([k3d 공식 문서](https://k3d.io/)). 두 도구는 실행 위치에 따라 나눈다.

| 위치 | 도구 | 이유 |
| --- | --- | --- |
| EC2 large + medium | **k3s** server 1 + agent 1 | Ubuntu host에 직접 설치하고 systemd로 기동한다. node 역할은 [AWS 2노드 k3s 배치](../deploy/aws-k3s.md)를 따른다 |
| 개발 PC (macOS) | k3d | k3s는 Linux용이다([k3s requirements](https://docs.k3s.io/installation/requirements)). k3d는 Docker만 있으면 같은 manifest를 띄운다 |
| CI | k3d | PR마다 cluster 생성 → manifest 적용 → smoke/E2E → 삭제를 짧게 반복한다 |
| node 장애 실험 | EC2 2노드 + 로컬 k3d multi-node | EC2에서는 medium 중지와 large 중지의 서로 다른 영향을 기록하고, 3개 이상 server가 필요한 control-plane HA는 로컬 실험으로 구분한다 |

EC2 cluster는 server 1대와 agent 1대라 **HA가 아니다**. embedded-etcd HA에는 server node 3대 이상이 필요하다([k3s quick start](https://docs.k3s.io/quick-start), [HA 문서](https://docs.k3s.io/datastore/ha-embedded)). medium이 내려가면 large의 모듈 앱과 stateful workload는 남지만 medium의 모듈 앱과 관측성이 멈춘다. large가 내려가면 control plane, 진입점(Envoy proxy), PostgreSQL과 Elasticsearch가 함께 사라져 서비스가 중단된다. 이 차이를 숨기지 않고 pod 장애와 node 장애 시연 결과를 분리한다.

### EC2 2노드 자원 예산

아래 예산은 cgroup limit 기준의 시작값이며 실제 배포 뒤 `kubectl top`, container RSS, JVM GC와 CPU credit으로 갱신한다. EC2에는 prod 한 벌만 둔다. 모듈 앱은 8개이고 앱마다 replica 1개다. 앱은 두 node에 네 개씩 나누고, stateful workload는 large에 고정한다. Envoy·cert-manager·ECK operator 값은 차트 기본값을 낮춘 추정값이라 설치 뒤 다시 잰다.

| t3.large `core-data` | 예산 |
| --- | ---: |
| OS | 350 MiB |
| k3s server + containerd + system pod (Traefik 끔) | 1,650 MiB |
| PostgreSQL | 768 MiB |
| Kafka | 700 MiB |
| Redis | 64 MiB |
| Elasticsearch | 1,536 MiB |
| 모듈 앱 4개(member·billing·appearance·search) × 448 MiB | 1,792 MiB |
| Envoy proxy | 256 MiB |
| **합계** | **7,116 MiB** |

사용 가능 메모리를 약 7.6 GiB로 보면 약 0.65 GiB가 남는다. 이 여유는 rolling surge pod 1개(448 MiB), filesystem cache와 오차에 쓴다. large의 앱은 `maxSurge: 1`, `maxUnavailable: 0`으로 무중단 배포한다. Elasticsearch는 heap만이 아니라 mmap·direct memory를 사용하므로 1.5 GiB limit에서 색인과 hybrid RAG 부하를 다시 측정한다. search-app은 ES와 같은 node에 둔다.

| t3.medium `app-observe` | 예산 |
| --- | ---: |
| OS | 350 MiB |
| k3s agent + containerd | 400 MiB |
| 모듈 앱 4개(lecture·chat·customer-support·notification) × 384 MiB | 1,536 MiB |
| Envoy Gateway controller | 128 MiB |
| ECK operator | 256 MiB |
| cert-manager (controller·webhook·cainjector) | 150 MiB |
| React 정적 서버 | 64 MiB |
| Prometheus·Loki·Tempo·Grafana·Alloy | 600 MiB |
| **합계** | **3,484 MiB** |

사용 가능 메모리를 약 3.7 GiB로 보면 약 0.3 GiB가 남는다. surge pod를 받을 여유가 없으므로 medium의 앱은 `maxSurge: 0`, `maxUnavailable: 1`로 배포하고 **배포하는 동안 그 앱이 잠깐 멈추는 것을 허용**한다. notification-app은 WebSocket을 다루는 chat-app과 같은 node에 둔다. 관측성은 보존 한도를 줄여 600 MiB 안에 두고 별도 backend를 추가하지 않는다.

앱 limit은 448 MiB로 시작한다. 앱별 RSS와 GC를 k6 부하에서 재 보고, 버티는 앱은 384 MiB까지 낮춘다. 그래도 부족하면 notification-app과 search-app은 시연할 때만 띄운다. EC2 instance 상향은 AGENTS.md 8장 범위 밖이라 팀과 운영진에게 먼저 묻는다.

#### 과거 단일-node 측정 기록

다음 측정값은 t3.large 단일 node 시절의 원자료다. 구성 요소별 request/limit 근거로만 사용하고, 아래 시나리오 합계와 권고를 현재 배치 결정으로 사용하지 않는다.

t3.large는 8 GiB지만 kernel이 일부를 예약하므로 당시 계산은 **사용 가능 약 7.6 GiB**로 두었다.

#### 측정값

2026-10-22에 로컬 Docker에서 쟀다.
- 앱: 현재 `dev` 브랜치의 `app.jar`, local profile, 빈 DB.
- 부하: k6 20 VU로 강의 목록 조회·health·OpenAPI 요청을 60~90초. 요청 실패는 0건이었다.
- 앱 JVM 옵션은 dev compose와 같다(`-XX:MaxRAMPercentage=75`).

| 구성 요소 | 대기 | 부하 후 | 비고 |
| --- | --- | --- | --- |
| Spring 앱, 제한 1 GiB | 670 MiB | 904 MiB | cgroup peak 984 MiB. 제한 가까이까지 늘어난다 |
| Spring 앱, 제한 768 MiB | - | 760 MiB | OOM 없음 |
| Spring 앱, 제한 512 MiB | - | 506 MiB | OOM 없음. 시작 7.7초 |
| PostgreSQL 18 | 63 MiB | 65 MiB | 데이터가 거의 없을 때 값 |
| Kafka 4.1 KRaft (heap 512 MiB) | 309 MiB | 315 MiB | Kafka에는 부하 없음 |
| Redis 8.2 | 12 MiB | 11 MiB | |
| Alloy 1.10 | 108 MiB | - | metrics 측정 때 값 |
| Prometheus 3.5 | 42 MiB | - | 3,613 series |
| Loki 3.5 / Tempo 2.8 / Grafana 12.2 | 55 / 33 / 112 MiB | 66 / 34 / 110 MiB | 수집 데이터 없음 |
| Traefik 3.5 | 14 MiB | 16 MiB | route 없음 |

측정에서 얻은 결론은 둘이다.
- **JVM pod는 limit 근처까지 메모리를 쓴다.** 부하가 가벼워도 heap 상한까지 늘어나므로, 예산은 사용량이 아니라 limit으로 잡는다.
- **현재 앱은 512 MiB로도 돈다.** 다만 빈 DB에 가벼운 조회만 한 결과다. Kafka client, Redis client, Spring AI가 들어가면 metaspace와 heap이 늘어나므로 최종 구성에서 다시 잰다.

k3s 자체는 측정하지 않고 공식 resource profiling 값을 쓴다. single-node server(SQLite)와 가벼운 workload에서 k3s와 containerd가 p95 **1,596 MiB**를 썼다. 이 값에는 OS와 pod가 들어가지 않는다([k3s resource profiling](https://docs.k3s.io/reference/resource-profiling)).

참고로 지금 prod compose의 `app`에는 `mem_limit`가 없다. 그래서 JVM 기본값(전체 메모리의 25%, 약 1.9 GiB)까지 heap이 커질 수 있다. 세미 프로젝트 서버에서도 prod 앱이 얼마나 쓰는지 `docker stats`로 확인할 필요가 있다.

#### 과거 단일-node 예산

예산은 측정값에 여유를 더한 값이다. JVM은 limit으로 잡는다.

| 구성 요소 | 예산 | 근거 |
| --- | --- | --- |
| OS (Ubuntu, systemd, sshd, SSM agent 등) | 350 MiB | 추정. 서버에서 확인 |
| k3s + containerd | 1,500 MiB | 공식 profiling 1,428~1,596 MiB |
| kube-system pod (CoreDNS, metrics-server, local-path, Traefik) | 100 MiB | Traefik 측정 16 MiB + 추정 |
| edge nginx (+ Docker daemon) | 100 MiB | edge nginx를 Docker로 계속 띄우면 dockerd가 남는다 |
| Kafka (heap 512 MiB) | 700 MiB | heap + non-heap. 측정 315 MiB는 부하 없는 값 |
| Redis | 50 MiB | 측정 11 MiB |
| Alloy / Prometheus / Loki / Tempo / Grafana | 200 / 300 / 150 / 150 / 150 MiB | Prometheus는 수만 series를 가정해 측정값의 약 7배 |
| **공용 platform 소계** | **3,750 MiB (3.7 GiB)** | prod·dev가 함께 쓴다 |
| 환경별 PostgreSQL | 512 MiB (prod) / 256 MiB (dev) | 현재 dev compose limit 512m |
| 환경별 React web (nginx) | 20 MiB | |
| Spring 앱 pod 1개 | limit 768 MiB 또는 512 MiB | |

#### 과거 시나리오 A — prod만 단일 EC2에 올린다

prod는 앱 pod 3개, pod limit은 768 MiB다.

| 상태 | 합계 | 7.6 GiB 대비 여유 |
| --- | --- | --- |
| 평상시 | 3,750 + 532 + 768×3 = **6,586 MiB (6.4 GiB)** | 약 1.2 GiB |
| rolling update (surge pod 1개) | 6,586 + 768 = **7,354 MiB (7.2 GiB)** | 약 0.4 GiB |

**들어간다.** 남는 0.4 GiB는 page cache와 kubelet eviction 기준(기본 `memory.available<100Mi`)에 쓰인다. pod limit을 512 MiB로 낮추면 평상시 5.7 GiB, rolling 때 6.2 GiB로 여유가 1.4 GiB 이상이 된다. 앱 기능이 다 들어간 뒤 512 MiB로 버티는지 재 보고 정한다.

#### 과거 시나리오 B — prod와 dev를 단일 EC2에 함께 올린다

dev는 앱 pod 1개와 작은 PostgreSQL로 둔다. Kafka·Redis·관측 stack은 prod와 함께 쓴다(topic·key prefix, namespace label로 구분).

| 구성 | 평상시 | rolling update | 판단 |
| --- | --- | --- | --- |
| prod pod 768 × 3, dev pod 768 × 1, dev DB 512 | **7,886 MiB (7.7 GiB)** | 8,654 MiB | **들어가지 않는다** |
| prod pod 768 × 3, dev pod 512 × 1, dev DB 256 | **7,374 MiB (7.2 GiB)** | 8,142 MiB | 평상시만 겨우 들어가고 배포할 수 없다 |
| 모든 pod 512 (prod 3 + dev 1), dev DB 256 | **6,606 MiB (6.5 GiB)** | 7,118 MiB | 들어간다. 단 512 MiB가 최종 기능에서 버티는지가 전제 |
| 위 + Kafka heap 256 MiB | 약 **6.2 GiB** | 약 6.7 GiB | rolling 때도 여유 약 0.9 GiB. PostgreSQL instance는 환경마다 1개를 유지한다(10장) |

**pod limit을 512 MiB로 줄이지 않으면 두 환경은 함께 들어가지 않는다.** 함께 올리려면 다음을 모두 받아들여야 한다.
- 모든 Spring pod를 512 MiB로 줄인다. 최종 기능에서 OOM이나 GC 지연이 생기면 바로 넘친다.
- dev에는 replica 1개뿐이라, rolling update 시연은 prod에서만 한다.
- k6 부하 test는 공용 Kafka·DB·관측 stack을 같이 흔든다. prod에 영향이 없는 시간에만 한다.

#### 과거 단일-node Elasticsearch 측정 (#234, 9장)

ES 9.1.5를 단일 노드로 띄워 대기 상태만 쟀다. 요청은 보내지 않았다.

| 조건 | 결과 |
| --- | --- |
| heap 512 MiB, 컨테이너 제한 1 GiB | 대기 918 MiB. nori 설치 후 939 MiB(cgroup peak 939 MiB) |
| heap 384 MiB, 컨테이너 제한 768 MiB | cluster green, OOM 없음, 766 MiB(제한까지 채움) |

ES도 JVM처럼 제한까지 메모리를 채운다. heap 밖에 Lucene의 mmap과 direct memory를 쓰기 때문이다. 그래서 예산은 제한값으로 잡는다. 권장은 1 GiB(heap 512 MiB)이고, 최소는 768 MiB(heap 384 MiB)다. 768 MiB는 대기 상태만 확인한 값이라 색인·검색 부하에서 다시 잰다. ES 공식 문서도 heap을 컨테이너 메모리의 50% 이하로 두라고 한다([JVM settings](https://www.elastic.co/docs/reference/elasticsearch/jvm-settings)).

| 시나리오 | ES | 평상시 | rolling update | 판단 |
| --- | --- | --- | --- | --- |
| A, pod 768 MiB | 1,024 | 7,610 MiB (7.4 GiB) | 8,378 MiB | **들어가지 않는다** |
| A, pod 512 MiB | 1,024 | 6,842 MiB (6.7 GiB) | 7,354 MiB (7.2 GiB) | 들어간다. rolling 때 여유 약 0.4 GiB |
| A, pod 512 MiB | 768 | 6,586 MiB (6.4 GiB) | 7,098 MiB (6.9 GiB) | 들어간다. 여유 약 0.7 GiB |
| B 최소 구성(위 마지막 행) | 1,024 | 7,380 MiB (7.2 GiB) | 7,892 MiB | **들어가지 않는다** |
| B 최소 구성 | 768 | 7,124 MiB (7.0 GiB) | 7,636 MiB (7.5 GiB) | 여유 약 0.1 GiB. 사실상 불가 |

이 결과는 Elasticsearch가 단일 t3.large의 다른 workload와 경쟁했을 때의 기준선이다. 현재 2노드에서는 Elasticsearch를 large에 두고 application·관측성 일부를 medium으로 분리하므로 위 합계를 그대로 적용하지 않는다.

Elasticsearch는 검색과 RAG만 담당한다. logs·metrics·traces를 Elasticsearch에 넣지 않아 검색 index와 telemetry ingest가 heap·CPU·disk를 경쟁하지 않게 한다.

#### 현재 권고

**EC2 k3s에는 prod(main) 한 벌만 둔다. dev 서버는 없애고, 개발 확인은 로컬과 PR k3d에서 한다.** 앱 8개는 위 예산 표대로 두 node에 나눈다.

dev 서버가 없어지면 프론트가 dev 서버로 API를 확인하던 경로(#332)도 사라진다. 전환 일정과 로컬 확인 방법을 프론트와 먼저 합의한다. 로컬에서는 BE 앱을 compose나 k3d로 띄우고, 역할별 테스트 계정 로그인은 local profile에서만 켠다. 서버에 올리기 전에 `free -m`, `kubectl top`과 선택한 관측성 UI의 node memory로 이 표를 실측값으로 바꾼다.

CPU는 2 vCPU의 standard credit이다. JVM pod 여러 개가 동시에 시작하면 credit을 소모하고 시작이 느려진다. node 재시작 때는 앱 4개가 한꺼번에 뜨므로 startupProbe 여유를 넉넉히 둔다.

large의 50 GB에는 container image, PostgreSQL, Kafka log와 Elasticsearch 검색/RAG index가 들어간다. medium의 50 GB에는 application image와 Prometheus·Loki·Tempo data가 들어간다. 각 저장소에 크기·기간 retention을 두고 Compose에서 k3s로 넘어가는 기간에는 Docker와 containerd가 image를 따로 저장하므로 node별 디스크 여유를 먼저 확인한다.

### EC2 운영 방식

- **진입점**: nginx는 쓰지 않는다. Envoy Gateway가 유일한 진입점이다. Envoy proxy Service는 `LoadBalancer`이고, k3s 내장 ServiceLB가 node의 80·443을 Envoy로 연결한다. ServiceLB는 large에만 `svccontroller.k3s.cattle.io/enablelb=true` label을 붙여 large에서만 켠다. 이 label이 붙은 node가 하나도 없으면 모든 node가 후보가 된다([k3s networking 문서](https://docs.k3s.io/networking/networking-services)). Envoy proxy Service는 `externalTrafficPolicy: Local`로 두어 Envoy가 실제 client IP를 보게 한다. DNS와 80·443 security group이 large EIP만 가리키기 때문이다. medium의 앱에는 Envoy가 cluster 내부 Service로 보낸다.
  - 지금 설치한 cluster는 Compose의 edge nginx가 80·443을 쓰므로 servicelb를 끄고, k3s 기본 Traefik은 켜진 채로 두었다([k3s 설치](../deploy/aws-k3s.md#k3s-설치)). 첫 설치 때 svclb의 hostPort DNAT가 edge nginx보다 먼저 80·443을 가로챘다. 그래서 **edge nginx를 멈춘 뒤에** servicelb를 켜고 Traefik을 끈다. 이 전환 동안 외부 접속이 잠깐 끊긴다([전환 순서](../deploy/aws-k3s.md#전환-순서)).
- **TLS**: cert-manager(공식 Helm 차트)가 Let's Encrypt에서 prod 도메인 인증서를 Gateway API HTTP-01 solver로 발급·갱신한다. 지금 edge nginx 옆의 certbot을 대신한다.
- **cluster API**: 6443은 인터넷에 공개하지 않고 같은 node security group에서 medium → large만 허용한다. 사람은 SSH tunnel로 접근한다([로컬 kubectl 접근](../deploy/aws-k3s.md#로컬-kubectl-접근)). 배포는 GitHub Actions → SSH → large에서 `kubectl apply -k`와 `rollout status`, 실패 시 `rollout undo`로 한다(아래 "manifest 관리").
- **namespace**: `hapbang-prod`(모듈 앱·React·PostgreSQL), `hapbang-platform`(Kafka·Redis·Elasticsearch·관측성)과 Envoy Gateway·cert-manager·ECK operator의 system namespace를 둔다. dev namespace는 두지 않는다.
- **S3 권한**: 모듈 앱이 어느 node에 배치돼도 S3를 호출할 수 있도록 두 EC2에 같은 instance role과 IMDSv2 hop limit 2를 적용한다. pod별 IAM 권한 분리(EKS IRSA·Pod Identity)는 EKS 기능이라 쓸 수 없으므로 **node의 pod가 같은 S3 권한을 공유한다**는 한계를 문서에 남기고, NetworkPolicy로 앱 pod 외에는 metadata endpoint(169.254.169.254)로 나가지 못하게 막는다.
- **이미지**: GHCR private image를 `imagePullSecret`으로 받는다. 태그는 지금처럼 `sha-<커밋>`을 고정해 쓴다.
- **자동 중지**: 매일 18:00 instance 중지는 node shutdown이다. kubelet graceful node shutdown(`shutdownGracePeriod`, `shutdownGracePeriodCriticalPods`)은 kubelet CLI 플래그가 없으므로 k3s의 kubelet drop-in 설정 파일로 두고, Ubuntu logind의 `InhibitDelayMaxSec`도 함께 올린다(설정은 [AWS 2노드 k3s 배치](../deploy/aws-k3s.md#k3s-설치)). 이 설정이 없으면 pod는 4장의 종료 순서를 밟지 못하고 끝날 수 있다([Kubernetes node shutdown](https://kubernetes.io/docs/concepts/cluster-administration/node-shutdown/)). instance 중지 때 OS가 실제로 기다려 주는 시간 안에 drain이 끝나는지 실측한다.

### API Gateway — Envoy Gateway

#### 맡는 책임

| 책임 | 내용 | 지금(Compose) 담당 |
| --- | --- | --- |
| TLS 종료 | cert-manager가 발급한 인증서로 443을 종료하고 80은 HTTPS로 redirect한다 | edge nginx + certbot |
| 모듈 단위 routing | AGENTS.md 3장의 모듈 진입 경로마다 `HTTPRoute`를 두고 그 앱의 Service로 보낸다. `/`는 React 정적 서버로 보낸다 | edge nginx의 `/api` 한 줄 |
| JWT 서명 검증 | `SecurityPolicy`의 JWT provider가 member-app JWKS(`remoteJWKS`)로 서명·만료·발급자를 확인한다. `optional: true`로 둔다. 검증한 claim `sub`·`role`을 `X-User-Id`·`X-User-Role` 헤더로 넘긴다(`claimToHeaders`). 회원의 역할은 하나라 `role`은 문자열 하나다. Envoy의 `claimToHeaders`는 배열 claim을 넘기지 못하므로 배열로 만들지 않는다 | 없음(세션) |
| 헤더 위조 차단 | 모든 route에서 외부 요청에 붙은 `X-User-*` 헤더를 먼저 지운다 | 없음 |
| 내부 API 차단 | `/api/v1/internal/**`은 `HTTPRoute`를 만들지 않는다. cluster 안 Service 호출로만 닿는다 | edge nginx가 404 |
| 큰 본문 업로드 | 업로드 경로(`PUT /api/v1/{모듈}/files/{id}/content`)에만 긴 timeout을 건다(`BackendTrafficPolicy`·`ClientTrafficPolicy`) | edge nginx의 2GB 허용 |
| trace 시작점 | 요청마다 W3C `traceparent`를 만들거나 이어 받아 OTLP로 보내고, access log를 JSON으로 남겨 Alloy가 수집한다 | 없음 |

rate limit은 Gateway가 아니라 각 앱의 Resilience4j가 맡는다(아래).

#### 인증 흐름

JWT는 암호화가 아니라 **서명**이다. payload는 누구나 Base64로 풀어 읽을 수 있으므로 회원 ID·역할 외의 정보를 넣지 않는다. 서명 검증은 "member-app의 개인 키로 서명됐고, 그 뒤 내용이 바뀌지 않았는가"를 공개 키로 확인하는 일이다.

1. member-app이 Google OAuth 로그인 뒤 access token(RS256, 15분)을 발급한다. 개인 키는 member-app만 갖고, 공개 키는 JWKS endpoint로 공개한다(#370).
2. refresh token(14일)은 `member` database에 저장하고 쓸 때마다 회전한다. refresh 경로로만 가는 `HttpOnly; Secure; SameSite=Strict` 쿠키로 준다. 로그아웃하면 폐기한다.
3. 프론트는 access token을 메모리에 두고 `Authorization: Bearer`로 보낸다. 새로고침하면 시작할 때 refresh를 한 번 호출한다. Bearer 헤더는 브라우저가 자동으로 싣지 않으므로 API의 CSRF 보호(`X-XSRF-TOKEN`)는 뺀다.
4. Envoy가 서명을 검증한다. 위조·만료 토큰은 401로 거절하고, 토큰이 없는 요청은 헤더 없이 통과시킨다. 로그인이 필요한지는 각 앱의 Security 설정이 정한다. 그래서 비로그인 경로 목록을 Envoy와 앱 두 곳에 적지 않는다.
5. 앱은 `X-User-*` 헤더로 인증 객체를 만들고 `@PreAuthorize`로 인가한다(#371). 앱은 서명을 다시 검증하지 않으므로, NetworkPolicy로 앱의 외부 API 포트에는 Envoy만 접근하게 한다. 앱 간 내부 API는 지금처럼 `X-Internal-Api-Key`로 인증한다.
6. 채팅: 브라우저의 WebSocket upgrade 요청에는 Bearer 헤더를 실을 수 없다. chat-app은 STOMP CONNECT frame의 토큰을 JWKS로 직접 검증한다. 토큰을 URL query에 싣지 않는다(access log에 남는다). 연결 뒤 강제 종료는 회원 이벤트(#242)를 따른다.
7. 발급한 JWT는 회수할 수 없다. 정지·역할 변경은 다음 refresh에서 막히고, 최대 15분(access token 수명) 늦게 반영된다. 그래서 HP가 움직이는 경로(보류·반환·지급·환전)와 역할 조건이 있는 거래(출연 거래는 활동 중인 인플루언서 이상, `docs/policy.md`)는 토큰의 `role`만 믿지 않고, 각 앱이 복제한 회원 상태를 함께 확인한다(#371).
8. `docs/policy.md`의 "로그인 상태는 서버 세션으로 유지"와 "역할 변경은 다시 로그인하지 않아도 바로 반영"은 JWT 전환과 맞지 않는다. policy를 어떻게 고칠지는 팀이 정한다(#370).

OAuth 진행 상태(authorization request)는 HttpSession 대신 짧은 수명의 암호화 쿠키에 저장한다. 그래서 Spring Session과 그 저장소였던 `platform` database가 필요 없다.

#### rate limit — 각 앱의 Resilience4j

- 대상: 로그인, HP 충전 요청, 고객센터 RAG 질문처럼 남용되면 비용이 드는 경로
- key: `X-User-Id`가 있으면 회원 ID, 없으면 client IP
- client IP 신뢰 경계: Envoy 앞에는 신뢰할 proxy가 없으므로 `ClientTrafficPolicy`의 `X-Forwarded-For` 신뢰 hop 수는 0으로 둔다. Envoy는 실제 접속 주소를 `X-Forwarded-For` 맨 뒤에 붙인다. 앱(`server.forward-headers-strategy=native`, Tomcat `RemoteIpValve`)은 오른쪽부터 읽어 cluster 내부 주소가 아닌 첫 주소를 client IP로 쓴다. 그래서 클라이언트가 앞쪽에 넣은 값은 무시된다. Envoy가 실제 client IP를 보는지(ServiceLB와 `externalTrafficPolicy: Local`)는 설치 뒤 access log로 확인한다
- 한도를 넘으면 429를 `ProblemDetail`로 응답한다
- RateLimiter는 앱 프로세스 메모리에서 센다. 앱마다 replica가 1개라 앱 하나의 한도가 곧 전체 한도다. replica를 늘리면 한도도 replica 수만큼 늘어나므로, 그때 공유 저장소 방식을 다시 판단한다
- 구현은 쓰는 앱에 먼저 두고, 두 앱 이상이 같은 코드를 쓰면 `common`으로 옮긴다
- 앱 간 내부 HTTP 호출(#280)의 timeout·circuit breaker도 Resilience4j로 통일한다

#### 구현 후보와 판단

| 후보 | 형태 | 장점 | 이 프로젝트에서의 한계 |
| --- | --- | --- | --- |
| **Envoy Gateway (선택)** | Gateway API 구현. controller + Gateway마다 Envoy proxy Deployment | OSS에서 JWT 검증(`SecurityPolicy`), claim의 헤더 전달, WebSocket, OpenTelemetry tracing을 지원한다. 공식 Helm 차트 | controller와 proxy가 상시 pod다. Helm 기본값(controller 요청 256Mi, proxy 요청 512Mi)이 커서 예산에 맞게 낮춘다 |
| Traefik (k3s 내장) | k3s 기본 ingress, Gateway API provider | 추가 설치 없음 | JWT middleware는 유료 Traefik Hub 전용이다. OSS에서는 요청마다 다른 서비스를 부르는 ForwardAuth나, 시작할 때마다 인터넷에서 받는 실험적 plugin으로만 할 수 있다 |
| Spring Cloud Gateway | 별도 Spring Boot 앱 | 팀이 익숙한 Java filter, JWKS 검증 | **JVM이 하나 더** 상주한다. WebSocket 프록시는 WebFlux 버전만 지원한다 |
| edge nginx 확장 | 지금 설정에 route 추가 | 추가 component 없음 | nginx를 없애기로 했다. 서버 파일을 직접 고치는 방식이라 cluster 배포와 버전 관리가 따로 논다 |

근거: [Envoy Gateway JWT Authentication](https://gateway.envoyproxy.io/docs/tasks/security/jwt-authentication/), [Traefik JWT middleware(Hub 전용)](https://doc.traefik.io/traefik/reference/routing-configuration/http/middlewares/jwt/), [Spring Cloud Gateway 문서](https://docs.spring.io/spring-cloud-gateway/reference/).

판단: **Envoy Gateway를 쓴다.** 새 JVM 없이 TLS·routing·JWT 검증·internal 차단을 한곳에서 맡고, route와 정책이 `k8s/` manifest 안의 `HTTPRoute`·`SecurityPolicy`로 앱과 같은 PR에서 관리된다.

참고로 Kubernetes 커뮤니티의 Ingress NGINX controller는 2026년 3월에 은퇴해 더는 보안 수정이 나오지 않는다([Kubernetes blog](https://kubernetes.io/blog/2025/11/11/ingress-nginx-retirement/)). cluster 안 routing을 Ingress가 아니라 Gateway API로 만드는 근거다.

#### 완료 증거

- `HTTPRoute` 하나의 backend만 바꿔 특정 모듈 경로를 다른 Service로 보내도 프론트 요청과 로그인이 그대로 동작한다.
- 만료·위조 토큰은 401이고, 토큰 없는 요청은 통과한다. 외부에서 보낸 `X-User-Id`는 앱에 닿지 않는다.
- Envoy가 아닌 pod에서 앱의 외부 API 포트를 호출하면 NetworkPolicy가 막는다.
- 위조한 `X-Forwarded-For`를 보내도 rate limit key(client IP)가 바뀌지 않는다.
- 외부에서 `/api/v1/internal/**`를 호출하면 앱에 닿지 않는다.
- 2GB 업로드가 끝까지 성공한다.
- STOMP WebSocket이 Envoy를 거쳐 연결되고, CONNECT에서 토큰이 검증되며, rolling update 때 재연결된다.
- rate limit 한도를 넘긴 k6 요청이 앱에서 429로 거절되고, 선택한 관측성 UI에 거절 수가 나온다.
- cert-manager가 인증서를 자동으로 갱신한다.
- Envoy가 시작한 trace가 Tempo에서 앱 transaction과 Kafka publish까지 이어진다.

### workload 구성

- 모듈 앱 8개(`member-app`, `billing-app`, `appearance-app`, `lecture-app`, `chat-app`, `customer-support-app`, `notification-app`, `search-app`): 앱마다 Deployment 1개, replica 1개. node 배치는 위 자원 예산 표를 따른다.
- React 정적 서버: FE 레포의 이미지를 그대로 pod로 띄운다. 이미지 안의 정적 서버도 nginx에서 static-web-server로 바꾼다(prgrms-be-adv-devcourse/beadv8_8_LMS_FE#126).
- PostgreSQL, Kafka, Redis, Elasticsearch: StatefulSet과 large의 local-path volume. `core-data` required node affinity를 사용하며 medium으로 volume이 이동한다고 가정하지 않는다.
- Envoy Gateway: proxy는 large, controller는 medium. cert-manager와 ECK operator는 medium.
- observability stack: `hapbang-platform` namespace에서 prod를 관측한다. 로컬·PR k3d 결과는 테스트 artifact로 남긴다.

replica가 1개라서 large의 앱은 새 pod가 ready가 된 뒤 기존 pod를 내리는 `maxSurge: 1`, `maxUnavailable: 0`으로 배포한다. medium의 앱은 surge 여유가 없어 `maxSurge: 0`, `maxUnavailable: 1`로 배포하고 잠깐 멈추는 것을 허용한다. `maxSurge`를 생략하면 기본값 25%가 1로 올림되어 surge pod가 생기므로 반드시 0으로 적는다. node가 멈추면 그 node의 앱은 node가 다시 뜰 때까지 멈춘다. 앱별 replica를 늘리는 것은 시연할 때만 하고, 그 전에 node 여유를 확인한다.

### manifest 관리 — Kustomize와 Helm

우리가 만든 앱과 직접 쓰는 workload는 레포의 `k8s/` 아래에 평문 YAML로 두고 Kustomize로 환경별 차이만 덮어쓴다(`kubectl apply -k`). 잘 관리되는 공식·준공식 차트가 있는 component만 Helm으로 설치한다. Helm CLI는 쓰지 않고, k3s 내장 helm-controller의 `HelmChart` resource(`valuesContent`)를 manifest로 커밋한다.

```text
k8s/
  cluster/       namespace, HelmChart(Envoy Gateway·cert-manager·ECK operator), GatewayClass·Gateway, ClusterIssuer
  components/    Kustomize Component: core-data required affinity, app-observe 배치 같은 공통 patch
  platform/      hapbang-platform: Kafka·Redis(직접 manifest), Elasticsearch(ECK CR), 관측성(HelmChart)
    base/
    overlays/    local(k3d), prod
  apps/          hapbang-prod: 모듈 앱 8개, React, PostgreSQL(HelmChart), HTTPRoute, SecurityPolicy, NetworkPolicy
    base/
    overlays/    local(k3d), prod
```

CI 자동 배포는 `k8s/apps/overlays/prod`만 적용한다. `cluster/`와 `platform/`은 자주 바뀌지 않고 잘못 건드리면 데이터가 위험하므로 수동으로만 적용한다. 그래서 앱 롤백이 stateful workload를 건드리지 않는다.

| 대상 | 설치 방식 | 이유 |
| --- | --- | --- |
| Envoy Gateway | 공식 Helm 차트 | Envoy 프로젝트가 직접 관리한다 |
| cert-manager | 공식 Helm 차트 | CNCF Graduated |
| Elasticsearch | ECK operator(Elastic 공식 Helm 차트) + Elasticsearch CR | ES 9.x를 지원하면서 관리가 이어지는 유일한 경로다. 예전 `elastic/helm-charts`는 2023년에 보관(archived)됐고 9.x 차트가 없다. nori는 커스텀 이미지를 `spec.image`로 지정한다. ECK의 ES 기본 메모리 2Gi는 1.5Gi로, operator limit 1Gi는 256Mi로 낮춘다. ECK 자체는 Elastic License 2.0이며 자체 운영에는 제한이 없다 |
| PostgreSQL | CloudPirates `postgres` 차트 | 공식 `postgres` 이미지를 쓰는 일반 차트다. CloudNativePG(CNCF)는 instance가 1개라 failover를 쓸 수 없고 S3 백업도 범위 밖이라 operator 비용만 남는다 |
| 관측성 | Grafana·prometheus-community 공식 차트 | 설정이 복잡해 차트의 이득이 크다 |
| Kafka | StatefulSet 직접 작성(`apache/kafka`, single-node KRaft) | Apache 공식 차트가 없다. Strimzi operator는 기본 384Mi를 상시로 쓴다 |
| Redis | StatefulSet 직접 작성(`redis:8`, AOF + PVC) | Redis Ltd의 OSS용 공식 차트가 없다. OT redis-operator는 기본 500Mi로 Redis 예산(64 MiB)보다 크다 |

Bitnami 차트는 쓰지 않는다. 2025년 8월부터 버전별 무료 이미지가 `bitnamilegacy`로 옮겨져 보안 패치가 나오지 않는다. 모든 차트는 버전을 고정하고 resource·node affinity·retention을 values에 적는다.

apps base에 둘 것:
- Deployment, Service, `HTTPRoute`(모듈 경로별), `SecurityPolicy`
- startup/readiness/liveness probe, resource request/limit
- ConfigMap. Secret(JWT 개인 키, DB 계정, 내부 API key)은 커밋하지 않고 서버에서 만든다
- graceful shutdown(4장)
- NetworkPolicy: 앱의 외부 API 포트는 Envoy만, 내부 API는 앱끼리만, metadata endpoint는 S3를 쓰는 앱만. 앱이 `X-User-*` 헤더를 믿으므로 이것이 헤더 위조를 막는 마지막 방어선이다. k3s 내장 network policy controller가 실제로 막는지 아래 완료 증거로 확인한다
- migration Job 또는 단일 migration 실행 전략
- 앱별 Kafka·Redis·PostgreSQL·ES endpoint와 member-app JWKS 주소 주입

overlay에서 바꿀 것: image tag(`kustomize edit set image`), resource limit, 환경별 ConfigMap 값. local overlay는 k3d 단일 node라 node affinity를 뺀다.

배포와 롤백은 kubectl로 한다.
1. `kubectl apply -k k8s/apps/overlays/prod`
2. `kubectl rollout status --timeout`으로 새 pod가 준비되기를 기다린다.
3. 실패하면 `kubectl rollout undo`로 Deployment를 직전 revision으로 되돌리고 배포를 실패로 표시한다.

Kustomize로 관리하는 앱에는 release 단위 롤백이 없다. 그래서 ConfigMap 변경은 이름에 hash가 붙는 `configMapGenerator`로 만든다. 그러면 `rollout undo`가 이전 ConfigMap까지 함께 되돌린다.

### CI 검증

- 지금(#363 이후): 필수 체크 `verify` job 하나가 `verifyModuleStructure` → 앱별 `check bootJar` → 앱별 이미지 빌드(push 안 함)를 실행한다. matrix 전환은 앱별 이미지 발행(#374)과 함께 판단한다.
- 배포: #363부터 `deploy.yml`의 자동 trigger가 꺼져 있다. 앱별 이미지 발행과 prod 배포는 #374에서 다시 작성한다.
- manifest: `kubectl kustomize`로 overlay별 rendering, kubeconform 같은 schema validation
- PR: k3d cluster 생성 → `kubectl apply -k k8s/apps/overlays/local` → migration → smoke/E2E → cluster 삭제
- PR: 앱 사이 JWT 헤더 인증·WebSocket·Kafka·Redis 흐름 검증
- prod k3s: 수동 dispatch로 승인된 main image를 `kubectl apply -k` → `rollout status` → 실패 시 `rollout undo`
- EC2: rolling update 중 HTTP 요청과 WebSocket 재연결 결과 기록
- EC2: 자원 사용량(`kubectl top`)이 예산 안인지 배포 후 기록

## 4. Graceful Shutdown — 전체 workload drain

graceful shutdown은 단순히 HTTP 요청을 기다리는 option이 아니다. web·consumer가 **새 작업을 받는 단계부터 차례로 닫히고**, PostgreSQL connection은 진행 중인 처리의 상태 저장이 끝날 때까지 살아 있어야 한다. Spring Boot 4.1은 embedded Tomcat의 graceful shutdown을 기본 활성화하며, application context 종료의 이른 `SmartLifecycle` phase에서 새 요청을 막고 기존 요청에 종료 유예 시간을 준다. phase별 timeout은 `spring.lifecycle.timeout-per-shutdown-phase`로 설정한다([Spring Boot 공식 문서](https://docs.spring.io/spring-boot/4.1/reference/web/graceful-shutdown.html)).

### 종료 계약과 순서

`DrainCoordinator`를 종료 순서를 소유하는 깊은 module로 두고 종료 동작을 여러 listener에 흩뿌리지 않는다. interface는 `drain(deadline)` 하나이며 내부에서 Web, Kafka, Redis용 drain adapter를 정해진 순서로 호출한다. 상태는 `ACCEPTING → DRAINING → STOPPED`이며 중복 호출해도 같은 결과인 idempotent operation이다. Kubernetes `preStop`은 localhost의 내부 drain endpoint를 호출하고 짧게 endpoint 전파를 기다린다. endpoint는 `/api/v1/internal/operations/drain`으로 두고 loopback과 내부 key로 제한하며 Envoy에는 route를 만들지 않는다.

종료 순서는 다음으로 고정한다.

1. Kubernetes가 Pod를 `Terminating`으로 만들면 EndpointSlice의 `ready`가 `false`가 되어 일반 Service traffic에서 제외된다. 동시에 `preStop`이 drain을 시작한다. Kubernetes는 `preStop` 실행 전부터 전체 종료 유예 시간을 세고, hook과 process 종료가 모두 그 안에 끝나지 않으면 강제 종료한다([Kubernetes Pod 종료 흐름](https://kubernetes.io/docs/tutorials/services/pods-and-endpoint-termination-flow/), [container lifecycle hook](https://kubernetes.io/docs/concepts/containers/container-lifecycle-hooks/)).
2. readiness를 `OUT_OF_SERVICE`로 바꾸고 application-level `acceptingWork=false` gate를 닫는다. web은 새 long-running command를 거절하고, 기존 짧은 HTTP 요청은 Spring Boot/Tomcat이 마칠 시간을 준다. WebSocket client에는 재연결 신호를 보낸 뒤 session을 닫으며, 재접속 후 PostgreSQL history sync로 gap을 복구한다.
3. Kafka listener는 새 poll 처리를 멈추고 현재 record까지만 끝낸 뒤 offset을 commit한다. 처리 결과가 commit되지 않은 record는 다음 consumer가 다시 받고 inbox 멱등성으로 흡수한다. Redis Streams worker는 새 `XREADGROUP`을 멈추고 DB commit과 fan-out이 끝난 건만 `XACK`한다. 미완료 건은 pending에 남아 다른 worker가 `XAUTOCLAIM`한다.
4. in-flight HTTP, WebSocket close, consumer 처리가 끝난 다음 executor를 종료한다. DataSource/HikariCP는 이 과정의 마지막까지 유지하고 Spring context가 닫을 때 종료한다. 종료 callback에서 DB pool을 직접 먼저 닫지 않는다.

Kubernetes 설정의 시작값은 `terminationGracePeriodSeconds: 120`, `preStop` drain·전파 예산 10초, application drain 예산 최대 90초, 강제 종료 전 여유 20초로 둔다. 이 숫자는 운영 SLO가 아니라 k6와 장애 test에서 측정해 조정할 초기값이다. `preStop`과 Spring의 phase timeout을 단순 합산했을 때 120초를 넘지 않게 CI에서 실제 종료 시간을 잰다.

EC2 매일 중지(18:00)는 pod 하나가 아니라 node 전체의 종료다. graceful node shutdown이 켜져 있으면 kubelet은 `shutdownGracePeriod` 안에서 일반 pod를 먼저 종료한다. 이때 pod에 주어지는 시간은 자기 `terminationGracePeriodSeconds`가 아니라 node 종료 예산으로 제한될 수 있다. 일반 pod 몫(`shutdownGracePeriod - shutdownGracePeriodCriticalPods`)을 앱 pod의 drain 예산보다 길게 잡는다. instance 중지 때 OS가 그만큼 기다려 주는지는 실제 중지로 확인한다. 기다려 주지 않으면 그 결과를 기록하고, 끝내지 못한 처리가 Kafka 재전달과 Redis pending claim으로 복구되는지 확인한다.

### 완료 증거

- 앱 pod에 `SIGTERM`을 보내면 새 요청을 거절하고 in-flight 요청과 consumer 처리를 마친 뒤 grace period 안에 종료한다.
- rolling update 동안 새 HTTP 요청은 새로 ready가 된 pod로 가고, 기존 요청은 deadline 안에 끝나며, WebSocket은 재연결 후 history gap이 없다.
- Kafka current record와 Redis pending message를 처리 중 종료해도 재할당·claim 뒤 최종 결과가 한 번이고, 종료 과정에서 DataSource closed 오류가 없다.
- 선택한 관측성 UI에 drain 시작·완료·timeout, restart count, forced termination 지표와 동일 correlation ID의 log를 남긴다.

## 5. Grafana observability stack과 부하 테스트

관측성은 **Prometheus + Alloy + Loki + Tempo + Grafana** 한 벌로 고정한다. 애플리케이션과 gateway는 Micrometer와 OpenTelemetry로 계측하고 metrics는 Prometheus, logs는 Loki, traces는 Tempo가 저장한다. Elasticsearch는 검색·Vector DB로만 사용한다.

같은 k6 시나리오로 ingest CPU·메모리·disk/day, 질의 p95, dashboard·alert와 trace-log 상관관계를 측정한다. Elasticsearch 관측성을 제외한 근거는 [Elasticsearch 통합 범위 조사](./elasticsearch-consolidation-research.md)를 따른다.

### 수집·저장 구성

비교 실험은 다음을 한 세트로 구성한다.

- Micrometer Prometheus-format endpoint: HTTP, JVM, HikariCP, Kafka lag, Redis pending, HP task 노출
- Prometheus: `/actuator/prometheus`와 infrastructure exporter를 직접 scrape하고 local TSDB·PromQL·rule 평가 담당
- Grafana Alloy: pod log 수집과 OTLP trace 수신·전달 담당. Mimir로 바꿀 때만 metrics scrape·remote write까지 확장
- Loki: JSON log 저장; `traceId`, `correlationId`, `eventId`, 배포 version으로 연결
- Tempo: HTTP → 업무 transaction → Kafka publish/consume → chat delivery trace 저장
- Grafana: Prometheus·Loki·Tempo datasource와 API, Kafka, chat, HP, Kubernetes dashboard·alert
- k6: 일반 API, WebSocket, 돈 흐름 재시도 시나리오

기준선에서는 **Prometheus가 metrics를 직접 scrape**하고, Alloy는 logs·traces를 Loki·Tempo로 전달한다. Grafana는 세 backend를 조회한다. Prometheus remote-write receiver는 공식 문서가 일반 scrape의 대체 수단으로 적합하지 않다고 명시하므로, collector를 하나처럼 보이게 만들기 위해 `Alloy scrape → Prometheus remote write receiver`를 기본 구성으로 쓰지 않는다([Prometheus Remote Write Receiver](https://prometheus.io/docs/prometheus/latest/querying/api/#remote-write-receiver)). Prometheus와 Mimir를 동시에 장기 저장소로 두지 않는다.

### metrics 수집과 저장 — Prometheus vs Mimir

metrics pipeline에는 역할이 셋 있다. Prometheus는 이 중 수집과 저장을 **둘 다** 할 수 있어서 "Alloy vs Prometheus"처럼 보이기 쉽지만, 실제로 고르는 것은 축 두 개다.

| 역할 | 하는 일 | 후보 | 이 문서의 선택 |
| --- | --- | --- | --- |
| metrics 수집 | 앱의 `/actuator/prometheus`와 exporter scrape | Prometheus 자체 scrape, Alloy scrape + remote write | **현재는 Prometheus 직접 scrape** |
| 저장·조회 | 시계열 보관, PromQL 응답, recording/alert rule | Prometheus TSDB, Mimir | 아래 비교 |
| logs·traces 수집 | pod log와 OTLP trace 전달 | Alloy | **Alloy로 고정** |
| 조회 UI | dashboard, alert, trace·log 이동 | Grafana | Grafana로 고정 |

#### 저장소: Prometheus vs Mimir

두 제품 모두 Prometheus ecosystem의 protocol과 PromQL을 사용하므로 Micrometer 계측과 Grafana dashboard는 유지할 수 있다. 차이는 단일 node TSDB와 분산 장기 저장소 중 어느 운영 문제를 풀 것인지다.

| 비교 축 | Prometheus | Grafana Mimir | 이 포트폴리오 판단 |
| --- | --- | --- | --- |
| 수집·조회·저장 model | target scrape, PromQL, rule, local TSDB를 한 server가 담당한다 | 직접 target을 scrape하지 않고 Alloy의 remote write를 받는다. distributor·ingester·querier·compactor 등으로 write/read/storage path를 나누고 TSDB block을 object storage에 둔다([Mimir architecture](https://grafana.com/docs/mimir/latest/get-started/about-grafana-mimir-architecture/)) | 현재는 Prometheus 하나가 metrics pipeline을 소유한다 |
| HA·장기 보존 | local storage는 단일 node의 확장성과 내구성에 제한되고 clustered·replicated storage가 아니다. 기본 보존은 15일이며 time/size limit를 설정한다([Prometheus storage](https://prometheus.io/docs/prometheus/latest/storage/)) | horizontally scalable·highly available한 long-term metrics store가 목표이며 object storage에 block을 보존한다. local filesystem backend는 single-node 전용이다([Mimir 개요](https://grafana.com/docs/mimir/latest/), [Mimir architecture](https://grafana.com/docs/mimir/latest/get-started/about-grafana-mimir-architecture/)) | server 1 + agent 1로는 Mimir의 HA를 실현할 수 없고, 기존 S3는 업로드 전용이라 장기 metrics object storage도 범위 밖이다 |
| 확장·tenancy | local TSDB는 한 server 단위이며 tenant isolation을 제공하지 않는다 | component를 수평 확장하고 tenant별 TSDB와 request tenant ID를 사용한다. shuffle sharding으로 tenant workload의 영향 범위도 제한할 수 있다([Mimir shuffle sharding](https://grafana.com/docs/mimir/latest/configure/configure-shuffle-sharding/)) | 단일 팀·2노드 시연에는 multi-tenancy와 분산 확장이 필요 없다 |
| 운영 복잡도 | server 1개, local volume 1개와 retention 설정으로 끝난다 | monolithic mode도 내부의 여러 component·ring·block compaction·storage 설정을 함께 운영한다. 분산 장점을 내려면 microservices와 object storage까지 필요하다([Mimir deployment modes](https://grafana.com/docs/mimir/latest/references/architecture/deployment-modes/)) | 장애 시연 대상이 아닌 metrics backend에 운영 항목을 늘리지 않는다 |
| 포트폴리오 적합성 | dashboard·alert·PromQL·k6 전후 비교·exemplar 연계에 충분하다 | 다중 tenant, 장기 보존, 대규모 수평 확장 자체를 시연할 때 가치가 있다 | **비교 기준선은 Prometheus 선택** |

따라서 Grafana 관측성 stack의 metrics backend는 **Prometheus 하나만 배포**한다. Prometheus와 Mimir를 동시에 쓰거나, 짧은 보존 Prometheus 앞에 Mimir를 추가하지 않는다. Mimir는 다음 중 하나가 실제 시연 요구가 될 때 Prometheus를 **대체**한다. 이때 Prometheus의 Kubernetes discovery·relabel 규칙을 Alloy `prometheus.scrape`로 옮기고 Mimir로 remote write한다([Alloy metrics 수집 공식 문서](https://grafana.com/docs/alloy/latest/collect/prometheus-metrics/)).

- MinIO 등 object storage에 metrics를 장기 보존하고 compaction·retention을 보여 줄 때
- Mimir를 microservices mode로 띄워 ingester 장애 중에도 수집과 조회가 유지되는 것을 보여 줄 때
- 환경(dev/demo)이나 팀별 tenant 분리와 tenant별 limit를 보여 줄 때

어느 쪽이든 metrics 장기 저장소는 하나만 둔다. 이 구성은 Elastic과 비교하기 위한 단일-node observability 기준선이다.

시연에서는 `traceId`를 구조화 log에 넣어 trace에서 관련 Loki log로 이동하고, log에서 Tempo trace로 돌아오게 한다. 가능하면 latency histogram exemplar에서도 해당 trace로 이동시켜 metrics → trace → logs 한 흐름을 녹화한다.

Promtail은 사용하지 않는다. 2026-03-02에 EOL이 되었고 이후 기능 개발은 Alloy에서 진행된다는 공식 안내가 있다([Loki 공식 문서](https://grafana.com/docs/loki/latest/send-data/promtail/)).

시연용 초기 목표는 다음과 같이 두고 실제 결과를 README에 남긴다.

| 시나리오 | 초기 목표 |
| --- | --- |
| 일반 API | 100 RPS, 10분, p95 500 ms 이하, 5xx 1% 미만 |
| 채팅 | WebSocket 200개, 20 msg/s, 저장→전달 p95 1초 이하 |
| Kafka | consumer 중지 후 재개, backlog 5분 내 회복 |
| Redis Streams | consumer 강제 종료 후 pending 자동 claim |
| 돈 흐름 | timeout·중복 요청에도 원장 불변식 위반 0건 |
| rolling update | HTTP 오류 최소화, WebSocket 재연결 후 history gap 0건 |

이 수치는 제품 SLO가 아니라 포트폴리오 실험의 기준선이다.

EC2에서 부하 test를 할 때는 다음을 지킨다.

- k6는 EC2 밖(개발 PC나 GitHub Actions runner)에서 실행한다. 같은 host에서 k6를 돌리면 부하 생성기가 2 vCPU를 나눠 써서 측정값이 왜곡된다.
- t3 standard credit 모드에서는 credit이 바닥나면 CPU가 기준선으로 제한된다. 각 실행 전후의 CloudWatch `CPUCreditBalance`를 결과와 함께 기록한다. credit 소진 구간의 p95는 코드 성능이 아니라 instance 제한으로 해석한다.
- EC2에는 prod만 있으므로 부하 test는 prod를 대상으로, 팀이 서버를 쓰지 않는 시간대에 하고 미리 공지한다. 결과에는 그 시간 node별 자원 사용량도 함께 남긴다. 반복 실험은 로컬 k3d에서 먼저 한다.
- 위 초기 목표(100 RPS 10분 등)가 2노드에서 유지되지 않으면 목표를 낮추는 것이 아니라 node별 CPU credit, DB connection, GC 병목을 JFR과 dashboard로 설명하는 것을 결과물로 삼는다.

## 6. SonarQube Cloud — CI 품질 gate

**self-hosted SonarQube Server가 아니라 SonarQube Cloud를 도입한다.** 이 프로젝트에서 보여 줄 것은 SonarQube 운영이 아니라 지속적인 code quality 통제이므로 cluster에 서버를 하나 더 올리지 않는다.

- GitHub Actions에서 test → JaCoCo XML report → SonarScanner 순서로 실행한다.
- `dev`와 PR의 quality gate를 required check로 연결한다.
- 처음에는 내장 `Sonar way`를 사용하고, 임의의 전체 coverage 숫자 대신 **new code** 기준으로 버그·취약점·중복·coverage 악화를 막는다.
- false positive를 숨기지 않고 `Won't Fix`/예외 사유를 PR이나 ADR에 남긴다.
- multi-module별 결과와 aggregate coverage가 의도대로 들어오는지 검증한다.

SonarQube Cloud는 GitHub Actions 분석, PR decoration, quality gate 실패 시 job/merge 차단을 지원한다([공식 GitHub integration 문서](https://docs.sonarsource.com/sonarqube-cloud/discovering-sonarcloud/integration-with-devops-platforms/github)). Coverage 자체를 계산하지는 않으므로 JaCoCo가 report를 먼저 만들고 scanner가 import해야 한다([공식 coverage 문서](https://docs.sonarsource.com/sonarqube-cloud/analyzing-source-code/test-coverage/overview)). 현재 프로젝트의 Java 25·Gradle 조합이 분석 시점의 지원 범위에 포함되는지는 실제 scanner spike로 먼저 확인하고 결과를 ADR에 기록한다.

## 7. 추가할 가치가 있는 포트폴리오 항목

아래 순서까지만 추가한다. 핵심 흐름의 장애·복구 test가 완성되기 전에 stretch 항목부터 넣지 않는다.

| 우선순위 | 항목 | 결정 | 보여 줄 증거 |
| --- | --- | --- | --- |
| Must | Testcontainers + Toxiproxy | 도입 | 실제 PostgreSQL·Kafka·Redis 통합 test와 지연·단절·복구 |
| Must | 모듈별 Gradle test fixtures | 도입 | test data builder·fake의 소유권과 소비 관계 |
| Must | Kafka event schema + contract compatibility | 도입 (레포 schema 파일, CI 검사. registry 서버 없음) | breaking schema 차단, version별 샘플 contract test |
| Must | OpenAPI·AsyncAPI contract CI | 도입 | HTTP/event 계약의 문서화와 호환성 검증 |
| Must | Spring AI + Elasticsearch hybrid RAG | 고객센터에 도입 | BM25/vector/RRF 비교, 근거 검색·출처 반환·답변 거절과 RAG 평가 |
| Must | JFR + JDK Mission Control | 도입 | k6 부하 중 CPU·allocation·GC·lock 근거와 전후 비교 |
| Must | Terraform 현행 AWS 구성 | import-first로 도입 | 실제 배포 자원의 구성과 drift 검증 |

### Gradle `java-test-fixtures` 적용 범위

현재 `:test-support`는 단순 fixture 모음이 아니다. 9개 class가 `IntegrationTest`, `RepositoryTest`, DB 정리, 로그인 helper, PostgreSQL/S3 Testcontainers 설정을 제공하고, `apps`, `global`, 6개 업무 모듈에 걸친 약 165개 test가 이를 사용한다. 조사 시점의 직접 사용은 `IntegrationTest` 약 92개, `RepositoryTest` 약 20개, `TestLogin` 약 49개, PostgreSQL 설정 약 24개, S3Mock 설정 약 6개다. 이를 없애면 같은 annotation·container lifecycle·cleanup을 각 모듈로 복사하거나 다른 운영 모듈의 test output에 얹어야 한다.

따라서 **`:test-support`는 제거하지 않는다.** `global`의 `testFixtures`로 옮기는 것은 기술적으로 가능하지만 모든 모듈이 `global`의 test variant에 의존하게 되어 global의 책임을 오염시키고 project 수도 줄지 않는다. 별도 module이 공통 test infrastructure의 소유권을 가장 정확히 나타낸다. 반면 회원·출연·강의·지갑·채팅의 domain fixture는 각 모듈 `src/test/java/.../fixture` 아래에 있어 일반 test class와 재사용 가능한 test data builder의 경계가 불분명하므로 이 부분만 옮긴다.

둘을 한곳으로 합치지 않고 다음처럼 나눈다.

- `:test-support` 유지: Spring test annotation, Testcontainers lifecycle, DB cleanup처럼 **도메인 중립적인 test infrastructure**만 소유한다.
- 각 업무 모듈에 `java-test-fixtures` 적용: 현재 `fixture` package의 data builder, object mother, fake server·seeder를 `src/testFixtures/java`로 옮긴다.
- 모듈 자신의 `src/test`는 별도 설정 없이 자기 fixture를 사용한다.
- 다른 프로젝트가 실제로 필요할 때만 `testImplementation(testFixtures(project(":billing")))`처럼 명시한다. 모든 모듈 fixture를 전역 주입하지 않는다.
- `apps`의 모듈 간 E2E는 다른 모듈 entity fixture를 직접 조립하지 않고 public API·event·ID로 준비한다. test fixture가 운영 모듈 경계를 우회하는 통로가 되면 안 된다.
- `AppearanceIntegrationTest`, clock wiring, MockMvc base class처럼 한 모듈의 test 실행 방식에만 필요한 support는 공유 소비자가 생기기 전까지 `src/test`에 둔다.

Gradle 공식 plugin은 `src/testFixtures` source set과 별도 consumable variant를 만들고, 해당 프로젝트의 main component에는 접근하게 하면서 다른 프로젝트는 `testFixtures(project(...))`로 명시적으로 소비하게 한다. 또한 `testFixturesApi`와 `testFixturesImplementation`을 나눠 fixture의 구현 dependency가 소비자 compile classpath에 새지 않게 한다([Gradle 공식 문서](https://docs.gradle.org/current/userguide/java_test_fixtures_plugin.html)).

완료 기준은 `:test-support`의 9개 공통 infrastructure class는 유지되고, 업무 모듈의 builder·object mother·fake만 `src/testFixtures`로 이동하며, `apps` E2E가 다른 모듈의 entity fixture를 새로 직접 참조하지 않는 것이다.

### Testcontainers + Toxiproxy

repository·serialization·멱등 consumer test는 mock broker가 아니라 Testcontainers의 PostgreSQL, Kafka, Redis를 사용한다. Toxiproxy를 application과 dependency 사이에 두고 latency, timeout, connection cut을 test code에서 재현한다. Testcontainers 공식 Toxiproxy module도 이 구성을 client resilience와 multi-container 장애 시험 용도로 제공한다([공식 문서](https://java.testcontainers.org/modules/toxiproxy/)).

최소 장애 test matrix는 다음과 같다.

- Kafka publish 응답 유실 → Modulith publication 재발행 → consumer 결과 한 번
- Kafka consumer 처리 중 connection cut → retry/DLT → inbox 중복 0건
- Redis `XACK` 직전 worker 종료 → pending claim → 채팅 DB 중복 0건
- PostgreSQL commit 응답 timeout → 같은 command 재시도 → HP 원장 불변식 유지

### Spring AI + Elasticsearch — 고객센터 근거 기반 답변

AI를 전 도메인에 퍼뜨리지 않고 **고객센터의 공개 지식 질의응답** 한 가지 use case에만 적용한다. 검색에 필수인 Elasticsearch를 RAG vector store로도 사용해 pgvector와 별도 vector DB를 제거한다. `customersupport` 모듈은 자기 index namespace와 Elasticsearch role/API key를 소유하며, `search` 모듈의 repository나 index를 우회해서 사용하지 않는다. 물리 cluster만 공유하고 index 소유권은 bounded context별로 분리한다.

#### 지식과 경계

- 원본(source of truth): 고객센터의 게시된 FAQ·공지와 검토가 끝난 서비스 정책 문서
- 검색 projection: 원본을 chunk로 나눈 content, nori 분석 field, embedding, 검색·출처용 metadata
- 1차 색인 제외: 개인 문의·답변, 계좌·결제 정보, 비공개 관리자 메모. 개인 정보와 권한이 섞인 corpus로 시작하지 않는다.
- metadata: `sourceType`, `sourceId`, `version`, `visibility`, `published`, `category`, `sourceUrl`, `updatedAt`
- 모든 검색에 서버가 `published == true && visibility == 'PUBLIC'` filter를 강제한다. client가 보낸 filter 문자열을 그대로 실행하지 않는다.

Spring AI의 `ElasticsearchVectorStore`로 vector 저장·기본 검색을 처리한다. nori BM25와 dense vector 결과를 RRF로 결합하는 hybrid retriever는 Elasticsearch native client를 사용하는 고객센터 adapter 안에 캡슐화한다. schema 자동 생성은 사용하지 않고 versioned index template·mapping·사용자 사전을 레포에서 관리한다. embedding dimension이나 analyzer가 바뀌면 새 index를 만든 뒤 검증하고 alias를 교체한다([Spring AI Elasticsearch Vector Store](https://docs.spring.io/spring-ai/reference/api/vectordbs/elasticsearch.html)).

#### 색인 흐름

1. FAQ·공지·정책이 게시·수정·게시 취소되면 고객센터 application event를 발행한다.
2. 같은 고객센터 모듈의 indexer가 원본을 Spring AI `Document`로 바꾸고 source metadata를 붙인다.
3. `TokenTextSplitter`로 chunk를 만들고 고객센터 소유 Elasticsearch index에 기록한다.
4. `sourceType + sourceId + version`을 idempotency 기준으로 삼아 기존 chunk를 교체한다. 재색인 전체 rebuild도 가능해야 한다.
5. 색인 실패는 원본 transaction을 되돌리지 않고 재시도 대상으로 기록한다. vector store는 언제든 원본에서 다시 만들 수 있는 projection이다.

Spring AI의 ETL model은 `DocumentReader → DocumentTransformer → DocumentWriter`이며 `VectorStore`가 writer 역할을 할 수 있다. 따라서 문서 읽기·chunking·저장과 Elasticsearch 고유 hybrid query를 고객센터 use case 뒤의 adapter로 캡슐화한다([Spring AI ETL 공식 문서](https://docs.spring.io/spring-ai/reference/api/etl-pipeline.html)).

#### 답변 흐름과 API 계약

1. 인증 여부와 무관하게 공개 지식만 검색하되 abuse 방지를 위한 rate limit을 적용한다.
2. 고객센터 hybrid retriever가 nori BM25와 dense vector를 실행하고 RRF로 합친 뒤 metadata filter를 적용한다.
3. 검색 context가 없거나 threshold보다 낮으면 추측하지 않고 “근거를 찾지 못했다”고 답하며 문의 등록 경로를 안내한다.
4. 답변 본문과 함께 `sources: [{sourceType, sourceId, title, sourceUrl, updatedAt}]`를 반환한다.
5. `sources`는 LLM이 만든 URL을 parsing하지 않고 advisor context의 실제 retrieved `Document` metadata로 조립한다.

`RetrievalAugmentationAdvisor`는 metadata filter, query transformer, 검색 결과 post-processor를 조합할 수 있고, 기본적으로 검색 context가 비면 답하지 않는 흐름을 지원한다([Spring AI RAG 공식 문서](https://docs.spring.io/spring-ai/reference/api/retrieval-augmented-generation.html)). `ChatClientResponse`의 advisor execution context에서는 RAG가 검색한 문서를 포함한 context를 얻을 수 있으므로 출처는 이 값으로 만든다([Spring AI ChatClient 공식 문서](https://docs.spring.io/spring-ai/reference/api/chatclient.html)). 단순 spike는 `QuestionAnswerAdvisor`로 시작할 수 있지만 최종 구조는 filter와 post-processing seam이 명확한 `RetrievalAugmentationAdvisor`로 통일한다.

업무 code가 `ChatClient`, `VectorStore`, 특정 model provider SDK를 직접 알지 않게 다음 interface 뒤에 둔다.

- `SupportKnowledgeIndexer`: 고객센터 원본의 색인·재색인·제거
- `SupportAnswerGenerator`: 질문을 받아 답변과 검색된 source를 반환
- Spring AI/Elasticsearch adapter: embedding, hybrid retrieval, prompt, model 호출 구현

model과 embedding provider는 설정으로 교체할 수 있게 하고 API key는 secret으로만 주입한다. LLM이 지급·환불·환전 같은 command를 실행하는 tool calling은 이 범위에 넣지 않는다.

#### 완료 증거

- 정답 근거가 있는 golden question set에서 retrieval hit@k와 답변 relevance를 기록한다.
- 근거가 없는 질문, 미게시·비공개 문서 질문에는 답변을 생성하지 않는 test를 둔다.
- FAQ 수정·게시 취소 후 이전 version chunk가 검색되지 않는 것을 검증한다.
- API의 모든 `sources`가 실제 검색 문서 metadata와 일치하는지 검증한다.
- prompt injection 문구가 포함된 문서를 넣어도 공개 범위 filter와 “문서는 명령이 아니라 근거”라는 system policy가 유지되는지 시험한다.

Spring AI는 생성된 응답이 제공된 context와 관련 있는지 평가하는 `RelevancyEvaluator`를 제공한다. 이 점수 하나를 정답 판정으로 쓰지는 않고 golden set에서 nori BM25, dense vector, RRF hybrid의 hit@k·MRR과 답변 거절 test를 함께 기록한다([Spring AI testing 공식 문서](https://docs.spring.io/spring-ai/reference/api/testing.html)).

#### vector 저장소 선택 기록

Spring AI는 `VectorStore` interface 뒤에 여러 구현을 제공한다([Spring AI Vector Databases](https://docs.spring.io/spring-ai/reference/api/vectordbs.html)). 업무 code는 `SupportKnowledgeIndexer`·`SupportAnswerGenerator`만 알기 때문에 아래 중 어느 것으로 바꿔도 adapter 설정만 바뀐다. 비교 기준은 2노드의 node별 메모리 예산, S3 사용 범위, 원본(FAQ·공지)과의 동기화 비용이다.

| 대안 | 형태 | 장점 | 이 프로젝트에서의 한계 |
| --- | --- | --- | --- |
| pgvector | 기존 PostgreSQL extension | 추가 process 없음. 원본과 같은 DB라 backup·권한·Flyway를 함께 관리. SQL·JSON metadata filter | Elasticsearch가 필수인 최종안에서는 vector backend가 중복된다. PostgreSQL 업무 부하와도 CPU·메모리를 경쟁한다 |
| Qdrant | 전용 vector DB (별도 server) | payload filter와 vector 검색이 전용 엔진에 최적화. Spring AI 지원 | stateful workload와 backup 대상이 하나 더 는다. 원본과 다른 저장소라 색인 동기화 실패를 따로 다뤄야 한다 |
| Redis (Query Engine) | 채팅용으로 들이는 Redis를 재사용 | 새 server 없이 vector 검색. 응답이 빠르다 | Spring AI Redis Vector Store는 Redis Stack(Search and Query)을 전제로 한다([문서](https://docs.spring.io/spring-ai/reference/api/vectordbs/redis.html)). vector가 메모리에 상주하고, 채팅 전달용 Redis와 영속성·eviction 정책이 섞인다 |
| **Elasticsearch (선택)** | 9장의 물리 cluster에 고객센터 소유 index 추가 | BM25와 kNN hybrid 검색, nori 한국어 분석. 새 server가 늘지 않음 | 일반 검색과 RAG가 자원을 공유하므로 index·권한 분리와 동시 부하 검증이 필요하다 |
| Amazon S3 Vectors | AWS 관리형 vector bucket | 서버 없이 사용량만큼 과금. metadata filter. Spring AI S3 Vector Store 지원 | 새 bucket 종류와 `s3vectors` IAM namespace를 쓴다([AWS 문서](https://docs.aws.amazon.com/AmazonS3/latest/userguide/s3-vectors.html)). "S3 버킷 1개, 업로드 용도" 범위를 넘으므로 팀 합의가 필요하다. 조직 SCP 허용 여부와 서울 region 지원을 먼저 확인한다. 자주 쓰지 않는 query는 지연이 1초 가까이 될 수 있다 |
| PostgreSQL 전문 검색만 | vector 없이 `tsvector` | embedding API 호출과 비용 없음. 가장 단순한 기준선 | 기본 설정에는 한국어 사전이 없어 형태소 단위 검색이 약하다. 의미가 같은 다른 표현을 찾지 못한다 |
| Spring AI `SimpleVectorStore` | 앱 메모리 | 의존성 없음 | 공식 문서가 test·demo 전용이라고 명시한다. 단위 test의 fake로만 쓴다 |

Chroma, Weaviate, Milvus도 Spring AI가 지원한다. 그러나 Qdrant와 같은 별도 stateful server라는 한계가 있고, Milvus는 etcd·object storage까지 필요해 EC2 예산에 맞지 않는다.

판단: **Elasticsearch를 Vector DB로 선택한다.** 검색용으로 반드시 배포할 process를 고객센터 RAG도 물리적으로 공유하므로 pgvector·Qdrant·Redis Vector를 함께 운영하지 않는다. 위 대안 표는 선택 과정의 기록이며 구현 후보 목록이 아니다. Elasticsearch에는 검색·RAG index만 두고 telemetry는 저장하지 않는다.

### Kafka schema와 API/event contract

schema registry 서버(Apicurio, Confluent 등)는 **두지 않는다.** 앱이 따로 배포되므로(#363) version이 다른 producer와 consumer가 꽤 오래 함께 돌 수 있다. 예를 들어 billing-app만 먼저 배포된 경우다. 이 문제는 runtime registry 대신 아래 두 가지로 막는다.

- 레포 schema 파일과 CI 호환성 검사가 breaking 변경을 PR 단계에서 막는다.
- **배포 순서 규칙**: `eventVersion`을 올리는 변경은 consumer 앱(upcaster 포함)을 먼저 배포하고, 그 뒤에 producer 앱을 배포한다.

registry 서버는 메모리 예산(3장)에 들어가지 않고, 위 두 가지가 막지 못하는 문제가 아직 없다.

대신 schema를 **레포의 파일**로 관리하고 **CI에서 호환성을 검사**한다.

- 이벤트 payload는 JSON Schema 파일로 `shared`에 둔다. 예: `shared/src/main/resources/events/lecture/LectureOrderConfirmed.v1.json`. 이벤트 record와 schema가 어긋나지 않게, record에서 schema를 생성해 커밋된 파일과 비교하는 test를 둔다.
- 메시지는 schema ID 없이 일반 JSON으로 직렬화한다. header에 `eventType`과 `eventVersion`을 싣는다.
- **호환 규칙**: 같은 `eventVersion` 안에서는 필드 추가(선택 필드)만 허용한다. 삭제, 이름 변경, 타입 변경, 필수 필드 추가는 금지한다. 그런 변경은 `eventVersion`을 올리고, consumer는 upcaster로 이전 version을 새 version으로 바꿔 읽는다.
- **CI 검사**: AsyncAPI 문서가 topic과 message payload schema를 함께 담는다. CI에서 `asyncapi validate`와 `asyncapi diff`로 `dev` 기준 문서와 비교하고, breaking 변경이면 실패시킨다([AsyncAPI CLI 공식 문서](https://www.asyncapi.com/docs/tools/cli/usage)).
- **transitive 보장**: diff는 직전 version과만 비교한다. 그래서 version별 **샘플 payload**를 레포에 남기고, 현재 consumer가 모든 이전 version 샘플을 읽을 수 있는지 contract test로 확인한다. registry의 `FULL_TRANSITIVE` 검사를 이 test가 대신한다.

계약은 두 층으로 나눈다.

- OpenAPI: 외부 HTTP endpoint, request/response, `ProblemDetail`, `RsData`
- AsyncAPI: Kafka topic/channel, message header, payload JSON Schema, producer/consumer 관계

CI는 생성된 OpenAPI를 `dev` 기준 spec과 비교하고 backward-incompatible 변경이면 실패시킨다. `openapi-diff`는 `--fail-on-incompatible` 검사를 제공한다([공식 저장소](https://github.com/OpenAPITools/openapi-diff)). schema 변경 PR에서는 old producer/new consumer와 new producer/old consumer contract test도 실행한다.

registry는 배포 순서 규칙을 지킬 수 없게 될 때 다시 검토한다. 예를 들어 같은 event를 소비하는 앱이 많아져 순서를 맞추기 어려워질 때다. 이 경우 broker를 schema registry가 내장된 Redpanda로 바꾸는 방안도 함께 비교한다(1장).

### JFR + JDK Mission Control

JVM 성능 증거의 표준은 **JFR recording 파일**로 정한다. k6 기준선과 장애 scenario 실행 때 app JVM에 저부하 continuous recording을 켜고, 문제가 발생한 시간 구간을 `.jfr` artifact로 dump한다. JDK Mission Control(JMC)에서 같은 파일을 열어 CPU hot method, allocation, GC pause, thread park·lock 경합을 분석하고 변경 전·후 screenshot과 결론을 남긴다. JFR은 JVM event를 timestamp와 함께 보존해 사후 분석할 수 있고 continuous recording은 오래된 데이터를 버리는 bounded buffer로 운용할 수 있다. Oracle 문서도 default continuous recording을 저부하 진단 용도로 설명한다([Java 25 Troubleshooting Guide](https://docs.oracle.com/en/java/javase/25/troubleshoot/troubleshooting-guide.pdf)). JMC는 JFR 데이터를 시각화하는 공식 분석 도구다([JDK Mission Control](https://docs.oracle.com/en/java/java-components/jdk-mission-control/index.html)).

시연 산출물은 다음으로 고정한다.

- 같은 k6 scenario의 baseline/after `.jfr` 두 개
- JMC automated analysis와 hot method·allocation·GC·lock 화면
- p95 변화와 JFR 원인을 연결한 짧은 performance report
- pod 종료 직전 또는 오류율 급등 구간을 `jcmd <pid> JFR.dump`로 추출하는 runbook

현재 runtime image는 `eclipse-temurin:25-jre`라서 `jcmd`를 전제로 하지 않는다. 기본 image는 그대로 유지하고, JFR demo profile에서만 JDK 기반 diagnostic image를 사용해 `jcmd` dump를 지원하거나 JVM 시작 option으로 duration·destination이 정해진 recording을 만든다. profile용 편의를 production image 크기 증가로 바꾸지 않는다.

JConsole과 VisualVM은 채택 stack이 아니라 **로컬 수동 진단 대안**으로만 기록한다. JConsole은 JMX 기반 live monitoring·MBean 조작 도구이고 local monitoring도 관찰 대상에 부하를 줄 수 있다([Java 25 JConsole 문서](https://docs.oracle.com/en/java/javase/25/management/using-jconsole.html)). VisualVM은 live CPU·heap·thread 관찰과 sampler, thread/heap dump에 편리하지만 사람이 붙어 있어야 한다([VisualVM 공식 기능 문서](https://visualvm.github.io/features.html)). 둘은 CI artifact와 동일 시간 구간의 재분석이 가능한 JFR을 대체하지 않는다. 이를 위해 Kubernetes에 원격 JMX port를 별도로 공개하지 않는다.

### Terraform — 현재 배포된 AWS만 코드화

Terraform으로 미래 reference architecture를 새로 설계하지 않는다. **현재 AWS에 실제 존재하며 합방 배포에 사용 중인 resource를 먼저 inventory하고 import한 뒤, 그 상태만 HCL로 옮긴다.** `aws-usage.md`로 확인한 현재 구성은 다음과 같다.
- EC2 2대(t3.large·t3.medium), Elastic IP 2개, 각 EBS·network interface, key pair
- 프로젝트 전용 VPC, public subnet 1개, Internet Gateway, route table
- security group 1개
- S3 bucket 1개
- EC2 instance role·instance profile과 S3 inline policy

모두 `Team=devcos-team08` tag가 붙어 있다. EC2 안의 k3s와 Kubernetes resource, Docker Compose, PostgreSQL volume, edge nginx, 인증서는 Terraform 대상이 아니다.

코드화 범위는 다음 원칙으로 확정한다.

- EC2: 두 instance의 실제 AMI, instance type, credit specification, root volume(gp3 50 GB), key pair, metadata option과 tag를 import 결과 그대로 표현한다. EIP association도 둘 다 import한다.
- security group: 현재 public `22`·`80`·`443`과 node 간 TCP 6443, UDP 8472, TCP 10250 self-reference rule, 실제 outbound rule을 그대로 표현한다. zero-change plan 뒤 edge rule을 large에만 적용하는 구조는 별도 PR로 전환한다.
- network: 프로젝트 전용으로 만든 VPC, public subnet, Internet Gateway, route table을 import한다. 다른 팀과 같이 쓰는 resource가 있으면 `data` source로 참조한다. private subnet·NAT는 지금 없으므로 만들지 않는다.
- S3: 실제 bucket의 서울 region, public access block, object ownership(ACL 비활성), SSE-S3 기본 암호화, CORS를 표현한다. 현재 없는 versioning·lifecycle·bucket policy는 추가하지 않는다.
- IAM: 현재 large에 연결된 EC2 role·instance profile과 S3 policy를 먼저 import한다. zero-change plan 뒤 application replica가 medium에서도 S3를 사용할 수 있도록 같은 instance profile을 medium에 연결하는 변경을 별도 plan으로 검토한다. access key는 만들지 않는다.
- 09:00~18:00 자동 시작·중지는 실제로 운영 중이지만 구현 방식(EventBridge Scheduler, Instance Scheduler 등)과 소유자가 문서에 없다. inventory로 찾아 이 프로젝트 소유일 때만 import한다. 계정 공용 도구라면 참조만 하고, 새 Lambda·EventBridge를 만들지 않는다.

GitHub ruleset, repository secret, k3s·k3d, Kubernetes manifest, Kafka, Redis, observability stack은 이 Terraform root에 넣지 않는다. GitHub Actions는 Terraform이 관리하는 실제 배포 infrastructure가 아니고, cluster 내부 resource는 `k8s/` manifest가 소유하기 때문이다. RDS, EKS, NAT Gateway, 다중 AZ, 새 VPC 같은 미배포 resource도 만들지 않는다.

진행 순서는 `AWS inventory와 tag 확인 → 민감 값 없는 import block 작성 → terraform plan 결과가 변경 0인지 확인 → HCL 정리 → CI의 fmt/validate/plan 검증`이다. 첫 완료 조건은 새 resource 생성이 아니라 **기존 환경에 대한 zero-change plan**이다. 이후 실제 AWS를 바꿀 때만 PR의 plan을 검토하고 apply 여부를 별도로 결정한다. 민감한 account ID, bucket 이름, instance ID, public IP는 variable/state backend에서 다루고 문서와 repository output에 노출하지 않는다.

### 지금 넣지 않는 것

- **Debezium**: 현재 Spring Modulith publication/outbox externalization과 같은 DB→Kafka 전달 책임을 하나 더 만든다. CDC 자체를 비교하는 별도 실험이 아니라면 제외한다.
- **Spring Cloud Gateway, k3s 내장 Traefik**: Gateway 책임은 Envoy Gateway가 맡는다(3장 "API Gateway").
- **nginx(edge nginx, Ingress NGINX controller)**: 진입점은 Envoy Gateway 하나다. Ingress NGINX controller는 2026년 3월에 은퇴해 보안 수정도 나오지 않는다.
- **Prometheus 장기 저장 + Mimir 장기 저장, Elastic Observability 추가**: 같은 telemetry signal의 backend를 두 벌 운영하지 않는다.
- **Pyroscope**: 이 포트폴리오에서는 JFR/JMC가 JVM code-level profiling 증거를 담당한다. 별도 continuous-profile backend까지 더하지 않는다.
- **JConsole·VisualVM 상시 운영**: 필요할 때 로컬에서 쓰는 수동 도구일 뿐 배포 component나 포트폴리오 증거 pipeline으로 만들지 않는다.
- **Bitnami 차트와 추가 operator**(Strimzi, CloudNativePG, redis-operator): Bitnami는 무료 이미지에 보안 패치가 없고, operator는 상시 메모리를 쓰는데 instance 1개 구성에서 얻는 것이 적다. 쓰는 차트는 3장 "manifest 관리"의 표로 정한다.
- **Terraform Kubernetes provider**: in-cluster resource는 `k8s/` manifest가 소유한다.
- **service mesh, Vault, 다중 Kubernetes cluster**: 현재 시연할 통신·secret rotation·multi-cluster 문제가 없어 stack 수만 늘어난다.
- **AWS 관리형 대체**(EKS, RDS, ElastiCache, MSK, ELB·ACM, ECR, Amazon Managed Prometheus·Grafana, OpenSearch Service, Bedrock): AGENTS.md 8장의 EC2 2대·VPC 1개·S3 1개 범위를 넘는다. "실운영이라면 무엇으로 바꿀지"는 11장과 포트폴리오 ADR로만 남긴다.
- **EC2 instance 상향·추가**: t3.large + t3.medium이 상한이다. node별 자원 예산을 넘으면 상시 profile을 줄이고, 그래도 부족하면 팀과 운영진에게 먼저 묻는다.

## 8. 알림(notification) 모듈 분리

### 현재 상태와 문제

지금 알림은 채팅 모듈 안에 있다(#229, #232). 출연 이벤트를 받으면 `ChatNotificationUseCase`가 행위자와 받는 사람의 1:1 채팅방에 `NOTIFICATION` 종류의 채팅 메시지를 남기고 STOMP로 보낸다. 이 구조에는 한계가 있다.

- **받을 수 있는 사람이 채팅 가능 회원으로 제한된다.** 두 사람 중 채팅할 수 없는 회원(일반 유저, 정지·탈퇴)이 있으면 알림을 남기지 않는다. 일반 유저는 강의 구매자인데, 구매 확정·구매 취소·충전·환불 결과 알림을 받을 수 없다.
- **보낸 사람이 있어야 한다.** 알림이 1:1 방의 메시지라서 시스템 알림(지급 완료, 환전 송금 실패, 채널 인증 심사 결과, 문의 답변)을 둘 자리가 없다.
- **알림 때문에 채팅방이 생긴다.** 알림이 오면 방이 없을 때 새로 만들고, 나간 방이면 다시 들어가게 한다. 대화하지 않은 상대와의 방이 알림 때문에 늘어난다.
- **종류를 늘릴수록 채팅 모듈이 다른 모듈 이벤트에 의존한다.** 지금은 출연 2종뿐이지만 강의·지갑·회원·고객센터 이벤트까지 채팅이 들으면 채팅의 책임이 흐려진다.

### 목표

알림을 **독립 bounded context `notification`**으로 분리한다. 채팅은 1:1 대화와 차단만 맡는다.

| 항목 | 내용 |
| --- | --- |
| 앱·database | `notification-app`, database `notification`. 다른 앱은 `common/shared`의 이벤트 계약만 공유한다 |
| API | `/api/v1/notifications`: 목록(cursor), 읽지 않은 수, 읽음 처리, 전체 읽음 |
| 입력 | 각 모듈의 integration event (1장 Kafka). 알림 모듈이 모든 이벤트를 구독하고, 발행하는 모듈은 알림의 존재를 모른다 |
| 저장 | 받는 사람별 알림 행(종류, 참조 ID, 본문에 필요한 값, 읽은 시각, 만든 시각). 원본 이벤트의 `eventId`와 받는 사람으로 유일 인덱스를 두어 중복 소비를 흡수한다 |
| 실시간 전달 | 저장이 commit된 뒤 Redis Pub/Sub으로 발행하고, 사용자의 WebSocket session을 가진 앱이 받아 보낸다. 2장 채팅과 같은 `RealtimeFanout` seam을 쓰되 destination은 알림 전용(`/user/queue/notifications`)이다. 어느 앱이 알림용 WebSocket을 받을지는 아래 "결정 필요" |
| 복구 | 실시간 전달은 유실될 수 있다. 재접속 때 마지막으로 받은 알림 ID 이후를 REST로 다시 읽는다. PostgreSQL 알림 행이 진실의 원천이다 |
| 회원 정보 | 받는 사람의 상태(정지·탈퇴)와 닉네임은 회원 이벤트로 만든 복제본에서 읽는다. 다른 모듈처럼 가입 이벤트로만 만들고 변경 이벤트로 갱신한다 |
| 보존 | 읽은 알림의 보존 기간은 기획서에 없다. 정하기 전에는 지우지 않는다. 지우게 되더라도 Soft Delete 규칙을 따른다 |

알림 문구는 알림 모듈이 만든다. 이벤트에는 문구가 아니라 사실(출연 요청 ID, 제목, 출연료, 일시 등)을 담는다. 이렇게 하면 발행 모듈이 표시 형식을 알 필요가 없다.

### 알림 종류 후보

아래는 용어 정리와 현재 이벤트에서 뽑은 **후보**다. 어떤 종류를 MVP에 넣을지는 기획서를 확인하고 팀이 정한다(AGENTS.md 10장).

| 발행 모듈 | 후보 |
| --- | --- |
| 출연 | 출연 요청 도착·수락·거절, 모집 지원 도착·선택, 출연 취소 요청·승인·거절, 출연 확인, 이의 제기 판정 |
| 강의 | 구매 확정, 구매 취소, 판매 강의의 지급 완료 |
| 지갑·정산 | 충전 완료, 환불 완료, 환전 송금 완료·실패 |
| 회원 | 채널 인증 심사 결과, 승급 심사 결과 |
| 고객센터 | 문의 답변 등록, 공지 |

### 채팅에서 옮기는 방법

1. 알림 모듈이 출연 요청 도착·모집 지원 도착을 먼저 받는다. 이 기간에는 채팅 알림도 그대로 둔다.
2. 프론트가 알림 API와 알림 destination으로 바꾼 뒤, 채팅의 `AppearanceNotificationEventListener`를 제거한다.
3. 이미 남은 `NOTIFICATION` 채팅 메시지는 대화 기록이므로 지우지 않는다. 새 알림만 채팅에 남지 않는다.

채팅 자체의 "새 메시지 도착" 표시(`notifyNewMessage`)는 채팅의 일이므로 채팅에 남긴다. 이것까지 알림 수신함에 넣을지는 프론트와 정한다.

### 배포 단위

처음부터 **별도 앱 `notification-app`**으로 만든다(앱 1개 = bounded context 1개). 알림은 이벤트만 받고 다른 앱을 동기 호출하지 않으므로 독립 배포가 가장 쉽다. 3장 예산에서 medium의 앱(384 MiB)으로 잡았고, chat-app과 같은 node에 둔다. 메모리가 모자라면 시연할 때만 띄운다.

### 결정 필요

- AGENTS.md 3장 모듈 표에서 채팅의 책임이 "1:1 대화, 알림, 차단"이다. 알림을 떼어 내려면 이 표를 바꾸는 팀 합의가 필요하다.
- 알림 종류(위 후보), 읽은 알림 보존 기간, 일반 유저도 알림을 받는지(현재는 못 받음), 이메일 등 앱 밖 채널 여부
- 알림용 WebSocket을 어느 앱이 받을지: chat-app의 연결을 재사용해 chat-app이 Redis Pub/Sub 알림을 전달할지, notification-app이 자체 STOMP endpoint를 둘지(프론트 연결이 2개가 됨)

### 완료 증거

- 일반 유저 계정이 강의 구매 확정·충전 완료 알림을 받는다.
- 같은 integration event를 두 번 소비해도 알림은 한 번만 생긴다.
- 실시간 알림이 WebSocket으로 도착한다. 실시간 전달을 일부러 유실시켜도 재접속 후 목록이 같다.
- notification-app을 끈 동안 쌓인 이벤트가 재시작 후 consumer lag가 줄면서 모두 알림이 된다.
- 채팅 모듈에 다른 모듈의 이벤트 listener가 남지 않는다.

## 9. 검색(search) 모듈과 Elasticsearch (#234)

### 현재 상태

지금 검색은 모듈마다 PostgreSQL에서 직접 조회한다.
- 강의: 제목 `LIKE`(`LectureRepository.findOnSaleOrderByRating`)
- 고객센터: 내 문의 목록 keyword
- 출연: 모집글 검색·필터는 아직 TODO

모듈 간 테이블 JOIN이 금지라 강의·모집글·인플루언서를 한 번에 찾는 통합 검색은 쿼리 하나로 만들 수 없다. `LIKE`는 한국어 형태소를 모르고 relevance 정렬도 없다.

### 목표

검색을 **독립 bounded context `search`**로 두고, 검색 엔진은 **Elasticsearch**로 한다. OpenSearch 등 다른 엔진은 비교하지 않는다(팀 결정).

| 항목 | 내용 |
| --- | --- |
| 앱 | `search-app`, database `search`. `search`는 통합 검색 index만 소유한다. 고객센터(`customer-support-app`)는 RAG index를 직접 소유하며, 두 앱은 물리 cluster만 공유한다 |
| 입력 | 1장 Kafka의 integration event. 강의 등록·수정·삭제·판매 상태 변경, 모집글 등록·수정·마감, 인플루언서 프로필 변경 등. 발행하는 모듈은 검색의 존재를 모른다 |
| 진실의 원천 | 각 모듈의 PostgreSQL. ES index는 언제든 원본에서 다시 만들 수 있는 projection이다 |
| API | `GET /api/v1/search?type=LECTURE\|RECRUIT\|INFLUENCER&keyword=...`(통합 검색, 페이지). 자동완성·추천은 범위 밖 |
| 멱등 | document ID = 원본 ID, 이벤트의 aggregate version으로 오래된 이벤트가 새 문서를 덮지 않게 한다(external versioning). 같은 이벤트를 두 번 받아도 결과가 같다 |
| 공개 범위 | 판매 중인 강의, 공개된 모집글 등 비회원도 볼 수 있는 것만 색인한다. 개인 정보와 HP 금액은 넣지 않는다 |

### 한국어 분석

nori 형태소 분석기(`analysis-nori` plugin)를 쓴다([nori 문서](https://www.elastic.co/docs/reference/elasticsearch/plugins/analysis-nori)). 이미지에 plugin을 넣은 custom image를 GHCR에 올리고, 실행 중에 설치하지 않는다.

2026-10-22 측정에서 기본 사전은 "인플루언서 강의를 구매했습니다"의 "인플루언서"를 `인`·`플`·`언서`로 쪼갰다. 서비스 용어(인플루언서, 합방, 치지직, 출연 모집 등)는 **사용자 사전**에 넣고, 사전을 바꾸면 재색인한다. 사용자 사전과 동의어 목록은 레포에서 관리한다.

### 색인과 재색인

1. **초기 적재**: 각 모듈의 공개 조회 API(또는 내부 API, D62)를 page 단위로 읽어 새 index에 bulk로 넣는다. 다른 모듈의 DB를 직접 읽지 않는다. 실행 방식은 아직 정하지 않았다.
2. **실시간 반영**: Kafka consumer가 이벤트마다 문서를 upsert·delete한다. inbox로 멱등 처리하고, 실패는 retry와 DLT로 보낸다.
3. **재색인**: 사전이나 mapping을 바꾸면 versioned index(`lectures-v2`)를 새로 만들어 초기 적재를 다시 돌린다. 그동안 들어온 이벤트는 두 index에 모두 쓰고, 검증한 뒤 alias를 원자적으로 바꾼다. 이전 index는 확인한 뒤 지운다.
4. **정합성 점검**: 원본 수와 index 문서 수를 비교하는 주기 작업을 두고, 차이가 나면 선택한 관측성 UI에 alert한다.

### 운영

- **배치**: `hapbang-platform` namespace에 ECK의 Elasticsearch CR로 만든다. node 1개(`nodeSets[].count: 1`), replica shard 0. 단일 node라 HA는 없다고 README에 적는다.
- **메모리**: 3장 예산대로 컨테이너 limit 1.5 GiB(heap은 limit의 50% 이하). ECK 기본값 2Gi를 그대로 두지 않는다. 대기 상태 측정으로는 1 GiB(heap 512 MiB)도 떴으므로 부하 측정 뒤 줄일 수 있다.
- **host 설정**: host에 `vm.max_map_count=262144`를 설정한다(k3s 설치 때 sysctl로 고정).
- **보안**: security를 켜 두고 앱 계정 비밀번호는 Secret으로 넣는다. 9200 port는 security group과 Gateway route에 열지 않고, NetworkPolicy로 search-app과 customer-support-app pod만 접근하게 한다.
- **환경**: EC2에는 prod만 두므로 ES도 하나다. index 이름에 환경 prefix를 붙이지 않는다.
- **백업**: S3 snapshot은 S3 용도를 넘으므로 하지 않는다. 원본에서 다시 만들 수 있으므로 재색인 절차를 복구 수단으로 둔다.
- **관측**: Prometheus/Grafana에서 index별 문서 수, 색인 지연, consumer lag, 검색 p95, heap·GC를 본다.
- **조회·관리 GUI — Elasticvue**: index, mapping, 문서, analyzer 결과를 확인할 때 Elasticvue를 쓴다. 오픈소스(MIT)이고 ES 모든 version을 지원한다([Elasticvue](https://github.com/cars10/elasticvue)).
  - **cluster에 띄우지 않는다.** 개발자 PC의 **데스크톱 앱이나 브라우저 확장**을 쓰고, SSH 터널이나 `kubectl port-forward`로 ES 9200에 붙는다. 이 두 형태는 ES의 CORS 설정이 필요 없다. Docker·web 형태는 `http.cors.*` 설정이 필요하고 pod 하나가 늘어나므로 쓰지 않는다.
  - 9200은 계속 외부에 열지 않는다. 터널은 DataGrip으로 PostgreSQL에 붙는 지금 방식(`docs/deploy/operations.md`)과 같다.
  - 계정을 나눈다. 평소 조회는 **읽기 전용 role**(index 조회·`_analyze`)로 하고, index 삭제·alias 교체 같은 관리 작업은 별도 관리자 계정으로 한다. prod에서 GUI로 index를 지우는 사고를 막기 위해서다.
  - 사용자 사전을 바꾼 뒤 nori 분석 결과(`_analyze`)를 확인하는 데 특히 쓴다.
  - Elasticvue는 index·mapping·analyzer를 직접 점검하는 개발 도구다. Grafana는 운영 지표·로그·trace를 보는 도구라 역할이 겹치지 않는다.
- **license**: 공식 binary는 Elastic License 2.0이다. 2024-08부터 source에 AGPLv3 선택지가 생겼다([Elastic 발표](https://www.businesswire.com/news/home/20240829537786/en)). 직접 운영하며 검색 기능을 쓰는 이 프로젝트는 ELv2의 제한(ES를 관리형 서비스로 제공하는 것 등)에 걸리지 않는다.
- **client 호환**: Spring Data Elasticsearch와 Elasticsearch Java client가 ES 9 서버와 맞는 version인지 spike로 먼저 확인한다.

### 고객센터 RAG와의 관계

ES는 통합 검색과 고객센터 RAG의 공통 **물리 검색 플랫폼**으로 사용한다. `search`는 공개 강의·모집·인플루언서 index를, `customersupport`는 FAQ·공지·정책 hybrid RAG index를 각각 소유한다. 서로 다른 role/API key와 index pattern을 사용하고 상대 모듈의 repository나 API를 경유하지 않는다. 원본은 각 모듈의 PostgreSQL이며 모든 index는 원본과 Kafka event 또는 전체 재색인으로 복구할 수 있어야 한다.

### 결정 필요

- 검색 대상과 색인 필드, 기존 모듈 `keyword` 검색을 검색 모듈로 옮길지 남길지(프론트 공지 필요)
- Spring Boot 4.1.1의 Spring Data Elasticsearch·Spring AI와 선택한 Elasticsearch server version 호환성
- 서비스 검색과 RAG 동시 부하에서 Elasticsearch memory·disk 한도

### 완료 증거

- "인플루언서" 등 서비스 용어가 사용자 사전대로 분석되고, 형태소 단위 검색과 relevance 정렬이 PostgreSQL `LIKE`보다 나은 결과를 golden query set으로 보인다.
- 강의 수정 이벤트 뒤 정해 둔 시간 안에 검색 결과에 반영되고, 같은 이벤트를 두 번 받아도 문서가 하나다.
- search-app을 멈춘 동안 쌓인 이벤트가 재시작 후 반영되고, consumer lag가 0으로 돌아온다.
- mapping 변경 재색인을 alias 교체로 하는 동안 검색 API가 끊기지 않는다.
- ES를 지우고 다시 만들어도 초기 적재만으로 같은 문서 수가 된다.

## 10. 모듈별 database 분리

모듈마다 database를 나누는 것은 확정이다(AGENTS.md 3장, #338). #363은 중간 단계로 database 하나에 앱별 스키마를 나눴다(`docs/architecture/app-modules.md`). 스키마는 권한을 걸어야 JOIN이 막히지만 database는 connection 자체가 다른 database에 붙지 못하므로, 목표는 database 분리로 유지하고 #338에서 스키마를 database로 옮긴다. 이 장은 **instance까지 나눌지**를 판단하고, 분리에 따라 바뀌는 것을 정리한다.

### 판단: instance는 1개, database만 앱별로

| 기준 | instance 1개 + 모듈별 database | 모듈별 instance |
| --- | --- | --- |
| 모듈 경계 강제 | PostgreSQL connection은 한 database에만 붙는다. 다른 database의 table을 JOIN할 수 없다(`dblink`·`postgres_fdw`를 설치하지 않으면). 경계가 이미 강제된다 | 같음. 경계 면에서 더 얻는 것이 없다 |
| 계정 분리 | database마다 소유 role을 따로 두고 `REVOKE CONNECT ... FROM PUBLIC`. 모듈 계정은 자기 database에만 접속한다 | 같음 |
| 메모리 | instance 고정 비용을 한 번만 낸다(`shared_buffers` 기본 128 MB, background process, WAL buffer). 현재 예산 512 MiB 유지 | database 9개면 고정 비용이 9번. `shared_buffers`만 기본값으로 1 GiB를 넘어 3장 예산에 들어가지 않는다 |
| 자원·장애 격리 | 한 모듈의 무거운 query가 다른 모듈에 영향을 준다 | process는 나뉘지만 같은 EC2의 CPU·disk·kernel을 쓴다. 단일 host에서는 격리 효과가 작다 |
| 백업·복구 | `pg_dump`는 database 단위로 가능하다. 시점 복구(PITR)는 WAL이 instance 전체라 모듈 하나만 되돌릴 수 없다 | 모듈별 PITR 가능 |
| version 업그레이드 | 모든 모듈이 함께 올린다 | 모듈별로 따로 올린다 |
| 운영 | Kubernetes StatefulSet 1개, 백업·모니터링 1벌 | StatefulSet·Secret·백업·대시보드가 모듈 수만큼 |

근거: "It is not possible to access more than one database per connection"([PostgreSQL Managing Databases](https://www.postgresql.org/docs/current/managing-databases.html)). `shared_buffers` 기본값 128 MB([PostgreSQL Resource Consumption](https://www.postgresql.org/docs/current/runtime-config-resource.html)).

모듈별 instance가 더 얻는 것은 모듈별 PITR, 독립 업그레이드, process 격리다. 모두 단일 EC2에서는 효과가 작거나 필요 없다. 그 대가는 3장 예산을 넘는 메모리다. 따라서 **instance 1개를 두고, 그 안에서 앱마다 database를 나눈다.** EC2에는 prod만 두므로 instance도 하나다. 개발용 DB는 로컬과 PR k3d에서 띄운다.

instance를 나누는 시점은 모듈을 별도 service로 떼어 내 따로 배포하거나, 관리형 DB(RDS 등)로 옮길 때다(11장).

### database 목록

| database | 소유 앱 | 내용 |
| --- | --- | --- |
| `member` | 회원 | 회원, 역할, 채널 인증, refresh token |
| `appearance` | 출연 | 출연 요청·모집, 회원 복제본 |
| `lecture` | 강의 | 강의, 장바구니, 주문, 리뷰, 회원 복제본 |
| `billing` | 지갑·정산 | 지갑, 원장, 보류, 지급, 환전 |
| `chat` | 채팅 | 대화, 차단, 회원 복제본 |
| `customersupport` | 고객센터 | 공지, FAQ, 문의, RAG 색인 작업·재처리 상태(검색 문서는 고객센터 소유 ES index) |
| `notification` | 알림 | 알림, 회원 복제본, inbox |
| `search` | 검색 | 이벤트 inbox, 색인 상태(문서는 ES) |

Spring Session을 쓰지 않으므로(3장 "인증 흐름") 공용 `platform` database는 두지 않는다.

### 분리하면 바뀌는 것 (instance 결정과 무관)

instance를 하나로 두어도 database가 나뉘는 순간 다음이 바뀐다. 실제 작업량의 대부분은 여기에 있다.

1. **모듈을 넘는 transaction이 사라진다.**
   - 지금 강의 주문 생성은 주문 저장과 billing의 HP 보류를 한 transaction으로 처리한다(`OrderCreateUseCase` → `BillingPort.hold`).
   - database가 다르면 이 둘을 원자적으로 묶을 수 없다. 분리 전에 내부 API 전환(#271~#281)과 보상 처리(보류 실패 시 주문 취소, 주문 실패 시 보류 반환)를 끝내야 한다.
   - 출연 모듈도 `BillingPort`를 같은 방식(호출한 쪽 transaction 안)으로 부르므로 같은 작업이 필요하다.
2. **Modulith 이벤트 저장소는 앱마다 자기 database에 둔다.**
   - Spring Modulith의 JDBC publication registry는 `DataSource` 하나에 묶인다(`JdbcEventPublicationAutoConfiguration`, 2.1.1 소스 확인).
   - 앱마다 database가 하나이므로 업무 data와 publication이 같은 database에 있다. "업무 transaction 안에서 이벤트를 기록한다"는 outbox 보장이 앱 안에서 그대로 지켜진다.
   - 각 앱이 자기 registry의 publication을 Kafka로 externalization한다. 1장의 `IntegrationEventPublisher` seam 뒤에서 구현한다.
3. **앱마다 `DataSource`와 Flyway가 하나씩이다.** 앱 하나가 database 하나에만 붙으므로 모놀리스처럼 모듈별 transaction manager를 나눌 필요가 없다. 버전 충돌 검사(`uniq -d`)는 앱 안에서만 의미가 있다.
4. **기존 데이터와 migration 이력을 옮긴다.** 지금 운영 DB는 database 하나에 모든 모듈 table과 Flyway 이력이 있다. 앱별 database로 옮길 때 데이터와 이력을 보존하는 절차가 필요하다(#338, `docs/architecture/app-modules.md`).
5. **connection 수를 다시 계산한다.**
   - 앱은 자기 database에만 pool을 연다. 총 connection은 `(앱 수 + rolling surge pod 수) × pool 크기`다.
   - 앱 8개와 surge pod 1개가 pool을 5개씩 열면 이론상 45개다. `max_connections`는 계산값에 여유를 더해 정한다. connection마다 backend process 메모리가 드므로 3장 PostgreSQL 예산도 함께 다시 잰다.
6. **test 지원을 바꾼다.** 앱 test는 자기 database만 띄운다. `common/test-support`의 DB 정리와 Testcontainers 설정이 이에 맞는지 확인한다.

### 진행 순서

1. 모듈을 넘는 동기 호출을 내부 API와 보상 처리로 바꾼다(#271~#281).
2. 앱별 publication externalization을 `IntegrationEventPublisher` 뒤에 만든다(Kafka 도입과 함께, #339).
3. #363의 앱별 스키마를 하나씩 앱별 database로 옮긴다. 데이터 이전은 `pg_dump`/restore로 하고 Flyway 이력을 보존한다(#338).
4. connection 수와 PostgreSQL 메모리를 재측정한다(실행 전 허락).

### 완료 증거

- 모듈 계정으로 다른 모듈 database에 접속하면 거부된다.
- 강의 주문 중 HP 보류가 실패하면 주문이 남지 않고, 주문 저장이 실패하면 보류가 반환된다.
- 업무 commit 직후 앱을 종료해도 모듈 outbox의 이벤트가 재기동 후 Kafka로 나간다.
- rolling update 중 최대 connection 수가 `max_connections` 안에 있다.

## 11. 실서비스 전환 시 고려사항

이 장은 포트폴리오 범위(EC2 2대)에서는 **구현하지 않는다.** 실제 트래픽을 받는 서비스로 옮길 때 검토할 항목을 남겨 두는 곳이다.

### 모듈별 PostgreSQL instance

10장은 환경마다 instance 1개로 정했다. 모듈을 별도 service로 떼어 내 따로 배포하거나 관리형 DB(RDS 등)로 옮기면 instance도 모듈별로 나눈다. 그때 얻는 것은 모듈별 시점 복구(PITR), 독립 업그레이드, 자원·장애 격리다. database가 이미 나뉘어 있으므로, 옮길 때는 모듈 database 하나를 `pg_dump`/restore하고 연결 정보만 바꾸면 된다.

### KEDA — 이벤트 양에 따른 consumer 자동 확장

KEDA(Kubernetes Event-Driven Autoscaling)는 CPU·메모리가 아니라 외부 이벤트의 양으로 pod 수를 조절한다. 기준은 Kafka consumer lag, Redis Stream pending 수 등이다. `ScaledObject`에 기준을 적으면 KEDA가 HPA의 외부 metric으로 넘기고, 일이 없으면 0개까지 줄일 수도 있다([공식 scaler 목록](https://keda.sh/docs/latest/scalers)).

지금 넣지 않는 이유:
- 현재 2노드는 node별 역할과 메모리 상한이 고정돼 있어 lag에 따라 pod를 늘릴 여유가 작다(3장).
- Kafka consumer가 web과 같은 앱 프로세스 안에 있어 lag 때문에 HTTP를 받는 앱 전체를 늘리게 된다. 이것은 잘못된 확장이다.

실서비스에서 도입할 조건:
- 알림·검색 consumer처럼 lag가 쌓이는 처리가 **web과 분리된 worker workload**로 떨어져 있다.
- node를 늘릴 수 있는 cluster(다중 node, 또는 관리형 Kubernetes)가 있다.
- topic partition 수가 목표 최대 replica 수 이상이다. 한 consumer group에서 동시에 일하는 consumer는 partition 수를 넘을 수 없다.
- scale-in 때 처리 중인 record가 4장의 종료 순서(현재 record까지 처리 후 offset commit)를 따른다.

확인할 것: `consumer lag 증가 → replica 증가 → lag 회복 → scale-in`이 dashboard에 보이는지, scale-in과 rebalance 중에 중복 처리가 inbox 멱등성으로 흡수되는지.

## 구현 순서

### Phase 1 — 측정 기준선

1. EC2 현재 Compose의 container별 메모리·CPU·디스크 사용량과 CPU credit 추이를 측정해 3장 자원 예산 확정
2. k6 시나리오와 현재 Compose 기준 결과 저장 (EC2 밖에서 실행, credit 잔량 함께 기록)
3. 공통 correlation/event ID와 구조화 로그
4. Prometheus·Alloy·Loki·Tempo·Grafana 연결. Mimir 비교는 5장 비교표에만 남김
5. SonarQube Cloud + JaCoCo PR quality gate 연결
6. 각 업무 모듈의 data fixture를 `src/testFixtures`로 분리하고 `:test-support`는 공통 infrastructure로 유지
7. Testcontainers 공통 fixture와 Toxiproxy 장애 test 기반 구축
8. 같은 k6 scenario의 JFR baseline을 저장하고 JMC 분석 template 작성

### Phase 2 — 모듈별 database 분리

1. 현재 schema·transaction·모듈 간 동기 호출을 inventory하고 database 소유권 확정
2. 모듈을 넘는 동기 변경을 내부 HTTP interface와 보상 처리로 전환
3. 앱별 publication registry가 업무와 같은 database transaction에 기록되는지 확인
4. #363의 앱별 스키마를 앱별 database로 옮기고 최소 권한 계정 구성(#338)
5. Testcontainers에 동일 database·계정 구조를 만들고 cross-database 접근 차단 test
6. schema별 `pg_dump`/restore, HP 원장 합계·row count·미처리 event 검증

### Phase 3 — Kafka

1. integration event envelope과 topic contract
2. 레포 JSON Schema·AsyncAPI와 호환성 CI(`asyncapi diff`, version별 샘플 contract test)
3. 모듈별 outbox → Kafka relay adapter
4. inbox 멱등 consumer, retry/DLT, 재처리 명령
5. 회원 복제 이벤트 하나로 end-to-end 완성 후 출연·강의로 확장

### Phase 4 — 채팅 Redis와 알림 모듈

1. `chat_outbox`와 publisher
2. Redis Streams consumer group, ACK/pending/claim/failed stream
3. Redis Pub/Sub fan-out과 재접속 history sync
4. 장애·중복·순서 테스트
5. `notification-app`과 database, 알림 API, integration event 소비와 `eventId` 멱등 처리
6. 알림 실시간 전달과 재접속 복구, 채팅의 출연 알림 listener 제거

### Phase 5 — 검색(ES)과 고객센터 RAG

1. 공개 FAQ·공지·정책 corpus와 답변 거절 기준 확정
2. nori와 사용자 사전을 넣은 Elasticsearch image, `search-app`, 색인 이벤트 소비
3. `search`와 `customersupport`의 index pattern·role/API key·alias 소유권 분리
4. Spring AI `ElasticsearchVectorStore` ETL과 native client 기반 BM25 + dense vector + RRF retriever 구현
5. 초기 적재, 멱등 재색인, 게시 취소 반영, alias 교체, 원본·index 수 비교 job
6. 실제 검색 metadata 기반 `sources`와 근거 부족 시 답변 거절 구현
7. BM25/vector/hybrid golden question, 비공개 filter, 근거 없음, prompt injection 평가

### Phase 6 — Grafana observability stack

1. Micrometer metrics와 OpenTelemetry trace, 구조화 JSON log 계측
2. Prometheus scrape·retention과 Alloy logs/OTLP pipeline 구성
3. Loki·Tempo 저장 한도와 Grafana datasource 연결
4. API·Kafka·Redis·Elasticsearch dashboard와 alert 구성
5. k6 부하에서 metrics → trace → logs 상관관계와 node별 memory·CPU·disk/day 기록

### Phase 7 — Graceful Shutdown

1. `DrainCoordinator.drain(deadline)`과 Web·Kafka·Redis drain adapter 구현
2. readiness와 신규 작업 gate, WebSocket 재연결, DataSource 최종 종료 순서 검증
3. `SIGTERM`, deadline 초과, 중복 drain 호출, `SIGKILL` 통합 test
4. `terminationGracePeriodSeconds`와 node shutdown budget을 실측값으로 확정

### Phase 8 — EC2 k3s 전환과 현행 AWS IaC

1. JWT 인증 전환: member-app 발급·refresh·JWKS(#370), 앱 공용 헤더 인증 필터와 chat STOMP 검증(#371)
2. `k8s/` 구조(cluster·components·platform·apps)와 Helm 차트, 로컬 k3d bootstrap, PR CI smoke/E2E(#373)
3. 모듈 앱 8개 Deployment(앱마다 replica 1개)와 probe·resource limit·migration·drain 설정
4. Envoy Gateway·cert-manager·ServiceLB, `HTTPRoute`·`SecurityPolicy`, 헤더 위조 차단, 내부 API 차단, 큰 본문 업로드 검증(#372)
5. 앱별 이미지 발행과 prod 배포 workflow(#374). EC2에 k3s를 설치하고 prod만 전환한다. 전환 검증 전까지 Compose 이미지로 되돌릴 수 있게 둔다
6. rolling update, pod 강제 종료, EC2 자동 중지·재시작과 자원 사용량 기록
7. prod 전환 후 Compose, edge nginx, dev 서버와 기존 배포 script 제거
8. 실제 EC2·EIP·network·security group·S3·IAM을 Terraform으로 import하고 zero-change plan 검증

### Phase 9 — 포트폴리오 증거

1. 정상 흐름과 장애 복구 sequence diagram
2. 기술 선택·대안·한계를 기록한 ADR
3. Grafana dashboard와 k6 전후 metrics·logs·traces 상관관계
4. baseline/after JFR과 JMC 분석 report
5. Kafka/Redis/pod 강제 종료 demo script
6. Graceful Shutdown drain demo
7. OpenAPI·AsyncAPI와 schema compatibility 실패 PR 예시
8. RAG 검색 근거·답변 거절·출처 반환 평가 report
9. Sonar quality gate 결과
10. Terraform zero-change plan과 실제 배포 AWS diagram
11. “실운영이라면 무엇을 빼거나 managed service로 바꿀지” 명시(11장)

## 범위 통제

포트폴리오라도 다음은 피한다.

- Kafka와 Redis Streams에 같은 integration event를 중복 발행
- HP 원장이나 채팅 이력을 Kafka/Redis에만 저장
- 모든 module이 KafkaTemplate·RedisTemplate을 직접 호출
- 분산 락을 unique constraint·조건부 update·멱등키의 대체물로 사용
- Prometheus server와 Mimir를 목적 구분 없이 이중 metrics 저장소로 운영하거나, Elasticsearch에 같은 telemetry를 중복 저장
- self-hosted SonarQube를 cluster의 필수 runtime dependency로 포함
- large 8 GiB·medium 4 GiB의 node별 예산을 넘는 구성을 상시로 띄우기. 시연할 때만 필요한 replica는 시연 때만 scale out
- 팀 합의 없이 S3 버킷을 업로드 외 용도(metrics·logs·vector·backup)로 쓰거나 인스턴스 역할 권한을 넓히기
- security group에 k3s API·Grafana·Elasticsearch·Kafka·Redis port를 열기
- 개인 문의·결제 정보 등 비공개 data를 공개 고객센터 RAG corpus에 색인
- LLM 답변 문자열이 만든 URL을 검증 없이 출처로 반환
- 전환이 끝난 환경에서 Compose와 Kubernetes 두 배포 경로를 병행하거나, 서버에서 `kubectl edit` 등으로 manifest와 다르게 고치기
- 장애 demo 없이 정상 실행 screenshot만 제시
- 근거 없는 MSA 분리

가장 좋은 결과물은 기술 수가 많은 프로젝트가 아니라, **PostgreSQL을 진실의 원천으로 유지하면서 Kafka와 Redis의 at-least-once 전달을 멱등성으로 제어하고, Elasticsearch의 검색·RAG·관측성 경계를 설명하며, 고객센터 RAG가 검색한 근거만 답하고, 한정된 EC2 2노드 k3s에서 pod와 node 장애의 차이를 재현하는 프로젝트**다.
