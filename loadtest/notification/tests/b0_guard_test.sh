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
  # 비교용 스냅샷(app-final.log)은 kill 전에 받는다. kill 뒤에 받으면 그사이 앱이 쓴 줄이 app.log에 없어 거짓 실패가 난다(PR 리뷰)
  snap=$(grep -n "^+ kubectl .* logs deploy/auxiliary-app > .*app-final.log" <<<"$out" | head -1 | cut -d: -f1)
  wait_line=$(grep -n "^+ sleep " <<<"$out" | awk -F: -v s="${snap:-0}" '$1 > s { print $1; exit }')
  alive=$(grep -n "^+ kill -0 <앱 로그 따라 받기>" <<<"$out" | head -1 | cut -d: -f1)
  stop=$(grep -n "^+ kill <앱 로그 따라 받기>" <<<"$out" | head -1 | cut -d: -f1)
  check=$(grep -n "^+ 앱 로그 따라 받기 끊김 확인" <<<"$out" | head -1 | cut -d: -f1)
  step5=$(grep -nE "^\[.*\] 5\. " <<<"$out" | head -1 | cut -d: -f1)
  step8=$(grep -nE "^\[.*\] 8\. " <<<"$out" | head -1 | cut -d: -f1)
  step12=$(grep -nE "^\[.*\] 12\. " <<<"$out" | head -1 | cut -d: -f1)
  order="시작=${start:-없음} 스냅샷=${snap:-없음} 대기=${wait_line:-없음} 생존=${alive:-없음} 멈춤=${stop:-없음} 확인=${check:-없음} 5=${step5:-없음} 8=${step8:-없음} 12=${step12:-없음}"
  if [ "$code" = 0 ] && [ -n "$start" ] && [ -n "$snap" ] && [ -n "$wait_line" ] && [ -n "$alive" ] && [ -n "$stop" ] \
      && [ -n "$check" ] && [ -n "$step5" ] && [ -n "$step8" ] && [ -n "$step12" ] && [ "$start" -gt "$step5" ] \
      && [ "$start" -lt "$step8" ] && [ "$snap" -gt "$step12" ] && [ "$wait_line" -gt "$snap" ] \
      && [ "$alive" -gt "$wait_line" ] && [ "$stop" -gt "$alive" ] && [ "$check" -gt "$stop" ]; then
    echo "PASS 앱 로그 따라 받기(5단계 뒤 시작, 12단계에서 스냅샷 → 대기 → 생존 확인 → kill → 확인)"
  else
    echo "FAIL 앱 로그 따라 받기: exit=$code, 줄 순서=$order"; fail=1
  fi
else
  echo "SKIP 앱 로그 따라 받기 시험(k3d-hapbang-b0가 없다)"
fi

# 5) 끊김 확인(stop_log_follow)을 모의 kubectl·모의 따라 받기로 실제 실행한다. cluster가 필요 없다.
#    모의 앱은 0.1초마다 컨테이너 로그에 한 줄을 쓰고, 모의 따라 받기는 0.2초마다 그 로그를 app.log로 옮긴다(수집 지연).
#    모의 kubectl logs는 0.3초 뒤 그때의 컨테이너 로그를 낸다(API 지연). kill 뒤에 스냅샷을 받으면 그사이 쓴 줄 때문에 거짓 실패가 난다
stop_fn=$(sed -n '/^LOG_FLUSH_SECONDS=/,/^}/p' "$B0")
mock_dir=$(mktemp -d)
mock_writer=
mock_follower=
# 시험이 중간에 끊겨도 모의 앱·따라 받기 반복이 남지 않게 한다
trap 'kill $mock_writer $mock_follower 2>/dev/null; rm -rf "$mock_dir"' EXIT
start_mock() {
  : > "$mock_dir/container.log"; : > "$mock_dir/out/app.log"
  ( i=0; while :; do i=$((i + 1)); echo "app line $i" >> "$mock_dir/container.log"; sleep 0.1; done ) &
  mock_writer=$!
}
stop_mock() { kill "$mock_writer" 2>/dev/null; wait "$mock_writer" 2>/dev/null; }
# $1: 따라 받기 상태(alive: 계속 옮김, dead: 미리 끝남), $2: 모의 kubectl logs 성공 여부(ok·fail). 출력은 stop_log_follow의 stderr
run_stop() {
  local follow=$1 snapshot=$2
  mkdir -p "$mock_dir/out"; start_mock
  ( while :; do cp "$mock_dir/container.log" "$mock_dir/out/app.log.tmp" && mv "$mock_dir/out/app.log.tmp" "$mock_dir/out/app.log"; sleep 0.2; done ) &
  local follower=$!
  mock_follower=$follower
  sleep 0.5
  # quiet: 앱이 쓰기를 멈춘 뒤 따라 받기가 끝난다(비교로는 끊김이 드러나지 않는 경우)
  if [ "$follow" = quiet ]; then stop_mock; sleep 0.5; fi
  if [ "$follow" = dead ] || [ "$follow" = quiet ]; then kill "$follower" 2>/dev/null; wait "$follower" 2>/dev/null; sleep 0.5; fi
  (
    set -euo pipefail
    DRY=0 CTX=k3d-hapbang-b0 APP_NS=hapbang-app logs_pid=$follower MOCK_DIR=$mock_dir MOCK_SNAPSHOT=$snapshot
    die() { echo "b0: $*" >&2; exit 2; }
    kubectl() { sleep 0.3; [ "$MOCK_SNAPSHOT" = ok ] || return 1; cat "$MOCK_DIR/container.log"; }
    eval "$stop_fn"
    declare -F stop_log_follow >/dev/null || { echo "b0.sh에서 stop_log_follow를 온전히 꺼내지 못했다"; exit 3; }
    LOG_FLUSH_SECONDS=1
    stop_log_follow "$MOCK_DIR/out"
  ) 2>&1
  local rc=$?
  kill "$follower" 2>/dev/null; wait "$follower" 2>/dev/null; stop_mock
  return $rc
}

if [ -z "$stop_fn" ]; then
  echo "FAIL 끊김 확인 모의 실행: b0.sh에서 stop_log_follow를 찾지 못했다"; fail=1
else
  out=$(run_stop alive ok); code=$?
  if [ "$code" = 0 ]; then echo "PASS 끊김 확인: 따라 받기가 살아 있으면 앱이 계속 써도 통과"
  else echo "FAIL 끊김 확인: 정상 따라 받기를 끊김으로 봤다(거짓 실패) exit=$code: $out"; fail=1; fi

  out=$(run_stop dead ok); code=$?
  expect "끊김 확인: 따라 받기가 먼저 끝나면 실패하고 원인을 알린다" 2 "$code" "$out" "kill 전에 이미 끝나 있었다"

  out=$(run_stop quiet ok); code=$?
  expect "끊김 확인: 그 뒤 앱 로그가 없어도 먼저 끝난 따라 받기는 실패다" 2 "$code" "$out" "kill 전에 이미 끝나 있었다"

  out=$(run_stop alive fail); code=$?
  expect "끊김 확인: 스냅샷을 못 받으면 통과로 보지 않는다" 2 "$code" "$out" "app-final.log를 받지 못했다"
fi

exit $fail
