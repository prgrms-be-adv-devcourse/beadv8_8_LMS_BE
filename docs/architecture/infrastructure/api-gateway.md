# 3. API Gateway — Envoy Gateway

구현·로컬 검증·운영 전환: [Gateway 운영 문서](../../deploy/gateway.md)(#372). 내부 API는 k8s에서 8081 listener를 따로 열고 외부 8080과 경로를 분리한다. Grafana는 `/grafana/`에서 자체 로그인으로 공개한다.

상위 문서: [최종 프로젝트 인프라 설계](../final-project-infrastructure.md). 결정 기록은 #369다.

## 맡는 책임

| 책임 | 내용 | 기존 Compose 담당(2026-10-30 제거) |
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
2. refresh token(14일)은 member-app이 Redis에 해시로 저장하고(`RedisRefreshTokenStore`) 쓸 때마다 회전한다. refresh 경로로만 가는 `HttpOnly; Secure; SameSite=Strict` 쿠키로 준다. 로그아웃하면 폐기한다.
3. 프론트는 access token을 메모리에 두고 `Authorization: Bearer`로 보낸다. 새로고침하면 시작할 때 refresh를 한 번 호출한다. Bearer 헤더는 브라우저가 자동으로 싣지 않으므로 API의 CSRF 보호(`X-XSRF-TOKEN`)는 뺀다.
4. Envoy가 서명을 검증한다. 위조·만료 토큰은 401로 거절하고, 토큰이 없는 요청은 헤더 없이 통과시킨다. 로그인이 필요한지는 각 앱의 Security 설정이 정한다. 그래서 비로그인 경로 목록을 Envoy와 앱 두 곳에 적지 않는다.
5. 앱은 `X-User-*` 헤더로 인증 객체를 만들고 `@PreAuthorize`로 인가한다(#371). 헤더 필터는 공용 모듈이 아니라 앱마다 안에 둔다. 지금 member·appearance(#395)·chat(#501)·lecture(#521)·customer-support(#540)가 쓰고, billing은 아직이다. 앱은 서명을 다시 검증하지 않으므로, NetworkPolicy로 앱의 외부 API 포트(8080)에는 Envoy만 접근하게 한다. Prometheus는 관리 포트(8082)로 수집한다(lecture #521, member·appearance·customer-support #528, chat #590). 앱 간 내부 API는 지금처럼 `X-Internal-Api-Key`로 인증한다. 이 구조를 고른 이유와 전제는 아래 [헤더 신뢰 구조](#헤더-신뢰-구조를-유지하는-이유-2026-11-02)에 있다.
6. 채팅: 브라우저의 WebSocket upgrade 요청에는 Bearer 헤더를 실을 수 없다. 그래서 chat-app은 JWT를 직접 검증하지 않고, Gateway가 검증한 REST 요청으로 일회용 연결 티켓을 발급해 웹소켓 연결을 그 티켓으로 인증한다(#501). 연결 만료는 chat SecurityPolicy가 `exp` claim을 넘기는 `X-User-Token-Exp`로 정한다([Gateway 운영 문서](../../deploy/gateway.md)). 그래서 JWKS를 쓰는 곳은 Gateway뿐이다. JWT는 URL query에 싣지 않는다(access log에 남는다). 연결 뒤 강제 종료는 회원 이벤트(#242)를 따른다.
7. 발급한 JWT는 회수할 수 없다. 정지·역할 변경은 다음 refresh에서 막히고, 최대 15분(access token 수명) 늦게 반영된다. 그래서 HP가 움직이는 경로(보류·반환·지급·환전)와 역할 조건이 있는 거래(출연 거래는 활동 중인 인플루언서 이상, `docs/policy.md`)는 토큰의 `role`만 믿지 않고, 각 앱이 복제한 회원 상태를 함께 확인한다(#371). 전역 차단 목록(blacklist)을 두지 않는 이유는 아래 [즉시 차단](#정지역할-변경의-즉시-차단)에 있다.
8. `docs/policy.md`의 "로그인 상태는 서버 세션으로 유지"와 "역할 변경은 다시 로그인하지 않아도 바로 반영"은 JWT 전환과 맞지 않는다. policy를 어떻게 고칠지는 팀이 정한다(#370).

OAuth 진행 상태(authorization request)는 HttpSession 대신 짧은 수명의 암호화 쿠키에 저장한다. 그래서 Spring Session과 그 저장소였던 `platform` database가 필요 없다.

### 헤더 신뢰 구조를 유지하는 이유 (2026-11-02)

PR #523 보안 리뷰에서 "앱이 서명 없는 헤더를 믿는다"는 지적을 받고 다시 판단했다. Gateway 뒤에서 사용자 정보를 서비스로 넘기는 방식은 보통 아래와 같다.

| 방식 | 동작 | 예 | 합방 |
| --- | --- | --- | --- |
| 신뢰 헤더 | Gateway가 토큰을 검증하고 회원 ID·역할을 일반 헤더로 넘긴다. 앱은 그대로 믿는다 | Envoy `claimToHeaders`, oauth2-proxy | **지금 구조** |
| 원래 토큰 전달 + 서비스마다 검증 | Gateway가 JWT를 그대로 넘기고 각 서비스가 JWKS로 다시 검증한다 | Spring `oauth2ResourceServer().jwt()` | 올릴 때의 후보 |
| 신뢰할 발급자가 서명한 내부 assertion | Gateway(또는 발급자)가 서명한 값을 넘기고 서비스가 서명을 확인한다 | Google IAP `x-goog-iap-jwt-assertion` | 쓰지 않음 |
| 토큰 교환(RFC 8693) | 서비스가 다음 서비스를 부를 때 그 대상 전용 토큰으로 바꾼다 | 서비스 간 위임이 깊은 조직 | 쓰지 않음 |
| 서비스 메시 | 사이드카끼리 mTLS로 서로를 확인하고 사이드카가 JWT도 검증한다 | Istio | 쓰지 않음 |

OWASP는 프록시가 넣은 헤더를 믿으려면 세 가지가 필요하다고 한다. 지금 상태는 다음과 같다.

| 조건 | 상태 |
| --- | --- |
| 클라이언트가 보낸 같은 이름의 헤더를 지운다 | ✅ `ClientTrafficPolicy`가 `x-user-id`·`x-user-role`·`x-user-token-exp`를 요청 맨 앞에서 지운다(`k8s/gateway/base/gateway.yaml`, `k8s/gateway/tests/run.py`가 검사) |
| 프록시를 거치지 않는 요청을 막는다 | ✅ NetworkPolicy가 8080에 Envoy pod만 들인다. 내부 포트(8081)는 `/api/v1/internal/**`만 받고, 그 경로의 보안 설정에는 헤더 필터가 없다 |
| 프록시를 인증한다 | ❌ 앱은 보낸 쪽이 Envoy인지 암호로 확인하지 않는다(mTLS 없음). NetworkPolicy가 대신한다 |

- **감수하는 위험**: 클러스터 안의 pod가 장악되어 앱 8080에 직접 닿으면 아무 회원(관리자 포함)으로 행세할 수 있다. 다만 그런 pod는 인증이 없는 Kafka·Redis에도 닿는다([Gateway 운영 문서](../../deploy/gateway.md)에서 이 규모에서는 감수하기로 함). 앱의 JWT 재검증만 더해서는 피해 범위가 크게 줄지 않는데, 바꾸려면 앱 6개에 JWT 검증을 넣고 Envoy가 원래 토큰을 앱까지 넘기게 해야 한다. 그래서 지금 구조를 유지한다.
- **8080에 들어오는 다른 pod를 없앤다**: Prometheus도 지표를 모으려고 8080에 들어왔다. 헤더를 믿는 앱은 actuator를 관리 포트(8082)로 옮기고 Prometheus는 그 포트만 쓴다(lecture #521, member·appearance·customer-support #528, chat #590, [관측성](observability.md)).
- **앱에서 JWT를 검증하는 구조로 올릴 때**: 아래 NetworkPolicy 확인이 실패할 때, 클러스터에 다른 서비스·사람이 늘어 "클러스터 안은 믿는다"는 가정이 약해질 때, 앱을 Gateway 밖(다른 진입점·클러스터)에서도 부르게 될 때.

근거: [OWASP Identity Propagation Patterns](https://cheatsheetseries.owasp.org/cheatsheets/Identity_Propagation_Patterns_Cheat_Sheet.html)(trusted proxy header: 클라이언트 헤더 제거, 프록시 인증, 우회 차단), [OWASP Microservices Security](https://cheatsheetseries.owasp.org/cheatsheets/Microservices_Security_Cheat_Sheet.html)(Gateway 검사는 서비스 쪽 통제를 보완할 뿐이다), [Google IAP signed headers](https://docs.cloud.google.com/iap/docs/signed-headers-howto)(IAP를 우회하면 서명 없는 헤더는 위조될 수 있어 서명된 JWT를 확인한다).

#### 확인할 전제: NetworkPolicy가 실제로 막는가

헤더 위조를 막는 마지막 방어선이 NetworkPolicy다([k3s 문서](k3s.md)). k3s 내장 network policy controller(kube-router)가 동작하는 것은 IMDS 차단으로 확인했다([aws-k3s.md](../../deploy/aws-k3s.md)). 아직 **Envoy가 아닌 pod에서 앱 8080으로의 접속이 막히는지**는 확인 기록이 없다.

- 확인 방법: `hapbang-prod`에 라벨 없는 임시 pod를 띄우고, Ready 뒤 15초 기다린 다음(kube-router가 새 pod에 규칙을 거는 동안은 막히지 않는다) `X-User-Id`·`X-User-Role`을 붙여 앱의 8080과 8082에 요청한다. 둘 다 연결되지 않아야 한다.
- 결과는 [k3s 문서](k3s.md)의 완료 증거로 남긴다. 연결되면 앱에서 JWT를 검증하는 구조로 올린다.

### 정지·역할 변경의 즉시 차단

- **지금**: refresh token은 Redis에서 폐기할 수 있다. 비밀번호를 바꾸면 그 회원의 refresh token을 모두 폐기하고(`MemberTokenUseCase.revokeAll`), 이용할 수 없는 회원(정지 등)은 재발급을 거부하면서 모두 폐기한다. 이미 받은 access token은 만료(최대 15분)까지 통한다.
- **access token까지 즉시 끊는 방법**: 토큰 ID(`jti`) 차단 목록, 또는 회원별 기준 시각(`iat`가 그보다 앞서면 거부)을 둔다. 지금 토큰에는 `jti`가 없다.
- **전역 차단 목록을 두지 않는 이유**: 요청마다 누군가 목록을 조회해야 한다. Envoy의 JWT 검증에는 목록 조회가 없어 외부 인가 서비스(ext_authz)를 요청마다 불러야 한다. 각 앱이 공유 Redis를 읽으면 다른 모듈의 저장소를 직접 쓰게 되어 모듈 경계(`AGENTS.md` 3장)에 맞지 않고, Redis 장애 때 통과·거부도 정해야 한다.
- **대신**: 7번처럼 중요한 경로(HP 이동, 역할 조건이 있는 거래, 관리자 기능)만 회원 이벤트로 갱신한 앱의 회원 복제본으로 다시 확인한다. 요청마다 다른 서비스를 부르지 않고, 지연은 Kafka 전달 시간이다. 모듈 경계를 지키는 차단 목록이다.

## rate limit — 각 앱의 Resilience4j

- 대상: 로그인, HP 충전 요청, 고객센터 RAG 질문처럼 남용되면 비용이 드는 경로. 요청 안에서 다른 앱을 동기로 부르는 경로도 넣는다(강의 주문·구매 취소, 강의 D80). 내부 API(`/api/v1/internal/**`)와 관리자 API는 넣지 않는다. 내부 API는 회원이 아니라 앱이 부르므로 부르는 앱의 circuit breaker와 그 앞단 사용자 한도로 막는다
- key: `X-User-Id`가 있으면 회원 ID, 없으면 client IP
- client IP 신뢰 경계: Envoy 앞에는 신뢰할 proxy가 없으므로 `ClientTrafficPolicy`의 `X-Forwarded-For` 신뢰 hop 수는 0으로 둔다. Envoy는 실제 접속 주소를 `X-Forwarded-For` 맨 뒤에 붙인다. 앱(`server.forward-headers-strategy=native`, Tomcat `RemoteIpValve`)은 오른쪽부터 읽어 cluster 내부 주소가 아닌 첫 주소를 client IP로 쓴다. 그래서 클라이언트가 앞쪽에 넣은 값은 무시된다. Envoy가 실제 client IP를 보는지(ServiceLB와 `externalTrafficPolicy: Local`)는 설치 뒤 access log로 확인한다
- client IP rate limit을 구현할 때 Envoy가 실제 공인 IP를 보는지 다시 확인한다. Envoy가 private 주소(로컬 k3d의 docker 대역, SNAT된 node 주소)를 보면 Tomcat이 그 주소도 proxy로 건너뛰어 클라이언트가 넣은 값을 읽는다. 그때는 Envoy에서 `X-Forwarded-For`를 실제 접속 주소로 덮어쓰는 방식을 검토한다. 앱 코드에 client IP rate limit이 아직 없어 #372에서는 넣지 않았다. 처음 넣은 곳은 auxiliary-app 강의 검색(#883)이다. #726 운영 접근 로그와 2026-11-12 client IP 조사로 Envoy가 공인 IP를 보는 것을 확인했고, 앱은 `request.getRemoteAddr()`만 쓴다(`X-Forwarded-For`·`X-Real-IP`·`X-Envoy-External-Address`를 읽지 않음). 공인 주소가 아니면 IP 한도를 건너뛰고 WARN·지표를 남긴다([검색 모듈](../../search/module.md) 14장)
- 한도를 넘으면 429를 `ProblemDetail`로 응답한다
- RateLimiter는 앱 프로세스 메모리에서 센다. 앱마다 replica가 1개라 앱 하나의 한도가 곧 전체 한도다. replica를 늘리면 한도도 replica 수만큼 늘어나므로, 그때 공유 저장소 방식을 다시 판단한다
- 예외: 회원 앱의 이메일 인증 제한(발송: 같은 이메일 1분, IP 1시간 10통, 이메일 하루 5통, 전체 하루 300통 / 코드 확인: IP 1시간 30번, #557)은 Redis로 센다. 이유: (1) 하루 단위 상한이 배포·재시작 뒤에도 유지돼야 한다. Resilience4j는 pod 메모리에서 세어 배포(dev 병합)마다 0이 된다. (2) 세는 단위가 이메일·IP마다 따로라 키가 끝없이 늘어나는데, Redis는 키마다 TTL로 저절로 지워지고 Lua로 원자적으로 센다. (3) Redis는 이미 쓰고 있어(refresh token, 채팅) 새 인프라가 아니다. client IP는 위 신뢰 경계대로 읽고, IPv6는 /64 단위로 센다. 운영에서 진짜 client IP가 오는지는 병합 전에 확인한다
- 구현은 쓰는 앱에 먼저 두고, 두 앱 이상이 같은 코드를 쓰면 `common`으로 옮길지 판단한다. 지갑(#627)과 강의(#423)가 같은 방식의 `global.ratelimit`를 각자 두고 있고, 옮길지는 #697에서 판단한다
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
