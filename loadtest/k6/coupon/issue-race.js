// 쿠폰 선착순 발급 경쟁 (#813). 실행 방법·결과는 docs/load-test/coupon-issue.md.
//
// 회원 N명(각자 다른 회원)이 재고 M인 판매자 쿠폰 하나에 동시에 발급을 접수하고, 접수(202)된 요청은 상태를 ISSUED·REJECTED까지 폴링한다.
// 매진(409 COUPON_SOLD_OUT)은 접수 순간 확정된 거절로 센다. 과발급·중복·잔류는 끝난 뒤 DB·Redis로 확인한다(verify-local.sh).
//
// 환경 변수:
//   BASE_URL   대상 주소. 기본 로컬 lecture-app(http://localhost:18084). 운영 주소는 사용자 승인 뒤에만 준다
//   COUPON_ID  대상 쿠폰 ID(필수). 발급이 열린(OPEN) 판매자 쿠폰
//   N          동시 회원 수(기본 1000). M은 쿠폰 수량으로 정해지고, 스크립트는 기대값 EXPECTED_M(기본 100)과 비교해 요약에 남긴다
//   AUTH       header(기본, 로컬 앱 포트에 X-User-Id·X-User-Role) 또는 token(TOKENS_FILE의 access token, 운영 Envoy 경유)
//   MEMBER_ID_START  header 모드의 첫 회원 ID(기본 1000000). 회원 i는 MEMBER_ID_START + i
//   TOKENS_FILE      token 모드에서 access token 배열(JSON) 파일. 요약 JSON에 남지 않도록 setup()이 아니라 init에서 읽는다
//   RUN_ID     발급 요청 ID 접두어(기본 시작 시각). 같은 쿠폰에 다시 돌릴 때 요청 ID가 겹치지 않게 한다
//   POLL_INTERVAL    상태 조회 간격 초(기본 0.5), SETTLE_LIMIT 확정 대기 상한 초(기본 150)
//   PROFILE    race(기본) 또는 smoke(회원 1명, 스크립트가 도는지만 본다)
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import { SharedArray } from 'k6/data';
import exec from 'k6/execution';
import { headers, requiredEnv, serverErrors } from '../lib/http.js';

const BASE = (__ENV.BASE_URL || 'http://localhost:18084').replace(/\/$/, '');
const PROFILE = __ENV.PROFILE || 'race';
const AUTH = __ENV.AUTH || 'header';
const COUPON_ID = requiredEnv('COUPON_ID');
const N = PROFILE === 'smoke' ? 1 : parseInt(__ENV.N || '1000', 10);
const EXPECTED_M = parseInt(__ENV.EXPECTED_M || '100', 10);
const MEMBER_ID_START = parseInt(__ENV.MEMBER_ID_START || '1000000', 10);
const RUN_ID = __ENV.RUN_ID || `r${Date.now()}`;
const POLL_INTERVAL = parseFloat(__ENV.POLL_INTERVAL || '0.5');
const SETTLE_LIMIT = parseFloat(__ENV.SETTLE_LIMIT || '150');

if (!['race', 'smoke'].includes(PROFILE)) {
    throw new Error(`알 수 없는 PROFILE=${PROFILE}. race 또는 smoke`);
}
if (AUTH === 'header' && !/^http:\/\/(localhost|127\.0\.0\.1)[:/]/.test(`${BASE}/`)) {
    // 운영 Envoy는 외부에서 붙인 X-User-* 헤더를 지운다. header 모드는 로컬 앱 포트에서만 뜻이 있다
    throw new Error('AUTH=header는 로컬 주소에서만 쓴다. 운영은 AUTH=token');
}

const tokens = AUTH === 'token'
    ? new SharedArray('tokens', () => JSON.parse(open(requiredEnv('TOKENS_FILE'))))
    : null;
if (tokens && tokens.length < N) {
    throw new Error(`토큰 ${tokens.length}개로 회원 ${N}명을 만들 수 없다`);
}

