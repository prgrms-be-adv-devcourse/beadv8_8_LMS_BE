# 고객센터(문의) 앱 결정 사항

customer-support-app에서 확정한 결정이다. 이의 제기 강의 연동 결정은 강의 결정 기록(`docs/lecture/decisions.md` D60·D61·D71)에 있다.

- **Gateway 사용자 헤더 인증(2026-11-02, #540, 강의 D72·출연 #395와 같은 방식)**: 회원 앱이 JWT로 바뀌어(#370) 세션 설정으로는 로그인이 필요한 문의 API가 모두 401이었다. Envoy가 JWT를 검증해 넘기는 `X-User-Id`·`X-User-Role`로 `global/auth/GatewayUserHeaderFilter`가 로그인 상태를 만든다(`getName()`은 회원 ID, 권한은 `ROLE_{역할}`이라 `@LoginUserId`와 Facade의 `@PreAuthorize`는 그대로다). 헤더가 없으면 익명, 한쪽만 있거나 형식이 틀리면 익명 + 경고 로그다. 서명은 다시 검증하지 않는다(Gateway가 외부 `X-User-*`를 지우고 NetworkPolicy가 8080에 Gateway만 들인다). 쿠키로 인증하지 않으므로 CSRF를 끄고 세션을 만들지 않는다(STATELESS, `RequestAttributeSecurityContextRepository`). 내부 API(`/api/v1/internal/**`)는 지금처럼 `X-Internal-Api-Key`로만 인증한다. 필터와 테스트 로그인(`support.GatewayLogin`)은 앱 안에 둔다. 프론트는 문의 API에 `X-XSRF-TOKEN`을 더는 보내지 않아도 된다. actuator 관리 포트 분리는 #528에서 한다.
