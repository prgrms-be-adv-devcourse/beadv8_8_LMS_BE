# Envoy Gateway (#372)

상태: EC2에 Envoy Gateway·cert-manager를 설치하고 외부 진입점을 전환했다(2026-10-30, #372). ServiceLB → Envoy가 80·443을 받고 Let's Encrypt production 인증서를 사용한다. FE `web`은 `/` route에 연결됐고, 이 route는 FE 레포 `k8s/`가 소유한다(#454). member-app은 JWT 발급(#370)과 함께 route·JWT 검증을 연결했다(2026-11-02 #500). 같은 날 chat-app(#545)과 billing·appearance·lecture·customer-support(#541)도 사용자 헤더 인증 이미지로 route·JWT 검증을 연결해, 업무 앱 6개가 모두 열렸다([릴리스 전 배포](k8s.md#릴리스-전-배포)). 이후 업무 앱은 dev 병합 때 자동으로 배포된다(#559). 이 문서의 명령은 별도 표시가 없으면 로컬 `k3d-hapbang`만 대상으로 한다.

## 결정 기록 (2026-10-29)

#372 계획에서 사용자에게 확인한 결정이다.

- 내부·외부 API를 실제 listener로 분리한다. 외부 8080은 Envoy만, 내부 8081은 앱 pod끼리만 접근한다. 호출 관계별 허용 목록은 두지 않는다. 같은 내부 API 키를 쓰는 앱 7개 사이에서 목록이 막는 경로가 작고, 호출을 더할 때마다 제공 앱 정책을 고쳐야 했기 때문이다. 내부 API 키도 앱 7개가 같은 값을 쓴다. 제공 앱별 키로 호출 관계를 Secret에서 제한하는 방식은 실서비스가 아닌 이 규모에서 과하다고 보고 두지 않는다. Service 포트 이름만 나누는 방식은 쓰지 않는다.
- Grafana는 prod 도메인의 `/grafana/`에서 자체 계정 로그인으로 공개한다. 익명 접근·가입·auth proxy는 끈다. NodePort·3000 공개는 하지 않는다.
- 이번 작업은 manifest·앱 포트 분리·로컬 검증·운영 전환 절차까지다. 실제 EC2 진입점 전환과 운영 검증 전에는 #372를 완료 처리하지 않는다.
- 티켓 범위를 넘는 장치는 두지 않는다. 업로드 메서드·형식·크기 검사는 앱이 원본이고 Envoy는 timeout만 늘린다. platform에는 ingress NetworkPolicy를 두지 않는다. `run.py`는 완료 조건만 검사한다.
- 앱 manifest는 각 앱의 `k8s/`에 둔다. 공통 Gateway, platform 접근 정책, Grafana 설정은 `k8s/gateway/`에 둔다. Alloy 설정(Gateway OTLP trace 수신 포함)은 pod log pipeline과 함께 `k8s/platform/base/observability.yaml`로 옮겼다(#480). 앱이 루트 manifest를 참조하지 않는다.

## 변경 결정 (2026-10-30)

- 사용자는 기존 배포 서버와의 호환을 제외하고 Gateway 설치·전환을 먼저 진행하도록 지시했다. 공용 Gateway·TLS·관측성 경로를 먼저 적용하고 업무 앱·FE 연결은 후속 작업으로 남긴다. 기존 DB·PVC는 삭제하지 않는다.
- 같은 날 진입점 전환 뒤 Compose(prod·dev app·web·PostgreSQL, edge nginx·certbot)를 볼륨까지 지웠다(#440). 기존 운영 DB에는 필요 없는 데이터만 있어 백업하지 않았다. 그래서 아래 [진입점 복구](#진입점-복구)의 nginx 복구 경로는 없어졌다.
- `--gateway-only`는 앱 overlay와 FE의 `web` HTTPRoute를 제외한다. JWT 발급 앱이나 FE Service가 없어도 공용 진입점을 준비할 수 있다. `/`는 FE 배포 때 연결하며 그 전에는 404다. (#500에서 렌더러가 Gateway 전용이 되어 이 플래그가 없어졌고, #507에서 렌더러를 지우며 BE의 `web` route 정의도 지웠다.)
- 기본 렌더링은 인증서 연락 이메일을 요구한다. 기존 Compose는 이메일 없이 등록한 구성이며 설치된 ACME CRD에서 이메일은 선택 항목이다. 이번에는 연락처 답변을 기다린 뒤 `--without-acme-email`로 명시적으로 생략했다. 연락처가 정해지면 issuer의 `spec.acme.email`에 추가할 수 있다. 이메일을 지정한 상태에서 생략 플래그를 함께 쓰면 실패한다. (#507부터는 prod overlay의 ClusterIssuer에 email이 없다.)

## JWT issuer 결정 (2026-11-02, #500)

#370(PR #496) 병합 뒤 member-app 첫 배포를 준비하며 사용자에게 확인한 결정이다.

- 운영 issuer는 `https://hapbang.duckdns.org`(Gateway 호스트)다. 원본은 각 앱의 prod overlay다. 앱 7개 prod overlay가 SecurityPolicy issuer를 patch하고, member prod overlay가 같은 값을 `HAPBANG_JWT_ISSUER`(발급하는 토큰의 `iss`)로 넣는다. chat-app이 STOMP에서 직접 검증할 때(#371)도 같은 이름·값을 쓴다.
- 앱 배포(`.github/scripts/k3s-deploy.sh`)는 overlay를 그대로 적용하고 렌더러를 거치지 않는다. 그래서 issuer를 배포 입력으로 주입하지 않고 overlay에 둔다. 공용 Gateway의 운영 값도 #507에서 prod overlay로 옮기고 렌더러(`render-prod.py`)를 지웠다([운영 Gateway 값](#운영-gateway-값-507)).
- 발급과 검증의 issuer가 다르면 그 앱의 토큰 요청이 모두 401이다. CI `manifests` job의 `k8s/gateway/tools/check-prod-host.py`가 prod overlay 7곳(SecurityPolicy 6, member ConfigMap 1)이 `https://<Gateway 호스트>`인지, Envoy가 JWKS를 member 8080에서 읽는지 검사한다. 새 앱을 더하면 prod overlay에 같은 patch를 넣는다.
- base와 local overlay는 `https://gateway.invalid`(member 기본값과 같음)를 그대로 둔다. 로컬·k3d는 서명 키가 달라 운영 토큰을 받아들이지 않는다.
- 서명은 #369대로 RS256 + JWKS다. member-app 하나만 개인 키를 갖고, Envoy는 JWKS로 공개 키만 받는다. 공유 비밀값(HS256)은 쓰지 않는다. Envoy v1.9.3의 `localJWKS`는 inline·ConfigMap만 받아 Secret이 아닌 곳에 비밀값을 두게 되고, 검증하는 곳(Envoy·chat)마다 모든 회원·ADMIN 토큰을 위조할 수 있는 키가 퍼지기 때문이다.

## Swagger 공개 결정 (2026-11-02, #498)

- 운영에서도 Swagger를 누구나 보게 연다. dev 서버가 없어 FE팀과 리뷰어가 운영 API를 명세로 확인한다. 레포가 public이라 명세 내용은 이미 공개돼 있고, Swagger를 막아도 API 호출은 막히지 않는다.
- Swagger는 각 앱의 모듈 경로 아래(`/api/v1/<모듈>/swagger-ui.html`, `/api/v1/<모듈>/v3/api-docs`)에 두어 기존 앱 HTTPRoute가 받는다. Gateway route는 더하지 않는다. 루트 `/swagger-ui`·`/v3/api-docs`는 `api-not-found`로 계속 404다. 앱마다 같은 루트 경로를 써서 한 도메인에서 겹치기 때문이다.
- 강의는 2026-11-02 API route보다 Swagger를 먼저 열려고, Swagger 경로의 GET만 받는 `lecture-app-swagger` route를 잠시 운영에 두었다(#541). 같은 날 billing-app을 배포하고 강의 API route를 열면서 지웠다. 지금은 6개 앱 모두 위 원칙대로 앱 HTTPRoute가 Swagger를 받는다.
- 쓰지 않은 안: `/swagger/<앱>/` route와 URLRewrite(앱이 `forward-headers-strategy: native`라 `X-Forwarded-Prefix` 처리를 더해야 한다), 한 앱이 6개 명세를 모으는 단일 진입점(그 앱이 문서 서버가 된다), CI가 만든 정적 명세(다른 도메인이라 Try it out이 CORS로 막힌다).
- 내부 API도 명세에 남는다. 외부에서 호출하면 Gateway에 route가 없어 404다(앱의 내부 포트 검사도 막는다).
- 앱 설정과 Authorize 사용법은 [API 규칙](../api/conventions.md#swagger)에 있다.

## 구성과 적용 순서

| 경로 | 책임 |
| --- | --- |
| `k8s/gateway/install/envoy-gateway` | 공식 HelmChart v1.9.3, controller medium 128Mi, Gateway API·Envoy CRD |
| `k8s/gateway/install/cert-manager` | 공식 HelmChart v1.21.3, large의 controller·cainjector 각 128Mi, webhook 50Mi(#532에서 medium에서 옮김) |
| `k8s/gateway/overlays/local` | `hapbang.local`, 로컬 self-signed TLS. trace sampling은 base와 같은 100%다(#679 전에는 로컬만 100%) |
| `k8s/gateway/overlays/prod` | 운영 호스트(`hapbang.duckdns.org`)·Grafana 주소, Let's Encrypt staging·production issuer(연락 이메일 없음). sampling은 base의 100%(운영 도구 route는 0%, #679)를 그대로 쓴다. 운영과 같은 값이라 그대로 적용한다 |
| `apps/<앱>/k8s/base/gateway.yaml` | 모듈 HTTPRoute·optional JWT·본문 제한·업로드·WebSocket |
| `apps/<앱>/k8s/base/networkpolicy.yaml` | 앱 ingress와 IMDS 접근 제한 |

Helm CLI로 설치하지 않고 k3s helm-controller가 설치한다. Envoy chart의 CRD·controller 준비 뒤 cert-manager를 설치해야 한다. cert-manager는 시작 시 Gateway API를 탐지하므로 순서를 뒤집지 않는다. CRD 설치 전에 Gateway·Certificate·정책을 한 번에 apply하지 않는다.

```bash
# 먼저 docs/deploy/k8s.md의 cluster·platform을 준비한다.
bash k8s/gateway/tools/install-local.sh
```

스크립트는 Envoy → CRD 대기 → cert-manager → webhook 대기 → local Gateway 순서로 적용한다. local server node에만 ServiceLB label을 붙인다. EC2 context는 받지 않는다. 기존 로컬 cluster와 데이터는 삭제하지 않는다.

Gateway와 TLS는 `envoy-gateway-system`, Grafana route는 `hapbang-platform`, 앱 route는 `hapbang-prod`에 있다. Gateway listener의 허용 namespace도 이 셋으로 제한한다. 각 route의 `Accepted`·`ResolvedRefs`와 Gateway `Programmed`를 확인한다. `/` route(`HTTPRoute web` → `web:80`)는 FE 레포 `k8s/`가 Service와 함께 소유한다(#454). BE Gateway overlay만 적용한 로컬 cluster에서는 `/`가 404이고, FE local overlay를 적용하면 연결된다.

## 인증과 내부 포트

`hapbang.internal-api.port`(환경변수 `HAPBANG_INTERNAL_API_PORT`)가 없으면 기존 단일 포트 동작을 유지한다. k8s는 8081을 설정한다. 잘못된 범위 또는 외부와 같은 포트면 기동 실패다.

분리 모드에서 Tomcat이 정규화·decode한 경로와 실제 수신 포트를 비교한다. 외부 포트의 `/api/v1/internal`·하위 경로는 404, 내부 포트의 나머지도 404다. forwarded 헤더는 포트 판단에 사용하지 않는다. 내부 API는 계속 `X-Internal-Api-Key`와 기존 전용 SecurityFilterChain을 사용한다. 앱 간 동기 호출의 transaction·보상 정책은 바꾸지 않는다.

앱 7개의 pod에는 `hapbang.io/internal-api-client: 'true'` label을 붙인다. 모든 앱의 8081은 이 label을 가진 pod만 허용한다. 새 앱은 자기 Deployment에 label을 붙이면 다른 앱의 내부 API를 부를 수 있다. 현재 chat·customer-support의 8081을 부르는 앱은 없다. 앱 egress는 IMDS만 막으므로 8081 접근 통제는 제공 앱의 ingress NetworkPolicy가 맡는다.

NetworkPolicy는 HTTP path를 구분하지 못한다. 그래서 두 실제 listener와 앱의 경로 검사를 함께 둔다. 같은 namespace라는 이유로 앱의 8080을 열지 않는다. Prometheus는 앱의 관리 포트(8082, `MANAGEMENT_SERVER_PORT`)에서 수집하고 NetworkPolicy는 그 포트만 Prometheus에 연다(lecture #521, member·appearance·customer-support #528, chat #590, [관측성](../architecture/infrastructure/observability.md)).

JWT는 업무 API route에만 적용한다. Grafana는 Grafana의 자체 로그인, FE는 정적 서버다. `optional: true`로 토큰이 없는 요청만 허용하고, 잘못된 토큰은 거절한다. `failOpen`은 설정하지 않는다. v1.9.3 CRD는 값이 false여도 optional과 failOpen 필드의 동시 설정 자체를 거절한다.

`ClientTrafficPolicy.earlyRequestHeaders`는 인증 전에 외부 `X-User-Id`, `X-User-Role`, `X-User-Token-Exp`, `X-Internal-Api-Key`와 전달 프로토콜·호스트 위조 헤더를 제거한다. 검증한 claim만 사용자 헤더로 생성한다. XFF의 신뢰 hop은 0이며 Envoy가 실제 접속 주소를 오른쪽에 추가한다. 앱은 native RemoteIpValve로 읽는다.

**실제 client IP (2026-11-06 운영 확인, #726)**: 앱이 받는 remote address는 사용자의 공인 IP다. 그래서 비회원 요청의 IP rate limit을 앱에서 걸 수 있다.

- ServiceLB가 `externalTrafficPolicy: Local`로 접속 주소를 보존한다. Envoy pod는 ServiceLB와 같은 large node에 있다. 그때 Envoy 접근 로그 2,000줄의 접속 주소는 모두 공인 IP였고(서로 다른 주소 20개), 개발자 PC에서 보낸 요청은 그 PC의 공인 IP로 남았다.
- Envoy는 접속 주소를 XFF 맨 오른쪽에 붙여 앱으로 보낸다. 앱의 RemoteIpValve는 Envoy pod 주소(10.42.x, 내부 proxy)를 건너뛰고 그 값을 쓴다. 클라이언트가 보낸 XFF는 그 왼쪽에 남으므로 앱의 remote address를 바꾸지 못한다(로컬 `run.py` 5번).
- **Envoy 접근 로그의 `client_ip`는 위조된다.** 운영에서 `X-Forwarded-For: 198.51.100.77`을 붙인 요청은 `client_ip`가 198.51.100.77로 남았다. v1.9.3은 `numTrustedHops: 0`을 XFF 원래 IP 감지 확장(`use_remote_address: false`, `xff_num_trusted_hops: 0`)으로 바꾸고, Envoy는 이때 들어온 XFF의 맨 오른쪽 값을 client로 본다(XFF가 없으면 접속 주소). 앱으로 가는 XFF에는 실제 접속 주소가 붙으므로 앱은 영향이 없다. 로그로 사용자를 추적할 때는 이 값을 믿지 않는다.

prod issuer는 [JWT issuer 결정](#jwt-issuer-결정-2026-11-02-500)을 따른다. Gateway는 member Service 8080의 `/api/v1/members/.well-known/jwks.json`에서 공개 JWKS를 직접 읽는다. chat-app은 JWT를 직접 검증하지 않는다(#501). 웹소켓은 Gateway가 검증한 REST 요청으로 받은 일회용 연결 티켓으로 인증하고, 연결 만료는 chat SecurityPolicy가 `exp` claim을 넘기는 `X-User-Token-Exp`(epoch 초)로 정한다(chat-app이 발급 뒤 15분으로 상한을 둔다). member 8080을 chat에 열지 않는다. 8080은 Envoy가 넣은 `X-User-Id`를 믿는 포트라 열면 chat pod가 아무 회원으로나 위장할 수 있다.

Gateway는 토큰을 `Authorization: Bearer` 헤더에서만 읽는다(`extractFrom.headers`, 2026-11-02 #508). `extractFrom`을 비워 두면 Envoy 기본값이 `access_token` query도 받는다. 그러면 토큰이 URL에 실려 브라우저 기록·Referer에 남을 수 있다. 토큰을 query로 보내는 클라이언트는 없다(chat 웹소켓은 JWT가 아니라 일회용 연결 티켓을 `ticket` query로 보낸다, #501). CI `check-prod-host.py`가 앱 7개 prod overlay의 JWT provider가 이 값인지 검사한다. 새 앱도 같은 `extractFrom`을 둔다.

## 본문·연결·Grafana·trace

- 일반 업무 route만 `requestBuffer`로 1MiB 상한을 적용한다. 일반 요청 timeout은 30초다.
- 파일 content 경로는 regex와 `PUT` 메서드로 분리해 timeout만 늘린다. 다른 메서드는 일반 route(1MiB·30초)로 간다. 업로드에는 requestBuffer·retry를 적용하지 않는다. 전체 timeout은 30분, stream idle은 60초다. Content-Type·Content-Length·크기 검사는 앱이 한다. Envoy에서 같은 검사를 다시 하지 않는다. `ClientTrafficPolicy`의 `requestReceivedTimeout`은 헤더뿐 아니라 본문까지 받는 시간이라 2GiB 업로드를 위해 0s(해제)로 둔다. 아무것도 보내지 않고 연결만 잡는 요청은 idle·stream idle 60초가 끊는다.
- WebSocket은 별도 route에서 전체 timeout을 해제하고 stream idle 5분을 둔다. STOMP heartbeat·CONNECT JWT는 #371에서 처리한다.
- 알 수 없는 `/api/**`, actuator, 루트의 Swagger·api-docs(`/swagger-ui`, `/v3/api-docs`)는 앱/React backend로 보내지 않고 404다. Swagger는 앱의 모듈 경로 아래에서 그 앱의 route로 연다(#498). 인증된 API도 실제 앱의 인가·권한 검사가 필요하다.
- `/grafana`는 `/grafana/`로 redirect한다. prefix는 지우지 않는다. Grafana `root_url`, `serve_from_sub_path`, Secure cookie를 설정한다. admin 자격 증명은 기존 chart Secret을 사용하고 레포·로그에 남기지 않는다.
- Grafana는 320Mi 한도에서 subpath 변경 후 기동 시 높은 CPU·probe timeout이 관찰되어 384Mi와 `GOMAXPROCS=2`를 적용했다. 운영 3일 최대 working set이 374Mi(97%)라 448Mi로 올렸다(#532). 이 64Mi 증가는 #343의 medium 목표 예산 조정에 포함한다. 448Mi에서도 운영 7일 최대 418Mi(93%)로 GOMEMLIMIT(403MiB)을 넘어 512Mi로 올렸다(#608).
- Grafana는 SQLite PVC를 공유하는 새·옛 pod가 함께 뜨지 않도록 `Recreate`로 갱신한다. probe는 `/grafana/api/health`, startup은 최대 5분을 기다린다. 로그인 화면·정적 자원은 익명에게 제공되고 dashboard·데이터 API는 로그인이 필요하다.
- Kafka UI(Redpanda Console)는 `/kafka/`에서 Envoy Basic Auth(`SecurityPolicy` basicAuth, SHA htpasswd)로 연다. Console 무료판에는 로그인이 없기 때문이다. 인증이 없으면 401과 `WWW-Authenticate: Basic`을 돌려줘 브라우저 로그인 창이 뜬다. route가 `X-Forwarded-Prefix: /kafka`를 넣어 Console이 하위 경로로 동작한다. 계정은 `admin` 하나이고 prod 비밀번호는 만든 사람의 `~/.hapbang/kafka-ui-password`에만 있어 팀 비밀 보관소로 공유한다. 바꿀 때는 새 비밀번호의 SHA htpasswd를 kubeseal로 다시 봉인해 `overlays/prod/kafka-ui-basic-auth.yaml`을 바꾼다. Basic Auth에는 로그아웃·시도 횟수 제한·사용자 구분이 없다.
- `/kafka/`에서는 토픽 생성이 HTTP 405로 실패한다(#484). 토픽 생성이 필요하면 port-forward로 연 Console에서 한다([관측성 접속](k8s.md#관측성-접속)).
  - 원인: Console 프론트엔드가 Connect RPC 요청(`/redpanda.api.dataplane.v1.TopicService/CreateTopic` 등)을 base path 없이 루트로 보낸다. connect-query용 transport를 `getGrpcBasePath('')`로 만드는데, `overrideUrl ?? getBasePath()`는 빈 문자열을 base path로 바꾸지 않는다(upstream `frontend/src/app.tsx`, v3.13.1과 2026-11-03 master가 같다). 같은 증상의 upstream 이슈가 열려 있다([redpanda-data/console#2041](https://github.com/redpanda-data/console/issues/2041)). 그래서 요청이 `/` route(FE `web`)에 닿고, 정적 파일 서버인 Caddy가 POST를 거절한다. Envoy도 Console도 거절하지 않는다. 서버가 넣는 `window.BASE_URL`은 `/kafka/`로 정상이라 Console 설정으로는 고칠 수 없다.
  - REST(`./api`, 상대 경로)를 쓰는 기능은 `/kafka/api/...`로 가서 동작한다. 토픽 목록 조회와 토픽 삭제(`DELETE ./api/topics/{이름}`)가 REST다. 삭제는 번들 코드로만 확인했고 직접 실행하지 않았다.
  - 확인 방법: 인증 없이 `curl -s -o /dev/null -D - -X POST https://<도메인>/redpanda.api.dataplane.v1.TopicService/CreateTopic`을 보내면 405와 `server: Caddy`, `allow: GET, HEAD`가 돌아온다. 같은 요청을 `/kafka/` 아래로 보내면 Basic Auth의 401이다(2026-11-03 prod).
  - 결정(2026-11-03, #484): 고치지 않는다. 토픽은 발행 앱이 `NewTopic`으로 선언하므로([Kafka](../architecture/infrastructure/kafka.md)) UI에서 토픽을 만들 일이 드물다. 검토한 대안은 둘이다. 하나는 `kafka-ui` route에 `^/redpanda\.api\.[^/]+/[^/]+$` 정규식 rule을 더해 Console로 보내는 것이다. Envoy의 Basic Auth realm은 요청 URL이라, `/kafka/` 밖 경로에서 브라우저가 로그인 창을 다시 띄울 수 있다. 다른 하나는 `kafka.<도메인>` 서브도메인에 Console을 루트 경로로 여는 것이다. HTTPS listener와 인증서가 하나씩 더 필요하다. upstream이 고치면 다시 본다.
- Tempo는 trace 수신·조회 부하에서 기존 64Mi로 OOMKilled되어 128Mi·GOMEMLIMIT=112MiB·GOMAXPROCS=2를 적용했다. 운영 3일 최대 working set이 115Mi(90%)라 160Mi·GOMEMLIMIT=136MiB로 올렸다(#532). Envoy proxy는 운영 최대 24Mi라 256Mi에서 128Mi로 낮췄다(#532). 이 조정과 Grafana 증가분, Envoy 종료 sidecar 32Mi를 [예산 표](../architecture/infrastructure/k3s.md)에 반영한다.
- Envoy JSON access log는 query·본문·Authorization·쿠키를 기록하지 않는다. W3C trace를 Alloy OTLP 4317 → Tempo 4317로 전송한다. dashboard·앱 전체 계측·alert는 #343이다.
- platform(PostgreSQL·Kafka·Redis·Elasticsearch·관측성)에는 ingress NetworkPolicy를 두지 않는다. 외부에서는 닿지 않고, 막아 주는 경우는 cluster 안 pod가 먼저 뚫린 상황뿐이라 실서비스가 아닌 이 규모에서는 감수한다. 인증이 없는 Kafka·Redis도 같다. 앱 이름 허용 목록은 앱을 더할 때마다 고쳐야 하고 빠뜨려도 CI가 잡지 못한다(#374). `deny-metadata-egress`는 namespace의 모든 pod에 적용되므로 이 구성 요소들도 IMDS egress는 막힌다. k3s(kube-router)는 새 pod에 규칙을 거는 데 몇 초가 걸려 그동안은 IMDS가 막히지 않는다(EC2 실측, #374). metadata egress는 S3를 쓰는 member·appearance·lecture에만 허용한다. node 자체나 hostNetwork pod는 일반 NetworkPolicy의 신뢰 경계 밖이다.

## 로컬 Envoy (#508)

`docker/compose.yaml`의 `envoy`는 k3d 없이 로컬 앱 앞에서 이 문서의 JWT 검증·사용자 헤더 전달을 재현한다. 설정은 `docker/envoy/envoy.yaml`(정적 Envoy 설정)이다. 이미지는 Envoy Gateway v1.9.3이 띄우는 Envoy와 같은 `envoyproxy/envoy:distroless-v1.39.5`다. 사용법은 [로컬 실행](../dev/local.md#로그인과-사용자-헤더)에 있다.

운영과 같게 둔 것:

- 경로별 route. 호스트에서 실행한 앱의 기본 포트 8081~8087(auxiliary-app 8087)로 보낸다
- optional JWT(토큰이 없으면 통과, 잘못된 토큰은 401), `sub`·`role` → `X-User-Id`·`X-User-Role`, `exp` → `X-User-Token-Exp`(chat SecurityPolicy와 같다. 로컬은 provider가 하나라 모든 앱 route에 붙지만 chat-app만 읽는다), 토큰은 `Authorization: Bearer`에서만 읽기, 검증한 토큰을 앱에 그대로 넘기기(`forward`)
- `earlyRequestHeaders` 제거(`x-user-token-exp` 포함), XFF 신뢰 hop 0, 경로 정규화
- 일반 route 30초·본문 1MiB, 업로드 `PUT` 30분, WebSocket 전체 timeout 해제·stream idle 5분, idle·stream idle 60초, 나머지 경로 404(내부 API 포함)

일부러 다르게 둔 것:

- TLS·HTTPS redirect·access log·tracing·Grafana·Kafka UI·FE route는 없다. FE는 Vite dev server가 맡는다.
- JWKS를 시작할 때 받지 않고 토큰이 처음 올 때 받는다(운영은 `async_fetch`). member가 늦게 떠도 Envoy가 바로 포트를 열어야 다른 앱의 spring-boot-docker-compose 기동 대기가 timeout으로 실패하지 않는다.
- JWKS 캐시는 5초다(운영 300초). 로컬 member는 `JWT_PRIVATE_KEY`가 없으면 재시작마다 임시 서명 키를 만들고, Envoy는 모르는 kid가 와도 JWKS를 다시 받지 않는다.
- 서명 키는 운영과 달라도 된다. 운영과 같아야 하는 것은 검증 동작이다.

결정(2026-11-02, #508): 매일 개발에 k3d 전체(Docker 14GiB·이미지 빌드)는 과하다. 앱에 local 전용 JWT 검증 필터를 두면 운영과 다른 인증 경로가 생긴다. 그래서 Envoy 컨테이너 하나로 재현한다. 원본 manifest와 따로 쓰는 설정이라 어긋날 수 있다. Gateway 정책(`k8s/gateway/base/gateway.yaml`, `apps/*-app/k8s/base/gateway.yaml`)이나 Envoy Gateway 버전을 바꾸면 `docker/envoy/envoy.yaml`도 같이 고친다. CI로 비교하지는 않는다. 설정 문법은 `docker run --rm -v "$PWD/docker/envoy/envoy.yaml:/etc/envoy/envoy.yaml:ro" envoyproxy/envoy:distroless-v1.39.5 --mode validate -c /etc/envoy/envoy.yaml`로 확인한다.

## 로컬 검증 재현

Docker Desktop은 기존 2노드 검증 기준인 14GiB 이상을 사용한다. 검증용 backend는 운영 앱 이미지에 포함하지 않는다. 실행은 프로젝트 루트에서 한다.

```bash
UV_CACHE_DIR=/tmp/gateway-uv uv venv /tmp/gateway-test-venv
UV_CACHE_DIR=/tmp/gateway-uv uv pip install --python /tmp/gateway-test-venv/bin/python \
  PyYAML==6.0.3 cryptography==50.0.2 websockets==17.3

docker build -t hapbang/gateway-test:372 k8s/gateway/tests
k3d image import -c hapbang hapbang/gateway-test:372
/tmp/gateway-test-venv/bin/python k8s/gateway/tests/run.py --large-upload
```

`run.py`는 #372 완료 조건 7개와 query 토큰을 읽지 않는지(#508)만 검사한다. 2GiB 업로드는 `--large-upload`를 줄 때만 실행한다. 테스트는 테스트 전용 pod·route·policy를 만들고 종료 때 자기 label의 자원만 지운다. 기존 앱 Deployment·Service를 테스트 대역으로 바꾸지 않는다. 생성한 RS256 개인 키는 테스트 프로세스 메모리에만 둔다. 2GiB 본문은 1MiB chunk로 생성하며 디스크에 만들지 않는다.

앱 검증은 `./gradlew verifyModuleStructure --no-daemon` 뒤 `./gradlew check bootJar --parallel --no-daemon`로 한다. 구조 검사는 다른 프로젝트 설정을 조회하므로 parallel과 함께 실행하지 않는다. CI의 manifests job은 고정 버전 upstream CRD로 schema를 만들고 모든 overlay를 `kubeconform -strict`로 검사한다. CEL admission 제약·정책 충돌은 schema만으로 검증되지 않으므로 실제 k3d 검증이 필요하다.

## 운영 Gateway 값 (#507)

`k8s/gateway/overlays/prod`가 운영 값의 원본이다. base의 `gateway.invalid`를 prod overlay가 운영 호스트로 바꾼다(Gateway listener 2곳·Certificate patch, `grafana.yaml`의 `root_url`). ClusterIssuer에는 연락 이메일을 두지 않는다. 2026-10-30에는 렌더러(`render-prod.py`)가 적용할 때마다 이 값을 넣었고, 2026-11-02에 overlay로 옮기며 렌더러를 지웠다. 운영 호스트는 앱 prod overlay의 JWT issuer(#500)에 이미 들어가 있어 Gateway만 자리 표시 값으로 둘 이유가 없었다.

- CI `manifests` job의 `k8s/gateway/tools/check-prod-host.py`가 prod 결과에 `.invalid`가 남지 않았는지, Gateway·Certificate·Grafana가 한 호스트인지, 앱 JWT issuer가 `https://<그 호스트>`인지 검사한다. 호스트를 바꾸면 앱 issuer도 같이 바꿔야 CI를 통과한다.
- FE `/` route(`HTTPRoute web`)는 FE 레포 `k8s/`가 소유한다(#454). BE overlay에는 두지 않는다. 로컬 k3d에서 `/`가 필요하면 FE local overlay를 적용한다.
- Gateway·platform은 수동으로 적용한다. 적용 전에 운영과 비교하고, 바뀐 리소스만 고른다. Grafana HelmChartConfig처럼 재시작을 부르는 리소스는 의도한 변경일 때만 적용한다.

```bash
kubectl --context hapbang-k3s diff -k k8s/gateway/overlays/prod
```

2026-11-02(#507) 기준 위 diff는 차이가 없다(exit 0). #534가 더한 `x-user-token-exp` 제거는 같은 날 chat-app route를 열기 전에 `ClientTrafficPolicy hapbang`만 골라 적용했다(#545). 적용 뒤 위 diff는 다시 차이가 없다(exit 0). 업무 앱 manifest(route·JWT issuer 포함)는 앱 prod overlay가 원본이고 앱 배포(`k3s-deploy.sh`)가 적용한다. 아직 세션을 쓰는 앱을 Gateway 헤더 인증이 완료된 앱처럼 배포하지 않는다.

### 전체 서비스 전환 조건과 순서

아래는 업무 앱·FE를 포함한 전체 서비스 전환 절차다. Gateway 설치·진입점 전환은 위의 2026-10-30 결정에 따라 먼저 완료했다. Compose도 지웠으므로(#440) 2~4의 nginx·Compose 단계와 7은 더 해당하지 않는다. 인증·업무 앱의 완료 조건은 그대로 남는다.

1. #370·#371 JWT/인가, #280 독립 기동(2026-11-02 완료), #338 데이터 이전·복구, #374 앱·FE 이미지와 Secret 준비를 확인한다. medium의 목표 구성 예산 초과는 #343에서 조정하고 sidecar·설치 Job·rolling 여유도 포함해 재측정한다.
2. nginx·기존 인증서·Compose와 k3s config를 백업하고 중단 시간을 팀에 공지한다. 기존 서비스·DB는 지우지 않는다.
3. Gateway controller·cert-manager·정책을 준비하되 edge nginx가 80·443을 쓰는 동안 ServiceLB를 켜지 않는다.
4. nginx를 먼저 멈춘다. large에만 `svccontroller.k3s.cattle.io/enablelb=true` label을 붙이고 k3s config에서 `servicelb` disable을 제거하고 `traefik`을 disable한 뒤 재시작한다.
5. ServiceLB가 large에서만 포트를 점유하는지 확인한다. 먼저 Certificate의 issuerRef를 `gateway-staging`으로 바꿔 HTTP-01 발급을 확인하고, production issuer로 바꿔 신뢰 가능한 인증서를 받는다. staging 인증서 상태로 서비스 전환 완료를 선언하지 않는다.
6. 외부 TLS·토큰·헤더 위조·내부 API 차단·실제 client IP·Grafana 로그인·S3 2GiB 업로드·STOMP를 확인한다. 인증서 갱신과 pod 재시작도 검증한다.
7. 복구 검증까지 끝난 뒤에만 별도 전환 작업에서 Compose·dev 경로를 제거한다.

### 진입점 복구

Compose edge nginx와 그 인증서 볼륨, 뒤의 app·web·DB를 2026-10-30에 지웠다(#440). nginx로 진입점을 되돌리는 복구는 없다. 서버의 `/var/backups/hapbang/gateway-<UTC>/`에 전환 전 nginx 설정·인증서 백업이 남아 있어도 보낼 Compose 서비스가 없다. Gateway에 문제가 생기면 k3s 안에서 고친다.

- stateful PVC·DB·인증서 Secret을 삭제하지 않는다. Gateway 작업을 되돌리려고 `k3s-uninstall.sh`를 실행하지 않는다.


## 검증 기록 (2026-10-29, 로컬 k3d)

- Envoy v1.9.3·cert-manager v1.21.3을 실제 HelmChart로 설치하고 Gateway `Programmed`·Certificate `Ready`를 확인했다.
- 실제 Tomcat listener의 외부/내부 경로 분리·인코딩 경로·위조 forwarded 포트·잘못된 설정 거절, 기존 내부 키 인증 테스트를 통과했다.
- `run.py`의 완료 조건 검사를 통과했다. 토큰 없음 통과, 만료·issuer·서명·kid 위조 401, 사용자 헤더·내부 키 위조 제거, 외부 내부 경로 404를 확인했다. 다른 node의 caller pod에서 8080 우회 차단, XFF 위조, WebSocket 양방향 frame, Grafana 익명 데이터 차단·로그인·인증 후 조회도 확인했다.
- 2GiB 스트리밍은 업로드 Lua를 지우기 전에 검증 backend 64Mi, Envoy 본체 256Mi 한도에서 통과했다. Lua를 지운 뒤에는 다시 실행하지 않았다. 실제 S3 업로드와 도메인 파일 크기 정책 검증을 대신하지 않는다.
- 범위를 줄이기 전에 한 번 확인하고 `run.py`에서 뺀 항목이 있다. 업무 경로 7개 라우팅, JWKS 키 교체·장애, 8081 허용 목록·키 인증, 일반 본문 1MiB 제한, Grafana Secure cookie·Live WebSocket이다. trace ID의 Envoy → Alloy → Tempo 도착과 로컬 인증서 갱신·Envoy TLS reload도 이때 확인했다. Let's Encrypt의 실제 발급·갱신 검증은 아니다.
- `verifyModuleStructure`, 전체 `check bootJar`, 고정 CRD schema 검사, Python lint를 통과했다. 최신 dev의 출연 RestAssured 변경(#402)을 병합하고 출연 check를 다시 통과했다. 강의 HP 작업 실행기(#275)가 추가한 Billing Client 주소도 8081로 맞추고 통합 check를 다시 통과했다.
- EC2·DNS·security group·운영 nginx·운영 데이터는 변경하지 않았다. IMDS 정책의 실제 AWS 동작, 인터넷 client IP, 실제 STOMP JWT와 FE 통합은 운영 전환 전에 확인한다. 인터넷 client IP는 2026-11-06에 확인했다(위 "실제 client IP", #726).

## EC2 적용·검증 기록 (2026-10-30)

- Envoy Gateway v1.9.3과 cert-manager v1.21.3을 설치했다. controller는 medium, Envoy proxy는 large에서 Ready다.
- 로컬 cert-manager의 controller 반복 종료·cainjector OOMKilled를 확인해 두 컨테이너를 각각 50Mi에서 128Mi로 조정했다. 변경 후 로컬·EC2에서 재시작 없이 Ready를 확인했다. webhook은 50Mi를 유지한다. 목표 8개 앱의 medium 예산 초과는 별도 조정이 필요하다(#343).
- k3s 설정·edge Compose/nginx 구성·기존 인증서를 서버의 root 전용 `/var/backups/hapbang/gateway-<UTC>/`에 백업했다. edge nginx·certbot을 중지하고, k3s의 `disable: servicelb`를 `disable: traefik`으로 바꾼 뒤 재시작했다. ServiceLB label은 large에만 붙였으며 두 ServiceLB 포트 컨테이너가 large에서 Ready다. 이때 기존 app·web·DB와 PVC는 보존했고, 같은 날 뒤이어 Compose를 볼륨까지 지웠다(#440).
- Let's Encrypt staging 발급 성공 뒤 `gateway-issuer` production 인증서로 전환했다. Certificate Ready와 일반 TLS 검증을 통과했다. 만료는 2027-01-28, cert-manager의 갱신 예정은 2026-12-29다. 실제 만료 갱신을 실행했다는 뜻은 아니다.
- 외부 도메인 검증 12개 통과: HTTP→HTTPS 301, 신뢰된 TLS와 Grafana 로그인 화면 200, Grafana 익명 데이터 401·실제 로그인/인증 데이터 200, Kafka UI 익명 401·인증 화면/토픽 API 200, 내부 API 404, 미등록 앱 API·FE root 404, 위조 사용자 헤더로 Grafana 데이터 접근 불가.
- Alloy의 OTLP 수신 13 spans·Tempo 전송 13 spans·수신 거절 0을 확인했고 Tempo에서 Envoy ingress trace를 조회했다. 당시 운영 샘플링은 10%여서 특정 단건 요청이 항상 저장되지 않았다. 2026-11-04부터 업무 route는 100%다(#679).
- Gateway 전용 렌더링에서 업무 앱·FE route를 제외했다. 초기 `web` route는 FE Service가 없어 500을 반환했으며 이번에 생성한 그 route만 제거한 뒤 404를 확인했다. FE 배포 때 root route를 연결해야 한다.
- 로컬 k3d 통합 검증 14개 통과: 2GiB 스트리밍, optional JWT·서명/만료/issuer/kid 거절, 사용자 헤더·내부 키 위조 제거, 내부 경로·8080 우회 차단, XFF 우측 hop, WebSocket, Grafana 익명 차단·로그인·데이터 조회. 테스트 경로는 `/api/v1/gateway-test/`로 분리해 이미 설치된 실제 앱 route와 충돌하지 않는다.
- 운영 렌더러의 Basic Auth `SecurityPolicy` 처리 시 `KeyError: jwt`를 수정했다. 실제 Kustomize overlay를 쓰는 회귀 검사 3개를 CI에 추가했다. `verifyModuleStructure`, 전체 `check`(3,153개, 실패·오류·건너뜀 0개), Gateway manifest schema 검사를 통과했다.
- 최신 dev의 출연 HTTP 전환을 통합한 뒤 `check`를 다시 통과했다. 변경된 출연·shared 테스트를 실행하고 나머지 이전 결과를 재사용했으며 결과 XML 총 3,169개, 실패·오류·건너뜀 0개다.
- 업무 앱·FE 이미지·Secret 배포, 실제 member JWKS와 앱 헤더 인가(#370·#371), S3 업로드·STOMP JWT·FE 통합은 미완료다. 이 작업만으로 #372 전체 완료 또는 서비스 전환 완료를 선언하지 않는다.
