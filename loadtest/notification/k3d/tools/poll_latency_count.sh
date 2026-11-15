#!/usr/bin/env bash
# B0 정상 단계 완료 polling(N13, ND-167·ND-168·ND-169). b0.sh가 도구 pod(b0-kafka-tools)에 넣고 kubectl exec로 돌린다.
# 앱 pod의 지표를 이름 필터(includedNames)로 좁혀 받아 알림 토픽 5개의 성공 처리 건수(consume.latency count)를 awk로 센다.
# 한 줄씩 "클러스터 시각 TAB 기준 대비 증가량 TAB 조회에 걸린 초"를 출력한다. 시각은 응답을 받은 뒤의 이 pod 시계다.
# 증가량이 기대에 닿으면 B0_EXTRA_POLLS번 더 본 뒤(더 늘지 않는지 보려고) 0으로 끝난다. 기한 안에 닿지 않으면 3으로 끝난다.
#
# 사용: poll_latency_count.sh <기준값> <기대 증가량>
# 환경 변수: B0_FETCH(지표 본문을 표준 출력으로 내는 명령), B0_AWK(alert_latency_count.awk 경로),
#           B0_INTERVAL(초, 기본 0.5), B0_EXTRA_POLLS(기본 10), B0_DEADLINE_SECONDS(기본 900)
set -uo pipefail

base=$1
expected=$2
interval=${B0_INTERVAL:-0.5}
extra=${B0_EXTRA_POLLS:-10}
deadline=$(( $(date +%s) + ${B0_DEADLINE_SECONDS:-900} ))
after_reach=-1

while :; do
  start=$(date +%s.%N)
  if body=$($B0_FETCH); then
    end=$(date +%s.%N)
    count=$(printf '%s\n' "$body" | awk -f "$B0_AWK")
    printf '%s\t%s\t%s\n' "$end" "$((count - base))" "$(awk -v a="$start" -v b="$end" 'BEGIN { printf "%.3f", b - a }')"
    # 추가 확인은 성공한 조회만 센다(조회가 실패한 회차는 "더 늘지 않았다"는 근거가 아니다)
    if [ "$after_reach" -ge 0 ]; then
      after_reach=$((after_reach + 1))
    elif [ "$((count - base))" -ge "$expected" ]; then
      after_reach=0
    fi
    [ "$after_reach" -ge "$extra" ] && exit 0
  fi
  [ "$(date +%s)" -lt "$deadline" ] || exit 3
  sleep "$interval"
done
