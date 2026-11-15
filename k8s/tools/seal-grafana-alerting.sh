#!/usr/bin/env bash
# Grafana Slack contact point Secret(grafana-alerting)을 SealedSecret으로 만들어 k8s/platform/overlays/prod/grafana-alerting.yaml에 쓴다(#781).
# 값은 ~/.hapbang/prod-secrets.env의 GRAFANA_SLACK_WEBHOOK_URL 줄(Slack App의 Incoming Webhook URL)이다. 화면·명령 인자에 남기지 않는다.
# 사용법: k8s/tools/seal-grafana-alerting.sh
set -euo pipefail

ctx=hapbang-k3s
env_file=${HAPBANG_SECRET_DIR:-$HOME/.hapbang}/prod-secrets.env
root=$(git rev-parse --show-toplevel)
out=$root/k8s/platform/overlays/prod/grafana-alerting.yaml

url=$(grep -m1 '^GRAFANA_SLACK_WEBHOOK_URL=' "$env_file" | cut -d= -f2- || true)
case "$url" in
  \"*\") url=${url#\"}; url=${url%\"} ;;
  \'*\') url=${url#\'}; url=${url%\'} ;;
esac
if ! [[ "$url" =~ ^https://hooks\.slack\.com/services/[A-Za-z0-9/]+$ ]]; then
  echo "$env_file에 GRAFANA_SLACK_WEBHOOK_URL=https://hooks.slack.com/services/... 줄이 없거나 형식이 다르다." >&2
  exit 1
fi

tmp=$(mktemp)
sealed=$(mktemp)
trap 'rm -f "$tmp" "$sealed"' EXIT
chmod 600 "$tmp"
printf 'SLACK_WEBHOOK_URL=%s\n' "$url" > "$tmp"
# kubeseal이 실패해도 레포의 기존 파일이 비지 않게 임시 파일에 쓴 뒤 옮긴다.
kubectl create secret generic grafana-alerting -n hapbang-platform --from-env-file="$tmp" --dry-run=client -o yaml \
  | kubeseal --context "$ctx" --format yaml > "$sealed"
cp "$sealed" "$out"
echo "봉인했다: ${out#"$root"/}"
