#!/usr/bin/env bash
# 쿠폰 발급 경쟁 뒤 로컬 DB·Redis·지표로 I1·I2·I3을 확인하고, 실행 중 Redis 메모리를 표본으로 남긴다 (#813).
# local-stack.sh가 띄운 컨테이너(hapbang-lt-*)만 본다. 결과·절차는 docs/load-test/coupon-issue.md.
#
# 사용법:
#   verify-local.sh sample <쿠폰ID> [간격초]   # k6와 함께 띄운다. 끝낼 때까지 CSV(시각, requests·users 바이트·항목 수, used_memory)
#   verify-local.sh check <쿠폰ID> <기대 발급 수 M>
set -euo pipefail

STACK="$(dirname "$0")/local-stack.sh"
APP_URL="${LT_BASE_URL:-http://localhost:18084}"

rcli() { "$STACK" redis-cli "$@"; }

sample() {
    local c="$1" interval="${2:-0.5}"
    echo "epoch_ms,requests_bytes,requests_len,users_bytes,users_len,used_memory"
    while true; do
        printf '%s,%s,%s,%s,%s,%s\n' "$(python3 -c 'import time;print(int(time.time()*1000))')" \
            "$(rcli MEMORY USAGE "coupon:${c}:requests" SAMPLES 0)" "$(rcli HLEN "coupon:${c}:requests")" \
            "$(rcli MEMORY USAGE "coupon:${c}:users" SAMPLES 0)" "$(rcli SCARD "coupon:${c}:users")" \
            "$(rcli INFO memory | tr -d '\r' | awk -F: '$1=="used_memory"{print $2}')"
        sleep "$interval"
    done
}

check() {
    local c="$1" m="$2"
    echo "== DB (coupon schema) =="
    "$STACK" psql <<SQL
\\pset footer off
-- I1: DB 확정 발급 수 ≤ 총수량, 수요가 재고보다 크면 = M
select c.id, c.quantity, c.issued_count, c.status,
       (select count(*) from coupon.member_coupon mc where mc.coupon_id = c.id) as member_coupons
  from coupon.coupon c where c.id = ${c};
-- I2: 회원당 같은 쿠폰 1장(0행이어야 한다. UNIQUE(member_id, coupon_id)가 막는다)
select member_id, count(*) from coupon.member_coupon where coupon_id = ${c} group by member_id having count(*) > 1;
-- 요청 결과 분포와 소비 처리량(접수 → DB 확정)
select result, count(*), min(accepted_at) as first_accepted, max(processed_at) as last_processed,
       round(extract(epoch from max(processed_at) - min(accepted_at))::numeric, 3) as span_s,
       round(extract(epoch from max(processed_at - accepted_at))::numeric, 3) as max_lag_s,
       round(extract(epoch from percentile_cont(0.95) within group (order by processed_at - accepted_at))::numeric, 3) as p95_lag_s
  from coupon.coupon_issue_request where coupon_id = ${c} group by result;
-- DLT 격리 기록(0이어야 한다)
select status, count(*) from coupon.coupon_dlt_log where coupon_id = ${c} group by status;
SQL
    echo "== Redis =="
    echo "stock=$(rcli GET "coupon:${c}:stock") users=$(rcli SCARD "coupon:${c}:users") requests=$(rcli HLEN "coupon:${c}:requests")"
    echo "issue-control: $(rcli HGETALL "coupon:${c}:issue-control" | paste -sd' ' -)"
    # I3: Redis에 접수 기록이 있는데 DB에 결과가 없는 요청(= PENDING 잔류)
    local ids pending=0 id n
    ids=$(rcli HKEYS "coupon:${c}:requests")
    for id in $ids; do
        n=$("$STACK" psql "select count(*) from coupon.coupon_issue_request where coupon_id = ${c} and issue_request_id = '${id}'" \
            | sed -n 3p | tr -d ' ')
        [[ "$n" == 0 ]] && pending=$((pending + 1))
    done
    echo "PENDING 잔류(Redis requests에 있고 DB에 없음)=${pending}"
    # 수량 식: 총수량 = DB 발급 완료 + 진행 중/보상 대기 + Redis 가용 재고
    echo "== 지표 (${APP_URL}/actuator/prometheus) =="
    curl -s "${APP_URL}/actuator/prometheus" | grep -E '^coupon_(reconciliation_mismatches|dlt_pending|issue_backlog_holds|issue_consume_total|issue_publish_failures_total|issue_republished_total|issue_republish_oldest_age)' || true
    echo "기대 발급 수 M=${m}"
}

case "${1:-}" in
    sample) shift; sample "$@" ;;
    check) shift; check "$@" ;;
    *) sed -n '2,7p' "$0" | sed 's/^# \{0,1\}//'; exit 1 ;;
esac
