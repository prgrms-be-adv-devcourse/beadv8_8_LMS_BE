# 모듈 구조와 연동

현재 소스 구조와 전환 범위는 [앱 구조](../architecture/app-modules.md)를 따른다. 각 앱은 독립 실행 JAR를 만들지만 기존 프로세스 내부 연동은 후속 전환 대상이다.

## 의존 경계

- 업무 코드는 `apps/<업무>-app`에 둔다. 다른 앱의 Gradle 프로젝트, 엔티티 또는 Repository를 의존하지 않는다.
- 앱별 공통 코드는 `com.hapbang.<업무>.global`에 둔다. `global` Gradle 모듈은 없다.
- `common`에는 재사용 기술 라이브러리와 기존 공유 타입을 둔다. common은 앱에 의존하지 않는다.
- 이벤트 스키마와 Java 계약 모듈은 이번 작업에서 만들지 않는다. 기존 `shared`의 DTO·포트·이벤트는 업무 연동을 함께 바꾸지 않기 위해 그대로 보존한다. 신규 이벤트를 모두 shared에 추가한다는 정책은 아니다.
- 각 앱의 HTTP 요청·응답 DTO는 해당 앱의 `in/dto`·`app/dto`에 둔다.

## 기존 프로세스 내부 포트

`BillingPort`와 `LectureObjectionPort`의 구현은 각각 billing·강의 앱에 있다. 출연·강의·고객센터 앱에 그 구현을 Gradle 의존으로 다시 끌어오거나 운영용 Fake를 등록하지 않는다. 필요한 원격 어댑터가 없는 앱은 실행에 제약이 있다.

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
- Kafka 모듈 추가만으로 업무 이벤트를 외부 발행하지 않는다. 이벤트 식별·계약·발행 신뢰성·중복 소비 처리는 서비스 간 전환 작업의 범위다.

## 빈과 배치

- 앱 실행 진입점 아래의 업무·global 패키지를 해당 앱이 스캔한다. 다른 앱의 global 패키지를 스캔하지 않는다.
- 같은 타입의 특수 빈이 여러 개면 명시적 이름과 `@Qualifier`를 사용한다. 필요한 경우 기본 후보에서 제외한다.
- 채팅 STOMP의 스케줄러와 앱 배치 스케줄러가 섞이지 않도록 각 앱의 `SchedulingConfig`를 사용한다.
- 배치는 `@ConditionalOnSchedulingEnabled`를 붙인 얇은 `@Scheduled` 빈과 UseCase로 구성하고, 테스트는 [테스트 문서](testing.md)를 따른다.
