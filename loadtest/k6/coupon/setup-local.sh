#!/usr/bin/env bash
# 쿠폰 부하 시나리오의 로컬 테스트 데이터를 앱 API로 만든다 (#813). 결과·절차는 docs/load-test/coupon-issue.md.
#
# 로컬 lecture-app 포트로 사용자 헤더(X-User-Id·X-User-Role)를 직접 넣는다(docs/dev/local.md "앱 하나만 확인").
# 운영은 Envoy가 이 헤더를 지우므로 이 스크립트는 운영에서 쓸 수 없고, localhost가 아니면 멈춘다.
#
# 사용법(LT_BASE_URL 기본 http://localhost:18084):
#   setup-local.sh lecture [가격]                       # 판매자 강의 하나를 등록·승인(ON_SALE)하고 강의 ID를 출력
#   setup-local.sh coupon <강의ID> <수량> [할인율] [중첩] # 판매자 쿠폰 신청 → 심사 요청 → 관리자 승인(OPEN), 쿠폰 ID 출력
#   setup-local.sh issue <쿠폰ID> <회원ID>               # 회원 한 명이 쿠폰을 받는다(접수 → ISSUED까지 폴링)
#   setup-local.sh close <쿠폰ID>                         # 관리자 발급 종료(CLOSED)
#
# 판매자 ID는 LT_SELLER_ID(기본 900001), 관리자 ID는 LT_ADMIN_ID(기본 900000)다. 회원 ID는 k6 스크립트와 겹치지 않게 고른다.
set -euo pipefail

BASE_URL="${LT_BASE_URL:-http://localhost:18084}"
SELLER_ID="${LT_SELLER_ID:-900001}"
ADMIN_ID="${LT_ADMIN_ID:-900000}"

case "$BASE_URL" in
    http://localhost:*|http://127.0.0.1:*) ;;
    *) echo "로컬 주소만 받는다: $BASE_URL" >&2; exit 1 ;;
esac

# call <회원ID> <역할> <메서드> <경로> [JSON 본문]. 응답 본문을 출력하고, 2xx가 아니면 멈춘다
call() {
    local user="$1" role="$2" method="$3" path="$4" body="${5:-}" out status
    out=$(mktemp)
    status=$(curl -s -o "$out" -w '%{http_code}' -X "$method" "${BASE_URL}${path}" \
        -H "X-User-Id: ${user}" -H "X-User-Role: ${role}" -H 'Content-Type: application/json' \
        ${body:+-d "$body"})
    if [[ "$status" != 2* ]]; then
        echo "${method} ${path} → ${status}: $(cat "$out")" >&2
        rm -f "$out"
        return 1
    fi
    cat "$out"
    rm -f "$out"
}

lecture() {
    local price="${1:-10000}" pdf file_id lecture_id
    pdf=$(mktemp)
    printf '%%PDF-1.4\n%%EOF\n' > "$pdf"
    file_id=$(call "$SELLER_ID" VERIFIED_INFLUENCER POST /api/v1/lectures/files \
        "{\"fileType\":\"LECTURE_DOCUMENT\",\"originalFilename\":\"loadtest.pdf\",\"contentType\":\"application/pdf\",\"fileSize\":$(wc -c < "$pdf" | tr -d ' ')}" \
        | jq -r '.data.fileId')
    curl -sf -X PUT "${BASE_URL}/api/v1/lectures/files/${file_id}/content" \
        -H "X-User-Id: ${SELLER_ID}" -H 'X-User-Role: VERIFIED_INFLUENCER' -H 'Content-Type: application/pdf' \
        --data-binary "@${pdf}" >/dev/null
    rm -f "$pdf"
    lecture_id=$(call "$SELLER_ID" VERIFIED_INFLUENCER POST /api/v1/lectures \
        "{\"title\":\"부하 테스트 강의\",\"description\":\"loadtest\",\"priceHp\":${price},\"contents\":[{\"fileId\":${file_id},\"title\":\"자료\",\"contentType\":\"DOCUMENT\"}]}" \
        | jq -r '.data.id')
    call "$ADMIN_ID" ADMIN POST "/api/v1/lectures/admin/${lecture_id}/inspection/approve" >/dev/null
    echo "$lecture_id"
}

coupon() {
    local lecture_id="$1" quantity="$2" rate="${3:-10}" stackable="${4:-true}" now end coupon_id
    now=$(date -u +%Y-%m-%dT%H:%M:%SZ)
    end=$(date -u -v+1d +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || date -u -d '+1 day' +%Y-%m-%dT%H:%M:%SZ)
    coupon_id=$(call "$SELLER_ID" VERIFIED_INFLUENCER POST /api/v1/coupons/seller \
        "{\"name\":\"부하 테스트 쿠폰\",\"targetType\":\"LECTURE\",\"lectureId\":${lecture_id},\"discountType\":\"RATE\",\"discountValue\":${rate},\"quantity\":${quantity},\"issueStartAt\":\"${now}\",\"issueEndAt\":\"${end}\",\"expiryType\":\"RELATIVE\",\"expiryDays\":7,\"stackable\":${stackable}}" \
        | jq -r '.data.couponId')
    call "$SELLER_ID" VERIFIED_INFLUENCER POST "/api/v1/coupons/seller/${coupon_id}/review" >/dev/null
    call "$ADMIN_ID" ADMIN POST "/api/v1/coupons/admin/${coupon_id}/approve" >/dev/null
    echo "$coupon_id"
}

issue() {
    local coupon_id="$1" member_id="$2" request_id status i
    request_id="setup-${coupon_id}-${member_id}"
    call "$member_id" USER POST "/api/v1/coupons/${coupon_id}/issue-requests" \
        "{\"issueRequestId\":\"${request_id}\"}" >/dev/null
    for i in $(seq 1 60); do
        status=$(call "$member_id" USER GET "/api/v1/coupons/${coupon_id}/issue-requests/${request_id}" | jq -r '.data.status')
        [[ "$status" == ISSUED || "$status" == REJECTED ]] && { echo "$status"; return; }
        sleep 0.5
    done
    echo "30초 안에 확정되지 않았다: coupon=${coupon_id} member=${member_id} status=${status}" >&2
    return 1
}

close_coupon() {
    call "$ADMIN_ID" ADMIN POST "/api/v1/coupons/admin/${1}/close" | jq -r '.data.status'
}

case "${1:-}" in
    lecture) shift; lecture "$@" ;;
    coupon) shift; coupon "$@" ;;
    issue) shift; issue "$@" ;;
    close) shift; close_coupon "$@" ;;
    *) sed -n '2,13p' "$0" | sed 's/^# \{0,1\}//'; exit 1 ;;
esac
