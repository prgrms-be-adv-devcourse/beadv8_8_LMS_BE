#!/usr/bin/env bash
# 알림 B0 대표 기준선(N13, ND-153·ND-155·ND-156). 로컬 k3d 격리 환경 전용이다. 운영(hapbang-k3s)에는 쓰지 않는다.
#
# 사용(레포 루트에서):
#   loadtest/notification/b0.sh [--dry-run] setup      # cluster·인프라·앱 이미지 준비, 앱을 한 번 띄워 Flyway 적용 뒤 0으로 내림
#   loadtest/notification/b0.sh [--dry-run] run <회차>  # 초기화 → 회원 투입 → 기동 → 단계 장벽 → 알림 투입 → 대기 → 수집 → 검증
#
# 입력: 고정 이벤트 폴더(B0_FIXTURE, 기본 ~/.hapbang/load-tests/b0/fixture. B0FixtureExportTest로 만든다)
# 출력: 회차 폴더(B0_RUNS, 기본 ~/.hapbang/load-tests/runs/b0-<시각>-r<회차>). 레포에 커밋하지 않는다
# --dry-run: 바꾸는 명령을 실행하지 않고 출력만 한다. context 검사는 그대로 한다.
set -euo pipefail
# Git Bash가 /tmp 같은 컨테이너 안 경로를 Windows 경로로 바꾸지 않게 한다
export MSYS_NO_PATHCONV=1

# B0_CONTEXT는 안전 장치 시험용이다. k3d-hapbang-b0 말고는 모두 거절한다(guard)
CTX=${B0_CONTEXT:-k3d-hapbang-b0}
CLUSTER=hapbang-b0
# Windows(Git Bash)에서는 k3d·kubectl·docker·python이 Windows 프로그램이라 /d/... 경로를 읽지 못한다. D:/... 형식으로 잡는다
native_path() { (cd "$1" && (pwd -W 2>/dev/null || pwd)); }
ROOT=$(native_path "$(dirname "$0")/../..")
DIR="$ROOT/loadtest/notification"
HOME_DIR=$(native_path "$HOME")
FIXTURE=${B0_FIXTURE:-$HOME_DIR/.hapbang/load-tests/b0/fixture}
RUNS=${B0_RUNS:-$HOME_DIR/.hapbang/load-tests/runs}
APP_NS=hapbang-prod
PLATFORM_NS=hapbang-platform
# Kafka 도구는 broker 컨테이너가 아니라 같은 이미지의 도구 pod에서 돌린다(ND-165, k3d/tools/kafka-tools.yaml)
TOOLS_POD=b0-kafka-tools
# B0 기준선의 Kafka 메모리 한도·요청(ND-178). k3d/platform/kustomization.yaml의 patch 값과 같아야 한다
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
DRY=0

die() { echo "b0: $*" >&2; exit 2; }
log() { echo "[$(date +%H:%M:%S)] $*" >&2; }
now() { date +%s.%3N; }
# 바꾸는 명령. --dry-run이면 출력만 한다
show() { printf '+' >&2; printf ' %q' "$@" >&2; echo >&2; }
act() {
  if [ "$DRY" = 1 ]; then show "$@"; else "$@"; fi
}
kc() { act kubectl --context "$CTX" "$@"; }
# 읽기 전용 kubectl. --dry-run이면 실행하지 않는다
kr() { if [ "$DRY" = 1 ]; then show kubectl --context "$CTX" "$@"; else kubectl --context "$CTX" "$@"; fi; }

