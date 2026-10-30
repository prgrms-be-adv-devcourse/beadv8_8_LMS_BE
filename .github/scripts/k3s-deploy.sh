#!/usr/bin/env bash
# large(k3s server)에서 root로 실행되는 배포 스크립트(#374). deploy.yml이 ssm-run.sh로 SSM Run Command에 실어 보낸다.
# 공개 레포라 Actions 로그도 공개다. node 이름·IP가 나오는 출력(-o wide, describe)은 쓰지 않는다.
#
# 사용법:
#   k3s-deploy.sh images                   hapbang-prod의 Deployment마다 "<앱> <이미지>"를 출력
#   k3s-deploy.sh apply <앱> <sha> <tag>   <sha> 커밋의 apps/<앱>/k8s/overlays/prod에 이미지 :sha-<tag>를 넣어 적용하고 rollout을 기다린다
#                                          rollout이 실패하면 rollout undo로 되돌리고 실패로 끝난다
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
