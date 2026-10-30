#!/usr/bin/env bash
# private GHCR 이미지를 pull Secret으로 실제로 받아 실행되는지 확인한다(#460·#461·#462).
# 지정한 node pool에 검증 pod 하나를 띄워 이미지를 받고 버전 명령만 실행한 뒤 지운다. 업무 코드·DB·배치는 실행하지 않는다.
# - imagePullPolicy Always라 node에 이미지가 있어도 registry에 인증을 거친다. 층까지 새로 받았는지 보려면
#   먼저 그 node에서 `sudo k3s crictl rmi <이미지>`로 지운다(node 재시작은 캐시를 지우지 않는다).
# - Secret을 빼고(SECRET=) 실행하면 익명 pull이 실패하는지(패키지가 private인지) 확인할 수 있다.
# - 값·node 이름은 출력하지 않는다.
# 사용법: k8s/tools/ghcr-pull-check.sh <이미지> <pool>
#   예: k8s/tools/ghcr-pull-check.sh ghcr.io/prgrms-be-adv-devcourse/beadv8_8_lms_be/member-app:sha-<커밋> core-data
#   pool: core-data(large) | app-observe(medium)
#   환경 변수: KUBECTL(기본 kubectl --context hapbang-k3s), NS(기본 hapbang-prod), SECRET(기본 ghcr-pull, 비우면 없이)
set -euo pipefail

image=${1:?이미지가 필요합니다}
pool=${2:?pool(core-data|app-observe)이 필요합니다}
KUBECTL=${KUBECTL:-kubectl --context hapbang-k3s}
NS=${NS:-hapbang-prod}
SECRET=${SECRET-ghcr-pull}
[[ "$pool" =~ ^(core-data|app-observe)$ ]] || { echo "pool은 core-data 또는 app-observe다: $pool" >&2; exit 2; }

# shellcheck disable=SC2086
kc() { $KUBECTL "$@"; }

# FE는 Caddy, BE는 JRE 이미지다. 이미지를 받아 프로세스가 뜨는지만 본다.
case "$image" in
  */beadv8_8_lms_fe:*) command='["caddy", "version"]' ;;
  *) command='["java", "-version"]' ;;
esac
pull_secrets=
[ -z "$SECRET" ] || pull_secrets="imagePullSecrets: [{name: $SECRET}]"

pod=ghcr-pull-check-$(date +%s)
trap 'kc -n "$NS" delete pod "$pod" --ignore-not-found --wait=false > /dev/null' EXIT

kc apply -f - > /dev/null <<EOF
apiVersion: v1
kind: Pod
metadata:
  name: $pod
  namespace: $NS
  labels:
    app.kubernetes.io/name: ghcr-pull-check
spec:
  restartPolicy: Never
  automountServiceAccountToken: false
  nodeSelector:
    hapbang.io/pool: $pool
  $pull_secrets
  containers:
    - name: check
      image: $image
      imagePullPolicy: Always
      command: $command
      resources:
        requests: {cpu: 10m, memory: 64Mi}
        limits: {memory: 256Mi}
EOF

# pull 실패(ErrImagePull·ImagePullBackOff)나 실행 종료(Succeeded·Failed)까지 최대 5분 기다린다.
result=
for _ in $(seq 60); do
  phase=$(kc -n "$NS" get pod "$pod" -o jsonpath='{.status.phase}')
  waiting=$(kc -n "$NS" get pod "$pod" -o jsonpath='{.status.containerStatuses[0].state.waiting.reason}')
  case "$phase/$waiting" in
    Succeeded/*) result=ok; break ;;
    Failed/*) result=run-failed; break ;;
    */ErrImagePull | */ImagePullBackOff) result=pull-failed; break ;;
  esac
  sleep 5
done

events=$(kc -n "$NS" get events --field-selector "involvedObject.name=$pod" \
  -o jsonpath='{range .items[*]}{.reason}{": "}{.message}{"\n"}{end}' | grep -E '^(Pulled|Failed):' || true)
echo "$events"
case "$result" in
  ok) echo "성공: $pool node에서 받아 실행했다 ($image, Secret ${SECRET:-없음})" ;;
  pull-failed) echo "pull 실패: $pool node ($image, Secret ${SECRET:-없음})"; exit 1 ;;
  run-failed) echo "받았지만 실행 실패: $pool node ($image)"; kc -n "$NS" logs "$pod" | tail -n 5; exit 1 ;;
  *) echo "5분 안에 끝나지 않았다: $pool node ($image)"; exit 1 ;;
esac
