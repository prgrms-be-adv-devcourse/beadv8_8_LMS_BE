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
| `:auxiliary-global` | `:shared` | 응답 `RsData`, `GlobalExceptionHandler`, 로그인 회원 `@LoginUserId`, 내부 API 키·포트, 스케줄링 조건, 로그 MDC. 업무 규칙은 두지 않는다 |
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
- 역할 검사는 각 모듈 Facade의 `@PreAuthorize`로 한다. 챗봇은 인플루언서 이상(`INFLUENCER`·`VERIFIED_INFLUENCER`)만 쓸 수 있다.

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

## 실행과 설정

| 항목 | 값 |
| --- | --- |
| 포트 | 외부 API 8080(로컬 8087), 내부 API 8081(`HAPBANG_INTERNAL_API_PORT`, k8s만), 관리 8082(`MANAGEMENT_SERVER_PORT`, k8s만) |
| 헬스체크 | `GET /actuator/health` (k8s는 관리 포트의 liveness·readiness) |
| Swagger | `/api/v1/auxiliary/swagger-ui.html` (`global/src/main/resources/swagger.yml`). 모듈 셋의 API가 한 명세에 있고 컨트롤러 `@Tag`로 나뉜다 |
| DB 연결 풀 | Hikari 5. PostgreSQL `max_connections` 100을 다른 앱과 나눠 쓴다 |
| 배치 스레드 | `spring.task.scheduling.pool.size` 2 |
| Kafka·Redis | common 라이브러리 자동 설정 |
| 메모리 | large node, limit 704Mi, `-Xmx256m`, `MALLOC_ARENA_MAX=2`(#684 예산) |

DB 연결 풀·배치 스레드·메모리는 앱 하나의 값이라 모든 모듈이 나눠 쓴다. 지금 값은 챗봇 하나 기준이다. 모듈을 더하는 PR에서 그 모듈이 동시에 쓰는 DB 연결·배치 수와 메모리를 보고 다시 정한다. 한 모듈이 다 쓰면 다른 모듈의 요청·배치가 기다린다.

STOMP 연결 티켓(알림 모듈)에 쓰는 토큰 만료 헤더 `X-User-Token-Exp`는 global 필터와 Gateway `claimToHeaders`에 없다. chat-app global에서 복사할 때 뺐으므로, 쓰는 모듈이 Gateway 설정과 함께 더한다.

로컬은 다른 앱과 같이 `./gradlew :auxiliary-app:bootRun`으로 띄운다. 로컬 PostgreSQL(docker compose)은 모든 앱이 database 하나를 schema로 나눠 쓰므로 따로 만들 것이 없다.

## 운영 배포

- prod overlay는 `replicas: 0`으로 시작한다(#714). dev 병합이 곧 운영 배포라, 운영 database와 Secret이 준비되기 전에 뜨면 기동에 실패한다.
- 공용 인프라(`k8s/platform`, `k8s/tools`, `k8s/gateway`, `docker/`)와 인프라 문서는 이 앱 작업에서 고치지 않는다. 띄우기 전에 인프라 담당과 정해서 할 일:
  1. 운영 PostgreSQL에 role·database `auxiliary`를 만들고, `postgres-credentials`(hapbang-platform)에 `auxiliary` 키를 더한다. 새 cluster용 initdb 목록과 로컬 platform overlay에도 `auxiliary`가 들어가야 한다.
  2. `k8s/tools/seal-app-secrets.sh`에 `auxiliary`를 더하고, 이 앱의 SealedSecret `k8s/overlays/prod/auxiliary-app-secrets.yaml`(`DB_PASSWORD`, `HAPBANG_INTERNAL_API_KEY`)을 만든다.
  3. #684에 적은 large node 선행 작업(PostgreSQL 512Mi, member·appearance·billing `-Xmx`)을 적용한다.
  4. PostgreSQL `max_connections` 계산에 이 앱(Hikari 5)을 넣는다.
  5. 문서와 로컬 설정: `docs/architecture/infrastructure/k3s.md` 메모리 예산, `docs/deploy/k8s.md` Secret 표, `k8s/gateway/tests/run.py` 앱 목록, 로컬 Gateway `docker/envoy/envoy.yaml`의 `/api/v1/chatbot` route.
- 끝나면 `auxiliary-app-secrets.yaml`을 prod overlay `resources`에 더하고 `replicas` 패치를 지우는 PR을 낸다. 그때 이 앱의 첫 이미지로 CI가 새로 만든 GHCR 패키지에 "Inherit access from source repository"가 켜져 있는지 보고, `k8s/tools/ghcr-pull-check.sh`로 운영 `ghcr-pull`이 이 이미지를 받을 수 있는지 확인한다(`docs/deploy/k8s.md`).
