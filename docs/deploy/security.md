# 보안 가이드

## 원칙

- **이 레포는 공개(public)다.** 비밀값, 서버 IP, AWS 계정 정보, 키 파일 이름·내용을 레포·PR·이슈·커밋 메시지에 쓰지 않는다. 문서에는 `<EC2 주소>`, `<키 파일>`처럼 자리표시자만 쓴다.
- **비밀값은 한 곳에만 둔다.** 서버 env 파일 또는 GitHub Secrets. 채팅·노션·스크린샷으로 옮기지 않는다.
- **장기 토큰을 서버에 두지 않는다.** GHCR pull은 배포 job의 임시 토큰을 쓴다.
- AWS 지침: 액세스 키 등 유출 메일 2회 시 팀 AWS 지원 회수(2OUT), 계정 외부 공유·목적 외 사용은 즉시 회수(1OUT).

## 자격 증명 목록

| 자격 증명 | 보관 위치 | 접근 가능 | 용도 | 교체 |
| --- | --- | --- | --- | --- |
| EC2 키 페어(pem) | AWS 담당자 로컬 | 서버 관리자 | `ubuntu` SSH 접속 | AWS에서 새 키 페어 → 서버 `~ubuntu/.ssh/authorized_keys` 교체 |
| 배포 키 | GitHub Secrets `DEPLOY_SSH_KEY`(FE·BE) | Actions | `deploy` 접속 → `deploy.sh`만 실행 | [배포 키 교체](#배포-키-교체) |
| 서버 호스트 키 | GitHub Secrets `DEPLOY_KNOWN_HOSTS` | Actions | 서버 위조 방지 | 서버 재구축 시 갱신 |
| DB 비밀번호 | 서버 `/opt/hapbang/env/{prod,dev}.env` | 서버 관리자 | app → PostgreSQL | [DB 비밀번호 교체](#db-비밀번호-교체) |
| 앱 비밀값(OAuth·결제 등) | 서버 env 파일 | 서버 관리자 | Spring | [env-vars.md](env-vars.md#값-변경-비밀값-교체-포함) |
| GHCR 읽기 | 배포 job의 `GITHUB_TOKEN`(읽기 전용, job 종료 시 만료) | Actions | 서버의 이미지 pull | 자동 |
| 개인 PAT | 만들지 않고 둔다. 수동 pull 때만 `read:packages`·짧은 만료로 생성 후 삭제 | 사용자 본인 | 수동 pull | 사용 후 즉시 삭제 |
| DuckDNS 토큰 | DuckDNS 계정 소유자 | 소유자 | 도메인 IP 변경 | DuckDNS에서 재발급 |
| TLS 개인 키 | edge `letsencrypt` 볼륨 | 서버 | HTTPS | 재발급 시 자동 교체 |

## 구조상 보호 장치

| 장치 | 효과 |
| --- | --- |
| 배포 키 `command=...,restrict` | 키가 유출돼도 셸·포트포워딩 불가. 할 수 있는 것은 정해진 이미지 재배포뿐 |
| `deploy.sh` 입력 검증 | 환경·서비스·SHA·토큰 형식 외 입력 거부(명령 주입 차단) |
| 서버 설정 수동 반영 | 배포 키·Actions로 compose·nginx를 바꿀 수 없음 |
| GHCR private + 읽기 전용 임시 토큰 | 서버가 탈취돼도 레지스트리에 이미지를 올릴 수 없음 |
| 외부 포트는 edge(80·443)만 | app·DB에 직접 접근 불가 |
| 미등록 도메인 차단 | IP 직접 접근·스캐너에 응답하지 않음(80 연결 종료, 443 핸드셰이크 거부) |
| HTTPS + HSTS, forward-headers | 쿠키에 Secure, https 리다이렉트 |
| `.gitignore`·`.dockerignore` | `.env`, `.env.*`, `*.pem`이 커밋·이미지에 들어가지 않음 |
| SSH 비밀번호 로그인 꺼짐 | 22 포트가 열려 있어도 키 없이는 접속 불가 |

## 알려진 위험

| 위험 | 현재 | 개선안 |
| --- | --- | --- |
| dev가 인터넷에 공개(Swagger 포함) | 누구나 API 명세 조회·호출 가능 | edge에서 dev를 팀 IP 또는 Basic 인증으로 제한 |
| SSH 22 전체 개방 | Actions 배포 때문. 키 인증만 허용 | 접근 로그 주기 확인(`sudo journalctl -u ssh`) |
| `main`·`dev` 직접 push 가능 | 브랜치 보호 없음. CI 통과해야 배포는 됨 | 필요 시 ruleset |
| GitHub Actions 버전 태그(`@v4`) 사용 | 액션 저장소가 오염되면 영향 | 커밋 SHA로 고정 |
| DB 백업 없음 | 수동 절차만 | 정기 백업 결정 |

## 배포 키 교체

유출이 의심되거나 담당자가 바뀌면 바로 교체한다.

1. [server-setup.md#5-배포-키github-secrets](server-setup.md#5-배포-키github-secrets)로 새 키를 만들어 서버 `authorized_keys`에 **추가**한다.
2. FE·BE 레포의 `DEPLOY_SSH_KEY`를 새 개인 키로 바꾼다.
3. 아무 Deploy run의 `deploy` job을 재실행해 새 키로 성공하는지 확인한다.
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
- 문서·PR에 실제 IP, 계정 ID, 키 파일 이름 기재
