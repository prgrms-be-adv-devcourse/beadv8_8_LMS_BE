#!/usr/bin/env bash
# 알림 N15 본 부하 시험 격리 단계(NT-LOAD-L3·L4·L5S·L5B·L5M·L5F, ND-187~ND-197). B0 cluster(k3d-hapbang-b0) 전용이다.
# 운영(hapbang-k3s)에는 쓰지 않는다. b0.sh와 B0 구성(k3d/platform·auxiliary, k3d/tools의 기존 파일)은 그대로 쓰고 고치지 않는다(ND-189).
# k3d/tools에는 N15 전용 도구(pace.sh·poll_group_lag.sh)만 새로 더했다.
# 필요한 함수는 b0.sh에서 옮겨 왔다(b0.sh는 불러오면 바로 실행되므로 source하지 않는다).
#
# 사용(레포 루트에서, 먼저 b0.sh setup으로 cluster·앱 이미지를 준비한다):
#   loadtest/notification/n15.sh [--dry-run] l4 <회차>          # 정상 백로그 10,000 → 재개 → lag 0 → 실패 단계 → 검증
#   loadtest/notification/n15.sh [--dry-run] l5 <S|B|M|F> <회차> # 예약 생성 → (F: 복제본 삭제·기한 지남) → 회차 관측 → 검증
#   loadtest/notification/n15.sh [--dry-run] l3 <회차>          # STOMP 100연결(k6) → 알림 6,000건 초당 20건 → 수신 지연·유실 검증
#
# 입력: L4 fixture(N15_L4_FIXTURE, 기본 ~/.hapbang/load-tests/n15/l4. N15FixtureExportTest로 만든다).
#       L5·L3 fixture는 회차마다 Gradle로 회차 폴더 안에 만든다(L5는 시각을 정해서).
# 출력: 회차 폴더(N15_RUNS, 기본 ~/.hapbang/load-tests/runs/n15-<시험>-<시각>-r<회차>). 레포에 커밋하지 않는다
set -euo pipefail
export MSYS_NO_PATHCONV=1

CTX=${N15_CONTEXT:-k3d-hapbang-b0}
CLUSTER=hapbang-b0
native_path() { (cd "$1" && (pwd -W 2>/dev/null || pwd)); }
ROOT=$(native_path "$(dirname "$0")/../..")
DIR="$ROOT/loadtest/notification"
HOME_DIR=$(native_path "$HOME")
L4_FIXTURE=${N15_L4_FIXTURE:-$HOME_DIR/.hapbang/load-tests/n15/l4}
RUNS=${N15_RUNS:-$HOME_DIR/.hapbang/load-tests/runs}
APP_NS=hapbang-prod
PLATFORM_NS=hapbang-platform
TOOLS_POD=b0-kafka-tools
B0_KAFKA_MEMORY=700Mi
BOOTSTRAP=kafka.hapbang-platform.svc.cluster.local:9092
KAFKA_BIN=/opt/kafka/bin
ALERT_TOPICS=(appearance.events.v1 billing.events.v1 chat.events.v1 customersupport.events.v1 lecture.events.v1)
ALL_TOPICS=(member.events.v1 "${ALERT_TOPICS[@]}")
declare -A GROUP_OF=(
  [member.events.v1]=auxiliary-app.notification-member-replica
  [appearance.events.v1]=auxiliary-app.notification-appearance-alert
  [billing.events.v1]=auxiliary-app.notification-billing-alert
  [lecture.events.v1]=auxiliary-app.notification-lecture-alert
  [customersupport.events.v1]=auxiliary-app.notification-customersupport-alert
  [chat.events.v1]=auxiliary-app.notification-chat-alert
)
REQUIRED_CONFIG=("acks = -1" "enable.idempotence = true" "max.in.flight.requests.per.connection = 1"
  "partitioner.ignore.keys = false" "partitioner.class = null")
PF_PORT=18082
POSTGRES_POD=postgres-0
DRY=0
LOG_FLUSH_SECONDS=2

die() { echo "n15: $*" >&2; exit 2; }
log() { echo "[$(date +%H:%M:%S)] $*" >&2; }
now() { date +%s.%3N; }
show() { printf '+' >&2; printf ' %q' "$@" >&2; echo >&2; }
act() { if [ "$DRY" = 1 ]; then show "$@"; else "$@"; fi; }
kc() { act kubectl --context "$CTX" "$@"; }

