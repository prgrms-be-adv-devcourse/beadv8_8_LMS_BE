# 모듈 구조와 연동

모듈 경계 규칙은 `AGENTS.md` 3장에 있다. 이 문서는 그 규칙을 코드로 지키는 방법이다.

## 레이어

- 서비스 레이어 = Facade/UseCase/Support (`member/app/MemberFacade` 기준). 회의에서 말하는 "서비스단"은 MSA 분리가 아니라 이 레이어를 뜻한다.
- 모듈에서 `@PreAuthorize`를 쓰려면 `spring-security-core` 의존성이 필요하다 (global은 `implementation`으로만 의존한다).

## shared 계약

- 모듈 간 호출 계약(포트·요청·응답·참조 enum)과 이벤트는 `shared`에 두고, 모듈은 `shared`에만 의존한다 (다른 모듈 Gradle 의존 금지).
- 컨트롤러 요청·응답 DTO는 프론트용이라 모듈 `in/dto`·`app/dto`에 둔다.
- 모듈 테스트(`ModuleTestConfiguration`)에는 다른 모듈 빈이 없으므로 `shared` 포트는 테스트 소스의 Fake로 채운다.
- `shared`는 `global`에 의존하지 않아 shared 계약 예외는 `BusinessException`을 상속할 수 없다. 런타임 예외 + 사유 enum으로 두고, 호출한 모듈이 자기 업무 예외로 바꿔 던진다.
- 참여한 `@Transactional`에서 예외가 나면 호출한 쪽 트랜잭션은 rollback-only라, 잡고 삼키면 커밋 때 `UnexpectedRollbackException`이 난다 (D49).

## 내부 HTTP API (D62·D63)

새 모듈 간 동기 연동은 이 방식으로 만든다.

- 경로 `/api/v1/internal/<모듈>/...`, 헤더 `X-Internal-Api-Key`(`shared.internal.InternalApiHeaders`).
- 인증: global `internal.InternalApiSecurityConfig` 전용 체인(세션 미사용, `ROLE_INTERNAL`) + Facade `@PreAuthorize("hasRole('INTERNAL')")`.
- 계약 DTO·enum은 제공 모듈의 `shared.<모듈>.dto`·`domain`, 호출 Client는 `shared.<모듈>.out.<모듈>ApiClient` (`hapbang.clients.<모듈>.base-url`이 있을 때만 빈이 생김).
- 응답은 RsData/ProblemDetail, Swagger에 노출한다. 계약 문서는 `docs/api/internal-*.md`.

## 이벤트

- `@ApplicationModuleListener` = `@Async` + `@Transactional(REQUIRES_NEW)` + `@TransactionalEventListener`(AFTER_COMMIT). 다른 리스너 애노테이션을 겹쳐 붙이지 않는다. 테스트 방법은 [테스트 문서](testing.md)의 "이벤트 리스너".

## 회원 복제본

- ID를 직접 넣는 엔티티(`ReplicaUser` 상속 등)는 `save()`가 `merge`로 동작해서, "없으면 생성"을 동시에 처리하면 PK 충돌 없이 덮어쓴다.
- 그래서 회원 복제본은 가입 이벤트로만 만들고, 변경 이벤트는 있는 복제본만 갱신한다 (없으면 예외로 실패시켜 재처리, 채팅 #79·강의 D17).

## Spring Modulith와 빈

- 다른 모듈이 global의 새 패키지를 쓰려면 그 패키지에 `package-info.java`(`@NamedInterface("이름")`)가 있어야 한다. 없으면 모듈 테스트는 통과하고 apps `ModularityTests`만 `depends on non-exposed type`으로 실패한다 (#149의 `global.scheduling`). `shared`는 `Type.OPEN` 모듈이라 필요 없다 (#156 `shared.lecture`).
- 모듈에 `Clock` 같은 공통 타입 빈을 추가할 때는 `@Bean(name = ..., defaultCandidate = false)` + `@Qualifier`로 주입한다. 같은 타입 빈이 둘이 되면 Spring Modulith `eventPublicationRegistry`가 고르지 못해 앱이 뜨지 않는데, 모듈 테스트로는 안 보이고 `apps` 테스트에서만 드러난다 (강의 `LectureConfig.CLOCK`, 출연 `appearanceClock`).
- 채팅 STOMP가 `messageBrokerTaskScheduler`를 등록해서 Boot의 `TaskScheduler` 자동 설정이 물러난다 (`@ConditionalOnMissingBean`). 배치는 apps `SchedulingConfig`의 `taskScheduler` 빈(`spring.task.scheduling.*` 적용)을 쓴다 (#157). Boot 자동 설정에 기대기 전에 같은 타입의 빈이 이미 있는지 확인한다.

## 배치

- 모듈 배치 = `@ConditionalOnSchedulingEnabled`를 붙인 얇은 `@Scheduled` 빈 + UseCase (항목마다 `TransactionTemplate`, 예외는 루프에서 처리. 강의 `OrderAutoConfirmUseCase`).
- 테스트 방법은 [테스트 문서](testing.md)의 "배치".
