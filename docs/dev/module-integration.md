# 모듈 구조와 연동

현재 소스 구조는 [앱 구조](../architecture/app-modules.md)를 따른다. 각 앱은 독립 실행 JAR를 만들고, 앱 사이 연동은 제공 앱의 내부 HTTP API와 Kafka 이벤트다(#271~#280, D62).

## 의존 경계

- 업무 코드는 `apps/<업무>-app`에 둔다. 다른 앱의 Gradle 프로젝트, 엔티티 또는 Repository를 의존하지 않는다.
- 앱별 공통 코드는 `com.hapbang.<업무>.global`에 둔다. `global` Gradle 모듈은 없다.
- `common`에는 재사용 기술 라이브러리와 기존 공유 타입을 둔다. common은 앱에 의존하지 않는다.
- `shared`에는 DTO·이벤트·안정적인 Enum·오류 코드·공통 HTTP Client만 둔다. Java Port·엔티티·Repository·공유 업무 예외는 두지 않는다(D62, `SharedContractRulesTest`). 다른 앱이 받는 integration event만 shared에 두고, 앱 안에서만 쓰는 이벤트는 앱에 둔다.
- 각 앱의 HTTP 요청·응답 DTO는 해당 앱의 `in/dto`·`app/dto`에 둔다.

## 테스트 실행 산출물 예외

같은 모노레포 안에서 `lecture-app`·`coupon`의 `Test` task가 `:billing-app:e2eServerJar`를 `dependsOn`으로 빌드하고 JAR를 파일 입력으로 받는 것은 테스트 실행 산출물 의존의 명시적 예외다. billing은 별도 JVM과 격리 DB에서 실행하며 소비자는 HTTP로 호출한다. 이 예외는 소비자의 Java 클래스패스에 billing 구현을 넣거나 다른 앱의 엔티티·Repository·DB를 공유하도록 허용하지 않는다. 실행 방법과 하네스 경계는 [테스트 문서](testing.md#강의billing-http-통합-테스트)를 따른다.

루트 `build.gradle.kts`의 `verifyModuleStructure`는 각 프로젝트의 `compileClasspath`·`runtimeClasspath` 해석 결과에서 전이 의존을 포함한 Gradle 프로젝트 컴포넌트를 검사해 다른 실행 앱에 대한 의존을 막는다. `testCompileClasspath`·`testRuntimeClasspath`와 task의 `dependsOn`·파일 입력은 검사하지 않는다. 따라서 이 검사의 통과는 위 테스트 산출물 예외나 테스트 클래스패스 경계까지 자동으로 검증했다는 뜻이 아니다.

레포를 분리할 때는 현재 모노레포 task 연결을 그대로 사용할 수 없다. billing이 발행한 E2E 실행 JAR 아티팩트를 소비자 테스트 환경에서 받아 하네스에 전달하도록 빌드를 변경해야 한다. 현재 구현은 같은 저장소의 빌드 산출물을 사용하며, 외부 아티팩트 취득·연결은 레포 분리 시 필요한 작업이다.

## 기존 프로세스 내부 포트

모듈 간 Java Port는 없다. 강의·출연은 billing 내부 HTTP(#274~#277)로 바꿨고, `LectureObjectionPort`는 #433, shared `BillingPort`·`BillingPortAdapter`는 #486에서 지웠다. 고객센터 ↔ 강의 이의 제기는 내부 HTTP(접수)와 Kafka 이벤트(판단·철회·결과)다(#278·#279). shared에 Java Port를 두지 않는다(`SharedContractRulesTest`). 다른 앱의 구현을 Gradle 의존으로 끌어오거나(`verifyModuleStructure`가 compileClasspath·runtimeClasspath를 검사) 운영용 Fake를 등록하지 않는다.

현재 일부 호출은 호출자 트랜잭션과 함께 커밋·롤백되는 의미를 가진다. HTTP 호출로 옮길 때는 소비 모듈 로컬 트랜잭션 밖에서 실행하고, 타임아웃·중복 요청·상태 복구를 설계해야 한다. 같은 JVM의 `@Transactional` 의미를 그대로 유지한다고 가정하지 않는다.

## 내부 HTTP API (D62·D63)

- 경로는 `/api/v1/internal/<모듈>/...`, 인증 헤더는 `X-Internal-Api-Key`다.
- 앱의 `global.internal.InternalApiSecurityConfig`가 세션을 사용하지 않는 전용 체인과 `ROLE_INTERNAL`을 적용한다.
- 기존 공통 HTTP Client는 `hapbang.clients.<모듈>.base-url`이 있을 때 등록된다. 실제 제공 서비스의 주소를 실행 환경에 주입한다.
- 공개 응답 형식은 RsData/ProblemDetail이며 계약 문서는 `docs/api/internal-*.md`를 따른다.
- 외부에서 내부 API로 들어오는 경로는 Gateway/네트워크 계층에서도 차단해야 한다. 이번 작업에서는 Gateway 배포를 만들지 않는다.

## 이벤트와 회원 복제본

- Spring Event와 `@ApplicationModuleListener`는 같은 프로세스 안에서만 동작한다. 회원 가입·변경, 지갑 생성, 출연 알림·로그아웃 알림은 앱 분리만으로 다른 프로세스에 전달되지 않는다.
- `@ApplicationModuleListener`는 커밋 뒤 비동기·별도 트랜잭션으로 처리한다. 다른 리스너 애노테이션을 겹쳐 붙이지 않는다.
- 회원 복제본은 가입 이벤트로 만들고, 변경 이벤트는 기존 복제본만 갱신한다. 없는 복제본을 변경 이벤트에서 임의 생성하지 않는다.
  - 읽기용 사본 예외(챗봇 `member_copy`, #717): 변경 이벤트에서도 사본이 없으면 만든다. 사본은 version으로 순서를 정하고 매시 싱크가 같은 행을 만들므로, 변경 이벤트로 만들어도 결과가 같다.
- Kafka 모듈 추가만으로 업무 이벤트를 외부 발행하지 않는다. 이벤트 식별·계약·발행 신뢰성·중복 소비 처리는 서비스 간 전환 작업의 범위다.

## 빈과 배치

- 앱 실행 진입점 아래의 업무·global 패키지를 해당 앱이 스캔한다. 다른 앱의 global 패키지를 스캔하지 않는다.
- 같은 타입의 특수 빈이 여러 개면 명시적 이름과 `@Qualifier`를 사용한다. 필요한 경우 기본 후보에서 제외한다.
- 채팅 STOMP의 스케줄러와 앱 배치 스케줄러가 섞이지 않도록 각 앱의 `SchedulingConfig`를 사용한다.
- 배치는 `@ConditionalOnSchedulingEnabled`를 붙인 얇은 `@Scheduled` 빈과 UseCase로 구성하고, 테스트는 [테스트 문서](testing.md)를 따른다.
