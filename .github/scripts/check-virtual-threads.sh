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
#     SPRING_THREADS_VIRTUAL_ENABLED. Boot가 이 값을 켜면 Tomcat·Kafka 리스너·Boot builder가 모두 virtual thread를 쓴다.
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
PROPERTY_RE="spring\.threads\.virtual\.enabled[[:space:]]*[=:][[:space:]]*[\"']?true|SPRING_THREADS_VIRTUAL_ENABLED"

allowed_issue() {
  local entry
  for entry in "${ALLOWED[@]}"; do
    if [ "${entry% *}" = "$1" ]; then
      echo "${entry##* }"
      return
    fi
  done
}

hits() {
  git grep -n -I -E "$JAVA_RE" -- '*.java' "${EXCLUDES[@]}" || true
  git grep -n -I -E "$PROPERTY_RE" -- . "${EXCLUDES[@]}" || true
  # yaml 중첩 키(spring: → threads: → virtual: → enabled: true). 들여쓰기로 키 경로를 따라간다. 목록 항목 안은 보지 않는다
  git ls-files -z -- '*.yaml' '*.yml' "${EXCLUDES[@]}" | xargs -0 awk -v q="'" '
    FNR == 1 { depth = 0 }
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
}

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
done < <(hits | sort -u)

if [ "$violations" -gt 0 ]; then
  echo "virtual thread를 만드는 곳 ${violations}개. platform thread로 바꾼다(docs/adr/0003-no-virtual-threads-on-jdk25.md)." >&2
  exit 1
fi
echo "virtual thread 검사 통과(예외 ${allowed}곳)"
