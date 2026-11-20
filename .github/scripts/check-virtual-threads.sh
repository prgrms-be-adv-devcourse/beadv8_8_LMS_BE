#!/usr/bin/env bash
# main 코드와 설정이 virtual thread를 만들지 않는지 검사한다(#942, docs/adr/0003-no-virtual-threads-on-jdk25.md).
# JDK 25에서는 virtual thread가 클래스를 처음 읽는 동안 carrier(virtual thread를 실행하는 OS 스레드)에서 내려오지 못한다.
# 클래스 로딩 자원을 쥔 virtual thread가 carrier에서 내려간 사이 다른 virtual thread들이 그 자원을 기다리며 carrier를 모두
# 붙잡으면 앱 전체가 멈춘다. auxiliary-app이 기동 때 이렇게 멈췄다(#942).
# ci.yml changes job이 실행한다. 잡을 것과 넘길 것의 사례는 check-virtual-threads-test.sh에 있다.
#
# 사용법: .github/scripts/check-virtual-threads.sh   (레포 루트에서)
#
# git이 추적하는 파일에서 찾는다. 테스트 코드(src/test), 테스트 전용 라이브러리(common/test-support), 문서는 뺀다.
#   - Java: Thread.ofVirtual(), Thread.startVirtualThread(), Executors.newVirtualThreadPerTaskExecutor(),
#     Spring VirtualThreadTaskExecutor, builder·executor의 virtualThreads(true)·setVirtualThreads(true). 주석 줄은 넘긴다.
#   - 설정: spring.threads.virtual.enabled=true(properties, yaml 평면 키, JVM 인자), yaml 중첩 키, 환경 변수
#     SPRING_THREADS_VIRTUAL_ENABLED=true(k8s env의 name·value 형식 포함). false로 끄는 설정은 넘긴다.
#     Boot가 이 값을 켜면 Tomcat·Kafka 리스너·Boot builder가 모두 virtual thread를 쓴다.
# 종료 코드: 0 통과, 1 virtual thread를 만드는 곳이 있음, 2 검사를 끝내지 못함(git·awk 실패).
# 인자를 변수로 넘기는 virtualThreads(flag)처럼 문자열로 보이지 않는 것은 잡지 못한다. 리뷰에서 본다.
set -euo pipefail

# 아직 고치지 못한 곳. 파일 단위이고, 후속 티켓에서 고치면 줄을 지운다.
ALLOWED=(
  "apps/chat-app/src/main/java/com/hapbang/chat/in/kafka/ChatMemberEventTopicVerifier.java #1028"
  "apps/chat-app/src/main/java/com/hapbang/chat/out/redis/RedisRoomChannelSubscriptions.java #1028"
  "common/shared/src/main/java/com/hapbang/shared/billing/out/BillingApiClient.java #1029"
)

EXCLUDES=(
  ':(exclude,glob)**/src/test/**'
  ':(exclude)common/test-support'
  ':(exclude)docs'
  ':(exclude,glob)**/*.md'
  ':(exclude).github/scripts/check-virtual-threads.sh'
  ':(exclude).github/scripts/check-virtual-threads-test.sh'
)

# git grep -E는 \s·\b를 모른다. POSIX 문자 class로 쓴다.
JAVA_RE='(ofVirtual|startVirtualThread|newVirtualThreadPerTaskExecutor)[[:space:]]*\(|VirtualThreadTaskExecutor|[vV]irtualThreads[[:space:]]*\([[:space:]]*true'
# 값이 true일 때만 본다(false로 끄는 설정은 넘긴다). k8s env의 name·value 두 줄 형식과 yaml 중첩 키는 yaml_hits가 본다
PROPERTY_RE="(spring\.threads\.virtual\.enabled|SPRING_THREADS_VIRTUAL_ENABLED)[[:space:]]*[=:][[:space:]]*[\"']?true"

allowed_issue() {
  local entry
  for entry in "${ALLOWED[@]}"; do
    if [ "${entry% *}" = "$1" ]; then
      echo "${entry##* }"
      return
    fi
  done
}

