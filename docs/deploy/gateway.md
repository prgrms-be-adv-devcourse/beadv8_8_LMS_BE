# Envoy Gateway (#372)

상태: 로컬 검증용 구성. EC2 진입점은 아직 Compose edge nginx다. 운영 전환은 #370·#371 인증, #374 이미지·배포, #338 데이터 이전 준비 뒤 수행한다. 이 문서의 명령은 별도 표시가 없으면 로컬 `k3d-hapbang`만 대상으로 한다.

## 결정 기록 (2026-10-29)

#372 계획에서 사용자에게 확인한 결정이다.

- 내부·외부 API를 실제 listener로 분리한다. 외부 8080은 Envoy만, 내부 8081은 앱 pod끼리만 접근한다. 호출 관계별 허용 목록은 두지 않는다. 같은 내부 API 키를 쓰는 앱 6개 사이에서 목록이 막는 경로가 작고, 호출을 더할 때마다 제공 앱 정책을 고쳐야 했기 때문이다. 내부 API 키도 앱 6개가 같은 값을 쓴다. 제공 앱별 키로 호출 관계를 Secret에서 제한하는 방식은 실서비스가 아닌 이 규모에서 과하다고 보고 두지 않는다. Service 포트 이름만 나누는 방식은 쓰지 않는다.
- Grafana는 prod 도메인의 `/grafana/`에서 자체 계정 로그인으로 공개한다. 익명 접근·가입·auth proxy는 끈다. NodePort·3000 공개는 하지 않는다.
- 이번 작업은 manifest·앱 포트 분리·로컬 검증·운영 전환 절차까지다. 실제 EC2 진입점 전환과 운영 검증 전에는 #372를 완료 처리하지 않는다.
- 티켓 범위를 넘는 장치는 두지 않는다. 업로드 메서드·형식·크기 검사는 앱이 원본이고 Envoy는 timeout만 늘린다. platform에는 ingress NetworkPolicy를 두지 않는다. `run.py`는 완료 조건만 검사한다.
- 앱 manifest는 각 앱의 `k8s/`에 둔다. 공통 Gateway, platform 접근 정책, Grafana·Alloy 설정은 `k8s/gateway/`에 둔다. 앱이 루트 manifest를 참조하지 않는다.

## 구성과 적용 순서

| 경로 | 책임 |
| --- | --- |
| `k8s/gateway/install/envoy-gateway` | 공식 HelmChart v1.9.3, controller medium 128Mi, Gateway API·Envoy CRD |
| `k8s/gateway/install/cert-manager` | 공식 HelmChart v1.21.3, controller·webhook·cainjector medium 각 50Mi |
| `k8s/gateway/overlays/local` | `hapbang.local`, 로컬 self-signed TLS, trace sampling 100% |
| `k8s/gateway/overlays/prod` | 명시적 입력이 필요한 운영 template, Let's Encrypt staging·production issuer, sampling 10% |
| `apps/<앱>/k8s/base/gateway.yaml` | 모듈 HTTPRoute·optional JWT·본문 제한·업로드·WebSocket |
| `apps/<앱>/k8s/base/networkpolicy.yaml` | 앱 ingress와 IMDS 접근 제한 |

Helm CLI로 설치하지 않고 k3s helm-controller가 설치한다. Envoy chart의 CRD·controller 준비 뒤 cert-manager를 설치해야 한다. cert-manager는 시작 시 Gateway API를 탐지하므로 순서를 뒤집지 않는다. CRD 설치 전에 Gateway·Certificate·정책을 한 번에 apply하지 않는다.

```bash
# 먼저 docs/deploy/k8s.md의 cluster·platform을 준비한다.
bash k8s/gateway/tools/install-local.sh
```

스크립트는 Envoy → CRD 대기 → cert-manager → webhook 대기 → local Gateway 순서로 적용한다. local server node에만 ServiceLB label을 붙인다. EC2 context는 받지 않는다. 기존 로컬 cluster와 데이터는 삭제하지 않는다.

