// 알림·챗봇 혼합 부하 L6(N15, NT-LOAD-L6, ND-157·ND-187). 준비만 했다: 챗봇 담당이 요청 경로·비율을 확인하기 전에는 실행하지 않는다.
// 알림 쪽은 L1(REST 조회 초당 100, GET만)과 STOMP 연결 100을 함께 걸고, 챗봇은 LLM을 부르지 않는 GET 위주에 낮은 비율의 LLM 요청을 더한다.
// 챗봇 비율은 담당 확인값을 환경 변수로 받는다(기본값을 두지 않는다). 경로(FAQ 모아보기·FAQ 대화)도 담당 확인 대상이며, 바뀌면 이 파일을 고친다. 단독 대비 악화율을 보려면 챗봇 요청만 같은 비율로 따로 돌린다(CHATBOT_ONLY=1).
// 운영 쓰기: 알림은 없다. 챗봇 FAQ 대화(LLM)는 챗봇 쪽 기록·외부 LLM 호출을 남길 수 있어 담당 확인 대상이다.
import http from 'k6/http';
import exec from 'k6/execution';
import { Counter } from 'k6/metrics';
import { BASE_URL, checkRsData, headers, requiredEnv } from '../lib/http.js';
import { assertTicketBudget, holdConnection, loadAccounts, loginAll, staggerSleep } from './stomp-common.js';

if (__ENV.CHATBOT_CONFIRMED !== 'yes') {
    throw new Error('L6은 챗봇 담당 확인 뒤에만 실행한다(ND-187). 확인했으면 CHATBOT_CONFIRMED=yes를 준다');
}

const ORIGIN = requiredEnv('WS_ORIGIN');
const ACCOUNTS = loadAccounts(requiredEnv('LT_ACCOUNTS_FILE'));
const CHATBOT_ONLY = __ENV.CHATBOT_ONLY === '1';
const CHATBOT_GET_RPS = Number(requiredEnv('CHATBOT_GET_RPS'));
const CHATBOT_LLM_RPS = Number(requiredEnv('CHATBOT_LLM_RPS'));
// LLM 대화 질문. 레포에 개인 정보가 없는 고정 문장만 둔다
const LLM_MESSAGE = __ENV.CHATBOT_LLM_MESSAGE || '출연을 취소하면 HP는 돌려받나요?';

// access token(15분) 안에서 끝나게 10분(L1과 같은 길이)
const DURATION_SECONDS = 600;
const STOMP_VUS = 100;
// 계정 10개 × 회원마다 60초에 티켓 10번이라 100연결은 90초에 나눠 연다
const STOMP_RAMP = 90;
if (!CHATBOT_ONLY) {
    assertTicketBudget(ACCOUNTS, [{ vus: STOMP_VUS, ramp: STOMP_RAMP }]);
}

const chatbotRateLimited = new Counter('chatbot_rate_limited');

function scenarios() {
    const chatbot = {
        chatbot_get: {
            executor: 'constant-arrival-rate', rate: CHATBOT_GET_RPS, timeUnit: '1s', duration: `${DURATION_SECONDS}s`,
            preAllocatedVUs: 10, maxVUs: 50, exec: 'chatbotGet',
        },
    };
    if (CHATBOT_LLM_RPS > 0) {
        chatbot.chatbot_llm = {
            executor: 'constant-arrival-rate', rate: CHATBOT_LLM_RPS, timeUnit: '1s', duration: `${DURATION_SECONDS}s`,
            preAllocatedVUs: 10, maxVUs: 60, exec: 'chatbotLlm',
        };
    }
    if (CHATBOT_ONLY) {
        return chatbot;
    }
    return Object.assign({
        notification_rest: {
            executor: 'constant-arrival-rate', rate: 100, timeUnit: '1s', duration: `${DURATION_SECONDS}s`,
            preAllocatedVUs: 50, maxVUs: 200, exec: 'notificationRest',
        },
        notification_stomp: {
            executor: 'per-vu-iterations', vus: STOMP_VUS, iterations: 1,
            maxDuration: `${DURATION_SECONDS + 60}s`, exec: 'notificationStomp',
        },
    }, chatbot);
}

const P95 = ['p(95)<500'];

export const options = {
    scenarios: scenarios(),
    thresholds: CHATBOT_ONLY ? {} : {
        // 알림은 L1·L2 기준을 만족해야 한다(NT-LOAD-L6)
        'http_req_duration{scenario:notification_rest}': P95,
        'http_req_duration{scenario:notification_rest,name:notification_list}': P95,
        'http_req_duration{scenario:notification_rest,name:notification_unread_count}': P95,
        'server_errors{scenario:notification_rest}': ['rate<0.01'],
        'dropped_iterations{scenario:notification_rest}': ['count<60'],
        'checks{scenario:notification_rest}': ['rate>0.99'],
        'checks{scenario:notification_stomp}': ['rate>0.99'],
        stomp_abnormal_close: ['count<1'],
        stomp_expired_close: ['count<1'],
        stomp_ticket_failed: ['count<1'],
        stomp_connected: [`count>=${STOMP_VUS}`],
    },
    summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

export function setup() {
    const tokens = loginAll(ACCOUNTS, DURATION_SECONDS);
    console.log(`L6 혼합 부하를 측정합니다. 챗봇 GET ${CHATBOT_GET_RPS}/s, LLM ${CHATBOT_LLM_RPS}/s, 챗봇만=${CHATBOT_ONLY}`);
    return { tokens, endMs: Date.now() + DURATION_SECONDS * 1000 };
}

function tokenFor(data, index) {
    return data.tokens[index % data.tokens.length];
}

export function notificationRest(data) {
    const token = tokenFor(data, exec.scenario.iterationInTest);
    if (Math.random() < 0.5) {
        const res = http.get(`${BASE_URL}/api/v1/notifications`,
            { headers: headers(token), tags: { name: 'notification_list' } });
        checkRsData(res, 'notification_list');
    } else {
        const res = http.get(`${BASE_URL}/api/v1/notifications/unread-count`,
            { headers: headers(token), tags: { name: 'notification_unread_count' } });
        checkRsData(res, 'notification_unread_count');
    }
}

export function notificationStomp(data) {
    const index = exec.scenario.iterationInTest;
    staggerSleep(index, STOMP_VUS, STOMP_RAMP);
    holdConnection(tokenFor(data, index), ORIGIN, data.endMs - Date.now(), {});
}

// FAQ 모아보기: LLM·호출 제한 없음
export function chatbotGet(data) {
    const res = http.get(`${BASE_URL}/api/v1/chatbot/faqs`,
        { headers: headers(tokenFor(data, exec.scenario.iterationInTest)), tags: { name: 'chatbot_faq_list' } });
    checkRsData(res, 'chatbot_faq_list');
}

// FAQ 대화(한 번에 받기): LLM을 부른다. 회원마다 호출 제한이 있어 429는 따로 센다
export function chatbotLlm(data) {
    const res = http.post(`${BASE_URL}/api/v1/chatbot/chat/faq`, JSON.stringify({ message: LLM_MESSAGE }),
        { headers: Object.assign(headers(tokenFor(data, exec.scenario.iterationInTest)), { 'Content-Type': 'application/json' }),
            tags: { name: 'chatbot_faq_chat' } });
    if (res.status === 429) {
        chatbotRateLimited.add(1);
        return;
    }
    checkRsData(res, 'chatbot_faq_chat');
}
