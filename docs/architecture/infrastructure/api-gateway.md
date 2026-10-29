# 3. API Gateway — Envoy Gateway

구현·로컬 검증·운영 전환: [Gateway 운영 문서](../../deploy/gateway.md)(#372). 내부 API는 k8s에서 8081 listener를 따로 열고 외부 8080과 경로를 분리한다. Grafana는 `/grafana/`에서 자체 로그인으로 공개한다.

상위 문서: [최종 프로젝트 인프라 설계](../final-project-infrastructure.md). 결정 기록은 #369다.

## 맡는 책임

| 책임 | 내용 | 지금(Compose) 담당 |
| --- | --- | --- |
| TLS 종료 | cert-manager가 발급한 인증서로 443을 종료하고 80은 HTTPS로 redirect한다 | edge nginx + certbot |
| 모듈 단위 routing | AGENTS.md 3장의 모듈 진입 경로마다 `HTTPRoute`를 두고 그 앱의 Service로 보낸다. `/`는 React 정적 서버로 보낸다 | edge nginx의 `/api` 한 줄 |
| JWT 서명 검증 | `SecurityPolicy`의 JWT provider가 member-app JWKS(`remoteJWKS`)로 서명·만료·발급자를 확인한다. `optional: true`로 둔다. 검증한 claim `sub`·`role`을 `X-User-Id`·`X-User-Role` 헤더로 넘긴다(`claimToHeaders`). 회원의 역할은 하나라 `role`은 문자열 하나다. Envoy의 `claimToHeaders`는 배열 claim을 넘기지 못하므로 배열로 만들지 않는다 | 없음(세션) |
| 헤더 위조 차단 | 모든 route에서 외부 요청에 붙은 `X-User-*` 헤더를 먼저 지운다 | 없음 |
| 내부 API 차단 | `/api/v1/internal/**`은 `HTTPRoute`를 만들지 않는다. cluster 안 Service 호출로만 닿는다 | edge nginx가 404 |
| 큰 본문 업로드 | 업로드 경로(`PUT /api/v1/{모듈}/files/{id}/content`)에만 긴 timeout을 건다(`BackendTrafficPolicy`·`ClientTrafficPolicy`) | edge nginx의 2GB 허용 |
| trace 시작점 | 요청마다 W3C `traceparent`를 만들거나 이어 받아 OTLP로 보내고, access log를 JSON으로 남겨 Alloy가 수집한다 | 없음 |

rate limit은 Gateway가 아니라 각 앱의 Resilience4j가 맡는다(아래).

## 인증 흐름

JWT는 암호화가 아니라 **서명**이다. payload는 누구나 Base64로 풀어 읽을 수 있으므로 회원 ID·역할 외의 정보를 넣지 않는다. 서명 검증은 "member-app의 개인 키로 서명됐고, 그 뒤 내용이 바뀌지 않았는가"를 공개 키로 확인하는 일이다.

1. member-app이 Google OAuth 로그인 뒤 access token(RS256, 15분)을 발급한다. 개인 키는 member-app만 갖고, 공개 키는 JWKS endpoint로 공개한다(#370).
2. refresh token(14일)은 `member` database에 저장하고 쓸 때마다 회전한다. refresh 경로로만 가는 `HttpOnly; Secure; SameSite=Strict` 쿠키로 준다. 로그아웃하면 폐기한다.
3. 프론트는 access token을 메모리에 두고 `Authorization: Bearer`로 보낸다. 새로고침하면 시작할 때 refresh를 한 번 호출한다. Bearer 헤더는 브라우저가 자동으로 싣지 않으므로 API의 CSRF 보호(`X-XSRF-TOKEN`)는 뺀다.
4. Envoy가 서명을 검증한다. 위조·만료 토큰은 401로 거절하고, 토큰이 없는 요청은 헤더 없이 통과시킨다. 로그인이 필요한지는 각 앱의 Security 설정이 정한다. 그래서 비로그인 경로 목록을 Envoy와 앱 두 곳에 적지 않는다.
5. 앱은 `X-User-*` 헤더로 인증 객체를 만들고 `@PreAuthorize`로 인가한다(#371). 앱은 서명을 다시 검증하지 않으므로, NetworkPolicy로 앱의 외부 API 포트에는 Envoy만 접근하게 한다. 앱 간 내부 API는 지금처럼 `X-Internal-Api-Key`로 인증한다.
6. 채팅: 브라우저의 WebSocket upgrade 요청에는 Bearer 헤더를 실을 수 없다. chat-app은 STOMP CONNECT frame의 토큰을 JWKS로 직접 검증한다. 토큰을 URL query에 싣지 않는다(access log에 남는다). 연결 뒤 강제 종료는 회원 이벤트(#242)를 따른다.
7. 발급한 JWT는 회수할 수 없다. 정지·역할 변경은 다음 refresh에서 막히고, 최대 15분(access token 수명) 늦게 반영된다. 그래서 HP가 움직이는 경로(보류·반환·지급·환전)와 역할 조건이 있는 거래(출연 거래는 활동 중인 인플루언서 이상, `docs/policy.md`)는 토큰의 `role`만 믿지 않고, 각 앱이 복제한 회원 상태를 함께 확인한다(#371).
8. `docs/policy.md`의 "로그인 상태는 서버 세션으로 유지"와 "역할 변경은 다시 로그인하지 않아도 바로 반영"은 JWT 전환과 맞지 않는다. policy를 어떻게 고칠지는 팀이 정한다(#370).

OAuth 진행 상태(authorization request)는 HttpSession 대신 짧은 수명의 암호화 쿠키에 저장한다. 그래서 Spring Session과 그 저장소였던 `platform` database가 필요 없다.

## rate limit — 각 앱의 Resilience4j

- 대상: 로그인, HP 충전 요청, 고객센터 RAG 질문처럼 남용되면 비용이 드는 경로
- key: `X-User-Id`가 있으면 회원 ID, 없으면 client IP
- client IP 신뢰 경계: Envoy 앞에는 신뢰할 proxy가 없으므로 `ClientTrafficPolicy`의 `X-Forwarded-For` 신뢰 hop 수는 0으로 둔다. Envoy는 실제 접속 주소를 `X-Forwarded-For` 맨 뒤에 붙인다. 앱(`server.forward-headers-strategy=native`, Tomcat `RemoteIpValve`)은 오른쪽부터 읽어 cluster 내부 주소가 아닌 첫 주소를 client IP로 쓴다. 그래서 클라이언트가 앞쪽에 넣은 값은 무시된다. Envoy가 실제 client IP를 보는지(ServiceLB와 `externalTrafficPolicy: Local`)는 설치 뒤 access log로 확인한다
- client IP rate limit을 구현할 때 Envoy가 실제 공인 IP를 보는지 다시 확인한다. Envoy가 private 주소(로컬 k3d의 docker 대역, SNAT된 node 주소)를 보면 Tomcat이 그 주소도 proxy로 건너뛰어 클라이언트가 넣은 값을 읽는다. 그때는 Envoy에서 `X-Forwarded-For`를 실제 접속 주소로 덮어쓰는 방식을 검토한다. 앱 코드에 client IP rate limit이 아직 없어 #372에서는 넣지 않았다
- 한도를 넘으면 429를 `ProblemDetail`로 응답한다
- RateLimiter는 앱 프로세스 메모리에서 센다. 앱마다 replica가 1개라 앱 하나의 한도가 곧 전체 한도다. replica를 늘리면 한도도 replica 수만큼 늘어나므로, 그때 공유 저장소 방식을 다시 판단한다
- 구현은 쓰는 앱에 먼저 두고, 두 앱 이상이 같은 코드를 쓰면 `common`으로 옮긴다
- 앱 간 내부 HTTP 호출(#280)의 timeout·circuit breaker도 Resilience4j로 통일한다

## 구현 후보와 판단

| 후보 | 형태 | 장점 | 이 프로젝트에서의 한계 |
| --- | --- | --- | --- |
| **Envoy Gateway (선택)** | Gateway API 구현. controller + Gateway마다 Envoy proxy Deployment | OSS에서 JWT 검증(`SecurityPolicy`), claim의 헤더 전달, WebSocket, OpenTelemetry tracing을 지원한다. 공식 Helm 차트 | controller와 proxy가 상시 pod다. Helm 기본값(controller 요청 256Mi, proxy 요청 512Mi)이 커서 예산에 맞게 낮춘다 |
| Traefik (k3s 내장) | k3s 기본 ingress, Gateway API provider | 추가 설치 없음 | JWT middleware는 유료 Traefik Hub 전용이다. OSS에서는 요청마다 다른 서비스를 부르는 ForwardAuth나, 시작할 때마다 인터넷에서 받는 실험적 plugin으로만 할 수 있다 |
| Spring Cloud Gateway | 별도 Spring Boot 앱 | 팀이 익숙한 Java filter, JWKS 검증 | **JVM이 하나 더** 상주한다. WebSocket 프록시는 WebFlux 버전만 지원한다 |
| edge nginx 확장 | 지금 설정에 route 추가 | 추가 component 없음 | nginx를 없애기로 했다. 서버 파일을 직접 고치는 방식이라 cluster 배포와 버전 관리가 따로 논다 |

근거: [Envoy Gateway JWT Authentication](https://gateway.envoyproxy.io/docs/tasks/security/jwt-authentication/), [Traefik JWT middleware(Hub 전용)](https://doc.traefik.io/traefik/reference/routing-configuration/http/middlewares/jwt/), [Spring Cloud Gateway 문서](https://docs.spring.io/spring-cloud-gateway/reference/).

판단: **Envoy Gateway를 쓴다.** 새 JVM 없이 TLS·routing·JWT 검증·internal 차단을 한곳에서 맡고, route와 정책이 `k8s/` manifest 안의 `HTTPRoute`·`SecurityPolicy`로 앱과 같은 PR에서 관리된다.

참고로 Kubernetes 커뮤니티의 Ingress NGINX controller는 2026년 3월에 은퇴해 더는 보안 수정이 나오지 않는다([Kubernetes blog](https://kubernetes.io/blog/2025/11/11/ingress-nginx-retirement/)). cluster 안 routing을 Ingress가 아니라 Gateway API로 만드는 근거다.

## 완료 증거

- `HTTPRoute` 하나의 backend만 바꿔 특정 모듈 경로를 다른 Service로 보내도 프론트 요청과 로그인이 그대로 동작한다.
- 만료·위조 토큰은 401이고, 토큰 없는 요청은 통과한다. 외부에서 보낸 `X-User-Id`는 앱에 닿지 않는다.
- Envoy가 아닌 pod에서 앱의 외부 API 포트를 호출하면 NetworkPolicy가 막는다.
- 위조한 `X-Forwarded-For`를 보내도 rate limit key(client IP)가 바뀌지 않는다.
- 외부에서 `/api/v1/internal/**`를 호출하면 앱에 닿지 않는다.
- 2GB 업로드가 끝까지 성공한다.
- STOMP WebSocket이 Envoy를 거쳐 연결되고, CONNECT에서 토큰이 검증되며, rolling update 때 재연결된다.
- rate limit 한도를 넘긴 k6 요청이 앱에서 429로 거절되고, 선택한 관측성 UI에 거절 수가 나온다.
- cert-manager가 인증서를 자동으로 갱신한다.
- Envoy가 시작한 trace가 Tempo에서 앱 transaction과 Kafka publish까지 이어진다.
