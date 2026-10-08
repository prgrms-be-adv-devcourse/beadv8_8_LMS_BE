# 서버 설정 (최초·재구축)

새 EC2에 배포 환경을 처음부터 만드는 절차다. 지금 서버는 이미 구성되어 있으므로 재구축하거나 다른 서버로 옮길 때 쓴다.

## 서버 구성

서버에는 이미지를 받아 띄우는 데 필요한 파일만 둔다. 레포는 clone하지 않는다.

```
/opt/hapbang/                                   (소유자 deploy)
├── compose.prod.yaml  compose.dev.yaml  compose.edge.yaml  nginx/    ← BE 레포 docker/에서 복사
└── env/  prod.env  dev.env  edge.env           (권한 600) ← 비밀값. docker/.env.example 참고
/usr/local/bin/hapbang-deploy                   (소유자 root) ← docker/deploy.sh
/home/deploy/.ssh/authorized_keys               ← 배포 키 (deploy.sh만 실행 가능)
```

| 계정 | 용도 |
| --- | --- |
| `ubuntu` | 사람이 접속해 관리한다(sudo). EC2 키 페어로 접속 |
| `deploy` | 컨테이너 실행 전용(docker 그룹). GitHub Actions는 이 계정에 배포 키로 접속하며, 셸 없이 `hapbang-deploy`만 실행된다 |

## 사전 준비

- Ubuntu 24.04, Docker Engine + Compose 플러그인(Docker 공식 apt 저장소. snap 버전은 `/opt` 마운트가 막혀 쓸 수 없다)
- 보안 그룹 인바운드: 22(SSH, 키 인증만), 80, 443
- 도메인 2개(prod, dev)가 서버 IP를 가리킬 것 (DuckDNS: `<이름>.duckdns.org`, `dev.<이름>.duckdns.org`)
- 서버 SSH 설정: 비밀번호 로그인 꺼짐 확인 — `sudo sshd -T | grep -E '^(passwordauthentication|permitrootlogin)'` → `no` / `without-password`
- 서버 시간대: `sudo timedatectl set-timezone Asia/Seoul`

## 1. 계정·디렉터리·네트워크

```bash
sudo useradd --create-home --shell /bin/bash --groups docker deploy
sudo install -d -o deploy -g deploy /opt/hapbang /opt/hapbang/env
sudo docker network create hapbang-edge
```

## 2. 설정 파일

