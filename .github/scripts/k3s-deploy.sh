#!/usr/bin/env bash
# large(k3s server)에서 root로 실행되는 배포 스크립트(#374). deploy.yml이 ssm-run.sh로 SSM Run Command에 실어 보낸다.
# 공개 레포라 Actions 로그도 공개다. node 이름·IP가 나오는 출력(-o wide, describe)은 쓰지 않는다.
#
# 사용법:
#   k3s-deploy.sh images                   hapbang-prod의 Deployment마다 "<앱> <이미지>"를 출력
#   k3s-deploy.sh apply <앱> <sha> <tag>   <sha> 커밋의 apps/<앱>/k8s/overlays/prod에 이미지 :sha-<tag>를 넣어 적용하고 rollout을 기다린다
#                                          rollout이 실패하면 원인(이미지 pull·앱 기동)을 알리고 rollout undo로 되돌린 뒤 실패로 끝난다
# GHCR은 조직 정책으로 private이다. 이미지는 hapbang-prod의 공유 Secret ghcr-pull(k8s/registry)로 받는다(#461).
#   이 스크립트는 그 Secret을 확인만 하고 만들거나 지우지 않는다. 토큰은 SSM 명령으로 넘기지 않는다.
# 로컬 k3d 시험: KUBECTL="kubectl --context k3d-hapbang" ROLLOUT_TIMEOUT=60s .github/scripts/k3s-deploy.sh apply ...
set -euo pipefail

# SSM은 HOME 없이 실행한다. kubectl이 캐시 디렉터리를 만들 수 있게 둔다.
export HOME=${HOME:-/root}
REPO=prgrms-be-adv-devcourse/beadv8_8_LMS_BE
IMAGE_PREFIX=ghcr.io/prgrms-be-adv-devcourse/beadv8_8_lms_be
NS=hapbang-prod
KUBECTL=${KUBECTL:-k3s kubectl}
ROLLOUT_TIMEOUT=${ROLLOUT_TIMEOUT:-10m}

# KUBECTL은 "k3s kubectl"처럼 단어 여러 개라 따옴표 없이 펼친다.
# shellcheck disable=SC2086
kc() { $KUBECTL "$@"; }

# Secret이 있고 auths["ghcr.io"]에 인증 값이 든 dockerconfigjson인지만 본다. 값은 출력하지 않는다.
# 토큰이 유효한지는 실제 pull로만 알 수 있다(rollout, k8s/tools/ghcr-pull-check.sh).
# JSON은 python3로 읽는다(Ubuntu 24.04 기본 포함. jq는 서버에 없다).
check_pull_secret() {
  local type
  type=$(kc -n "$NS" get secret ghcr-pull -o jsonpath='{.type}' --ignore-not-found)
  if [ -z "$type" ]; then
    echo "::error::pull Secret ghcr-pull이 ${NS}에 없습니다. k8s/registry를 먼저 적용하세요(docs/deploy/k8s.md GHCR pull 인증)."
    exit 1
  fi
  if [ "$type" != kubernetes.io/dockerconfigjson ] \
    || ! kc -n "$NS" get secret ghcr-pull -o jsonpath='{.data.\.dockerconfigjson}' | base64 -d \
      | python3 -c 'import json, sys; a = json.load(sys.stdin).get("auths", {}).get("ghcr.io", {}); sys.exit(0 if a.get("auth") or (a.get("username") and a.get("password")) else 1)' 2> /dev/null; then
    echo "::error::pull Secret ghcr-pull의 형식이 다릅니다(type $type). ghcr.io 인증이 든 kubernetes.io/dockerconfigjson이어야 합니다."
    exit 1
  fi
}

