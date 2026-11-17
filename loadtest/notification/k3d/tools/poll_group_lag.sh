#!/usr/bin/env bash
# N15 L4 lag 관측(NT-LOAD-L4, ND-190). n15.sh가 도구 pod(b0-kafka-tools)에 넣고 kubectl exec로 돌린다.
# broker가 보는 소비 그룹 lag(커밋한 offset 기준)를 kafka-consumer-groups.sh로 한 번에 읽는다. 한 번 읽는 데 JVM 기동 몇 초가 걸리므로
# 표본 간격은 그만큼이다(관측 간격은 결과에 남긴다).
# 한 줄씩 "응답을 받은 뒤의 이 pod 시각 TAB 합계 lag TAB lag이 숫자로 보인 group 수 TAB 조회에 걸린 초"를 출력한다.
# 모든 group이 보이고 합계가 0인 관측이 N15_EXTRA_POLLS번 이어지면 0으로 끝난다. 기한 안에 닿지 않으면 3으로 끝난다.
#
# 사용: poll_group_lag.sh <bootstrap> <group>...
# 환경 변수: N15_EXTRA_POLLS(기본 3), N15_DEADLINE_SECONDS(기본 900), KAFKA_BIN(기본 /opt/kafka/bin)
set -uo pipefail

bootstrap=$1
shift
groups=("$@")
args=()
for g in "${groups[@]}"; do args+=(--group "$g"); done
bin=${KAFKA_BIN:-/opt/kafka/bin}
extra=${N15_EXTRA_POLLS:-3}
deadline=$(( $(date +%s) + ${N15_DEADLINE_SECONDS:-900} ))
zero=0
wanted=$(printf '%s\n' "${groups[@]}")

while :; do
  start=$(date +%s.%N)
  body=$("$bin/kafka-consumer-groups.sh" --bootstrap-server "$bootstrap" --describe "${args[@]}" 2>/dev/null)
  end=$(date +%s.%N)
  # 열: GROUP TOPIC PARTITION CURRENT-OFFSET LOG-END-OFFSET LAG ... . 아직 커밋하지 않은 partition(CURRENT-OFFSET "-")은
  # 처음부터 읽어야 하므로 LOG-END-OFFSET 전체를 lag로 센다(빼면 lag을 작게 본다). LOG-END-OFFSET이 숫자인 행이 있는 group을 "보인" group으로 센다
  read -r lag seen < <(printf '%s\n' "$body" | awk -v wanted="$wanted" '
    BEGIN { n = split(wanted, w, "\n"); for (i = 1; i <= n; i++) want[w[i]] = 1 }
    ($1 in want) && $5 ~ /^[0-9]+$/ { lag += ($6 ~ /^[0-9]+$/ ? $6 : $5); seen[$1] = 1 }
    END { c = 0; for (g in seen) c++; printf "%d %d\n", lag, c }')
  printf '%s\t%s\t%s\t%s\n' "$end" "$lag" "$seen" "$(awk -v a="$start" -v b="$end" 'BEGIN { printf "%.3f", b - a }')"
  if [ "$lag" = 0 ] && [ "$seen" = "${#groups[@]}" ]; then
    zero=$((zero + 1))
    [ "$zero" -ge "$extra" ] && exit 0
  else
    zero=0
  fi
  [ "$(date +%s)" -lt "$deadline" ] || exit 3
done
