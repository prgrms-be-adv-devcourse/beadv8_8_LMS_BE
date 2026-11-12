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

인플루언서 검색(#767)부터 구현했다. 모듈 구조·index·이벤트 처리·API는 [검색 모듈 문서](../../search/module.md)에 있다.

| 항목 | 내용 |
| --- | --- |
| 앱 | `auxiliary-app`의 `search` 모듈(#684, #714), database `auxiliary`의 schema `search`. `search`는 검색 index(`search-*`)만 소유한다. 같은 앱의 챗봇은 `chatbot-*` index를 따로 소유하며, 둘은 물리 cluster만 공유하고 API key도 따로다 |
| 입력 | [1장](kafka.md) Kafka의 integration event. 강의 등록·수정·삭제·판매 상태 변경, 모집글 등록·수정·마감, 인플루언서 프로필 변경 등. 발행하는 모듈은 검색의 존재를 모른다 |
| 진실의 원천 | 각 모듈의 PostgreSQL. ES index는 언제든 원본에서 다시 만들 수 있는 projection이다 |
| API | 대상별 경로(#767 Q14): `GET /api/v1/search/influencers`(#771), `GET /api/v1/search/lectures`(#883, 비회원 포함), 모집은 `/search/recruits`로 더한다. 통합 검색은 강의 색인과 FE 화면이 정해지면. 자동완성·추천은 범위 밖 |
| 멱등 | document ID = 원본 ID, 원본 version을 문서 필드에 두고 painless 조건부 쓰기로 더 큰 version만 반영한다. ES external versioning은 쓰지 않는다: 주기 통계 부분 갱신이 문서 버전을 올려 다음 원본 이벤트를 거절하게 만든다(#771 시험). 숨김은 삭제 대신 tombstone이다. 같은 이벤트를 두 번 받아도 결과가 같다 |
| 공개 범위 | 판매 중인 강의, 공개된 모집글, 정상 인플루언서의 공개 프로필처럼 서비스에서 보이는 것만 색인한다. 검색 API는 policy.md를 따른다(인플루언서 검색은 로그인 회원만, 강의 검색은 강의 목록처럼 비회원도 쓰고 회원·client IP별 호출 제한을 둔다). 연락처 같은 개인 정보와 HP 거래 금액은 넣지 않는다(강의 목록에 보이는 강의 가격은 넣는다, #909) |

## 한국어 분석

nori 형태소 분석기(`analysis-nori` plugin)를 쓴다([nori 문서](https://www.elastic.co/docs/reference/elasticsearch/plugins/analysis-nori)). plugin은 공식 이미지에 ECK init container로 설치한다(#374). custom image를 GHCR에 발행·관리하는 비용이 이 규모에 비해 커서 택했다. pod가 새로 뜰 때마다 Elastic 저장소에서 plugin을 내려받으므로 기동이 그만큼 느려지고, 그때 외부 접속이 필요하다.

2026-10-22 측정에서 기본 사전은 "인플루언서 강의를 구매했습니다"의 "인플루언서"를 `인`·`플`·`언서`로 쪼갰다. 서비스 용어(인플루언서, 합방, 치지직 등)와 게임 이름·줄임말은 **사용자 사전**(한 단어 규칙, `decompound_mode: discard`)에 넣고, 사전을 바꾸면 재색인한다. 동의어는 **검색할 때만** `synonym_graph`로 쓰고 ES Synonyms API 묶음으로 관리한다(바꾸면 ES가 검색 분석기를 다시 읽어 재색인이 없다). 사용자 사전과 동의어 목록은 레포 파일이 원본이다(#771).

## 색인과 재색인

1. **처음 적재**: 앱 시작 때(비동기, 기동을 막지 않음)와 10분마다 확인해 alias가 없으면, 원본 모듈의 내부 API(D62, 인플루언서는 회원 목록·통계)를 page 단위로 끝까지 읽어 새 index에 bulk로 넣는다. 다른 모듈의 DB를 직접 읽지 않는다. 문서 수를 확인한 뒤 alias를 붙이고, 그동안 alias가 없어 건너뛴 이벤트는 아래 3의 따라잡기가 반영한다(#773).
2. **실시간 반영**: Kafka consumer가 이벤트마다 문서를 upsert하거나 숨긴다(tombstone). 원본 version 조건부 쓰기로 멱등 처리하고, 실패는 retry와 DLT로 보낸다. 자주 바뀌는 숫자(팔로워·평점)는 이벤트 대신 원본 내부 API를 10분마다 읽어 맞춘다(#771). 놓친 이벤트는 10분마다 원본 변경분 API를 읽는 증분 보정이 바로잡는다(#772).
3. **재색인**: 사전이나 mapping을 바꾸면 설정 해시가 붙은 새 index(`search-influencers-<해시>`)에 처음 적재와 같은 순서로 채운다. 시작 시각 T를 기록하고, 새 index의 문서 수가 원본과 같은지 확인한 뒤 alias를 한 요청(remove + add)으로 옮긴다. 그사이 이벤트는 옛 index로 갔으므로, **T − 1분부터 증분 보정을 한 번 돌려 따라잡는다**(Kafka offset을 다시 읽거나 두 index에 함께 쓰는 dual write는 하지 않는다). 그 뒤 옛 index를 지운다. 실패하면 alias는 옛 그대로 두고 새 index를 지운 뒤 다음 확인 때 다시 한다. 동의어는 해시에 넣지 않아 바꿔도 재색인하지 않는다(#773).
4. **정합성 점검**: 원본 수와 index 문서 수를 비교하는 주기 작업을 두고 차이를 지표로 낸다(#772, alert는 필요해지면 붙인다).

절차와 실패 처리는 [검색 모듈 문서](../../search/module.md) 6·7장에 있다. 강의(#884)는 원본에 변경분 API가 없어 2의 증분 보정과 3의 따라잡기를 **동기화 키셋 API 전체 읽기 + version 비교**로 한다(강의 10분, 리뷰 1시간, 판매자 사본은 회원 변경분 10분). 처음 적재는 판매자·리뷰 사본을 먼저 맞춘 뒤 강의를 넣는다(모듈 문서 15장).

## 운영

- **배치**: `hapbang-platform` namespace에 ECK의 Elasticsearch CR로 만든다. node 1개(`nodeSets[].count: 1`), replica shard 0. 단일 node라 HA는 없다고 README에 적는다.
- **메모리**: [3장](k3s.md#ec2-2노드-자원-예산) 예산대로 컨테이너 limit 1.5 GiB(heap은 limit의 50% 이하). ECK 기본값 2Gi를 그대로 두지 않는다. 대기 상태 측정으로는 1 GiB(heap 512 MiB)도 떴으므로 부하 측정 뒤 줄일 수 있다.
- **host 설정**: large host에 `vm.max_map_count=1048576`을 설정한다(k3s 설치 때 sysctl로 고정). 262144는 ES 8.15 이하 값이다([ECK virtual memory](https://www.elastic.co/docs/deploy-manage/deploy/cloud-on-k8s/virtual-memory)). 로컬 k3d는 Docker Desktop VM 값을 바꾸지 않고 `node.store.allow_mmap: false`로 띄운다.
- **보안**: security를 켜 두고 앱은 모듈마다 role·API key를 Secret으로 받는다(chatbot은 role `chatbot`·index `chatbot-*`, 주소·CA·만드는 절차는 [k8s.md](../../deploy/k8s.md#elasticsearch-앱-접근), #726). 9200 port는 security group과 Gateway route에 열지 않는다. platform에는 ingress NetworkPolicy를 두지 않으므로([gateway.md](../../deploy/gateway.md)) cluster 안의 pod는 9200에 닿고, 접근 제어는 role·API key가 맡는다.
- **환경**: EC2에는 prod만 두므로 ES도 하나다. index 이름에 환경 prefix를 붙이지 않는다.
- **백업**: S3 snapshot은 S3 용도를 넘으므로 하지 않는다. 원본에서 다시 만들 수 있으므로 재색인 절차를 복구 수단으로 둔다.
- **관측**: Prometheus/Grafana에서 index별 문서 수, 색인 지연, consumer lag, 검색 p95, heap·GC를 본다.
- **조회·관리 GUI — Elasticvue**: index, mapping, 문서, analyzer 결과를 확인할 때 Elasticvue를 쓴다. 오픈소스(MIT)이고 ES 모든 version을 지원한다([Elasticvue](https://github.com/cars10/elasticvue)).
  - **cluster에 띄우지 않는다.** 개발자 PC의 **데스크톱 앱이나 브라우저 확장**을 쓰고, SSH 터널이나 `kubectl port-forward`로 ES 9200에 붙는다. 이 두 형태는 ES의 CORS 설정이 필요 없다. Docker·web 형태는 `http.cors.*` 설정이 필요하고 pod 하나가 늘어나므로 쓰지 않는다.
  - 9200은 계속 외부에 열지 않는다. 접속은 `kubectl port-forward`로 한다([k8s.md#관측성-접속](../../deploy/k8s.md#관측성-접속)과 같은 방식).
  - 계정을 나눈다. 평소 조회는 **읽기 전용 role**(index 조회·`_analyze`)로 하고, index 삭제·alias 교체 같은 관리 작업은 별도 관리자 계정으로 한다. prod에서 GUI로 index를 지우는 사고를 막기 위해서다.
  - 사용자 사전을 바꾼 뒤 nori 분석 결과(`_analyze`)를 확인하는 데 특히 쓴다.
  - Elasticvue는 index·mapping·analyzer를 직접 점검하는 개발 도구다. Grafana는 운영 지표·로그·trace를 보는 도구라 역할이 겹치지 않는다.
- **license**: 공식 binary는 Elastic License 2.0이다. 2024-08부터 source에 AGPLv3 선택지가 생겼다([Elastic 발표](https://www.businesswire.com/news/home/20240829537786/en)). 직접 운영하며 검색 기능을 쓰는 이 프로젝트는 ELv2의 제한(ES를 관리형 서비스로 제공하는 것 등)에 걸리지 않는다.
- **client 호환**: Boot 4.1.1 BOM의 Elasticsearch Java client 9.4.5가 ES 9.5.5 서버에서 `_analyze`·Synonyms API·bulk·script·집계까지 동작함을 확인했다(#771 시험). BOM 버전 그대로 client를 직접 쓴다(Spring Data Elasticsearch는 쓰지 않는다). 검색은 챗봇과 다른 API key로 붙는 별도 client를 둔다.

## 고객센터 RAG와의 관계

ES는 통합 검색과 고객센터 RAG의 공통 **물리 검색 플랫폼**으로 사용한다. `search`는 공개 강의·모집·인플루언서 index를, `customersupport`는 FAQ·공지·정책 hybrid RAG index를 각각 소유한다. 서로 다른 role/API key와 index pattern을 사용하고 상대 모듈의 repository나 API를 경유하지 않는다. 원본은 각 모듈의 PostgreSQL이며 모든 index는 원본과 Kafka event 또는 전체 재색인으로 복구할 수 있어야 한다.

## 결정 필요

- 모집 검색의 색인 필드, 기존 모집 `keyword` 검색을 검색 모듈로 옮길지 남길지(프론트 공지 필요). 인플루언서는 #771, 강의는 #881(색인 필드 #882, API #883, 가격 #909, 보정·처음 적재·재색인 #884)에서 정했고 #884 배포 뒤 처음 적재가 끝나면 운영 강의 검색이 열린다. 강의 목록 `GET /api/v1/lectures?keyword=`는 그대로 두고, 없앨지는 FE 전환 뒤 강의 담당과 정한다(L1). 강의 데이터가 커져 전체 읽기 보정이 길어지면 강의·리뷰 변경분 API를 정한다
- 서비스 검색과 RAG 동시 부하에서 Elasticsearch memory·disk 한도

## 완료 증거

- "인플루언서" 등 서비스 용어가 사용자 사전대로 분석되고, 형태소 단위 검색과 relevance 정렬이 PostgreSQL `LIKE`보다 나은 결과를 golden query set으로 보인다.
- 강의 수정 이벤트 뒤 정해 둔 시간 안에 검색 결과에 반영되고, 같은 이벤트를 두 번 받아도 문서가 하나다.
- 검색 모듈(auxiliary-app)을 멈춘 동안 쌓인 이벤트가 재시작 후 반영되고, consumer lag가 0으로 돌아온다.
- mapping 변경 재색인을 alias 교체로 하는 동안 검색 API가 끊기지 않는다.
- ES를 지우고 다시 만들어도 초기 적재만으로 같은 문서 수가 된다.
