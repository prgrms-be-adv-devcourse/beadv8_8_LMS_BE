#!/usr/bin/env bash
# 앱 prod Secret(<앱>-secrets)을 SealedSecret으로 만들어 apps/<앱>/k8s/overlays/prod/<앱>-secrets.yaml에 쓴다(#374).
# 값은 화면에 출력하지 않는다. 평문은 권한 600 임시 파일에만 두고 끝나면 지운다.
# - DB_PASSWORD: EC2 cluster의 postgres-credentials(hapbang-platform)에서 앱 role 값을 읽는다.
# - HAPBANG_INTERNAL_API_KEY: ~/.hapbang/internal-api-key. 없으면 만든다. 앱 6개가 같은 값을 쓴다.
# - 그 밖의 값: ~/.hapbang/prod-secrets.env의 KEY=VALUE 줄(따옴표 없이). 이 파일은 실행하지 않고 읽기만 한다.
# 사용법: k8s/tools/seal-app-secrets.sh [앱 이름...]   예: member billing (기본: 앱 6개)
set -euo pipefail

ctx=hapbang-k3s
dir=${HAPBANG_SECRET_DIR:-$HOME/.hapbang}
env_file=$dir/prod-secrets.env
key_file=$dir/internal-api-key
root=$(git rev-parse --show-toplevel)

# 앱마다 DB role, 필수 키, 선택 키(없으면 넣지 않는다)를 정한다. 키 목록은 docs/deploy/k8s.md "Secret"과 같다.
spec() {
  case "$1" in
    member) echo "member|S3_BUCKET GOOGLE_CLIENT_ID GOOGLE_CLIENT_SECRET CHZZK_CLIENT_ID CHZZK_CLIENT_SECRET CHZZK_REDIRECT_URI|" ;;
    billing) echo "billing||TOSS_PAYMENTS_SECRET_KEY BILLING_PAYMENT_KEY_ENCRYPTION_KEY BILLING_PAYMENT_KEY_HMAC_KEY" ;;
    lecture) echo "lecture|S3_BUCKET|" ;;
    appearance) echo "appearance||" ;;
    chat) echo "chat||" ;;
    customer-support) echo "customersupport||" ;;
    *) echo "알 수 없는 앱: $1" >&2; return 1 ;;
  esac
}

value() {
  [ -f "$env_file" ] || return 0
  grep -m1 "^$1=" "$env_file" | cut -d= -f2- || true
}

apps=("$@")
[ ${#apps[@]} -gt 0 ] || apps=(member billing appearance lecture chat customer-support)

kubectl --context "$ctx" -n hapbang-platform get secret postgres-credentials >/dev/null
if [ ! -s "$key_file" ]; then
  (umask 077; mkdir -p "$dir"; openssl rand -hex 32 | tr -d '\n' > "$key_file")
  echo "내부 API 키를 새로 만들었다: $key_file"
fi

tmp=$(mktemp)
trap 'rm -f "$tmp"' EXIT
chmod 600 "$tmp"

for app in "${apps[@]}"; do
  IFS='|' read -r role required optional <<< "$(spec "$app")"
  missing=()
  {
    printf 'DB_PASSWORD=%s\n' "$(kubectl --context "$ctx" -n hapbang-platform get secret postgres-credentials -o jsonpath="{.data.$role}" | base64 -d)"
    printf 'HAPBANG_INTERNAL_API_KEY=%s\n' "$(cat "$key_file")"
    for key in $required; do
      v=$(value "$key")
      if [ -z "$v" ]; then missing+=("$key"); else printf '%s=%s\n' "$key" "$v"; fi
    done
    for key in $optional; do
      v=$(value "$key")
      [ -z "$v" ] || printf '%s=%s\n' "$key" "$v"
    done
  } > "$tmp"
  if [ ${#missing[@]} -gt 0 ]; then
    echo "${app}: ${env_file}에 없는 필수 키: ${missing[*]}" >&2
    exit 1
  fi
  out=$root/apps/$app-app/k8s/overlays/prod/$app-app-secrets.yaml
  kubectl create secret generic "$app-app-secrets" -n hapbang-prod --from-env-file="$tmp" --dry-run=client -o yaml \
    | kubeseal --context "$ctx" --format yaml > "$out"
  echo "$app: $(yq -r '.spec.encryptedData | keys | join(" ")' "$out") -> ${out#"$root"/}"
done
