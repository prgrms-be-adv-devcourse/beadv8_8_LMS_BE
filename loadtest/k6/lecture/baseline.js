// 강의 앱 조회 API 기준선 (#690). 실행 방법·측정 절차는 docs/load-test/README.md, 결과는 docs/load-test/lecture-baseline.md.
//
// 요청 비율: 강의 목록 LATEST 40%, keyword 검색 20%, 강의 상세 30%, 리뷰 목록 10%.
// 목록은 비회원도 되지만 상세·리뷰는 로그인이 필요해(lecture-app SecurityConfig) setup()에서 한 번 로그인한다.
//
// PROFILE=baseline(기본): 워밍업 30 RPS 2분 → 본 측정 100 RPS 10분
// PROFILE=smoke: 1 VU 30초. 스크립트가 도는지만 본다
import http from 'k6/http';
import { BASE_URL, checkRsData, headers, requiredEnv } from '../lib/http.js';
import { login } from '../lib/auth.js';

const PROFILE = __ENV.PROFILE || 'baseline';

// 결과가 있는 단어와 없는 단어를 섞는다. 바꾸면 결과 문서의 시나리오 절도 고친다
const KEYWORDS = ['AI', '크리에이터', '유튜브', '편집', '마케팅', '촬영', '없는검색어'];

const PAGE_SIZE = 20;

const PROFILES = {
    baseline: {
        warmup: {
            executor: 'constant-arrival-rate',
            rate: 30,
            timeUnit: '1s',
            duration: '2m',
            preAllocatedVUs: 20,
            maxVUs: 100,
        },
        main: {
            executor: 'constant-arrival-rate',
            rate: 100,
            timeUnit: '1s',
            duration: '10m',
            startTime: '2m',
            preAllocatedVUs: 50,
            maxVUs: 300,
        },
    },
    smoke: {
        main: {
            executor: 'constant-vus',
            vus: 1,
            duration: '30s',
        },
    },
};

// access token(15분) 안에 끝나는지 setup()에서 확인한다. gracefulStop 기본 30초를 더한 값
const PLANNED_SECONDS = { baseline: 12 * 60 + 30, smoke: 30 + 30 };

if (!PROFILES[PROFILE]) {
    throw new Error(`알 수 없는 PROFILE=${PROFILE}. baseline 또는 smoke`);
}

const MAIN_P95 = ['p(95)<500'];

export const options = {
    scenarios: PROFILES[PROFILE],
    // 합격 기준은 본 측정(main) 구간만 본다. 워밍업은 JIT·connection pool이 데워지는 구간이다
    thresholds: {
        'http_req_duration{scenario:main}': MAIN_P95,
        'http_req_duration{scenario:main,name:lecture_list}': MAIN_P95,
        'http_req_duration{scenario:main,name:lecture_search}': MAIN_P95,
        'http_req_duration{scenario:main,name:lecture_detail}': MAIN_P95,
        'http_req_duration{scenario:main,name:lecture_reviews}': MAIN_P95,
        'server_errors{scenario:main}': ['rate<0.01'],
        // 5xx가 5%를 넘으면 30초 뒤부터 바로 멈춘다(측정 오염 방지)
        server_errors: [{ threshold: 'rate<0.05', abortOnFail: true, delayAbortEval: '30s' }],
        // 토큰 만료(401) 등으로 성공 응답이 무너지면 결과가 무효라 같이 본다
        'checks{scenario:main}': ['rate>0.99'],
    },
    summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

export function setup() {
    const { token, expiresIn } = login(requiredEnv('LT_EMAIL'), requiredEnv('LT_PASSWORD'));
    if (expiresIn - 60 < PLANNED_SECONDS[PROFILE]) {
        throw new Error(`access token(${expiresIn}초)이 측정 시간(${PLANNED_SECONDS[PROFILE]}초)보다 짧습니다.`);
    }

    const res = http.get(`${BASE_URL}/api/v1/lectures?sort=LATEST&page=0&size=100`, { headers: headers() });
    if (res.status !== 200) {
        throw new Error(`강의 ID 목록을 받지 못했습니다. status=${res.status}`);
    }
    const lectureIds = res.json('data.content').map((lecture) => lecture.lectureId);
    if (lectureIds.length === 0) {
        throw new Error('공개 강의가 없습니다.');
    }
    console.log(`강의 ${lectureIds.length}건으로 측정합니다. PROFILE=${PROFILE}`);
    return { token, lectureIds };
}

function pick(values) {
    return values[Math.floor(Math.random() * values.length)];
}

export default function (data) {
    const r = Math.random();
    if (r < 0.4) {
        const res = http.get(`${BASE_URL}/api/v1/lectures?sort=LATEST&page=0&size=${PAGE_SIZE}`,
            { headers: headers(), tags: { name: 'lecture_list' } });
        checkRsData(res, 'lecture_list');
    } else if (r < 0.6) {
        const keyword = encodeURIComponent(pick(KEYWORDS));
        const res = http.get(`${BASE_URL}/api/v1/lectures?keyword=${keyword}&sort=LATEST&page=0&size=${PAGE_SIZE}`,
            { headers: headers(), tags: { name: 'lecture_search' } });
        checkRsData(res, 'lecture_search');
    } else if (r < 0.9) {
        const res = http.get(`${BASE_URL}/api/v1/lectures/${pick(data.lectureIds)}`,
            { headers: headers(data.token), tags: { name: 'lecture_detail' } });
        checkRsData(res, 'lecture_detail');
    } else {
        const res = http.get(`${BASE_URL}/api/v1/lectures/${pick(data.lectureIds)}/reviews?page=0&size=${PAGE_SIZE}`,
            { headers: headers(data.token), tags: { name: 'lecture_reviews' } });
        checkRsData(res, 'lecture_reviews');
    }
}
