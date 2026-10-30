#!/usr/bin/env bash
# 배포할 앱 목록과 배포 순서의 단일 출처(#374). 이미지 발행(publish.yml)과 배포 workflow가 함께 쓴다.
# 순서는 다른 앱이 부르는 제공 쪽(member·billing)을 먼저 둔다. 새 앱(apps/<앱>-app)은 여기에 추가해야 발행·배포된다.
#
# 사용법:
#   apps.sh list                 모든 앱을 배포 순서대로 출력
#   apps.sh check                apps/*-app 디렉터리와 목록이 다르면 실패(ci.yml verify)
#   apps.sh changed <base> <head>
#                                base..head 사이에 바뀐 앱을 배포 순서대로 출력(한 줄에 하나)
#                                - apps/<앱>/**                           → 그 앱
#                                - common/**, 루트 build.gradle.kts·settings.gradle.kts, gradle/** → 전체 앱
#                                - 그 밖(docs/, k8s/, .github/ 등)      → 없음
#                                base가 비었거나 0000…(브랜치 첫 push)이거나 커밋을 찾을 수 없으면 전체 앱
#   apps.sh source <앱> <rev>   rev 시점의 그 앱 내용을 마지막으로 바꾼 커밋(40자). 이미지 태그 sha-<커밋>에 쓴다
#                                발행은 head가 아니라 이 커밋으로 태그를 붙이고, 배포도 같은 커밋으로 이미지를 찾는다.
#                                rebase 병합처럼 push 한 번에 커밋이 여러 개여도 발행·배포가 같은 태그를 가리킨다.
# 레포 루트에서 실행한다. changed는 base·head 커밋이 있어야 하므로 checkout에 fetch-depth: 0이 필요하다.
set -euo pipefail

APPS=(
  member-app
  billing-app
  appearance-app
  lecture-app
  chat-app
  customer-support-app
)

list() {
  printf '%s\n' "${APPS[@]}"
}

check() {
  local expected actual
  expected=$(printf '%s\n' "${APPS[@]}" | sort)
  actual=$(find apps -mindepth 1 -maxdepth 1 -type d -name '*-app' -exec basename {} \; | sort)
  if [ "$expected" != "$actual" ]; then
    echo "::error::.github/scripts/apps.sh의 앱 목록과 apps/*-app 디렉터리가 다릅니다. 새 앱은 목록에 배포 순서대로 추가하세요."
    diff <(echo "$expected") <(echo "$actual") || true
    exit 1
  fi
  echo "앱 목록 일치: ${#APPS[@]}개"
}

# 이 경로가 바뀌면 전체 앱이 바뀐 것으로 본다. 앱 자기 경로는 apps/<앱>/이다.
SHARED_PATHS=(common/ gradle/ build.gradle.kts settings.gradle.kts)

changed() {
  local base=${1:-} head=${2:?head 커밋이 필요합니다}
  if [[ -z "$base" || "$base" =~ ^0+$ ]] || ! git cat-file -e "$base^{commit}" 2>/dev/null; then
    list
    return
  fi

  if ! git diff --quiet "$base" "$head" -- "${SHARED_PATHS[@]}"; then
    list
    return
  fi

  local app
  for app in "${APPS[@]}"; do
    if ! git diff --quiet "$base" "$head" -- "apps/$app/"; then
      echo "$app"
    fi
  done
}

source_commit() {
  local app=${1:?앱 이름이 필요합니다} rev=${2:?rev가 필요합니다}
  if ! printf '%s\n' "${APPS[@]}" | grep -qx "$app"; then
    echo "목록에 없는 앱입니다: $app" >&2
    exit 2
  fi
  git log --first-parent -1 --format=%H "$rev" -- "apps/$app/" "${SHARED_PATHS[@]}"
}

case "${1:-}" in
  list) list ;;
  check) check ;;
  changed) shift; changed "$@" ;;
  source) shift; source_commit "$@" ;;
  *) echo "사용법: $0 list | check | changed <base> <head> | source <앱> <rev>" >&2; exit 2 ;;
esac
