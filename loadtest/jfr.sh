#!/usr/bin/env bash
# JVM 앱 pod에 JDK 임시 컨테이너(ephemeral container)를 붙여 JFR을 기록한다 (#690).
# 운영 이미지는 eclipse-temurin:25-jre라 jcmd가 없다. 임시 컨테이너는 앱 컨테이너의 PID namespace를 같이 쓰고,
# 앱과 같은 uid·gid로 돌아야 jcmd가 붙는다(/proc/<pid>/root 접근 검사). 절차와 주의는 docs/load-test/README.md "JFR".
#
# 사용법:
#   loadtest/jfr.sh --context <kubectl context> start <앱>         # 임시 컨테이너를 붙이고 JFR.start
#   loadtest/jfr.sh --context <kubectl context> dump  <앱> [폴더]  # JFR.dump 후 로컬로 복사(기본 폴더: .)
#   loadtest/jfr.sh --context <kubectl context> stop  <앱>         # JFR.stop
#
# 대상: member-app, appearance-app, lecture-app, billing-app, chat-app.
# customer-support-app은 운영에서 Native Image라 이 방법으로 붙지 않는다.
set -euo pipefail

NAMESPACE="hapbang-prod"
CONTEXT=""
JDK_IMAGE="eclipse-temurin:25-jdk"
# 앱 Dockerfile의 useradd --system --uid 10001 app. gid는 이미지(Ubuntu)가 고르는 첫 system gid라 999다
APP_UID=10001
APP_GID="${APP_GID:-999}"
# gid가 다른 임시 컨테이너를 다시 쓰지 않도록 이름에 gid를 넣는다
PREFIX="jfr-g${APP_GID}-"
RECORDING="loadtest"
# 임시 컨테이너는 지울 수 없어 pod가 바뀔 때까지 남는다. sleep이 끝나면 종료 상태로만 남는다
DEBUG_SECONDS=3600
# 앱 컨테이너의 ENTRYPOINT가 java라 같은 PID namespace 안에서 PID 1이다
JAVA_PID="${JAVA_PID:-1}"

usage() {
    sed -n '2,12p' "$0" | sed 's/^# \{0,1\}//'
    exit 1
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --context) CONTEXT="$2"; shift 2 ;;
        -n|--namespace) NAMESPACE="$2"; shift 2 ;;
        -h|--help) usage ;;
        *) break ;;
    esac
done

[[ -n "$CONTEXT" ]] || { echo "--context를 지정한다 (AGENTS.md 8장)." >&2; exit 1; }
[[ $# -ge 2 ]] || usage
ACTION="$1"
APP="$2"
OUT_DIR="${3:-.}"

case "$APP" in
    member-app|appearance-app|lecture-app|billing-app|chat-app) ;;
    customer-support-app) echo "customer-support-app은 Native Image라 지원하지 않는다." >&2; exit 1 ;;
    *) echo "알 수 없는 앱: $APP" >&2; exit 1 ;;
esac

kc() { kubectl --context "$CONTEXT" -n "$NAMESPACE" "$@"; }

pod_name() {
    local pods
    pods=$(kc get pod -l "app.kubernetes.io/name=$APP" --field-selector=status.phase=Running -o name)
    if [[ $(wc -w <<<"$pods") -ne 1 ]]; then
        echo "Running pod가 하나가 아니다: ${pods:-없음}" >&2
        exit 1
    fi
    echo "${pods#pod/}"
}

# 돌고 있는 jfr-g<gid>- 임시 컨테이너 이름. 없으면 빈 문자열
running_debug_container() {
    kc get pod "$1" -o jsonpath='{range .status.ephemeralContainerStatuses[?(@.state.running)]}{.name}{"\n"}{end}' \
        | grep "^${PREFIX}" | sort | tail -1 || true
}

jcmd_in() {
    # jcmd 자체도 JVM이라 node 메모리를 쓴다. 임시 컨테이너에는 limit을 줄 수 없어 heap을 작게 묶는다
    kc exec "$1" -c "$2" -- jcmd -J-Xmx32m "$JAVA_PID" "${@:3}"
}

POD=$(pod_name)

case "$ACTION" in
    start)
        CONTAINER=$(running_debug_container "$POD")
        if [[ -z "$CONTAINER" ]]; then
            CONTAINER="${PREFIX}$(date +%s)"
            CUSTOM=$(mktemp)
            trap 'rm -f "$CUSTOM"' EXIT
            printf '{"securityContext":{"runAsUser":%d,"runAsGroup":%d,"runAsNonRoot":true}}\n' "$APP_UID" "$APP_GID" > "$CUSTOM"
            kc debug "$POD" --image="$JDK_IMAGE" --target=app --container="$CONTAINER" \
                --profile=restricted --custom="$CUSTOM" -- sleep "$DEBUG_SECONDS" >/dev/null
            echo "임시 컨테이너 $CONTAINER 를 붙였다. 실행될 때까지 기다린다(이미지 pull 포함)."
            for _ in $(seq 1 60); do
                [[ "$(running_debug_container "$POD")" == "$CONTAINER" ]] && break
                sleep 5
            done
            [[ "$(running_debug_container "$POD")" == "$CONTAINER" ]] || { echo "임시 컨테이너가 5분 안에 뜨지 않았다." >&2; exit 1; }
        fi
        # /proc/<pid>/status는 누구나 읽는다. gid가 다르면 attach가 Permission denied로 실패한다
        TARGET_GID=$(kc exec "$POD" -c "$CONTAINER" -- awk '/^Gid:/{print $2}' "/proc/${JAVA_PID}/status")
        [[ "$TARGET_GID" == "$APP_GID" ]] || { echo "앱 JVM의 gid가 ${TARGET_GID}다. APP_GID=${TARGET_GID}로 다시 실행한다." >&2; exit 1; }
        jcmd_in "$POD" "$CONTAINER" JFR.start name="$RECORDING" settings=default disk=true maxsize=256m
        echo "pod=$POD container=$CONTAINER 에서 JFR 기록을 시작했다."
        ;;
    dump)
        CONTAINER=$(running_debug_container "$POD")
        [[ -n "$CONTAINER" ]] || { echo "돌고 있는 jfr- 임시 컨테이너가 없다. start부터 한다." >&2; exit 1; }
        FILE="/tmp/${RECORDING}.jfr"
        LOCAL="${OUT_DIR}/${APP}-$(date +%Y%m%d-%H%M%S).jfr"
        mkdir -p "$OUT_DIR"
        jcmd_in "$POD" "$CONTAINER" JFR.dump name="$RECORDING" filename="$FILE"
        # 파일은 앱 컨테이너의 /tmp에 생긴다. 같은 uid인 임시 컨테이너가 /proc/<pid>/root로 읽고 지운다(앱 컨테이너에 exec하지 않음)
        kc exec "$POD" -c "$CONTAINER" -- cat "/proc/${JAVA_PID}/root${FILE}" > "$LOCAL"
        kc exec "$POD" -c "$CONTAINER" -- rm -f "/proc/${JAVA_PID}/root${FILE}"
        echo "$LOCAL ($(du -h "$LOCAL" | cut -f1))"
        ;;
    stop)
        CONTAINER=$(running_debug_container "$POD")
        [[ -n "$CONTAINER" ]] || { echo "돌고 있는 jfr- 임시 컨테이너가 없다." >&2; exit 1; }
        jcmd_in "$POD" "$CONTAINER" JFR.stop name="$RECORDING"
        echo "JFR 기록을 멈췄다. 임시 컨테이너는 ${DEBUG_SECONDS}초 뒤 스스로 끝난다."
        ;;
    *) usage ;;
esac