[operations.md#설정-반영](operations.md#설정-반영)의 로컬 `scp` → 서버 반영 절차를 그대로 한다(검증 중 `nginx -t`는 인증서가 없어 실패하므로 4번 이후에 확인한다).

## 3. 비밀값

DB 비밀번호는 서버에서 무작위로 만들어 파일에만 기록한다. **한 번만 실행한다**(PostgreSQL은 처음 초기화할 때의 값을 쓴다).
DB 이름·계정도 추측하기 어려운 값으로 정하고, 이 레포나 문서에 적지 않는다.

```bash
for e in dev prod; do
sudo -u deploy sh -c "umask 077; cat > /opt/hapbang/env/$e.env" <<EOF
POSTGRES_DB=<DB 이름>
POSTGRES_USER=<DB 계정>
POSTGRES_PASSWORD=$(openssl rand -hex 16)
EOF
done

sudo -u deploy sh -c 'umask 077; cat > /opt/hapbang/env/edge.env' <<'EOF'
PROD_HOST=<이름>.duckdns.org
DEV_HOST=dev.<이름>.duckdns.org
EOF

sudo ls -l /opt/hapbang/env      # 모두 -rw------- deploy deploy
```

추가 환경변수는 [env-vars.md](env-vars.md).

## 4. HTTPS 인증서

Let's Encrypt 인증서 하나에 prod·dev 도메인을 담는다(`--cert-name hapbang`). 80 포트를 쓰므로 edge가 떠 있으면 먼저 내린다(재발급 시 1분 내외 접속 중단).

```bash
cd /opt/hapbang
sudo -u deploy docker compose -f compose.edge.yaml --env-file env/edge.env down
sudo -u deploy sh -c 'set -a; . /opt/hapbang/env/edge.env; cd /opt/hapbang && \
  docker compose -f compose.edge.yaml --env-file env/edge.env run --rm -p 80:80 --entrypoint certbot certbot \
    certonly --standalone --non-interactive --agree-tos --register-unsafely-without-email \
    --cert-name hapbang -d "$PROD_HOST" -d "$DEV_HOST"'
```

`Successfully received certificate`가 나오면 `nginx -t` 검증 후 edge를 띄운다.

```bash
sudo -u deploy docker compose -f compose.edge.yaml --env-file env/edge.env \
  run --rm --no-deps --entrypoint /docker-entrypoint.sh nginx nginx -t
sudo -u deploy docker compose -f compose.edge.yaml --env-file env/edge.env up -d
sudo -u deploy docker compose -f compose.edge.yaml --env-file env/edge.env exec certbot \
  certbot renew --dry-run --webroot -w /var/www/certbot        # 자동 갱신 경로 점검
```

이후 갱신은 자동이다(certbot 12시간마다 renew, nginx 6시간마다 reload). 도메인을 바꿀 때도 이 절차를 반복하고 `up -d --force-recreate`한다.

## 5. 배포 키·GitHub Secrets

로컬(레포 밖 디렉터리)에서 배포 전용 키를 만든다. 이 키는 다른 용도로 쓰지 않는다.

```bash
mkdir -p ~/hapbang-deploy-key && cd ~/hapbang-deploy-key
ssh-keygen -t ed25519 -N '' -C github-actions-deploy -f deploy_key
cat deploy_key.pub
```

서버에 공개 키를 **제한 옵션과 함께** 등록한다. `command=...,restrict`가 빠지면 이 키로 셸이 열린다.

```bash
sudo install -d -o deploy -g deploy -m 700 /home/deploy/.ssh
echo 'command="/usr/local/bin/hapbang-deploy",restrict <deploy_key.pub 내용>' \
  | sudo -u deploy tee -a /home/deploy/.ssh/authorized_keys
sudo chmod 600 /home/deploy/.ssh/authorized_keys
sudo ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub     # 지문 메모
```

로컬에서 호스트 키를 받아 지문을 대조하고, 키 제한을 확인한다.

```bash
ssh-keyscan -t ed25519 <EC2 주소> > known_hosts
ssh-keygen -lf known_hosts                                     # 서버에서 메모한 지문과 같아야 한다
ssh -i deploy_key -o UserKnownHostsFile=known_hosts deploy@<EC2 주소> hack
# → "사용법: deploy.sh ..."만 출력되고 끝나면 정상 (셸이 열리면 안 된다)
```

FE·BE 레포 각각 Settings → Secrets and variables → Actions에 등록한다(또는 `gh secret set`).

| Secret | 값 |
| --- | --- |
| `DEPLOY_SSH_KEY` | `deploy_key`(개인 키) 전체 |
| `DEPLOY_KNOWN_HOSTS` | `known_hosts` 내용 |
| `DEPLOY_HOST` | EC2 주소. **서버 준비가 끝난 뒤 마지막에** 등록한다(없으면 deploy job이 배포를 건너뛴다) |

등록 후 로컬 키를 삭제한다: `rm -rf ~/hapbang-deploy-key`. 키를 잃어버려도 새로 만들어 서버·Secrets만 교체하면 된다.

## 6. 첫 기동

GHCR 패키지는 private이다. 수동으로 pull하는 이번만 개인 토큰(classic PAT, `read:packages`만, 만료 1일)으로 로그인하고 끝나면 로그아웃·토큰 삭제한다.

```bash
cd /opt/hapbang
read -rs PAT && echo "$PAT" | sudo -u deploy docker login ghcr.io -u <GitHub 아이디> --password-stdin; unset PAT
sudo -u deploy hapbang-deploy dev app
sudo -u deploy hapbang-deploy dev web
sudo -u deploy hapbang-deploy prod app
sudo -u deploy hapbang-deploy prod web
sudo -u deploy docker logout ghcr.io
```

이후 배포는 GitHub Actions가 한다. 각 레포의 최근 Deploy run에서 `deploy` job을 재실행해 동작을 확인한다.

## 7. 확인

- https://dev 도메인, https://prod 도메인 접속, http는 https로 301
- dev `/swagger-ui.html` 열림, prod는 403
- `ps`에서 app·web·postgres `(healthy)`, 이미지 태그 `:sha-<커밋>`
- [operations.md#상태-확인](operations.md#상태-확인)
