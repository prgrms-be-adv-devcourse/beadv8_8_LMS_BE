# 로그

업무 앱 7개가 함께 따르는 로그 표준이다. 요청 하나·회원 한 명·이벤트 하나의 로그를 Loki에서 모으고 Tempo trace와 오갈 수 있게 하는 것이 목적이다. 결정은 #582(2026-11-04)에서 했다.

## 형식

- **운영**(`prod` 프로필)은 Boot 내장 구조화 로그의 `logstash` 형식(JSON 한 줄)으로 쓴다. 앱마다 `application-prod.yaml`에 `logging.structured.format.console: logstash` 한 줄을 둔다.
- **로컬·테스트**는 Boot 기본 평문이다. 프로필 기본값이 `local`이고, `dev`·`test`·`prod` 프로필로 뜨는 테스트도 평문이 아니면 읽기 어렵다. 그래서 JSON은 `application.yaml`이 아니라 `application-prod.yaml`에서만 켠다.
- logstash-logback-encoder와 `logback-spring.xml`은 쓰지 않는다. Boot 내장 `logstash`가 같은 필드 이름을 내고, 의존성이 없으며, Native Image(customer-support)도 지원한다. encoder만 주는 값 마스킹은 쓰지 않는다(아래 "민감 정보").

| 필드 | 내용 |
| --- | --- |
| `@timestamp` | 기록 시각 |
| `level` | `ERROR`·`WARN`·`INFO`·`DEBUG` |
| `logger_name` | 로거(클래스) 이름. 스케줄 작업을 거를 때 쓴다 |
| `thread_name` | 스레드 이름 |
| `message` | 메시지 |
| `stack_trace` | 예외의 stack trace. 같은 JSON 줄 안에 들어간다 |
| MDC 키 | `traceId`·`userId` 등이 최상위 필드로 들어간다(아래 "MDC 키") |
| `@version`·`level_value` | logstash 형식 고정 필드 |

