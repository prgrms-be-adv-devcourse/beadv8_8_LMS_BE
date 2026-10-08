#!/usr/bin/env bash
# EC2 배포 스크립트. GitHub Actions의 배포 전용 SSH 키로는 이 스크립트만 실행된다(forced command).
# 사용법: deploy.sh <prod|dev> <web|app>
#   web  FE 레포 배포. 해당 환경의 web만 교체한다.
#   app  BE 레포 배포. 해당 환경의 app만 교체한다(postgres가 내려가 있으면 함께 올린다).
# stdin: (선택) "<GHCR 사용자> <GHCR 토큰>" 한 줄. 배포 workflow가 넘긴다.
# 서버 설치 방법은 docker/README.md 참고.
set -euo pipefail

ROOT=/opt/hapbang
read -r ENV_NAME SERVICE EXTRA <<< "${SSH_ORIGINAL_COMMAND:-$*}" || true

usage() { echo "사용법: deploy.sh <prod|dev> <web|app>" >&2; exit 2; }
[ -z "${EXTRA:-}" ] || usage
case "${ENV_NAME:-}" in prod|dev) ;; *) usage ;; esac
case "${SERVICE:-}" in
  web) TARGETS=(web) ;;
  app) TARGETS=(postgres app) ;;
  *)   usage ;;
esac

# GHCR 패키지는 private이다. 배포 workflow는 "<사용자> <토큰>"(GHCR 읽기 전용, job 종료 시 만료)을 stdin으로 넘긴다.
# 토큰은 임시 DOCKER_CONFIG에만 로그인하고 끝나면 지운다. 수동 실행(터미널)은 deploy 계정의 기존 로그인을 쓴다.
GHCR_USER="" GHCR_TOKEN=""
if [ ! -t 0 ]; then
  read -r -t 10 GHCR_USER GHCR_TOKEN || true
fi
if [ -n "$GHCR_TOKEN" ]; then
  [[ "$GHCR_USER" =~ ^[A-Za-z0-9_.-]+$ && "$GHCR_TOKEN" =~ ^[A-Za-z0-9_]+$ ]] || { echo "잘못된 GHCR 인증 정보" >&2; exit 2; }
  DOCKER_CONFIG=$(mktemp -d)
  export DOCKER_CONFIG
  trap 'rm -rf "$DOCKER_CONFIG"' EXIT
  printf '%s' "$GHCR_TOKEN" | docker login ghcr.io -u "$GHCR_USER" --password-stdin > /dev/null
fi
unset GHCR_TOKEN

# FE·BE 배포가 동시에 들어와도 한 번에 하나씩 처리한다.
exec 9>"$ROOT/deploy.lock"
flock 9

stack() {
  docker compose -f "$ROOT/compose.$ENV_NAME.yaml" --env-file "$ROOT/env/$ENV_NAME.env" "$@"
}

echo "[deploy] $ENV_NAME $SERVICE 시작"
stack pull --quiet "$SERVICE"
stack up -d --no-deps --wait --wait-timeout 180 "${TARGETS[@]}"

# 이전 이미지가 디스크(50GB)를 채우지 않도록 정리한다.
docker image prune -f > /dev/null

stack ps --format 'table {{.Service}}\t{{.Image}}\t{{.Status}}'
echo "[deploy] $ENV_NAME $SERVICE 완료"
