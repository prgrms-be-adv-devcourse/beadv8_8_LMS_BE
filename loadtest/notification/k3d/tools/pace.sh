#!/usr/bin/env bash
# N15 L3 투입 속도 조절(NT-LOAD-L3, ND-193). 표준 입력의 줄을 초당 <rate>줄로 표준 출력에 낸다(console producer 앞에 둔다).
# 1초 창마다 rate줄을 내보내고 그 창이 끝날 때까지 기다린다. 창 끝을 이미 지났으면(출력이 막혀 늦어짐) 기준 시각을 지금으로 옮겨
# 다음 창을 지금부터 센다. 밀린 창을 연달아 내보내 따라잡지 않는다(초당 rate줄을 넘지 않음).
# 사용: pace.sh <rate> < 파일 | kafka-console-producer.sh ...
set -uo pipefail

rate=$1
start=$(date +%s.%N)
window=0
count=0
while IFS= read -r line; do
  printf '%s\n' "$line"
  count=$((count + 1))
  if [ "$count" -ge "$rate" ]; then
    count=0
    window=$((window + 1))
    now=$(date +%s.%N)
    wait_s=$(awk -v s="$start" -v w="$window" -v n="$now" 'BEGIN { d = s + w - n; printf "%.3f", (d > 0 ? d : 0) }')
    if [ "$wait_s" != "0.000" ]; then
      sleep "$wait_s"
    else
      start=$now
      window=0
    fi
  fi
done
