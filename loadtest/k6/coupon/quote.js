// 쿠폰 계산 미리보기(추천) 부하 (#813). 실행 방법·결과는 docs/load-test/coupon-issue.md.
//
// POST /api/v1/coupons/me/quote를 강의 수(기본 1·5·20개)별로 차례로 보낸다. selections를 비워(null) 추천을 받는다.
// 강의 수별 p95와 optimal=false(탐색 상한에 걸려 찾은 최선을 돌려줌) 비율로 calculate.max-search-nodes·max-items를 본다.
// 회원은 setup-local.sh로 강의마다 쿠폰을 여러 장 받아 둔다(문서 "quote 데이터").
//
// 환경 변수:
//   BASE_URL     대상 주소. 기본 로컬 lecture-app(http://localhost:18084)
//   LECTURE_IDS  강의 ID 목록(쉼표, 필수). SIZES의 최대값 이상
//   SIZES        한 요청의 강의 수 목록(기본 1,5,20). 강의 수마다 시나리오 하나를 차례로 돌린다
//   RATE         초당 요청 수(기본 10), DURATION 강의 수별 시간(기본 1m)
//   AUTH         header(기본, 로컬 앱 포트) 또는 token(TOKENS_FILE)
//   MEMBER_ID_START, MEMBERS   header 모드 회원 범위(기본 3000000부터 10명). 요청마다 돌아가며 고른다
//   PROFILE      load(기본) 또는 smoke(강의 수마다 1 VU 5회)
import http from 'k6/http';
import { check } from 'k6';
import { Rate } from 'k6/metrics';
import { SharedArray } from 'k6/data';
import exec from 'k6/execution';
import { headers, requiredEnv, serverErrors } from '../lib/http.js';

const BASE = (__ENV.BASE_URL || 'http://localhost:18084').replace(/\/$/, '');
const PROFILE = __ENV.PROFILE || 'load';
const AUTH = __ENV.AUTH || 'header';
const LECTURE_IDS = requiredEnv('LECTURE_IDS').split(',').map((v) => parseInt(v.trim(), 10));
const SIZES = (__ENV.SIZES || '1,5,20').split(',').map((v) => parseInt(v.trim(), 10));
const RATE = parseInt(__ENV.RATE || '10', 10);
const DURATION = __ENV.DURATION || '1m';
const MEMBER_ID_START = parseInt(__ENV.MEMBER_ID_START || '3000000', 10);
const MEMBERS = parseInt(__ENV.MEMBERS || '10', 10);

if (!['load', 'smoke'].includes(PROFILE)) {
    throw new Error(`알 수 없는 PROFILE=${PROFILE}. load 또는 smoke`);
}
if (Math.max(...SIZES) > LECTURE_IDS.length) {
    throw new Error(`강의 ${LECTURE_IDS.length}개로 ${Math.max(...SIZES)}개 요청을 만들 수 없다`);
}
if (AUTH === 'header' && !/^http:\/\/(localhost|127\.0\.0\.1)[:/]/.test(`${BASE}/`)) {
    throw new Error('AUTH=header는 로컬 주소에서만 쓴다. 운영은 AUTH=token');
}

const tokens = AUTH === 'token'
    ? new SharedArray('tokens', () => JSON.parse(open(requiredEnv('TOKENS_FILE'))))
    : null;

// 추천이 탐색 상한에 걸린 비율(optimal=false)
const notOptimal = new Rate('quote_not_optimal');

function durationSeconds(value) {
    const m = /^(\d+)(s|m)$/.exec(value);
    if (!m) {
        throw new Error(`DURATION은 30s·2m 꼴: ${value}`);
    }
    return parseInt(m[1], 10) * (m[2] === 'm' ? 60 : 1);
}

function scenarios() {
    const result = {};
    let start = 0;
    for (const size of SIZES) {
        if (PROFILE === 'smoke') {
            result[`quote_${size}`] = {
                executor: 'per-vu-iterations', vus: 1, iterations: 5, startTime: `${start}s`, env: { SIZE: String(size) },
            };
            start += 10;
        } else {
            result[`quote_${size}`] = {
                executor: 'constant-arrival-rate', rate: RATE, timeUnit: '1s', duration: DURATION,
                startTime: `${start}s`, preAllocatedVUs: 10, maxVUs: 100, env: { SIZE: String(size) },
            };
            start += durationSeconds(DURATION) + 5;
        }
    }
    return result;
}

const thresholds = {
    server_errors: ['rate<0.01'],
    checks: ['rate>0.99'],
};
for (const size of SIZES) {
    thresholds[`http_req_duration{name:quote_${size}}`] = ['p(95)<500'];
    thresholds[`quote_not_optimal{size:${size}}`] = ['rate>=0'];
}

export const options = {
    scenarios: scenarios(),
    thresholds,
    summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

function authHeaders(memberIndex) {
    if (AUTH === 'token') {
        return headers(tokens[memberIndex % tokens.length]);
    }
    return Object.assign(headers(), {
        'X-User-Id': String(MEMBER_ID_START + (memberIndex % MEMBERS)),
        'X-User-Role': 'USER',
    });
}

export default function () {
    const size = parseInt(__ENV.SIZE, 10);
    const iteration = exec.scenario.iterationInTest;
    // 강의 목록에서 size개를 돌려 가며 고른다. 같은 강의가 두 번 들어가지 않는다
    const offset = iteration % LECTURE_IDS.length;
    const lectureIds = [];
    for (let i = 0; i < size; i += 1) {
        lectureIds.push(LECTURE_IDS[(offset + i) % LECTURE_IDS.length]);
    }
    const res = http.post(`${BASE}/api/v1/coupons/me/quote`, JSON.stringify({ lectureIds }), {
        headers: Object.assign(authHeaders(iteration), { 'Content-Type': 'application/json' }),
        tags: { name: `quote_${size}` },
    });
    serverErrors.add(res.status >= 500, { name: `quote_${size}` });
    let optimal = null;
    let applied = 0;
    try {
        optimal = res.json('data.optimal');
        applied = (res.json('data.items') || []).reduce((sum, item) => sum + (item.coupons || []).length, 0);
    } catch (e) {
        optimal = null;
    }
    check(res, {
        'quote 200': (r) => r.status === 200,
        'quote 쿠폰 적용': () => applied > 0,
    });
    if (optimal !== null) {
        notOptimal.add(optimal === false, { size: String(size) });
    }
}
