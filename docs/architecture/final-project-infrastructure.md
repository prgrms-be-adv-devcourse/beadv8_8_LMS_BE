# 최종 프로젝트 포트폴리오 인프라 설계

작성일: 2026-10-22
최종 갱신: 2026-10-29

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
| security group | 22·80·443 inbound 전체 허용. 6443은 kubectl 사용자 IP `/32`만 허용(2026-10-28) | Grafana, Elasticsearch, Kafka, Redis port는 열지 않는다. k3s API(6443)는 사용자 IP `/32` 외에는 열지 않는다 |
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
| 채팅 전달 작업 | PostgreSQL outbox로 보관·재처리 | DB 선점·lease 회수·backoff·실패 격리. Redis Streams는 구현하지 않음(2026-10-29 결정) |
| Redis Pub/Sub·TTL | 채팅·알림 보조로 도입 | 다중 앱 replica fan-out, 접속 상태·짧은 수명 데이터 |
| 앱 구조 | 앱 1개 = bounded context 1개 = database 1개. #363의 앱 6개에 `notification-app`·`search-app`을 더해 8개 | 서비스별 빌드·배포·장애 격리 |
| 알림(notification) | 채팅에서 분리해 `notification-app`으로 ([8장](infrastructure/notification.md)) | 이벤트 기반 독립 context, 멱등 소비, 실시간 전달과 복구 |
| Elasticsearch + `search-app` | **도입 확정** ([9장](infrastructure/search.md), #234) | 이벤트 기반 색인, nori 한국어 검색, alias 재색인, 고객센터 hybrid RAG |
| k3s(server 1 + agent 1) + Kustomize + Helm | 도입. 로컬·CI는 같은 manifest를 k3d로 검증. 잘 관리되는 공식·준공식 차트만 Helm으로 | 실제 서버의 Kubernetes 배포, rolling update, probe, node별 workload 분리와 자원 예산 관리 |
| 모듈별 PostgreSQL database | 도입. instance는 1개, database만 앱별로 ([10장](infrastructure/database.md)) | database-per-bounded-context, 앱별 계정·migration·outbox |
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
            └─ chat outbox → DB relay(선점·lease·재시도)
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

## 장별 문서

장 번호는 분리 전 이 문서의 장 번호를 그대로 쓴다. 다른 문서의 "인프라 문서 N장" 참조는 아래 표로 찾는다.

| 장 | 문서 | 내용 |
| --- | --- | --- |
| 1 | [Kafka](infrastructure/kafka.md) | 모듈 간 integration event, broker 선택 |
| 2 | [채팅 전달](infrastructure/redis-chat.md) | PostgreSQL outbox·Redis Pub/Sub 채팅 전달 pipeline |
| 3 | [k3s·Kustomize·Helm](infrastructure/k3s.md) | k3s와 k3d, EC2 2노드 자원 예산, EC2 운영, workload, manifest, CI |
| 3 | [API Gateway](infrastructure/api-gateway.md) | Envoy Gateway, JWT 인증 흐름, rate limit |
| 4 | [Graceful Shutdown](infrastructure/graceful-shutdown.md) | 전체 workload drain |
| 5 | [관측성](infrastructure/observability.md) | Grafana observability stack, Prometheus vs Mimir, 부하 테스트 |
| 6·7 | [포트폴리오 추가 항목](infrastructure/portfolio-items.md) | SonarQube Cloud, test fixtures, Toxiproxy, Spring AI RAG, event contract, JFR, Terraform |
| 8 | [알림 모듈](infrastructure/notification.md) | notification 앱 분리 |
| 9 | [검색 모듈](infrastructure/search.md) | search 앱과 Elasticsearch |
| 10 | [모듈별 database](infrastructure/database.md) | instance 1개 + 앱별 database |
| 11 | [아래](#11-실서비스-전환-시-고려사항) | 실서비스 전환 시 고려사항 |

## 11. 실서비스 전환 시 고려사항

이 장은 포트폴리오 범위(EC2 2대)에서는 **구현하지 않는다.** 실제 트래픽을 받는 서비스로 옮길 때 검토할 항목을 남겨 두는 곳이다.

### 모듈별 PostgreSQL instance

[10장](infrastructure/database.md)은 환경마다 instance 1개로 정했다. 모듈을 별도 service로 떼어 내 따로 배포하거나 관리형 DB(RDS 등)로 옮기면 instance도 모듈별로 나눈다. 그때 얻는 것은 모듈별 시점 복구(PITR), 독립 업그레이드, 자원·장애 격리다. database가 이미 나뉘어 있으므로, 옮길 때는 모듈 database 하나를 `pg_dump`/restore하고 연결 정보만 바꾸면 된다.

### KEDA — 이벤트 양에 따른 consumer 자동 확장

KEDA(Kubernetes Event-Driven Autoscaling)는 CPU·메모리가 아니라 외부 이벤트의 양으로 pod 수를 조절한다. 기준은 Kafka consumer lag 등이다. `ScaledObject`에 기준을 적으면 KEDA가 HPA의 외부 metric으로 넘기고, 일이 없으면 0개까지 줄일 수도 있다([공식 scaler 목록](https://keda.sh/docs/latest/scalers)).

지금 넣지 않는 이유:
- 현재 2노드는 node별 역할과 메모리 상한이 고정돼 있어 lag에 따라 pod를 늘릴 여유가 작다([3장](infrastructure/k3s.md#ec2-2노드-자원-예산)).
- Kafka consumer가 web과 같은 앱 프로세스 안에 있어 lag 때문에 HTTP를 받는 앱 전체를 늘리게 된다. 이것은 잘못된 확장이다.

실서비스에서 도입할 조건:
- 알림·검색 consumer처럼 lag가 쌓이는 처리가 **web과 분리된 worker workload**로 떨어져 있다.
- node를 늘릴 수 있는 cluster(다중 node, 또는 관리형 Kubernetes)가 있다.
- topic partition 수가 목표 최대 replica 수 이상이다. 한 consumer group에서 동시에 일하는 consumer는 partition 수를 넘을 수 없다.
- scale-in 때 처리 중인 record가 [4장](infrastructure/graceful-shutdown.md#종료-계약과-순서)의 종료 순서(현재 record까지 처리 후 offset commit)를 따른다.

확인할 것: `consumer lag 증가 → replica 증가 → lag 회복 → scale-in`이 dashboard에 보이는지, scale-in과 rebalance 중에 중복 처리가 inbox 멱등성으로 흡수되는지.

## 구현 순서

### Phase 1 — 측정 기준선

1. EC2 현재 Compose의 container별 메모리·CPU·디스크 사용량과 CPU credit 추이를 측정해 [3장 자원 예산](infrastructure/k3s.md#ec2-2노드-자원-예산) 확정
2. k6 시나리오와 현재 Compose 기준 결과 저장 (EC2 밖에서 실행, credit 잔량 함께 기록)
3. 공통 correlation/event ID와 구조화 로그
4. Prometheus·Alloy·Loki·Tempo·Grafana 연결. Mimir 비교는 [5장](infrastructure/observability.md) 비교표에만 남김
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

1. 메시지·시스템 안내·회원 상태 변경과 전달·제어 outbox의 원자 저장
2. PostgreSQL outbox relay: DB 선점·lease·claimToken·재시도·DEAD 격리·운영자 재처리
3. Redis Pub/Sub fan-out, 수신 참여 세대 확인, 마지막 유실 이력 대조·회원 상태 재검사
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
2. `k8s/` 구조(cluster·components·platform·apps)와 Helm 차트, 로컬 k3d bootstrap, CI 렌더링·schema 검사(#373). PR CI smoke/E2E는 앱이 모두 단독 기동한 뒤
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
11. “실운영이라면 무엇을 빼거나 managed service로 바꿀지” 명시([11장](#11-실서비스-전환-시-고려사항))

## 범위 통제

포트폴리오라도 다음은 피한다.

- 채팅에 Redis Streams·consumer group·ACK·pending·failed stream을 구현하거나 Kafka integration event와 채팅 outbox의 책임을 중복 배치
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
