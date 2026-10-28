# API 규칙

컨트롤러·요청 DTO·예외를 만들 때 따르는 규칙이다. 핵심 요약은 `AGENTS.md` 7장에 있다.

## 성공 응답 (RsData)

- 성공 응답은 `global`의 `RsData`(`com.hapbang.global.response`)로 감싼다: `statusCode`(HTTP 상태 코드 숫자), `message`, `data` (2026-10-13 팀 규칙). 오류는 아래 "오류 응답"대로 `ProblemDetail`이다.
- 컨트롤러는 `RsData.ok(message, data)`·`RsData.created(message, data)`·`RsData.of(status, message, data)`를 그대로 반환한다. 200이 아니면 메서드에 `@ResponseStatus`로 같은 상태를 정한다 (예: 생성은 `@ResponseStatus(HttpStatus.CREATED)` + `RsData.created(...)`). 상태가 상황마다 달라지거나 헤더가 필요하면 `ResponseEntity<RsData<T>>`로 같은 상태를 본문과 HTTP 응답에 함께 쓴다.
- 본문이 없는 응답(204)은 `ResponseEntity<Void>`를 쓴다.

## 오류 응답 (global 공통 예외 처리, #71)

- 모든 HTTP 오류는 `global`의 `GlobalExceptionHandler` 하나가 `ProblemDetail`(RFC 9457)로 응답한다 (2026-10-12 회의). **모듈은 `@RestControllerAdvice`를 만들지 않는다.**
- 응답 본문: `status`, `detail`(메시지), `code`(프론트가 분기하는 식별자). 요청 값 오류는 `errors: [{field, message}]`를 더한다.
- 공통 클래스 (`com.hapbang.global.exception`)
    - `BaseErrorCode`: 오류 코드 계약. `getStatus()`, `getMessage()`, `getCode()`(기본값 enum 이름)
    - `BusinessException`: 예상된 업무 실패. 모듈 예외의 부모다
    - `GlobalErrorCode`: 공통 핸들러 전용 코드 (`INVALID_REQUEST`, `UNAUTHORIZED`, `FORBIDDEN`, `NOT_FOUND`, `CONFLICT`, `INTERNAL_ERROR`)
- 모듈에서 할 일
    - 오류 코드는 모듈의 `{Module}ErrorCode` enum에 정의하고 `BaseErrorCode`를 구현한다. 응답의 `code`는 enum 이름이다.
    - 예외는 `{Module}Exception extends BusinessException`으로 만들고 `new {Module}Exception({Module}ErrorCode.X)`로 던진다.
    - 메시지만 상황에 맞게 바꾸려면 상세 메시지 생성자(`new {Module}Exception(code, "상세")`)를 쓴다. `code`는 같다.
    - 응답에 추가 정보가 필요하면 하위 예외에서 `addProperty(name, value)`를 쓴다. `code`·`errors`·표준 필드는 쓸 수 없다.
    - 예상하지 못한 실패(버그, 외부 장애)는 `BusinessException`으로 감싸지 않는다. 공통 핸들러가 500 `INTERNAL_ERROR`로 응답하고, 내부 메시지는 응답에 넣지 않고 로그에만 남긴다. 낙관적 락 충돌처럼 따로 처리하지 않은 예외도 500 `INTERNAL_ERROR`다.
- 공통 핸들러가 처리하는 것

| 상황 | 응답 |
| --- | --- |
| `BusinessException` | 오류 코드의 상태, `code`, `detail` (+ 추가 속성) |
| `@Valid`·`@RequestParam` 검증 실패, 타입 불일치, 필수 파라미터 누락, JSON 형식 오류 | 400 `INVALID_REQUEST` + `errors` |
| 컨트롤러·Facade의 `@PreAuthorize` 거절 | 로그인했으면 403 `FORBIDDEN`, 아니면 401 `UNAUTHORIZED` |
| 그 밖의 Spring MVC 예외 (405, 415, 없는 경로 등) | 해당 상태 + `code`(공통 코드 또는 HTTP 상태 이름, 예: `METHOD_NOT_ALLOWED`) |
| 처리하지 못한 예외 | 500 `INTERNAL_ERROR` |

- 공통 핸들러가 처리하지 않는 것
    - Security 필터에서 막힌 요청(로그인 안 함, `/api/v1/*/admin/**` URL 권한)은 컨트롤러에 오기 전이라 `SecurityConfig`가 응답한다 (본문 없음).
    - STOMP(WebSocket) 오류는 HTTP가 아니라서 각 모듈의 `@MessageExceptionHandler`가 처리한다.

## 요청 값

- `spring.mvc.formcontent.filter.enabled: false`(#107)라 PUT·PATCH·DELETE의 form 본문 파라미터는 읽지 않는다. 이런 요청 값은 쿼리 문자열이나 JSON으로 받는다 (이유는 [S3 문서](../deploy/s3.md)의 "업로드 규칙").
- 요청 DTO의 필수 숫자는 `@NotNull Long/Integer`로 둔다. 기본 타입이면 값이 빠졌을 때 JSON 파싱 400이 나서 어느 필드가 문제인지 알 수 없다.

## Swagger

- enum 설명(springdoc 3.1.1, OAS 3.1): 상수별 설명은 지원되지 않는다. enum 타입의 `@Schema(description)`이 필드에 인라인되지만, 필드에 `description`이 있으면 덮어쓴다.
- 쿼리 파라미터는 Swagger UI가 스키마 설명을 보여 주지 않으므로 Spec의 `@Parameter(description)`에 둔다 (#112).
