# 보안 가이드

## 원칙

- **이 레포는 공개(public)다.** 비밀값, 서버 IP, AWS 계정 정보, 키 파일 이름·내용, **서버 DB 이름·계정**을 레포·PR·이슈·커밋 메시지에 쓰지 않는다. 문서에는 `<EC2 주소>`, `<키 파일>`처럼 자리표시자만 쓴다.
- **비밀값은 한 곳에만 둔다.** 운영 값은 SealedSecret(레포에는 암호문만, 원문은 봉인한 사람의 `~/.hapbang`과 팀 비밀 보관소) 또는 GitHub Secrets. 채팅·노션·스크린샷으로 옮기지 않는다.
- **장기 토큰을 서버에 두지 않는다.** k3s 앱 이미지는 public이라 pull 자격 증명이 없고, S3는 EC2 인스턴스 역할로, k3s 배포는 GitHub OIDC로 받은 임시 자격 증명으로 접근한다(AWS 액세스 키를 만들지 않는다).
- AWS 지침: 액세스 키 등 유출 메일 2회 시 팀 AWS 지원 회수(2OUT), 계정 외부 공유·목적 외 사용은 즉시 회수(1OUT).

## AWS 계정

- 계정은 다른 팀과 같이 쓴다. 리소스 이름은 `team08-hapbang-*`, 태그는 `Team=devcos-team08`이다(`infra/terraform`).
- 그룹 정책이 IAM 사용자·액세스 키 생성을 거부한다. 서버는 EC2 인스턴스 역할로 접근한다.
- 계정이 AWS Organizations에 속해 있고 SCP/RCP는 조회할 수 없다. 조직 정책으로 거부되면 상세 이유 없이 `Access Denied`만 온다.
- `aws iam simulate-principal-policy`는 조직 정책과 네트워크 조건을 반영하지 않는다. 허용으로 나와도 실제 요청으로 확인한다.
- Codex 등 외부 서비스에 검토를 맡길 때도 버킷 이름·계정 ID·URL·토큰을 빼고 넘긴다.

## 자격 증명 목록

