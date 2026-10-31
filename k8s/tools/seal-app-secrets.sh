#!/usr/bin/env bash
# 앱 prod Secret(<앱>-secrets)을 SealedSecret으로 만들어 apps/<앱>/k8s/overlays/prod/<앱>-secrets.yaml에 쓴다(#374).
# 값은 화면에 출력하지 않는다. 평문은 권한 600 임시 파일에만 두고 끝나면 지운다.
# - DB_PASSWORD: EC2 cluster의 postgres-credentials(hapbang-platform)에서 앱 role 값을 읽는다.
# - HAPBANG_INTERNAL_API_KEY: ~/.hapbang/internal-api-key. 앱 6개가 같은 값을 쓴다.
#   파일이 없으면 멈춘다. 처음 설치할 때만 HAPBANG_NEW_INTERNAL_API_KEY=1로 앱 6개를 함께 봉인하며 새로 만든다.
#   앱 하나만 새 키로 봉인하면 앱끼리 키가 달라져 내부 HTTP 인증이 깨진다.
# - 그 밖의 값: ~/.hapbang/prod-secrets.env의 KEY=VALUE 줄. 서버 prod.env 줄을 그대로 옮기면 된다.
#   이 파일은 실행하지 않고 읽기만 한다. Compose처럼 값 전체를 감싼 따옴표 한 쌍은 뗀다.
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
  local v
  v=$(grep -m1 "^$1=" "$env_file" | cut -d= -f2- || true)
  case "$v" in
    \"*\") v=${v#\"}; v=${v%\"} ;;
    \'*\') v=${v#\'}; v=${v%\'} ;;
  esac
  printf '%s' "$v"
}

apps=("$@")
[ ${#apps[@]} -gt 0 ] || apps=(member billing appearance lecture chat customer-support)

kubectl --context "$ctx" -n hapbang-platform get secret postgres-credentials >/dev/null
if [ ! -s "$key_file" ]; then
  if [ "${HAPBANG_NEW_INTERNAL_API_KEY:-}" != 1 ] || [ $# -gt 0 ]; then
    echo "내부 API 키 파일이 없다: $key_file" >&2
    echo "팀 비밀 보관소에서 가져온다. 처음 설치라면 앱 이름 없이 HAPBANG_NEW_INTERNAL_API_KEY=1로 실행해 앱 6개를 함께 봉인한다." >&2
    exit 1
  fi
  (umask 077; mkdir -p "$dir"; openssl rand -hex 32 | tr -d '\n' > "$key_file")
  echo "내부 API 키를 새로 만들었다: $key_file"
fi

tmp=$(mktemp)
sealed=$(mktemp)
trap 'rm -f "$tmp" "$sealed"' EXIT
chmod 600 "$tmp"

for app in "${apps[@]}"; do
  IFS='|' read -r role required optional <<< "$(spec "$app")"
  missing=()
  # printf 인자 안의 명령 치환은 실패해도 set -e가 잡지 못한다. 변수에 먼저 담고 비었으면 멈춘다.
  db_password=$(kubectl --context "$ctx" -n hapbang-platform get secret postgres-credentials -o jsonpath="{.data.$role}" | base64 -d)
  if [ -z "$db_password" ]; then
    echo "${app}: postgres-credentials에 ${role} 값이 없다" >&2
    exit 1
  fi
  # billing 결제 키 암호화는 두 키가 모두 있거나 모두 없어야 한다. 하나만 있으면 앱이 기동하지 못한다.
  if [ "$app" = billing ]; then
    enc=$(value BILLING_PAYMENT_KEY_ENCRYPTION_KEY)
    hmac=$(value BILLING_PAYMENT_KEY_HMAC_KEY)
    if { [ -n "$enc" ] && [ -z "$hmac" ]; } || { [ -z "$enc" ] && [ -n "$hmac" ]; }; then
      echo "billing: BILLING_PAYMENT_KEY_ENCRYPTION_KEY와 BILLING_PAYMENT_KEY_HMAC_KEY는 둘 다 있거나 둘 다 없어야 한다" >&2
      exit 1
    fi
  fi
  {
    printf 'DB_PASSWORD=%s\n' "$db_password"
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
  # kubeseal이 실패해도 레포의 기존 파일이 비지 않게 임시 파일에 쓴 뒤 옮긴다.
  kubectl create secret generic "$app-app-secrets" -n hapbang-prod --from-env-file="$tmp" --dry-run=client -o yaml \
    | kubeseal --context "$ctx" --format yaml > "$sealed"
  cp "$sealed" "$out"
  echo "$app: $(yq -r '.spec.encryptedData | keys | join(" ")' "$out") -> ${out#"$root"/}"
done
