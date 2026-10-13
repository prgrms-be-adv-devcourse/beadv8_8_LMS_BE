# 출연(Appearance) 모듈: 오류 코드

출연 API의 오류는 Problem Detail(RFC 9457) 형식으로 응답한다. 오류 코드가 추가되면 이 문서도 함께 갱신한다.

- 정책과 구조: [module.md](module.md)
- 테스트 명세: [test-spec.md](test-spec.md)

---

## 1. 응답 형식

```http
HTTP/1.1 403 Forbidden
Content-Type: application/problem+json

{
  "title": "Forbidden",
  "status": 403,
  "detail": "인플루언서 이상만 출연 거래에 참여할 수 있습니다.",
  "instance": "/api/v1/appearances/...",
  "code": "NOT_INFLUENCER"
}
```

| 필드 | 설명 |
|---|---|
| `status` | HTTP 상태 코드 |
| `title` | HTTP 상태 이름 |
| `detail` | 사용자에게 보여줄 메시지. 기본 메시지 대신 상황별 메시지가 들어갈 수 있다 |
| `instance` | 요청 경로 |
| `code` | 출연 오류 코드 (아래 표). 프론트는 이 값으로 분기한다 |

- `type`은 기본값(`about:blank`)이라 응답에서 생략된다.
- (#71) 모듈 핸들러(`AppearanceExceptionHandler`)는 없앴다. `AppearanceException extends BusinessException`이고, global의 공통 핸들러(`GlobalExceptionHandler`)가 모든 모듈의 오류를 같은 형식(Problem Detail + `code`)으로 응답한다.
- 로그인하지 않은 요청의 401은 보안 필터가 응답하며, 이 형식이 아니다 (본문 없음).

## 2. 오류 코드

| 코드 | 상태 | 기본 메시지 | 발생 상황 | 이슈 |
|---|---|---|---|---|
| `INVALID_REQUEST` | 400 | 요청 값이 올바르지 않습니다. | 요청 값 형식·범위 오류 | #26 |
| `APPEARANCE_USER_NOT_FOUND` | 404 | 출연 회원 정보를 찾을 수 없습니다. | 출연 모듈에 복제된 회원 정보가 없음 (가입 이벤트 미반영 등) | #31 |
| `APPEARANCE_USER_NOT_ACTIVE` | 403 | 현재 이용할 수 없는 회원입니다. | 탈퇴(`WITHDRAWN`)·정지(`SUSPENDED`) 회원이 출연 거래에 참여 | #31 |
| `NOT_INFLUENCER` | 403 | 인플루언서 이상만 출연 거래에 참여할 수 있습니다. | 일반 유저·관리자가 출연 거래에 참여 | #31 |

- 정의 위치: `boundedContext/appearance/src/main/java/com/hapbang/appearance/domain/AppearanceErrorCode.java`
- 출연 예외는 `throw new AppearanceException(AppearanceErrorCode.XXX)`로 던진다. 상황별 메시지가 필요하면 두 번째 인자로 넘긴다.
