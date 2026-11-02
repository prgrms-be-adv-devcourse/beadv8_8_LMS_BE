#!/usr/bin/env bash
# apps.sh tested(CI가 테스트할 앱)의 경로 판정을 임시 git 저장소에서 확인한다(#488). ci.yml changes job이 실행한다.
# 판정이 틀리면 바뀐 앱의 테스트가 조용히 빠지므로, 규칙을 바꾸면 여기에 사례를 더한다.
# 사용법: .github/scripts/apps-test.sh
set -euo pipefail

SCRIPT="$(cd "$(dirname "$0")" && pwd)/apps.sh"
REPO=$(mktemp -d)
trap 'rm -rf "$REPO"' EXIT

# 개발자의 git 설정(hook·서명)이 임시 저장소 커밋에 끼어들지 않게 한다.
export GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_NOSYSTEM=1
g() { git -C "$REPO" -c user.name=apps-test -c user.email=apps-test@localhost "$@"; }

ALL=$("$SCRIPT" list)

# 기준 커밋: 앱마다 소스·manifest·문서, common 라이브러리, 루트 빌드·CI 파일
g init -q
for app in $ALL; do
  mkdir -p "$REPO/apps/$app/src/main/java" "$REPO/apps/$app/k8s/base"
  echo base > "$REPO/apps/$app/src/main/java/App.java"
  echo base > "$REPO/apps/$app/k8s/base/deployment.yaml"
  echo base > "$REPO/apps/$app/AGENTS.md"
done
mkdir -p "$REPO/common/shared/src/main/java" "$REPO/.github/workflows" "$REPO/.github/scripts" "$REPO/docs"
echo base > "$REPO/common/shared/src/main/java/Shared.java"
echo base > "$REPO/build.gradle.kts"
g add -A
g commit -qm base
BASE=$(g rev-parse HEAD)

failures=0

# expect <사례> <기대 출력(앱마다 한 줄, 없으면 "")> <tested에 줄 base> <바꿀 경로...>
# 사례마다 기준 커밋에서 경로를 바꾼 커밋을 하나 만들고 tested <base> <그 커밋>의 출력을 비교한다.
expect() {
  local name=$1 want=$2 base=$3 path got
  shift 3
  g checkout -q --detach "$BASE"
  for path in "$@"; do
    mkdir -p "$REPO/$(dirname "$path")"
    echo "$name" >> "$REPO/$path"
  done
  g add -A
  g commit -qm "$name"
  got=$(cd "$REPO" && "$SCRIPT" tested "$base" HEAD 2>&1) || got="실패(exit $?) $got"
  if [ "$got" = "$want" ]; then
    echo "ok   $name"
  else
    echo "FAIL $name"
    echo "     기대: [${want//$'\n'/ }]"
    echo "     결과: [${got//$'\n'/ }]"
    failures=$((failures + 1))
  fi
}

expect "앱 소스 → 그 앱" "chat-app" "$BASE" apps/chat-app/src/main/java/App.java
expect "앱 Dockerfile → 그 앱" "member-app" "$BASE" apps/member-app/Dockerfile
expect "앱 둘 → 배포 순서대로" "$(printf '%s\n' billing-app chat-app)" "$BASE" apps/chat-app/build.gradle.kts apps/billing-app/src/main/java/App.java
expect "앱 소스와 문서 → 그 앱" "lecture-app" "$BASE" apps/lecture-app/src/main/java/App.java apps/lecture-app/AGENTS.md

expect "common 라이브러리 → 전체" "$ALL" "$BASE" common/shared/src/main/java/Shared.java
expect "루트 build.gradle.kts → 전체" "$ALL" "$BASE" build.gradle.kts
expect "settings.gradle.kts → 전체" "$ALL" "$BASE" settings.gradle.kts
expect "Gradle version catalog → 전체" "$ALL" "$BASE" gradle/libs.versions.toml
expect "Gradle wrapper 스크립트 → 전체" "$ALL" "$BASE" gradlew
expect "CI workflow → 전체" "$ALL" "$BASE" .github/workflows/ci.yml
expect "앱 판정 스크립트 → 전체" "$ALL" "$BASE" .github/scripts/apps.sh

expect "앱 manifest만 → 없음(manifests job이 검사)" "" "$BASE" apps/chat-app/k8s/base/deployment.yaml apps/billing-app/k8s/overlays/prod/kustomization.yaml
expect "앱·common 문서(*.md)만 → 없음" "" "$BASE" apps/chat-app/AGENTS.md apps/billing-app/docs/decisions.md common/shared/README.md
expect "docs·루트 k8s·다른 workflow → 없음" "" "$BASE" docs/deploy/k8s.md k8s/cluster/kustomization.yaml .github/workflows/publish.yml

# 기준 커밋을 알 수 없으면 판정하지 않고 전체를 테스트한다.
expect "기준 없음 → 전체" "$ALL" "" apps/chat-app/src/main/java/App.java
expect "기준 0000… → 전체" "$ALL" 0000000000000000000000000000000000000000 apps/chat-app/src/main/java/App.java
expect "기준 커밋이 저장소에 없음 → 전체" "$ALL" 1234567890abcdef1234567890abcdef12345678 apps/chat-app/src/main/java/App.java

if [ "$failures" -gt 0 ]; then
  echo "::error::apps.sh tested 판정 ${failures}건이 기대와 다릅니다."
  exit 1
fi