# rollout이 실패한 원인을 나눈다. 이미지 pull 실패는 앱 코드와 무관하므로 먼저 가린다.
# 이벤트는 지금 pull에 실패한 pod 것만 본다. 이전 배포의 pod 이벤트(보존 1시간)가 섞이면 원인을 잘못 고른다.
explain_failure() {
  local app=$1 failing messages
  failing=$(kc -n "$NS" get pods -l "app.kubernetes.io/name=$app" \
    -o jsonpath='{range .items[*]}{.metadata.name}{" "}{range .status.containerStatuses[*]}{.state.waiting.reason}{end}{"\n"}{end}' \
    | awk '$2 == "ErrImagePull" || $2 == "ImagePullBackOff" { print $1 }')
  messages=$(kc -n "$NS" get events --field-selector type=Warning,reason=Failed \
    -o jsonpath='{range .items[*]}{.involvedObject.name}{" "}{.message}{"\n"}{end}' \
    | awk -v pods="$failing" 'BEGIN { n = split(pods, p, "\n"); for (i = 1; i <= n; i++) want[p[i]] = 1 } $1 in want')
  if [ -z "$failing" ]; then
    echo "::error::$app: 이미지는 받았고 앱이 Ready가 되지 않았습니다(기동 실패·probe 실패). 위 pod 상태와 이벤트, 앱 로그를 확인하세요."
  elif grep -qiE ': not found|manifest unknown' <<< "$messages"; then
    echo "::error::$app: 이미지가 GHCR에 없습니다. 그 커밋의 Publish images run을 확인하세요."
  elif grep -qiE '401|403|unauthorized|forbidden|denied' <<< "$messages"; then
    # GHCR은 아직 발행되지 않은 패키지도 403으로 답한다.
    echo "::error::$app: GHCR 인증에 실패했습니다(401·403). ghcr-pull 토큰의 만료·권한(read:packages, package Read)과 패키지 발행 여부를 확인하세요(docs/deploy/k8s.md GHCR pull 인증)."
  else
    echo "::error::$app: 이미지를 받지 못했습니다(원인 미분류). 위 이벤트를 확인하세요."
  fi
}

images() {
  kc -n "$NS" get deploy -o jsonpath='{range .items[*]}{.metadata.name}{" "}{.spec.template.spec.containers[0].image}{"\n"}{end}'
}

apply_app() {
  local app=${1:-} sha=${2:-} tag=${3:-}
  if ! [[ "$app" =~ ^[a-z-]+-app$ && "$sha" =~ ^[0-9a-f]{40}$ && "$tag" =~ ^[0-9a-f]{40}$ ]]; then
    echo "잘못된 입력: app=$app sha=$sha tag=$tag" >&2
    exit 2
  fi

  # trap이 스크립트 종료 때 지울 수 있게 전역 변수로 둔다.
  dir=$(mktemp -d)
  trap 'rm -rf "$dir"' EXIT

  # 공개 레포에서 배포할 커밋을 받는다(수 MB). 서버에 레포를 두지 않는다.
  curl -sSfL "https://codeload.github.com/$REPO/tar.gz/$sha" | tar xz -C "$dir" --strip-components=1

  # prod overlay를 감싸 이미지만 바꾼다. 레포의 overlay 파일은 고치지 않는다.
  mkdir "$dir/deploy"
  cat > "$dir/deploy/kustomization.yaml" <<EOF
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization
resources:
  - ../apps/$app/k8s/overlays/prod
images:
  - name: $app
    newName: $IMAGE_PREFIX/$app
    newTag: sha-$tag
EOF

  check_pull_secret
  kc apply -k "$dir/deploy"

  # 앱 기동(Flyway migration 포함)을 기다린다. replica가 0인 앱은 바로 끝난다.
  if kc -n "$NS" rollout status "deploy/$app" --timeout="$ROLLOUT_TIMEOUT"; then
    return
  fi

  echo "rollout 실패: $app. 이전 revision으로 되돌린다."
  kc -n "$NS" get pods -l "app.kubernetes.io/name=$app" || true
  kc -n "$NS" get events --field-selector type=Warning --sort-by=.lastTimestamp \
    -o 'custom-columns=AGE:.lastTimestamp,REASON:.reason,OBJECT:.involvedObject.name,MESSAGE:.message' \
    | grep "$app" | tail -n 10 || true
  explain_failure "$app" || true
  # ConfigMap·Secret·HTTPRoute 같은 다른 리소스는 되돌리지 않는다. 앱 Deployment만 이전 revision으로 돌린다.
  if kc -n "$NS" rollout undo "deploy/$app"; then
    kc -n "$NS" rollout status "deploy/$app" --timeout=5m || true
  else
    echo "이전 revision이 없어 되돌리지 못했다(첫 배포)."
  fi
  exit 1
}

case "${1:-}" in
  images) images ;;
  apply) shift; apply_app "$@" ;;
  *) echo "사용법: $0 images | apply <앱> <sha> <tag>" >&2; exit 2 ;;
esac
