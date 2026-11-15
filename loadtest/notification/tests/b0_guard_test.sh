#!/usr/bin/env bash
# b0.sh 안전 장치 시험(N13, test-spec NT-B0-09, S1). 실제 cluster·Kafka·DB를 바꾸지 않는다.
#   1) B0 전용이 아닌 context(예: 운영 hapbang-k3s)는 --dry-run에서도 거절한다(종료 코드 2).
#   2) B0 cluster가 없으면 run은 거절한다(setup 먼저).
#   3) --dry-run setup은 명령만 출력하고 cluster를 만들지 않는다.
set -uo pipefail
export MSYS_NO_PATHCONV=1
B0="$(cd "$(dirname "$0")/.." && pwd)/b0.sh"
fail=0

expect() {
  local name=$1 want=$2 got=$3 output=$4 pattern=$5
  if [ "$got" != "$want" ] || ! grep -q -- "$pattern" <<<"$output"; then
    echo "FAIL $name: exit=$got(want $want), 출력에 '$pattern' 없음"; echo "$output" | head -5; fail=1
  else
    echo "PASS $name"
  fi
}

out=$(B0_CONTEXT=hapbang-k3s bash "$B0" --dry-run run 1 2>&1); code=$?
expect "운영 context 거절" 2 "$code" "$out" "k3d-hapbang-b0만 허용"

out=$(B0_CONTEXT=k3d-hapbang bash "$B0" --dry-run setup 2>&1); code=$?
expect "다른 로컬 context 거절" 2 "$code" "$out" "k3d-hapbang-b0만 허용"

if ! kubectl config get-contexts k3d-hapbang-b0 >/dev/null 2>&1; then
  before=$(k3d cluster list 2>/dev/null)
  out=$(bash "$B0" --dry-run run 1 2>&1); code=$?
  expect "cluster 없으면 run 거절" 2 "$code" "$out" "setup을 실행한다"
  out=$(bash "$B0" --dry-run setup 2>&1); code=$?
  expect "dry-run setup은 출력만" 0 "$code" "$out" "+ k3d cluster create"
  after=$(k3d cluster list 2>/dev/null)
  if [ "$before" != "$after" ]; then echo "FAIL dry-run이 cluster 목록을 바꿨다"; fail=1; else echo "PASS cluster 목록 그대로"; fi
else
  echo "SKIP cluster 없음 시험(k3d-hapbang-b0가 이미 있다)"
fi

exit $fail
