// 알림 STOMP 연결 유지 L2(N13·N15, ND-157). 데이터를 쓰지 않는다: 연결 티켓 발급(30초 일회용 Redis 키, 회원마다 60초에
// 10번까지)과 연결·구독·heart-beat만 한다(N13 결정). 알림 생성·수신 측정(L3)은 N15에서 정한다.
// 연결 수를 늘리는 단계별 프로필은 티켓 발급 한도 때문에 시험 계정 여러 개가 필요해 N15에서 더한다.
import http from 'k6/http';
import ws from 'k6/ws';
import { check } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import { BASE_URL, headers, requiredEnv } from '../lib/http.js';
import { login } from '../lib/auth.js';

const PROFILE = __ENV.PROFILE || 'smoke';
// 운영 알림 WebSocket이 허용한 출처(FE 주소). 레포에 적지 않고 환경 변수로 받는다
const ORIGIN = requiredEnv('WS_ORIGIN');
const HOLD_SECONDS = Number(__ENV.HOLD_SECONDS || 30);
const HEARTBEAT_MS = 25000;

const connected = new Counter('stomp_connected');
const abnormalClose = new Counter('stomp_abnormal_close');
const connectTime = new Trend('stomp_connect_ms', true);

const PROFILES = {
    smoke: {
        main: { executor: 'per-vu-iterations', vus: 1, iterations: 1, maxDuration: `${HOLD_SECONDS + 30}s` },
    },
};

if (!PROFILES[PROFILE]) {
    throw new Error(`알 수 없는 PROFILE=${PROFILE}. smoke`);
}

export const options = {
    scenarios: PROFILES[PROFILE],
    thresholds: {
        'checks{scenario:main}': ['rate>0.99'],
        stomp_abnormal_close: ['count<1'],
        stomp_connect_ms: ['p(95)<2000'],
    },
    summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

export function setup() {
    const { token } = login(requiredEnv('LT_EMAIL'), requiredEnv('LT_PASSWORD'));
    return { token };
}

export default function (data) {
    const ticketRes = http.post(`${BASE_URL}/api/v1/notifications/ws-tickets`, null,
        { headers: headers(data.token), tags: { name: 'notification_ws_ticket' } });
    if (!check(ticketRes, { 'ws ticket 200': (r) => r.status === 200 })) {
        return;
    }
    const ticket = ticketRes.json('data.ticket');
    const url = `${BASE_URL.replace(/^http/, 'ws')}/api/v1/notifications/ws?ticket=${encodeURIComponent(ticket)}`;
    const start = Date.now();
    let gotConnected = false;
    let subscribed = false;

    const res = ws.connect(url, { headers: { Origin: ORIGIN, 'User-Agent': 'hapbang-loadtest-k6' },
        tags: { name: 'notification_ws' } }, (socket) => {
        socket.on('open', () => {
            socket.send(`CONNECT\naccept-version:1.2\nhost:hapbang\nheart-beat:${HEARTBEAT_MS},${HEARTBEAT_MS}\n\n\0`);
        });
        socket.on('message', (frame) => {
            if (!gotConnected && frame.startsWith('CONNECTED')) {
                gotConnected = true;
                connectTime.add(Date.now() - start);
                connected.add(1);
                socket.send('SUBSCRIBE\nid:sub-0\ndestination:/user/queue/notifications\n\n\0');
                subscribed = true;
            }
        });
        socket.setInterval(() => socket.send('\n'), HEARTBEAT_MS);
        socket.on('close', (code) => {
            // 정상 종료(1000)와 연결 만료(4401, ND-056) 밖의 종료를 센다. 1006은 비정상 끊김이다
            if (code !== 1000 && code !== 4401 && code !== undefined) {
                abnormalClose.add(1, { code: String(code) });
            }
        });
        socket.setTimeout(() => socket.close(1000), HOLD_SECONDS * 1000);
    });

    check(res, { 'ws 101': (r) => r && r.status === 101 });
    check(null, { 'stomp CONNECTED': () => gotConnected, 'stomp SUBSCRIBE 보냄': () => subscribed });
}
