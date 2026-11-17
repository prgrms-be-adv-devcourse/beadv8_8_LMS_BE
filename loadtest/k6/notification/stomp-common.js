// 알림 STOMP 연결 유지와 여러 시험 계정 로그인 도우미(L2·L6, N15). 운영 쓰기를 하지 않는다: 연결 티켓 발급·연결·구독·heart-beat만 한다.
// 실행 방법과 계정 파일은 docs/load-test/notification-load.md "운영 실행 방법".
import http from 'k6/http';
import ws from 'k6/ws';
import { check, sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import { BASE_URL, headers } from '../lib/http.js';
import { login } from '../lib/auth.js';

export const HEARTBEAT_MS = 25000;

// 비밀번호 로그인은 IP당 분당 10회다(#506). 한 번에 로그인하는 계정 수를 이 값 이하로 둔다
export const MAX_ACCOUNTS = 10;
// 연결 티켓은 회원마다 60초에 10번이다(NT-RT-05). 시각 흔들림을 생각해 계정마다 60초에 8번까지만 쓴다
const TICKETS_PER_ACCOUNT_PER_MINUTE = 8;

const connected = new Counter('stomp_connected');
const abnormalClose = new Counter('stomp_abnormal_close');
// 연결 만료(4401, ND-056)로 닫힌 연결. 토큰 수명 안에서 끝나게 짠 프로필(L2·L6, ND-198)에서는 0이어야 한다
const expiredClose = new Counter('stomp_expired_close');
const ticketFailed = new Counter('stomp_ticket_failed');
const connectTime = new Trend('stomp_connect_ms', true);

// 계정 파일(JSON 배열 [{"email","password"}])을 init 단계에서 읽는다. 내용은 로그에 쓰지 않는다
export function loadAccounts(path) {
    const accounts = JSON.parse(open(path));
    if (!Array.isArray(accounts) || accounts.length === 0) {
        throw new Error('계정 파일이 비었거나 배열이 아닙니다.');
    }
    if (accounts.length > MAX_ACCOUNTS) {
        throw new Error(`계정은 ${MAX_ACCOUNTS}개까지 씁니다(로그인 IP 한도). 지금 ${accounts.length}개`);
    }
    accounts.forEach((a, i) => {
        if (!a || !a.email || !a.password) {
            throw new Error(`계정 파일 ${i}번째 항목에 email·password가 없습니다.`);
        }
    });
    return accounts;
}

// 단계마다 연결을 ramp초에 나눠 열 때 계정이 충분한지 본다. 60초 창에 드는 연결 수 = min(연결 수, 연결 수 × 60 / ramp)이고,
// 계정에 고르게 나누므로 계정마다 그 수를 계정 수로 나눈 값(올림)이다. 모자라면 시험을 시작하지 않는다
export function requiredAccounts(stages) {
    const perMinute = Math.max(...stages.map((s) => Math.min(s.vus, (s.vus * 60) / s.ramp)));
    return Math.ceil(perMinute / TICKETS_PER_ACCOUNT_PER_MINUTE);
}

export function assertTicketBudget(accounts, stages) {
    const need = requiredAccounts(stages);
    if (accounts.length < need) {
        throw new Error(`계정이 ${accounts.length}개라 티켓 발급 한도(회원마다 60초에 10번)를 넘는다. ${need}개 이상 필요하다`);
    }
}

// setup()에서 부른다. 모든 계정의 access token 남은 시간이 needSeconds + 60초 이상인지 확인한다
export function loginAll(accounts, needSeconds) {
    const started = Date.now();
    const tokens = accounts.map((a) => login(a.email, a.password));
    const elapsed = (Date.now() - started) / 1000;
    const minRemaining = Math.min(...tokens.map((t) => t.expiresIn)) - elapsed;
    if (minRemaining - 60 < needSeconds) {
        throw new Error(`access token 남은 시간(${Math.floor(minRemaining)}초)이 측정 시간(${needSeconds}초) + 60초보다 짧습니다.`);
    }
    return tokens.map((t) => t.token);
}

// 연결 하나를 열어 holdMs 동안 유지한다. 클라이언트가 계획대로 닫기 전에 닫히면 종료 코드가 1000이어도 유지 실패다:
// 4401(연결 만료)은 stomp_expired_close, 그 밖은 stomp_abnormal_close(1006 포함)로 센다
export function holdConnection(token, origin, holdMs, tags) {
    const ticketRes = http.post(`${BASE_URL}/api/v1/notifications/ws-tickets`, null,
        { headers: headers(token), tags: Object.assign({ name: 'notification_ws_ticket' }, tags) });
    if (!check(ticketRes, { 'ws ticket 200': (r) => r.status === 200 })) {
        ticketFailed.add(1, { status: String(ticketRes.status) });
        return;
    }
    const ticket = ticketRes.json('data.ticket');
    const url = `${BASE_URL.replace(/^http/, 'ws')}/api/v1/notifications/ws?ticket=${encodeURIComponent(ticket)}`;
    const start = Date.now();
    let gotConnected = false;
    let subscribed = false;
    let plannedClose = false;

    const res = ws.connect(url, { headers: { Origin: origin, 'User-Agent': 'hapbang-loadtest-k6' },
        tags: Object.assign({ name: 'notification_ws' }, tags) }, (socket) => {
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
            if (plannedClose) {
                return;
            }
            if (code === 4401) {
                expiredClose.add(1);
            } else {
                abnormalClose.add(1, { code: String(code) });
            }
        });
        socket.setTimeout(() => {
            plannedClose = true;
            socket.close(1000);
        }, Math.max(holdMs, 1000));
    });

    check(res, { 'ws 101': (r) => r && r.status === 101 });
    check(null, { 'stomp CONNECTED': () => gotConnected, 'stomp SUBSCRIBE 보냄': () => subscribed });
}

// 단계 시작 뒤 rampSeconds 안에 연결을 고르게 나눠 연다(티켓 발급 한도: 회원마다 60초에 10번)
export function staggerSleep(indexInStage, stageSize, rampSeconds) {
    sleep((indexInStage / stageSize) * rampSeconds);
}