Gateway와 TLS는 `envoy-gateway-system`, Grafana route는 `hapbang-platform`, 앱 route는 `hapbang-prod`에 있다. Gateway listener의 허용 namespace도 이 셋으로 제한한다. 각 route의 `Accepted`·`ResolvedRefs`와 Gateway `Programmed`를 확인한다. `web:80`은 #374에서 FE 이미지를 배포할 Service 계약이다. 아직 FE Service가 없으면 `/`는 500, Service에 준비된 endpoint가 없으면 503이며 준비 완료로 보지 않는다.

## 인증과 내부 포트

`hapbang.internal-api.port`(환경변수 `HAPBANG_INTERNAL_API_PORT`)가 없으면 기존 단일 포트 동작을 유지한다. k8s는 8081을 설정한다. 잘못된 범위 또는 외부와 같은 포트면 기동 실패다.

분리 모드에서 Tomcat이 정규화·decode한 경로와 실제 수신 포트를 비교한다. 외부 포트의 `/api/v1/internal`·하위 경로는 404, 내부 포트의 나머지도 404다. forwarded 헤더는 포트 판단에 사용하지 않는다. 내부 API는 계속 `X-Internal-Api-Key`와 기존 전용 SecurityFilterChain을 사용한다. 앱 간 동기 호출의 transaction·보상 정책은 바꾸지 않는다.

앱 6개의 pod에는 `hapbang.io/internal-api-client: 'true'` label을 붙인다. 모든 앱의 8081은 이 label을 가진 pod만 허용한다. 새 앱은 자기 Deployment에 label을 붙이면 다른 앱의 내부 API를 부를 수 있다. 현재 chat·customer-support의 8081을 부르는 앱은 없다. 앱 egress는 IMDS만 막으므로 8081 접근 통제는 제공 앱의 ingress NetworkPolicy가 맡는다.

NetworkPolicy는 HTTP path를 구분하지 못한다. 그래서 두 실제 listener와 앱의 경로 검사를 함께 둔다. 같은 namespace라는 이유로 앱의 8080을 열지 않는다. Prometheus의 앱 직접 scrape가 필요하면 #343에서 별도 management port와 접근 정책을 설계한다.

JWT는 업무 API route에만 적용한다. Grafana는 Grafana의 자체 로그인, FE는 정적 서버다. `optional: true`로 토큰이 없는 요청만 허용하고, 잘못된 토큰은 거절한다. `failOpen`은 설정하지 않는다. v1.9.3 CRD는 값이 false여도 optional과 failOpen 필드의 동시 설정 자체를 거절한다.

`ClientTrafficPolicy.earlyRequestHeaders`는 인증 전에 외부 `X-User-Id`, `X-User-Role`, `X-Internal-Api-Key`와 전달 프로토콜·호스트 위조 헤더를 제거한다. 검증한 claim만 사용자 헤더로 생성한다. XFF의 신뢰 hop은 0이며 Envoy가 실제 접속 주소를 오른쪽에 추가한다. 앱은 native RemoteIpValve로 읽는다. 실제 인터넷 client IP 보존은 EC2 ServiceLB 전환 후 다시 검증한다.

prod issuer·JWKS 경로는 #370과 맞춘다. Gateway는 member Service 8080에서 공개 JWKS를 직접 읽는다. chat의 직접 JWT 검증(#371)은 Gateway의 공개 HTTPS JWKS URL을 사용해 member 8080 직접 접근 예외를 만들지 않는다. 토큰 URL query 전달은 하지 않는다.

## 본문·연결·Grafana·trace