로그 양을 줄여야 하면 `logging.structured.json.exclude`로 고정 필드(`@version`·`level_value`)를 뺀다. 지금은 뺄 필요가 없다(2026-11-06, #699).

- **줄당 크기와 줄 수**: JSON 줄은 줄당 약 466 B로 바꾸기 전 평문(약 111 B)의 4.2배다. 대신 여러 줄 로그(Kafka client 설정 출력, stack trace)가 JSON 한 줄로 합쳐져 줄 수는 줄었다. 지금 pod(기동 1번)마다 여러 줄 로그는 JSON 3줄에서 7줄이고, 평문이었다면 220~530줄이었다.
- **PVC**: 바꾸기 전 하루(앱 6개 약 2만 줄, 약 2.2 MB, pod 기동 37번)의 줄이 하나도 합쳐지지 않고 모두 JSON 한 줄씩 된다고 크게 잡아도, 앱 로그는 하루 약 9.3 MB이고 Envoy 로그를 더한 7일치는 약 105 MB(압축 전)로 Loki PVC 5 GiB의 약 2%다.
- **Loki 메모리**: 바꾼 뒤 하루 최대 140 MiB(limit 256 MiB의 55%)였다. 그날은 pod 기동(10번)과 트래픽이 적어 Loki 수집량이 바꾸기 전 날의 절반 아래(3.5 MB, 바꾸기 전 8.0 MB)였으므로 같은 부하에서 비교한 값은 아니다.

JSON으로 바꾼 뒤 앱 메모리 차이는 pod마다 흔들리는 폭과 구분되지 않았다. 바꾸기 전 같은 설정 pod와 기동 90분 working set을 비교하면 앱별 차이가 −16~+25 MiB였고, 같은 설정 pod끼리의 차이(appearance 두 pod 40 MiB)보다 작았다(2026-11-05, #699). 앱마다 pod 1~2개를 기동 90분 시점에 비교한 결과이고, 오래 돈 pod끼리는 아직 비교하지 않았다([k3s 자원 예산](../architecture/infrastructure/k3s.md#ec2-2노드-자원-예산)).

## 수집과 조회

- Alloy는 pod log를 형식 그대로 Loki로 보낸다. label은 `namespace`·`app`·`pod`·`container`뿐이고 JSON은 수집할 때 파싱하지 않는다. `level`·`userId`를 label로 올리면 stream이 그 값마다 갈라져(특히 `userId`는 회원 수만큼) Loki가 느려진다.
- JSON 필드는 조회할 때 `| json`으로 꺼낸다. Envoy access log도 같은 방식이다.

| 찾는 것 | LogQL |
| --- | --- |
| 요청 하나(앱·Envoy, 다른 앱의 내부 API·Kafka 소비 포함) | `{namespace=~"hapbang-prod\|envoy-gateway-system"} \|= "<traceId>"` |
| 회원 한 명이 보낸 요청 | `{namespace="hapbang-prod"} \| json \| userId="42"` |
| 한 대상에 대한 이벤트 처리 | `{namespace="hapbang-prod"} \| json \| eventKey="exchange:45"` |
| 앱의 ERROR·WARN | `{namespace="hapbang-prod", app="lecture-app"} \| json \| level=~"ERROR\|WARN"` |

- **회원에서 요청으로**: `userId`는 그 회원의 요청을 처음 받은 앱의 로그에만 있다. 그 줄의 `traceId`로 다시 찾으면 비동기 스레드·다른 앱의 로그까지 모인다. Loki는 두 쿼리를 하나로 합치지 못하므로 두 번 찾는다.
- **입구가 없는 요청**: 성공한 조회(GET)는 앱 로그를 남기지 않고, Envoy에서 끝난 요청(401, route 없음)은 앱에 오지 않는다. 그래서 Envoy access log에 `user_id: '%REQ(X-USER-ID)%'`를 더해 모든 요청에 회원 ID와 `trace_id`의 짝을 남긴다(`k8s/gateway/base/gateway.yaml`). 외부에서 보낸 `x-user-id`는 Envoy가 먼저 지운다(`earlyRequestHeaders.remove`). JWT 필터가 넣은 값을 access log가 읽는지는 Gateway를 운영에 적용한 뒤 access log로 확인한다(#582).
- **평문과 JSON을 함께 읽는 곳**: JVM dashboard의 ERROR·WARN 패널(`k8s/platform/base/dashboards/jvm.json`)과 Loki datasource의 derived field(앱 로그 → Tempo, `k8s/platform/base/observability.yaml`)는 평문(`[traceId-spanId]`, 줄 앞 레벨)과 JSON(`"traceId":"…"`, `"level":"ERROR"`)을 모두 정규식으로 찾는다. 로컬 k3d(평문)와 전환 전 운영 로그도 그대로 보이게 하기 위해서다. 앱 개요의 로그 레벨별 건수는 지표(`logback_events_total`)라 형식과 상관없다.

## MDC 키

| 키 | 값 | 넣는 곳 |
| --- | --- | --- |
| `traceId`, `spanId` | trace 식별자 | Micrometer Tracing이 자동으로 넣는다. HTTP 요청, `@Scheduled` 실행마다, Kafka 소비(#630) |
| `userId` | 회원 ID | 각 앱의 `global/auth/GatewayUserHeaderFilter`가 `X-User-Id`를 읽은 뒤. chat STOMP 메시지는 `ChatStompInterceptor` |
| `eventType` | Kafka 헤더 `eventType`(예: `ExchangeCompletedEvent`) | `common/kafka`의 `EventLogContextRecordInterceptor` |
| `eventKey` | Kafka 레코드 key(예: `exchange:45`, `order-item:123`) | 위와 같다 |

- **`traceId`는 모든 요청·실행에 찍힌다.** Tempo로 보내지 않는 요청·실행(앱 안에서 시작하는 배치는 10%만, Grafana·Kafka UI 요청은 0%, #679)의 로그에도 `traceId`가 찍힌다. 2026-11-04 운영에서 로그에 찍힌 `traceId` 3건(billing `@Scheduled` 2건, member 4xx 요청 1건)이 Tempo에 없는 것으로 확인했다. 로그끼리 모으는 데는 샘플링이 상관없고, 그 줄에서 Tempo로 가는 링크는 Tempo에 남은 trace만 열린다(업무 요청은 모두, 앱 안에서 시작한 배치는 10%).
- **`userId`가 없는 로그**: 내부 API를 받은 앱(내부 API는 키로만 인증하고 사용자 헤더를 읽지 않는다, D63), Kafka 소비, 비동기 스레드의 로그다. 이 로그는 `traceId`로 찾는다. trace baggage로 `userId`를 다른 앱까지 넘기지 않는다. 앱 사이 헤더와 Envoy 설정이 늘고, `traceId`가 이미 같은 일을 한다.
- **스케줄 작업**: 실행마다 Boot가 trace를 만들어 `traceId`가 실행 한 번을 묶는다. 어느 작업인지는 `logger_name`으로 거른다. 그래서 `jobName`·`runId` MDC는 두지 않는다. chat의 1초 polling처럼 trace를 막아 둔 작업([관측성](../architecture/infrastructure/observability.md)의 "짧은 주기 polling")은 `traceId`가 없다.
- **Kafka 소비**: #630으로 소비가 보낸 쪽 trace를 이어받아 "요청 → 발행 → 다른 앱의 소비"가 `traceId` 하나로 묶인다. `eventKey`는 "무엇에 대한 이벤트인지"로 거를 때 쓴다. Boot는 `RecordInterceptor` 빈이 하나일 때만 자기 리스너 컨테이너 팩토리에 붙인다. 그래서 앱이 자기 `RecordInterceptor` 빈을 두면 `common/kafka`의 빈은 만들지 않는다(그 앱이 MDC도 맡는다). 앱이 팩토리를 직접 만들면(chat의 `ChatKafkaConfig`) 그 팩토리에도 직접 붙인다. 레코드 처리가 끝나면(`afterRecord`) 지우므로 재시도·DLT 전송 로그까지 키가 붙는다.
- **두지 않는 키**: `eventId`는 Modulith가 재발행할 때마다 값이 달라 쓰지 않는다([ADR 0002](../adr/0002-modulith-externalization-without-event-id.md)). `correlationId`는 `traceId`가 대신한다.
- **새 키를 더할 때**: 여러 로그 줄에 공통으로 붙어야 하는 식별자만 MDC에 넣는다. 한 줄에만 쓰는 값은 메시지에 쓴다. MDC는 넣은 곳에서 `finally`로 지운다. 스레드를 다시 쓰므로 지우지 않으면 다음 요청 로그에 남는다.

## 스레드 경계

trace context와 MDC는 스레드 로컬이라 다른 스레드로 저절로 넘어가지 않는다.

- **`@Async`**(Modulith 이벤트 외부화, `@ApplicationModuleListener`)는 앱의 `taskScheduler`가 실행한다. 앱에 `taskScheduler`가 있으면 Boot는 `applicationTaskExecutor`를 만들지 않는다. 앱마다 `global/config/ObservationConfig`의 `traceContextTaskDecorator`가 Observation만 넘기고(#630), Boot가 이 decorator를 executor·scheduler builder에 붙인다. 비동기 스레드의 로그에는 넘긴 쪽의 `traceId`가 찍히고 `userId`는 없다.
- `spring.task.execution.propagate-context`는 쓰지 않는다. 등록된 전파기를 모두 넘겨 Spring Security 인증 정보까지 비동기 리스너로 넘어간다.
- **작업을 하나씩 넘기는 executor는 Boot builder로 만든다.** Boot가 주입하는 `ThreadPoolTaskExecutorBuilder`·`ThreadPoolTaskSchedulerBuilder`·`SimpleAsyncTaskExecutorBuilder`(가상 스레드)로 만들면 위 decorator가 붙어, 작업을 넘긴 요청·소비의 `traceId`가 작업 스레드 로그에 찍힌다. chat의 읽음 수 신호(`ChatReadCountSignals`)가 예다. builder는 `spring.task.execution.*` 기본값(코어 스레드 유휴 종료 등)을 가져오므로 동작에 영향을 주는 값은 모두 명시한다. 빈이 아니면 `initialize()`도 직접 부른다. builder로 만들 수 없는 `ExecutorService`는 `ContextExecutorService.wrap`으로 감싼다(`shared`의 `BillingApiClient`가 예다).
- **여러 요청의 작업을 줄에 모아 처리하는 executor에는 decorator를 붙이지 않는다.** 줄 비우기 작업을 넘긴 쪽의 trace가 묶음 안 다른 작업의 로그에도 붙어 틀린 `traceId`가 된다. chat의 STOMP 수신·송신 실행기(`ChatChannelBudget`)가 그렇다. 처음부터 넘길 trace가 없는 스레드(Redis 구독, 주기 작업, 세션 만료)의 executor도 바꿀 필요가 없다. 2026-11-04 기준 chat의 나머지 executor·scheduler는 이 이유로 `new`로 만든 채 둔다.
- **진입점에서 새로 시작하는 곳**: `@Scheduled` 실행과 Kafka 소비는 Boot·Spring Kafka가 진입할 때 trace를 만들고, Kafka MDC는 `RecordInterceptor`가 레코드마다 넣고 지운다.
- **chat STOMP 메시지**는 HTTP 요청이 아니어서 `GatewayUserHeaderFilter`도 trace도 거치지 않는다. `ChatStompInterceptor`가 프레임을 처리하는 스레드에서 `userId`를 넣고 처리가 끝나면 지운다(`ExecutorChannelInterceptor`의 `beforeHandle`·`afterMessageHandled`). `chatRoomId`는 MDC에 넣지 않고 메시지에 쓴다. Redis로 받은 다른 서버의 전달 작업에는 회원이 정해져 있지 않아 키를 넣지 않는다.

## 레벨

| 레벨 | 기준 | 예 |
| --- | --- | --- |
| ERROR | 사람이 봐야 한다. alert 대상이다 | 지급 실패, DLT 전송, 대사 불일치, 처리하지 못한 예외, 5xx 업무 예외 |
| WARN | 자동으로 복구됐다. 반복되면 확인한다 | 재시도 뒤 성공, circuit breaker 열림, 형식이 틀린 사용자 헤더, 다음 실행으로 미룬 배치 항목 |
| INFO | 상태 변경, 돈·HP 이동의 시작과 결과, 요청을 거절한 업무 사유 | 주문 생성, HP 작업 등록·결과, 관리자·회원이 돈 흐름을 바꾼 결정(결정한 회원 ID 포함), 보류 무효화 결과, 배치 처리 건수, 4xx 업무 예외(`code`·`message`) |
| DEBUG | 개발 중에만 본다. 운영에서는 끈다 | 이미 처리한 이벤트라 무시 |

- **4xx 업무 예외는 INFO다.** 사용자 입력이 틀렸거나 정책상 거절한 정상 응답이므로 WARN 패널과 alert를 채우지 않는다. 그래도 문의를 조사할 때 거절 사유(`code`)가 필요하므로 7개 앱의 `GlobalExceptionHandler`가 INFO로 남긴다. Envoy access log에는 상태 코드만 있다.
- **Kafka 소비 처리 결과는 INFO 한 줄을 남긴다.** 복제본 반영, 명령 처리, 별점 반영처럼 받은 이벤트로 상태를 바꿨으면 리스너나 그 처리기가 한 줄을 남긴다. 그래야 `eventKey`·`traceId`로 찾을 줄이 생긴다. 쓰지 않는 이벤트를 넘기는 것은 DEBUG다.
- 5xx 업무 예외와 처리하지 못한 예외는 ERROR다. 아래 "잠시 뒤 다시 보내면 되는 거절"은 5xx여도 예외다.
- **잠시 뒤 다시 보내면 되는 거절은 요청마다 남기지 않는다.** 회원별 요청 한도 초과(429 `TOO_MANY_REQUESTS`)와 업로드 자리 없음(503 `FILE_UPLOAD_BUSY`)은 남용·혼잡 때 한꺼번에 몰려, 요청마다 남기면 로그가 쏟아지고 혼잡이 풀리면 저절로 회복된다. 그래서 공통 `GlobalExceptionHandler`는 이 예외(강의 `RetryLater`, 지갑·auxiliary-app `RateLimitExceededException`)를 `Retry-After` 헤더와 함께 응답하고 DEBUG만 남긴다(4xx INFO·5xx ERROR 규칙의 예외). 거절은 지표(`lecture.rate_limit.rejected`·`hapbang.billing.rate.limit.rejected`·`hapbang.chatbot.rate.limit.rejected`, Envoy route별 5xx)와, 거절한 곳이 상태가 바뀔 때만 남기는 로그(상한에 닿기 시작할 때 WARN, 다시 받을 때 INFO)로 본다 (지갑 BD-140, 강의 D80·D81, 챗봇은 회원 키마다 `KeyedRateLimiter` #715). auxiliary-app은 외부 의존이 잠시 안 되는 503도 오류 코드에 `isRetryLater()`를 켜 같은 방식으로 처리한다(챗봇 `LECTURE_RECOMMEND_UNAVAILABLE`: 장애가 시작될 때 WARN, 회복될 때 INFO, 건수는 `http.server.requests`(status=503)·Envoy route별 5xx, #718).
- **쿠폰 선착순 매진도 요청마다 INFO로 남기지 않는다.** 매진(409 `COUPON_SOLD_OUT`)은 발급이 열리면 1초에 수백 건씩 몰리고, 줄마다 동기로 쓰면 로그 쓰기 lock에서 요청 스레드가 기다린다(로컬 측정, `docs/load-test/coupon-issue.md`). 그래서 쿠폰 모듈의 `CouponExceptionHandler`는 매진만 DEBUG로 남기고(4xx INFO 규칙의 예외), 건수는 지표 `coupon.issue.sold_out`(Counter)으로 본다. 그 밖의 쿠폰 4xx는 INFO다 (#826).
- **HP 작업 등록과 결정 로그는 커밋 뒤에 찍는다.** 작업과 결정은 업무 트랜잭션 안에서 저장되고, 같은 요청 ID 동시 등록이나 커밋 때의 충돌처럼 롤백될 수 있다. 롤백된 변경이 "~했다" 로그로 남지 않도록 `TransactionSynchronization.afterCommit`에서 찍는다(앱마다 `app/AfterCommit`). HP 작업 결과도 결과를 기록한 트랜잭션이 커밋된 뒤에 찍는다(#681).
- **결정 로그에는 결정한 회원 ID를 쓴다.** 수락·거절·취소·확인·검수처럼 회원이나 관리자가 돈 흐름이나 판매 상태를 바꾼 결정은 대상 ID와 결정한 회원 ID(`adminUserId`, `buyerUserId`, `hostId` 등 코드의 필드 이름)를 남긴다. 사유처럼 회원이 쓴 본문은 남기지 않는다.

## 민감 정보

다음 값은 **로그에 찍지 않는다.** 마스킹(앞 몇 글자만 남기기)은 지켜졌는지 리뷰하기 어려워 쓰지 않는다.

- access·refresh token, OAuth 인가 코드, 웹소켓 연결 티켓
- TossPayments 시크릿 키, `paymentKey`(시크릿 키와 함께 결제를 조회·취소할 수 있다)
- 계좌번호, 예금주
- 비밀번호, 내부 API 키(`X-Internal-Api-Key`)
- 요청·응답 본문 전체
- 회원 개인정보: 이메일, 이름, 닉네임, 채널 URL

다음 값은 남겨도 된다: 회원 ID, 엔티티 ID, Toss `orderId`, 금액, 오류 코드. 회원은 ID로만 가리킨다. 결제는 `orderId`로 추적한다.

검색어는 요청마다 남기지 않는다(남의 이름·닉네임·연락처일 수 있고, `userId`가 붙으면 개인 검색 기록이 된다). 예외로 auxiliary-app 검색은 사전·동의어를 늘릴 근거로 **결과가 0건이던 검색어를 아래 조건을 모두 지킬 때만** 모아 남긴다(#771, PR #828 리뷰). 한 사람의 입력이 로그에 나오지 않게 하는 조건이다. 자세한 것은 [검색 모듈](../search/module.md) 8장.

- 서로 다른 회원 2명 이상이 찾은 말만 남긴다. 회원 ID는 이 판단에만 메모리에서 쓰고 남기지 않는다.
- 이메일·URL·전화번호 모양(숫자 8개 이상)과 30자를 넘는 말은 세지 않는다. 필터 때문에 0건인 요청도 세지 않는다.
- 요청 스레드에서는 세기만 하고 배치 스레드가 주기마다 한 줄로 남긴다. 그래서 그 줄에는 검색한 회원의 `userId`·`traceId`가 붙지 않는다.

비밀값 전반의 금지 사항은 [보안](../deploy/security.md)의 "금지 사항"이다.

## 메시지 쓰는 법

- 한국어 평서문("~다")으로 쓰고, 식별자는 문장 뒤에 `필드이름=값`으로 쉼표로 이어 붙인다. 키 이름은 코드의 필드 이름(`orderItemId`, `exchangeId`)을 그대로 쓴다.

  ```java
  log.info("환전 지급을 마쳤다 exchangeId={}, amount={}", exchangeId, amount);
  log.warn("지급을 시작하지 못해 다음 실행에서 다시 고른다 orderItemId={}", itemId, exception);
  ```

- 피한다: 영어 접두어(`late result ignored:`), "~합니다"체, 키 없는 값(`대상 {}건`), 키 이름을 인자로 넘기기(`{}={}`), 구분자 섞기(`a={} b={}`, `: id={}`).
- SLF4J `addKeyValue`는 쓰지 않는다. JSON에는 필드로 들어가지만 로컬 평문 패턴에는 보이지 않는다. 여러 줄에 붙일 값은 MDC로 넣는다.
- **예외**는 마지막 인자로 넘겨 stack trace를 남긴다. 잡아서 다시 던질 때는 찍지 않고, 처리하는 곳에서 한 번만 찍는다.
- **alert 표식**: 사람이 바로 봐야 하는 ERROR는 메시지 앞에 `[대문자_코드]`를 붙이고(예: `[HP_TASK_DEAD]`, `[LECTURE_HP_RECONCILIATION_MISMATCH]`), alert 쿼리는 이 코드로 찾는다(`|= "[HP_TASK_DEAD]"`). 평문과 JSON에서 똑같이 찾을 수 있어 SLF4J Marker(`tags` 필드)는 쓰지 않는다. 표식은 ERROR에만 붙인다. WARN·INFO에 붙이면 alert 쿼리가 사람이 볼 필요 없는 줄까지 잡는다.
- 기존 로그 문은 한꺼번에 고치지 않고, 새로 쓰거나 고치는 로그 문부터 맞춘다. 강의 앱은 #666에서 모두 맞췄다.
