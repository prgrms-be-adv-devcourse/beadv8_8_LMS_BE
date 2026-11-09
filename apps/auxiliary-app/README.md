# auxiliary-app

검색·챗봇·알림을 JVM 하나에 모은 앱이다(#684, #714). JVM 앱은 heap과 상관없이 약 300 MiB를 고정으로 써서, 세 기능을 앱 셋으로 띄울 메모리가 없다. 그래서 앱은 하나로 띄우고, 안에서는 세미 프로젝트의 멀티모듈 모놀리스처럼 모듈로 나눈다.

Windows가 `aux`를 예약 장치 이름으로 막아(Git for Windows가 경로에 `aux`가 든 파일을 `Invalid path`로 거부) 이름이 `aux-app`이 아니라 `auxiliary-app`이다.

## 구조

```text
apps/auxiliary-app/                 :auxiliary-app      실행 진입점 com.hapbang.auxiliary.AuxiliaryApplication
├── build.gradle.kts · Dockerfile · k8s/
├── src/main/resources/             application*.yaml (앱 설정)
├── global/                         :auxiliary-global   com.hapbang.auxiliary.global (공통 코드)
└── boundedContext/                 Gradle 프로젝트가 아니라 모듈을 묶는 디렉터리
    └── chatbot/                    :chatbot            com.hapbang.auxiliary.chatbot
```

| 프로젝트 | 의존할 수 있는 것 | 하는 일 |
| --- | --- | --- |
| `:auxiliary-app` | 모든 모듈 | 실행 진입점, 앱 설정, 앱 전체 테스트(기동·Flyway·포트, 모듈 경계) |
| `:auxiliary-global` | `:shared` | 응답 `RsData`, `GlobalExceptionHandler`, 로그인 회원 `@LoginUserId`, 내부 API 키·포트, 스케줄링 조건, 배치 실행 락(`JobLock`, #716), 로그 MDC. 업무 규칙은 두지 않는다 |
| 업무 모듈(`:chatbot` 등) | `:auxiliary-global`, `:shared` | 업무 기능. 다른 업무 모듈을 의존하지 않는다 |

- global은 chat-app `global`을 복사해 시작했다. 다른 앱의 global을 의존하지 않는다(앱끼리 의존 금지).
- 하위 프로젝트 이름에 `-app`을 붙이지 않는다. 루트 `verifyModuleStructure`가 `-app`으로 끝나는 프로젝트를 실행 앱으로 센다.
- CI는 앱마다 `:auxiliary-app:check`만 돌린다. 이 앱의 `check`가 디렉터리 안의 하위 프로젝트(`global`, `boundedContext/*`) `check`를 함께 부르므로 모듈 테스트도 CI에서 돈다.

## 모듈 경계

- **코드**: Spring Modulith(`detection-strategy: explicitly-annotated`)로 검사한다.
  - 업무 모듈 `package-info.java`에 `@ApplicationModule(allowedDependencies = "global")`을 붙인다. 공통 모듈만 참조할 수 있다.
  - `global`은 열린 모듈(`Type.OPEN`)이라 모든 업무 모듈이 쓸 수 있다. `com.hapbang.shared`는 앱 패키지 밖이라 검사 대상이 아니고 누구나 쓴다.
  - `AuxiliaryModularityTest`가 `ApplicationModules.of(AuxiliaryApplication.class).verify()`를 돌린다. 업무 모듈끼리 직접 참조하면 실패한다.
  - 모듈 사이 연동은 Spring 이벤트로만 한다(아래 "모듈 사이 이벤트").
- **Gradle**: `verifyModuleStructure`가 업무 모듈은 `:auxiliary-global`·`:shared`에만, `:auxiliary-global`은 `:shared`에만 의존하는지 검사한다.
- **database**: 앱 공통 database `auxiliary` 하나, 모듈마다 schema 하나(모듈 이름)다. DataSource·JPA·Flyway·트랜잭션 관리자는 하나다.
  - 모듈은 자기 schema의 테이블만 쓴다. 다른 모듈 schema의 테이블과 JOIN·FK·같은 트랜잭션 쓰기를 하지 않는다.
  - 엔티티는 `@Table(schema = "<모듈>")`, SQL의 테이블 이름에도 schema를 붙인다(`chatbot.faq`).
  - Flyway 이력(`flyway_schema_history`)과 Modulith 이벤트 발행 기록은 앱 공통 schema `auxiliary`에 둔다.
  - 이 database 예외는 auxiliary-app 안의 모듈에만 해당한다(AGENTS.md "경계 규칙").

## 모듈 사이 이벤트

세미 프로젝트와 같은 방식이다(`docs/dev/module-integration.md`).

- 이벤트 클래스(record)는 `common/shared`의 `com.hapbang.shared.<모듈>.event`에 둔다. 업무 모듈은 서로를 참조할 수 없지만 shared는 누구나 쓴다.
- 보내는 모듈은 자기 트랜잭션 안에서 `ApplicationEventPublisher.publishEvent(...)`로 발행한다.
- 받는 모듈은 `in` 계층의 리스너에 `@ApplicationModuleListener`를 붙인다. 보낸 쪽이 커밋된 뒤 다른 스레드·새 트랜잭션에서 처리하고, 다른 리스너 애노테이션을 겹쳐 붙이지 않는다.
- 발행 기록은 Modulith가 `auxiliary` schema에 남기고, 리스너가 실패하면 미완료로 남아 재시작 때 다시 보낸다. 그래서 리스너는 같은 이벤트를 두 번 받아도 결과가 같아야 한다.
- 이 이벤트는 같은 JVM 안에서만 전달된다. 다른 앱과는 Kafka integration event로 주고받는다.

## 권한

- 업무 API는 비회원에게 열지 않는다. 공통 `SecurityConfig`가 Swagger·헬스체크 밖의 모든 요청에 로그인을 요구한다. 모듈이 공개 경로를 따로 열지 않는다.
- 역할 검사는 각 모듈 Facade의 `@PreAuthorize`로 한다. 챗봇은 로그인 회원 전체(`USER`·`INFLUENCER`·`VERIFIED_INFLUENCER`·`ADMIN`)가 쓴다(11/06 결정, #715).

## Flyway

- 이력은 앱 하나다. 모듈 SQL은 각 모듈의 `src/main/resources/db/migration`에 두고 클래스패스에서 합쳐진다.
- 모듈의 첫 SQL은 `CREATE SCHEMA IF NOT EXISTS <모듈>;`이다.
- 버전은 작성 시각(`V{yyyyMMddHHmm}__...`)이다. 모듈이 서로 의존하지 않으므로 `out-of-order: true`로 늦게 병합된 낮은 버전도 실행한다. 같은 분에 두 모듈이 SQL을 만들면 버전이 겹치므로 병합 전에 확인한다.

## 모듈 추가 방법 (검색·알림 담당)

1. `settings.gradle.kts`의 `auxiliaryModules`에 모듈 이름을 더한다(예: `"search"`). Gradle 프로젝트 `:search`가 `apps/auxiliary-app/boundedContext/search`를 가리킨다.
2. `boundedContext/<모듈>/build.gradle.kts`를 만든다. `implementation(project(":auxiliary-global"))`과 그 모듈에 필요한 라이브러리만 적는다(`:chatbot` 참고). 다른 업무 모듈을 의존하지 않는다.
3. `apps/auxiliary-app/build.gradle.kts`에 `implementation(project(":<모듈>"))`를 더한다.
4. `src/main/java/com/hapbang/auxiliary/<모듈>/package-info.java`에 `@ApplicationModule(displayName = "...", allowedDependencies = "global")`을 붙인다.
5. 첫 SQL `src/main/resources/db/migration/V{yyyyMMddHHmm}__create_<모듈>_schema.sql`에 `CREATE SCHEMA IF NOT EXISTS <모듈>;`을 쓴다.
6. 외부 API가 있으면 `k8s/base/gateway.yaml`의 HTTPRoute `matches`에 그 모듈 경로(`/api/v1/<경로>`)를 더한다. 응답 시간 30초·본문 1MiB가 그 경로에도 걸린다. 오래 열어 두는 응답(SSE·WebSocket)은 chat-app처럼 경로를 따로 만들어 시간 제한을 따로 둔다. 보안 설정(`SecurityConfig`)은 경로와 상관없이 같다.
7. `./gradlew verifyModuleStructure :auxiliary-app:check`로 확인한다. `AuxiliaryModularityTest`가 새 모듈을 찾는지도 본다.

모듈 `build.gradle.kts`에는 `-parameters` 컴파일 옵션을 켠다(`:chatbot` 참고). Spring Boot 플러그인을 쓰는 실행 앱은 자동으로 켜지지만 하위 프로젝트는 아니다. 없으면 `@RequestParam`·`@PathVariable`이 인자 이름을 읽지 못한다.

advisory lock으로 한 pod만 돌릴 작업은 global `JobLock`을 쓴다. 모듈이 `JobLockKey`를 구현한 enum을 만들고(챗봇 `ChatbotJob`), 락 번호는 앱 전체(database 하나)에서 겹치지 않게 **그 작업을 더한 이슈 번호 × 1000 + 순번**으로 정한다(예: 챗봇 FAQ 맞추기 `715_001`). 회원별 호출 제한은 global `KeyedRateLimiter`를 모듈이 API마다 빈으로 만든다(초과 시 429·`Retry-After`). `KeyedRateLimiter`는 지갑(billing-app)의 `UserRateLimiter` 구조를 빌려 왔지만 이 앱에 맞게 따로 고친다. 지갑 쪽과 맞춰 둘 필요는 없다.

## 실행과 설정

| 항목 | 값 |
| --- | --- |
| 포트 | 외부 API 8080(로컬 8087), 내부 API 8081(`HAPBANG_INTERNAL_API_PORT`, k8s만), 관리 8082(`MANAGEMENT_SERVER_PORT`, k8s만) |
| 헬스체크 | `GET /actuator/health` (k8s는 관리 포트의 liveness·readiness) |
| Swagger | `/api/v1/auxiliary/swagger-ui.html` (`global/src/main/resources/swagger.yml`). 모듈 셋의 API가 한 명세에 있고 컨트롤러 `@Tag`로 나뉜다 |
| DB 연결 풀 | Hikari 5. PostgreSQL `max_connections` 100을 다른 앱과 나눠 쓴다 |
| 배치 스레드 | `spring.task.scheduling.pool.size` 2 |
| Kafka·Redis | common 라이브러리 자동 설정. 업무 모듈은 `:kafka` 프로젝트를 의존할 수 없어(`verifyModuleStructure`) Kafka starter를 직접 적고, 소비 컨테이너 팩토리·오류 처리기를 모듈 전용 빈으로 만든다(Boot 기본 빈을 바꾸지 않는다. 챗봇 `ChatbotKafkaConfig`) |
| 배치 실행 락 | global `JobLock`(PostgreSQL advisory lock). database를 모듈이 함께 쓰므로 락 번호는 그 작업을 더한 이슈 번호 × 1000 + 순번(`JobLockKey`) |
| 다른 앱 내부 API | shared Client는 스캔하지 않는다. 쓰는 모듈이 필요한 Client만 `@Import`한다(챗봇 출연 싱크: `AppearanceApiClient`, `APPEARANCE_API_BASE_URL`) |
| 메모리 | large node, limit 704Mi, `-Xmx256m`, `MALLOC_ARENA_MAX=2`(#684 예산) |

DB 연결 풀·배치 스레드·메모리는 앱 하나의 값이라 모든 모듈이 나눠 쓴다. 지금 값은 챗봇 하나 기준이다. 모듈을 더하는 PR에서 그 모듈이 동시에 쓰는 DB 연결·배치 수와 메모리를 보고 다시 정한다. 한 모듈이 다 쓰면 다른 모듈의 요청·배치가 기다린다.

STOMP 연결 티켓(알림 모듈)에 쓰는 토큰 만료 헤더 `X-User-Token-Exp`는 global 필터와 Gateway `claimToHeaders`에 없다. chat-app global에서 복사할 때 뺐으므로, 쓰는 모듈이 Gateway 설정과 함께 더한다.

로컬은 다른 앱과 같이 `./gradlew :auxiliary-app:bootRun`으로 띄운다. 로컬 PostgreSQL(docker compose)은 모든 앱이 database 하나를 schema로 나눠 쓰므로 따로 만들 것이 없다.

## 챗봇 FAQ (#715)

FAQ 원본은 `boundedContext/chatbot/src/main/resources/chatbot/faq-seed.json`(53건, 분류 11개)이다. 고객센터에 FAQ 기능이 없어 이 파일이 원본이고, 내용을 고치면 이 파일을 고쳐 배포한다.

- 시작할 때 별도 스레드에서 시드를 `chatbot.faq`에 code로 맞춘다(없으면 넣고, 다르면 고치고, 빠진 code는 지운다). 이어서 내용 해시가 바뀐 FAQ만 임베딩해 ES index에 넣는다. advisory lock으로 한 pod만 하고, 실패해도 기동·다른 API는 그대로다. 색인 실패는 10분마다 점검해 다시 한다.
- index 이름에 공급자·차원이 들어간다(`chatbot-faq-ollama-768`, `chatbot-faq-openai-1536`). 모델을 바꾸면 새 index에 모두 다시 색인한다.
- Spring AI 자동 설정의 vector store 빈은 끈다(`spring.ai.vectorstore.type=none`). 그 빈은 만들어질 때 ES를 불러 ES가 죽어 있으면 앱이 뜨지 않는다. 챗봇이 `ElasticsearchVectorStore`를 직접 만들고 index mapping(양자화 없는 `hnsw`, cosine)도 직접 만든다.
- ES·OpenAI가 죽어도 liveness·readiness는 UP이다. FAQ 목록은 그대로 되고, 검색은 빈 결과, 대화는 LLM 없이 검색 상위 1건 또는 안내 문구로 답한다(`fallback: true`). ES 헬스 지표는 전체 상태(`/actuator/health`)에만 들어간다.
- API: `GET /api/v1/chatbot/faqs?category=`, `GET /api/v1/chatbot/faqs/search?q=`, `POST /api/v1/chatbot/chat/faq`, `POST /api/v1/chatbot/chat/faq/stream`(SSE). 검색·대화는 회원마다 분당 10회다(`hapbang.chatbot.faq.rate-limit.*`).
- 스트림 경로(`/api/v1/chatbot/**/stream`)만 Gateway route를 따로 두어 60초다(`k8s/base/gateway.yaml`의 `auxiliary-app-stream`). 서버 스트림 상한 45초·MVC 비동기 상한 50초가 그보다 짧아 서버의 fallback·error 이벤트가 먼저 나간다.
- 지표: `hapbang.chatbot.requests{feature}`, `hapbang.chatbot.faq.unmatched{feature}`, `hapbang.chatbot.faq.search.errors`, `hapbang.chatbot.fallbacks{feature,reason}`, `hapbang.chatbot.rate.limit.rejected{feature}`. 대화 내용은 저장하지 않고 로그에도 남기지 않는다.

### 챗봇 로컬 실행

로컬 모델은 Ollama(chat `qwen2.5:3b`, 임베딩 `nomic-embed-text`)다. 공용 `docker/compose.yaml`(PostgreSQL 등)과 별도로 ES·Ollama를 띄운다.

```bash
# 1) ES 9.5.5(보안 끔, 9200)와 Ollama(11434)
docker compose -f apps/auxiliary-app/compose.yaml up -d
# 2) 모델 받기(처음 한 번, 볼륨에 남는다). qwen2.5:3b는 약 2GB다. 0.5b는 Tool을 거의 부르지 않는다
docker compose -f apps/auxiliary-app/compose.yaml exec ollama ollama pull qwen2.5:3b
docker compose -f apps/auxiliary-app/compose.yaml exec ollama ollama pull nomic-embed-text
# 3) 앱 실행(local 프로필). 뜬 뒤 로그에 "FAQ 색인을 맞췄다 ... indexed=53"이 나온다
./gradlew :auxiliary-app:bootRun
# 4) 호출. 로컬에는 Gateway가 없어 사용자 헤더를 직접 싣는다
curl -N -X POST localhost:8087/api/v1/chatbot/chat/faq/stream -H 'X-User-Id: 1' -H 'X-User-Role: USER'   -H 'Content-Type: application/json' -d '{"message":"HP 충전은 어떻게 해요?"}'
```

- 루트 `.env`(Git에 올리지 않음, `.gitignore`)는 값에 따옴표를 쓰지 않는다. Spring은 `.env`를 properties로 읽어 따옴표까지 값으로 쓴다. 로컬 챗봇에는 OpenAI 키가 필요 없다.
- 운영 모델은 OpenAI(chat `gpt-4o-mini`, 임베딩 `text-embedding-3-small`)이고 키는 Secret `OPENAI_API_KEY`로만 받는다.

### 테스트와 평가

- CI(`./gradlew :auxiliary-app:check`)는 가짜 모델(`src/test`의 `FakeChatModel`·`FakeEmbeddingModel`)과 ES Testcontainers(9.5.5)로 돈다. 실제 모델을 부르는 테스트는 `@Tag("llm")`이라 빠진다.
- 로컬에서 실제 모델로: `./gradlew :chatbot:llmTest`(위 compose의 Ollama와 모델 필요).
  - `FaqOllamaLlmTest`: 모델이 `searchFaq`를 부르고 스트림이 `done`까지 오는지(답의 품질은 보지 않는다)
  - `FaqRetrievalEvaluationOllamaTest`: 평가셋(`src/test/resources/chatbot/faq-eval.json`, 다른 표현 148개 + 범위 밖 7개)의 hit@1·hit@3와 기준값별 범위 밖 거절률 표. 목표를 걸지 않는다
  - `FaqRetrievalEvaluationOpenAiTest`: 같은 평가를 운영 임베딩(OpenAI)으로. hit@3 0.85 아래면 실패한다. `OPENAI_API_KEY`가 있을 때만 돈다. 배포 전에 한 번 돌려 `application-prod.yaml`의 `similarity-threshold`를 정한다
- 기준값은 공급자마다 다르다(코사인 유사도). 운영(OpenAI) 값은 평가 전이라 "임시값"으로 표시한다.
- 로컬 Ollama는 호출이 되는지 보는 개발용이고 품질은 운영 공급자로 본다. 2026-11-09 Ollama 평가는 hit@1 0.169, hit@3 0.399였고, 기준값 0.30~0.70 어디서도 범위 밖 질문 7개를 하나도 거르지 못했다(0.76에서 2개). `nomic-embed-text`가 영어 위주로 학습되어 한국어 문장의 뜻을 잘 가르지 못하는 것이 원인으로 보인다. 로컬에서 품질까지 보려면 다국어 임베딩 모델로 바꾸고 차원·index 이름을 함께 바꾼다.

## 운영 배포

- prod overlay는 `replicas: 0`으로 시작했고(#714), 운영 준비가 끝나 1로 올렸다(#726). dev에 병합하면 다른 앱처럼 자동으로 배포된다.
- 운영 준비(#726): PostgreSQL role·database `auxiliary`, SealedSecret `k8s/overlays/prod/auxiliary-app-secrets.yaml`(`DB_PASSWORD`, `HAPBANG_INTERNAL_API_KEY`, `ES_API_KEY`, `OPENAI_API_KEY`), ES role `chatbot`·API key, `hapbang-prod`의 ConfigMap `elasticsearch-ca`(키 `ca.crt`), large node 자리, `max_connections` 계산. 값과 절차는 `docs/deploy/k8s.md`의 "Secret"·"PostgreSQL database"·"Elasticsearch 앱 접근"에 있다.
- 공용 인프라(`k8s/platform`, `k8s/tools`, `k8s/gateway`, `docker/`)와 인프라 문서는 이 앱 작업에서 고치지 않고 인프라 담당과 정한다.
- 비밀값을 더하거나 바꾸면 `~/.hapbang/prod-secrets.env`에 넣고 `k8s/tools/seal-app-secrets.sh auxiliary`로 다시 봉인한다(필수·선택 키 목록은 그 스크립트의 `auxiliary` spec).
