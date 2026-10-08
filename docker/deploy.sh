#!/usr/bin/env bash
# EC2 배포 스크립트. GitHub Actions의 배포 전용 SSH 키로는 이 스크립트만 실행된다(forced command).
# 사용법: deploy.sh <prod|dev> <web|app> [커밋 SHA]
#   web  FE 레포 배포. 해당 환경의 web만 교체한다.
#   app  BE 레포 배포. 해당 환경의 app만 교체한다(postgres가 내려가 있으면 함께 올린다).
#   SHA  배포할 이미지(:sha-<SHA>). 생략하면 :latest(prod) / :dev(dev).
#        workflow가 빌드한 커밋을 넘기므로, 이전 run의 deploy job을 다시 실행하면 그 버전으로 롤백된다.
# stdin: (선택) "<GHCR 사용자> <GHCR 토큰>" 한 줄. 배포 workflow가 넘긴다.
# 새 컨테이너가 healthy가 되지 않으면 이전 이미지로 되돌리고 실패로 끝난다.
# 서버 설치 방법은 docker/README.md 참고.
set -euo pipefail

ROOT=/opt/hapbang
read -r ENV_NAME SERVICE SHA EXTRA <<< "${SSH_ORIGINAL_COMMAND:-$*}" || true

usage() { echo "사용법: deploy.sh <prod|dev> <web|app> [커밋 SHA]" >&2; exit 2; }
[ -z "${EXTRA:-}" ] || usage
case "${ENV_NAME:-}" in prod|dev) ;; *) usage ;; esac
case "${SERVICE:-}" in
  web) TARGETS=(web);          TAG_VAR=FE_TAG ;;
  app) TARGETS=(postgres app); TAG_VAR=BE_TAG ;;
  *)   usage ;;
esac
if [ -n "${SHA:-}" ]; then
  [[ "$SHA" =~ ^[0-9a-f]{40}$ ]] || usage
  # 셸 환경 변수는 --env-file보다 우선한다.
  export "$TAG_VAR=sha-$SHA"
fi

# GHCR 패키지는 private이다. 토큰은 임시 DOCKER_CONFIG에만 로그인하고 끝나면 지운다.
# 수동 실행(터미널)은 deploy 계정의 기존 로그인을 쓴다.
GHCR_USER="" GHCR_TOKEN=""
if [ ! -t 0 ]; then
  read -r -t 10 GHCR_USER GHCR_TOKEN || true
fi
if [ -n "$GHCR_TOKEN" ]; then
  # GITHUB_TOKEN은 JWT 형식(ghs_..., 약 520자, '.'·'-' 포함)이다. 길이는 제한하지 않는다.
  [[ "$GHCR_USER" =~ ^[A-Za-z0-9_.-]+(\[bot\])?$ && "$GHCR_TOKEN" =~ ^[A-Za-z0-9._-]+$ ]] || { echo "잘못된 GHCR 인증 정보" >&2; exit 2; }
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

# workflow가 레포와 비교해 서버 설정이 뒤처졌는지(수동 반영 누락) 알려 준다.
for f in compose.prod.yaml compose.dev.yaml compose.edge.yaml nginx/templates/default.conf.template nginx/snippets/routes.conf nginx/snippets/ssl.conf; do
  echo "[config] $(sha256sum "$ROOT/$f" | cut -d' ' -f1) $f"
done
echo "[config] $(sha256sum "$0" | cut -d' ' -f1) deploy.sh"

echo "[deploy] $ENV_NAME $SERVICE ${!TAG_VAR:-기본 태그} 시작"

# 롤백 대비: 교체 전 이미지를 기억한다(최초 배포면 없음).
prev_container=$(stack ps -q "$SERVICE" 2>/dev/null || true)
prev_image="" prev_ref=""
if [ -n "$prev_container" ]; then
  prev_image=$(docker inspect -f '{{.Image}}' "$prev_container")
  prev_ref=$(docker inspect -f '{{.Config.Image}}' "$prev_container")
fi

stack pull --quiet "$SERVICE"
if ! stack up -d --no-deps --wait --wait-timeout 180 "${TARGETS[@]}"; then
  echo "[deploy] $SERVICE 가 healthy가 되지 않음. 최근 로그:" >&2
  stack logs --tail 50 "$SERVICE" >&2 || true
  if [ -n "$prev_image" ]; then
    # 이전 이미지에 로컬 태그를 붙여 그 태그로 다시 띄운다(pull하지 않음).
    rollback_ref="${prev_ref%:*}:rollback-$ENV_NAME"
    docker tag "$prev_image" "$rollback_ref"
    export "$TAG_VAR=rollback-$ENV_NAME"
    echo "[deploy] 이전 이미지로 롤백: $prev_ref ($prev_image)" >&2
    stack up -d --no-deps --wait --wait-timeout 180 "$SERVICE" \
      && echo "[deploy] 롤백 완료" >&2 \
      || echo "[deploy] 롤백도 실패. 서버에서 직접 확인 필요" >&2
  fi
  exit 1
fi

# 이전 이미지가 디스크(50GB)를 채우지 않도록 정리한다.
docker image prune -f > /dev/null

stack ps --format 'table {{.Service}}\t{{.Image}}\t{{.Status}}'
echo "[deploy] $ENV_NAME $SERVICE 완료"
