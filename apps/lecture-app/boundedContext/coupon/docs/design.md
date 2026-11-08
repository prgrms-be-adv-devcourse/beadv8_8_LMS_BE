# 구조와 처리 흐름

## 모듈 경계

쿠폰은 lecture-app pod 안에서 강의와 함께 실행되지만, **JVM이 나뉘어 있다고 가정하고 설계한다.** 강의 ↔ 쿠폰 통신은 다른 앱 사이와 똑같이 **내부 HTTP API(동기)와 Kafka(비동기)**만 쓴다. 같은 JVM이라는 이유로 Java 인터페이스 직접 호출, Modulith 인프로세스 이벤트(`@ApplicationModuleListener`), 같은 트랜잭션 참여를 하지 않는다. 그래서 트랜잭션은 구조적으로 연결될 수 없고, 나중에 쿠폰을 별도 pod로 빼도 설정(URL)만 바뀐다.

### Gradle 구조

`:lecture-app`은 그대로 두고 그 안에 쿠폰 Gradle 하위 프로젝트를 추가한다. 별도 global 프로젝트는 만들지 않는다. 하위 프로젝트 선례는 auxiliary-app의 `boundedContext/<모듈>`(#714)이다.

| Gradle 프로젝트 | 경로 | 역할 | 의존 |
| --- | --- | --- | --- |
| `:lecture-app` | `apps/lecture-app` | 실행 진입점 `LectureApplication`, `application.yaml`, Dockerfile, Flyway, **기존 강의 모듈 코드(`com.hapbang.lecture`) 그대로** | `:shared`, `runtimeOnly(project(":coupon"))` |
| `:coupon` | `apps/lecture-app/boundedContext/coupon` | 쿠폰 모듈(`com.hapbang.coupon`) | `:shared`와, 쿠폰 코드가 컴파일에 필요한 것을 `:coupon/build.gradle.kts`에 직접 선언: `:kafka`, `:redis`(패키지 `com.hapbang.common`), data-jpa, webmvc, validation, security-core 등. 루트 Gradle의 공통 적용은 `-app` 프로젝트에만 걸리므로 하위 프로젝트는 스스로 선언한다 |

- `:lecture-app` → `:coupon`은 **`runtimeOnly`**다. 같은 jar·같은 JVM에 실리되 강의 코드가 쿠폰 클래스를 컴파일 시점에 볼 수 없다. 쿠폰은 강의를 전혀 의존하지 않는다.
- 두 모듈이 주고받는 **HTTP DTO·Kafka 이벤트 schema는 `common/shared`**에 둔다. 팀 규칙(shared에는 DTO·이벤트·Enum·오류 코드·공통 HTTP Client만)과 같고, 다른 앱 사이와 같은 방식이다. 이벤트 소유권 문제(순환)는 생기지 않는다.
- 쿠폰이 필요로 하는 앱 공통 코드(인증 principal 읽기, 오류 응답, 배치 켜기 애노테이션)는 `:shared`에 있는 것을 쓰고, 없으면 `com.hapbang.coupon.global`에 자기 것을 둔다. `com.hapbang.lecture.global`을 가져다 쓰지 않는다. 그래서 쿠폰은 루트 AGENTS.md 7장("모듈은 `@RestControllerAdvice`를 만들지 않는다")의 **예외**다: 앱으로 분리될 것을 전제하므로 `com.hapbang.coupon` 패키지에 한정한 자기 예외 처리기·RsData·로그인 해석을 둔다. 루트 AGENTS.md 7장에 이 예외를 적는다.
- 다른 앱을 부르는 HTTP 클라이언트(강의 조회, billing)는 루트 AGENTS.md 2장대로 Resilience4j circuit breaker와 timeout을 붙인다(billing의 `OutboundCircuitBreakers` 패턴). 열리면 포트의 Unavailable 예외 → 503.
- 하위 프로젝트 이름에 `-app`을 붙이지 않는다(`verifyModuleStructure`가 `-app`을 실행 앱으로 센다). `settings.gradle.kts`에 auxiliary와 같은 방식으로 등록하고, `verifyModuleStructure`가 `:lecture-app`의 하위 프로젝트 의존을 허용하도록 규칙을 확인한다.

### 프로젝트 구조

기존 lecture-app의 패키지 구조(`app`·`config`·`domain`·`global`·`in`·`out`)를 쿠폰에도 그대로 쓴다.

```
hapbang-backend/
├─ settings.gradle.kts                       # include(":coupon"), projectDir = apps/lecture-app/boundedContext/coupon
├─ common/shared/src/main/java/com/hapbang/shared/
│  └─ coupon/
│     ├─ api/                                # 내부 HTTP DTO (Calculate·Reserve·Release·ReservationStatus·PriceRaiseBlocked)
│     └─ event/                              # Kafka 이벤트 schema (OrderItemPaid, OrderItemCancelCompleted, CouponIssueRequested)
└─ apps/lecture-app/                         # Gradle :lecture-app, 배포 단위(pod 하나), 그대로 유지
   ├─ build.gradle.kts                       # + runtimeOnly(project(":coupon"))
   ├─ Dockerfile                             # 변경 없음
   ├─ src/main/java/com/hapbang/lecture/     # 기존 강의 코드 그대로
   │  ├─ LectureApplication.java             # scan 범위에 com.hapbang.coupon 추가
   │  ├─ app/ config/ domain/ global/ in/
   │  └─ out/coupon/CouponClient.java       # 쿠폰 내부 API를 부르는 @HttpExchange 클라이언트
   ├─ src/main/resources/
   │  ├─ application.yaml                    # flyway.schemas += coupon, locations += db/migration/coupon, hapbang.coupon.base-url
   │  └─ db/migration/                       # 강의 마이그레이션 그대로
   ├─ src/test/java/com/hapbang/lecture/ModulithStructureTest.java   # of("com.hapbang", 공통 라이브러리 제외) → {lecture, coupon} 단언 + verify() + 깨 보기 fixture
   └─ boundedContext/
      └─ coupon/                             # Gradle :coupon
         ├─ build.gradle.kts                 # implementation(project(":shared")), testImplementation(project(":test-support"))
         ├─ src/main/java/com/hapbang/coupon/
         │  ├─ package-info.java             # @ApplicationModule(displayName = "Coupon", allowedDependencies = {})
         │  ├─ domain/                       # Coupon, MemberCoupon, CouponUsage, CouponIssueRequest, 상태·금액 규칙
         │  ├─ app/                          # 계산·예약·해제·발급·복원 UseCase, 만료·대사·재발행 배치
         │  ├─ config/                       # Redis·Kafka·Flyway 위치·HTTP 클라이언트 설정
         │  ├─ global/                       # 쿠폰 전용 공통 (오류 응답, 배치 켜기 애노테이션, 인증 principal 읽기)
         │  ├─ in/                           # 들어오는 것: 컨트롤러·리스너 (lecture-app 규칙)
         │  │  ├─ ApiV1CouponController.java / …Spec.java          # /api/v1/coupons/** 회원
         │  │  ├─ ApiV1CouponSellerController.java                 # 판매자 신청·중단
         │  │  ├─ ApiV1CouponAdminController.java                  # 승인·발행·중단·종료·DLT
         │  │  ├─ ApiV1CouponInternalController.java               # /api/v1/internal/coupons/** (강의가 호출)
         │  │  ├─ dto/
         │  │  └─ event/                     # Kafka 소비자: OrderItemPaid·OrderItemCancelCompleted(lecture.events.v1)·CouponIssueRequested(coupon.events.v1)
         │  └─ out/                          # 나가는 것: Repository·클라이언트
         │     ├─ CouponRepository.java, MemberCouponRepository.java, … (@Table(schema = "coupon"))
         │     ├─ redis/                     # CouponRedisKeys, Lua 실행, requests HSCAN(재발행)
         │     ├─ kafka/                     # coupon.events.v1 CouponIssueRequested 발행 (접수 직후 앱이 전송, 재발행 배치가 재사용)
         │     └─ billing/                   # billing 내부 API를 부르는 @HttpExchange 클라이언트 (계약은 사용자 담당)
         ├─ src/main/resources/
         │  ├─ lua/                          # issue.lua, compensate.lua, cache-single-flight-swr.lua, release-lock.lua
         │  └─ db/migration/coupon/          # V..._create_coupon_tables.sql (coupon. 접두사 명시)
         └─ src/test/java/com/hapbang/coupon/
```

- `:coupon`에는 실행 클래스·`application.yaml`·Dockerfile이 없다. 실행 설정은 전부 `:lecture-app`이 가진다.
- 강의 쪽에 새로 생기는 코드는 다섯 곳이다: 강의 조회 내부 API(`in/ApiV1Lecture…InternalController`, 읽기 전용, 쿠폰이 호출), `out/coupon/` 클라이언트, 주문 1~3단계 분리(`OrderCreateUseCase` 수정), 가격 수정 시 `priceRaiseBlocked` 호출, 결제·취소 완료 이벤트 externalize.

### 실행·배포

- 배포 단위는 지금처럼 **lecture-app pod 하나**다. 이미지·Dockerfile·k8s 매니페스트는 그대로이고 jar 안에 강의와 쿠폰이 함께 들어간다.
- `LectureApplication`의 scan 범위에 `com.hapbang.coupon`을 추가해 쿠폰 빈·`@KafkaListener`·배치가 같은 컨텍스트에서 뜬다. 설정은 `application.yaml`의 `hapbang.coupon.*`(코드 기본값 우선, Spring·라이브러리 설정만 yaml)에 둔다.
- 쿠폰 내부 API의 base URL은 `hapbang.coupon.base-url`이며 기본값은 같은 서버(`http://localhost:<server.port>`)다. 쿠폰을 별도 pod로 빼면 이 값만 바뀐다.
- pod 메모리(704Mi) 안에서 쿠폰 모듈·Kafka 생산자·소비자가 더 쓰는 heap을 측정한다(운영 문서).

### Spring Modulith

- `com.hapbang.lecture`(기존 `@ApplicationModule(displayName = "Lecture")`)와 `com.hapbang.coupon`(`@ApplicationModule(displayName = "Coupon")`)을 모듈로 선언하고 두 모듈 사이에 **허용 의존이 없음**(`allowedDependencies = {}`)을 둔다. Gradle `runtimeOnly`가 컴파일에서, Modulith가 패키지에서 경계를 지킨다.
- **검증 테스트(`ModulithStructureTest`, `:lecture-app`):** `ApplicationModules.of(LectureApplication.class)`는 모듈을 0개로 보고 아무것도 검사하지 않는다(실험으로 확인): lecture-app은 `spring.modulith.detection-strategy: explicitly-annotated`라 루트의 **하위 패키지** 중 `@ApplicationModule`이 붙은 것만 모듈로 세는데, 루트 `com.hapbang.lecture` 자체의 애노테이션은 세지 않고 `com.hapbang.coupon`은 루트 밖이다. `@Modulithic(additionalPackages)`도 0개였다. 그래서 루트를 `com.hapbang`으로 주고 공통 라이브러리를 제외한다: `ApplicationModules.of("com.hapbang", resideOutsideOfPackages("com.hapbang.shared..", "com.hapbang.kafka..", "com.hapbang.redis..", "com.hapbang.testsupport.."))`. 테스트는 모듈 집합이 정확히 `{lecture, coupon}`임을 먼저 단언한 뒤 `verify()`를 부른다. `@Modulithic(additionalPackages = …)`는 각 루트의 하위 패키지(app·domain…)를 모듈로 잡아 "두 모듈" 설계와 맞지 않으므로 쓰지 않는다.
- **깨 보기 테스트:** 실제 코드는 Gradle이 컴파일에서 막아 위반을 만들 수 없으므로, auxiliary-app의 `AuxiliaryModularityTest`처럼 테스트 전용 fixture 모듈 두 개가 서로 참조하는 경우에 `verify()`가 실패하는 것을 함께 검증한다. 검사가 실제로 작동함을 증명하는 용도다. fixture는 `com.hapbang` 밖(최상위 `modulithfixture` 패키지)에 둔다. `com.hapbang` 아래에 `@Modulithic` 클래스가 있으면 그것이 bootstrap으로 잡혀 루트가 바뀐다.
- Flyway는 한 인스턴스다. 기본 위치 `classpath:db/migration`이 하위 디렉터리를 재귀로 읽으므로 `:coupon` jar의 `db/migration/coupon`은 위치를 따로 더하지 않아도 적용된다(더하면 두 번 읽힌다). 두 모듈이 `lecture.flyway_schema_history` 하나를 쓰므로 버전 타임스탬프는 모듈 간에 겹치지 않게 한다.
- Modulith는 **이벤트 externalization**에 쓴다. 강의는 `@Externalized` 이벤트를 `event_publication`에 커밋과 함께 기록하고 `spring-modulith-events-kafka`가 Kafka로 내보낸다(다른 앱들과 같은 방식). 쿠폰은 `@KafkaListener`로 받는다. 인프로세스 `@ApplicationModuleListener`·`@EventListener`로 두 모듈을 잇지 않는다.
- `event_publication` 테이블은 앱당 하나라 두 모듈이 lecture-app의 같은 테이블을 쓴다. 스키마 분리 대상이 아니다. 발급용 신규 OUTBOX 금지는 이 전달 기록을 없애라는 뜻이 아니다.

### 통신 계약

| 방향 | 수단 | 내용 |
| --- | --- | --- |
| 강의 → 쿠폰 (동기) | 내부 HTTP API, 팀 공통 HTTP Client(`@HttpExchange`) | calculate, reserve, release, reservationStatus, priceRaiseBlocked. `docs/api/internal-coupon.md`에 계약을 둔다 |
| 강의 → 쿠폰 (비동기) | Kafka | `OrderItemPaid`, `OrderItemCancelCompleted`. 강의가 externalize, 쿠폰이 소비. 토픽 이름은 팀 규칙(`*.events.v1`) |
| 쿠폰 작업자 → 쿠폰 소비자 | Kafka | `coupon.events.v1`의 `CouponIssueRequested`(발급 흐름) |
| 쿠폰 → 강의 (동기, 조회만) | 내부 HTTP API | 강의 조회 `GET /api/v1/internal/lectures/{lectureId}`(가격, 판매자 ID, 판매 상태). 발행·승인 시 예산 계산(LECTURE 대상 가격 × 할인율 × 수량)과 판매자 소유 확인, 계산 API의 가격 재검증에 쓴다. 기존 내부 API에 같은 정보가 있으면 그것을 쓴다. 쿠폰 → 강의 이벤트는 없다 |
| 쿠폰 → billing | 내부 HTTP API | 예산 예약·해제·조회(계약은 사용자 담당) |

- HTTP 호출은 호출자 트랜잭션 밖에서 한다. 강의는 `@Transactional` 메서드 안에서 쿠폰 API를 부르지 않는다(계산·주문 1~3단계 분리). 통신 실패를 성공으로 간주하거나 무조건 재시도하지 않고, `workId`·`reservationStatus`로 결과 불명을 복구한다.
- Kafka 소비는 `processed_operation`(이벤트 ID UNIQUE)으로 멱등 처리한다. 순서는 key(주문 항목 ID 또는 쿠폰 ID)로 파티션을 고정해 지킨다.

### 스키마·마이그레이션

- JPA 엔티티는 `@Table(schema = "coupon")`로 스키마를 명시한다. Flyway는 한 인스턴스이고 `schemas: [lecture, coupon]`, `default-schema: lecture`다. 쿠폰 마이그레이션은 `:coupon`의 `db/migration/coupon`에 두며(기본 위치가 재귀로 읽는다) SQL은 `coupon.` 접두사를 명시한다.
- 다른 스키마를 직접 읽지 않는다(JOIN·FK·Repository 접근 금지). 강의 상태 확인은 제공 계약을 통해 수행한다.
- 통합 테스트는 한 모듈 롤백이 다른 모듈 커밋을 되돌리지 않음을 확인한다(운영 문서 "경계" 시나리오). HTTP·Kafka만 쓰므로 구조상 묶일 수 없지만, 실수로 Java 직접 호출이 들어오면 Modulith `verify()`가 잡는다.

## 발행과 플랫폼 예산

1. 판매자는 판매자 쿠폰만 신청하고 관리자 심사를 거친다. 플랫폼 쿠폰은 관리자가 발행한다. 신청자와 유형은 따로 기록한다. 특정 강의 대상은 강의 조회 내부 API로 판매 상태를 확인해 판매 중(ON_SALE)인 강의만 신청·발행·승인할 수 있다.
2. 특정 강의 가격·할인율·수량으로 예산을 산출한다. 10,000 HP × 10% × 100장 = 100,000 HP다. 정액은 강의 가격 범위에서 장당 할인액 × 수량이다.
3. 플랫폼 쿠폰이면 플랫폼이 billing 프로모션 지갑에 재원을 충전하고 해당 쿠폰 예산으로 예약한다. 충전과 예약은 다른 작업이며 각각 원장·멱등 ID·결과를 남긴다. 판매자가 대신 충전하지 않는다.
4. 예산 확보와 Redis 초기화를 확인한 뒤 발급을 연다. 중간 실패(응답 불명)는 동일 작업으로 조회·재시도하며 준비 전에는 열지 않는다. billing이 예약을 거절(FAILED)한 뒤 관리자가 다시 열면 attempt 번호를 붙인 새 작업 ID로 재예약하고 이전 작업 이력은 남긴다.
5. **DB와 Redis 상태의 순서·fence:** Redis 초기화는 "준비됨·접수 차단" 상태로만 하고, DB가 OPEN으로 커밋된 **뒤에** Redis를 접수 허용으로 바꾼다(응답 유실로 DB는 REVIEW인데 Redis만 열리는 것을 막는다). `issue-control`에 fence(쿠폰 DB version)를 두고 Redis 상태 쓰기마다 그 커밋의 version을 넘긴다. Lua는 열기는 fence보다 클 때만, 막기는 같거나 클 때만 반영해 늦게 도착한 옛 쓰기(늦은 resume, 늦은 suspend)를 무시한다. 이미 OPEN인 쿠폰의 open·approve·resume 재호출은 Redis 열기만 다시 하는 멱등 성공이다.

일반적인 '정률 최대 3,000 HP'와 같은 별도 상한은 특정 강의 쿠폰에서는 필수가 아니다. 대신 **가격 인상 제한**이 예산을 지킨다: 강의 모듈의 가격 수정은 쿠폰 모듈의 `priceRaiseBlocked(lectureId)`를 호출해 특정 강의 대상 플랫폼 쿠폰이 `OPEN`이거나 미사용 발급 쿠폰이 남아 있으면 인상을 거부한다. 인하는 허용한다.

**모든 강의 중 하나 대상 정률 쿠폰**은 대상 가격이 제각각이고 가격 인상 제한도 걸 수 없으므로, 관리자가 **장당 최대 할인액**을 입력한다. 예산 = 수량 × 최대 할인액, 할인 = min(할인율 × 적용 직전 남은 금액, 최대 할인액). 정액 쿠폰은 할인액 자체가 상한이라 입력이 없다. 특정 강의 정률 쿠폰에는 이 입력이 없다.

**예산 해제 규칙:** 최대 할인액 `M`(특정 강의는 가격 × 할인율), 실제 사용액 `F`일 때 (1) 발급되지 않은 수량의 `M`은 발급 종료(`CLOSED`)와 진행 중 발급 정리 뒤 해제, (2) 발급됐지만 사용 안 한 쿠폰의 `M`은 `expiresAt` 뒤 해제, (3) 사용한 쿠폰의 `M − F`는 `expiresAt` 뒤 해제, (4) 보류 중인 `F`는 기한과 무관하게 지급 또는 반환으로만 끝난다. 복원 가능 기간 = 그 발급 쿠폰의 `expiresAt`까지다(복원은 `expiresAt > now`일 때만 하므로). 처리 중 작업이 있으면 그 작업이 끝난 뒤 해제한다.

쿠폰 사용 잔액은 없다. 단, 중첩·정액 제한 때문에 실제 플랫폼 부담이 예상보다 작아질 수 있으므로 billing은 실제 사용액과 예약 재원을 별도로 추적해야 한다. 이 차액을 회원이 재사용할 쿠폰 잔액으로 표시하지 않는다. 복원 가능 쿠폰의 재사용을 보장할 예산을 유지하고, 만료·종료 시 더 이상 필요 없는 재원을 해제한다. 구체 필요액은 확정된 가격 기준으로 계산하며 임의 최대 할인 정책으로 대체하지 않는다.

## 발급

1. 인증·권한·형식을 확인하고 안정적인 issueRequestId를 받는다. **접수 경로는 DB를 읽지 않는다.** epoch·발급 기간·만료 계산 입력(방식·N일·고정 시각)은 발급 준비(`prepare`) 때 `issue-control`에 넣어 두고, Lua가 그 값으로 `expiresAt`을 계산해 요청 기록(requests)에 쓰고 반환한다. 쿠폰 존재·운영 상태 확인은 Lua의 `-2`로 충분하다(Hikari pool을 강의 API와 공유하므로 선착순 핫패스에 SELECT를 두지 않는다).
2. 기존 요청이면 내용·소유자를 대조해 결과를 반환한다. 기간 종료·매진 뒤 재시도도 기존 결과를 조회할 수 있다.
3. 신규 요청은 Lua에서 운영 상태·준비 상태·epoch·시작/종료 시각·회원 중복·재고를 확인한다. SWR 캐시를 최종 판정에 쓰지 않는다.
4. 재고 차감·회원 기록·요청 기록을 한 Lua에서 처리한다. 모든 키 타입·인수를 쓰기 전에 검사한다. 쓰기 순서는 재고 `DECR` → 회원 `SADD` → 요청 `HSET`(회원·접수 시각·만료 시각)이며, **뒤의 쓰기는 `redis.pcall`로 감싸 실패(OOM 등)하면 같은 스크립트 안에서 앞의 쓰기를 되돌리고 오류를 반환한다.** Redis Stream은 두지 않는다(2026-11-08). 요청 기록이 Lua와 같은 atomic 단위에 남으므로 그것이 접수의 복구 근거다. 스크립트 하나가 atomic이라 되돌리기도 다른 요청과 섞이지 않는다. Lua는 오류 시 자동 롤백되지 않으므로 되돌리기 자체가 실패한 부분 기록 의심은 쿠폰 차단·대사로 처리한다. 마지막 1장이면 매진 플래그를 남기고, 앱은 짧은 메모리 캐시로 매진 뒤 요청을 Redis 호출 없이 거절한다(F12). 이 캐시는 요청 ID를 보지 않으므로, 캐시가 매진을 들고 있는 짧은 창(TTL) 동안에는 이미 접수된 요청의 재전송도 `ALREADY_ACCEPTED`가 아니라 매진(409)으로 응답한다. 클라이언트는 같은 요청 ID로 이미 보낸 적이 있으면 매진 응답 뒤 상태 조회로 결과를 확인한다. 반환값: `1` 접수, `2` 같은 회원·같은 요청 ID 재응답, `0` 매진, `-1` 이미 발급, `-2` 접수 불가(issue-control 없음·READY·SUSPENDED·CLOSED·epoch 불일치), `-3` 기간 아님, `-4` 요청 ID 충돌(다른 회원이 같은 요청 ID → 409), `-9` 쓰기 실패(되돌린 뒤).
5. 접수 응답은 ACCEPTED이며 완료가 아니다. 앱은 Lua 성공 직후 `CouponIssueRequested`를 Kafka로 보내고 ACK를 짧게 기다린다(설정, 기본 1초). 성공이면 requests 값에 `PUBLISHED` 표식을 더한다(실패해도 무해). 전송 실패·시간 초과·앱 중단이어도 응답은 ACCEPTED이고 재고를 되돌리지 않는다. **재발행 배치**가 OPEN·SUSPENDED·정리 전 CLOSED 쿠폰의 requests를 `HSCAN`해 접수 뒤 N초(설정, 기본 30초)가 지났는데 DB `coupon_issue_request`에 없는 요청을 같은 ID로 다시 발행한다. 중복 전달은 소비자 멱등이 흡수한다.
6. 소비자가 요청 결과·member_coupon INSERT·총량 이하 조건의 발급 수 증가를 한 DB 트랜잭션으로 저장한다. epoch 검증도 동일 트랜잭션에 포함한다.
7. 제한 재시도 뒤 DLT에 격리한다. 영구 거절을 DB에 확정한 뒤 요청별 Redis 보상 표식과 수량 복구를 한 Lua로 수행한다. DB 보상 완료 기록 전에 종료돼도 Redis 표식으로 중복 복구를 막는다. 이전 epoch 보상은 재구축 재고를 변경하지 않는다.

Kafka key는 couponId로 한다. 같은 요청이 Kafka에 두 번 있거나 DLT에 있어도 한 번만 집계한다. DB 조건부 카운터와 유일성 제약은 최종 방어다.

## 계산과 주문

쿠폰 모듈이 서버가 조회한 강의 가격·판매자와 회원 소유 쿠폰으로 계산한다. 클라이언트가 보낸 할인 금액을 신뢰하지 않는다. 회원 선택은 쿠폰→강의 매핑으로 받는다. 추천은 공용 쿠폰의 항목 배치까지 주문 전체로 비교한다. 임의 후보 제한·근사 결과를 최대 할인이라고 표시하지 않는다.

주문 처리는 다음처럼 지속 가능한 단계로 나눈다.

1. 강의 트랜잭션: 주문·가격/할인 스냅샷·예약 작업 ID·선택 매핑 저장. HP 작업은 아직 실행 가능하게 만들지 않는다.
2. 트랜잭션 밖: 쿠폰 reserve 호출. 쿠폰 모듈에서 전체 매핑을 검증하고 한 트랜잭션으로 CAS 예약한다.
3. 강의 트랜잭션: 응답 generation과 할인 기록 저장 후 HP 보류 작업 등록/활성화. 동일 작업 재조회로 2~3단계 사이 종료를 복구한다.
4. billing 한 트랜잭션: 구매자 부담과 쿠폰별 플랫폼 부담을 함께 보류한다. 한 재원 실패면 해당 항목 전체를 반영하지 않는다.
5. 강의 결과 처리기: 모든 항목의 보류 성공 후 결제 완료와 쿠폰 결제 완료 이벤트를 함께 기록한다.
6. 실패: 이미 성공한 항목을 보상하고 결과 불명 보류를 끝까지 확인한다. 양쪽 재원 보상 완료 후 예약 해제 작업을 실행한다. 보상 중 재사용을 허용하지 않는다.

기존 OrderCreateUseCase는 주문 생성과 동시에 실행 가능한 HP 작업을 등록하므로 단계 1~3에 맞춰 수정해야 한다. Facade 호출 순서만 바꾸면 배치 경쟁을 막을 수 없다.

계산 결과의 generation은 기대값이고 최종 할인 이력에는 reserve가 반환한 reservedGeneration을 쓴다. 예약 시 소유·기한·상태·정책 버전·금액을 재검증한다. 기존 주문은 할인 0과 기존 금액을 유지하도록 마이그레이션한다.

## 지급·취소·복원

지급 시 구매자 보류 + 플랫폼 보류를 판매자 수익 HP로 합산 지급한다. 원가격을 지급액으로 그대로 사용하는 기존 수익 조회와 HP 작업을 수정한다.

취소 요청 → 강의 취소 처리 중·시청 차단 → billing 구매자 반환/플랫폼 예약 복구 → 강의 취소 완료 이벤트 → 쿠폰 복원 또는 만료 순서다. 이의 제기 인정도 같은 금액 원칙을 사용한다. 다른 항목 재계산은 없다.

취소 진행 이벤트는 만들지 않는다. RESERVED·USED로 재사용을 막는다. 결제 실패 해제도 만료된 경우 EXPIRED로 끝낸다. 늦은 결제 완료는 해당 시도 사실만 기록하고 취소·해제 결과를 뒤집지 않는다.

구매자 금액 0이어도 플랫폼 부담이 있으면 처리한다. 모든 금액이 0이면 원장에 0 금액 줄을 만들지 않고 작업 결과·주문 상태를 정상 완료한다. 충전·출연·환전의 최소 금액 규칙은 변경하지 않는다.
