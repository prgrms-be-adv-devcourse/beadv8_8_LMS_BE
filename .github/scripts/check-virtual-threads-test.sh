#!/usr/bin/env bash
# check-virtual-threads.sh가 잡을 것과 넘길 것을 임시 git 저장소에서 확인한다(#942). ci.yml changes job이 실행한다.
# 검사가 조용히 빠지면 virtual thread가 다시 들어와도 모르므로, 패턴을 바꾸면 여기에 사례를 더한다.
# 사용법: .github/scripts/check-virtual-threads-test.sh
set -euo pipefail

SCRIPT="$(cd "$(dirname "$0")" && pwd)/check-virtual-threads.sh"
REPO=$(mktemp -d)
trap 'rm -rf "$REPO"' EXIT

export GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_NOSYSTEM=1
g() { git -C "$REPO" "$@"; }
g init -q

FAILED=0

# 파일 하나만 둔 저장소에서 검사를 돌려 기대한 결과(pass·fail)인지 본다.
expect() {
  local want=$1 path=$2 content=$3 name=$4 got
  g rm -rq --cached . >/dev/null 2>&1 || true
  find "$REPO" -mindepth 1 -maxdepth 1 ! -name .git -exec rm -rf {} +
  mkdir -p "$REPO/$(dirname "$path")"
  printf '%s\n' "$content" > "$REPO/$path"
  g add -A
  if (cd "$REPO" && "$SCRIPT" >/dev/null 2>&1); then got=pass; else got=fail; fi
  if [ "$got" = "$want" ]; then
    echo "ok   $name"
  else
    echo "FAIL $name: 기대 $want, 결과 $got"
    FAILED=1
  fi
}

MAIN=apps/x-app/src/main/java/X.java

expect fail "$MAIN" 'Thread t = Thread.ofVirtual().name("a").start(r);' "Thread.ofVirtual()"
expect fail "$MAIN" 'Thread.startVirtualThread(r);' "Thread.startVirtualThread()"
expect fail "$MAIN" 'var e = Executors.newVirtualThreadPerTaskExecutor();' "Executors.newVirtualThreadPerTaskExecutor()"
expect fail "$MAIN" 'builder.virtualThreads( true )' "Boot builder virtualThreads(true)"
expect fail "$MAIN" 'executor.setVirtualThreads(true);' "SimpleAsyncTaskExecutor setVirtualThreads(true)"
expect fail "$MAIN" 'return new VirtualThreadTaskExecutor("a-");' "Spring VirtualThreadTaskExecutor"
expect fail common/shared/src/main/java/S.java 'Thread.ofVirtual().start(r);' "common 라이브러리"
expect pass "$MAIN" 'builder.virtualThreads(false)' "virtualThreads(false)"
expect pass "$MAIN" 'Thread t = Thread.ofPlatform().daemon(true).start(r);' "platform thread"
expect pass "$MAIN" '    // Thread.ofVirtual()은 쓰지 않는다' "한 줄 주석"
expect pass "$MAIN" '     * {@code Thread.ofVirtual()}를 쓰지 않는다' "Javadoc 줄"
expect pass apps/x-app/src/test/java/XTest.java 'Thread.ofVirtual().start(r);' "테스트 코드"
expect pass common/test-support/src/main/java/T.java 'Executors.newVirtualThreadPerTaskExecutor();' "테스트 전용 라이브러리"
expect pass docs/x.md 'Thread.ofVirtual()' "문서"
expect pass apps/chat-app/src/main/java/com/hapbang/chat/in/kafka/ChatMemberEventTopicVerifier.java \
  'Thread.ofVirtual().start(r);' "예외 목록(#1028)"

YAML=apps/x-app/src/main/resources/application.yaml
expect fail "$YAML" $'spring:\n  threads:\n    virtual:\n      enabled: true' "yaml 중첩 키"
expect fail "$YAML" $'spring:\n  threads.virtual:\n    enabled: "true"' "yaml 일부 평면 키, 따옴표 값"
expect fail "$YAML" $'spring.threads.virtual.enabled: true' "yaml 평면 키"
expect fail "$YAML" $'server:\n  port: 8080\n---\nspring:\n  threads:\n    virtual:\n      enabled: true' "yaml 둘째 문서"
expect pass "$YAML" $'spring:\n  threads:\n    virtual:\n      enabled: false' "yaml false"
expect pass "$YAML" $'spring:\n  threads:\n    other: true\n  virtual:\n    enabled: true' "다른 경로의 virtual.enabled"
expect pass "$YAML" $'spring:\n  threads:\n    # virtual:\n    #   enabled: true\n    max: 1' "yaml 주석"
expect fail apps/x-app/src/main/resources/application.properties 'spring.threads.virtual.enabled = true' "properties"
expect fail apps/x-app/k8s/base/kustomization.yaml $'configMapGenerator:\n  - literals:\n      - SPRING_THREADS_VIRTUAL_ENABLED=true' "환경 변수"
expect fail apps/x-app/k8s/base/kustomization.yaml $'      - JAVA_TOOL_OPTIONS=-Dspring.threads.virtual.enabled=true' "JVM 인자"
expect pass apps/x-app/k8s/base/kustomization.yaml $'configMapGenerator:\n  - literals:\n      - SPRING_THREADS_VIRTUAL_ENABLED=false' "환경 변수 false"
expect fail apps/x-app/k8s/base/deployment.yaml $'        env:\n          - name: SPRING_THREADS_VIRTUAL_ENABLED\n            value: "true"' "k8s env name·value"
expect pass apps/x-app/k8s/base/deployment.yaml $'        env:\n          - name: SPRING_THREADS_VIRTUAL_ENABLED\n            value: "false"' "k8s env name·value false"
expect pass apps/x-app/k8s/base/deployment.yaml $'        env:\n          - name: OTHER\n            value: "true"' "다른 환경 변수 true"

# git 저장소 밖에서는 검사를 끝내지 못하므로 통과가 아니라 실패(종료 코드 2)여야 한다
OUTSIDE=$(mktemp -d)
rc=0
(cd "$OUTSIDE" && GIT_CEILING_DIRECTORIES="$OUTSIDE" "$SCRIPT" >/dev/null 2>&1) || rc=$?
rm -rf "$OUTSIDE"
if [ "$rc" -eq 2 ]; then
  echo "ok   git 실패는 통과로 넘기지 않는다"
else
  echo "FAIL git 실패는 통과로 넘기지 않는다: 기대 종료 코드 2, 결과 $rc"
  FAILED=1
fi

exit "$FAILED"
