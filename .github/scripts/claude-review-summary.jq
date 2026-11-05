# Claude 리뷰 run의 실행 수치를 Step Summary용 Markdown으로 만든다(#700). claude-review.yml이 실행한다.
# 입력은 claude-code-action의 execution_file(SDK 메시지 배열)이다. 리뷰가 diff 밖을 얼마나 읽는지와 사용량을 보려고 남긴다.
# 공개 레포라 Actions 화면도 공개다. 숫자와 도구 이름만 쓰고, 도구 입력·결과(코드, PR 내용, 검색어)는 쓰지 않는다.
# 사용법: jq -r --arg base "$RUNNER_TEMP/review-base/" -f claude-review-summary.jq <execution_file>

def num: if type == "number" then tostring else "-" end;

(map(select(.type == "system" and .subtype == "init")) | first // {}) as $init
| (map(select(.type == "result")) | last // {}) as $result
| [.[] | select(.type == "assistant") | .message.content[]? | select(.type == "tool_use")] as $calls
| ($result.usage // {}) as $usage
| "### Claude 리뷰 실행 수치",
  "",
  "| 항목 | 값 |",
  "| --- | --- |",
  "| 모델 | \(if ($init.model | type) == "string" then $init.model else "-" end) |",
  "| 결과 | \(if ($result.subtype | type) == "string" then $result.subtype else "-" end) |",
  "| turn | \($result.num_turns | num) |",
  "| 시간(초) | \(if ($result.duration_ms | type) == "number" then ($result.duration_ms / 1000 | floor | tostring) else "-" end) |",
  "| 비용(USD, 환산) | \(if ($result.total_cost_usd | type) == "number" then ($result.total_cost_usd * 100 | round / 100 | tostring) else "-" end) |",
  "| 토큰(입력 · 캐시 쓰기 · 캐시 읽기 · 출력) | \($usage.input_tokens | num) · \($usage.cache_creation_input_tokens | num) · \($usage.cache_read_input_tokens | num) · \($usage.output_tokens | num) |",
  "| dev 기준 문서 읽기 | \([$calls[] | select(.name == "Read") | .input.file_path? | select(type == "string" and startswith($base))] | length) |",
  "",
  "| 도구 | 호출 수 |",
  "| --- | --- |",
  ([$calls[].name | select(type == "string")] | group_by(.) | sort_by(-length) | .[] | "| `\(.[0])` | \(length) |")
