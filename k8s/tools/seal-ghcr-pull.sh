#!/usr/bin/env bash
# GHCR pull 인증 Secret(ghcr-pull)을 SealedSecret으로 만들어 k8s/registry/ghcr-pull.yaml에 쓴다(#460).
# GHCR 패키지는 조직 정책으로 private이다. BE 앱 6개와 FE web이 hapbang-prod의 이 Secret 하나로 이미지를 받는다.
# 토큰은 read:packages만 고른 classic PAT(만료 90일)다. 화면·명령 인자·레포에 남기지 않는다.
# - 토큰 권한이 read:packages 하나가 아니면 멈춘다(cluster 관리자는 Secret을 읽을 수 있다).
# - 봉인 전에 패키지마다 GHCR에서 읽을 수 있는지 확인한다. 권한이 없으면 멈추고, 아직 발행되지 않은 패키지는 알리고 넘어간다.
# - 만료일을 출력한다. 교체는 만료 14일 전에 한다(docs/deploy/k8s.md "GHCR pull 인증").
# 사용법: k8s/tools/seal-ghcr-pull.sh   GitHub 아이디와 토큰을 입력받는다(토큰은 화면에 보이지 않는다).
set -euo pipefail

ctx=hapbang-k3s
org=prgrms-be-adv-devcourse
root=$(git rev-parse --show-toplevel)
out=$root/k8s/registry/ghcr-pull.yaml

packages=(beadv8_8_lms_fe)
for app in $(cd "$root" && .github/scripts/apps.sh list); do
  packages+=("beadv8_8_lms_be/$app")
done

read -rp 'GitHub 아이디: ' user
read -rsp 'PAT(read:packages): ' token
echo
if ! [[ "$user" =~ ^[A-Za-z0-9-]+$ && "$token" =~ ^ghp_[A-Za-z0-9]+$ ]]; then
  echo "아이디 또는 토큰 형식이 맞지 않다(classic PAT는 ghp_로 시작한다)." >&2
  exit 1
fi

tmp=$(mktemp)
sealed=$(mktemp)
trap 'rm -f "$tmp" "$sealed"' EXIT
chmod 600 "$tmp"

# 토큰은 curl 인자가 아니라 stdin 설정(-K -)으로 넘긴다. 인자는 프로세스 목록에 보인다.
headers=$(printf 'user = "%s:%s"\n' "$user" "$token" | curl -sS -o /dev/null -D - -K - https://api.github.com/user)
scopes=$(grep -i '^x-oauth-scopes:' <<< "$headers" | cut -d: -f2- | tr -d ' \r')
expires=$(grep -i '^github-authentication-token-expiration:' <<< "$headers" | cut -d: -f2- | sed 's/^ *//' | tr -d '\r')
if [ "$scopes" != read:packages ]; then
  echo "토큰 권한이 read:packages 하나가 아니다(지금: ${scopes:-인증 실패}). read:packages만 골라 다시 만든다." >&2
  exit 1
fi
if [ -z "$expires" ]; then
  echo "만료 없는 토큰이다. 만료 90일로 다시 만든다." >&2
  exit 1
fi

failed=0
for pkg in "${packages[@]}"; do
  body=$(printf 'user = "%s:%s"\n' "$user" "$token" \
    | curl -sS -K - "https://ghcr.io/token?scope=repository:$org/$pkg:pull&service=ghcr.io")
  bearer=$(jq -r '.token // empty' <<< "$body")
  if [ -n "$bearer" ]; then
    code=$(printf 'header = "Authorization: Bearer %s"\n' "$bearer" \
      | curl -sS -o /dev/null -w '%{http_code}' -K - "https://ghcr.io/v2/$org/$pkg/tags/list")
  else
    code=$(jq -r '.errors[0].code // "UNKNOWN"' <<< "$body")
  fi
  case "$code" in
    200) echo "읽기 가능: $pkg" ;;
    404 | NAME_UNKNOWN) echo "아직 발행되지 않음: $pkg (첫 발행 뒤 k8s/tools/ghcr-pull-check.sh로 확인)" ;;
    *) echo "읽을 수 없음($code): $pkg. 계정의 package Read 권한과 SSO 승인을 확인한다." >&2; failed=1 ;;
  esac
done
[ "$failed" = 0 ]

auth=$(printf '%s:%s' "$user" "$token" | base64 | tr -d '\n')
printf '{"auths":{"ghcr.io":{"auth":"%s"}}}' "$auth" > "$tmp"
# kubeseal이 실패해도 레포의 기존 파일이 비지 않게 임시 파일에 쓴 뒤 옮긴다.
kubectl create secret generic ghcr-pull -n hapbang-prod --type=kubernetes.io/dockerconfigjson \
  --from-file=.dockerconfigjson="$tmp" --dry-run=client -o yaml \
  | kubeseal --context "$ctx" --format yaml > "$sealed"
cp "$sealed" "$out"
echo "봉인했다: ${out#"$root"/} (계정 $user, 만료 $expires)"
