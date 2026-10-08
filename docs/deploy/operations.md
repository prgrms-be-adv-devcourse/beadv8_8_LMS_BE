# 운영 가이드

서버 작업은 EC2에 `ubuntu` 계정으로 접속해서 한다. 접속 정보(키 파일, IP)는 AWS 담당자에게 받는다. 아래 명령은 모두 서버에서 실행하며, 대부분 `/opt/hapbang`에서 실행한다고 가정한다.

```bash
cd /opt/hapbang
# 자주 쓰는 별칭 (세션마다)
dev()  { sudo -u deploy docker compose -f /opt/hapbang/compose.dev.yaml  --env-file /opt/hapbang/env/dev.env  "$@"; }
prod() { sudo -u deploy docker compose -f /opt/hapbang/compose.prod.yaml --env-file /opt/hapbang/env/prod.env "$@"; }
edge() { sudo -u deploy docker compose -f /opt/hapbang/compose.edge.yaml --env-file /opt/hapbang/env/edge.env "$@"; }
```

## 상태 확인

| 확인 | 방법 |
| --- | --- |
| 최근 배포 | 각 레포 Actions → Deploy |
| 서비스 응답 | 브라우저로 prod·dev 주소. `/api/...`는 인증 전 403이 정상 |
| 컨테이너 | `dev ps`, `prod ps`, `edge ps` — app·web·postgres가 `(healthy)` |
| 실행 중인 버전 | `dev ps --format '{{.Service}} {{.Image}}'` → `:sha-<커밋>` |
| 인증서 | `edge exec certbot certbot certificates` (남은 일수) |
| 디스크 | `df -h /`, `sudo docker system df` |

## 로그

```bash
dev logs -f --tail 100 app        # Spring
dev logs --tail 100 web           # FE nginx
dev logs --tail 100 postgres
edge logs --tail 100 nginx        # 접근 로그·프록시 오류(502 원인 등)
edge logs --tail 50 certbot       # 인증서 갱신
```

로그는 컨테이너당 10MB × 3개까지만 보관된다. 컨테이너가 새로 만들어지면 이전 로그는 사라진다.

## 배포 실패 대응

Actions의 Deploy run에서 실패한 job을 먼저 본다.