# 안전 장치: B0 전용 로컬 cluster만 허용한다(b0.sh guard와 같은 규칙)
guard() {
  [ "$CTX" = "k3d-hapbang-b0" ] || die "context는 k3d-hapbang-b0만 허용한다: $CTX"
  kubectl config get-contexts "$CTX" >/dev/null 2>&1 || die "context $CTX가 없다. 먼저 b0.sh setup을 실행한다"
  local server
  server=$(kubectl config view -o jsonpath="{.clusters[?(@.name=='$CTX')].cluster.server}")
  [[ "$server" =~ ^https://(0\.0\.0\.0|127\.0\.0\.1|localhost|host\.docker\.internal): ]] \
    || die "context $CTX의 API 서버가 로컬이 아니다: $server"
  log "context=$CTX server=$server"
}

kafka() { kc -n "$PLATFORM_NS" exec "$TOOLS_POD" -- "$@"; }
kafka_i() { kc -n "$PLATFORM_NS" exec -i "$TOOLS_POD" -- "$@"; }
ensure_tools() {
  kc apply -f "$DIR/k3d/tools/kafka-tools.yaml"
  kc -n "$PLATFORM_NS" wait --for=condition=Ready "pod/$TOOLS_POD" --timeout=5m
}
psql_file() {
  kc -n "$PLATFORM_NS" exec -i "$POSTGRES_POD" -- psql -U postgres -d auxiliary -At -F $'\t' -v ON_ERROR_STOP=1 < "$1"
}
psql_c() {
  kc -n "$PLATFORM_NS" exec "$POSTGRES_POD" -- psql -U postgres -d auxiliary -At -F $'\t' -v ON_ERROR_STOP=1 -c "$1"
}

producer_command() {
  echo "KAFKA_LOG4J_OPTS='-Dlog4j2.configurationFile=/tmp/b0/producer-log4j2.yaml' $KAFKA_BIN/kafka-console-producer.sh \
--bootstrap-server $BOOTSTRAP --topic $1 --producer.config /tmp/b0/producer.properties \
--reader-property parse.key=true --reader-property parse.headers=true"
}

# 파일 하나를 한 토픽에 넣는다. 설정 적용 로그와 오류 줄을 본다(B0 V0-5와 같음). name은 stderr 파일 이름(producer-<name>.stderr)
inject_file() {
  local topic=$1 file=$2 name=$3 out=$4
  if [ "$DRY" = 1 ] && [ ! -f "$file" ]; then
    echo "+ (투입 $name: $file, 드라이런이라 파일을 만들지 않았다)" >&2
    return 0
  fi
  [ -f "$file" ] || die "투입 파일이 없다: $file"
  if [ ! -s "$file" ]; then log "투입 $name (0줄, 건너뜀)"; return 0; fi
  log "투입 $name ($(wc -l < "$file")줄)"
  if [ "$DRY" = 1 ]; then
    echo "+ kubectl --context $CTX -n $PLATFORM_NS exec -i $TOOLS_POD -- bash -c \"$(producer_command "$topic")\" < $file" >&2
    return 0
  fi
  kafka_i bash -c "echo B0_CLOCK_START \$(date +%s.%N) >&2; $(producer_command "$topic"); rc=\$?; echo B0_CLOCK_END \$(date +%s.%N) >&2; exit \$rc" \
    < "$file" 2> "$out/producer-$name.stderr" >/dev/null
  local line
  grep -q "ProducerConfig values" "$out/producer-$name.stderr" || die "producer 설정 로그가 없다: $name"
  for line in "${REQUIRED_CONFIG[@]}"; do
    grep -qF "$line" "$out/producer-$name.stderr" || die "producer 설정이 적용되지 않았다: $name: $line"
  done
  if grep -E " ERROR |Exception" "$out/producer-$name.stderr"; then die "producer 오류: $name"; fi
}

group_lag() {
  kafka "$KAFKA_BIN/kafka-consumer-groups.sh" --bootstrap-server "$BOOTSTRAP" --describe --group "$1" 2>/dev/null \
    | awk '$1 == "'"$1"'" && $6 ~ /^[0-9]+$/ {lag += $6; n++} END {if (n == 0) print -1; else print lag}'
}
topic_end_total() {
  kafka "$KAFKA_BIN/kafka-get-offsets.sh" --bootstrap-server "$BOOTSTRAP" --topic "$1" 2>/dev/null \
    | awk -F: '{s += $3} END {print s + 0}'
}
dump_topic() {
  local topic=$1 count
  count=$(topic_end_total "$topic")
  [ "$count" -gt 0 ] || return 0
  kafka "$KAFKA_BIN/kafka-console-consumer.sh" --bootstrap-server "$BOOTSTRAP" --topic "$topic" --from-beginning \
    --max-messages "$count" --timeout-ms 30000 \
    --formatter-property print.partition=true --formatter-property print.offset=true \
    --formatter-property print.headers=true --formatter-property print.key=true \
    --formatter-property print.timestamp=false --formatter-property 'key.separator=<<B0SEP>>' \
    --formatter-property 'headers.separator=<<B0HDR>>' --formatter-property 'line.separator=<<B0EOR>>' 2>/dev/null \
    | python -I "$DIR/normalize_dump.py" "$topic"
}
metrics() { curl -sf "http://localhost:$PF_PORT/actuator/prometheus"; }

measure_clock_offset() {
  local best="" i h0 p h1
  for i in 1 2 3 4 5; do
    h0=$(now); p=$(kubectl --context "$CTX" -n "$PLATFORM_NS" exec "$TOOLS_POD" -- date +%s.%N); h1=$(now)
    best=$(awk -v a="$h0" -v p="$p" -v b="$h1" -v best="$best" 'BEGIN {
      rtt = b - a; off = (a + b) / 2 - p
      if (best == "") { printf "%.4f %.4f", off, rtt; exit }
      split(best, x, " "); if (rtt < x[2]) printf "%.4f %.4f", off, rtt; else printf "%s", best }')
  done
  echo "$best"
}

# 5초마다 Hikari·CPU·메모리(B0 sampler와 같은 방식, ND-176)
sampler() {
  local out=$1
  while :; do
    local m top cpu mem
    m=$(metrics || true)
    top=$(kubectl --context "$CTX" -n "$APP_NS" top pod -l app.kubernetes.io/name=auxiliary-app --no-headers 2>/dev/null || true)
    cpu=$(awk '{gsub("m","",$2); print $2+0}' <<<"$top")
    mem=$(awk '{gsub("Mi","",$3); print $3+0}' <<<"$top")
    printf '{"t":%s,"hikariActive":%s,"hikariPending":%s,"hikariTimeout":%s,"cpuMillicores":%s,"memoryMiB":%s}\n' "$(date +%s)" \
      "$(awk '/^hikaricp_connections_active/ {s+=$2} END {print s+0}' <<<"$m")" \
      "$(awk '/^hikaricp_connections_pending/ {s+=$2} END {print s+0}' <<<"$m")" \
      "$(awk '/^hikaricp_connections_timeout_total/ {s+=$2} END {print s+0}' <<<"$m")" "${cpu:-0}" "${mem:-0}" >> "$out"
    sleep 5
  done
}

cleanup() {
  local pid
  for pid in ${logs_pid:-} ${lag_pid:-} ${poller_pid:-} ${sampler_pid:-} ${pf_pid:-}; do kill "$pid" 2>/dev/null; done
  true
}

start_log_follow() {
  local file=$1
  if [ "$DRY" = 1 ]; then show kubectl --context "$CTX" -n "$APP_NS" logs -f deploy/auxiliary-app; return; fi
  kubectl --context "$CTX" -n "$APP_NS" logs -f deploy/auxiliary-app > "$file" 2> "$file.stderr" &
  logs_pid=$!
}

# b0.sh stop_log_follow와 같은 끊김 확인(ND-185): 스냅샷 → 대기 → 살아 있는지 → 멈춤 → 마지막 줄 비교
stop_log_follow() {
  local file=$1 snapshot=$2 last
  if [ "$DRY" = 1 ]; then show kubectl --context "$CTX" -n "$APP_NS" logs deploy/auxiliary-app; return; fi
  kubectl --context "$CTX" -n "$APP_NS" logs deploy/auxiliary-app > "$snapshot" || die "비교용 로그를 받지 못했다"
  sleep "$LOG_FLUSH_SECONDS"
  kill -0 "$logs_pid" 2>/dev/null || { logs_pid=; die "앱 로그 따라 받기가 이미 끝나 있었다($file.stderr 확인)"; }
  kill "$logs_pid" 2>/dev/null || true
  wait "$logs_pid" 2>/dev/null || true
  logs_pid=
  last=$(grep -v '^[[:space:]]*$' "$snapshot" | tail -n 1 || true)
  [ -n "$last" ] || die "비교용 로그가 비었다"
  grep -qxF -- "$last" "$file" || die "앱 로그 따라 받기가 끊겼다($snapshot 마지막 줄이 $file에 없다)"
}

port_forward() {
  [ "$DRY" = 1 ] && { show kubectl port-forward deploy/auxiliary-app "$PF_PORT:8082"; return; }
  kubectl --context "$CTX" -n "$APP_NS" port-forward deploy/auxiliary-app "$PF_PORT:8082" >/dev/null 2>&1 &
  pf_pid=$!
  until metrics >/dev/null; do sleep 1; done
}
stop_port_forward() { [ -n "${pf_pid:-}" ] && { kill "$pf_pid" 2>/dev/null || true; wait "$pf_pid" 2>/dev/null || true; pf_pid=; }; true; }

app_down() {
  kc -n "$APP_NS" scale deploy/auxiliary-app --replicas=0
  kc -n "$APP_NS" wait --for=delete pod -l app.kubernetes.io/name=auxiliary-app --timeout=5m || true
}
app_up() {
  kc -n "$APP_NS" scale deploy/auxiliary-app --replicas=1
  kc -n "$APP_NS" rollout status deploy/auxiliary-app --timeout=10m
}
app_ip() {
  kubectl --context "$CTX" -n "$APP_NS" get pod -l app.kubernetes.io/name=auxiliary-app -o jsonpath='{.items[0].status.podIP}'
}

# B0와 같은 조건인지 확인해 남긴다(Kafka 700Mi ND-178, synchronous_commit=off ND-170)
record_conditions() {
  local out=$1 fixture=$2
  [ "$DRY" = 1 ] && return 0
  {
    echo "git=$(git -C "$ROOT" rev-parse HEAD)"
    echo "gitDirty=$(git -C "$ROOT" status --porcelain | wc -l)"
    echo "fixture=$fixture"
    echo "image=$(docker image inspect hapbang/auxiliary-app:local --format '{{.Id}}')"
  } > "$out/conditions.txt"
  local kafka_memory
  kafka_memory=$(kubectl --context "$CTX" -n "$PLATFORM_NS" get pod kafka-0 \
    -o jsonpath='{.spec.containers[?(@.name=="kafka")].resources.limits.memory}/{.spec.containers[?(@.name=="kafka")].resources.requests.memory}')
  echo "kafkaMemoryLimitRequest=$kafka_memory" >> "$out/conditions.txt"
  [ "$kafka_memory" = "$B0_KAFKA_MEMORY/$B0_KAFKA_MEMORY" ] || die "Kafka 메모리 한도/요청이 B0($B0_KAFKA_MEMORY)와 다르다: $kafka_memory"
}

reset_round() {
  local out=$1 topic group
  log "앱 내림·알림 데이터 비우기·커밋 설정 확인"
  app_down
  psql_file "$DIR/queries/reset.sql"
  if [ "$DRY" = 0 ]; then
    local commit_setting session_commit
    commit_setting=$(psql_file "$DIR/queries/commit_setting.sql" | grep -v '^ALTER DATABASE$' | tr '\n' ' ' | sed 's/ *$//')
    session_commit=$(psql_c "show synchronous_commit")
    [ "$session_commit" = off ] || die "새 연결의 synchronous_commit이 off가 아니다: $session_commit"
    [ "$commit_setting" = "(모든 역할)@auxiliary=synchronous_commit=off" ] || die "auxiliary 설정이 기대와 다르다: $commit_setting"
    printf 'synchronousCommit=%s\ndbRoleSettings=%s\n' "$session_commit" "$commit_setting" >> "$out/conditions.txt"
  fi
  log "Kafka 초기화(토픽·DLT·group)"
  for topic in "${ALL_TOPICS[@]}"; do
    group=${GROUP_OF[$topic]}
    kafka "$KAFKA_BIN/kafka-consumer-groups.sh" --bootstrap-server "$BOOTSTRAP" --delete --group "$group" || true
    kafka "$KAFKA_BIN/kafka-topics.sh" --bootstrap-server "$BOOTSTRAP" --delete --if-exists --topic "$topic" || true
    kafka "$KAFKA_BIN/kafka-topics.sh" --bootstrap-server "$BOOTSTRAP" --delete --if-exists --topic "$topic.$group.DLT" || true
  done
  if [ "$DRY" != 1 ]; then
    local deleted=() listed left deadline=$((SECONDS + 120))
    for topic in "${ALL_TOPICS[@]}"; do deleted+=("$topic" "$topic.${GROUP_OF[$topic]}.DLT"); done
    while :; do
      listed=$(kafka "$KAFKA_BIN/kafka-topics.sh" --bootstrap-server "$BOOTSTRAP" --list) || die "토픽 목록을 읽지 못했다"
      left=$(printf '%s\n' "${deleted[@]}" | grep -Fxf - <(printf '%s\n' "$listed") || true)
      [ -z "$left" ] && break
      [ "$SECONDS" -lt "$deadline" ] || die "토픽 삭제가 끝나지 않았다: $(echo $left)"
      sleep 2
    done
  fi
  for topic in "${ALL_TOPICS[@]}"; do
    kafka "$KAFKA_BIN/kafka-topics.sh" --bootstrap-server "$BOOTSTRAP" --create --topic "$topic" --partitions 3 --replication-factor 1
  done
  kafka_i bash -c 'mkdir -p /tmp/b0 && cat > /tmp/b0/producer.properties' < "$DIR/producer.properties"
  kafka_i bash -c 'cat > /tmp/b0/producer-log4j2.yaml' < "$DIR/producer-log4j2.yaml"
  kafka_i bash -c 'cat > /tmp/b0/alert_latency_count.awk' < "$DIR/queries/alert_latency_count.awk"
  kafka_i bash -c 'cat > /tmp/b0/poll_latency_count.sh' < "$DIR/k3d/tools/poll_latency_count.sh"
  kafka_i bash -c 'cat > /tmp/b0/poll_group_lag.sh' < "$DIR/k3d/tools/poll_group_lag.sh"
}

# 회원 복제본 수와 회원 group lag 0(단계 장벽). 결과는 BARRIER_REPLICAS·BARRIER_LAG에 둔다(프로세스 치환으로 부르면 die가 본 셸을 멈추지 못한다)
BARRIER_REPLICAS=0
BARRIER_LAG=-1
wait_barrier() {
  local expected=$1 deadline=$((SECONDS + 300))
  [ "$DRY" = 1 ] && { BARRIER_REPLICAS=0; BARRIER_LAG=0; return; }
  while :; do
    BARRIER_REPLICAS=$(psql_file "$DIR/queries/replicas.sql")
    BARRIER_LAG=$(group_lag "${GROUP_OF[member.events.v1]}")
    [ "$BARRIER_REPLICAS" = "$expected" ] && [ "$BARRIER_LAG" = 0 ] && return 0
    [ "$SECONDS" -lt "$deadline" ] || die "단계 장벽을 넘지 못했다: replicas=$BARRIER_REPLICAS lag=$BARRIER_LAG"
    sleep 3
  done
}

# 한 단계 투입의 클러스터 시계: start면 투입 순서(ALERT_TOPICS)상 첫 파일의 첫 B0_CLOCK_START, end면 마지막 파일의 마지막 B0_CLOCK_END.
# 파이프를 head로 끊지 않는다(pipefail·errexit 아래에서 앞 명령이 SIGPIPE로 끝나 스크립트가 141로 멈춘다. L4 1회차)
phase_clock() {
  local out=$1 phase=$2 which=$3 topic f value=""
  for topic in "${ALERT_TOPICS[@]}"; do
    f="$out/producer-$topic.$phase.stderr"
    [ -f "$f" ] || continue
    if [ "$which" = start ]; then
      awk '$1 == "B0_CLOCK_START" {print $2; exit}' "$f"
      return 0
    fi
    value=$(awk '$1 == "B0_CLOCK_END" {v = $2} END {print v}' "$f")
  done
  echo "$value"
}

# ---------------------------------------------------------------- L4

run_l4() {
  local round=$1 FIXTURE=$L4_FIXTURE
  guard
  trap cleanup EXIT
  [ -f "$FIXTURE/manifest.json" ] || die "L4 manifest가 없다: $FIXTURE/manifest.json (N15FixtureExportTest l4)"
  local out="$RUNS/n15-l4-$(date +%Y%m%d-%H%M%S)-r$round"
  act mkdir -p "$out"
  act chmod 700 "$out" 2>/dev/null || true
  log "L4 회차 $round → $out"
  ensure_tools
  record_conditions "$out" "$(cat "$FIXTURE/content.sha256")"
  reset_round "$out"

  log "1. 회원 투입(앱이 꺼진 동안) → 앱 기동 → 단계 장벽"
  inject_file member.events.v1 "$FIXTURE/member.events.v1.txt" member.events.v1 "$out"
  app_up
  start_log_follow "$out/app-barrier.log"
  local replicas lag barrier_at
  wait_barrier 1000
  replicas=$BARRIER_REPLICAS lag=$BARRIER_LAG
  barrier_at=$(now)

  log "2. 장벽 pod 지표 보존 → 소비자 정지(replicas 0)"
  port_forward
  [ "$DRY" = 0 ] && metrics > "$out/metrics-barrier.prom"
  stop_port_forward
  stop_log_follow "$out/app-barrier.log" "$out/app-barrier-final.log"
  app_down

  log "3. 정상 백로그 투입(소비자 정지 중)"
  local clock_offset=0 clock_rtt=0 inject_start
  [ "$DRY" = 0 ] && read -r clock_offset clock_rtt < <(measure_clock_offset)
  log "호스트 - 도구 pod 시계 차 ${clock_offset}초(왕복 ${clock_rtt}초)"
  inject_start=$(now)
  local topic
  for topic in "${ALERT_TOPICS[@]}"; do
    inject_file "$topic" "$FIXTURE/$topic.normal.txt" "$topic.normal" "$out"
  done
  local normal_inject_end; normal_inject_end=$(now)

  log "4. 재개(replicas 1) → 할당·처리·lag 0 관측"
  local expected_normal replicas_one_host app_addr
  expected_normal=$(python -I -c "import json,sys; print(sum(json.load(open(sys.argv[1], encoding='utf-8'))['phaseRecordsByTopic']['normal'].values()))" "$FIXTURE/manifest.json")
  replicas_one_host=$(now)
  app_up
  start_log_follow "$out/app.log"
  local groups=() t
  for t in "${ALERT_TOPICS[@]}"; do groups+=("${GROUP_OF[$t]}"); done
  if [ "$DRY" = 0 ]; then
    kubectl --context "$CTX" -n "$PLATFORM_NS" exec "$TOOLS_POD" -- env N15_EXTRA_POLLS=3 N15_DEADLINE_SECONDS=900 \
      bash /tmp/b0/poll_group_lag.sh "$BOOTSTRAP" "${groups[@]}" > "$out/lag.tsv" 2> "$out/lag.stderr" &
    lag_pid=$!
    app_addr=$(app_ip); [ -n "$app_addr" ] || die "앱 pod IP를 찾지 못했다"
    kubectl --context "$CTX" -n "$PLATFORM_NS" exec "$TOOLS_POD" -- env \
      "B0_FETCH=wget -qO- http://$app_addr:8082/actuator/prometheus?includedNames=hapbang_notification_consume_latency_seconds" \
      B0_AWK=/tmp/b0/alert_latency_count.awk B0_INTERVAL=0.5 B0_EXTRA_POLLS=10 B0_DEADLINE_SECONDS=900 \
      bash /tmp/b0/poll_latency_count.sh 0 "$expected_normal" > "$out/progress.tsv" 2> "$out/progress.stderr" &
    poller_pid=$!
    port_forward
    sampler "$out/samples.jsonl" &
    sampler_pid=$!
  fi

  log "5. 정상 백로그 처리 완료($expected_normal건) → lag 0"
  local normal_completed_at=0 cluster_done=0 lag_zero=0
  if [ "$DRY" = 0 ]; then
    local rc=0
    wait "$poller_pid" || rc=$?; poller_pid=
    normal_completed_at=$(now)
    [ "$rc" = 0 ] || die "정상 백로그 처리 건수가 기대에 닿지 않았다(종료 코드 $rc, 마지막 $(tail -n 1 "$out/progress.tsv" | cut -f2)/$expected_normal)"
    [ "$(tail -n 1 "$out/progress.tsv" | cut -f2)" = "$expected_normal" ] || die "처리 건수가 기대와 다르다(원인 분석 필요)"
    cluster_done=$(awk -F'\t' -v e="$expected_normal" '$2 == e {print $1; exit}' "$out/progress.tsv")
    rc=0
    wait "$lag_pid" || rc=$?; lag_pid=
    [ "$rc" = 0 ] || die "lag 0에 닿지 않았다(종료 코드 $rc, lag.tsv 확인)"
    lag_zero=$(awk -F'\t' '$2 == 0 {print $1; exit}' "$out/lag.tsv")
    local normal_dlt=0
    for topic in "${ALERT_TOPICS[@]}"; do normal_dlt=$((normal_dlt + $(topic_end_total "$topic.${GROUP_OF[$topic]}.DLT"))); done
    [ "$normal_dlt" = 0 ] || die "정상 백로그에 DLT가 생겼다: $normal_dlt"
    metrics > "$out/metrics-normal.prom"
  fi

  log "6. 실패 단계 투입 → DLT·재시도 완료"
  local failure_inject_start; failure_inject_start=$(now)
  for topic in "${ALERT_TOPICS[@]}"; do
    inject_file "$topic" "$FIXTURE/$topic.failure.txt" "$topic.failure" "$out"
  done
  local failure_inject_end; failure_inject_end=$(now)
  local expected_dlt completed_at=0
  expected_dlt=$(python -I -c "import json,sys; print(sum(json.load(open(sys.argv[1], encoding='utf-8'))['deadLetters'].values()))" "$FIXTURE/manifest.json")
  if [ "$DRY" = 0 ]; then
    local deadline=$((SECONDS + 1800)) stable_since=$SECONDS last="" total_lag dlt l current
    while :; do
      total_lag=0 dlt=0
      for topic in "${ALERT_TOPICS[@]}"; do
        l=$(group_lag "${GROUP_OF[$topic]}"); [ "$l" -ge 0 ] || l=999999
        total_lag=$((total_lag + l))
        dlt=$((dlt + $(topic_end_total "$topic.${GROUP_OF[$topic]}.DLT")))
      done
      current=$(metrics | grep -E '^hapbang_notification_consume_(outcomes|failures)_total' | sort | md5sum)
      [ "$current" = "$last" ] || { last=$current; stable_since=$SECONDS; }
      [ "$total_lag" = 0 ] && [ "$dlt" = "$expected_dlt" ] && [ $((SECONDS - stable_since)) -ge 30 ] && break
      [ "$SECONDS" -lt "$deadline" ] || die "실패 단계가 끝나지 않았다: lag=$total_lag dlt=$dlt/$expected_dlt"
      sleep 3
    done
    completed_at=$(python -I -c "print($(now) - 30)")
  fi

  log "7. 수집"
  if [ "$DRY" = 0 ]; then
    metrics > "$out/metrics-after.prom"
    kill "$sampler_pid" 2>/dev/null || true; sampler_pid=
    stop_log_follow "$out/app.log" "$out/app-final.log"
    stop_port_forward
    : > "$out/topics.tsv"
    for topic in "${ALL_TOPICS[@]}"; do dump_topic "$topic" >> "$out/topics.tsv"; done
    : > "$out/dlt.tsv"
    for topic in "${ALERT_TOPICS[@]}"; do dump_topic "$topic.${GROUP_OF[$topic]}.DLT" >> "$out/dlt.tsv"; done
    psql_file "$DIR/queries/total.sql" > "$out/db-total.txt"
    psql_file "$DIR/queries/rows.sql" > "$out/db-rows.tsv"
    psql_file "$DIR/queries/dup.sql" > "$out/db-dup.tsv"
    psql_file "$DIR/queries/schedules.sql" > "$out/db-schedules.tsv"
    psql_file "$DIR/queries/fingerprint.sql" > "$out/db-fingerprint.tsv"
    local cluster_start cluster_end replicas_one
    cluster_start=$(phase_clock "$out" normal start)
    cluster_end=$(phase_clock "$out" normal end)
    replicas_one=$(python -I -c "print(round($replicas_one_host - $clock_offset, 3))")
    # B0 검증기(verify_b0)가 읽는 키 + L4 키. 클러스터 시계 값은 도구 pod·앱 시계(같은 VM)다
    printf '{"replicas":%s,"memberLag":%s,"barrierAt":%s,"alertInjectStartAt":%s,"normalInjectEndAt":%s,"normalCompletedAt":%s,"failureInjectStartAt":%s,"failureInjectEndAt":%s,"completedAt":%s,"replicasOneAt":%s,"processedAt":%s,"lagZeroAt":%s,"clusterClock":{"normalInjectStartAt":%s,"normalInjectEndAt":%s,"normalCompletedAt":%s,"hostMinusClusterSeconds":%s,"clockRttSeconds":%s}}\n' \
      "$replicas" "$lag" "$barrier_at" "$inject_start" "$normal_inject_end" "$normal_completed_at" \
      "$failure_inject_start" "$failure_inject_end" "$completed_at" "$replicas_one" "$cluster_done" "${lag_zero:-0}" \
      "$cluster_start" "$cluster_end" "$cluster_done" "$clock_offset" "$clock_rtt" > "$out/timing.json"
  fi

  log "8. 검증"
  if [ "$DRY" = 0 ]; then
    python -I "$DIR/verify_n15.py" l4 "$FIXTURE/manifest.json" "$out"
  else
    echo "+ python -I $DIR/verify_n15.py l4 $FIXTURE/manifest.json $out" >&2
  fi
}

# ---------------------------------------------------------------- L5

# Git Bash(Windows)의 date는 IANA 이름(Asia/Seoul)을 모를 수 있어 POSIX 형식(KST-9 = UTC+9, 일광 절약 없음)으로 준다
kst() { TZ=KST-9 date -d "@$1" +%Y-%m-%dT%H:%M:%S; }
ceil_minute() { echo $(( ($1 + 59) / 60 * 60 )); }
wait_until() { local target=$1; [ "$DRY" = 1 ] && return 0; while [ "$(date +%s)" -lt "$target" ]; do sleep 1; done; }

# 예약 생성 확인: SCHEDULED 수가 기대에 닿을 때까지
wait_scheduled() {
  local expected=$1 deadline=$((SECONDS + 300)) n
  [ "$DRY" = 1 ] && return 0
  while :; do
    n=$(psql_c "select count(*) from notification.notification_schedule where kind = 'APPEARANCE_REQUEST_EXPIRING' and status = 'SCHEDULED'")
    [ "$n" = "$expected" ] && return 0
    [ "$n" -gt "$expected" ] && die "예약이 기대보다 많다: $n/$expected"
    [ "$SECONDS" -lt "$deadline" ] || die "예약 생성이 끝나지 않았다: $n/$expected"
    sleep 2
  done
}

run_l5() {
  local scenario=$1 round=$2
  case "$scenario" in S|B|M|F) ;; *) die "시나리오는 S·B·M·F 중 하나다: $scenario" ;; esac
  guard
  trap cleanup EXIT
  local out="$RUNS/n15-l5$scenario-$(date +%Y%m%d-%H%M%S)-r$round"
  act mkdir -p "$out"
  act chmod 700 "$out" 2>/dev/null || true
  log "L5-$scenario 회차 $round → $out"
  ensure_tools

  # 시각 계획(KST 정각 단위). F: M1:36에 기한 지남 예약 투입 → M1:55 전에 앱 내림 → 기한(M1+2:00) 뒤 다시 띄움 → T = M1+6:00
  local start send_at expired_deadline=0 m1=0
  start=$(date +%s)
  if [ "$scenario" = F ]; then
    m1=$(ceil_minute $((start + 300)))
    expired_deadline=$((m1 + 120))
    send_at=$((m1 + 360))
  else
    send_at=$(ceil_minute $((start + 480)))
  fi
  log "보낼 시각 $(kst "$send_at") KST$( [ "$scenario" = F ] && echo ", 기한 지남 예약 기한 $(kst "$expired_deadline") KST")"

  local fixture="$out/fixture"
  log "1. L5 fixture 만들기(Gradle)"
  if [ "$DRY" = 1 ]; then
    show env HAPBANG_N15_OUT="$fixture" HAPBANG_N15_WHAT=l5 HAPBANG_L5_SCENARIO="$scenario" HAPBANG_L5_SEND_AT="$(kst "$send_at")" \
      "$ROOT/gradlew" -p "$ROOT" :notification:test --tests '*N15FixtureExportTest' --rerun
  else
    env HAPBANG_N15_OUT="$fixture" HAPBANG_N15_WHAT=l5 HAPBANG_L5_SCENARIO="$scenario" HAPBANG_L5_SEND_AT="$(kst "$send_at")" \
      HAPBANG_L5_EXPIRED_DEADLINE="$(kst $((expired_deadline > 0 ? expired_deadline : send_at - 1800)))" \
      "$ROOT/gradlew" -p "$ROOT" -q :notification:test --tests '*N15FixtureExportTest' --rerun > "$out/export.log" 2>&1 \
      || die "fixture를 만들지 못했다($out/export.log)"
  fi
  local fdir="$fixture/l5-$scenario"
  record_conditions "$out" "l5-$scenario sendAt=$(kst "$send_at")"
  reset_round "$out"

  log "2. 회원 투입 → 앱 기동 → 단계 장벽"
  local members
  members=$( [ "$DRY" = 1 ] && echo 0 || wc -l < "$fdir/members.txt" | tr -d ' ')
  inject_file member.events.v1 "$fdir/members.txt" members "$out"
  app_up
  start_log_follow "$out/app-pre.log"
  wait_barrier "$members"
  local member_end; member_end=$( [ "$DRY" = 1 ] && echo 0 || topic_end_total member.events.v1)

  local failing_ids="" expired_rows=0
  if [ "$scenario" = F ]; then
    expired_rows=$( [ "$DRY" = 1 ] && echo 20 || wc -l < "$fdir/expired.txt" | tr -d ' ')
    log "3. 기한 지남 예약: $(kst $((m1 + 36))) KST에 투입 → 생성 확인 → 앱 내림 → 기한 뒤 다시 띄움"
    [ "$DRY" = 1 ] || [ "$(date +%s)" -le $((m1 + 36)) ] || die "시각 계획을 지나쳤다(기한 지남 예약 투입 시각), 다시 실행한다"
    wait_until $((m1 + 36))
    inject_file appearance.events.v1 "$fdir/expired.txt" expired "$out"
    wait_scheduled "$expired_rows"
    [ "$DRY" = 1 ] || [ "$(date +%s)" -le $((m1 + 50)) ] || die "기한 지남 예약을 만든 뒤 앱을 내리기 전에 회차가 돌 수 있다, 다시 실행한다"
    stop_log_follow "$out/app-pre.log" "$out/app-pre-final.log"
    app_down
    wait_until $((expired_deadline + 10))
    app_up
    start_log_follow "$out/app.log"
  else
    stop_log_follow "$out/app-pre.log" "$out/app-pre-final.log"
    start_log_follow "$out/app.log"
  fi

  log "4. 정상·실패 예약 투입(보낼 시각 $(kst "$send_at"))"
  [ "$DRY" = 1 ] || [ "$(date +%s)" -le $((send_at - 120)) ] || die "보낼 시각까지 2분이 안 남았다, 다시 실행한다"
  local main_rows
  main_rows=$( [ "$DRY" = 1 ] && echo 0 || wc -l < "$fdir/main.txt" | tr -d ' ')
  inject_file appearance.events.v1 "$fdir/main.txt" main "$out"
  wait_scheduled "$main_rows"
  [ "$DRY" = 1 ] || [ "$(date +%s)" -lt "$send_at" ] || die "예약 생성이 보낼 시각을 넘었다, 다시 실행한다"

  if [ "$scenario" = F ]; then
    log "5. 실패 전용 회원 복제본 삭제(격리 DB)"
    failing_ids=$( [ "$DRY" = 1 ] && echo 0 || paste -sd, "$fdir/failing-receivers.txt")
    psql_c "delete from notification.notification_user where id in ($failing_ids)"
  fi

  log "6. 관측: 모든 정상·실패 예약이 끝날 때까지"
  port_forward
  [ "$DRY" = 0 ] && metrics > "$out/metrics-before.prom"
  if [ "$DRY" = 0 ]; then
    sampler "$out/samples.jsonl" &
    sampler_pid=$!
    : > "$out/replica-checks.txt"
    : > "$out/status.tsv"
    local expect_runs deadline left
    expect_runs=$(python -I -c "import math,sys; n=$main_rows; f=$( [ "$scenario" = F ] && echo 10 || echo 0 ); print(math.ceil((n-f+3*f)/100))")
    deadline=$((send_at + 60 * (expect_runs + 4)))
    while :; do
      left=$(psql_c "select count(*) from notification.notification_schedule where kind = 'APPEARANCE_REQUEST_EXPIRING' and status = 'SCHEDULED'")
      printf '%s\t%s\n' "$(date +%s)" "$left" >> "$out/status.tsv"
      if [ "$scenario" = F ]; then
        psql_c "select count(*) from notification.notification_user where id in ($failing_ids)" >> "$out/replica-checks.txt"
      fi
      [ "$left" = 0 ] && break
      [ "$(date +%s)" -lt "$deadline" ] || { log "기한까지 끝나지 않았다(남은 예약 $left). 수집·검증으로 넘어간다"; break; }
      sleep 5
    done
    [ "$(topic_end_total member.events.v1)" = "$member_end" ] || die "시험 중 회원 이벤트가 더 들어왔다(복제본 재생성 위험)"
    sleep 5
    metrics > "$out/metrics-after.prom"
    kill "$sampler_pid" 2>/dev/null || true; sampler_pid=
  fi

  log "7. 수집·검증"
  if [ "$DRY" = 0 ]; then
    stop_log_follow "$out/app.log" "$out/app-final.log"
    stop_port_forward
    psql_file "$DIR/queries/l5_schedules.sql" > "$out/db-l5-schedules.tsv"
    psql_file "$DIR/queries/l5_notifications.sql" > "$out/db-l5-notifications.tsv"
    psql_file "$DIR/queries/l5_status.sql" > "$out/db-l5-status.tsv"
    printf '{"scenario":"%s","sendAt":%s,"expiredDeadline":%s,"members":%s,"memberEndOffset":%s}\n' \
      "$scenario" "$send_at" "$( [ "$scenario" = F ] && echo "$expired_deadline" || echo null)" "$members" "$member_end" \
      > "$out/timing.json"
    python -I "$DIR/verify_n15.py" l5 "$fdir/manifest.json" "$out"
  else
    echo "+ python -I $DIR/verify_n15.py l5 $fdir/manifest.json $out" >&2
  fi
}

