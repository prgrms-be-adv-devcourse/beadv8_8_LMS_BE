# GraalVM Native Image 시험 적용 (#609)

상위 문서: [최종 프로젝트 인프라 설계](../final-project-infrastructure.md). 자원 예산은 [k3s 3장](k3s.md#ec2-2노드-자원-예산)이다.

## 왜 시험하는가

JVM 앱 하나는 heap과 상관없이 280~355 MiB를 고정으로 쓴다(2026-11-03 운영 7일 실측). Metaspace 130~152 MiB(앱마다 클래스 2.6~3만 개), 코드 캐시·클래스 영역 40~60 MiB, thread stack·malloc 같은 native 100~145 MiB다. heap 사용은 82~154 MiB뿐이다. 두 node의 메모리가 거의 다 차 있어 새 앱(aux-app)을 띄울 자리가 없다.

Native Image는 앱을 빌드할 때 기계어로 컴파일하고 JVM 없이 실행한다. Metaspace와 JIT 코드 캐시가 없어지고 기동이 빠르다. 트래픽이 가장 적고(하루 몇 건) 외부 연동이 적은 customer-support-app 하나로 먼저 잰다. aux-app은 이 결과와 상관없이 JVM으로 띄운다(2026-11-03 결정).

## 구성

| 항목 | 내용 |
| --- | --- |
| GraalVM | Oracle GraalVM 25(CI는 `graalvm/setup-graalvm@v1`, `distribution: graalvm`). Serial GC |
| Gradle | `org.graalvm.buildtools.native` 1.1.8(Spring Boot 4.1.1 BOM과 같은 버전). customer-support-app에만 적용 |
| 실행 파일 | `./gradlew :customer-support-app:nativeCompile` → `apps/customer-support-app/build/native/nativeCompile/app` |
| 이미지 | `apps/customer-support-app/Dockerfile.native`(ubuntu 24.04 + 실행 파일). 러너(ubuntu-24.04)의 glibc에 동적 링크되므로 같은 배포판을 쓴다. JVM `Dockerfile`은 그대로 두어 CI 이미지 확인·로컬 k3d는 JVM 이미지를 쓴다 |
| CI | native 앱(`build.gradle.kts`에 플러그인이 있는 앱)은 JVM 테스트 뒤 `nativeCompile`과 native 이미지 빌드를 확인하고 실행 파일을 artifact(`app-native-<앱>`)로 올린다. publish는 이 실행 파일을 `Dockerfile.native`로 발행한다 |

### 빌드 설정에서 정해야 했던 것

- **AOT는 빈 조건을 빌드 때 정한다.** `@Profile`·`@ConditionalOnProperty`가 빌드 시점 값으로 굳는다. `processAot`에 운영 조건(`--spring.profiles.active=prod`, `--hapbang.internal-api.port=8081`, `--management.server.port=8082`)을 넘긴다. 넘기지 않으면 내부 API 포트 설정(`InternalApiPortConfig`)과 별도 actuator context가 빠진다. 값 자체는 실행 때 환경 변수로 다시 읽는다.
- **trace 전송도 빌드 때 조건이 굳는다** (#663). Boot의 OTLP span exporter는 `management.opentelemetry.tracing.export.otlp.endpoint`가 있을 때만 생긴다(`OtlpTracingConfigurations.ConnectionDetails`의 `@ConditionalOnProperty`). endpoint는 ConfigMap이 실행 때 넣으므로 빌드 때 비어 있어 exporter가 실행 파일에서 빠졌다. JVM 테스트는 통과하고 native는 span을 만들기만 하고 보내지 않았다. `processAot`에 운영 endpoint를 넘겨 고쳤다. 2026-11-04 로컬에서 임시 PostgreSQL과 Tempo(OTLP gRPC)에 붙여 확인했다: 샘플링된 `traceparent` 요청은 server span과 Security span 5개가 그 trace로 들어왔고, 샘플링되지 않은 요청과 관리 포트 요청(probe·Prometheus 6건)은 span을 보내지 않았다(Tempo 수신 span 5개). RSS는 기동 직후 220 MiB, 요청 뒤 227 MiB였다(Kafka 없이, 위 결과와 조건이 달라 직접 비교하지 않는다).
- **실행 파일로 만든다.** 루트가 모든 모듈에 `java-library`를 적용하면 Native Build Tools는 공유 라이브러리(`app.so`)를 기본값으로 만든다. `sharedLibrary = false`를 준다. `application` 플러그인이 없으므로 `mainClass`도 직접 넘긴다.

### 메타데이터를 더한 것

`apps/customer-support-app/src/main/resources/META-INF/native-image/com.hapbang/customer-support-app-hints/reachability-metadata.json`에 Spring Boot AOT가 등록하지 않는 것만 둔다. AOT가 `com.hapbang/customer-support-app`에 파일을 만들므로 경로를 나눈다(같은 경로면 `bootJar`가 중복으로 실패한다. 그 CI는 `bootJar`에서 멈춰 native 빌드에서도 한쪽이 가려지는지는 확인하지 않았다).

| 증상(native에서만) | 원인 | 더한 것 |
| --- | --- | --- |
| 기동 실패 `swagger.yml ... does not exist` | `spring.config.import`가 읽는 파일은 AOT가 resource로 넣지 않는다 | resources: `customer-support.yml`, `swagger.yml` |
| 모르는 타입·깨진 JSON 하나에 Kafka listener container가 멈춤(`SerializationConstructorAccessor class not found ... Throwable`) | `ErrorHandlingDeserializer`가 역직렬화 실패를 Java 직렬화로 헤더에 담는다 | serializable 33개. 모르는 타입, 깨진 JSON, 타입 불일치 세 경우를 tracing agent로 실행해 모은 목록 + `IllegalArgumentException`·`IllegalStateException` |
| 이의 제기 접수 500(`Record components not available ... LectureObjectionFileRequest`) | 강의 내부 API Client가 주고받는 record는 AOT가 모른다 | reflection: `LectureObjectionFileRequest`, `LectureObjectionFiledDto`, `LectureApiClient$Body` |
| (예방) | 고객센터가 발행하는 이벤트는 Modulith 발행 기록 JSON 저장·재발행, Kafka JSON 직렬화, `@Externalized` key의 SpEL이 reflection으로 읽는다 | reflection: `LectureObjectionResolutionRequestedEvent`, `LectureObjectionWithdrawalRequestedEvent` |
| `lecture.events.v1`의 받지 않는 타입(#712 동기화 이벤트 등)이 DLT로 감 | 모르는 타입은 AOT가 등록하지 않아 `__TypeId__`로 클래스를 찾지 못한다. #712에서 4종과 DTO를 손으로 등록했다 | 등록 대신 `eventType` 헤더로 읽을 타입을 정한다(#722, [Kafka "소비"](kafka.md#소비)). reflection: `LectureEventTypeResolver.resolve`(역직렬화기가 이름으로 부른다). #712의 손 등록 10개는 지웠다 |

**새 코드를 더할 때.** 앱 밖(`common/shared`)의 record를 JSON으로 주고받거나, 새 이벤트를 발행하거나, 설정 파일을 `spring.config.import`로 더하면 이 파일에도 더한다. JVM 테스트는 이것을 잡지 못한다. 빠진 것은 native 실행 파일로 아래 확인을 돌려야 드러난다. 강의 이벤트를 새로 받을 때는 `@KafkaHandler`와 `LectureEventTypeResolver` 표에 더한다. 처리기 파라미터는 AOT가 등록하므로 이 파일은 고치지 않는다. 강의가 받지 않는 타입을 더할 때는 아무것도 더하지 않는다.

## 로컬 확인 방법

```bash
mise install java@oracle-graalvm-25.0.5
export GRAALVM_HOME=~/.local/share/mise/installs/java/oracle-graalvm-25.0.5/Contents/Home
./gradlew :customer-support-app:nativeCompile   # macOS arm64에서 약 4분
```

빠진 메타데이터를 찾을 때는 JVM을 AOT 모드와 tracing agent로 실행하고 기능을 지나가게 한다.

```bash
$GRAALVM_HOME/bin/java -agentlib:native-image-agent=config-output-dir=agent-out \
  -Dspring.aot.enabled=true -jar apps/customer-support-app/build/libs/app.jar
```

agent 결과에는 AOT가 이미 등록한 것과 Hibernate 같은 라이브러리가 존재만 확인하는 조회가 섞여 있다. 실패한 경로에 필요한 것만 골라 hints 파일에 옮긴다.

### 모르는 이벤트 타입 확인 (#722)

강의가 `lecture.events.v1`에 고객센터가 받지 않는 새 타입을 더해도 DLT로 가지 않는지 native 실행 파일로 확인한다. JVM 테스트(`CustomerSupportObjectionKafkaTest`의 `클래스가_없는_모르는_타입은_DLT로_보내지_않고_다음_결과를_처리한다`)는 클래스가 없는 타입으로 같은 경로를 재현하지만, reflection 등록 누락은 native에서만 드러난다.

1. 임시 PostgreSQL·Redis·Kafka를 기본과 다른 port로 띄운다(예: 55432·56379·59092). Kafka는 운영처럼 토픽 자동 생성을 끄고, 컨테이너 안에서 쓸 listener(예: `INTERNAL://localhost:29092`)를 따로 둔다. `lecture.events.v1`을 partition 3개로 만든다. DLT는 앱이 만든다.
2. 실행 파일을 붙인다. 실행 파일은 빌드 때 prod 조건으로 굳어 있으므로 운영 ConfigMap·Secret의 키를 환경 변수로 준다.

   ```bash
   env SPRING_PROFILES_ACTIVE=prod SERVER_PORT=58080 HAPBANG_INTERNAL_API_PORT=58081 MANAGEMENT_SERVER_PORT=58082 \
     DB_URL=jdbc:postgresql://localhost:55432/customersupport DB_USERNAME=customersupport DB_PASSWORD=<임시 값> \
     SPRING_KAFKA_BOOTSTRAP_SERVERS=localhost:59092 SPRING_DATA_REDIS_HOST=localhost SPRING_DATA_REDIS_PORT=56379 \
     LECTURE_API_BASE_URL=http://localhost:58099 HAPBANG_INTERNAL_API_KEY=<임시 값> \
     MANAGEMENT_TRACING_SAMPLING_PROBABILITY=0 LOGGING_LEVEL_COM_HAPBANG_CUSTOMERSUPPORT_IN=DEBUG \
     apps/customer-support-app/build/native/nativeCompile/app
   ```

3. 같은 key로 레코드를 보낸다. 형식은 `헤더<탭>key<탭>값`이다([DLT 재처리 runbook](kafka.md#dlt-재처리-runbook)과 같은 console producer 옵션).
   - 클래스가 없는 타입: `__TypeId__:com.hapbang.shared.lecture.event.NotYetKnownEvent,eventType:NotYetKnownEvent`
   - 받지 않는 기존 타입: `__TypeId__:...LectureChangedEvent,eventType:LectureChangedEvent`
   - 받는 타입(없는 문의 ID): `__TypeId__:...LectureObjectionResolvedEvent,eventType:LectureObjectionResolvedEvent`
   - 깨진 JSON(받는 타입 헤더, 다른 key)
4. 기대 결과는 다음과 같다.
   - 앞의 둘은 DEBUG "고객센터가 쓰지 않는 강의 이벤트라 무시한다"(MDC `eventType`)를 남긴다.
   - 받는 타입은 ERROR "강의 결과 이벤트에 맞는 이의 제기 문의가 없어 무시한다"를 남긴다. 그 클래스로 읽었다는 뜻이다.
   - DLT(`lecture.events.v1.customer-support-app.objection-result.DLT`)에는 깨진 JSON 1건만 있다.
   - `kafka-consumer-groups.sh --describe --group customer-support-app.objection-result`의 LAG는 0이다. 깨진 JSON 뒤에 보낸 레코드도 처리된다.

2026-11-06 macOS arm64에서 위 결과대로 나왔다(기동 0.54초, ERROR는 받는 타입의 1줄뿐).

## 결과

### 로컬 (2026-11-03~04)

임시 PostgreSQL·Redis·Kafka에 붙여 같은 작업을 돌렸다. HTTP(문의 등록·조회·답변·관리자 목록, 403·401·400, 내부 포트 404, actuator·Prometheus), Kafka 받기(강의 이벤트 처리, 모르는 타입·깨진 JSON·타입 불일치 메시지 3건 DLT, listener 계속 동작), 이의 제기 흐름(강의 API stub 접수, 재시도 batch, 판단·철회 이벤트를 key `inquiry:N`으로 `customersupport.events.v1`에 발행, 발행 기록 완료), 목록 조회 300건이다. native에서 위 문제를 고친 뒤 모두 통과했다.

| 조건 | 지표 | JVM | native |
| --- | --- | ---: | ---: |
| macOS arm64. JVM은 `-XX:+UseSerialGC -XX:ActiveProcessorCount=2 -XX:MaxRAM=448m -XX:MaxRAMPercentage=60`(운영과 같은 Serial GC·CPU 2개), native는 `-XX:ActiveProcessorCount=2` | 기동 | 4.3초 | 0.57초 |
| 〃 | RSS 기동 20초 뒤 / 작업 직후 / 60초 쉰 뒤 | 379 / 413 / 415 Mi | 208 / 231 / 233 Mi |
| macOS arm64, JVM은 heap 상한만 맞춤(GC·CPU는 로컬 기본값) | RSS 같은 시점 | 390 / 419 / 415 Mi | 210 / 236 / 208 Mi |
| linux/amd64 컨테이너(memory 448m, Rosetta 번역) | working set 기동 20초 뒤 / 작업 직후 / 60초 쉰 뒤 | 446 / 444 / 447 Mi | 221 / 264 / 264 Mi |

- 같은 조건(Serial GC·CPU 2개)에서 native가 메모리를 약 44~45% 덜 쓴다. 처음에는 heap 상한만 맞춰 쟀는데, 로컬 JVM이 CPU가 많아 G1을 골랐을 수 있다는 리뷰를 받고 GC·CPU까지 맞춰 다시 쟀다(로그 `Using Serial` 확인). 결과는 크게 다르지 않았다.
- linux/amd64 컨테이너 값은 macOS가 amd64를 Rosetta로 번역해 돌린 값이다. JVM은 기동이 38초로 늘고 limit에 붙었다. 두 방식 모두 번역을 거친 비교라 절대값은 운영 측정으로 정한다.
- 운영 JVM 기준선: limit 448 MiB, 7일 최대 working set 404 MiB, 평소 380~390 MiB.

### 운영 (2026-11-04)

| 지표 | JVM | native |
| --- | ---: | ---: |
| working set | 391 MiB(배포 직전, 하루 넘게 돈 pod), 7일 최대 404 MiB | 배포 1~12분 뒤 120~129 MiB, 5시간 최대 142·174 MiB(native pod 2개, page cache 포함) |
| RSS | - | 5시간 최대 110·111 MiB |
| heap 사용 | 7일 최대 82 MiB | 5시간 최대 82~84 MiB(committed 약 82 MiB) |
| 기동(`application_ready_time_seconds`) | 최근 3일 JVM pod 22.9~27.8초 | 1.10~1.16초 |
| 압축 이미지(amd64) | 204.5 MiB(`sha-265cee7b`) | 144.3 MiB(`sha-821abc2d`) |

- 실제 요청(2026-11-04 05:25~05:26 UTC, 프론트): 문의 등록 10건 201(5~60 ms), 목록 11건 200(첫 요청 401 ms, 이후 4~15 ms), 앱 로그 오류 0. 이의 제기(강의 연동)와 이벤트 발행은 운영에서 아직 돌지 않았다(로컬에서 같은 linux 실행 파일로 확인).
- heap은 JVM과 거의 같다. 줄어든 것은 heap 밖 메모리(Metaspace·JIT 코드 캐시·native)다.
- JVM 값은 오래 돈 pod, native 값은 막 뜬 pod라 측정 시점이 다르다. 같은 조건 비교는 위 로컬 표다.

### memory limit과 heap 상한 (#672)

운영 request·limit을 448 MiB에서 256 MiB로 낮추고 실행 인자 `-Xmx128m`을 준다(prod overlay만. base는 로컬 k3d의 JVM 이미지용이라 448 MiB와 `JAVA_TOOL_OPTIONS`를 그대로 둔다).

- **native는 `JAVA_TOOL_OPTIONS`를 읽지 않는다.** 로컬에서 `JAVA_TOOL_OPTIONS=-Xmx24m`은 GC를 일으키지 않았고, 인자 `-Xmx24m`은 기동 0.07초 안에 GC 10회를 일으켰다(`-XX:+PrintGC`). 운영 ConfigMap의 `-XX:MaxRAMPercentage=60`은 native에 효과가 없다.
- **heap 상한 기본값은 메모리의 80%다**(빌드 출력 `Serial GC (max heap size: 80% of RAM)`). 256 MiB면 약 205 MiB라 heap 밖을 더하면 limit을 넘을 수 있어 `-Xmx`를 명시한다. 128 MiB는 관측 최대 84 MiB의 약 1.5배다.
- 256 MiB 안의 구성: heap 상한 128 + heap 밖 약 30(RSS 111 − heap 82) + page cache 최대 69(회수 가능) ≈ 227 MiB.
- medium node 스케줄러 요청 합계가 3,712 MiB(96%)에서 3,520 MiB(92%)가 된다.
- 적용 뒤 1시간 15분(2026-11-04 06:54~08:09 UTC): 재시작 0, heap 최대 48.5 MiB(상한 128 MiB의 38%), working set 최대 108 MiB(limit의 42%), RSS 최대 78 MiB, `UnsupportedFeatureError`·`OutOfMemoryError` 0. 같은 시간 운영 요청: 문의 목록 200 ×108, 등록 201 ×16·409 ×86(`OBJECTION_ALREADY_FILED`, 같은 주문 항목 반복 시험), 강의 내부 API 이의 제기 접수 200 ×3·409 ×86. native에서 고친 강의 Client record 직렬화가 운영에서도 동작했다. 이의 제기 판단·철회 이벤트 발행과 강의 결과 이벤트 받기는 아직 운영에서 돌지 않았다.

## 비용과 한계

- **빌드 시간**: CI의 native 컴파일은 11~14분이다(GitHub 러너 4 CPU, 최대 메모리 약 12 GB). customer-support-app이 바뀐 PR과 dev push마다 이만큼 늘어난다. 로컬 macOS(arm64)는 약 4분이다.
- **native 빌드가 실패하면** 그 push의 배포 전체가 멈춘다. publish의 배포는 모든 앱의 빌드가 성공해야 돈다. 그래서 PR CI에서 native 빌드를 먼저 확인한다.
- **실행 파일 크기**: 약 311 MB(코드 153 MB, image heap 144 MB). 쓰지 않는 AWT(`java.desktop`)도 딸려 들어온다.
- **실행 중 최적화(JIT)가 없다.** 최고 처리량이 JVM보다 낮을 수 있다. 지금 트래픽에서는 차이가 드러나지 않는다.
- **지표가 달라진다.** Metaspace·코드 캐시·GC 종류 같은 JVM 지표가 바뀌어 JVM dashboard 일부 패널이 비거나 의미가 달라진다.
- **JFR 지원이 일부다.** Phase 9의 JFR·JMC 분석은 JVM 앱으로 한다.
- **CPU 대상**: x86-64-v3(AVX2 이상)로 컴파일된다. EC2 t3(Intel Xeon)는 지원한다.

## 결정 (2026-11-04, #609)

- **customer-support-app만 native로 유지하고, 다른 앱으로 넓히는 것은 보류한다.** JVM 앱 튜닝(#610) 결과를 본 뒤 다시 정한다. 넓히면 앱마다 CI가 native 컴파일 10~14분만큼 늘고, 위 메타데이터 확인(smoke 실행과 tracing agent)을 앱마다 다시 해야 한다.
- 새로 만드는 aux-app(검색·알림·AI)은 JVM으로 띄운다(#193·#234·#345).
- 되돌리지 않는다. 같은 조건에서 메모리 44~45%, 운영 기동 23~28초 → 1.1초, medium 요청 192 MiB를 얻었고, 운영에서 확인한 범위에서 native 전용 오류가 없다.

### 운영 이벤트 흐름과 하루 관찰 (2026-11-04~06)

- **이벤트 흐름**: 2026-11-04 08:24 UTC 관리자 이의 제기 판단(문의 24·12, 202)에서 고객센터가 `customersupport.events.v1`에 2건 발행(`spring_kafka_template_seconds` `error=none`) → lecture-app `lecture-app.objection-command` 그룹이 받아 처리(`error=none`) → 고객센터가 `lecture.events.v1`에서 결과 2건 처리(`spring_kafka_listener_seconds` `error=none`). 이것으로 HTTP, 강의 내부 API 호출, Kafka 발행·받기를 모두 운영에서 확인했다.
- **JSON 로그와 trace**(#699): 로그를 JSON으로 바꾼 뒤(#695) 2026-11-06 03:46 UTC 관리자 이의 제기 판단(문의 114) 한 건이 Envoy → 고객센터 HTTP → 고객센터 발행 → lecture-app 받기·발행 → 고객센터 받기까지 한 trace(span 12개)로 Tempo에 남았다. 커밋 뒤 발행(`scheduling-N` 스레드)의 로그에도 요청의 `traceId`가 찍혀, native에서도 비동기 발행으로 trace가 넘어간다. 고객센터 로그는 모두 JSON이었고 받는 로그에 `traceId`·`eventType`·`eventKey`가 붙었다. ERROR·`UnsupportedFeatureError`는 없었다.
- **256 MiB·`-Xmx128m` pod 약 17시간**(2026-11-04 06:54 ~ 11-05 00:08 UTC): 재시작 0, working set 최대 123 MiB(limit의 48%), RSS 최대 80 MiB, heap 최대 52 MiB(상한의 41%).
- **로그(Loki, 전환 뒤 30시간)**: `UnsupportedFeatureError`, listener 정지, DLT, `OutOfMemoryError`, ERROR 0건. WARN은 기동 때의 Flyway 스캔·GC 알림 미지원 같은 예상된 로그뿐이다.
- Loki로 본 기동 시간: JVM 24.8~27.7초(2026-11-03), native 1.10~1.16초.

## 남은 일

- JVM 앱 튜닝(#610)은 나중에 한다.
- 자리가 더 필요하면 limit을 192 MiB까지 낮출 수 있다([k3s 예산](k3s.md)의 줄일 수 있는 후보).
