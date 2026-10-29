# 9. 검색(search) 모듈과 Elasticsearch (#234)

상위 문서: [최종 프로젝트 인프라 설계](../final-project-infrastructure.md). 결정 기록은 #369다.

## 현재 상태

지금 검색은 모듈마다 PostgreSQL에서 직접 조회한다.
- 강의: 제목 `LIKE`(`LectureRepository.findOnSaleOrderByRating`)
- 고객센터: 내 문의 목록 keyword
- 출연: 모집글 검색·필터는 아직 TODO

모듈 간 테이블 JOIN이 금지라 강의·모집글·인플루언서를 한 번에 찾는 통합 검색은 쿼리 하나로 만들 수 없다. `LIKE`는 한국어 형태소를 모르고 relevance 정렬도 없다.

## 목표

검색을 **독립 bounded context `search`**로 두고, 검색 엔진은 **Elasticsearch**로 한다. OpenSearch 등 다른 엔진은 비교하지 않는다(팀 결정).

| 항목 | 내용 |
| --- | --- |
| 앱 | `search-app`, database `search`. `search`는 통합 검색 index만 소유한다. 고객센터(`customer-support-app`)는 RAG index를 직접 소유하며, 두 앱은 물리 cluster만 공유한다 |
| 입력 | [1장](kafka.md) Kafka의 integration event. 강의 등록·수정·삭제·판매 상태 변경, 모집글 등록·수정·마감, 인플루언서 프로필 변경 등. 발행하는 모듈은 검색의 존재를 모른다 |
| 진실의 원천 | 각 모듈의 PostgreSQL. ES index는 언제든 원본에서 다시 만들 수 있는 projection이다 |
| API | `GET /api/v1/search?type=LECTURE\|RECRUIT\|INFLUENCER&keyword=...`(통합 검색, 페이지). 자동완성·추천은 범위 밖 |
| 멱등 | document ID = 원본 ID, 이벤트의 aggregate version으로 오래된 이벤트가 새 문서를 덮지 않게 한다(external versioning). 같은 이벤트를 두 번 받아도 결과가 같다 |
| 공개 범위 | 판매 중인 강의, 공개된 모집글 등 비회원도 볼 수 있는 것만 색인한다. 개인 정보와 HP 금액은 넣지 않는다 |

## 한국어 분석

nori 형태소 분석기(`analysis-nori` plugin)를 쓴다([nori 문서](https://www.elastic.co/docs/reference/elasticsearch/plugins/analysis-nori)). plugin은 공식 이미지에 ECK init container로 설치한다(#374). custom image를 GHCR에 발행·관리하는 비용이 이 규모에 비해 커서 택했다. pod가 새로 뜰 때마다 Elastic 저장소에서 plugin을 내려받으므로 기동이 그만큼 느려지고, 그때 외부 접속이 필요하다.

2026-10-22 측정에서 기본 사전은 "인플루언서 강의를 구매했습니다"의 "인플루언서"를 `인`·`플`·`언서`로 쪼갰다. 서비스 용어(인플루언서, 합방, 치지직, 출연 모집 등)는 **사용자 사전**에 넣고, 사전을 바꾸면 재색인한다. 사용자 사전과 동의어 목록은 레포에서 관리한다.

## 색인과 재색인

1. **초기 적재**: 각 모듈의 공개 조회 API(또는 내부 API, D62)를 page 단위로 읽어 새 index에 bulk로 넣는다. 다른 모듈의 DB를 직접 읽지 않는다. 실행 방식은 아직 정하지 않았다.
2. **실시간 반영**: Kafka consumer가 이벤트마다 문서를 upsert·delete한다. inbox로 멱등 처리하고, 실패는 retry와 DLT로 보낸다.
3. **재색인**: 사전이나 mapping을 바꾸면 versioned index(`lectures-v2`)를 새로 만들어 초기 적재를 다시 돌린다. 그동안 들어온 이벤트는 두 index에 모두 쓰고, 검증한 뒤 alias를 원자적으로 바꾼다. 이전 index는 확인한 뒤 지운다.
4. **정합성 점검**: 원본 수와 index 문서 수를 비교하는 주기 작업을 두고, 차이가 나면 선택한 관측성 UI에 alert한다.

## 운영

- **배치**: `hapbang-platform` namespace에 ECK의 Elasticsearch CR로 만든다. node 1개(`nodeSets[].count: 1`), replica shard 0. 단일 node라 HA는 없다고 README에 적는다.
- **메모리**: [3장](k3s.md#ec2-2노드-자원-예산) 예산대로 컨테이너 limit 1.5 GiB(heap은 limit의 50% 이하). ECK 기본값 2Gi를 그대로 두지 않는다. 대기 상태 측정으로는 1 GiB(heap 512 MiB)도 떴으므로 부하 측정 뒤 줄일 수 있다.
- **host 설정**: large host에 `vm.max_map_count=1048576`을 설정한다(k3s 설치 때 sysctl로 고정). 262144는 ES 8.15 이하 값이다([ECK virtual memory](https://www.elastic.co/docs/deploy-manage/deploy/cloud-on-k8s/virtual-memory)). 로컬 k3d는 Docker Desktop VM 값을 바꾸지 않고 `node.store.allow_mmap: false`로 띄운다.
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

## 고객센터 RAG와의 관계

ES는 통합 검색과 고객센터 RAG의 공통 **물리 검색 플랫폼**으로 사용한다. `search`는 공개 강의·모집·인플루언서 index를, `customersupport`는 FAQ·공지·정책 hybrid RAG index를 각각 소유한다. 서로 다른 role/API key와 index pattern을 사용하고 상대 모듈의 repository나 API를 경유하지 않는다. 원본은 각 모듈의 PostgreSQL이며 모든 index는 원본과 Kafka event 또는 전체 재색인으로 복구할 수 있어야 한다.

## 결정 필요

- 검색 대상과 색인 필드, 기존 모듈 `keyword` 검색을 검색 모듈로 옮길지 남길지(프론트 공지 필요)
- Spring Boot 4.1.1의 Spring Data Elasticsearch·Spring AI와 선택한 Elasticsearch server version 호환성
- 서비스 검색과 RAG 동시 부하에서 Elasticsearch memory·disk 한도

## 완료 증거

- "인플루언서" 등 서비스 용어가 사용자 사전대로 분석되고, 형태소 단위 검색과 relevance 정렬이 PostgreSQL `LIKE`보다 나은 결과를 golden query set으로 보인다.
- 강의 수정 이벤트 뒤 정해 둔 시간 안에 검색 결과에 반영되고, 같은 이벤트를 두 번 받아도 문서가 하나다.
- search-app을 멈춘 동안 쌓인 이벤트가 재시작 후 반영되고, consumer lag가 0으로 돌아온다.
- mapping 변경 재색인을 alias 교체로 하는 동안 검색 API가 끊기지 않는다.
- ES를 지우고 다시 만들어도 초기 적재만으로 같은 문서 수가 된다.
