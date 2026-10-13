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
| 최근 배포 | 각 레포 Actions → Deploy (BE 운영은 Deploy prod) |
| 서비스 응답 | 브라우저로 prod·dev 주소. `/api/...`는 인증 전 403이 정상 |
| 컨테이너 | `dev ps`, `prod ps`, `edge ps` — app·web·postgres가 `(healthy)` |
| 앱·DB 상태 | app의 `(healthy)`는 `/actuator/health`가 UP(DB 연결 포함)이라는 뜻이다. 직접 확인: `dev exec app bash -c 'exec 3<>/dev/tcp/127.0.0.1/8080; printf "GET /actuator/health HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n" >&3; cat <&3'` (이미지에 curl이 없다). 외부(nginx)로는 열려 있지 않다 |
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

## 운영 배포

BE를 운영(prod)에 올리는 절차다. `main`에 병합해도 운영은 바뀌지 않는다. 아래 1~6단계를 순서대로 한다. 실패하면 [배포 실패 대응](#배포-실패-대응), 되돌릴 때는 [롤백](#롤백)을 본다.

**준비**

| 항목 | 내용 |
| --- | --- |
| 시간 | EC2가 켜져 있는 09:00~18:00. 18:00 직전은 피한다(서버가 꺼지면 배포가 중단된다) |
| 권한 | BE 레포 쓰기 권한(Actions 실행) |
| 로컬 | BE 레포 클론. 터미널 명령을 쓰려면 `gh`(GitHub CLI) 로그인 |
| 서버 접속 | 3·4단계에서 쓴다. EC2 키 파일과 주소는 AWS 담당자에게 받는다 |

### 1. dev → main 병합

GitHub에서 base `main` ← compare `dev` PR을 만들어 병합한다.

- **FE도 함께 내보낼 때**: FE는 `main` 병합 즉시 운영에 배포된다. 새 FE가 이번 BE 변경(새 API 등)을 쓰면, **BE 운영 배포(5·6단계)를 끝낸 뒤** FE를 `main`에 병합한다.

### 2. main 빌드 확인, 배포할 sha 정하기

병합하면 Deploy 워크플로가 테스트하고 이미지를 만든다(몇 분 걸린다). 성공해야 배포할 수 있다.

- 화면: Actions → **Deploy** → 방금 병합한 `main` run. `ci / verify`·`build`가 초록(성공)이고 `deploy`가 회색(skipped)이면 정상이다. 이 run의 커밋 sha가 **배포할 sha**다.
- 터미널:

```bash
gh run list --workflow deploy.yml --branch main --event push --limit 1 --json databaseId,status,conclusion,headSha
gh run watch <databaseId> --exit-status    # 아직 진행 중이면 끝날 때까지 기다린다
```

`conclusion`이 `success`이면 `headSha`가 배포할 sha다. `failure`이면 배포할 수 없다. 원인을 고쳐 `dev` → `main`으로 다시 병합한다.

### 3. 지금 운영 버전 적어 두기

배포 후 문제가 생기면 이 sha로 되돌린다. 서버에서([별칭](#운영-가이드)을 먼저 정의한다):

```bash
prod ps --format '{{.Service}} {{.Image}}'    # app ghcr.io/...:sha-<지금 운영 sha>
```

서버에 접속할 수 없으면 최근 성공한 Deploy prod run의 Summary("운영 배포 대상")에 적힌 커밋을 쓴다.

### 4. 서버 설정(compose.prod.yaml) 확인

서버의 `compose.prod.yaml`이 `main`과 다르면 5단계가 배포 전에 멈춘다(운영은 그대로). 먼저 두 파일의 해시를 비교한다.

```bash
# 로컬 (BE 레포)
git fetch origin
git show origin/main:docker/compose.prod.yaml | shasum -a 256

# 서버
sha256sum /opt/hapbang/compose.prod.yaml
```

**두 값이 같으면** 5단계로 간다. **다르면** 무엇이 바뀌었는지 본다.

```bash
git diff <3단계의 운영 sha> origin/main -- docker/compose.prod.yaml
```

1. `app:`의 `environment:`에 새 줄(`KEY: ${KEY:?...}`)이 있으면 서버 `prod.env`에 그 값을 **먼저** 넣는다([env-vars.md#서버-env-파일-편집](env-vars.md#서버-env-파일-편집)). 값은 그 기능 담당자에게 받는다.
2. `main`의 `compose.prod.yaml`만 서버에 올려 검증하고 반영한다.

```bash
# 로컬
git show origin/main:docker/compose.prod.yaml > /tmp/compose.prod.yaml
ssh -i <키 파일> ubuntu@<EC2 주소> 'mkdir -p ~/hapbang-config'
scp -i <키 파일> /tmp/compose.prod.yaml ubuntu@<EC2 주소>:~/hapbang-config/

# 서버: 검증 → "prod: ok"가 나오면 반영
cd ~/hapbang-config
sudo docker compose -f compose.prod.yaml --env-file /opt/hapbang/env/prod.env config --quiet && echo "prod: ok"
sudo cp compose.prod.yaml /opt/hapbang/compose.prod.yaml
sudo chown deploy:deploy /opt/hapbang/compose.prod.yaml
```

- 검증이 `required variable ... is missing`으로 실패하면 1번(`prod.env`)이 빠진 것이다. 반영하지 말고 1번부터 한다.
- `compose.dev.yaml`은 올리지 않는다. `dev`가 `main`보다 앞서 있을 수 있어서, 덮어쓰면 dev 배포가 실패한다. `nginx/`·`compose.edge.yaml`·`deploy.sh`는 `dev` 병합 때 반영한다([설정 반영](#설정-반영)).

### 5. Deploy prod 실행

- 화면
  1. BE 레포 → **Actions** 탭 → 왼쪽 목록에서 **Deploy prod**
  2. 오른쪽 **Run workflow** → `Use workflow from`이 **main**인지 확인
  3. "배포할 main 커밋 sha(7~40자)" 칸에 2단계의 sha(앞 7자 이상)를 넣는다
  4. 초록색 **Run workflow**를 누른다
- 터미널

```bash
gh workflow run deploy-prod.yml --ref main -f sha=<2단계의 sha>
gh run list --workflow deploy-prod.yml --limit 1     # 방금 실행한 run의 id
gh run watch <id> --exit-status
```

`resolve`(sha·이미지 검사) → `deploy / deploy`(서버 설정 비교 → 배포) 순서로 진행된다. 새 app이 healthy가 될 때까지 최대 180초 기다린다.

### 6. 확인

- run의 `resolve`와 `deploy / deploy`가 모두 초록이다. run의 Summary "운영 배포 대상"에 배포한 커밋이 나온다.
- `deploy / deploy` → "EC2 배포" 로그 끝에 `[deploy] prod app 완료`가 있고, 그 위 표에서 app이 `sha-<배포한 sha>`, `(healthy)`다.
- 브라우저로 운영 주소([README](README.md))에 접속해 이번에 바뀐 기능을 확인한다.

- **실패했을 때**: run에서 빨간 job의 오류 문구를 [배포 실패 대응](#배포-실패-대응) 표에서 찾는다. `resolve`나 서버 설정 비교에서 멈췄으면 운영은 바뀌지 않았다. 새 app이 healthy가 되지 않으면 이전 버전으로 자동 롤백된다.
- **배포는 성공했는데 기능에 문제가 있을 때**: 3단계의 sha로 5단계를 다시 실행한다([롤백](#롤백)).

## 배포 실패 대응

Actions의 Deploy run(BE 운영은 Deploy prod run)에서 실패한 job을 먼저 본다. 아래의 `deploy` job 재실행은 Deploy prod run에서도 같다(`Re-run failed jobs`).

| 실패 위치 | 원인 | 대응 |
| --- | --- | --- |
| `ci` | 테스트·린트·빌드·이미지 빌드 확인 실패 | 코드 수정 후 다시 병합 |
| `build` | jar 받기·GHCR push 실패 | 로그 확인. 일시적이면 Re-run(jar artifact는 1일 보관) |
| `resolve` — `main 브랜치에서 실행하세요` | Deploy prod를 main이 아닌 브랜치로 실행 | Run workflow에서 `Use workflow from: main`을 골라 다시 실행 |
| `resolve` — `main에 포함되지 않은 커밋입니다` / `커밋을 찾을 수 없거나` | dev에만 있는 커밋, 오타, 짧은 sha가 여러 커밋에 해당 | main의 커밋 sha를 확인해 다시 실행(모호하면 더 길게) |
| `resolve` — `이미지 ... 가 없습니다` | 그 커밋의 Deploy run이 실패(테스트 등)했거나 아직 진행 중 | 그 커밋의 Deploy run을 확인. 끝난 뒤 다시 실행 |
| `deploy` — SSH 연결 타임아웃 | EC2 꺼짐(18:00 이후) | 서버가 켜진 뒤 `deploy` job Re-run |
| `deploy` — `Host key verification failed` | 서버 재구축 등으로 호스트 키 변경 | `DEPLOY_KNOWN_HOSTS` Secret 갱신([server-setup.md](server-setup.md#5-배포-키github-secrets)) |
| `deploy` — `Permission denied (publickey)` | 배포 키 불일치 | 서버 `authorized_keys`와 `DEPLOY_SSH_KEY` 확인 |
| `deploy` — `잘못된 GHCR 인증 정보` / `denied` | GHCR 토큰 문제, 패키지 권한 | 패키지 설정 → Manage Actions access에 레포가 있는지 확인 |
| `deploy` — `healthy가 되지 않음` → `롤백 완료` | 새 버전이 기동 실패(설정 누락, DB 접속 실패 등) | 로그에 찍힌 앱 로그로 원인 확인. **서비스는 이전 버전으로 동작 중** |
| `deploy` — `롤백도 실패` | 이전 버전도 뜨지 않음(DB 등 공통 원인) | 서버에서 `dev logs app`, `dev logs postgres` 확인 |
| `deploy` — `required variable ... is missing` | 서버 env 파일에 값 없음 | [env-vars.md](env-vars.md) 2번. 기존 컨테이너는 그대로 동작 중 |
| `deploy` — `서버의 compose.<env>.yaml 가 ... 달라 배포하지 않습니다` | 배포 환경의 compose 반영 누락. **배포 전에 멈췄으므로 서비스는 이전 버전으로 동작 중** | 아래 [설정 반영](#설정-반영) 후 `deploy` job Re-run |
| `deploy` — `서버 설정 확인에 실패했습니다` | 서버의 deploy.sh가 옛 버전(`check` 미지원) | [설정 반영](#설정-반영)(deploy.sh 포함) 후 `deploy` job Re-run |
| 경고 `서버의 ... 가 레포와 다릅니다` | 다른 환경 compose·edge·nginx·deploy.sh 반영 누락(배포는 진행됨) | [설정 반영](#설정-반영) |

## 롤백

**BE 운영(prod)**: BE 레포 Actions → Deploy prod → Run workflow(`main`)에 **되돌릴 버전(정상이던 커밋)의 sha**를 넣고 실행한다. 그 커밋의 이미지(`:sha-<커밋>`)로 교체된다. 다시 최신으로 올릴 때도 같은 방법으로 최신 sha를 넣는다.

**BE dev, FE(prod·dev)**

1. 각 레포 Actions → Deploy에서 **되돌릴 버전(정상이던 커밋)의 run**을 연다.
2. `Re-run jobs` → **`deploy`만** 선택해 다시 실행한다. 그 run의 커밋 이미지(`:sha-<커밋>`)로 교체된다.

FE·BE는 각각 롤백한다. DB 스키마가 바뀐 배포는 이미지 롤백만으로 되돌아가지 않을 수 있다.

**#72 이전 버전으로 롤백할 때**: app 헬스체크가 `/actuator/health`를 호출하는데, #72 이전 이미지에는 이 경로가 없어(401) healthy가 되지 않는다. 그래서 자동 롤백과 수동 롤백이 모두 실패한다. 그 버전으로 되돌려야 하면 서버에서 직접 처리한다. 서버 `compose.<env>.yaml`이 레포와 달라지면 Actions의 deploy job이 막히기 때문이다.

1. 서버 `/opt/hapbang/compose.<env>.yaml`에서 app의 `healthcheck.test`를 포트 확인 방식(`["CMD", "bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/8080"]`)으로 바꾼다.
2. 서버에서 `sudo -u deploy /usr/local/bin/hapbang-deploy <env> app <되돌릴 커밋 SHA>`를 실행한다.
3. 다음 정상 배포 전에 [설정 반영](#설정-반영)으로 compose를 레포와 다시 맞춘다.

## 설정 반영

`docker/`의 `compose.*.yaml`, `nginx/`, `deploy.sh`는 서버에 자동으로 반영되지 않는다(배포 키로 서버 설정을 바꿀 수 없게 하기 위함). PR 병합과 함께 직접 반영한다.

BE 배포는 시작 전에 서버 설정을 **배포 브랜치의 최신 파일**(dev: `dev`, 운영: `main`)과 비교한다. 배포 환경의 `compose.<env>.yaml`이 다르면 배포하지 않고 실패하며, 나머지 파일은 경고만 한다. `compose.dev.yaml`을 바꾼 PR이 `dev`에 병합되면 Deploy가 실패하는 것이 정상이다. 반영한 뒤 `deploy` job을 다시 실행한다.

- `compose.dev.yaml`은 `dev` 병합 때, `compose.prod.yaml`은 `main` 병합 뒤 **운영 배포(Deploy prod) 전에** 반영한다. 서버에는 파일이 한 벌뿐이므로, 미리 반영하면 아직 반영 전인 브랜치의 배포가 실패한다.
- `main` 병합은 배포하지 않으므로 compose가 달라도 실패하지 않는다. 반영하지 않고 Deploy prod를 실행하면 그때 실패한다.

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
| `compose.<env>.yaml`의 app·postgres | dev: 최근 **BE** Deploy run → `deploy` job 재실행. prod: **지금 운영 중인 커밋 sha**([실행 중인 버전](#상태-확인))로 Deploy prod 실행. (또는 다음 BE 배포) |
| `compose.<env>.yaml`의 web | 해당 환경의 최근 **FE** Deploy run → `deploy` job 재실행 (또는 다음 FE 배포) |
| `nginx/`, `compose.edge.yaml` | `edge up -d --force-recreate` (1~2초 끊김) |
| `deploy.sh` | 다음 배포부터 적용 |

## 하지 말 것

| 하지 말 것 | 이유 | 대신 |
| --- | --- | --- |
| 서버에서 `docker compose ... up` 직접 실행(prod·dev) | 이미지 태그가 지정되지 않아 서버에 남아 있던 **예전 `:dev`/`:latest` 이미지로 바뀔 수 있다** | dev: 최근 Deploy run의 `deploy` job 재실행. BE 운영: 운영 중인 sha로 Deploy prod 실행 |
| `docker compose ... down -v` | **DB 볼륨이 삭제된다** | 필요하면 [DB 초기화](#db-초기화) 절차 |
| `docker system prune -a --volumes` | 이미지·DB 볼륨·인증서 볼륨까지 삭제 | `docker image prune -f` |
| env 파일 내용을 `cat`해서 화면 공유·캡처 | 비밀값 노출 | `grep -c '^KEY='`로 존재만 확인 |
| `.env`·키 파일을 레포·채팅·노션에 올림 | 공개 레포, AWS 키 유출 2OUT 규정 | [security.md](security.md) |
| 18:00 직전 배포·배치 작업 | 서버 자동 종료로 중단될 수 있다 | 다음 날 오전 |

## EC2 자동 시작·중지

- 09:00에 켜지면 Docker가 자동으로 시작되고, 모든 컨테이너가 `restart: unless-stopped`로 직전 상태 그대로 올라온다. 할 일은 없다.
- 18:00 이후 작업이 필요하면 AWS 콘솔에서 인스턴스를 직접 시작하고, 끝나면 중지한다.

## DB 접속 (DataGrip 등)

PostgreSQL은 서버 자신(`127.0.0.1`)에만 포트를 연다. 인터넷에서는 접근할 수 없고, **SSH 터널**로만 접속한다.

| 환경 | 서버 내부 포트 |
| --- | --- |
| prod | `127.0.0.1:15432` |
| dev | `127.0.0.1:15433` |

DataGrip → New Data Source → PostgreSQL

| 탭 | 항목 | 값 |
| --- | --- | --- |
| SSH/SSL | Use SSH tunnel | Host `<EC2 주소>`, Port `22`, User `ubuntu`, Auth type `Key pair`, Private key `<키 파일>` |
| General | Host / Port | `127.0.0.1` / `15432`(prod) 또는 `15433`(dev) |
| General | Database / User | 서버 env 파일의 `POSTGRES_DB` / `POSTGRES_USER` 값 |
| General | Password | 서버 env 파일의 `POSTGRES_PASSWORD` 값 |

DB 이름·계정·비밀번호는 서버에서 확인한다(화면 공유 중에는 실행하지 않는다). 레포·채팅·노션에 옮겨 적지 않는다.

```bash
sudo -u deploy grep -E '^POSTGRES_(DB|USER|PASSWORD)=' /opt/hapbang/env/dev.env
```

터미널로 터널을 열 때:

```bash
ssh -i <키 파일> -N -L 15433:127.0.0.1:15433 ubuntu@<EC2 주소>   # 로컬 localhost:15433 → dev DB
```

- **prod는 조회 위주로 쓴다.** 앱과 같은 권한의 계정이라 `UPDATE`·`DELETE`·DDL도 실행된다. 데이터를 바꿔야 하면 먼저 [백업](#db-백업복원)한다.
- DataGrip 연결 설정에 비밀번호를 저장할 때는 OS 키체인(기본값)에 두고, 설정 파일을 공유·커밋하지 않는다.
- DataGrip의 Read-only 옵션(연결 설정 → Options → Read-only)을 prod 연결에 켜 두면 실수로 쓰는 것을 막을 수 있다.

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

이후 Actions에서 dev의 최근 BE Deploy run → `deploy` job을 재실행하면 postgres(새 볼륨)와 app이 올라온다. prod는 `dev`를 `prod`로 바꾸고, 운영 중이던 sha로 Deploy prod를 실행한다.

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

- **prod는 SHA를 꼭 지정한다.** `:latest`는 `main`의 최신 빌드이지 운영 중인 버전이 아니다(BE 운영은 Deploy prod로 따로 배포한다).
