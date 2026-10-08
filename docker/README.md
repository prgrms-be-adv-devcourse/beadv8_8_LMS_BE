# 배포 구성

EC2 1대에 운영(prod)과 개발 서버(dev)를 함께 올린다.

```
push(main | dev) → GitHub Actions: 테스트(ci.yml) → 이미지 빌드 → GHCR push → ssh deploy@EC2 "<prod|dev> <web|app>"
                                                              └ deploy.sh (이 키로는 이것만 실행된다)

[인터넷] :80 ─ edge nginx ─┬─ PROD_HOST ─┬─ /api, /swagger-ui, /v3/api-docs → prod-app:8080
                          │             └─ 그 외 → prod-web:80
                          └─ DEV_HOST  ─┬─ /api, /swagger-ui, /v3/api-docs → dev-app:8080
                                        └─ 그 외 → dev-web:80
```

| 파일 | 역할 |
| --- | --- |
| `compose.yaml` | 로컬 개발용 PostgreSQL |
| `compose.prod.yaml` / `compose.dev.yaml` | 환경별 web(FE) + app(BE) + postgres. 외부 포트를 열지 않는다 |
| `compose.edge.yaml`, `nginx/` | 유일한 진입점(80). 도메인·경로 라우팅은 여기에만 둔다 |
| `Dockerfile` | BE 이미지 (FE 이미지는 FE 레포의 `Dockerfile`) |
| `deploy.sh` | 서버 배포 스크립트 (GitHub Actions가 SSH로 실행) |

| 브랜치 | 환경 | 이미지 태그 |
| --- | --- | --- |
| `main` | prod | `:latest` |
| `dev` | dev | `:dev` |

각 레포의 배포는 자기 컨테이너만 바꾼다. FE는 `<env> web`으로 해당 환경의 web만, BE는 `<env> app`으로 해당 환경의 app만 교체한다.

모든 이미지는 `:sha-<커밋>` 태그도 함께 올린다. 롤백은 `/opt/hapbang/env/{prod,dev}.env`에 `BE_TAG=sha-...` 또는 `FE_TAG=sha-...`를 넣고 `hapbang-deploy`를 실행한다.

## 서버 구성

서버에는 이미지를 받아 띄우는 데 필요한 파일만 둔다. 레포는 clone하지 않는다.

```
/opt/hapbang/
├── compose.prod.yaml  compose.dev.yaml  compose.edge.yaml  nginx/   # 이 폴더에서 복사
└── env/prod.env  dev.env  edge.env                                  # 비밀값 (.env.example 참고)
/usr/local/bin/hapbang-deploy                                        # deploy.sh
```

## 서버 최초 설정 (1회)

Docker Engine과 Compose 플러그인이 설치되어 있다고 가정한다. 서버에서 `ubuntu` 계정으로 실행한다.

```bash
# 1. 배포 전용 계정과 디렉터리
sudo useradd --create-home --shell /bin/bash --groups docker deploy
sudo install -d -o deploy -g deploy /opt/hapbang /opt/hapbang/env

# 2. 비밀값 (값은 환경마다 다르게)
sudo -u deploy sh -c 'umask 077; touch /opt/hapbang/env/prod.env /opt/hapbang/env/dev.env /opt/hapbang/env/edge.env'
#    prod.env / dev.env: POSTGRES_DB, POSTGRES_USER, POSTGRES_PASSWORD
#    edge.env: PROD_HOST, DEV_HOST

# 3. 네트워크
docker network create hapbang-edge

```

이어서 아래 "설정 반영"으로 파일을 올리고, 처음 한 번은 스택 전체를 띄운다.
GHCR 패키지는 private이므로 수동으로 pull할 때만 개인 토큰(classic PAT, `read:packages`만)으로 잠깐 로그인한다.

```bash
cd /opt/hapbang
sudo -u deploy docker login ghcr.io -u <GitHub 아이디>   # 비밀번호 자리에 PAT
sudo -u deploy docker compose -f compose.prod.yaml --env-file env/prod.env up -d
sudo -u deploy docker compose -f compose.dev.yaml  --env-file env/dev.env  up -d
sudo -u deploy docker compose -f compose.edge.yaml --env-file env/edge.env up -d
sudo -u deploy docker logout ghcr.io
```

배포 workflow는 서버에 로그인 정보를 두지 않는다. deploy job의 읽기 전용 `GITHUB_TOKEN`(job 종료 시 만료)을 SSH stdin으로 넘기고, `deploy.sh`가 임시 설정에만 로그인해 pull한 뒤 지운다.

## 설정 반영

이 폴더의 compose·nginx·deploy.sh가 바뀌면 서버에 직접 올린다. 로컬의 BE 레포 루트에서 실행한다.

```bash
ssh -i team08-hapbang-key.pem ubuntu@<EC2 IP> 'mkdir -p ~/hapbang-config'
scp -i team08-hapbang-key.pem -r docker/compose.prod.yaml docker/compose.dev.yaml docker/compose.edge.yaml \
    docker/nginx docker/deploy.sh ubuntu@<EC2 IP>:~/hapbang-config/
```

서버에서:

```bash
sudo cp -r ~/hapbang-config/compose.*.yaml ~/hapbang-config/nginx /opt/hapbang/
sudo chown -R deploy:deploy /opt/hapbang
sudo install -o root -g root -m 755 ~/hapbang-config/deploy.sh /usr/local/bin/hapbang-deploy
```

- `compose.prod.yaml`·`compose.dev.yaml`: 다음 배포 때 해당 서비스에 반영된다. 바로 반영하려면 위 "처음 한 번" 명령을 다시 실행한다.
- `nginx/`·`compose.edge.yaml`: edge는 시작할 때만 설정을 읽으므로 다시 만든다.
  `sudo -u deploy docker compose -f /opt/hapbang/compose.edge.yaml --env-file /opt/hapbang/env/edge.env up -d --force-recreate`

## 배포 키 등록

로컬에서 배포 전용 키를 만든다. 이 키는 다른 용도로 쓰지 않는다.

```bash
ssh-keygen -t ed25519 -N '' -C github-actions-deploy -f deploy_key
```

서버의 `/home/deploy/.ssh/authorized_keys`에 아래 한 줄로 등록한다. 이 키로 접속하면 셸 없이 `deploy.sh`만 실행된다.

```
command="/usr/local/bin/hapbang-deploy",restrict ssh-ed25519 AAAA... github-actions-deploy
```

```bash
sudo install -d -o deploy -g deploy -m 700 /home/deploy/.ssh
sudo -u deploy tee -a /home/deploy/.ssh/authorized_keys   # 위 한 줄 붙여넣기 후 Ctrl-D
sudo chmod 600 /home/deploy/.ssh/authorized_keys
```

FE·BE 레포 각각의 GitHub Secrets에 등록한다.

| Secret | 값 |
| --- | --- |
| `DEPLOY_HOST` | EC2 탄력적 IP |
| `DEPLOY_SSH_KEY` | `deploy_key`(개인 키) 내용 |
| `DEPLOY_KNOWN_HOSTS` | `ssh-keyscan -t ed25519 <EC2 IP>` 결과 |

등록 후 로컬의 `deploy_key` 파일은 삭제한다.

## 수동 배포

새 이미지를 받으려면 먼저 위처럼 `docker login ghcr.io`를 하고, 끝나면 로그아웃한다.

```bash
sudo -u deploy hapbang-deploy prod app   # BE
sudo -u deploy hapbang-deploy dev web    # FE
```
