# Elasticsearch 통합 범위 조사

조사일: 2026-10-26
결정 반영: 2026-10-28 (채팅 Streams 변경 결정: 2026-10-29)

## 질문과 전제

Elasticsearch 도입이 확정됐을 때 서비스 검색 외에 RAG vector store, 로그·메트릭·trace, Kafka·Redis 관련 기능까지 얼마나 통합할지 판단한다.

현재 전제는 다음과 같다.

- 결과물은 실제 운영보다 포트폴리오의 설계·비교·장애 시연이 목적이므로, 운영 비용을 이유로 통합 가능성을 미리 제외하지 않는다.
- 현재 EC2는 t3.large(8 GiB) `core-data`와 t3.medium(4 GiB) `app-observe` 2노드이며 각각 gp3 50 GB를 사용한다.
- PostgreSQL은 업무 데이터와 HP 원장의 진실의 원천이다.
- Kafka는 모듈 간 integration event를, PostgreSQL outbox relay·Redis Streams·Pub/Sub은 채팅 전달 pipeline을 맡는다(2026-10-29 결정 반영).
- 조사 전 설계안은 검색용 Elasticsearch, RAG용 pgvector, 관측성용 Prometheus·Loki·Tempo·Grafana를 각각 사용하는 구성이었다.

이 문서는 선택 근거와 대안 비교 기록이다. 구현 기준은 [최종 프로젝트 포트폴리오 인프라 설계](./final-project-infrastructure.md)이며, 아래 결론은 해당 문서에 반영됐다.

## 결론

**Elasticsearch를 서비스 검색과 RAG 검색의 공통 검색 플랫폼으로 사용한다.** 별도 pgvector는 제거하고, 하나의 Elasticsearch cluster 안에서 모듈별 index와 권한을 분리한다.

**large의 Elasticsearch는 통합 검색과 고객센터 Vector DB만 담당하고, medium의 Grafana observability stack이 관측성을 담당한다.** 관측성 구성은 Prometheus metrics, Alloy 수집, Loki logs, Tempo traces, Grafana dashboard·alert로 고정한다.

Kibana, Elastic Agent, Elastic APM과 Logstash는 도입하지 않는다. Elasticsearch에 telemetry를 저장하지 않아 서비스 검색·hybrid RAG와 지속적인 ingest가 heap·CPU·disk를 경쟁하지 않게 한다.

| 책임 | Elasticsearch 통합 | 판단 |
| --- | --- | --- |
| 강의·출연 모집·인플루언서 통합 검색 | 통합 | Elasticsearch의 본래 역할. BM25, filter, nori를 사용한다 |
| 고객센터 RAG vector store | **통합** | 이미 필요한 Elasticsearch의 `dense_vector`를 재사용하고 pgvector 중복을 없앤다 |
| 한국어 keyword + vector hybrid retrieval | **통합** | nori lexical 검색과 kNN을 RRF로 결합한다 |
| 애플리케이션 로그 | 통합하지 않음 | Alloy가 수집하고 Loki가 저장한다 |
| 메트릭 | 통합하지 않음 | Prometheus가 scrape·저장·PromQL·rule을 담당한다 |
| 분산 trace | 통합하지 않음 | OpenTelemetry trace를 Alloy가 받아 Tempo에 저장한다 |
| Kafka event stream | 통합하지 않음 | Kafka가 전달·재생을 담당하고 ES는 소비 결과인 read model만 가진다 |
| PostgreSQL outbox·Redis Streams·Pub/Sub | 통합하지 않음 | DB가 원자 저장·적재 복구, Streams가 작업 소비·회수, Pub/Sub이 실시간 전달을 맡는다. ES를 전달 큐로 쓰지 않는다 |
| 업무 DB·HP 원장·채팅 이력 | 통합하지 않음 | PostgreSQL이 source of truth이고 ES는 삭제 후 재구축 가능한 projection이다 |
| 검색·색인 운영 지표 | Grafana stack에 통합 | Elasticsearch exporter/API metrics를 Prometheus가 수집하고 Grafana에서 본다 |

