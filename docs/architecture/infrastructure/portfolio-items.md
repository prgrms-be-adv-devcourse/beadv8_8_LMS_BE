# 6·7. 포트폴리오 추가 항목

상위 문서: [최종 프로젝트 인프라 설계](../final-project-infrastructure.md). 결정 기록은 #369다.

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
| Must | Testcontainers 장애 test | 도입 (컨테이너 pause·stop, Toxiproxy 없음) | 실제 PostgreSQL·Kafka·Redis 통합 test와 단절·복구 |
| Must | 모듈별 Gradle test fixtures | 도입 | test data builder·fake의 소유권과 소비 관계 |
| Must | OpenAPI contract CI | 도입 | HTTP 계약의 문서화와 호환성 검증 |
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

### Testcontainers 장애 test

repository·serialization·멱등 consumer test는 mock broker가 아니라 Testcontainers의 PostgreSQL, Kafka, Redis를 사용한다. 장애는 Toxiproxy 없이 컨테이너와 listener를 직접 멈춰 만든다(#339, 2026-11-03).

- **broker·DB 단절**: 공유 컨테이너를 `docker pause`/`unpause`한다(`KAFKA.getDockerClient().pauseContainerCmd(...)`). 도우미는 `:test-support`의 Testcontainers 설정 class에 둔다. Kafka 컨테이너는 stop/start하면 포트가 바뀌어 client가 다시 붙지 못하므로 pause를 쓴다. 컨테이너를 JVM 전체가 같이 쓰므로 `finally`에서 반드시 되살린다.
- **Redis 단절**: 고정 포트 컨테이너를 stop/start한다(chat-app `FixedPortRedis`, `ChatRedisOutageE2ETest`).
- **consumer 중단**: Kafka listener container를 `KafkaListenerEndpointRegistry`에서 꺼내 `stop()`/`start()`한다. Redis 구독은 `ChatSubscriptionOutageE2ETest`가 같은 방식으로 멈춘다.
- **응답 유실**: `KafkaTemplate`을 spy로 감싸 broker에는 도착했지만 앱은 실패로 받는 상황을 만든다(`BillingEventKafkaTest.republish()`).
- producer `max.block.ms`처럼 기다리는 시간이 긴 client 설정은 test에서 몇 초로 줄인다.

Toxiproxy를 쓰지 않는 이유: 완료 조건에 필요한 장애는 멈춤과 복구뿐이고, Toxiproxy만 만들 수 있는 느린 응답·대역폭 제한 시나리오는 없다. 프록시 컨테이너와 client 연결 경로를 하나 더 두는 비용만 생긴다. timeout 직전의 지연을 재현해야 하는 요구가 생기면 Testcontainers 공식 Toxiproxy module([공식 문서](https://java.testcontainers.org/modules/toxiproxy/))로 다시 검토한다.

최소 장애 test matrix는 다음과 같다.

- Kafka publish 응답 유실 → Modulith publication 재발행 → consumer 결과 한 번
- Kafka broker pause 중 발행·소비 → unpause 뒤 미완료 publication 재발행과 backlog 소비 → 같은 자연 키의 업무 반영은 한 번(재전달은 멱등 처리가 흡수)
- 채팅 relay 선점 직후 종료 → lease 회수, XADD 직후 DB 기록 전 종료 → 중복 적재 흡수·옛 claimToken 무시, Stream 소비/PUBLISH/DB 체크포인트/XACK 사이 종료 → PEL 회수·중복 흡수, Stream 유실 → DB 미완료 작업 재구성
- PostgreSQL 컨테이너 pause로 commit 응답 timeout → 같은 command 재시도 → HP 원장 불변식 유지

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
| **Elasticsearch (선택)** | [9장](search.md)의 물리 cluster에 고객센터 소유 index 추가 | BM25와 kNN hybrid 검색, nori 한국어 분석. 새 server가 늘지 않음 | 일반 검색과 RAG가 자원을 공유하므로 index·권한 분리와 동시 부하 검증이 필요하다 |
| Amazon S3 Vectors | AWS 관리형 vector bucket | 서버 없이 사용량만큼 과금. metadata filter. Spring AI S3 Vector Store 지원 | 새 bucket 종류와 `s3vectors` IAM namespace를 쓴다([AWS 문서](https://docs.aws.amazon.com/AmazonS3/latest/userguide/s3-vectors.html)). "S3 버킷 1개, 업로드 용도" 범위를 넘으므로 팀 합의가 필요하다. 조직 SCP 허용 여부와 서울 region 지원을 먼저 확인한다. 자주 쓰지 않는 query는 지연이 1초 가까이 될 수 있다 |
| PostgreSQL 전문 검색만 | vector 없이 `tsvector` | embedding API 호출과 비용 없음. 가장 단순한 기준선 | 기본 설정에는 한국어 사전이 없어 형태소 단위 검색이 약하다. 의미가 같은 다른 표현을 찾지 못한다 |
| Spring AI `SimpleVectorStore` | 앱 메모리 | 의존성 없음 | 공식 문서가 test·demo 전용이라고 명시한다. 단위 test의 fake로만 쓴다 |

Chroma, Weaviate, Milvus도 Spring AI가 지원한다. 그러나 Qdrant와 같은 별도 stateful server라는 한계가 있고, Milvus는 etcd·object storage까지 필요해 EC2 예산에 맞지 않는다.

판단: **Elasticsearch를 Vector DB로 선택한다.** 검색용으로 반드시 배포할 process를 고객센터 RAG도 물리적으로 공유하므로 pgvector·Qdrant·Redis Vector를 함께 운영하지 않는다. 위 대안 표는 선택 과정의 기록이며 구현 후보 목록이 아니다. Elasticsearch에는 검색·RAG index만 두고 telemetry는 저장하지 않는다.

### Kafka 이벤트 계약과 OpenAPI contract

schema registry 서버(Apicurio, Confluent 등)는 **두지 않는다.** 앱이 따로 배포되므로(#363) version이 다른 producer와 consumer가 꽤 오래 함께 돌 수 있다. 예를 들어 billing-app만 먼저 배포된 경우다. 이 문제는 runtime registry 대신 아래 규칙으로 막는다.

- **호환 규칙**: 같은 `eventVersion` 안에서는 필드 추가(선택 필드)만 허용한다. 삭제, 이름 변경, 타입 변경, 필수 필드 추가는 금지한다. 그런 변경은 `eventVersion`을 올린 새 레코드 타입을 같은 토픽에 더한다([1장](kafka.md#이벤트-버전)).
- **배포 순서 규칙**: 두 타입을 받는 consumer 앱을 먼저 배포하고, 그 뒤에 producer 앱이 새 타입으로 바꾼다. 옛 consumer는 모르는 타입을 무시한다([1장](kafka.md#이벤트-버전)).
- **계약 검증**: producer와 consumer가 `shared`의 같은 레코드를 컴파일하고, 소비 test(Testcontainers)가 실제 Kafka에서 읽는다. 메시지는 schema ID 없이 일반 JSON으로 직렬화하고 header에 `eventType`과 `eventVersion`을 싣는다.

registry 서버는 메모리 예산([3장](k3s.md#ec2-2노드-자원-예산))에 들어가지 않고, 위 규칙이 막지 못하는 문제가 아직 없다.

**이벤트 schema 파일과 CI 호환성 검사는 하지 않는다(#339, 2026-11-03).** 계획했던 AsyncAPI 문서, `shared`의 JSON Schema 파일, `asyncapi validate`·`asyncapi diff` CI, version별 샘플 payload contract test를 모두 뺐다. 그래서 같은 `eventVersion` 안의 깨지는 변경을 PR 단계에서 자동으로 막는 장치는 없고, 위 호환 규칙과 리뷰로 막는다.

HTTP 계약은 OpenAPI로 관리한다(외부 HTTP endpoint, request/response, `ProblemDetail`, `RsData`). CI는 생성된 OpenAPI를 `dev` 기준 spec과 비교하고 backward-incompatible 변경이면 실패시킨다. `openapi-diff`는 `--fail-on-incompatible` 검사를 제공한다([공식 저장소](https://github.com/OpenAPITools/openapi-diff)).

registry는 배포 순서 규칙을 지킬 수 없게 될 때 다시 검토한다. 예를 들어 같은 event를 소비하는 앱이 많아져 순서를 맞추기 어려워질 때다. 이 경우 broker를 schema registry가 내장된 Redpanda로 바꾸는 방안도 함께 비교한다([1장](kafka.md#broker-선택--apache-kafka와-redpanda)).

### JFR + JDK Mission Control

JVM 성능 증거의 표준은 **JFR recording 파일**로 정한다. k6 기준선과 장애 scenario 실행 때 app JVM에 저부하 continuous recording을 켜고, 문제가 발생한 시간 구간을 `.jfr` artifact로 dump한다. JDK Mission Control(JMC)에서 같은 파일을 열어 CPU hot method, allocation, GC pause, thread park·lock 경합을 분석하고 변경 전·후 screenshot과 결론을 남긴다. JFR은 JVM event를 timestamp와 함께 보존해 사후 분석할 수 있고 continuous recording은 오래된 데이터를 버리는 bounded buffer로 운용할 수 있다. Oracle 문서도 default continuous recording을 저부하 진단 용도로 설명한다([Java 25 Troubleshooting Guide](https://docs.oracle.com/en/java/javase/25/troubleshoot/troubleshooting-guide.pdf)). JMC는 JFR 데이터를 시각화하는 공식 분석 도구다([JDK Mission Control](https://docs.oracle.com/en/java/java-components/jdk-mission-control/index.html)).

시연 산출물은 다음으로 고정한다.

- 같은 k6 scenario의 baseline/after `.jfr` 두 개
- JMC automated analysis와 hot method·allocation·GC·lock 화면
- p95 변화와 JFR 원인을 연결한 짧은 performance report
- pod 종료 직전 또는 오류율 급등 구간을 `jcmd <pid> JFR.dump`로 추출하는 runbook

현재 runtime image는 `eclipse-temurin:25-jre`라서 `jcmd`를 전제로 하지 않는다. customer-support-app은 Native Image라 JFR을 일부 이벤트만 지원하므로, JFR·JMC 분석은 JVM 앱으로 한다([Native Image](native-image.md)). 기본 image는 그대로 유지하고, JFR demo profile에서만 JDK 기반 diagnostic image를 사용해 `jcmd` dump를 지원하거나 JVM 시작 option으로 duration·destination이 정해진 recording을 만든다. profile용 편의를 production image 크기 증가로 바꾸지 않는다.

JConsole과 VisualVM은 채택 stack이 아니라 **로컬 수동 진단 대안**으로만 기록한다. JConsole은 JMX 기반 live monitoring·MBean 조작 도구이고 local monitoring도 관찰 대상에 부하를 줄 수 있다([Java 25 JConsole 문서](https://docs.oracle.com/en/java/javase/25/management/using-jconsole.html)). VisualVM은 live CPU·heap·thread 관찰과 sampler, thread/heap dump에 편리하지만 사람이 붙어 있어야 한다([VisualVM 공식 기능 문서](https://visualvm.github.io/features.html)). 둘은 CI artifact와 동일 시간 구간의 재분석이 가능한 JFR을 대체하지 않는다. 이를 위해 Kubernetes에 원격 JMX port를 별도로 공개하지 않는다.

### Terraform — 현재 배포된 AWS만 코드화

Terraform으로 미래 reference architecture를 새로 설계하지 않는다. **현재 AWS에 실제 존재하며 합방 배포에 사용 중인 resource를 먼저 inventory하고 import한 뒤, 그 상태만 HCL로 옮긴다.** `aws-usage.md`로 확인한 현재 구성은 다음과 같다.
- EC2 2대(t3.large·t3.medium), Elastic IP 2개, 각 EBS·network interface, key pair
- 프로젝트 전용 VPC, public subnet 1개, Internet Gateway, route table
- security group 1개
- S3 bucket 1개
- EC2 instance role·instance profile과 S3 inline policy

모두 `Team=devcos-team08` tag가 붙어 있다. EC2 안의 k3s와 Kubernetes resource(PostgreSQL volume, 인증서 포함)는 Terraform 대상이 아니다.

코드화 범위는 다음 원칙으로 확정한다.

- EC2: 두 instance의 실제 AMI, instance type, credit specification, root volume(gp3 50 GB), key pair, metadata option과 tag를 import 결과 그대로 표현한다. EIP association도 둘 다 import한다.
- security group: 현재 public `22`·`80`·`443`과 node 간 TCP 6443, UDP 8472, TCP 10250 self-reference rule, 실제 outbound rule을 그대로 표현한다. zero-change plan 뒤 edge rule을 large에만 적용하는 구조는 별도 PR로 전환한다.
- network: 프로젝트 전용으로 만든 VPC, public subnet, Internet Gateway, route table을 import한다. 다른 팀과 같이 쓰는 resource가 있으면 `data` source로 참조한다. private subnet·NAT는 지금 없으므로 만들지 않는다.
- S3: 실제 bucket의 서울 region, public access block, object ownership(ACL 비활성), SSE-S3 기본 암호화, CORS를 표현한다. 현재 없는 versioning·lifecycle·bucket policy는 추가하지 않는다.
- IAM: 현재 large에 연결된 EC2 role·instance profile과 S3 policy를 먼저 import한다. zero-change plan 뒤 application replica가 medium에서도 S3를 사용할 수 있도록 같은 instance profile을 medium에 연결하는 변경을 별도 plan으로 검토한다. access key는 만들지 않는다.
- 09:00~18:00 자동 시작·중지는 실제로 운영 중이지만 구현 방식(EventBridge Scheduler, Instance Scheduler 등)과 소유자가 문서에 없다. inventory로 찾아 이 프로젝트 소유일 때만 import한다. 계정 공용 도구라면 참조만 하고, 새 Lambda·EventBridge를 만들지 않는다.

GitHub ruleset, repository secret, k3s·k3d, Kubernetes manifest, Kafka, Redis, observability stack은 이 Terraform root에 넣지 않는다. GitHub Actions는 Terraform이 관리하는 실제 배포 infrastructure가 아니고, cluster 내부 resource는 `k8s/` manifest가 소유하기 때문이다. RDS, EKS, NAT Gateway, 다중 AZ, 새 VPC 같은 미배포 resource도 만들지 않는다.

진행 순서는 `AWS inventory와 tag 확인 → 민감 값 없는 import block 작성 → terraform plan 결과가 변경 0인지 확인 → HCL 정리 → CI의 fmt/validate/plan 검증`이다. 첫 완료 조건은 새 resource 생성이 아니라 **기존 환경에 대한 zero-change plan**이다. 이후 실제 AWS를 바꿀 때만 PR의 plan을 검토하고 apply 여부를 별도로 결정한다. 민감한 account ID, bucket 이름, instance ID, public IP는 variable/state backend에서 다루고 문서와 repository output에 노출하지 않는다.

### JVM 메모리 줄이기 실험 (#609, #610)

node 메모리 예산에서 가장 큰 몫은 앱 JVM이다. 2026-11-03 운영 7일 실측에서 앱 heap 사용 최대는 82~154 MiB인데 heap 밖(Metaspace, 코드 캐시, native)이 280~355 MiB였고, 앱 6개 모두 working set이 limit의 85% 이상이었다(#610). 두 가지를 차례로 잰다.

- **GraalVM Native Image** (#609, 완료): 실행 전에 기계어로 컴파일(AOT)해 JVM 고정 비용 대부분을 없앤다. 트래픽이 가장 적은 customer-support-app 하나에 적용했다. 같은 조건에서 메모리 −44~45%, 운영 기동 23~28초 → 1.1초, limit 448 → 256 MiB(#672)였다. 다른 앱으로 넓히는 것은 #610 뒤 다시 정한다(2026-11-04 결정, [Native Image](native-image.md)).
- **JVM 유지 + heap·native 튜닝** (#610): `-Xmx`를 실사용에 맞추고 heap 밖 메모리를 줄인다. 나중에 한다.

Java 25의 AOT cache(JVM 유지)는 주 효과가 기동 시간이라 메모리 문제의 첫 후보로 두지 않는다. 기동 시간이 문제가 될 때 같은 방법으로 잰다.

비교는 같은 앱·같은 시나리오의 전후로 한다. 지표는 working set·RSS, 기동 시간, 응답 시간(p95), 이미지 빌드·CI 시간이다. Native Image의 비용도 함께 적는다.

- reflection·proxy·resource hint: Spring Modulith, Hibernate·Flyway, Spring Security, springdoc를 확인한다.
- JIT가 없어 최고 처리량이 낮을 수 있다. k6 같은 시나리오로 확인한다.
- JFR 이벤트 일부만 지원해 위 "JFR + JDK Mission Control"의 증거 방식이 달라진다.

### 지금 넣지 않는 것

- **Toxiproxy**: 장애 test는 컨테이너 pause·stop과 listener stop으로 만든다(위 "Testcontainers 장애 test"). 느린 응답을 재현해야 할 때 다시 검토한다.
- **AsyncAPI와 이벤트 schema CI 검사**: 이벤트 계약은 `shared` 레코드와 호환·배포 순서 규칙으로 관리한다(위 "Kafka 이벤트 계약과 OpenAPI contract").
- **Debezium**: 현재 Spring Modulith publication/outbox externalization과 같은 DB→Kafka 전달 책임을 하나 더 만든다. CDC 자체를 비교하는 별도 실험이 아니라면 제외한다.
- **Spring Cloud Gateway, k3s 내장 Traefik**: Gateway 책임은 Envoy Gateway가 맡는다([3장 "API Gateway"](api-gateway.md)).
- **BFF(Backend for Frontend)**: 프런트엔드가 React 하나뿐이고 진입점은 Envoy Gateway다. 프런트별로 응답을 따로 조립할 필요가 없다.
- **nginx(edge nginx, Ingress NGINX controller)**: 진입점은 Envoy Gateway 하나다. Ingress NGINX controller는 2026년 3월에 은퇴해 보안 수정도 나오지 않는다.
- **Prometheus 장기 저장 + Mimir 장기 저장, Elastic Observability 추가**: 같은 telemetry signal의 backend를 두 벌 운영하지 않는다.
- **Opsgenie 등 온콜 호출 도구**: alert는 Grafana alerting에서 Slack으로 보낸다([5장 "Alert 정책"](observability.md#alert-정책-629)). Opsgenie는 신규 판매가 끝났고 2027-04-05에 지원이 끝난다.
- **Pyroscope**: 이 포트폴리오에서는 JFR/JMC가 JVM code-level profiling 증거를 담당한다. 별도 continuous-profile backend까지 더하지 않는다.
- **JConsole·VisualVM 상시 운영**: 필요할 때 로컬에서 쓰는 수동 도구일 뿐 배포 component나 포트폴리오 증거 pipeline으로 만들지 않는다.
- **Bitnami 차트와 추가 operator**(Strimzi, CloudNativePG, redis-operator): Bitnami는 무료 이미지에 보안 패치가 없고, operator는 상시 메모리를 쓰는데 instance 1개 구성에서 얻는 것이 적다. 쓰는 차트는 [3장 "manifest 관리"](k3s.md#manifest-관리--kustomize와-helm)의 표로 정한다.
- **Terraform Kubernetes provider**: in-cluster resource는 `k8s/` manifest가 소유한다.
- **service mesh, Vault, 다중 Kubernetes cluster**: 현재 시연할 통신·secret rotation·multi-cluster 문제가 없어 stack 수만 늘어난다.
- **AWS 관리형 대체**(EKS, RDS, ElastiCache, MSK, ELB·ACM, ECR, Amazon Managed Prometheus·Grafana, OpenSearch Service, Bedrock): AGENTS.md 8장의 EC2 2대·VPC 1개·S3 1개 범위를 넘는다. "실운영이라면 무엇으로 바꿀지"는 [11장](../final-project-infrastructure.md#11-실서비스-전환-시-고려사항)과 포트폴리오 ADR로만 남긴다.
- **EC2 instance 상향·추가**: t3.large + t3.medium이 상한이다. node별 자원 예산을 넘으면 상시 profile을 줄이고, 그래도 부족하면 팀과 운영진에게 먼저 묻는다.
