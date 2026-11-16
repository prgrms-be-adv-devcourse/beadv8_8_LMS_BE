// 알림 REST 조회 부하 L1(N13·N15, ND-157). 운영 쓰기를 하지 않는다: GET만 보낸다(목록 50%, 안 읽은 수 50%).
// 읽음·전체 읽음(PATCH)은 운영 쓰기라 넣지 않는다(N13 결정, N15에서 운영 쓰기 여부와 함께 정한다).
// 실행 방법과 계정 파일은 docs/load-test/notification-baseline.md, 측정 규칙은 docs/load-test/README.md.
import http from 'k6/http';
import { BASE_URL, checkRsData, headers, requiredEnv } from '../lib/http.js';
import { login } from '../lib/auth.js';

const PROFILE = __ENV.PROFILE || 'smoke';

const PROFILES = {
    // 운영 smoke: 스크립트가 운영 경로(로그인·Gateway·REST)로 도는지만 본다
    smoke: {
        main: { executor: 'constant-vus', vus: 1, duration: '30s' },
    },
    // 본 부하(N15 L1)의 프로필은 N15에서 더한다(N13 범위는 운영 smoke, ND-171)
};

// access token(15분) 안에 끝나는지 setup()에서 확인한다. gracefulStop 기본 30초를 더한 값
const PLANNED_SECONDS = { smoke: 30 + 30 };

if (!PROFILES[PROFILE]) {
    throw new Error(`알 수 없는 PROFILE=${PROFILE}. smoke만 있다`);
}

const MAIN_P95 = ['p(95)<500'];

export const options = {
    scenarios: PROFILES[PROFILE],
    thresholds: {
        'http_req_duration{scenario:main}': MAIN_P95,
        'http_req_duration{scenario:main,name:notification_list}': MAIN_P95,
        'http_req_duration{scenario:main,name:notification_unread_count}': MAIN_P95,
        'server_errors{scenario:main}': ['rate<0.01'],
        'dropped_iterations{scenario:main}': ['count<60'],
        server_errors: [{ threshold: 'rate<0.05', abortOnFail: true, delayAbortEval: '30s' }],
        'checks{scenario:main}': ['rate>0.99'],
    },
    summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

export function setup() {
    const { token, expiresIn } = login(requiredEnv('LT_EMAIL'), requiredEnv('LT_PASSWORD'));
    if (expiresIn - 60 < PLANNED_SECONDS[PROFILE]) {
        throw new Error(`access token(${expiresIn}초)이 측정 시간(${PLANNED_SECONDS[PROFILE]}초)보다 짧습니다.`);
    }
    console.log(`알림 REST 조회를 측정합니다. PROFILE=${PROFILE}`);
    return { token };
}

export default function (data) {
    if (Math.random() < 0.5) {
        const res = http.get(`${BASE_URL}/api/v1/notifications`,
            { headers: headers(data.token), tags: { name: 'notification_list' } });
        checkRsData(res, 'notification_list');
    } else {
        const res = http.get(`${BASE_URL}/api/v1/notifications/unread-count`,
            { headers: headers(data.token), tags: { name: 'notification_unread_count' } });
        checkRsData(res, 'notification_unread_count');
    }
}
