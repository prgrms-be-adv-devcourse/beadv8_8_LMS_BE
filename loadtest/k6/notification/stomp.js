// 알림 STOMP 연결 유지 L2(N13·N15, ND-157). 데이터를 쓰지 않는다: 연결 티켓 발급(30초 일회용 Redis 키, 회원마다 60초에
// 10번까지)과 연결·구독·heart-beat만 한다(N13 결정). 알림 생성·수신 측정(L3)은 격리 환경에서 했다(ND-188).
// PROFILE=smoke: 계정 하나(LT_EMAIL·LT_PASSWORD)로 연결 1개(N13).
// PROFILE=load(NT-LOAD-L2, ND-198): 계정 파일(LT_ACCOUNTS_FILE, 최대 10개)로 연결 50 → 100 → 200. 연결은 access token(15분)이
// 끝나면 닫히므로(ND-056) setup에서 받은 토큰 안에서 끝나게 13분(단계마다 4분 20초)으로 둔다.
import exec from 'k6/execution';
import { requiredEnv } from '../lib/http.js';
import { login } from '../lib/auth.js';
import { holdConnection, loadAccounts, loginAll, staggerSleep } from './stomp-common.js';

const PROFILE = __ENV.PROFILE || 'smoke';
// 운영 알림 WebSocket이 허용한 출처(FE 주소). 레포에 적지 않고 환경 변수로 받는다
const ORIGIN = requiredEnv('WS_ORIGIN');
const HOLD_SECONDS = Number(__ENV.HOLD_SECONDS || 30);

// load 단계: 연결을 여는 시작 시각(초), 늘릴 연결 수, 그 연결을 나눠 여는 시간(초)
const STAGE_SECONDS = 260;
const LOAD_END_SECONDS = STAGE_SECONDS * 3;
const LOAD_STAGES = {
    stage50: { start: 0, vus: 50, ramp: 30, offset: 0 },
    stage100: { start: STAGE_SECONDS, vus: 50, ramp: 30, offset: 50 },
    // 계정 10개 × 회원마다 60초에 티켓 10번 = 60초에 100번이 상한이라 100연결은 90초에 나눠 연다
    stage200: { start: STAGE_SECONDS * 2, vus: 100, ramp: 90, offset: 100 },
};

function loadScenarios() {
    const result = {};
    for (const [name, s] of Object.entries(LOAD_STAGES)) {
        result[name] = {
            executor: 'per-vu-iterations', vus: s.vus, iterations: 1, startTime: `${s.start}s`,
            maxDuration: `${LOAD_END_SECONDS - s.start + 60}s`, exec: 'loadConnection', tags: { stage: name },
        };
    }
    return result;
}

const PROFILES = {
    smoke: {
        main: { executor: 'per-vu-iterations', vus: 1, iterations: 1, maxDuration: `${HOLD_SECONDS + 30}s` },
    },
    load: loadScenarios(),
};

if (!PROFILES[PROFILE]) {
    throw new Error(`알 수 없는 PROFILE=${PROFILE}. smoke, load`);
}

const ACCOUNTS = PROFILE === 'load' ? loadAccounts(requiredEnv('LT_ACCOUNTS_FILE')) : null;

const THRESHOLDS = {
    smoke: {
        'checks{scenario:main}': ['rate>0.99'],
        stomp_abnormal_close: ['count<1'],
        stomp_connect_ms: ['p(95)<2000'],
    },
    // 비정상 끊김(1006) 0이 판정 기준이다. 메모리·OOMKilled는 Grafana로 본다(절차서)
    load: {
        checks: ['rate>0.99'],
        stomp_abnormal_close: ['count<1'],
        stomp_expired_close: ['count<1'],
        stomp_ticket_failed: ['count<1'],
        stomp_connected: ['count>=200'],
        stomp_connect_ms: ['p(95)<2000'],
    },
};

export const options = {
    scenarios: PROFILES[PROFILE],
    thresholds: THRESHOLDS[PROFILE],
    summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

export function setup() {
    if (PROFILE === 'load') {
        const tokens = loginAll(ACCOUNTS, LOAD_END_SECONDS);
        console.log(`알림 STOMP 연결 유지를 측정합니다. PROFILE=load, 계정 ${tokens.length}개, ${LOAD_END_SECONDS}초`);
        return { tokens, endMs: Date.now() + LOAD_END_SECONDS * 1000 };
    }
    const { token } = login(requiredEnv('LT_EMAIL'), requiredEnv('LT_PASSWORD'));
    return { token };
}

export default function (data) {
    holdConnection(data.token, ORIGIN, HOLD_SECONDS * 1000, {});
}

export function loadConnection(data) {
    const stage = LOAD_STAGES[exec.scenario.name];
    const index = exec.scenario.iterationInTest;
    staggerSleep(index, stage.vus, stage.ramp);
    // 연결을 계정에 고르게 나눈다(티켓 발급 한도)
    const token = data.tokens[(stage.offset + index) % data.tokens.length];
    holdConnection(token, ORIGIN, data.endMs - Date.now(), {});
}