즉, 이 프로젝트에서 Elasticsearch는 **공통 검색·Vector DB**다. telemetry, message broker, cache와 업무 DB를 대체하지 않는다.

## 1. 서비스 검색과 RAG vector store 통합

### 기술적으로 가능한가

Spring AI는 공식 `spring-ai-starter-vector-store-elasticsearch`와 `ElasticsearchVectorStore`를 제공한다. index 이름, embedding 차원, similarity, embedding field를 설정할 수 있고 schema 자동 생성은 기본적으로 꺼져 있다. 또한 native `ElasticsearchClient`에 접근할 수 있다([Spring AI Elasticsearch Vector Store](https://docs.spring.io/spring-ai/reference/api/vectordbs/elasticsearch.html)).

Elasticsearch는 `dense_vector`와 kNN 검색을 지원하며, keyword와 vector 검색을 한 요청에서 결합할 수 있다. Elastic은 hybrid 결과 결합에 Reciprocal Rank Fusion(RRF)을 권장한다([Vector search](https://www.elastic.co/docs/solutions/search/vector), [Hybrid search](https://www.elastic.co/docs/solutions/search/hybrid-search)). 한국어 검색에는 Lucene Nori 기반 공식 `analysis-nori` plugin을 사용할 수 있고, `decompound_mode`와 사용자 사전도 설정할 수 있다([nori analyzer](https://www.elastic.co/docs/reference/elasticsearch/plugins/analysis-nori-analyzer)).

따라서 다음을 하나의 검색 engine에서 검증할 수 있다.

1. 정확한 서비스명·정책명·도메인 용어: nori + BM25
2. 표현은 다르지만 의미가 비슷한 질문: embedding + kNN
3. 둘을 결합한 고객센터 RAG: BM25와 kNN 결과를 RRF로 결합
4. `published`, `visibility`, `category`, `sourceType` 같은 metadata filter

Spring AI의 기본 `VectorStore.similaritySearch`만으로 Elasticsearch 고유의 nori + RRF hybrid query 전체가 자동 제공되는 것은 아니다. 기본 vector 저장·조회는 `ElasticsearchVectorStore`로 처리하되, 최종 hybrid retriever는 native client 또는 별도 Elasticsearch adapter 안에 캡슐화해야 한다. Spring AI가 native client 접근을 공식 제공하는 이유도 VectorStore 공통 interface에 없는 Elasticsearch 기능을 사용할 수 있게 하기 위해서다([Spring AI Elasticsearch Vector Store](https://docs.spring.io/spring-ai/reference/api/vectordbs/elasticsearch.html)).

### 모듈 경계를 지키는 공유 방법

물리 cluster를 공유한다고 index 소유권까지 합치지 않는다.

| index/alias 예시 | 소유 모듈 | 내용 |
| --- | --- | --- |
| `prod-search-lectures-v1` | `search` | 공개 강의 검색 projection |
| `prod-search-recruits-v1` | `search` | 공개 출연 모집 검색 projection |
| `prod-search-influencers-v1` | `search` | 공개 인플루언서 검색 projection |
| `prod-customersupport-knowledge-v1` | `customersupport` | FAQ·공지·정책 chunk, metadata, embedding |

- `search` 모듈과 `customersupport` 모듈에 서로 다른 Elasticsearch role/API key를 준다.
- 각 계정은 자기 index pattern만 읽고 쓴다.
- 원본은 각 모듈의 PostgreSQL database이며 ES index는 재생성 가능한 projection이다.
- 공통으로 공유하는 것은 client 설정, index naming 규칙, 보안·운영 adapter뿐이다.
- 고객센터가 검색 모듈의 Repository나 API를 호출하는 구조로 우회하지 않는다.

현재 문서의 “ES index는 검색 모듈만 소유한다”는 원칙은 “각 bounded context가 자기 ES index를 소유한다”로 바꿔야 한다. 그래야 고객센터 RAG가 모듈 경계를 넘지 않으면서 같은 물리 cluster를 사용할 수 있다.

### 권장 색인 설계

- Spring AI 자동 schema 생성을 사용하지 않는다. Flyway 대상은 아니지만 versioned index template/mapping JSON을 레포에서 관리한다.
- 고객센터 index에는 원문용 `text` field, nori용 analyzed field, 정확 일치용 `keyword`, embedding용 `dense_vector`, 공개 범위 filter용 metadata field를 함께 둔다.
- embedding model을 바꾸거나 dimension·analyzer·mapping을 바꾸면 새 version index를 만들고 재색인한 뒤 alias를 교체한다.
- Spring AI에는 alias를 `index-name`으로 주고, 재색인 중에는 old/new index dual write 또는 event replay를 사용한다.
- `sourceType + sourceId + version + chunkNo`를 안정적인 document ID로 만들어 재처리를 멱등하게 한다.
- self-hosted ELSER 같은 ML model은 올리지 않고 외부 embedding model이 만든 vector만 저장한다. 이 서버에서 Elasticsearch ML workload까지 같이 운영하지 않는다.

### 이 통합으로 제거되는 것

- PostgreSQL의 pgvector extension과 vector table
- pgvector 전용 HNSW index·migration·backup 고려
- PostgreSQL 전문 검색과 pgvector 결과를 애플리케이션에서 합치는 별도 hybrid 구현
- “어떤 vector DB를 고를 것인가”라는 미결정

다만 PostgreSQL 원본에서 Elasticsearch를 복구하는 전체 재색인, 이벤트 유실·순서 역전 대응, 검색 장애 시 일반 API가 계속 동작하는 격리는 여전히 필요하다.

## 2. 관측성까지 Elasticsearch로 합치지 않는 이유

### 검토한 대안

Elastic Observability는 logs, metrics, traces를 Elasticsearch에 저장하고 OpenTelemetry를 지원한다([Elastic Observability](https://www.elastic.co/docs/solutions/observability)). self-managed 환경의 공식 OpenTelemetry 경로는 application/edge collector가 OTLP를 보내고 **gateway mode의 Elastic Agent**가 처리한 뒤 Elasticsearch에 기록하는 구조다([Elastic OpenTelemetry reference architecture](https://www.elastic.co/docs/reference/opentelemetry/architecture)). APM UI, service inventory와 service map을 제대로 사용하려면 Kibana까지 필요하다. Elasticsearch의 `/_otlp` endpoint에 직접 쓰면 trace enrichment와 aggregated APM metrics가 생기지 않아 해당 APM 화면이 비게 된다([Elasticsearch OTLP endpoint](https://www.elastic.co/docs/manage-data/ingest/otlp-endpoint)).

Prometheus 형식 metrics도 Elastic Agent가 exporter scrape, Prometheus federation/API, remote write 방식으로 받을 수 있다([Elastic Prometheus integration](https://www.elastic.co/docs/current/en/integrations/prometheus)). 따라서 기능적으로는 다음 완전 교체가 가능하다.

```text
EDOT / OTel SDK + Elastic Agent gateway
                 └─ logs + metrics + traces → Elasticsearch → Kibana
```

기술적으로는 이 경우 Loki와 Tempo를 제거하고 Prometheus도 축소할 수 있다. 하지만 아래 자원·책임 경계 때문에 채택하지 않는다.

### 통합 시 감수할 trade-off

Elasticsearch는 heap 밖의 off-heap buffer와 filesystem cache가 필요하므로 heap을 컨테이너 RAM의 50% 이하로 두라고 공식 문서가 권장한다. 실제 process 사용량은 `Xmx`보다 커지는 것이 정상이다([Elasticsearch JVM settings](https://www.elastic.co/docs/reference/elasticsearch/jvm-settings)). 따라서 `Xmx=512MiB`라면 컨테이너 1 GiB가 사실상 최저 실험선이고, 일반 검색·HNSW vector·지속적인 telemetry ingest를 함께 넣으려면 더 큰 여유가 필요하다.

Kibana 공식 sizing guidance는 일반적인 analytics 사용에서 1 vCPU와 2 GB부터 시작하도록 안내하며, 일부 고급 기능에서도 최소 2 GB를 권장한다([Kibana traffic scaling](https://www.elastic.co/docs/deploy-manage/production-guidance/kibana-traffic-scaling-considerations), [Kibana memory](https://www.elastic.co/docs/deploy-manage/production-guidance/kibana-configure-memory)). 여기에 Elastic Agent gateway와 telemetry 보존 공간도 추가된다. medium의 4 GiB에는 모듈 앱과 Grafana 관측성 stack이 이미 배치되므로 Kibana 2 GB를 상시 추가하기 어렵다.

또한 검색 content와 telemetry time series는 수명과 write pattern이 다르다. Elastic도 content와 계속 누적되는 time-series data를 구분하며, logs·metrics에는 data stream과 retention lifecycle이 필요하다고 설명한다([Elasticsearch data lifecycle](https://www.elastic.co/docs/manage-data/lifecycle), [Data streams](https://www.elastic.co/docs/manage-data/data-store/data-streams)). large의 50 GB에서 로그 폭증이 검색 index merge와 RAG query를 방해할 수 있으므로, 관측성 통합안을 채택한다면 짧은 lifecycle이 필요하다.

현재 2노드에서 다음 구성을 상시 profile로 사용한다.

```text
application ──metrics──→ Prometheus ─┐
            ──logs─────→ Loki ───────┼→ Grafana
            ──traces───→ Tempo ──────┘

search module ─────────→ Elasticsearch ←──────── customersupport RAG
                              │
                              └─ health/heap/GC/indexing/search metrics → Prometheus
```

### 제외한 절충안: Loki만 Elasticsearch로 바꾸기

Kibana 없이도 Grafana의 공식 Elasticsearch data source로 ES에 저장된 logs와 metrics를 조회하고 alert를 만들 수 있다([Grafana Elasticsearch data source](https://grafana.com/docs/grafana/latest/datasources/elasticsearch/)). 따라서 다음 구성은 기술적으로 가능하다.

```text
logs → collector → logs-* data stream → Elasticsearch → Grafana
metrics → Prometheus → Grafana
traces → Tempo → Grafana
```

하지만 현재 측정 예산에서 Loki는 약 150 MiB로 잡혀 있다. Loki 하나를 제거해서 절약하는 메모리보다 Elasticsearch의 지속적인 로그 색인, segment merge, filesystem cache와 디스크 사용이 더 큰 변수가 된다. 이 절충안은 기본 설계로 채택하지 않고, 동일 부하에서 아래를 측정하는 선택적 spike로만 둔다.

- Elasticsearch heap·RSS와 GC pause
- 서비스 검색과 RAG p95 변화
- 초당 로그 ingest와 3일/7일 retention의 disk 증가량
- Loki 대비 Grafana Explore 사용성과 trace correlation

결론적으로 전체 교체와 Loki만 교체하는 절충안 모두 제외한다. 포트폴리오의 관측성 증거는 Grafana에서 metrics → trace → logs로 연결하고, Elasticsearch는 검색·RAG 부하와 재색인 복구를 깊게 보여주는 데 집중한다.

## 3. Kafka·Redis·PostgreSQL과의 경계

### Kafka

- PostgreSQL transaction에서 발생한 integration event는 Kafka가 전달·보존·재생한다.
- Elasticsearch consumer는 이 event를 소비해 검색 projection을 갱신한다.
- Elasticsearch 장애 시 Kafka lag 또는 outbox가 쌓이고 복구 후 따라잡는다.
- ES가 Kafka topic이나 outbox를 대체하지 않는다.

Elastic의 Kafka integration은 broker logs와 metrics, consumer lag를 수집할 수 있지만 이는 Kafka의 **관측**이지 메시징 기능 통합이 아니다([Elastic Kafka integration](https://www.elastic.co/docs/reference/integrations/kafka)). 현재 관측성 backend가 Prometheus이므로 Kafka exporter/JMX metrics를 Prometheus에 모으는 기존 설계를 유지한다.

### Redis

- 채팅 전달 작업은 PostgreSQL outbox에서 Redis Streams로 적재한다. consumer group의 PEL·ACK·회수와 DB 발행 체크포인트·유실 재구성을 사용한다(2026-10-29 변경 결정). 상세 기준은 [채팅 전달 설계](infrastructure/redis-chat.md)를 따른다.
- Redis Pub/Sub은 단일 chat-app의 로컬 STOMP 전달을 맡는다. 서버 간 fan-out은 향후 확장 경로다.
- Elasticsearch는 채팅 전달 경로에 넣지 않는다.
- 채팅 본문 검색을 새 요구사항으로 정한 경우에만 PostgreSQL의 공개 가능한 projection을 별도 private search index로 만든다. 현재 MVP에는 추가하지 않는다.

Redis logs·slowlog·keyspace·eviction·hit/miss metrics도 Elastic integration으로 수집할 수 있지만([Elastic Redis integration](https://www.elastic.co/docs/reference/integrations/redis)), 현재는 Prometheus/Grafana에서 같은 운영 지표를 본다.

### PostgreSQL

- 회원, 출연, 강의, HP 원장, 고객센터 원문, 채팅 이력은 PostgreSQL이 source of truth다.
- ES document에는 검색과 출처 표시에 필요한 최소 공개 정보만 복제한다.
- HP 원장, 결제키, 계좌, 개인 문의와 같은 민감 데이터는 ES에 색인하지 않는다.
- ES snapshot을 갖지 못해도 PostgreSQL 원본과 Kafka event 또는 전체 재색인 API로 복구할 수 있어야 한다.

## 4. EC2 2노드 배포 권고

### 채택한 실행 구성

- Elasticsearch 1 node, prod만 배치
- large의 container limit 1.5 GiB, heap 512~768 MiB로 시작하되 충분한 값으로 간주하지 않고 부하 시험으로 검증
- primary shard 수를 index당 1로 작게 시작하고 single node이므로 replica 0
- 서비스 검색 index와 고객센터 vector index만 수용
- Kibana, Elastic Agent gateway, Logstash, ELSER/ML node를 배포하지 않음
- Elasticsearch 8.16+에서는 host의 `vm.max_map_count=1048576`을 적용. 공식 Docker 운영 문서도 같은 값을 요구한다([Elasticsearch Docker production configuration](https://www.elastic.co/docs/deploy-manage/deploy/self-managed/install-elasticsearch-docker-prod))
- 보안 기능을 켜고 9200을 외부에 노출하지 않음
- 기존 문서대로 dev workload는 로컬/CI로 내리고 EC2에는 prod 위주로 배치

`heap 512~768 MiB / container 1.5 GiB`는 시작값이다. 다음 중 하나가 발생하면 large의 다른 일시 workload를 줄인 뒤 Elasticsearch limit을 다시 측정한다.

- old GC 또는 memory pressure가 지속됨
- HNSW 색인/검색 중 OOM 또는 긴 pause가 발생함
- service search p95가 vector 재색인 중 크게 악화됨
- segment merge 중 CPU credit과 disk latency가 급증함

단일 node에는 replica가 없으므로 고가용성이 아니다. Elastic 공식 문서도 one-node cluster는 resilient하지 않다고 명시한다([Small cluster resilience](https://www.elastic.co/guide/en/elasticsearch/reference/current/high-availability-cluster-small-clusters.html)). 포트폴리오에는 이 제한과 “삭제 후 원본에서 재색인” 복구 절차를 함께 적는다.

### 제외한 Elastic Observability 전환안

다음 A/B 구성도 검토했지만 최종 구현 범위에서 제외한다.

1. Prometheus·Loki·Tempo·Grafana 기준선
2. Elastic Agent gateway + Elasticsearch + Kibana로 logs·metrics·traces 전체 교체

Kibana를 위해 medium의 모듈 앱·Grafana stack을 내려야 하므로 정상 2노드 topology와 관측성 backend 비교를 동시에 보여줄 수 없다. 검색·RAG Elasticsearch에 telemetry ingest까지 추가하면 장애 격리도 약해진다. 따라서 이 profile과 아래 구성은 구현 티켓으로 만들지 않는다.

구성은 다음과 같다.

```text
Spring/OpenTelemetry ──OTLP──→ Elastic Agent gateway
                                  ├─ logs
                                  ├─ metrics
                                  └─ traces/APM
                                         ↓
Kafka → search/RAG indexer ─────→ Elasticsearch ─────→ Kibana
```

- 애플리케이션이나 pod가 Elasticsearch `/_otlp`에 직접 쓰지 않는다.
- 검색/RAG 일반 index와 telemetry data stream을 naming·role·retention으로 분리한다.
- logs·metrics·traces는 짧은 retention과 sampling을 적용한다.
- 실제 운영으로 전환할 때는 검색 cluster와 observability cluster 분리를 우선 검토한다.

## 5. 티켓에 반영할 변경

### 기존 `[AI] 벡터 DB 선택`

선택이 아니라 구현 티켓으로 바꾼다.

> `[AI] Elasticsearch 기반 Hybrid RAG 구축`

하위 범위:

- Spring AI `ElasticsearchVectorStore` spike와 Boot 4.1.1 호환 검증
- 고객센터 소유 index mapping과 권한 분리
- nori BM25 기준선, dense vector 기준선, RRF hybrid 비교
- FAQ·공지·정책 ETL, 멱등 재색인, alias 교체
- golden question set의 hit@k·MRR·답변 근거성 평가
- pgvector 제거

### 검색 티켓

> `[SEARCH] Elasticsearch 기반 통합 검색 구축`

RAG와 같은 물리 cluster를 사용하되 index·계정·adapter 소유권은 분리한다. Kafka event 색인, nori 사용자 사전, external versioning, alias 재색인, 원본 복구를 포함한다.

### 관측성 티켓

관측성은 Elasticsearch와 분리한다.

> `[OBSERVABILITY] Grafana observability stack 구축`

하위 범위:

- Micrometer metrics, OpenTelemetry trace와 구조화 JSON log 계측
- Prometheus scrape·retention·alert rule
- Alloy의 pod log 수집과 OTLP trace pipeline
- Loki·Tempo retention과 Grafana datasource 연결
- API·Kafka·Redis·Kubernetes dashboard·alert
- Elasticsearch exporter/API metrics dashboard

- cluster health와 unassigned shard
- heap usage, GC, process RSS
- index size와 document count
- indexing rate·latency·failure
- search request rate·p95·failure
- Kafka event 발생부터 검색 반영까지 index freshness
- RAG retrieval p95와 empty-result rate

Mimir는 Prometheus와 역할이 겹치고 분산 장기 저장을 시연하지 않으므로 제외한다. 이 구성은 엄밀한 LGTM이 아니라 **Grafana observability stack(Prometheus + Alloy + Loki + Tempo + Grafana)**으로 부른다.

## 최종 결정문

> Elasticsearch는 서비스 통합 검색과 고객센터 RAG Vector DB의 공통 물리 cluster로 사용한다. 각 bounded context는 별도의 index namespace와 권한을 소유하고 PostgreSQL 원본에서 index를 재구축할 수 있어야 한다. 고객센터 RAG는 nori BM25와 dense vector kNN 결과를 RRF로 결합한다. Kafka와 Redis는 각각 이벤트 전달과 채팅 전달 역할을 유지한다. 관측성은 medium의 Prometheus·Alloy·Loki·Tempo·Grafana가 담당하며 Elasticsearch에는 telemetry를 저장하지 않는다. Kibana, Elastic Agent, Elastic APM, Logstash, Mimir와 별도 vector DB는 도입하지 않는다.
