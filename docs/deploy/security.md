# 보안 가이드

## 원칙

- **이 레포는 공개(public)다.** 비밀값, 서버 IP, AWS 계정 정보, 키 파일 이름·내용, **서버 DB 이름·계정**을 레포·PR·이슈·커밋 메시지에 쓰지 않는다. 문서에는 `<EC2 주소>`, `<키 파일>`처럼 자리표시자만 쓴다.
- **비밀값은 한 곳에만 둔다.** 서버 env 파일 또는 GitHub Secrets. 채팅·노션·스크린샷으로 옮기지 않는다.
- **장기 토큰을 서버에 두지 않는다.** GHCR pull은 배포 job의 임시 토큰을 쓰고(k3s 앱 이미지는 public이라 pull 자격 증명이 없다), S3는 EC2 인스턴스 역할로, k3s 배포는 GitHub OIDC로 받은 임시 자격 증명으로 접근한다(AWS 액세스 키를 만들지 않는다).
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
| 배포 키 | GitHub Secrets `DEPLOY_SSH_KEY`(FE·BE) | Actions | `deploy` 접속 → `deploy.sh`만 실행 | [배포 키 교체](#배포-키-교체) |
| 서버 호스트 키 | GitHub Secrets `DEPLOY_KNOWN_HOSTS` | Actions | 서버 위조 방지 | 서버 재구축 시 갱신 |
| DB 비밀번호 | 서버 `/opt/hapbang/env/{prod,dev}.env` | 서버 관리자 | app → PostgreSQL | [DB 비밀번호 교체](#db-비밀번호-교체) |
| 앱 비밀값(OAuth·결제 등) | 서버 env 파일 | 서버 관리자 | Spring | [env-vars.md](env-vars.md#값-변경-비밀값-교체-포함) |
| S3 접근 | EC2 인스턴스 역할(키 없음). 버킷 이름은 서버 env `S3_BUCKET` | app 컨테이너(인스턴스 메타데이터) | Presigned URL 발급, 업로드 확인 | 자동(임시 자격 증명) → [s3.md](s3.md) |
| GHCR 읽기 | (Compose) 배포 job의 `GITHUB_TOKEN`(읽기 전용, job 종료 시 만료). k3s 앱 이미지는 public이라 없음 | Actions | 서버의 이미지 pull | 자동 |
| k3s 배포 | IAM role(키 없음, GitHub OIDC 임시 자격 증명). role ARN·large 인스턴스 ID는 GitHub Environment `prod` Secrets | Environment `prod` job(`main`만) | SSM Run Command로 large에서 `k3s kubectl` 실행([k8s.md](k8s.md#운영-앱-배포)) | 자동(1시간 세션) |
| 개인 PAT | 만들지 않고 둔다. 수동 pull 때만 `read:packages`·짧은 만료로 생성 후 삭제 | 사용자 본인 | 수동 pull | 사용 후 즉시 삭제 |
| DuckDNS 토큰 | DuckDNS 계정 소유자 | 소유자 | 도메인 IP 변경 | DuckDNS에서 재발급 |
| TLS 개인 키 | edge `letsencrypt` 볼륨 | 서버 | HTTPS | 재발급 시 자동 교체 |
| 내부 API 키 | 서버 env `HAPBANG_INTERNAL_API_KEY` | app 컨테이너 | 모듈 간 내부 HTTP API(`/api/v1/internal/**`) 인증 (#300) | 서버 env 값을 바꾸고 재배포([env-vars.md](env-vars.md#값-변경-비밀값-교체-포함)). 같은 앱이 부르고 받으므로 한 번에 바뀐다 |

## 구조상 보호 장치

| 장치 | 효과 |
| --- | --- |
| 배포 키 `command=...,restrict` | 키가 유출돼도 셸·포트포워딩 불가. 할 수 있는 것은 정해진 이미지 재배포뿐 |
| `deploy.sh` 입력 검증 | 환경·서비스·SHA·토큰 형식 외 입력 거부(명령 주입 차단) |
| 서버 설정 수동 반영 | 배포 키·Actions로 compose·nginx를 바꿀 수 없음 |
| GHCR private + 읽기 전용 임시 토큰 | 서버가 탈취돼도 레지스트리에 이미지를 올릴 수 없음 |
| 외부 포트는 edge(80·443)만 | app·DB에 직접 접근 불가 |
| 내부 API 차단 | edge nginx가 `/api/v1/internal/`을 404로 끊고, 앱도 내부 API 키가 없으면 401 (#300) |
| 미등록 도메인 차단 | IP 직접 접근·스캐너에 응답하지 않음(80 연결 종료, 443 핸드셰이크 거부) |
| HTTPS + HSTS, forward-headers | 쿠키에 Secure, https 리다이렉트 |
| `.gitignore` | `.env`, `.env.*`, `*.pem`이 커밋되지 않음 |
| BE 이미지 빌드 컨텍스트는 jar 폴더뿐 | 이미지에는 `app.jar`만 들어간다. 소스·`.env`가 들어갈 수 없음 |
| CI(PR) 읽기 전용 권한 | PR 코드가 실행되는 job은 GHCR에 쓸 수 없음. push는 Deploy의 `build` job만 |
| 운영 배포 수동 실행·검사 | BE 운영은 Deploy prod를 `main`에서 실행해야 하고, `main`에 포함되고 이미지가 있는(테스트 통과) 커밋만 배포된다 |
| SSH 비밀번호 로그인 꺼짐 | 22 포트가 열려 있어도 키 없이는 접속 불가 |
| k3s 배포 role의 trust를 Environment `prod`로 제한 | 다른 브랜치·PR의 workflow는 role을 받지 못한다. `prod`는 `main` 브랜치만 허용 |
| `hapbang-prod` Pod Security `baseline` | 배포 권한으로 privileged·hostNetwork·hostPath pod를 띄워 node와 인스턴스 역할을 장악할 수 없다 |
| k3s API(6443)는 사용자 IP `/32`만 | 배포는 SSM으로 하므로 6443을 열지 않는다. 자격 증명이 새도 네트워크에서 막힌다 |

## 알려진 위험

| 위험 | 현재 | 개선안 |
| --- | --- | --- |
| dev가 인터넷에 공개(Swagger 포함) | 누구나 API 명세 조회·호출 가능 | edge에서 dev를 팀 IP 또는 Basic 인증으로 제한 |
| SSH 22 전체 개방 | Actions 배포 때문. 키 인증만 허용 | 접근 로그 주기 확인(`sudo journalctl -u ssh`) |
| Deploy prod 검사가 워크플로 안에 있음 | 실수(다른 브랜치·dev 커밋)만 막는다. 쓰기 권한자가 워크플로를 고친 브랜치로 실행하면 우회되고, 배포 Secrets는 레포 단위라 어느 브랜치에서나 쓸 수 있다 | GitHub Environment `prod`·`dev`를 만들어 배포 브랜치를 제한하고 `DEPLOY_*` Secrets를 Environment로 옮김 |
| main으로 가는 PR 출발 브랜치 검사가 이름만 봄 | ruleset `protect-main-dev`가 PR·`verify`·`source-branch`(dev, `release/review-N`만 허용, #180)를 요구한다. 승인 수는 0이라 이름을 맞춘 브랜치는 통과한다 | main 전용 ruleset으로 승인 1개 이상 요구 |
| k3s 배포 role이 large에서 임의 명령을 root로 실행할 수 있음 | trust를 Environment `prod`(`main`만)로 제한. `main`에 병합된 workflow만 쓴다 | 토큰 발급만 하는 전용 SSM 문서로 권한 축소 |
| GitHub Actions 메이저 버전 태그(`@v7` 등) 사용 | 액션 저장소가 오염되면 영향 | 커밋 SHA로 고정 |
| DB 백업 없음 | 수동 절차만 | 정기 백업 결정 |

## 배포 키 교체

유출이 의심되거나 담당자가 바뀌면 바로 교체한다.

1. [server-setup.md#5-배포-키github-secrets](server-setup.md#5-배포-키github-secrets)로 새 키를 만들어 서버 `authorized_keys`에 **추가**한다.
2. FE·BE 레포의 `DEPLOY_SSH_KEY`를 새 개인 키로 바꾼다.
3. dev의 최근 BE Deploy run(FE는 아무 Deploy run)에서 `deploy` job을 재실행해 새 키로 성공하는지 확인한다.
4. 서버 `authorized_keys`에서 **이전 키 줄을 삭제**한다: `sudo -u deploy vi /home/deploy/.ssh/authorized_keys`

## DB 비밀번호 교체

PostgreSQL은 처음 초기화할 때만 env의 비밀번호를 쓰므로, DB 안의 비밀번호와 env 파일을 함께 바꾼다(예: dev).

```bash
cd /opt/hapbang
NEW=$(openssl rand -hex 16)
sudo -u deploy docker compose -f compose.dev.yaml --env-file env/dev.env exec -T postgres \
  sh -c "psql -U \"\$POSTGRES_USER\" -d \"\$POSTGRES_DB\" -c \"ALTER USER \\\"\$POSTGRES_USER\\\" PASSWORD '$NEW'\"" > /dev/null
sudo -u deploy sed -i "s/^POSTGRES_PASSWORD=.*/POSTGRES_PASSWORD=$NEW/" /opt/hapbang/env/dev.env
unset NEW
```

그다음 dev의 최근 BE Deploy run → `deploy` job을 재실행해 app이 새 비밀번호로 다시 뜨게 한다.

## 유출이 의심될 때

1. **무엇이** 유출됐는지 확인한다(위 목록).
2. 해당 자격 증명을 **즉시 교체**한다. 커밋에 올라갔다면 히스토리에서 지우는 것보다 교체가 먼저다(공개 레포는 이미 복제됐을 수 있다).
3. AWS 관련(키 페어, 액세스 키)이면 AWS 담당자와 운영진에게 바로 알린다.
4. 영향 범위를 확인한다: 서버 `sudo journalctl -u ssh --since "-7 days"`, 컨테이너 로그, GitHub 감사 로그.
5. 원인과 조치를 팀에 공유한다.

## 금지 사항

- `.env`, `*.env`, `*.pem`, 개인 키를 커밋하거나 채팅·노션에 붙여넣기
- 서버 env 파일 내용을 `cat`해 화면 공유·캡처
- 비밀값을 로그에 출력하는 코드(`log.info(secret)`), Actions에서 `echo $SECRET`
- 배포 키를 사람이 서버 접속용으로 사용
- 문서·PR에 실제 IP, 계정 ID, 키 파일 이름, 서버 DB 이름·계정 기재