# 안전 장치: B0 전용 로컬 cluster만 허용한다
guard() {
  [ "$CTX" = "k3d-hapbang-b0" ] || die "context는 k3d-hapbang-b0만 허용한다: $CTX"
  local current
  current=$(kubectl config current-context 2>/dev/null || true)
  if ! kubectl config get-contexts "$CTX" >/dev/null 2>&1; then
    [ "$1" = setup ] && return 0
    die "context $CTX가 없다. 먼저 setup을 실행한다"
  fi
  local server
  server=$(kubectl config view -o jsonpath="{.clusters[?(@.name=='$CTX')].cluster.server}")
  [[ "$server" =~ ^https://(0\.0\.0\.0|127\.0\.0\.1|localhost|host\.docker\.internal): ]] \
    || die "context $CTX의 API 서버가 로컬이 아니다: $server"
  log "context=$CTX server=$server (현재 기본 context: ${current:-없음}, 이 스크립트는 기본 context를 쓰지 않는다)"
}

# 도구 JVM 힙은 도구 pod의 KAFKA_HEAP_OPTS(k3d/tools/kafka-tools.yaml)를 쓴다
kafka() { kc -n "$PLATFORM_NS" exec "$TOOLS_POD" -- "$@"; }
kafka_i() { kc -n "$PLATFORM_NS" exec -i "$TOOLS_POD" -- "$@"; }
# 도구 pod를 띄우고 준비될 때까지 기다린다(setup·run 모두. 이미 있으면 그대로)
ensure_tools() {
  kc apply -f "$DIR/k3d/tools/kafka-tools.yaml"
  kc -n "$PLATFORM_NS" wait --for=condition=Ready "pod/$TOOLS_POD" --timeout=5m
}
# PostgreSQL은 HelmChart가 만드는 StatefulSet postgres의 pod다(설치 Job pod helm-install-postgres-*와 구분)
POSTGRES_POD=postgres-0
psql_file() {
  kc -n "$PLATFORM_NS" exec -i "$POSTGRES_POD" -- psql -U postgres -d auxiliary -At -F $'\t' -v ON_ERROR_STOP=1 < "$1"
}

producer_command() {
  echo "KAFKA_LOG4J_OPTS='-Dlog4j2.configurationFile=/tmp/b0/producer-log4j2.yaml' $KAFKA_BIN/kafka-console-producer.sh \
--bootstrap-server $BOOTSTRAP --topic $1 --producer.config /tmp/b0/producer.properties \
--reader-property parse.key=true --reader-property parse.headers=true"
}

# console producer 한 토픽 투입. 종료 코드 0만으로 성공이라 하지 않는다: 설정 적용 로그와 오류 줄을 본다(V0-5)
# 셋째 인자 phase(normal·failure)가 있으면 그 단계 파일(<토픽>.<phase>.txt)을 넣는다(ND-166). 빈 파일이면 건너뛴다
inject() {
  local topic=$1 out=$2 phase=${3:-} file name
  if [ -n "$phase" ]; then file="$FIXTURE/$topic.$phase.txt"; name="$topic.$phase"; else file="$FIXTURE/$topic.txt"; name=$topic; fi
  [ -f "$file" ] || die "고정 이벤트 파일이 없다: $file"
  if [ ! -s "$file" ]; then log "투입 $name (0줄, 건너뜀)"; return 0; fi
  log "투입 $name ($(wc -l < "$file")줄)"
  if [ "$DRY" = 1 ]; then
    echo "+ kubectl --context $CTX -n $PLATFORM_NS exec -i $TOOLS_POD -- bash -c \"$(producer_command "$topic")\" < $file" >&2
    return 0
  fi
  # 투입 시작·끝을 도구 pod 시계로 남긴다(ND-169). 정상 단계 소요 시간은 이 시각과 도구 pod polling 시각으로만 잰다
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

# 토픽을 처음부터 다 읽어 topic\tpartition\toffset\theaders\tkey\tvalue로 쓴다(V0)
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

# 알림 토픽 lag 0, DLT 합계가 기대와 같고, 결과·실패 지표가 30초 동안 바뀌지 않을 때까지 기다린다.
# 마지막 변화 시각(지금 - 30초)을 출력한다
wait_consumed() {
  local expected_dlt=$1 deadline=$((SECONDS + 900)) stable_since=$SECONDS last="" total_lag dlt l current topic
  while :; do
    total_lag=0 dlt=0
    for topic in "${ALERT_TOPICS[@]}"; do
      l=$(group_lag "${GROUP_OF[$topic]}"); [ "$l" -ge 0 ] || l=999999
      total_lag=$((total_lag + l))
      dlt=$((dlt + $(topic_end_total "$topic.${GROUP_OF[$topic]}.DLT")))
    done
    current=$(metrics | grep -E '^hapbang_notification_consume_(outcomes|failures)_total' | sort | md5sum)
    [ "$current" = "$last" ] || { last=$current; stable_since=$SECONDS; }
    if [ "$total_lag" = 0 ] && [ "$dlt" = "$expected_dlt" ] && [ $((SECONDS - stable_since)) -ge 30 ]; then
      break
    fi
    [ "$SECONDS" -lt "$deadline" ] || die "소비가 끝나지 않았다: lag=$total_lag dlt=$dlt/$expected_dlt"
    sleep 3
  done
  python -I -c "print($(now) - 30)"
}

# 알림 토픽 성공 처리 건수(consume.latency count) 합. Python을 띄우지 않고 awk로 센다(ND-168)
latency_count() { awk -f "$DIR/queries/alert_latency_count.awk"; }

# 호스트 - 도구 pod 시계 차와 왕복 시간(초). 다섯 번 재어 왕복이 가장 짧은 것을 쓴다(ND-169). 정상 단계 소요 시간에는 쓰지 않고
# 호스트 시계로 찍은 표본(sampler)을 고를 때와 기록용으로만 쓴다
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

# 처리량 변동 진단(N13 진단 회차): 정상 단계 동안만 최소한으로 모은다. 판정에는 쓰지 않는다.
#  - 도구 pod: 1초마다 앱 지표(토픽별 처리 건수·스레드 상태별 개수·Hikari·프로세스 CPU, 이름 필터)와 VM 전체 CPU
#  - PostgreSQL pod: 1초마다 활동·대기·막는 pid·행 잠금이 걸린 회원
#  - 앱: 투입 시작 20·25·30초에 SIGQUIT 스레드 덤프 세 번(매초 하지 않음). 덤프는 앱 표준 출력(로그)에 남는다
#  - 시작 때 cgroup CPU 한도(throttling 가능 여부)를 한 번 남기고, 정상 단계 직후 앱 로그를 보존한다(로그 회전 대비)
diag_pids=""
start_diagnostics() {
  local out=$1 app_ip=$2 app_pod names
  mkdir -p "$out/diag"
  app_pod=$(kubectl --context "$CTX" -n "$APP_NS" get pod -l app.kubernetes.io/name=auxiliary-app -o jsonpath='{.items[0].metadata.name}')
  {
    echo "app $(kubectl --context "$CTX" -n "$APP_NS" exec "$app_pod" -- cat /sys/fs/cgroup/cpu.max)"
    echo "postgres $(kubectl --context "$CTX" -n "$PLATFORM_NS" exec "$POSTGRES_POD" -- cat /sys/fs/cgroup/cpu.max)"
    echo "kafka $(kubectl --context "$CTX" -n "$PLATFORM_NS" exec kafka-0 -- cat /sys/fs/cgroup/cpu.max)"
    echo "node-server $(docker exec "k3d-$CLUSTER-server-0" cat /sys/fs/cgroup/cpu.max)"
    echo "appNode $(kubectl --context "$CTX" -n "$APP_NS" get pod "$app_pod" -o jsonpath='{.spec.nodeName}')"
  } > "$out/diag/limits.txt" 2>&1
  kafka_i bash -c 'mkdir -p /tmp/b0 && cat > /tmp/b0/tools_sampler.sh' < "$DIR/diag/tools_sampler.sh"
  kc -n "$PLATFORM_NS" exec -i "$POSTGRES_POD" -- sh -c 'mkdir -p /tmp/b0 && cat > /tmp/b0/diag_pg.sql' < "$DIR/diag/diag_pg.sql"
  kc -n "$PLATFORM_NS" exec -i "$POSTGRES_POD" -- sh -c 'cat > /tmp/b0/pg_sampler.sh' < "$DIR/diag/pg_sampler.sh"
  names="includedNames=hapbang_notification_consume_latency_seconds&includedNames=jvm_threads_states_threads"
  names="$names&includedNames=hikaricp_connections_active&includedNames=hikaricp_connections_pending&includedNames=process_cpu_usage"
  kubectl --context "$CTX" -n "$PLATFORM_NS" exec "$TOOLS_POD" -- bash /tmp/b0/tools_sampler.sh \
    "http://$app_ip:8082/actuator/prometheus?$names" 300 > "$out/diag/tools.tsv" 2> "$out/diag/tools.stderr" &
  diag_pids="$diag_pids $!"
  kubectl --context "$CTX" -n "$PLATFORM_NS" exec "$POSTGRES_POD" -- sh /tmp/b0/pg_sampler.sh 300 \
    > "$out/diag/pg.tsv" 2> "$out/diag/pg.stderr" &
  diag_pids="$diag_pids $!"
  ( for wait_s in 20 5 5; do
      sleep "$wait_s"
      kubectl --context "$CTX" -n "$APP_NS" exec "$app_pod" -- kill -3 1 >/dev/null 2>&1
      echo "$(now) dump" >> "$out/diag/dumps.txt"
    done ) &
  diag_pids="$diag_pids $!"
}

stop_diagnostics() {
  local out=$1
  kafka sh -c 'touch /tmp/b0/diag.stop' || true
  kc -n "$PLATFORM_NS" exec "$POSTGRES_POD" -- sh -c 'touch /tmp/b0/diag.stop' || true
  wait $diag_pids 2>/dev/null || true
  diag_pids=""
  kubectl --context "$CTX" -n "$APP_NS" logs deploy/auxiliary-app > "$out/diag/app-normal.log" 2>/dev/null || true
}

# 단계 투입 stderr의 시계 표지(B0_CLOCK_START·B0_CLOCK_END) 중 첫 것·마지막 것. 투입 순서는 ALERT_TOPICS 순서다
first_clock_mark() {
  local out=$1 mark=$2 phase=$3 topic f
  for topic in "${ALERT_TOPICS[@]}"; do
    f="$out/producer-$topic.$phase.stderr"
    [ -f "$f" ] && { awk -v m="$mark" '$1 == m {print $2; exit}' "$f"; return 0; }
  done
}
last_clock_mark() {
  local out=$1 mark=$2 phase=$3 topic f value=""
  for topic in "${ALERT_TOPICS[@]}"; do
    f="$out/producer-$topic.$phase.stderr"
    [ -f "$f" ] && value=$(awk -v m="$mark" '$1 == m {print $2}' "$f" | tail -n 1)
  done
  echo "$value"
}

sampler() {
  local out=$1
  while :; do
    local m top cpu mem
    m=$(metrics || true)
    top=$(kubectl --context "$CTX" -n "$APP_NS" top pod -l app.kubernetes.io/name=auxiliary-app --no-headers 2>/dev/null || true)
    cpu=$(awk '{gsub("m","",$2); print $2+0}' <<<"$top")
    mem=$(awk '{gsub("Mi","",$3); print $3+0}' <<<"$top")
    printf '{"t":%s,"hikariActive":%s,"hikariPending":%s,"cpuMillicores":%s,"memoryMiB":%s}\n' "$(date +%s)" \
      "$(awk '/^hikaricp_connections_active/ {s+=$2} END {print s+0}' <<<"$m")" \
      "$(awk '/^hikaricp_connections_pending/ {s+=$2} END {print s+0}' <<<"$m")" "${cpu:-0}" "${mem:-0}" >> "$out"
    sleep 5
  done
}

setup() {
  guard setup
  if ! k3d cluster list 2>/dev/null | awk 'NR > 1 {print $1}' | grep -qx "$CLUSTER"; then
    act k3d cluster create --config "$DIR/k3d/cluster.yaml"
  fi
  # --dry-run이면 cluster가 실제로 생기지 않았으므로 나머지는 명령만 출력한다
  if [ "$DRY" = 0 ] || kubectl config get-contexts "$CTX" >/dev/null 2>&1; then guard run; fi
  kc apply -f "$ROOT/k8s/cluster/namespaces.yaml" -f "$ROOT/k8s/cluster/storageclass-local-path-retain.yaml"
  if [ "$DRY" = 1 ]; then
    echo "+ kubectl kustomize --load-restrictor LoadRestrictionsNone $DIR/k3d/platform | kubectl --context $CTX apply -f -" >&2
  else
    kubectl kustomize --load-restrictor LoadRestrictionsNone "$DIR/k3d/platform" | kubectl --context "$CTX" apply -f -
  fi
  kc -n "$PLATFORM_NS" rollout status statefulset/kafka --timeout=10m
  kc -n "$PLATFORM_NS" rollout status statefulset/redis --timeout=10m
  act bash -c "until kubectl --context $CTX -n $PLATFORM_NS get statefulset/postgres >/dev/null 2>&1; do sleep 5; done"
  kc -n "$PLATFORM_NS" rollout status statefulset/postgres --timeout=10m

  ensure_tools
  log "앱 이미지 빌드·가져오기"
  act "$ROOT/gradlew" -p "$ROOT" :auxiliary-app:bootJar
  act docker build -t hapbang/auxiliary-app:local -f "$ROOT/apps/auxiliary-app/Dockerfile" "$ROOT/apps/auxiliary-app/build/libs"
  act k3d image import -c "$CLUSTER" hapbang/auxiliary-app:local

  # 운영 프로필이 ES CA 파일을 요구한다. 격리 환경에는 ES가 없으므로 이 cluster에서만 쓰는 임시 자체 서명 인증서를 만든다
  local ca; ca=$(native_path "$(mktemp -d)")
  act openssl req -x509 -newkey rsa:2048 -nodes -days 30 -subj "/CN=b0-local-es-ca" -keyout "$ca/ca.key" -out "$ca/ca.crt"
  if [ "$DRY" = 1 ]; then
    echo "+ kubectl create configmap elasticsearch-ca --from-file=ca.crt | apply" >&2
  else
    kubectl --context "$CTX" -n "$APP_NS" create configmap elasticsearch-ca --from-file=ca.crt="$ca/ca.crt" \
      --dry-run=client -o yaml | kubectl --context "$CTX" apply -f -
  fi
  rm -rf "$ca"

  if [ "$DRY" = 1 ]; then
    echo "+ kubectl kustomize --load-restrictor LoadRestrictionsNone $DIR/k3d/auxiliary | kubectl --context $CTX apply -f -" >&2
  else
    kubectl kustomize --load-restrictor LoadRestrictionsNone "$DIR/k3d/auxiliary" | kubectl --context "$CTX" apply -f -
  fi
  kc -n "$APP_NS" rollout status deploy/auxiliary-app --timeout=10m
  kc -n "$APP_NS" scale deploy/auxiliary-app --replicas=0
  log "setup 완료. 앱은 replicas 0이다"
}

# 회차 중 띄운 백그라운드 작업(앱 로그 따라 받기, port-forward, 자원 표본, 완료 polling, 진단)을 회차가 끝나거나 멈출 때 모두 끝낸다
cleanup() {
  local pid
  for pid in ${logs_pid:-} ${poller_pid:-} ${sampler_pid:-} ${pf_pid:-} ${diag_pids:-}; do
    kill "$pid" 2>/dev/null
  done
  true
}

# 앱 로그 따라 받기를 멈추고 끊기지 않았는지 확인한다(ND-185). 비교용 컨테이너 로그(app-final.log)를 먼저 받고, 따라 받는 kubectl이 그
# 줄까지 쓸 시간을 기다린 뒤 멈춘다. 스냅샷의 마지막 줄이 app.log에 없으면(pod 재시작·연결 끊김으로 따라 받기가 먼저 끝남) 회차를 멈춘다.
# 멈춘 뒤에 스냅샷을 받으면 그사이 앱이 쓴 줄이 app.log에 없어 거짓 실패가 난다(PR 리뷰). 스냅샷을 못 받거나 비어도 통과로 보지 않는다
LOG_FLUSH_SECONDS=2 # 따라 받는 kubectl이 스냅샷 마지막 줄을 파일에 쓸 시간
stop_log_follow() {
  local out=$1 last
  if [ "$DRY" = 1 ]; then
    echo "+ kubectl --context $CTX -n $APP_NS logs deploy/auxiliary-app > $out/app-final.log" >&2
    echo "+ sleep $LOG_FLUSH_SECONDS" >&2
    echo "+ kill -0 <앱 로그 따라 받기>(이미 끝났으면 실패)" >&2
    echo "+ kill <앱 로그 따라 받기>" >&2
    echo "+ 앱 로그 따라 받기 끊김 확인(app-final.log 마지막 줄이 app.log에 있는지)" >&2
    return
  fi
  kubectl --context "$CTX" -n "$APP_NS" logs deploy/auxiliary-app > "$out/app-final.log" \
    || die "비교용 app-final.log를 받지 못했다(kubectl logs 실패). 따라 받기 끊김을 확인할 수 없다"
  sleep "$LOG_FLUSH_SECONDS"
  # 따라 받는 kubectl은 pod 종료·연결 끊김일 때만 스스로 끝난다. 그 뒤 앱이 쓴 줄이 없어 비교가 맞더라도 정상으로 보지 않는다
  if ! kill -0 "$logs_pid" 2>/dev/null; then
    logs_pid=
    die "앱 로그 따라 받기가 kill 전에 이미 끝나 있었다(pod 재시작·연결 끊김, app-log.stderr 확인)"
  fi
  kill "$logs_pid" 2>/dev/null || true
  wait "$logs_pid" 2>/dev/null || true
  logs_pid=
  last=$(grep -v '^[[:space:]]*$' "$out/app-final.log" | tail -n 1 || true)
  [ -n "$last" ] || die "비교용 app-final.log를 받지 못했다(빈 로그). 따라 받기 끊김을 확인할 수 없다"
  grep -qxF -- "$last" "$out/app.log" \
    || die "앱 로그 따라 받기가 끊겼다(app-final.log 마지막 줄이 app.log에 없다. 수집 지연이 LOG_FLUSH_SECONDS보다 길었거나 줄이 빠졌다, app-log.stderr 확인)"
}

run_once() {
  local round=$1
  guard run
  trap cleanup EXIT
  [ -f "$FIXTURE/manifest.json" ] || die "manifest가 없다: $FIXTURE/manifest.json"
  local out="$RUNS/b0-$(date +%Y%m%d-%H%M%S)-r$round"
  act mkdir -p "$out"
  # 원본(로그·지표·덤프)에는 레코드 본문이 있으므로 나만 읽게 둔다. Windows 파일 시스템은 권한을 지원하지 않아 실패를 무시한다
  act chmod 700 "$out" 2>/dev/null || true
  log "회차 $round → $out"
  ensure_tools
  if [ "$DRY" = 0 ]; then
    {
      echo "git=$(git -C "$ROOT" rev-parse HEAD)"
      echo "fixture=$(cat "$FIXTURE/content.sha256")"
      echo "producerVersion=$(kubectl --context "$CTX" -n "$PLATFORM_NS" exec "$TOOLS_POD" -- "$KAFKA_BIN/kafka-console-producer.sh" --version 2>/dev/null | head -1)"
      echo "image=$(docker image inspect hapbang/auxiliary-app:local --format '{{.Id}}')"
    } > "$out/conditions.txt"
    # B0 기준선과 같은 Kafka 메모리 한도인지 본다(ND-178). 운영 manifest가 바뀌어도 B0 구성(k3d/platform)이 700Mi로 고정한다
    # StatefulSet 정의가 아니라 실제로 떠 있는 pod의 kafka 컨테이너(이름으로 고름) 한도·요청을 본다
    local kafka_memory expected_kafka_memory="$B0_KAFKA_MEMORY/$B0_KAFKA_MEMORY"
    kafka_memory=$(kubectl --context "$CTX" -n "$PLATFORM_NS" get pod kafka-0 \
      -o jsonpath='{.spec.containers[?(@.name=="kafka")].resources.limits.memory}/{.spec.containers[?(@.name=="kafka")].resources.requests.memory}')
    echo "kafkaMemoryLimitRequest=$kafka_memory" >> "$out/conditions.txt"
    [ "$kafka_memory" = "$expected_kafka_memory" ] || die "Kafka 메모리 한도/요청이 B0 기준선($B0_KAFKA_MEMORY)과 다르다: $kafka_memory (b0.sh setup으로 B0 구성을 다시 적용하고 kafka-0을 다시 띄운다)"
  fi

  log "1. 앱 내림"
  kc -n "$APP_NS" scale deploy/auxiliary-app --replicas=0
  kc -n "$APP_NS" wait --for=delete pod -l app.kubernetes.io/name=auxiliary-app --timeout=5m || true

  log "2. 알림 데이터 비우기(runtime_setting·Flyway 이력 보존)"
  psql_file "$DIR/queries/reset.sql"

  # B0 커밋 설정(ND-170): 앱을 띄우기 전에 걸고, 새 연결에서 off인지와 역할별 덮어쓰기가 없는지 확인해 남긴다
  log "2-1. B0 커밋 설정(auxiliary database synchronous_commit=off)"
  if [ "$DRY" = 0 ]; then
    local commit_setting session_commit
    # database 설정(모든 역할)만 있어야 한다. 앱 역할(auxiliary)에 걸린 설정이 있으면 그쪽이 이겨 앱 연결 값이 달라질 수 있다
    commit_setting=$(psql_file "$DIR/queries/commit_setting.sql" | grep -v '^ALTER DATABASE$' | tr '\n' ' ' | sed 's/ *$//')
    session_commit=$(kc -n "$PLATFORM_NS" exec "$POSTGRES_POD" -- psql -U postgres -d auxiliary -At -c "show synchronous_commit")
    [ "$session_commit" = off ] || die "새 연결의 synchronous_commit이 off가 아니다: $session_commit"
    [ "$commit_setting" = "(모든 역할)@auxiliary=synchronous_commit=off" ] \
      || die "auxiliary database·앱 역할 설정이 기대와 다르다(역할별 덮어쓰기 확인): $commit_setting"
    printf 'synchronousCommit=%s\ndbRoleSettings=%s\n' "$session_commit" "$commit_setting" >> "$out/conditions.txt"
  else
    psql_file "$DIR/queries/commit_setting.sql"
  fi

  log "3. Kafka 초기화(토픽·DLT·group)"
  local topic group
  for topic in "${ALL_TOPICS[@]}"; do
    group=${GROUP_OF[$topic]}
    kafka "$KAFKA_BIN/kafka-consumer-groups.sh" --bootstrap-server "$BOOTSTRAP" --delete --group "$group" || true
    kafka "$KAFKA_BIN/kafka-topics.sh" --bootstrap-server "$BOOTSTRAP" --delete --if-exists --topic "$topic" || true
    kafka "$KAFKA_BIN/kafka-topics.sh" --bootstrap-server "$BOOTSTRAP" --delete --if-exists \
      --topic "$topic.$group.DLT" || true
  done
  # 지운 토픽 이름만 정확히 본다. 같은 앱의 챗봇·검색 DLT(…events.v1.….DLT)는 지우지 않으므로 넓은 패턴으로 기다리면 끝나지 않는다
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
    kafka "$KAFKA_BIN/kafka-topics.sh" --bootstrap-server "$BOOTSTRAP" --create --topic "$topic" --partitions 3 \
      --replication-factor 1
  done
  kafka_i bash -c 'mkdir -p /tmp/b0 && cat > /tmp/b0/producer.properties' < "$DIR/producer.properties"
  kafka_i bash -c 'cat > /tmp/b0/producer-log4j2.yaml' < "$DIR/producer-log4j2.yaml"
  kafka_i bash -c 'cat > /tmp/b0/alert_latency_count.awk' < "$DIR/queries/alert_latency_count.awk"
  kafka_i bash -c 'cat > /tmp/b0/poll_latency_count.sh' < "$DIR/k3d/tools/poll_latency_count.sh"

  log "4. 회원 투입(앱이 꺼진 동안)"
  inject member.events.v1 "$out"

  log "5. 앱 기동"
  kc -n "$APP_NS" scale deploy/auxiliary-app --replicas=1
  kc -n "$APP_NS" rollout status deploy/auxiliary-app --timeout=10m
  # 컨테이너 로그는 kubelet이 크기로 회전한다. 수집 때 kubectl logs로 한 번에 받으면 회전된 앞부분이 빠져 V8이 회차 전체를 보지 못한다
  # (N14 4회차). 기동 직후부터 따라 받아 회차 전체를 남긴다(ND-185). 측정 경로(투입·소비)는 바꾸지 않는다
  if [ "$DRY" = 1 ]; then
    show kubectl --context "$CTX" -n "$APP_NS" logs -f deploy/auxiliary-app
  else
    kubectl --context "$CTX" -n "$APP_NS" logs -f deploy/auxiliary-app > "$out/app.log" 2> "$out/app-log.stderr" &
    logs_pid=$!
  fi

  log "6. 단계 장벽: 복제본 수와 회원 group lag"
  local replicas=0 lag=-1 deadline=$((SECONDS + 300))
  if [ "$DRY" = 0 ]; then
    while :; do
      replicas=$(psql_file "$DIR/queries/replicas.sql")
      lag=$(group_lag "${GROUP_OF[member.events.v1]}")
      [ "$replicas" = 1000 ] && [ "$lag" = 0 ] && break
      [ "$SECONDS" -lt "$deadline" ] || die "단계 장벽을 넘지 못했다: replicas=$replicas lag=$lag"
      sleep 3
    done
  fi
  local barrier_at; barrier_at=$(now)

  log "7. 지표 연결·스냅샷 ①"
  local pf_pid="" sampler_pid=""
  if [ "$DRY" = 0 ]; then
    kubectl --context "$CTX" -n "$APP_NS" port-forward deploy/auxiliary-app "$PF_PORT:8082" >/dev/null 2>&1 &
    pf_pid=$!
    until metrics >/dev/null; do sleep 1; done
    metrics > "$out/metrics-before.prom"
    sampler "$out/samples.jsonl" &
    sampler_pid=$!
  fi

  # 두 단계 투입(ND-166): 정상 단계로 성능 기준선을 재고(스냅샷 ①~②), 실패 단계(복제본 없음·깨진 본문)로
  # DLT·재시도를 본다(②~③). 재시도 대기가 정상 단계 지표를 덮지 않게 정상 단계 소비가 끝난 뒤에 넣는다
  # 정상 단계 완료(ND-167·ND-168·ND-169): 도구 pod 안에서 앱 pod 지표를 이름 필터로 좁혀 0.5초마다 읽고, 알림 토픽 5개의
  # 성공 처리 건수가 투입 직전(스냅샷 ①) 값보다 정상 레코드 수만큼 는 첫 관측 시각(도구 pod 시계)을 완료로 본다.
  # 도구 pod에서 앱 pod IP:8082로 바로 접속한다(B0는 NetworkPolicy를 빼서 가능, k3d/auxiliary)
  local expected_normal base poller_pid="" app_ip clock_offset clock_rtt
  expected_normal=$(python -I -c "import json,sys; print(sum(json.load(open(sys.argv[1], encoding='utf-8'))['phaseRecordsByTopic']['normal'].values()))" "$FIXTURE/manifest.json")
  if [ "$DRY" = 0 ]; then
    base=$(latency_count < "$out/metrics-before.prom")
    app_ip=$(kubectl --context "$CTX" -n "$APP_NS" get pod -l app.kubernetes.io/name=auxiliary-app \
      -o jsonpath='{.items[0].status.podIP}')
    [ -n "$app_ip" ] || die "앱 pod IP를 찾지 못했다"
    read -r clock_offset clock_rtt < <(measure_clock_offset)
    log "호스트 - 도구 pod 시계 차 ${clock_offset}초(왕복 ${clock_rtt}초)"
    : > "$out/progress.tsv"
    kubectl --context "$CTX" -n "$PLATFORM_NS" exec "$TOOLS_POD" -- env \
      "B0_FETCH=wget -qO- http://$app_ip:8082/actuator/prometheus?includedNames=hapbang_notification_consume_latency_seconds" \
      B0_AWK=/tmp/b0/alert_latency_count.awk B0_INTERVAL=0.5 B0_EXTRA_POLLS=10 B0_DEADLINE_SECONDS=900 \
      bash /tmp/b0/poll_latency_count.sh "$base" "$expected_normal" > "$out/progress.tsv" 2> "$out/progress.stderr" &
    poller_pid=$!
    # 진단 수집은 B0_DIAG=1일 때만 켠다(진단 회차). 공식 회차는 끈다(스레드 덤프 등이 측정을 흔들 수 있다)
    [ "${B0_DIAG:-0}" = 1 ] && start_diagnostics "$out" "$app_ip"
  fi

  log "8. 정상 단계 투입($expected_normal건)"
  local inject_start; inject_start=$(now)
  for topic in "${ALERT_TOPICS[@]}"; do
    inject "$topic" "$out" normal
  done
  local normal_inject_end; normal_inject_end=$(now)

  log "9. 정상 단계 완료 대기(성공 처리 건수 증가량 = $expected_normal, 도구 pod 0.5초 polling) → 스냅샷 ②"
  local normal_completed_at=0 completed_at=0 expected_dlt cluster_start=0 cluster_end=0 cluster_done=0
  if [ "$DRY" = 0 ]; then
    # 도구 pod polling은 닿은 뒤 10번 더 보고 끝난다. 끝까지 기다린 뒤 판정한다(넘거나 닿지 않으면 완료로 처리하지 않는다)
    local poll_rc=0
    wait "$poller_pid" || poll_rc=$?
    poller_pid=""
    normal_completed_at=$(now)
    [ "$poll_rc" = 0 ] || die "정상 단계 성공 처리 건수가 기대에 닿지 않았다(polling 종료 코드 $poll_rc, 마지막 $(tail -n 1 "$out/progress.tsv" | cut -f2)/$expected_normal, 원인 분석 필요)"
    awk -F'\t' -v e="$expected_normal" '$2 > e {exit 1}' "$out/progress.tsv" \
      || die "정상 단계 성공 처리 건수가 기대를 넘었다(원인 분석 필요)"
    [ "$(tail -n 1 "$out/progress.tsv" | cut -f2)" = "$expected_normal" ] \
      || die "정상 단계 성공 처리 건수가 닿은 뒤 바뀌었다(원인 분석 필요)"
    cluster_done=$(awk -F'\t' -v e="$expected_normal" '$2 == e {print $1; exit}' "$out/progress.tsv")
    cluster_start=$(first_clock_mark "$out" B0_CLOCK_START normal)
    cluster_end=$(last_clock_mark "$out" B0_CLOCK_END normal)
    local normal_dlt=0
    for topic in "${ALERT_TOPICS[@]}"; do
      normal_dlt=$((normal_dlt + $(topic_end_total "$topic.${GROUP_OF[$topic]}.DLT")))
    done
    [ "$normal_dlt" = 0 ] || die "정상 단계에 DLT가 생겼다: $normal_dlt"
    metrics > "$out/metrics-normal.prom"
    [ "${B0_DIAG:-0}" = 1 ] && stop_diagnostics "$out"
    log "정상 단계 완료 $(python -I -c "print(round($cluster_done - $cluster_start, 3))")초(투입 시작부터, 도구 pod 시계)"
  fi

  log "10. 실패 단계 투입"
  local failure_inject_start; failure_inject_start=$(now)
  for topic in "${ALERT_TOPICS[@]}"; do
    inject "$topic" "$out" failure
  done
  local failure_inject_end; failure_inject_end=$(now)

  log "11. 실패 단계 소비 완료 대기(lag 0, DLT 기대 건수, 결과 지표 30초 변화 없음) → 스냅샷 ③"
  expected_dlt=$(python -I -c "import json,sys; print(sum(json.load(open(sys.argv[1], encoding='utf-8'))['deadLetters'].values()))" "$FIXTURE/manifest.json")
  if [ "$DRY" = 0 ]; then
    completed_at=$(wait_consumed "$expected_dlt") || exit 2
  fi

  log "12. 수집"
  [ "$DRY" = 0 ] && { metrics > "$out/metrics-after.prom"; kill "$sampler_pid" 2>/dev/null || true; }
  stop_log_follow "$out"
  if [ "$DRY" = 0 ]; then
    : > "$out/topics.tsv"
    for topic in "${ALL_TOPICS[@]}"; do dump_topic "$topic" >> "$out/topics.tsv"; done
    : > "$out/dlt.tsv"
    for topic in "${ALERT_TOPICS[@]}"; do dump_topic "$topic.${GROUP_OF[$topic]}.DLT" >> "$out/dlt.tsv"; done
    psql_file "$DIR/queries/total.sql" > "$out/db-total.txt"
    psql_file "$DIR/queries/rows.sql" > "$out/db-rows.tsv"
    psql_file "$DIR/queries/dup.sql" > "$out/db-dup.tsv"
    psql_file "$DIR/queries/schedules.sql" > "$out/db-schedules.tsv"
    psql_file "$DIR/queries/fingerprint.sql" > "$out/db-fingerprint.tsv"
    printf '{"replicas":%s,"memberLag":%s,"barrierAt":%s,"alertInjectStartAt":%s,"normalInjectEndAt":%s,"normalCompletedAt":%s,"failureInjectStartAt":%s,"failureInjectEndAt":%s,"completedAt":%s,"clusterClock":{"normalInjectStartAt":%s,"normalInjectEndAt":%s,"normalCompletedAt":%s,"hostMinusClusterSeconds":%s,"clockRttSeconds":%s}}\n' \
      "$replicas" "$lag" "$barrier_at" "$inject_start" "$normal_inject_end" "$normal_completed_at" \
      "$failure_inject_start" "$failure_inject_end" "$completed_at" \
      "$cluster_start" "$cluster_end" "$cluster_done" "${clock_offset:-0}" "${clock_rtt:-0}" > "$out/timing.json"
  fi

  log "13. 검증"
  if [ "$DRY" = 0 ]; then
    # 진단 분석은 판정에 쓰지 않는다. 실패해도 회차 검증은 계속한다
    if [ "${B0_DIAG:-0}" = 1 ]; then
      python -I "$DIR/diag_b0.py" "$out" > /dev/null 2> "$out/diag/analyze.stderr" || log "진단 분석 실패(diag/analyze.stderr)"
    fi
    python -I "$DIR/verify_b0.py" "$FIXTURE/manifest.json" "$out"
  else
    echo "+ python -I $DIR/verify_b0.py $FIXTURE/manifest.json $out" >&2
  fi
}

main() {
  if [ "${1:-}" = "--dry-run" ]; then DRY=1; shift; fi
  case "${1:-}" in
    setup) setup ;;
    run) [ -n "${2:-}" ] || die "회차 번호가 필요하다"; run_once "$2" ;;
    *) die "사용: b0.sh [--dry-run] setup | run <회차>" ;;
  esac
}

main "$@"