# ---------------------------------------------------------------- L3

K6_IMAGE=grafana/k6:1.0.0
K6_POD=n15-l3-k6
L3_RECEIVERS=${N15_L3_RECEIVERS:-100}
L3_ALERTS=${N15_L3_ALERTS:-6000}
L3_RATE=${N15_L3_RATE:-20}

run_l3() {
  local round=$1
  guard
  # 회차가 끝나거나 멈추면 k6 pod도 지운다(드라이런은 아무것도 바꾸지 않는다)
  trap 'cleanup; [ "$DRY" = 1 ] || kubectl --context "$CTX" -n "$PLATFORM_NS" delete pod "$K6_POD" --ignore-not-found >/dev/null 2>&1 || true' EXIT
  local out="$RUNS/n15-l3-$(date +%Y%m%d-%H%M%S)-r$round"
  act mkdir -p "$out"
  act chmod 700 "$out" 2>/dev/null || true
  log "L3 회차 $round → $out (회원 $L3_RECEIVERS, 알림 $L3_ALERTS건, 초당 $L3_RATE건)"
  ensure_tools

  log "1. L3 fixture 만들기(Gradle)·k6 이미지 가져오기"
  local fdir="$out/fixture/l3"
  if [ "$DRY" = 1 ]; then
    show env HAPBANG_N15_OUT="$out/fixture" HAPBANG_N15_WHAT=l3 "$ROOT/gradlew" -p "$ROOT" :notification:test --tests '*N15FixtureExportTest' --rerun
  else
    env HAPBANG_N15_OUT="$out/fixture" HAPBANG_N15_WHAT=l3 HAPBANG_L3_RECEIVERS="$L3_RECEIVERS" HAPBANG_L3_ALERTS="$L3_ALERTS" \
      "$ROOT/gradlew" -p "$ROOT" -q :notification:test --tests '*N15FixtureExportTest' --rerun > "$out/export.log" 2>&1 \
      || die "fixture를 만들지 못했다($out/export.log)"
    "$ROOT/gradlew" -p "$ROOT" --stop -q >/dev/null 2>&1 || true
  fi
  # k6 이미지는 노드가 레지스트리에서 버전 고정으로 직접 받는다(IfNotPresent). k3d 가져오기는 Docker Desktop containerd 저장소의
  # 다중 플랫폼 이미지에서 노드 가져오기가 실패해도 성공으로 끝나(L3 1회차) 쓰지 않는다. 실제 받은 이미지 ID는 pod 상태에서 남긴다
  record_conditions "$out" "l3 receivers=$L3_RECEIVERS alerts=$L3_ALERTS rate=$L3_RATE"
  reset_round "$out"
  kafka_i bash -c 'cat > /tmp/b0/pace.sh' < "$DIR/k3d/tools/pace.sh"

  log "2. 회원 투입 → 앱 기동 → 단계 장벽"
  inject_file member.events.v1 "$fdir/members.txt" members "$out"
  app_up
  start_log_follow "$out/app.log"
  wait_barrier "$L3_RECEIVERS"

  log "3. STOMP 연결 $L3_RECEIVERS개(k6, 클러스터 안, 사용자 헤더 직접 + 앱 티켓 발급·검증 그대로, ND-195)"
  local origin hold
  origin=$(awk '/allowed-origins:/ {print $2; exit}' "$ROOT/apps/auxiliary-app/src/main/resources/application-prod.yaml")
  [ -n "$origin" ] || die "운영 설정에서 WebSocket 허용 출처를 찾지 못했다"
  hold=$(( L3_ALERTS / L3_RATE + 180 ))
  # 만든 manifest는 회차 폴더에 남긴다. 드라이런은 회차 폴더를 만들지 않으므로 임시 폴더에 쓴다
  local work=$out
  [ "$DRY" = 1 ] && work=$(native_path "$(mktemp -d)")
  kubectl --context "$CTX" -n "$PLATFORM_NS" create configmap n15-l3-script \
    --from-file=l3.js="$ROOT/loadtest/k6/notification/l3.js" --dry-run=client -o yaml > "$work/k6-configmap.yaml"
  kc apply -f "$work/k6-configmap.yaml"
  cat > "$work/k6-pod.yaml" <<EOF
apiVersion: v1
kind: Pod
metadata:
  name: $K6_POD
  namespace: $PLATFORM_NS
spec:
  restartPolicy: Never
  containers:
    - name: k6
      image: $K6_IMAGE
      imagePullPolicy: IfNotPresent
      args: ["run", "--quiet", "--summary-export", "/tmp/summary.json", "/scripts/l3.js"]
      env:
        - {name: L3_BASE_URL, value: "http://auxiliary-app.$APP_NS.svc.cluster.local:8080"}
        - {name: L3_ORIGIN, value: "$origin"}
        - {name: L3_FIRST_USER, value: "$(head -n 1 "$fdir/receivers.txt" 2>/dev/null || echo 0)"}
        - {name: L3_USERS, value: "$L3_RECEIVERS"}
        - {name: L3_HOLD_SECONDS, value: "$hold"}
      volumeMounts:
        - {name: script, mountPath: /scripts}
  volumes:
    - name: script
      configMap: {name: n15-l3-script}
EOF
  kc -n "$PLATFORM_NS" delete pod "$K6_POD" --ignore-not-found
  kc apply -f "$work/k6-pod.yaml"
  if [ "$DRY" = 0 ]; then
    if ! kubectl --context "$CTX" -n "$PLATFORM_NS" wait --for=condition=Ready "pod/$K6_POD" --timeout=3m >/dev/null; then
      # 종료 trap이 pod를 지우기 전에 원인을 남긴다
      kubectl --context "$CTX" -n "$PLATFORM_NS" describe pod "$K6_POD" > "$out/k6-describe.txt" 2>&1 || true
      kubectl --context "$CTX" -n "$PLATFORM_NS" logs "$K6_POD" > "$out/k6.log" 2>&1 || true
      die "k6 pod가 뜨지 않았다(k6-describe.txt·k6.log)"
    fi
    echo "k6Image=$(kubectl --context "$CTX" -n "$PLATFORM_NS" get pod "$K6_POD" -o jsonpath='{.status.containerStatuses[0].imageID}')" \
      >> "$out/conditions.txt"
    local deadline=$((SECONDS + 180)) conns=0
    while :; do
      conns=$(kubectl --context "$CTX" -n "$PLATFORM_NS" logs "$K6_POD" 2>/dev/null | grep -c 'L3CONN' || true)
      [ "$conns" = "$L3_RECEIVERS" ] && break
      if kubectl --context "$CTX" -n "$PLATFORM_NS" logs "$K6_POD" 2>/dev/null | grep -q 'L3TICKETFAIL'; then
        kubectl --context "$CTX" -n "$PLATFORM_NS" logs "$K6_POD" > "$out/k6.log" 2>&1 || true
        die "연결 티켓 발급이 실패했다(k6.log). 인증을 바꾸지 않고 보고한다(ND-188)"
      fi
      [ "$SECONDS" -lt "$deadline" ] || die "STOMP 연결이 다 열리지 않았다: $conns/$L3_RECEIVERS"
      sleep 2
    done
    log "STOMP 연결 $conns개, 노드: 앱 $(kubectl --context "$CTX" -n "$APP_NS" get pod -l app.kubernetes.io/name=auxiliary-app -o jsonpath='{.items[0].spec.nodeName}'), k6 $(kubectl --context "$CTX" -n "$PLATFORM_NS" get pod "$K6_POD" -o jsonpath='{.spec.nodeName}')"
  fi

  log "4. 지표 스냅샷 ① → 알림 투입(초당 $L3_RATE건, pace.sh)"
  port_forward
  if [ "$DRY" = 0 ]; then
    metrics > "$out/metrics-before.prom"
    sampler "$out/samples.jsonl" &
    sampler_pid=$!
    kafka_i bash -c "echo B0_CLOCK_START \$(date +%s.%N) >&2; bash /tmp/b0/pace.sh $L3_RATE | $(producer_command billing.events.v1); rc=\$?; echo B0_CLOCK_END \$(date +%s.%N) >&2; exit \$rc" \
      < "$fdir/alerts.txt" 2> "$out/producer-alerts.stderr" >/dev/null
    local line
    for line in "${REQUIRED_CONFIG[@]}"; do
      grep -qF "$line" "$out/producer-alerts.stderr" || die "producer 설정이 적용되지 않았다: $line"
    done
    if grep -E " ERROR |Exception" "$out/producer-alerts.stderr"; then die "producer 오류"; fi
  else
    show "pace.sh $L3_RATE | kafka-console-producer billing.events.v1 < $fdir/alerts.txt"
  fi

  log "5. 저장 완료 대기 → 수신 마무리(10초) → 수집"
  if [ "$DRY" = 0 ]; then
    local deadline=$((SECONDS + 300)) saved=0
    while :; do
      saved=$(psql_c "select count(*) from notification.notification where kind = 'CHARGE_COMPLETED'")
      [ "$saved" = "$L3_ALERTS" ] && break
      [ "$SECONDS" -lt "$deadline" ] || die "저장이 끝나지 않았다: $saved/$L3_ALERTS"
      sleep 2
    done
    sleep 10
    metrics > "$out/metrics-after.prom"
    kill "$sampler_pid" 2>/dev/null || true; sampler_pid=
    kubectl --context "$CTX" -n "$PLATFORM_NS" logs "$K6_POD" > "$out/k6.log" 2>&1 || die "k6 로그를 받지 못했다"
    psql_c "select id, extract(epoch from (created_at at time zone 'Asia/Seoul')) from notification.notification where kind = 'CHARGE_COMPLETED' order by id" \
      > "$out/db-l3-rows.tsv"
    stop_log_follow "$out/app.log" "$out/app-final.log"
    stop_port_forward
    printf '{"receivers":%s,"alerts":%s,"rate":%s,"injectStart":%s,"injectEnd":%s,"origin":"%s","jwtVerified":false}\n' \
      "$L3_RECEIVERS" "$L3_ALERTS" "$L3_RATE" "$(awk '$1=="B0_CLOCK_START"{print $2}' "$out/producer-alerts.stderr")" \
      "$(awk '$1=="B0_CLOCK_END"{print $2}' "$out/producer-alerts.stderr")" "$origin" > "$out/timing.json"
    python -I "$DIR/verify_n15.py" l3 "$fdir/receivers.txt" "$out"
  else
    echo "+ python -I $DIR/verify_n15.py l3 $fdir/receivers.txt $out" >&2
  fi
}

main() {
  if [ "${1:-}" = "--dry-run" ]; then DRY=1; shift; fi
  case "${1:-}" in
    l4) [ -n "${2:-}" ] || die "회차 번호가 필요하다"; run_l4 "$2" ;;
    l5) [ -n "${2:-}" ] && [ -n "${3:-}" ] || die "사용: n15.sh l5 <S|B|M|F> <회차>"; run_l5 "$2" "$3" ;;
    l3) [ -n "${2:-}" ] || die "회차 번호가 필요하다"; run_l3 "$2" ;;
    *) die "사용: n15.sh [--dry-run] l4 <회차> | l5 <S|B|M|F> <회차> | l3 <회차>" ;;
  esac
}

# 시험에서 함수만 불러 쓸 수 있게, 직접 실행할 때만 main을 부른다
if [ "${BASH_SOURCE[0]}" = "$0" ]; then
  main "$@"
fi