- 일반 업무 route만 `requestBuffer`로 1MiB 상한을 적용한다. 일반 요청 timeout은 30초다.
- 파일 content 경로는 regex와 `PUT` 메서드로 분리해 timeout만 늘린다. 다른 메서드는 일반 route(1MiB·30초)로 간다. 업로드에는 requestBuffer·retry를 적용하지 않는다. 전체 timeout은 30분, stream idle은 60초다. Content-Type·Content-Length·크기 검사는 앱이 한다. Envoy에서 같은 검사를 다시 하지 않는다. `ClientTrafficPolicy`의 `requestReceivedTimeout`은 헤더뿐 아니라 본문까지 받는 시간이라 2GiB 업로드를 위해 0s(해제)로 둔다. 아무것도 보내지 않고 연결만 잡는 요청은 idle·stream idle 60초가 끊는다.
- WebSocket은 별도 route에서 전체 timeout을 해제하고 stream idle 5분을 둔다. STOMP heartbeat·CONNECT JWT는 #371에서 처리한다.
- 알 수 없는 `/api/**`, actuator·Swagger·api-docs는 앱/React backend로 보내지 않고 404다. 인증된 API도 실제 앱의 인가·권한 검사가 필요하다.
- `/grafana`는 `/grafana/`로 redirect한다. prefix는 지우지 않는다. Grafana `root_url`, `serve_from_sub_path`, Secure cookie를 설정한다. admin 자격 증명은 기존 chart Secret을 사용하고 레포·로그에 남기지 않는다.
- Grafana는 320Mi 한도에서 subpath 변경 후 기동 시 높은 CPU·probe timeout이 관찰되어 384Mi와 `GOMAXPROCS=2`를 적용했다. 이 64Mi 증가는 #343의 medium 목표 예산 조정에 포함한다.
- Grafana는 SQLite PVC를 공유하는 새·옛 pod가 함께 뜨지 않도록 `Recreate`로 갱신한다. probe는 `/grafana/api/health`, startup은 최대 5분을 기다린다. 로그인 화면·정적 자원은 익명에게 제공되고 dashboard·데이터 API는 로그인이 필요하다.
- Kafka UI(Redpanda Console)는 `/kafka/`에서 Envoy Basic Auth(`SecurityPolicy` basicAuth, SHA htpasswd)로 연다. Console 무료판에는 로그인이 없기 때문이다. 인증이 없으면 401과 `WWW-Authenticate: Basic`을 돌려줘 브라우저 로그인 창이 뜬다. route가 `X-Forwarded-Prefix: /kafka`를 넣어 Console이 하위 경로로 동작한다. 계정은 `admin` 하나이고 prod 비밀번호는 만든 사람의 `~/.hapbang/kafka-ui-password`에만 있어 팀 비밀 보관소로 공유한다. 바꿀 때는 새 비밀번호의 SHA htpasswd를 kubeseal로 다시 봉인해 `overlays/prod/kafka-ui-basic-auth.yaml`을 바꾼다. Basic Auth에는 로그아웃·시도 횟수 제한·사용자 구분이 없다. Console은 평소 replicas 0이라 꺼져 있으면 503이다.
- Tempo는 trace 수신·조회 부하에서 기존 64Mi로 OOMKilled되어 128Mi·GOMEMLIMIT=112MiB·GOMAXPROCS=2를 적용한다. 이 조정과 Grafana 증가분, Envoy 종료 sidecar 32Mi를 [예산 표](../architecture/infrastructure/k3s.md)에 반영한다.
- Envoy JSON access log는 query·본문·Authorization·쿠키를 기록하지 않는다. W3C trace를 Alloy OTLP 4317 → Tempo 4317로 전송한다. dashboard·앱 전체 계측·알림은 #343이다.
- platform(PostgreSQL·Kafka·Redis·Elasticsearch·관측성)에는 ingress NetworkPolicy를 두지 않는다. 외부에서는 닿지 않고, 막아 주는 경우는 cluster 안 pod가 먼저 뚫린 상황뿐이라 실서비스가 아닌 이 규모에서는 감수한다. 인증이 없는 Kafka·Redis도 같다. 앱 이름 허용 목록은 앱을 더할 때마다 고쳐야 하고 빠뜨려도 CI가 잡지 못한다(#374). `deny-metadata-egress`는 namespace의 모든 pod에 적용되므로 이 구성 요소들도 IMDS egress는 막힌다. k3s(kube-router)는 새 pod에 규칙을 거는 데 몇 초가 걸려 그동안은 IMDS가 막히지 않는다(EC2 실측, #374). metadata egress는 S3를 쓰는 member·appearance·lecture에만 허용한다. node 자체나 hostNetwork pod는 일반 NetworkPolicy의 신뢰 경계 밖이다.

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

`run.py`는 #372 완료 조건 7개만 검사한다. 2GiB 업로드는 `--large-upload`를 줄 때만 실행한다. 테스트는 테스트 전용 pod·route·policy를 만들고 종료 때 자기 label의 자원만 지운다. 기존 앱 Deployment·Service를 테스트 대역으로 바꾸지 않는다. 생성한 RS256 개인 키는 테스트 프로세스 메모리에만 둔다. 2GiB 본문은 1MiB chunk로 생성하며 디스크에 만들지 않는다.

앱 검증은 `./gradlew verifyModuleStructure --no-daemon` 뒤 `./gradlew check bootJar --parallel --no-daemon`로 한다. 구조 검사는 다른 프로젝트 설정을 조회하므로 parallel과 함께 실행하지 않는다. CI의 manifests job은 고정 버전 upstream CRD로 schema를 만들고 모든 overlay를 `kubeconform -strict`로 검사한다. CEL admission 제약·정책 충돌은 schema만으로 검증되지 않으므로 실제 k3d 검증이 필요하다.

## 운영 template 준비 (실행 전 점검용)

`render-prod.py`는 stdout으로만 출력한다. 비밀값이나 Secret은 읽지 않고 cluster도 변경하지 않는다. 아래 값은 #370·운영 담당자와 맞춘 실제 값으로 입력한다. `.invalid` placeholder나 값 누락은 실패한다.

```bash
export GATEWAY_HOST='<prod 도메인>'
export ACME_EMAIL='<인증서 연락처>'
export JWT_ISSUER='https://<prod issuer>'
export MEMBER_JWKS_URI='http://member-app.hapbang-prod.svc.cluster.local:8080/<확정된 JWKS 경로>'
/tmp/gateway-test-venv/bin/python k8s/gateway/tools/render-prod.py > /tmp/gateway-prod.yaml
```

운영 배포는 #374의 앱별 이미지·SealedSecret·ghcr-pull 준비 이후다. 출력 파일을 그대로 자동 apply하지 않는다. Gateway·platform은 수동, 앱 manifest는 앱별 workflow가 관리하도록 확정된 issuer·JWKS를 각 배포 입력에 반영한다. 아직 세션을 쓰는 앱을 Gateway 헤더 인증이 완료된 앱처럼 배포하지 않는다.

### EC2 전환 조건과 순서 (이번 작업에서는 실행하지 않음)

1. #370·#371 JWT/인가, #280 독립 기동, #338 데이터 이전·복구, #374 앱·FE 이미지와 Secret 준비를 확인한다. medium의 목표 구성 예산 초과는 #343에서 조정하고 sidecar·설치 Job·rolling 여유도 포함해 재측정한다.
2. nginx·기존 인증서·Compose와 k3s config를 백업하고 중단 시간을 팀에 공지한다. 기존 서비스·DB는 지우지 않는다.
3. Gateway controller·cert-manager·정책을 준비하되 edge nginx가 80·443을 쓰는 동안 ServiceLB를 켜지 않는다.
4. nginx를 먼저 멈춘다. large에만 `svccontroller.k3s.cattle.io/enablelb=true` label을 붙이고 k3s config에서 `servicelb` disable을 제거하고 `traefik`을 disable한 뒤 재시작한다.
5. ServiceLB가 large에서만 포트를 점유하는지 확인한다. 먼저 Certificate의 issuerRef를 `gateway-staging`으로 바꿔 HTTP-01 발급을 확인하고, production issuer로 바꿔 신뢰 가능한 인증서를 받는다. staging 인증서 상태로 서비스 전환 완료를 선언하지 않는다.
6. 외부 TLS·토큰·헤더 위조·내부 API 차단·실제 client IP·Grafana 로그인·S3 2GiB 업로드·STOMP를 확인한다. 인증서 갱신과 pod 재시작도 검증한다.
7. 복구 검증까지 끝난 뒤에만 별도 전환 작업에서 Compose·dev 경로를 제거한다.

### 진입점 복구

- nginx를 다시 띄우기 **전에** k3s의 `servicelb`를 다시 disable하고 k3s를 재시작한다. svclb pod와 hostPort DNAT의 80·443 점유가 사라졌는지 확인한다.
- 백업한 기존 Compose edge 구성·인증서를 사용해 nginx를 재시작한다. 외부 TLS와 기존 health를 확인한다. DNS는 기존 large EIP를 유지한다.
- stateful PVC·DB·인증서 Secret을 삭제하지 않는다. Gateway 작업을 되돌리려고 `k3s-uninstall.sh`를 실행하지 않는다.
- 데이터 이전 이후의 복구는 #338 절차가 필요하다. 진입점만 되돌렸다고 데이터도 이전 시점으로 복구됐다고 판단하지 않는다.


## 검증 기록 (2026-10-29, 로컬 k3d)

- Envoy v1.9.3·cert-manager v1.21.3을 실제 HelmChart로 설치하고 Gateway `Programmed`·Certificate `Ready`를 확인했다.
- 실제 Tomcat listener의 외부/내부 경로 분리·인코딩 경로·위조 forwarded 포트·잘못된 설정 거절, 기존 내부 키 인증 테스트를 통과했다.
- `run.py`의 완료 조건 검사를 통과했다. 토큰 없음 통과, 만료·issuer·서명·kid 위조 401, 사용자 헤더·내부 키 위조 제거, 외부 내부 경로 404를 확인했다. 다른 node의 caller pod에서 8080 우회 차단, XFF 위조, WebSocket 양방향 frame, Grafana 익명 데이터 차단·로그인·인증 후 조회도 확인했다.
- 2GiB 스트리밍은 업로드 Lua를 지우기 전에 검증 backend 64Mi, Envoy 본체 256Mi 한도에서 통과했다. Lua를 지운 뒤에는 다시 실행하지 않았다. 실제 S3 업로드와 도메인 파일 크기 정책 검증을 대신하지 않는다.
- 범위를 줄이기 전에 한 번 확인하고 `run.py`에서 뺀 항목이 있다. 업무 경로 7개 라우팅, JWKS 키 교체·장애, 8081 허용 목록·키 인증, 일반 본문 1MiB 제한, Grafana Secure cookie·Live WebSocket이다. trace ID의 Envoy → Alloy → Tempo 도착과 로컬 인증서 갱신·Envoy TLS reload도 이때 확인했다. Let's Encrypt의 실제 발급·갱신 검증은 아니다.
- `verifyModuleStructure`, 전체 `check bootJar`, 고정 CRD schema 검사, Python lint를 통과했다. 최신 dev의 출연 RestAssured 변경(#402)을 병합하고 출연 check를 다시 통과했다. 강의 HP 작업 실행기(#275)가 추가한 Billing Client 주소도 8081로 맞추고 통합 check를 다시 통과했다.
- EC2·DNS·security group·운영 nginx·운영 데이터는 변경하지 않았다. IMDS 정책의 실제 AWS 동작, 인터넷 client IP, 실제 STOMP JWT와 FE 통합은 운영 전환 전에 확인한다.
