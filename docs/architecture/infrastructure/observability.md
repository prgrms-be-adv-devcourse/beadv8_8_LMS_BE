# 5. Grafana observability stack과 부하 테스트

상위 문서: [최종 프로젝트 인프라 설계](../final-project-infrastructure.md). 결정 기록은 #369다.

관측성은 **Prometheus + Alloy + Loki + Tempo + Grafana** 한 벌로 고정한다. 애플리케이션과 gateway는 Micrometer와 OpenTelemetry로 계측하고 metrics는 Prometheus, logs는 Loki, traces는 Tempo가 저장한다. Elasticsearch는 검색·Vector DB로만 사용한다.

같은 k6 시나리오로 ingest CPU·메모리·disk/day, 질의 p95, dashboard·alert와 trace-log 상관관계를 측정한다. Elasticsearch 관측성을 제외한 근거는 [Elasticsearch 통합 범위 조사](../elasticsearch-consolidation-research.md)를 따른다.

## 수집·저장 구성

비교 실험은 다음을 한 세트로 구성한다.

- Micrometer Prometheus-format endpoint: HTTP, JVM, HikariCP, Kafka lag, 채팅 outbox 적재 대기·재시도·DEAD, Stream 그룹 lag·PEL/idle·ACK 지연·회수·메모리, DB 발행 체크포인트·재구성, Redis Pub/Sub 발행 실패, HP task 노출
- Prometheus: `/actuator/prometheus`와 infrastructure exporter를 직접 scrape하고 local TSDB·PromQL·rule 평가 담당
- Grafana Alloy: pod log 수집과 OTLP trace 수신·전달 담당. Mimir로 바꿀 때만 metrics scrape·remote write까지 확장
- Loki: JSON log 저장; `traceId`, `correlationId`, `eventId`, 배포 version으로 연결
- Tempo: HTTP → 업무 transaction → Kafka publish/consume → chat delivery trace 저장
- Grafana: Prometheus·Loki·Tempo datasource와 API, Kafka, chat, HP, Kubernetes dashboard·alert
- k6: 일반 API, WebSocket, 돈 흐름 재시도 시나리오

기준선에서는 **Prometheus가 metrics를 직접 scrape**하고, Alloy는 logs·traces를 Loki·Tempo로 전달한다. Grafana는 세 backend를 조회한다. Prometheus remote-write receiver는 공식 문서가 일반 scrape의 대체 수단으로 적합하지 않다고 명시하므로, collector를 하나처럼 보이게 만들기 위해 `Alloy scrape → Prometheus remote write receiver`를 기본 구성으로 쓰지 않는다([Prometheus Remote Write Receiver](https://prometheus.io/docs/prometheus/latest/querying/api/#remote-write-receiver)). Prometheus와 Mimir를 동시에 장기 저장소로 두지 않는다.

## metrics 수집과 저장 — Prometheus vs Mimir

metrics pipeline에는 역할이 셋 있다. Prometheus는 이 중 수집과 저장을 **둘 다** 할 수 있어서 "Alloy vs Prometheus"처럼 보이기 쉽지만, 실제로 고르는 것은 축 두 개다.

| 역할 | 하는 일 | 후보 | 이 문서의 선택 |
| --- | --- | --- | --- |
| metrics 수집 | 앱의 `/actuator/prometheus`와 exporter scrape | Prometheus 자체 scrape, Alloy scrape + remote write | **현재는 Prometheus 직접 scrape** |
| 저장·조회 | 시계열 보관, PromQL 응답, recording/alert rule | Prometheus TSDB, Mimir | 아래 비교 |
| logs·traces 수집 | pod log와 OTLP trace 전달 | Alloy | **Alloy로 고정** |
| 조회 UI | dashboard, alert, trace·log 이동 | Grafana | Grafana로 고정 |

### 저장소: Prometheus vs Mimir

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
| 채팅 outbox·Streams·Pub/Sub | relay lease·PEL 회수, XADD/PUBLISH/DB 기록/XACK 경계의 중복, Redis 데이터 유실 재구성·보존·메모리 상한, 마지막 프레임 유실의 이력 대조 복구 |
| 돈 흐름 | timeout·중복 요청에도 원장 불변식 위반 0건 |
| rolling update | HTTP 오류 최소화, WebSocket 재연결 후 history gap 0건 |

이 수치는 제품 SLO가 아니라 포트폴리오 실험의 기준선이다.

EC2에서 부하 test를 할 때는 다음을 지킨다.

- k6는 EC2 밖(개발 PC나 GitHub Actions runner)에서 실행한다. 같은 host에서 k6를 돌리면 부하 생성기가 2 vCPU를 나눠 써서 측정값이 왜곡된다.
- t3 standard credit 모드에서는 credit이 바닥나면 CPU가 기준선으로 제한된다. 각 실행 전후의 CloudWatch `CPUCreditBalance`를 결과와 함께 기록한다. credit 소진 구간의 p95는 코드 성능이 아니라 instance 제한으로 해석한다.
- EC2에는 prod만 있으므로 부하 test는 prod를 대상으로, 팀이 서버를 쓰지 않는 시간대에 하고 미리 공지한다. 결과에는 그 시간 node별 자원 사용량도 함께 남긴다. 반복 실험은 로컬 k3d에서 먼저 한다.
- 위 초기 목표(100 RPS 10분 등)가 2노드에서 유지되지 않으면 목표를 낮추는 것이 아니라 node별 CPU credit, DB connection, GC 병목을 JFR과 dashboard로 설명하는 것을 결과물로 삼는다.
