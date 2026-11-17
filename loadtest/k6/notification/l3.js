// 알림 L3 저장→실시간 전달(N15, NT-LOAD-L3, ND-188·ND-193·ND-195). B0 격리 cluster 안에서만 돈다(운영에 쓰지 않는다).
// Gateway가 없어 Gateway가 넣는 사용자 헤더(X-User-Id·X-User-Role)를 직접 준다. 앱의 연결 티켓 발급(30초 1회용, 회원당 60초 10회),
// 연결 때 티켓 검증·출처 검사·연결 만료는 그대로 거친다(ND-195). JWT 서명 검증은 거치지 않는다.
// VU 하나가 회원 한 명이다(L3_FIRST_USER + VU - 1). 받은 알림 프레임마다 "L3RECV <알림 ID> <수신 epoch ms> <VU>"를 표준 출력에 남긴다.
// 실행: n15.sh l3가 k6 pod로 띄운다. 환경 변수: L3_BASE_URL, L3_ORIGIN, L3_FIRST_USER, L3_USERS, L3_HOLD_SECONDS
import http from 'k6/http';
import ws from 'k6/ws';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

const BASE_URL = __ENV.L3_BASE_URL;
const ORIGIN = __ENV.L3_ORIGIN;
const FIRST_USER = Number(__ENV.L3_FIRST_USER);
const USERS = Number(__ENV.L3_USERS || 100);
const HOLD_SECONDS = Number(__ENV.L3_HOLD_SECONDS || 480);
const HEARTBEAT_MS = 25000;

const abnormalClose = new Counter('stomp_abnormal_close');

export const options = {
    scenarios: {
        main: { executor: 'per-vu-iterations', vus: USERS, iterations: 1, maxDuration: `${HOLD_SECONDS + 60}s` },
    },
    summaryTrendStats: ['avg', 'min', 'med', 'p(95)', 'p(99)', 'max'],
};

// STOMP MESSAGE 프레임 본문(빈 줄 뒤, NUL 앞)의 알림 ID
function notificationId(frame) {
    const body = frame.substring(frame.indexOf('\n\n') + 2).replace(/\0$/, '');
    return JSON.parse(body).notification.id;
}

export default function () {
    const userId = FIRST_USER + __VU - 1;
    const userHeaders = { 'X-User-Id': String(userId), 'X-User-Role': 'USER' };
    const ticketRes = http.post(`${BASE_URL}/api/v1/notifications/ws-tickets`, null, { headers: userHeaders });
    if (!check(ticketRes, { 'ws ticket 200': (r) => r.status === 200 })) {
        console.log(`L3TICKETFAIL ${__VU} ${ticketRes.status}`);
        return;
    }
    const ticket = ticketRes.json('data.ticket');
    const url = `${BASE_URL.replace(/^http/, 'ws')}/api/v1/notifications/ws?ticket=${encodeURIComponent(ticket)}`;
    const res = ws.connect(url, { headers: { Origin: ORIGIN } }, (socket) => {
        socket.on('open', () => {
            socket.send(`CONNECT\naccept-version:1.2\nhost:hapbang\nheart-beat:${HEARTBEAT_MS},${HEARTBEAT_MS}\n\n\0`);
        });
        socket.on('message', (frame) => {
            const at = Date.now();
            if (frame.startsWith('CONNECTED')) {
                socket.send('SUBSCRIBE\nid:sub-0\ndestination:/user/queue/notifications\n\n\0');
                console.log(`L3CONN ${__VU} ${at}`);
            } else if (frame.startsWith('MESSAGE')) {
                console.log(`L3RECV ${notificationId(frame)} ${at} ${__VU}`);
            }
        });
        socket.setInterval(() => socket.send('\n'), HEARTBEAT_MS);
        socket.on('close', (code) => {
            console.log(`L3CLOSE ${__VU} ${code} ${Date.now()}`);
            if (code !== 1000 && code !== 4401 && code !== undefined) {
                abnormalClose.add(1);
            }
        });
        socket.setTimeout(() => socket.close(1000), HOLD_SECONDS * 1000);
    });
    check(res, { 'ws 101': (r) => r && r.status === 101 });
}
