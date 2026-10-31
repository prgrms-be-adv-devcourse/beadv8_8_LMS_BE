#!/usr/bin/env bash
# GitHub Actions에서 large로 스크립트를 보내 root로 실행하고 결과를 출력한다(SSM Run Command, #374).
# 사용법: ssm-run.sh <스크립트 파일> [인자...]
#   환경 변수 INSTANCE_ID와 AWS 자격 증명(configure-aws-credentials)이 필요하다.
#   스크립트의 종료 코드가 0이 아니면 실패로 끝난다.
# AWS-RunShellScript는 명령을 sh로 실행하므로, 스크립트를 base64로 넘겨 서버에서 bash로 푼다.
# 인자는 그대로 명령에 붙으므로 호출하는 쪽이 형식을 검사한 값(앱 이름, 40자 sha)만 넘긴다.
set -euo pipefail

script=${1:?스크립트 파일이 필요합니다}
shift
: "${INSTANCE_ID:?INSTANCE_ID가 필요합니다}"

cmd="echo $(base64 -w0 "$script") | base64 -d | bash -s --"
for arg in "$@"; do
  cmd+=" $arg"
done

# executionTimeout은 서버에서 스크립트가 도는 최대 시간(초)이다. rollout 대기(10분)와 undo를 넘게 잡는다.
params=$(jq -nc --arg c "$cmd" '{commands: [$c], executionTimeout: ["1500"]}')
id=$(aws ssm send-command \
  --instance-ids "$INSTANCE_ID" \
  --document-name AWS-RunShellScript \
  --comment "hapbang deploy (${GITHUB_RUN_ID:-local})" \
  --parameters "$params" \
  --query Command.CommandId --output text)

# 막 보낸 명령은 잠시 조회되지 않는다(InvocationDoesNotExist). 30분 동안 5초마다 상태를 본다.
status=Pending
for _ in $(seq 360); do
  sleep 5
  status=$(aws ssm get-command-invocation --command-id "$id" --instance-id "$INSTANCE_ID" \
    --query Status --output text 2>/dev/null || echo Pending)
  case "$status" in
    Pending|InProgress|Delayed) ;;
    *) break ;;
  esac
done

aws ssm get-command-invocation --command-id "$id" --instance-id "$INSTANCE_ID" \
  --query StandardOutputContent --output text
err=$(aws ssm get-command-invocation --command-id "$id" --instance-id "$INSTANCE_ID" \
  --query StandardErrorContent --output text)
if [ -n "$err" ]; then
  echo "$err" >&2
fi

if [ "$status" != Success ]; then
  echo "::error::SSM 명령이 $status 상태로 끝났습니다."
  exit 1
fi
