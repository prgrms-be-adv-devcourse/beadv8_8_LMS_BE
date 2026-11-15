#!/usr/bin/env bash
# B0 처리량 변동 진단(N13): 도구 pod(b0-kafka-tools)에서 1초마다 앱 지표(이름 필터)와 VM 전체 CPU(/proc/stat)를 읽는다.
# 출력: "app TAB 시각 TAB 지표 줄"(주석·_bucket·_sum 줄 뺌), "vm TAB 시각 TAB 0 TAB 0 TAB 0 TAB cpu 필드".
# 정지 파일(/tmp/b0/diag.stop)이 생기거나 기한(초)이 지나면 끝난다. 사용: tools_sampler.sh <지표 URL> <기한 초>
set -uo pipefail
url=$1
end=$(( $(date +%s) + $2 ))
rm -f /tmp/b0/diag.stop
while [ ! -e /tmp/b0/diag.stop ] && [ "$(date +%s)" -lt "$end" ]; do
  t=$(date +%s.%N)
  wget -qO- "$url" 2>/dev/null | awk -v t="$t" '!/^#/ && !/_bucket\{/ && !/_sum\{/ && NF {print "app\t" t "\t" $0}'
  printf 'vm\t%s\t0\t0\t0\t%s\n' "$t" "$(head -n 1 /proc/stat | awk '{$1=""; sub(/^ /, ""); print}')"
  sleep 1
done