| 자격 증명 | 보관 위치 | 접근 가능 | 용도 | 교체 |
| --- | --- | --- | --- | --- |
| EC2 키 페어(pem) | AWS 담당자 로컬 | 서버 관리자 | `ubuntu` SSH 접속 | AWS에서 새 키 페어 → 서버 `~ubuntu/.ssh/authorized_keys` 교체 |
| DB 비밀번호 | SealedSecret `postgres-credentials`(`k8s/platform/overlays/prod`)와 각 앱 Secret의 `DB_PASSWORD` | cluster, 앱 pod | app → PostgreSQL | [DB 비밀번호 교체](#db-비밀번호-교체) |
| 앱 비밀값(OAuth·결제 등) | 앱 SealedSecret. 원문은 `~/.hapbang/prod-secrets.env` | 앱 pod | Spring | [env-vars.md](env-vars.md#값-변경-비밀값-교체-포함) |
| S3 접근 | EC2 인스턴스 역할(키 없음). 버킷 이름은 앱 Secret `S3_BUCKET` | IMDS egress가 허용된 member·appearance·lecture pod | Presigned URL 발급, 업로드 확인 | 자동(임시 자격 증명) → [s3.md](s3.md) |
| k3s 배포 | IAM role(키 없음, GitHub OIDC 임시 자격 증명). role ARN·large 인스턴스 ID는 GitHub Environment `prod` Secrets | Environment `prod` job(`main`만) | SSM Run Command로 large에서 `k3s kubectl` 실행([k8s.md](k8s.md#운영-앱-배포)) | 자동(1시간 세션) |
| 개인 PAT | 만들지 않고 둔다. 수동 pull 때만 `read:packages`·짧은 만료로 생성 후 삭제 | 사용자 본인 | 수동 pull | 사용 후 즉시 삭제 |
| DuckDNS 토큰 | DuckDNS 계정 소유자 | 소유자 | 도메인 IP 변경 | DuckDNS에서 재발급 |
| TLS 개인 키 | cert-manager가 만든 Secret `hapbang-tls`(`envoy-gateway-system`) | cluster | HTTPS | cert-manager가 자동 갱신 |
| 내부 API 키 | 앱 Secret `HAPBANG_INTERNAL_API_KEY`. 원문은 `~/.hapbang/internal-api-key` | 앱 pod | 모듈 간 내부 HTTP API(`/api/v1/internal/**`) 인증 (#300) | 앱 6개를 함께 다시 봉인하고 배포([env-vars.md](env-vars.md#값-변경-비밀값-교체-포함)) |

## 구조상 보호 장치

| 장치 | 효과 |
| --- | --- |
| 외부 포트는 Envoy(80·443)만 | app·platform(PostgreSQL·Kafka·Redis 등)에 직접 접근 불가 |
| 내부 API 차단 | Envoy에 `/api/v1/internal/**` route가 없어 404. 앱 외부 포트(8080)도 내부 경로를 404로 막고, 내부 포트(8081)는 허용 label이 있는 pod만 접근한다. 앱도 내부 API 키가 없으면 401 (#300, [gateway.md](gateway.md#인증과-내부-포트)) |
| 미등록 도메인 차단 | 443은 prod 도메인 외 TLS 핸드셰이크를 거부하고, 80은 404 |
| HTTPS 리다이렉트 | 80은 HTTPS로 301. HSTS 헤더는 아직 보내지 않는다 |
| `.gitignore` | `.env`, `.env.*`, `*.pem`이 커밋되지 않음 |
| BE 이미지 빌드 컨텍스트는 jar 폴더뿐 | 이미지에는 `app.jar`만 들어간다. 소스·`.env`가 들어갈 수 없음 |
| CI(PR) 읽기 전용 권한 | PR 코드가 실행되는 job은 GHCR에 쓸 수 없음. push는 Publish images의 `build` job만 |
| 운영 배포 수동 실행·검사 | Deploy는 Environment `prod`(`main`만)에서 실행되고, GHCR에 이미지가 있는(테스트 통과) 커밋만 배포된다 |
| SSH 비밀번호 로그인 꺼짐 | 22 포트가 열려 있어도 키 없이는 접속 불가 |
| k3s 배포 role의 trust를 Environment `prod`로 제한 | 다른 브랜치·PR의 workflow는 role을 받지 못한다. `prod`는 `main` 브랜치만 허용 |
| `hapbang-prod` Pod Security `baseline`(EC2 적용 2026-10-30) | 리뷰를 거쳐 배포되는 manifest가 privileged·hostNetwork·hostPath pod를 만들지 못한다. 그런 pod는 node root·IMDS(인스턴스 역할)에 닿는다. 배포 role 자체의 root 권한은 막지 못한다(아래 알려진 위험) |
| k3s API(6443)는 사용자 IP `/32`만 | 배포는 SSM으로 하므로 6443을 열지 않는다. 자격 증명이 새도 네트워크에서 막힌다 |

## 알려진 위험

| 위험 | 현재 | 개선안 |
| --- | --- | --- |
| SSH 22 전체 개방 | 기존 Compose 배포(Actions SSH) 때문에 열었다. 배포는 SSM으로 바뀌었지만 아직 열려 있다. 키 인증만 허용 | 허용 source 제한 또는 SSM Session Manager 전환([aws-k3s.md](aws-k3s.md#network와-외부-노출)) |
| 기존 Compose 배포 자격 증명이 남아 있음 | Compose는 2026-10-30에 지웠지만(#440) 서버의 `deploy` 계정·배포 키(`authorized_keys`)·`deploy.sh`, 옛 비밀값이 든 `/opt/hapbang/env`, FE·BE 레포의 `DEPLOY_SSH_KEY`·`DEPLOY_HOST`·`DEPLOY_KNOWN_HOSTS` Secrets는 남아 있을 수 있다 | 서버와 GitHub에서 삭제(#374 기존 경로 정리) |
| main으로 가는 PR 출발 브랜치 검사가 이름만 봄 | ruleset `protect-main-dev`가 PR·`verify`·`source-branch`(dev, `release/review-N`만 허용, #180)를 요구한다. 승인 수는 0이라 이름을 맞춘 브랜치는 통과한다 | main 전용 ruleset으로 승인 1개 이상 요구 |
| k3s 배포 role이 large에서 임의 명령을 root로 실행할 수 있음 | trust를 Environment `prod`(`main`만)로 제한. `main`에 병합된 workflow만 쓴다 | 토큰 발급만 하는 전용 SSM 문서로 권한 축소 |
| GitHub Actions 메이저 버전 태그(`@v7` 등) 사용 | 액션 저장소가 오염되면 영향 | 커밋 SHA로 고정 |
| DB 백업 없음 | k3s PostgreSQL 백업 절차가 없다 | 정기 백업 결정 |

## DB 비밀번호 교체

PostgreSQL은 데이터 디렉터리가 비어 있을 때만 `postgres-credentials` 값으로 앱 role을 만든다. 그래서 Secret만 바꾸면 DB 안의 비밀번호는 그대로다. 세 곳을 함께 바꾼다.

1. `postgres-credentials`의 그 앱 키를 새 값으로 다시 봉인해 `k8s/platform/overlays/prod`에 적용한다. 다음 단계 전에 Secret이 새 값으로 풀렸는지 확인한다. `kubectl --context hapbang-k3s -n hapbang-platform get sealedsecret postgres-credentials -o jsonpath='{.metadata.generation} {.status.observedGeneration} {.status.conditions[0].status}'`가 같은 숫자 두 개와 `True`를 출력하면 Secret이 새 값으로 풀린 것이다(값은 출력하지 않는다). 풀리기 전에 3을 하면 이전 비밀번호가 앱 Secret에 들어간다.
2. 실행 중인 PostgreSQL에서 superuser로 `ALTER ROLE <앱 role> PASSWORD ...`를 실행한다.
3. `k8s/tools/seal-app-secrets.sh <앱>`으로 앱 Secret을 다시 봉인한다(`DB_PASSWORD`를 cluster의 `postgres-credentials`에서 읽는다). PR을 병합하고 Deploy로 그 앱을 배포한 뒤 [env-vars.md](env-vars.md#값-변경-비밀값-교체-포함) 3번처럼 앱 Secret 반영을 확인한다.

2와 3 사이에는 그 앱의 새 DB 연결이 실패하므로 배포 시간을 짧게 잡는다. 비밀번호 값은 명령 기록·로그에 남지 않게 한다.

## 유출이 의심될 때

1. **무엇이** 유출됐는지 확인한다(위 목록).
2. 해당 자격 증명을 **즉시 교체**한다. 커밋에 올라갔다면 히스토리에서 지우는 것보다 교체가 먼저다(공개 레포는 이미 복제됐을 수 있다).
3. AWS 관련(키 페어, 액세스 키)이면 AWS 담당자와 운영진에게 바로 알린다.
4. 영향 범위를 확인한다: 서버 `sudo journalctl -u ssh --since "-7 days"`, pod 로그(Grafana의 Loki), GitHub 감사 로그.
5. 원인과 조치를 팀에 공유한다.

## 금지 사항

- `.env`, `*.env`, `*.pem`, 개인 키를 커밋하거나 채팅·노션에 붙여넣기
- `~/.hapbang` 파일이나 `kubectl get secret -o yaml` 출력을 화면 공유·캡처
- 비밀값을 로그에 출력하는 코드(`log.info(secret)`), Actions에서 `echo $SECRET`
- 문서·PR에 실제 IP, 계정 ID, 키 파일 이름, 서버 DB 이름·계정 기재