# 일치가 없으면 git grep은 1로 끝난다. 그 밖의 0이 아닌 값(128 등)은 검사가 돌지 못한 것이라 실패시킨다.
# 실패를 "일치 없음"으로 넘기면 검사가 조용히 빠진 채 통과한다
grep_hits() {
  local rc=0
  git grep -n -I -E "$@" || rc=$?
  if [ "$rc" -gt 1 ]; then
    echo "git grep이 실패했다(종료 코드 ${rc}). 레포 루트에서 실행한다." >&2
    return 1
  fi
}

yaml_hits() {
  # k8s env 목록의 name·value 두 줄 형식(name: SPRING_THREADS_VIRTUAL_ENABLED 다음 줄 value: "true")과
  # yaml 중첩 키(spring: → threads: → virtual: → enabled: true)를 본다. 중첩 키는 들여쓰기로 경로를 따라가고 목록 항목 안은 보지 않는다
  git ls-files -z -- '*.yaml' '*.yml' "${EXCLUDES[@]}" | xargs -0 awk -v q="'" '
    BEGIN {
      env_name_re = "name:[ \t]*[\"" q "]?spring_threads_virtual_enabled[\"" q "]?[ \t]*$"
      env_value_re = "value:[ \t]*[\"" q "]?true[\"" q "]?[ \t]*$"
    }
    FNR == 1 { depth = 0; env_line = 0 }
    tolower($0) ~ env_name_re { env_line = FNR; next }
    env_line && FNR - env_line <= 2 && tolower($0) ~ env_value_re { print FILENAME ":" FNR ":" $0; next }
    /^---/ { depth = 0; next }
    {
      line = $0
      sub(/[ \t]+#.*$/, "", line)
      if (line ~ /^[ \t]*(#|$)/ || line ~ /^[ \t]*-/ || line !~ /^ *[^ \t:]+[ \t]*:/) next
      match(line, /^ */)
      indent = RLENGTH
      key = line
      sub(/^ */, "", key)
      sub(/[ \t]*:.*$/, "", key)
      gsub("[\"" q "]", "", key)
      value = line
      sub(/^[^:]*:/, "", value)
      gsub("[\"" q " \t]", "", value)
      while (depth > 0 && ind[depth] >= indent) depth--
      depth++
      ind[depth] = indent
      k[depth] = key
      path = k[1]
      for (i = 2; i <= depth; i++) path = path "." k[i]
      if (tolower(path) ~ /(^|\.)spring\.threads\.virtual\.enabled$/ && tolower(value) == "true") {
        print FILENAME ":" FNR ":" $0
      }
    }'
  local status=("${PIPESTATUS[@]}")
  if [ "${status[0]}" -ne 0 ] || [ "${status[1]}" -ne 0 ]; then
    echo "yaml 검사가 실패했다(git ls-files ${status[0]}, awk ${status[1]})." >&2
    return 1
  fi
}

hits() {
  grep_hits "$JAVA_RE" -- '*.java' "${EXCLUDES[@]}" || return 1
  grep_hits "$PROPERTY_RE" -- . "${EXCLUDES[@]}" || return 1
  yaml_hits || return 1
}

# 프로세스 치환(< <(...))은 종료 코드를 넘기지 않으므로 결과를 먼저 받아 실패를 확인한다
if ! found=$(hits); then
  echo "virtual thread 검사를 끝내지 못했다." >&2
  exit 2
fi

violations=0
allowed=0
while IFS= read -r hit; do
  [ -n "$hit" ] || continue
  path=${hit%%:*}
  rest=${hit#*:}
  content=${rest#*:}
  trimmed=${content#"${content%%[![:space:]]*}"}
  case $trimmed in
    //* | \** | /\** | \#*) continue ;;
  esac
  issue=$(allowed_issue "$path")
  if [ -n "$issue" ]; then
    echo "예외(${issue}): $hit"
    allowed=$((allowed + 1))
  else
    echo "::error file=${path},line=${rest%%:*}::virtual thread를 만들지 않는다(docs/adr/0003-no-virtual-threads-on-jdk25.md): $trimmed"
    violations=$((violations + 1))
  fi
done < <(printf '%s\n' "$found" | sort -u)

if [ "$violations" -gt 0 ]; then
  echo "virtual thread를 만드는 곳 ${violations}개. platform thread로 바꾼다(docs/adr/0003-no-virtual-threads-on-jdk25.md)." >&2
  exit 1
fi
echo "virtual thread 검사 통과(예외 ${allowed}곳)"