// 결과 분류(접수 응답 기준)
const accepted = new Counter('issue_accepted');
const soldOut = new Counter('issue_sold_out');
const otherRejected = new Counter('issue_rejected_other');
// 최종 결과(폴링 뒤)
const issued = new Counter('issue_issued');
const rejected = new Counter('issue_rejected');
const unsettled = new Counter('issue_unsettled');
// 접수 응답부터 ISSUED·REJECTED 확인까지(초). 접수된 요청만
const settleSeconds = new Trend('issue_settle_seconds');
// 시나리오 시작부터 이 회원의 결과가 확정될 때까지(초). 최대값이 "모든 접수가 확정될 때까지 걸린 시간"이다
const settledSinceStart = new Trend('issue_settled_since_start_seconds');

export const options = {
    scenarios: {
        race: {
            executor: 'per-vu-iterations',
            vus: N,
            iterations: 1,
            maxDuration: `${Math.ceil(SETTLE_LIMIT) + 60}s`,
        },
    },
    thresholds: {
        // Q41: 접수 p95 ≤ 500 ms, 5xx < 1%, 모든 접수가 2분 안에 확정
        'http_req_duration{name:issue_accept}': ['p(95)<=500'],
        server_errors: ['rate<0.01'],
        issue_unsettled: ['count==0'],
        issue_settled_since_start_seconds: ['max<=120'],
        checks: ['rate>0.99'],
    },
    summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

function authHeaders(memberIndex) {
    if (AUTH === 'token') {
        return headers(tokens[memberIndex]);
    }
    return Object.assign(headers(), {
        'X-User-Id': String(MEMBER_ID_START + memberIndex),
        'X-User-Role': 'USER',
    });
}

export function setup() {
    console.log(`쿠폰 ${COUPON_ID}에 회원 ${N}명이 동시에 접수한다. BASE=${BASE}, AUTH=${AUTH}, RUN_ID=${RUN_ID}, PROFILE=${PROFILE}`);
    // 토큰을 돌려주지 않는다. --summary-export가 setup_data를 그대로 쓴다
    return { startedAt: Date.now() };
}

export default function (data) {
    const memberIndex = exec.vu.idInTest - 1;
    const requestId = `${RUN_ID}-${memberIndex}`;
    const hdrs = Object.assign(authHeaders(memberIndex), { 'Content-Type': 'application/json' });

    const res = http.post(`${BASE}/api/v1/coupons/${COUPON_ID}/issue-requests`,
        JSON.stringify({ issueRequestId: requestId }), { headers: hdrs, tags: { name: 'issue_accept' } });
    serverErrors.add(res.status >= 500, { name: 'issue_accept' });
    const code = safeJson(res, 'code');
    check(res, {
        'issue_accept 202 또는 409 매진': (r) => r.status === 202 || (r.status === 409 && code === 'COUPON_SOLD_OUT'),
    });

    if (res.status !== 202) {
        if (res.status === 409 && code === 'COUPON_SOLD_OUT') {
            soldOut.add(1);
        } else {
            otherRejected.add(1, { status: String(res.status), code: String(code) });
            console.warn(`접수 실패 member=${memberIndex} status=${res.status} code=${code}`);
        }
        settledSinceStart.add((Date.now() - data.startedAt) / 1000);
        return;
    }
    accepted.add(1);

    const acceptedAt = Date.now();
    const deadline = acceptedAt + SETTLE_LIMIT * 1000;
    while (Date.now() < deadline) {
        sleep(POLL_INTERVAL);
        const poll = http.get(`${BASE}/api/v1/coupons/${COUPON_ID}/issue-requests/${requestId}`,
            { headers: authHeaders(memberIndex), tags: { name: 'issue_status' } });
        serverErrors.add(poll.status >= 500, { name: 'issue_status' });
        const status = poll.status === 200 ? safeJson(poll, 'data.status') : null;
        if (status === 'ISSUED' || status === 'REJECTED') {
            (status === 'ISSUED' ? issued : rejected).add(1);
            settleSeconds.add((Date.now() - acceptedAt) / 1000);
            settledSinceStart.add((Date.now() - data.startedAt) / 1000);
            return;
        }
    }
    unsettled.add(1);
    console.warn(`확정되지 않았다 member=${memberIndex} requestId=${requestId}`);
}

function safeJson(res, path) {
    try {
        return res.json(path);
    } catch (e) {
        return null;
    }
}

export function teardown() {
    console.log(`끝. 기대 재고 EXPECTED_M=${EXPECTED_M}. DB·Redis 확인: loadtest/k6/coupon/verify-local.sh check ${COUPON_ID} ${EXPECTED_M}`);
}