| 실패 위치 | 원인 | 대응 |
| --- | --- | --- |
| `ci` | 테스트·린트·빌드 실패 | 코드 수정 후 다시 병합 |
| `build` | Docker 빌드·GHCR push 실패 | 로그 확인. 일시적이면 Re-run |
| `deploy` — SSH 연결 타임아웃 | EC2 꺼짐(18:00 이후) | 서버가 켜진 뒤 `deploy` job Re-run |
| `deploy` — `Host key verification failed` | 서버 재구축 등으로 호스트 키 변경 | `DEPLOY_KNOWN_HOSTS` Secret 갱신([server-setup.md](server-setup.md#5-배포-키github-secrets)) |
| `deploy` — `Permission denied (publickey)` | 배포 키 불일치 | 서버 `authorized_keys`와 `DEPLOY_SSH_KEY` 확인 |
| `deploy` — `잘못된 GHCR 인증 정보` / `denied` | GHCR 토큰 문제, 패키지 권한 | 패키지 설정 → Manage Actions access에 레포가 있는지 확인 |
| `deploy` — `healthy가 되지 않음` → `롤백 완료` | 새 버전이 기동 실패(설정 누락, DB 접속 실패 등) | 로그에 찍힌 앱 로그로 원인 확인. **서비스는 이전 버전으로 동작 중** |
| `deploy` — `롤백도 실패` | 이전 버전도 뜨지 않음(DB 등 공통 원인) | 서버에서 `dev logs app`, `dev logs postgres` 확인 |
| `deploy` — `required variable ... is missing` | 서버 env 파일에 값 없음 | [env-vars.md](env-vars.md) 2번. 기존 컨테이너는 그대로 동작 중 |
| 경고 `서버의 ... 가 레포와 다릅니다` | 설정 반영 누락 | 아래 [설정 반영](#설정-반영) |

## 롤백

1. 각 레포 Actions → Deploy에서 **되돌릴 버전(정상이던 커밋)의 run**을 연다.
2. `Re-run jobs` → **`deploy`만** 선택해 다시 실행한다. 그 run의 커밋 이미지(`:sha-<커밋>`)로 교체된다.

FE·BE는 각각 롤백한다. DB 스키마가 바뀐 배포는 이미지 롤백만으로 되돌아가지 않을 수 있다.

## 설정 반영

`docker/`의 `compose.*.yaml`, `nginx/`, `deploy.sh`는 서버에 자동으로 반영되지 않는다(배포 키로 서버 설정을 바꿀 수 없게 하기 위함). PR 병합과 함께 직접 반영한다.

**로컬** (BE 레포 루트, 반영할 커밋으로 체크아웃한 상태)

```bash
ssh -i <키 파일> ubuntu@<EC2 주소> 'mkdir -p ~/hapbang-config'
scp -i <키 파일> -r docker/compose.prod.yaml docker/compose.dev.yaml docker/compose.edge.yaml \
    docker/nginx docker/deploy.sh ubuntu@<EC2 주소>:~/hapbang-config/
```

**서버**: 검증 → 반영

```bash
cd ~/hapbang-config
for e in prod dev edge; do
  sudo docker compose -f compose.$e.yaml --env-file /opt/hapbang/env/$e.env config --quiet && echo "$e: ok"
done
sudo docker compose -f compose.edge.yaml --env-file /opt/hapbang/env/edge.env \
  run --rm --no-deps --entrypoint /docker-entrypoint.sh nginx nginx -t
bash -n deploy.sh && echo "deploy.sh: ok"

# 모두 통과하면
sudo cp -r ~/hapbang-config/compose.*.yaml ~/hapbang-config/nginx /opt/hapbang/
sudo chown -R deploy:deploy /opt/hapbang
sudo install -o root -g root -m 755 ~/hapbang-config/deploy.sh /usr/local/bin/hapbang-deploy
```

반영 후 적용:

| 바뀐 파일 | 적용 방법 |
| --- | --- |
| `compose.<env>.yaml`의 app·postgres | 해당 환경의 최근 **BE** Deploy run → `deploy` job 재실행 (또는 다음 BE 배포) |
| `compose.<env>.yaml`의 web | 해당 환경의 최근 **FE** Deploy run → `deploy` job 재실행 (또는 다음 FE 배포) |
| `nginx/`, `compose.edge.yaml` | `edge up -d --force-recreate` (1~2초 끊김) |
| `deploy.sh` | 다음 배포부터 적용 |

## 하지 말 것

| 하지 말 것 | 이유 | 대신 |
| --- | --- | --- |
| 서버에서 `docker compose ... up` 직접 실행(prod·dev) | 이미지 태그가 지정되지 않아 서버에 남아 있던 **예전 `:dev`/`:latest` 이미지로 바뀔 수 있다** | 최근 Deploy run의 `deploy` job 재실행 |
| `docker compose ... down -v` | **DB 볼륨이 삭제된다** | 필요하면 [DB 초기화](#db-초기화) 절차 |
| `docker system prune -a --volumes` | 이미지·DB 볼륨·인증서 볼륨까지 삭제 | `docker image prune -f` |
| env 파일 내용을 `cat`해서 화면 공유·캡처 | 비밀값 노출 | `grep -c '^KEY='`로 존재만 확인 |
| `.env`·키 파일을 레포·채팅·노션에 올림 | 공개 레포, AWS 키 유출 2OUT 규정 | [security.md](security.md) |
| 18:00 직전 배포·배치 작업 | 서버 자동 종료로 중단될 수 있다 | 다음 날 오전 |

## EC2 자동 시작·중지

- 09:00에 켜지면 Docker가 자동으로 시작되고, 모든 컨테이너가 `restart: unless-stopped`로 직전 상태 그대로 올라온다. 할 일은 없다.
- 18:00 이후 작업이 필요하면 AWS 콘솔에서 인스턴스를 직접 시작하고, 끝나면 중지한다.

## DB 백업·복원

자동 백업은 아직 없다(팀 결정 필요). 중요한 작업 전에는 수동으로 백업한다. 백업 파일은 서버에만 두고 외부로 옮길 때 주의한다(개인정보 포함 가능).

```bash
# 백업 (prod 예시)
sudo install -d -o deploy -g deploy -m 700 /opt/hapbang/backup
prod exec -T postgres sh -c 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc' \
  | sudo -u deploy tee /opt/hapbang/backup/prod-$(date +%Y%m%d-%H%M).dump > /dev/null
sudo ls -lh /opt/hapbang/backup

# 복원 (기존 데이터를 덮어쓴다)
sudo -u deploy cat /opt/hapbang/backup/<파일>.dump \
  | prod exec -T postgres sh -c 'pg_restore -U "$POSTGRES_USER" -d "$POSTGRES_DB" --clean --if-exists'
```

## DB 초기화

데이터를 모두 지우고 해당 환경의 DB를 새로 만든다. **되돌릴 수 없다.** prod는 먼저 백업한다.

```bash
dev stop app postgres
dev rm -f app postgres
sudo docker volume rm hapbang-dev_postgres-data
```

이후 Actions에서 해당 환경의 최근 BE Deploy run → `deploy` job을 재실행하면 postgres(새 볼륨)와 app이 올라온다. prod는 `dev`를 `prod`로 바꾼다.

## 인증서

자동 갱신된다(만료 30일 전부터). 확인과 점검:

```bash
edge exec certbot certbot certificates                               # 만료일
edge exec certbot certbot renew --dry-run --webroot -w /var/www/certbot   # 갱신 경로 점검
```

만료가 30일 이내인데 갱신되지 않았다면 `edge logs certbot`을 확인한다. 도메인을 바꿀 때는 [server-setup.md#4-https-인증서](server-setup.md#4-https-인증서).

## 디스크 정리

배포 때마다 사용하지 않는 이미지(dangling)는 자동 정리된다. 그래도 차면:

```bash
df -h /
sudo docker system df
sudo docker image prune -a -f --filter "until=168h"   # 7일 넘게 안 쓴 이미지 (실행 중인 것은 유지)
```

## 수동 배포

CI 없이 서버에서 배포 스크립트를 직접 실행한다. GHCR이 private이므로 새 이미지를 받으려면 개인 토큰(classic PAT, `read:packages`만, 짧은 만료)으로 잠깐 로그인한다. 가능하면 Actions 재실행을 쓴다.

```bash
read -rs PAT && echo "$PAT" | sudo -u deploy docker login ghcr.io -u <GitHub 아이디> --password-stdin; unset PAT
sudo -u deploy hapbang-deploy dev app <커밋 SHA 40자>    # SHA 생략 시 :dev / :latest
sudo -u deploy docker logout ghcr.io
```
