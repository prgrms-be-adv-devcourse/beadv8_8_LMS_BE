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
    ├── chatbot/                    :chatbot            com.hapbang.auxiliary.chatbot
    ├── notification/               :notification       com.hapbang.auxiliary.notification
    └── search/                     :search             com.hapbang.auxiliary.search
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

- 업무 API는 로그인을 요구한다. 공통 `SecurityConfig`가 Swagger·`/error`·헬스체크·Prometheus 수집 밖의 요청에 로그인을 요구한다. 업무 API의 예외는 둘이다.
    - 강의 검색 `GET /api/v1/search/lectures`: 비회원도 쓴다. 회원이면 헤더로 인증되어 회원 한도로 센다(#883).
    - 알림 웹소켓 핸드셰이크 `GET /api/v1/notifications/ws`: 브라우저가 업그레이드 요청에 인증 헤더를 싣지 못하므로 열고, 인증은 알림 연결 티켓으로 한다(`docs/notification/decisions.md` ND-060).
- 새 공개 경로는 모듈이 따로 열지 않고 공통 `SecurityConfig`에 이유와 함께 더한다.
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
6. 외부 API가 있으면 `k8s/base/gateway.yaml`의 HTTPRoute `matches`에 그 모듈 경로(`/api/v1/<경로>`)를 더한다. 응답 시간 30초·본문 1MiB가 그 경로에도 걸린다. 오래 열어 두는 응답(SSE·WebSocket)은 chat-app처럼 경로를 따로 만들어 시간 제한을 따로 둔다. 보안 설정(`SecurityConfig`)은 경로와 상관없이 같다(공개 경로 예외는 "권한").
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
| 다른 앱 내부 API | shared Client는 스캔하지 않는다. 쓰는 모듈이 필요한 Client만 `@Import`한다(챗봇 출연 싱크: `AppearanceApiClient`, `APPEARANCE_API_BASE_URL` / 검색 통계 동기화: `MemberApiClient`, `MEMBER_API_BASE_URL`) |
| 메모리 | large node, limit 704Mi, `-Xmx256m`, `MALLOC_ARENA_MAX=2`(#684 예산) |

DB 연결 풀·배치 스레드·메모리는 앱 하나의 값이라 모든 모듈이 나눠 쓴다. 지금 값은 챗봇 하나 기준이다. 모듈을 더하는 PR에서 그 모듈이 동시에 쓰는 DB 연결·배치 수와 메모리를 보고 다시 정한다. 한 모듈이 다 쓰면 다른 모듈의 요청·배치가 기다린다.

STOMP 연결 티켓(알림 모듈)에 쓰는 토큰 만료 헤더 `X-User-Token-Exp`는 Gateway `claimToHeaders`가 넘기고(#847), global 필터는 읽지 않는다. 알림 연결 티켓 API만 직접 읽는다(`docs/notification/decisions.md` ND-056·ND-064). 알림 웹소켓 핸드셰이크가 받는 출처는 `hapbang.notification.websocket.allowed-origins`(local·prod 프로필)로 정한다.

로컬은 다른 앱과 같이 `./gradlew :auxiliary-app:bootRun`으로 띄운다. 로컬 PostgreSQL(docker compose)은 모든 앱이 database 하나를 schema로 나눠 쓰므로 따로 만들 것이 없다.

## 챗봇 FAQ (#715)

FAQ 원본은 `boundedContext/chatbot/src/main/resources/chatbot/faq-seed.json`(53건, 분류 11개)이다. 고객센터에 FAQ 기능이 없어 이 파일이 원본이고, 내용을 고치면 이 파일을 고쳐 배포한다.

- 시작할 때 별도 스레드에서 시드를 `chatbot.faq`에 code로 맞춘다(없으면 넣고, 다르면 고치고, 빠진 code는 지운다). 이어서 내용 해시가 바뀐 FAQ만 임베딩해 ES index에 넣는다. advisory lock으로 한 pod만 하고, 실패해도 기동·다른 API는 그대로다. 색인 실패는 10분마다 점검해 다시 한다.
- index 이름에 공급자·차원이 들어간다(`chatbot-faq-ollama-768`, `chatbot-faq-openai-1536`). 모델을 바꾸면 새 index에 모두 다시 색인한다.
- Spring AI 자동 설정의 vector store 빈은 끈다(`spring.ai.vectorstore.type=none`). 그 빈은 만들어질 때 ES를 불러 ES가 죽어 있으면 앱이 뜨지 않는다. 챗봇이 `ElasticsearchVectorStore`를 직접 만들고 index mapping(양자화 없는 `hnsw`, cosine)도 직접 만든다.
- ES·OpenAI가 죽어도 liveness·readiness는 UP이다. FAQ 목록은 그대로 되고, 검색은 빈 결과, 대화는 LLM 없이 검색 상위 1건 또는 안내 문구로 답한다(`fallback: true`). ES 헬스 지표는 전체 상태(`/actuator/health`)에만 들어간다.
- API: `GET /api/v1/chatbot/faqs?category=`, `GET /api/v1/chatbot/faqs/search?q=`, `POST /api/v1/chatbot/chat/faq`, `POST /api/v1/chatbot/chat/faq/stream`(SSE). 검색·대화는 회원마다 분당 10회다(`hapbang.chatbot.faq.rate-limit.*`).
- 스트림 경로(`/api/v1/chatbot/**/stream`)만 Gateway route를 따로 두어 60초다(`k8s/base/gateway.yaml`의 `auxiliary-app-stream`). 서버 스트림 상한 45초·MVC 비동기 상한 50초가 그보다 짧아 서버의 fallback·error 이벤트가 먼저 나간다.
- 지표(챗봇 전체, `ChatbotMetrics`)
  - `hapbang.chatbot.requests{feature}`: 진입점(API)별 사용 수. FAQ는 `faq_list`·`faq_search`·`faq_chat`·`faq_stream`, 위젯은 `lecture_recommend`·`influencer_recommend`, 버튼은 `lecture_button`·`influencer_button`, 자유 질문은 `chat`·`chat_stream`이다. 대화 Tool 호출은 여기서 세지 않는다. `fallbacks`의 기능 태그에는 버튼 경로의 쿼리 확장 `query_expansion`도 있다.
  - `hapbang.chatbot.tool.calls{tool}`: 대화 LLM이 부른 Tool 수(`searchFaq`, `recommendLectures`, `recommendInfluencers`).
  - `hapbang.chatbot.fallbacks{feature,reason}`: LLM 호출이 실패해 정해진 답으로 대신한 수. LLM 실패만 센다.
  - `hapbang.chatbot.recommend.search.errors{feature}`: 추천 검색(ES·임베딩)이 실패해 인기순(`POPULAR_TEMPORARY`)으로 답한 수.
  - `hapbang.chatbot.faq.unmatched{feature}`, `hapbang.chatbot.faq.search.errors`, `hapbang.chatbot.rate.limit.rejected{feature}`.
  - LLM 지연·토큰 수는 Spring AI 관측 지표(`gen_ai.client.*`)를 쓴다. 대화 내용은 저장하지 않고 로그에도 남기지 않는다.

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

- 확인 화면(#720): 앱을 local 프로필로 띄운 뒤 `http://localhost:8087/local/chatbot/index.html`을 연다. 시작 버튼 4개와 자유 질문(스트림·한 번에)을 눌러 보고 응답·걸린 시간(ms)을 본다. 회원 ID·역할을 화면에서 정해 사용자 헤더로 싣는다. local 프로필에서만 서빙하고(`ChatbotLocalPageConfig`) 보안 설정도 local에서만 `/local/**`을 연다.
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

#### 추천 가중치 평가 (#867)

추천 가중치(신호 가중·α·β·인플루언서 상대 가중·인기 식)를 더미 데이터로 재서 정한다. 평가 코드는 `src/test/.../llm/recommend/`에 있고, CI에서 돌지 않는다(`@Tag("llm")`).

- **보정 루프**: 값 바꾸기 → 평가(80명) → 결과 표 기록 → 채택 또는 되돌림을 여러 번 돈다. 다 정하면 확인용 20명으로 한 번만 재고 `application-prod.yaml`에 값과 근거 주석을 넣는다. 이 루프를 비용 없이 여러 번 돌리려고 아래 두 가지를 둔다.
  - **임베딩 캐시**: 처음 한 번만 임베딩 API를 부르고 벡터를 `~/.hapbang/recommend-eval-cache/`(환경 변수 `RECOMMEND_EVAL_CACHE_DIR`로 바꿀 수 있다)에 남긴다. 가중치를 바꾼 회차는 캐시만 읽는다. 레포 밖에 두는 것은 작업 폴더를 지운 뒤에도 같은 벡터로 다시 재기 위해서다(코드를 바꾼 뒤 기준선을 다시 잴 때 차이가 벡터가 아니라 코드에서만 나오게). 파일은 모델마다 따로이고 열쇠는 보낸 글의 해시라, 더미 글이 바뀌면 그 글만 다시 임베딩한다. 커밋하지 않는다.
  - **운영 코드를 그대로 부른다**: 조합마다 설정 record를 새로 만들어 운영 추천 UseCase를 직접 만든다(앱을 다시 띄우지 않는다). 추천을 부르는 곳은 `app/RecommendEvalAccess` 하나다.
- **더미 데이터**(`src/test/resources/chatbot/recommend-eval/dataset.json`): 시드 867로 만든다(`RecommendEvalDataGenerator`, 규칙은 클래스 주석). 분야 8개, 강의 120개(판매자 24명), 강의 회원 100명(취향 분야 1~2개, 신호의 20%는 취향 밖 잡음, 관심사 문장 하나), 인플루언서 100명(함께 출연을 마친 상대 2~8명). 이름·이메일은 없다. 생성기를 고치면 `$env:RECOMMEND_EVAL_REGENERATE = "yes"`를 켜고 `./gradlew :chatbot:test --tests '*RecommendEvalDataGeneratorTest' --rerun`으로 파일을 다시 만든 뒤 환경 변수를 지운다(`--rerun`이 없으면 Gradle이 환경 변수만 바뀐 실행을 건너뛴다).
- **정답과 지표**: 강의는 회원마다 구매 하나, 인플루언서는 함께 완료한 상대 하나를 숨긴다(신호와 제외 목록 양쪽에서 뺀다). 회원마다 결과 수 5와 20으로 불러 hit@5·nDCG@5(사용자가 보는 5개)와 풀 recall@20(20개 중 맞춤 후보만, 인기순 채움 제외), 상위 5개의 서로 다른 항목 수(쏠림)를 찍는다. 강의는 "신호만"과 "신호+관심사" 두 줄이다(α는 관심사가 있을 때만 쓰인다).
- **조합 주는 법**: `RECOMMEND_EVAL_GRID`에 바꿔 볼 값(`키=값,값;키=값,값`, 키 1~2개, 키마다 값 5개까지), `RECOMMEND_EVAL_BASE`에 채택해 고정할 값을 준다. 키는 `hapbang.chatbot.recommend.` 뒤의 이름이다(예: `lecture.popularity-weight`, `lecture.weights.cart`, `influencer.popularity.prior-reviews`). 둘 다 없으면 지금 설정값으로 기준선을 잰다. 바꾼 키가 강의뿐이면 강의만 돈다.

```powershell
# Docker Desktop을 켠다(PostgreSQL·ES Testcontainers). 앱은 띄우지 않는다
$env:OPENAI_API_KEY = "<키>"

# 조정용 80명: 기준선
./gradlew :chatbot:llmTest --tests '*RecommendEvaluationOpenAiTest' --rerun
# 조정용 80명: 격자(채택한 값은 BASE로 고정)
$env:RECOMMEND_EVAL_GRID = "lecture.popularity-weight=0,0.05,0.1,0.2,0.3"
$env:RECOMMEND_EVAL_BASE = "lecture.interest-weight=0.3"
./gradlew :chatbot:llmTest --tests '*RecommendEvaluationOpenAiTest' --rerun

# 확인용 20명(마지막에 한 번). 이 값이 켜지면 80명 실행은 꺼지고 BASE 한 줄만 잰다(GRID가 남아 있으면 실패한다)
Remove-Item Env:RECOMMEND_EVAL_GRID
$env:RECOMMEND_EVAL_BASE = "lecture.popularity-weight=0.1;lecture.interest-weight=0.3"
$env:RECOMMEND_EVAL_HOLDOUT = "yes"
./gradlew :chatbot:llmTest --tests '*RecommendEvaluationOpenAiTest' --rerun
Remove-Item Env:RECOMMEND_EVAL_HOLDOUT
```

- 평가 코드가 도는지는 가짜 임베딩으로 본다: `./gradlew :chatbot:llmTest --tests '*RecommendEvaluationFakeTest'`(키 필요 없음. 숫자는 의미가 없고, 정답이 신호·제외 목록에 없는지, 두 번째 실행이 모델을 부르지 않는지, 같은 입력이면 같은 숫자인지를 확인한다). 지표·정답 숨기기·캐시·조합 해석의 단위 테스트는 CI에서 돈다.

## 챗봇 추천 색인·LLM 공통 (#718)

강의 추천(#718)·인플루언서 추천(#719)·자유 질문(#720)이 함께 쓰는 기반이다.

- **추천 벡터 index**(`RecommendVectorIndex`): Spring AI `VectorStore` 대신 `ElasticsearchClient`로 직접 다룬다(취향 벡터 kNN, 저장된 벡터 읽기, 필드 일부 갱신). 이름은 설정값 `hapbang.chatbot.index.lecture-name`·`influencer-name`이고 규칙은 `chatbot-<종류>-<공급자>-<차원>`이다(ES role `chatbot-*`에 들어간다). ES 9는 벡터를 `_source`에서 빼고 저장한다. 저장된 벡터는 `_source.exclude_vectors: false`로 읽는다.
- **색인 요청**(`chatbot.index_task`): 사본이 바뀐 트랜잭션에 요청을 적고, 실행기(`IndexTaskRunner`, 5초마다)가 커밋 뒤 가져가 종류별 처리기(`IndexTaskHandler`)에 그 종류의 문서를 한 번에 넘긴다(임베딩을 한 번의 공급자 호출로 묶을 수 있다). 처리기는 요청 값이 아니라 지금 사본 상태로 맞춘다.
  - 문서 단위로 가져간다. 한 문서의 대기 요청을 모두 함께 임대하고(`next_attempt_at`), 임대 중이거나 다시 시도를 기다리는 요청이 있는 문서는 가져가지 않는다. 가져가기는 짧은 트랜잭션의 advisory lock(`718_001`)으로 pod 사이에 한 줄로 세운다. 그래서 한 문서를 두 pod가 함께 처리해 옛 상태가 새 상태를 덮는 일이 없다. 끝내기·다시 시도는 내 임대일 때만 바꾼다.
  - 실패한 문서는 30초부터 두 배씩 1시간까지 늘려 다시 한다. 10번 이상 실패하면 `[CHATBOT_INDEX_TASK_STUCK]` ERROR. 회차가 임대(5분)의 절반을 넘기면 남은 종류는 돌려준다. 끝난 요청은 7일 뒤 지운다. 지표 `hapbang.chatbot.index.tasks{type,result}`.
- 강의 사본은 값이 바뀌었거나 원본 없음 표시가 바뀔 때만 요청을 적는다. 값이 같은 매시 싱크는 요청을 적지 않는다.
- 나눠 쓰는 자원: 실행기는 앱 배치 스레드(2개) 하나를 쓴다. 한 번에 최대 문서 50개(`hapbang.chatbot.index.task.batch-size`, 같은 문서에 쌓인 요청은 함께 가져가 요청 수는 더 많을 수 있다)라 평소에는 짧지만, 첫 전체 색인처럼 요청이 많으면 한 회차가 수십 초 걸린다(임베딩 호출 + ES 쓰기). 그동안 다른 배치는 남은 스레드 하나로 돈다. DB는 회차마다 짧은 쓰기(가져가기 트랜잭션 1번 + 종류마다 끝내기 1번 + 실패 문서마다 1번)만 쓴다.
- **LLM 호출 장치**(`LlmCallGuard`): 회로 하나와 동시 호출 상한 하나(`hapbang.chatbot.llm.max-concurrent-calls`, 기본 4)를 모든 챗봇 기능이 나눠 쓴다. 기능마다 따로 두지 않는다.
- **대화 Tool의 `ToolContext`**(`ChatToolContext`, #720): 키는 `memberId`(요청 회원 ID)와 `faqSources`(Tool이 찾은 FAQ를 담는 목록) 둘이다. 대화 쪽은 Map을 `ChatToolContext.of`로만 만들어 세 Tool(`searchFaq`, `recommendLectures`, `recommendInfluencers`)이 같은 키를 읽는다. 값이 없으면 Tool이 거절한다.
- **벡터 호출 감싸기**(`VectorCall`): 추천은 ES 검색·임베딩 호출만 감싸 실패를 "검색 장애"로 본다. 그 사이의 DB 조회·코드 오류는 감싸지 않아 그대로 올라간다. 벡터 계산(평균·정규화·혼합·코사인)은 `TasteVectors` 하나를 강의·인플루언서가 함께 쓴다.

**지키는 것** (테스트가 고정한다. 바꾸면 해당 테스트가 깨진다)

| 보장 | 외부 장애 때도 | 테스트 |
|---|---|---|
| 한 문서는 한 실행만 처리한다 | 처리 시간이 임대(5분)보다 짧을 때 | `IndexTaskRunnerTest.claimsWholeDocuments`, `runOnceTakesClaimLock` |
| LLM 동시 호출 상한은 챗봇 기능 전체가 나눠 쓴다. 넘으면 모델을 부르지 않고 정해진 답 | | `FaqChatServiceTest.busyWhenConcurrentLimitReached`, `concurrentLimitSharedAcrossFeatures` |
| 검색 장애(ES·임베딩 실패)만 인기순으로 답한다. 검색 구간 안의 DB 오류는 삼키지 않고 그대로 올라간다 | | `InfluencerRecommendSearchErrorTest`, `InfluencerRecommendUseCaseTest.embeddingFailureFallsBackToPopular` |
| 세 Tool은 같은 `ToolContext`(`ChatToolContext.of`)로 부른다. 회원 ID는 Tool 인자가 아니라 여기서만 꺼낸다 | | `LectureRecommendToolsTest.usesToolContextMemberId`, `rejectsWithoutMemberId`, `InfluencerRecommendApiTest.tool`, `FaqIndexingIntegrationTest.searchFaqTool` |

## 챗봇 강의 추천 (#718)

`GET /api/v1/chatbot/recommendations/lectures?interest=`(로그인 회원). 후보는 코드가 정하고(LLM 없음), 설정은 `hapbang.chatbot.recommend.lecture.*`(`ChatbotLectureRecommendProperties`)다.

- **색인**: 강의 사본 전부(판매 중이 아니어도)를 `LectureIndexTaskHandler`가 지금 사본 상태로 맞춘다. 임베딩 글(제목 + 소개 + 콘텐츠 제목)의 해시를 문서에 두어, 상태만 바뀌면 다시 임베딩하지 않는다.
- **정합 점검**(`LectureIndexCheckBatch`): 앱이 뜬 뒤 한 번(처음 전체 색인)과 매시 :50(강의 싱크 :20 뒤, `index-check-cron`). 사본을 500개씩 읽어 ES 문서와 비교하고, 어긋난 강의만 색인 요청을 적는다(처리는 실행기). 끝나지 않은 요청이 이미 있는 강의는 다시 적지 않는다. 사본 읽기 + ES ids 검색만 하고 임베딩은 없다. 매시 점검은 앱 배치 스레드 하나를 잠깐 쓰고, 기동 때 한 번은 기동·readiness를 막지 않게 가상 스레드 하나에서 돈다(FAQ 기동 싱크와 같다).
- **후보**: 신호(구매 1.0, 장바구니 0.6, 리뷰 별점 보정, 0 하한, 최근 50개)의 가중 평균 벡터, 관심사가 있으면 `(1−α)·신호 + α·관심사`(α 0.3, local·prod 같음). kNN 뒤 보유·결제 중·장바구니·본인 강의·숨김 판매자(탈퇴·마스킹·사본 없음)를 빼고 `(1−β)·유사도 + β·인기`(β 0.05, local·prod 같음), 판매자당 2개·5개, 모자라면 인기순. 후보는 두 단계다: `pool(회원, 관심사, 수)`가 맞춤 후보만 고르고(제외 규칙·판매자당 상한 적용, 인기순 채움 없음), `complete(풀, 고른 순서)`가 모자란 자리를 인기순으로 채운다. 위젯·Tool은 풀 크기가 추천 수이고, 버튼 경로(#720)는 큰 풀을 받아 LLM이 고른다. α·β는 임베딩 모델마다 다를 값이라 코드 기본값 없이 프로필 yaml에 둔다. 가중치·상한·α·β는 평가 전 "임시값"이고 추천 가중치 보정(#867)에서 다시 정한다. 다시 정할 때 평가 결과를 설정 주석·PR에 적는다. 이유 코드는 강의마다 구매·장바구니·관심사 중 가까운 쪽(`LectureTaste`). 맞춤으로 고를 수 없으면(활동이 없거나 활동한 강의가 색인 전) 인기순과 `needsInterest=true`(관심사를 주면 맞춤). 모델·ES 연결이 없는 실행이면 관심사도 소용없어 `false`다. 실행 중 ES·임베딩 장애는 활동이 없는 회원의 요청으로는 알 수 없어 `true`일 수 있다.
- **근거 코드**(`basis`, `RecommendBasis`, 인플루언서와 같다): `PERSONALIZED`(맞춤 후보가 있음, 모자란 자리는 인기순으로 채움), `POPULAR`(신호 없음·신호 강의 색인 전·맞춤 후보가 모두 제외됨·모델과 ES를 끈 실행, 다시 시도해도 같음), `POPULAR_TEMPORARY`(검색이 잠시 실패, FE는 이때만 다시 시도 안내). 요청 회원이 숨김이면 빈 목록과 `notice`(정해진 문구). `fallback`은 LLM 이유 문장 실패이고 위젯은 늘 `false`다.
- **실패**: 색인·임베딩 호출이 실패하면 관심사가 있든 없든 인기순 + `POPULAR_TEMPORARY`로 답한다(11/11에 정한 관심사 요청 503은 #720에서 없앴다). 지표는 `hapbang.chatbot.recommend.search.errors{feature=lecture_recommend}`이고, LLM 실패만 세는 `fallbacks`에는 넣지 않는다. 로그는 호출 실패가 시작되거나 회복될 때만 남긴다. DB 오류는 그대로 500.
- **인기순 캐시**(`PopularLectureCache`): 맞춤 후보가 모자랄 때만 인기순을 읽는다. 회원과 상관없는 상위 200개(`popular-cache-size`)를 pod 메모리에 1분(`popular-cache-ttl`) 두고 회원별 제외는 코드에서 한다. 채운 강의는 응답 직전에 ID로 다시 읽어 판매 중·판매자 보임을 확인하고, 하나라도 바뀌었거나 캐시로 다 채우지 못하면 인기순 쿼리를 직접 돈다(`popular-pool-size` 50개).
- **호출 제한**: 위젯 API는 모든 요청을 회원마다 분당 60회(`widget-rate-limit`, `chatbot-recommend-lecture-widget`)로 세고, 관심사를 넣은 요청은 분당 10회(`rate-limit`, `chatbot-recommend-lecture`)도 함께 센다. 넘으면 429이고 FE는 위젯을 숨긴다. Tool·버튼 경로는 대화 진입점(#720)의 제한이 감싼다.
- **LLM**: 대화 Tool `recommendLectures`(회원 ID는 `ToolContext`의 `memberId`)는 정해진 이유 문장만 준다. 결과를 바깥 대화 LLM이 읽으므로 회원이 산·담은 강의 제목은 넣지 않는다("구매하신 강의와 비슷해요"). 기준 강의 제목은 회원 본인 화면(API)에만 나간다. 관심사가 100자를 넘으면 Tool은 거절하지 않고 자른다(모델 인자라 거절하면 대화가 끊긴다. API는 400). 버튼 경로(`POST /recommendations/lectures/explained`)는 아래 "챗봇 대화 흐름"의 리랭킹을 거친다.

**지키는 것** (테스트가 고정한다. 바꾸면 해당 테스트가 깨진다)

| 보장 | 외부 장애 때도 | 테스트 |
|---|---|---|
| 보유·결제 중·장바구니·본인 강의·판매 중 아님·삭제·원본 없음·숨김 판매자(탈퇴·마스킹·사본 없음)의 강의는 추천하지 않는다 | 인기순으로 채울 때도. 사본 상태가 `ACTIVE`인 채 마스킹만 반영된 판매자도 | `LectureRecommendUseCaseTest.exclusions` |
| 요청 회원이 숨김(탈퇴·마스킹)이면 빈 결과(인기순도 주지 않는다) | | `hiddenRequesterGetsNothing`, `maskedRequesterGetsNothing` |
| 판매자당 2개, 모두 5개. 모자라면 인기순 | 색인 전 강의도 사본으로 채운다 | `LectureRankerTest.capsPerSellerAndSize`, `LectureRecommendUseCaseTest.sellerCapAndPopularFill` |
| 가중이 0 이하인 강의는 신호가 아니다 | | `LectureSignalsTest.dropsNonPositive` |
| 대화 Tool 결과에 회원이 산·담은 강의 제목이 없다 | | `toolHidesBasisTitle` |
| 검색이 실패하면 관심사가 있든 없든 인기순 + `POPULAR_TEMPORARY`(오류로 답하지 않는다). 모델·ES를 끈 실행은 `POPULAR` | index 없음, 임베딩 장애, 모델·ES 연결 없는 실행 | `unavailableIndex`, `interestEmbeddingFailure`, `LectureRecommendModelOffTest`, `ChatbotLectureRecommendApiIntegrationTest.interestValidation` |
| 인기순 캐시 뒤 판매 중지·판매자 숨김이 된 강의는 응답에 나오지 않는다. 캐시는 1분 동안 다시 읽지 않는다 | 캐시가 낡아도 | `popularCacheRechecksBeforeAnswer`, `popularCacheTtl` |
| 위젯은 관심사가 없어도 넓은 호출 제한(429), 관심사 요청은 좁은 제한도 함께 | | `ChatbotLectureRecommendApiIntegrationTest.widgetRateLimit`, `interestRateLimit` |
| 요청 회원 사본이 아직 없으면 빈 목록과 `notice` | 가입 직후 | `ChatbotLectureRecommendApiIntegrationTest.requesterWithoutCopy` |

## 챗봇 인플루언서 추천 (#719)

출연 상대로 어울리는 인플루언서 5명을 추천한다. 후보와 순서는 코드가 정하고(같은 상태면 같은 결과), LLM은 버튼 경로에서 이유만 쓴다.

| 경로 | 취향 | LLM |
|---|---|---|
| `GET /api/v1/chatbot/recommendations/influencers` (위젯) | 내 프로필 + 함께 출연을 마친 상대들 | 없음. 회원별 호출 제한(연타 방지) |
| `POST /api/v1/chatbot/recommendations/influencers/explained` `{interest?}` (챗봇 버튼) | 같음. 본문에 관심사가 있으면 그 문장(쿼리 확장) | 리랭킹 + 이유(아래 "챗봇 대화 흐름"). 회원별 호출 제한 |
| Tool `recommendInfluencers(interest?)` (자유 질문 #720) | 관심사 문장이 있으면 그 문장, 없으면 위와 같음 | 대화 LLM이 설명 |

- **색인**(`chatbot-influencers-<공급자>-<차원>`): 회원 사본이 바뀐 트랜잭션에 `INFLUENCER` 요청을 적고 처리기(`InfluencerIndexTaskHandler`)가 지금 사본 상태로 맞춘다. 대상은 `INFLUENCER`·`VERIFIED_INFLUENCER`. 정지·강등·탈퇴는 `hidden=true`로만 바꾸고(다시 임베딩하지 않음, 글이 비어도 지우지 않음) 복구되면 `false`(글이 바뀌었는지 봐야 해서 분야 이름 표가 필요하다. 회원 API가 죽어 있으면 살아날 때까지 숨긴 채로 둔다). 마스킹하면 문서를 지운다. 추천 가능한데 분야와 자기소개가 모두 없으면 문서를 두지 않는다(벡터 없는 문서는 두지 않는다. 인기순 후보는 DB에서 고른다). 처리기는 쓴 묶음 끝에 refresh해 다음 묶음이 방금 쓴 상태를 본다. 임베딩 글은 분야 한글 이름(회원 API, `category-names-ttl` 10분 보관, 실패하면 `category-names-retry` 30초 뒤 다시 받음, 처음 받는 중에는 색인 쪽 호출만 기다리고 화면용 호출은 코드를 그대로 보여 줌) + 이메일·전화번호를 지운 자기소개이고, ES에는 글 해시만 둔다. 해시가 같으면 다시 임베딩하지 않는다.
- **정합 점검**(`InfluencerIndexCheckBatch`, 매시 :45, `hapbang.chatbot.recommend.influencer.check-cron`): 모든 회원 사본을 문서와 비교해 어긋난 회원의 요청을 적는다. 첫 배포 뒤 첫 점검이 처음 전체 색인이다. 연속 3번 실패하면 `[CHATBOT_INFLUENCER_CHECK_FAILED]` ERROR(실패 수는 pod 메모리, FAQ 점검과 같다). 요청만 적으므로 배치 스레드를 짧게 쓰고, 임베딩·재시도는 실행기 한 곳이 한다. 사본 행은 지우지 않으므로(탈퇴·마스킹도 행을 남긴다) 사본만 훑는다. ES에만 남은 문서는 DB를 초기화했을 때만 생기고 그때는 index를 지우고 다시 색인한다(강의 추천 점검과 같다). 추천에는 DB 재확인으로 나오지 않는다.
- **인기 점수**: 요청할 때 사본에서 센다(색인에 넣지 않으므로 출연·리뷰가 바뀌어도 재색인이 없다). 완료 출연 수(호스트·게스트) + 리뷰 수로 보정한 평균 별점(베이지안), 원본 없음 출연·리뷰는 뺀다. 후보 안에서 0~1로 맞춘 뒤 `(1 − β)·유사도 + β·인기`.
- **후보**: kNN(`hidden=false`) 뒤 DB로 추천 가능(역할·`ACTIVE`·마스킹 아님)을 다시 확인한다. 본인은 빼고, 함께 출연한 상대는 빼지 않는다(`PAST_PARTNER`). 모자라면 인기순으로 채운다. 개인 신호가 없으면 인기순 + `needsInterest=true`. 임베딩·ES가 실패하면 인기순 + `POPULAR_TEMPORARY`(지표 `recommend.search.errors{feature=influencer_recommend}`). 근거 코드(`basis`)와 후보 두 단계(`pool`·`complete`)는 강의 추천과 같다. 요청 회원이 숨김(`MemberVisibility`: 사본 없음·탈퇴·마스킹, #717 규칙을 강의 추천과 같이 따름)이면 빈 목록과 `notice`(정해진 안내 문구). 사본이 없는 것은 대개 가입 직후 동기화 전이다. 인기 집계는 요청마다 추천 가능 회원 전체를 센다(강의 추천의 인기 후보 50개 같은 상한이 없다). 수천 명 규모라 캐시를 두지 않는 대신 전체 평균 별점은 요청마다 한 번만 세고, 위젯에도 회원별 호출 제한(분당 60회)을 걸어 한 회원의 연타가 앱 공용 DB 연결을 붙잡지 않게 한다. 그래도 느려지면 짧은 캐시를 둔다.
- 회원 ID는 Tool 인자로 받지 않고 `ToolContext`의 `memberId`(`ChatToolContext`)에서만 꺼낸다. Tool 관심사 문장은 `interest-max-length`(100글자)를 넘으면 자른다(대화 LLM이 채우는 값이라 거절하지 않는다). 글자(코드 포인트) 단위로 잘라 이모지가 반으로 갈리지 않는다. Tool 호출은 `tool.calls{tool=recommendInfluencers}`로 세고, 위젯 사용 수(`influencer_recommend`)·버튼 사용 수(`influencer_button`)와 나눈다. 설명 API의 LLM 프롬프트에는 후보 순번·분야 이름·완료 수·평균 별점·이유 코드만 넣는다(닉네임·자기소개 원문·회원 ID 없음). Tool이 대화 LLM에 돌려주는 결과에는 회원 ID와 `PAST_PARTNER`가 없다(#720). 회원 ID·함께 출연한 이력은 카드(수집기 → FE)에만 있다.
- 설정 `hapbang.chatbot.recommend.influencer.*`(`ChatbotInfluencerRecommendProperties`): 결과 수, kNN `k`·`num-candidates`, 상대 가중, β, 인기 가중치·베이지안 C, 호출 제한(설명 `rate-limit` 분당 10회, 위젯 `widget-rate-limit` 분당 60회, 따로 센다), Tool 관심사 최대 길이, 분야 이름 보관·재시도 시간, 점검 실패 알림 횟수.
- LLM 설명은 후보를 순번으로 받아 코드가 회원 ID로 되돌린다(리랭킹, 아래 "챗봇 대화 흐름").

**지키는 것** (테스트가 고정한다. 바꾸면 해당 테스트가 깨진다)

| 보장 | 외부 장애 때도 | 테스트 |
|---|---|---|
| 마스킹 회원 문서는 지운다 | 회원 API(분야 이름)가 기동 때부터 죽어 있어도 | `InfluencerIndexTaskHandlerTest.categoryNamesDown`, `InfluencerIndexRequestTest.checkerWithoutCategoryNames` |
| 정지·강등·탈퇴는 숨긴다. 복구되면 푼다 | 숨김: 회원 API 장애 때도. 복구: 정지 직후 복구(ES refresh 지연). 회원 API 장애 때는 살아날 때까지 숨긴 채 | `hideAndShowWithoutReembedding`, `restoreRightAfterSuspend`, `categoryNamesDown`, `checkerWithoutCategoryNames` |
| 본인은 추천하지 않는다 | | `InfluencerRecommendUseCaseTest` |
| 숨김 회원(사본 없음·탈퇴·마스킹)은 요청하면 빈 결과다. 추천 불가(정지·마스킹)는 후보로 나오지 않는다 | 사본 상태가 `ACTIVE`인 채 마스킹만 반영돼도(맞춤 후보·인기순 채우기 모두). 색인이 늦어 문서가 아직 보여도 | `withdrawnRequester`, `maskedRequester`, `requesterWithoutCopy`, `maskedCandidates`, `staleIndexIsFilteredByCopy` |
| 항상 5명(추천 가능한 회원이 그보다 적을 때만 적게) | 임베딩 장애, index 없음(첫 배포·ES 오류) 때 인기순 + `POPULAR_TEMPORARY` | `InfluencerRecommendUseCaseTest.searchFailsWithoutIndex`, `embeddingFailureFallsBackToPopular` |
| 회원 ID는 `ToolContext`에서만. Tool이 대화 LLM에 돌려주는 결과에는 회원 ID·`PAST_PARTNER`가 없고 카드에는 있다 | | `InfluencerRecommendApiTest.tool` |
| 설명 API의 LLM 프롬프트에 닉네임·자기소개 원문·회원 ID를 보내지 않는다 | | `explained`, `promptHasNoMemberIds` |
| 표를 처음 받는 중이면 색인 쪽 호출은 기다리고, 화면용 호출은 기다리지 않는다 | 기동 직후(받기에 실패하면 재시도 시간까지는 기다리지 않고 실패) | `ChatbotCategoryNamesTest.waitsForFirstFetch`, `displayDoesNotWait` |

## 챗봇 대화 흐름 (#720)

첫 화면 버튼 4개(FAQ 모아보기·강의 추천받기·인플루언서 추천받기·자유 질문하기)를 FE가 그리고, 서버는 아래 API를 준다. 챗봇은 로그인 회원 전체(USER 이상)만 쓴다.

| 버튼 | API | LLM |
|---|---|---|
| FAQ 모아보기 | `GET /api/v1/chatbot/faqs` | 없음 |
| 강의 추천받기 | `POST /api/v1/chatbot/recommendations/lectures/explained` `{interest?}` | 쿼리 확장(관심사가 있을 때) + 리랭킹·이유 1회 |
| 인플루언서 추천받기 | `POST /api/v1/chatbot/recommendations/influencers/explained` `{interest?}` | 같음 |
| 자유 질문하기 | `POST /api/v1/chatbot/messages`, `/messages/stream`(SSE) `{message, history}` | Tool Calling |

- **버튼 경로**(`LectureRecommendExplainUseCase`·`InfluencerRecommendExplainUseCase`, 처리는 `RecommendReranker`·`QueryExpander` 한 벌)
  - 관심사가 있으면 쿼리 확장으로 비슷한 표현을 받아 원문과 함께 임베딩해 평균을 낸다(`ChatbotEmbedder.embedInterest`). 실패하면 원문으로 계속한다(`fallbacks{feature=query_expansion}`).
  - 코드가 맞춤 후보 풀(최대 `rerank.pool-size` 20, 인기순 채움 없음)을 만들고 LLM이 그 안에서 순번으로 고르고 이유를 쓴다. 고른 것 → 남은 풀(코드 순서) → 인기순으로 5개를 채운다. 고르지 않은 항목은 이유 코드 문장이다. 풀이 비면 LLM을 부르지 않는다.
  - 형식은 구조화 출력(`AdvisorParams.ENABLE_NATIVE_STRUCTURED_OUTPUT`)이고 목록을 record로 감싼다(`{"picks": [...]}`, `{"phrases": [...]}`). 범위 밖·중복 순번, 빈 문장, 순번을 말한 문장은 버리고 이유는 80자에서 자른다.
  - LLM이 실패·시간 초과·회로 열림·동시 상한·형식 오류이거나 쓸 만한 답이 0개면 코드 순서 5개 + 이유 코드 문장, `fallback=true`(일부만 쓸 만하면 `false`).
  - 형식 오류(Jackson 파싱 실패)는 회로 실패로 세지 않는다(`fallbacks{reason=format}`). Spring AI 2.0.1은 이때 Jackson 예외를 그대로 던진다.
  - 리랭킹을 끄면(`rerank.enabled=false`) 코드 순서 앞 5개만 보여 주고 LLM에게는 이유만 받는다(순서는 코드 순서). 실패 로그에는 예외 클래스 이름만 남긴다(형식 오류 메시지에 모델 출력이 들어갈 수 있다).
  - 설정 `hapbang.chatbot.recommend.rerank.*`(`enabled`·`model`·`timeout` 12s·`pool-size` 20·`reason-max-length` 80·`description-max-length` 300), `hapbang.chatbot.recommend.query-expansion.*`(`model`·`timeout` 5s·`max-expansions` 3). 시간 상한은 출발값이다. 호출 제한은 강의 버튼 분당 10회(`button-rate-limit`), 인플루언서 버튼 분당 10회(`rate-limit`).
- **자유 질문**(`ChatbotConversationService`)
  - Tool 3개(`searchFaq`·`recommendLectures`·`recommendInfluencers`)를 요청마다 등록하고, 회원 ID·FAQ 근거·카드 수집기는 `ChatToolContext`로 넘긴다. 시스템 프롬프트는 서버 것만 쓴다.
  - `history`는 FE가 보낸다(서버 저장 없음, 11/09 결정). 최근 12개만 쓰고, role은 `USER`·`ASSISTANT`만, 내용은 USER 1~500자·ASSISTANT 1~2,000자, 쓰는 대화의 글자 수 합 8,000자까지. 어기면 400(`INVALID_CHAT_HISTORY`, 역할 값 자체가 틀리면 `INVALID_REQUEST`). 설정 `hapbang.chatbot.chat.*`.
  - 추천 카드는 Tool 결과(코드가 만든 것)만이다. 비스트림은 응답 `cards`, 스트림은 글이 끝난 뒤 `cards` 이벤트다. LLM 글은 설명이다. 강의 카드의 이유 문장은 기준 강의 제목 없이 쓴다(위젯 API의 문장과 다를 수 있다). Tool 결과에는 근거 코드와, 검색이 잠시 실패했으면(`POPULAR_TEMPORARY`) LLM이 그 사실을 말하게 하는 안내 한 줄(`guide`)이 있다.
  - LLM이 실패하면 카드가 있으면 카드 + "추천 결과를 아래에 보여 드려요." + `fallback=true`, 없으면 FAQ 대화와 같이 FAQ 상위 1건 또는 안내 문구(+ FAQ 모아보기 안내). 스트림에서 조각을 보낸 뒤 실패하면 `cards`를 보낸 뒤 `error`로 끝낸다.
  - 한 요청의 Tool 호출은 `spring.ai.tools.limits.max-total-tool-calls: 3`이다. Spring AI 2.0.1의 `ToolCallingAdvisor`는 상한을 넘으면 예외를 던지지 않고 종료 사유 `toolCallLimitExceeded`인 응답(상한 안내문)을 돌려준다. 대화는 이를 실패로 바꿔 위 규칙을 따르고 회로 실패로 세지 않는다(`fallbacks{reason=tool_limit}`). 모듈 테스트는 앱 yaml을 읽지 않아 `hapbang-module-test.yaml`에도 같은 값을 둔다.
  - 시간 상한: 한 번에 받기 전체 25s(`chat.timeout`, Gateway 30s 안), 스트림 45s(`chat.stream-timeout`). 출발값이다.
  - `history`는 50개까지 받는다(넘으면 400). 글자 수 합 8,000자는 버리고 남은 최근 12개에만 센다. 검사는 회원별 호출 제한보다 먼저 한다(400 요청은 한도를 쓰지 않는다).
  - 호출 제한은 FAQ 검색·대화(#715)의 제한을 함께 쓴다. 대화 내용은 DB·로그에 남기지 않는다(로그에는 history 개수·글자 수·부른 Tool 이름·걸린 시간만).
- **LLM HTTP 시간 상한**: prod OpenAI 호출은 openai-java SDK의 자체 HTTP 클라이언트로 나가 앱 공통 `spring.http.clients.read-timeout`(5s)이 걸리지 않는다. `spring.ai.openai.chat.timeout: 50s`(가드 상한 중 가장 긴 스트림 45s보다 길게), `max-retries: 0`으로 둔다. chat 클라이언트가 하나라 기능별로 다르게 둘 수 없다. 그래서 리랭킹(12s)·쿼리 확장(5s) 가드가 포기한 뒤에도 HTTP 호출이 최대 50s 남아 동시 상한 4를 넘길 수 있다(남은 위험, 측정 뒤 기능별 클라이언트를 검토). local Ollama는 부트의 `RestClient.Builder`를 받아 만든다.
- **FE GA4 이벤트 이름 제안**(FE가 정한다): `chatbot_button_click{button}`, `chatbot_recommend_card_impression{kind, basis, reason}`, `chatbot_recommend_card_click{kind, reason, position}`, `chatbot_conversation_start`, `chatbot_retry_click{kind}`(POPULAR_TEMPORARY 안내의 다시 시도).

**지키는 것** (테스트가 고정한다. 바꾸면 해당 테스트가 깨진다)

| 보장 | 외부 장애 때도 | 테스트 |
|---|---|---|
| 버튼: LLM이 고른 순번 → 남은 풀(코드 순서) → 인기순으로 5개. 범위 밖·중복 순번은 버린다 | | `LectureRecommendButtonTest.rerankPicksThenRestOfPool`, `fillsWithPopularAfterRerank`, `InfluencerRecommendApiTest.explained` |
| 버튼: LLM이 실패하면 코드 순서 5개 + 이유 코드 문장, `fallback=true` | LLM 오류·형식 오류·쓸 만한 답 0개 | `llmFailureFallsBackToCodeOrder`, `formatErrorIsNotCircuitFailure`, `noUsableAnswerIsFallback`, `InfluencerRecommendApiTest.explainedFallback` |
| 형식 오류는 회로 실패 수에 들어가지 않고 `reason=format`으로 센다 | | `formatErrorIsNotCircuitFailure` |
| 버튼: 풀이 비면 LLM 호출 0회. 관심사가 있으면 쿼리 확장 1회 + 리랭킹 1회, 쿼리 확장이 실패해도 계속 | 쿼리 확장 실패 | `emptyPoolSkipsLlm`, `interestCallsQueryExpansionThenRerank`, `InfluencerRecommendApiTest.explainedWithInterest` |
| 버튼 프롬프트에 회원 ID·회원이 산 강의가 없다 | | `LectureRecommendButtonTest.promptHasNoMemberInfo` |
| 자유 질문: 요청마다 Tool 3개를 등록하고 ToolContext에 회원 ID를 넣는다 | | `ChatbotConversationApiTest.registersThreeTools` |
| 자유 질문: 추천 카드는 비스트림 `cards`·SSE `cards` 이벤트로 나간다 | | `cardsInResponse`, `streamSendsCards` |
| 자유 질문: Tool이 카드를 만든 뒤 LLM이 실패해도 카드 + 정해진 문장 + `fallback=true`. 조각 뒤 실패는 `cards` 뒤 `error` | LLM 실패(스트림·비스트림) | `cardsSurviveLlmFailure`, `streamCardsBeforeFirstToken`, `streamCardsThenError` |
| 자유 질문: Tool 호출이 3회를 넘으면 더 부르지 않고 실패로 처리한다(회로 실패 아님) | 모델이 Tool을 계속 요청(스트림·비스트림) | `toolCallLimit`, `streamToolCallLimit` |
| `history`: 50개까지, 최근 12개만, 허용 밖 role·빈 내용·길이 초과는 400. 400은 호출 한도를 쓰지 않는다 | | `historyKeepsRecentTwelve`, `historyValidation` |
| 자유 질문: FAQ와 함께 쓰는 호출 제한을 넘으면 429. 스트림은 SSE를 열지 않는다 | | `rateLimitBeforeStream` |
| 추천 Tool: 검색이 잠시 실패하면 결과에 `POPULAR_TEMPORARY`와 안내 한 줄(`guide`) | 임베딩·ES 장애 | `LectureRecommendToolsTest.temporaryPopularGuide`, `InfluencerRecommendApiTest.toolTemporaryPopularGuide` |
| 버튼: 리랭킹을 끄면 코드 순서 그대로, LLM 이유만 쓴다 | | `RecommendRerankerTest.disabledKeepsCodeOrder` |
| 로그에 대화 본문이 남지 않는다 | | `logsHaveNoConversation` |

## 검색 (#771, #772, #868)

인플루언서 검색 모듈이다. 구조·index·이벤트 처리·API·지표는 [`docs/search/module.md`](../../docs/search/module.md)에 있다.

- 코드는 대상과 상관없는 공통 코드(`search.common`: index·alias·동의어·재색인·tombstone 정리·지표)와 대상별 구현(`search.influencer`)으로 나뉜다(#868). 강의·모집 검색은 `search.<대상>`으로 더한다(`docs/search/module.md` 3장 "새 대상을 붙이는 법").
- 검색 기능 스위치는 #860에서 지웠다. 검색 빈은 늘 뜨고, ES가 없어도 챗봇·알림은 그대로이며 검색 API만 503이다(`docs/search/module.md` 2장).
- ES는 챗봇과 같은 cluster지만 검색 전용 client가 `ES_SEARCH_API_KEY`(role `search`, `search-*`)로 따로 붙는다(`hapbang.search.elasticsearch.*`). 검색 index는 nori를 쓰므로 로컬 `compose.yaml`의 ES는 nori를 설치한 이미지다.
- Kafka: `member.events.v1`, 그룹 `auxiliary-app.search-member`. 배치: 통계 동기화·문서 수 정합성 점검(끝난 뒤 10분, `JobLock` 771_002), 회원 변경분 증분 보정(끝난 뒤 10분, 772_001), tombstone 정리(매일 04:30, 771_001), 0건 검색어 로그(10분, 락 없음).
- 나눠 쓰는 자원: DB는 증분 보정 커서(`search.sync_cursor`, 보정 페이지마다 짧은 쓰기 1번)와 정합성 점검 결과(`search.consistency_check`, 10분에 쓰기 1번) 두 행, 지표 수집 때 읽기 2번, `JobLock` 연결만 쓴다. 배치 스레드 2개를 챗봇과 나눠 쓰지만 검색 작업은 짧은 ES·HTTP 호출이라 그대로 둔다. 메모리는 client와 10분 캐시(분야 이름, 평점 평균, 0건 검색어 최대 1,000개)뿐이라 limit을 바꾸지 않는다.
- 로컬 실행: [`docs/search/module.md`](../../docs/search/module.md) 10장.

## 운영 배포

- prod overlay는 `replicas: 0`으로 시작했고(#714), 운영 준비가 끝나 1로 올렸다(#726). dev에 병합하면 다른 앱처럼 자동으로 배포된다.
- 운영 준비(#726): PostgreSQL role·database `auxiliary`, SealedSecret `k8s/overlays/prod/auxiliary-app-secrets.yaml`(`DB_PASSWORD`, `HAPBANG_INTERNAL_API_KEY`, `ES_API_KEY`, `OPENAI_API_KEY`), ES role `chatbot`·API key, `hapbang-prod`의 ConfigMap `elasticsearch-ca`(키 `ca.crt`), large node 자리, `max_connections` 계산. 값과 절차는 `docs/deploy/k8s.md`의 "Secret"·"PostgreSQL database"·"Elasticsearch 앱 접근"에 있다.
- 공용 인프라(`k8s/platform`, `k8s/tools`, `k8s/gateway`, `docker/`)와 인프라 문서는 이 앱 작업에서 고치지 않고 인프라 담당과 정한다.
- 비밀값을 더하거나 바꾸면 `~/.hapbang/prod-secrets.env`에 넣고 `k8s/tools/seal-app-secrets.sh auxiliary`로 다시 봉인한다(필수·선택 키 목록은 그 스크립트의 `auxiliary` spec).
