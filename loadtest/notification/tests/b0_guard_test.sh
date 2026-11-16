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

# 4) 앱 로그는 앱 기동 직후부터 따라 받아(kubectl logs -f) 컨테이너 로그가 회전해도 회차 전체가 남는다(N14, ND-185, V8).
#    --dry-run run은 명령만 출력한다. 따라 받기는 앱 기동(5단계) 뒤·정상 단계 투입(8단계) 전에 시작하고 수집(12단계)에서 멈춘다
if kubectl config get-contexts k3d-hapbang-b0 >/dev/null 2>&1; then
  out=$(bash "$B0" --dry-run run 1 2>&1); code=$?
  # 시작은 따라 받는 kubectl 명령, 멈춤은 멈추는 단계가 직접 내는 dry-run 명령(+ kill)과 확인 명령으로 본다(설명 log 문장이 아니다)
  start=$(grep -n "logs -f deploy/auxiliary-app" <<<"$out" | head -1 | cut -d: -f1)
  stop=$(grep -n "^+ kill <앱 로그 따라 받기>" <<<"$out" | head -1 | cut -d: -f1)
  check=$(grep -n "^+ 앱 로그 따라 받기 끊김 확인" <<<"$out" | head -1 | cut -d: -f1)
  step5=$(grep -nE "^\[.*\] 5\. " <<<"$out" | head -1 | cut -d: -f1)
  step8=$(grep -nE "^\[.*\] 8\. " <<<"$out" | head -1 | cut -d: -f1)
  step12=$(grep -nE "^\[.*\] 12\. " <<<"$out" | head -1 | cut -d: -f1)
  order="시작=${start:-없음} 멈춤=${stop:-없음} 확인=${check:-없음} 5=${step5:-없음} 8=${step8:-없음} 12=${step12:-없음}"
  if [ "$code" = 0 ] && [ -n "$start" ] && [ -n "$stop" ] && [ -n "$check" ] && [ -n "$step5" ] && [ -n "$step8" ] \
      && [ -n "$step12" ] && [ "$start" -gt "$step5" ] && [ "$start" -lt "$step8" ] && [ "$stop" -gt "$step12" ] \
      && [ "$check" -gt "$stop" ]; then
    echo "PASS 앱 로그 따라 받기(5단계 뒤 시작, 12단계에서 멈춤)"
  else
    echo "FAIL 앱 로그 따라 받기: exit=$code, 줄 순서=$order"; fail=1
  fi
else
  echo "SKIP 앱 로그 따라 받기 시험(k3d-hapbang-b0가 없다)"
fi

exit $fail
