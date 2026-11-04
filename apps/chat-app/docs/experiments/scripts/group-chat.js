import { WebSocket } from 'k6/websockets';
import { setInterval, clearInterval, setTimeout } from 'k6/timers';
import http from 'k6/http';
import { check } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import execution from 'k6/execution';

const people = Number(__ENV.CHAT_K6_PEOPLE);
const rate = Number(__ENV.CHAT_K6_RATE);
const seconds = Number(__ENV.CHAT_K6_SECONDS);
if (!Number.isInteger(people) || !(people >= 2 && people <= 200)) throw new Error('Total room members must be 2..200');
if (!Number.isInteger(rate) || rate < 1 || rate > 100) throw new Error('Rate must be 1..100');
if (!Number.isInteger(seconds) || seconds < 1 || seconds > 300) throw new Error('Seconds must be 1..300');
const total = rate * seconds;
const base = __ENV.CHAT_K6_URL;
const room = __ENV.CHAT_K6_ROOM;
const start = Number(__ENV.CHAT_K6_START);
const prefix = __ENV.CHAT_K6_PREFIX;
const initialSequence = Number(__ENV.CHAT_K6_SEQUENCE);
const auth = __ENV.CHAT_K6_AUTH_FILE ? JSON.parse(open(__ENV.CHAT_K6_AUTH_FILE)) : null;
if (!auth && !/^http:\/\/(localhost|127\.0\.0\.1):\d+$/.test(base)) {
  throw new Error('Synthetic user headers are restricted to loopback; remote tests require JWT input');
}
if (auth && (auth.accounts.length !== people || !/^https:\/\//.test(base))) {
  throw new Error('Remote tests require HTTPS and one authenticated member per VU');
}
function stopRemote(reason) { if (auth) execution.test.abort(reason); }

const received = new Counter('chat_received');
const sent = new Counter('chat_sent');
const duplicate = new Counter('chat_duplicate');
const errors = new Counter('chat_errors');
const ready = new Counter('chat_connected');
const readFrames = new Counter('chat_read_frames');
const skipped = new Counter('chat_receipt_skipped');
const latency = new Trend('chat_receive_ms', true);
const lag = new Trend('chat_send_lag_ms', true);
export const options = {
  scenarios: { room: { executor: 'per-vu-iterations', vus: people, iterations: 1, maxDuration: `${seconds + 50}s` } },
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(95)', 'p(99)'],
  thresholds: {
    checks: ['rate==1'], http_req_failed: ['rate==0'],
    chat_received: [`count==${total * people}`], chat_sent: [`count==${total}`],
    chat_connected: [`count==${people}`], chat_duplicate: ['count==0'], chat_errors: ['count==0'],
  },
};
function frame(command, headers = {}, body = '') {
  return command + '\n' + Object.entries(headers).map(([k, v]) => `${k}:${v}`).join('\n') + '\n\n' + body + '\0';
}
export default async function () {
  duplicate.add(0); errors.add(0); skipped.add(0);
  const userId = __VU === 1 ? 1 : 10000 + __VU - 2;
  const member = auth ? auth.accounts[__VU - 1] : null;
  const version = member ? member.participationVersion : 1;
  const headers = member ? { Authorization: 'Bearer ' + member.token } :
    { 'X-User-Id': String(userId), 'X-User-Role': 'INFLUENCER' };
  const params = { headers: { ...headers, 'Content-Type': 'application/json' }, timeout: '10s' };
  const ticket = await http.asyncRequest('POST', base + '/api/v1/chats/ws-tickets', null, params);
  if (!check(ticket, { 'ticket 200': r => r.status === 200 })) {
    console.error(`ticket failure status=${ticket.status} error_code=${ticket.error_code} `);
    stopRemote('Connection ticket rejected');
    return;
  }
  const socket = new WebSocket(base.replace(/^http/, 'ws') + '/api/v1/chats/ws?ticket=' + ticket.json('data.ticket'), ['v12.stomp'], { headers: { Origin: __ENV.CHAT_K6_ORIGIN || 'http://localhost:5173' } });
  const seen = new Set();
  let next = 0, lastUpdated = initialSequence, inFlight = false, connected = false, buffer = '', sendIndex = 0;
  socket.onopen = () => socket.send(frame('CONNECT', { 'accept-version': '1.2', host: base.replace(/^https?:\/\//, ''), 'heart-beat': '0,0' }));
  socket.onerror = () => { errors.add(1); stopRemote('WebSocket error'); };
  socket.onmessage = event => {
    buffer += event.data;
    let end;
    while ((end = buffer.indexOf('\0')) >= 0) {
      const raw = buffer.slice(0, end).replace(/^\n+/, ''); buffer = buffer.slice(end + 1);
      const split = raw.indexOf('\n\n');
      const lines = raw.slice(0, split).split('\n');
      const command = lines.shift();
      const headers = Object.fromEntries(lines.map(line => { const i = line.indexOf(':'); return [line.slice(0, i), line.slice(i + 1)]; }));
      if (command === 'CONNECTED') {
        connected = true; ready.add(1);
        socket.send(frame('SUBSCRIBE', { id: 'room', destination: `/topic/chats/rooms/${room}`, 'participation-version': String(version) }));
        socket.send(frame('SUBSCRIBE', { id: 'errors', destination: '/user/queue/chats/errors' }));
      } else if (command === 'ERROR') { errors.add(1); stopRemote('STOMP error'); }
      else if (command === 'MESSAGE') {
        if (headers.subscription === 'errors') { errors.add(1); stopRemote('Server error/control frame'); continue; }
        if (headers['chat-frame-type'] === 'READ_COUNTS') { readFrames.add(1); continue; }
        if (headers['chat-frame-type']) { errors.add(1); stopRemote('Server error/control frame'); continue; }
        const body = JSON.parse(raw.slice(split + 2));
        if (!String(body.content).startsWith(prefix)) {
          if (body.roomSequence > initialSequence) stopRemote('Unexpected concurrent room activity');
          continue;
        }
        const [indexText, sentAt] = body.content.slice(prefix.length).split(':');
        const index = Number(indexText);
        if (auth && (!Number.isInteger(index) || index < 0 || index >= total ||
            body.roomSequence !== initialSequence + index + 1)) {
          errors.add(1); stopRemote('Unexpected message order/sequence');
        }
        if (seen.has(index)) { duplicate.add(1); continue; }
        seen.add(index); received.add(1); latency.add(Date.now() - Number(sentAt));
        while (seen.has(next)) next++;
      }
    }
  };
  const sendTimer = setInterval(() => {
    if (__VU !== 1 || !connected || sendIndex >= total) return;
    const due = start + sendIndex * 1000 / rate;
    if (Date.now() < due) return;
    lag.add(Date.now() - due);
    const body = JSON.stringify({ content: `${prefix}${sendIndex}:${Date.now()}:` + '가'.repeat(100), clientMessageId: prefix + sendIndex });
    socket.send(frame('SEND', { destination: `/pub/chats/${room}/messages`, 'content-type': 'application/json' }, body));
    sent.add(1); sendIndex++;
  }, 5);
  const receiptTimer = setInterval(async () => {
    const sequence = initialSequence + next;
    if (!connected || sequence <= lastUpdated) return;
    if (inFlight) { skipped.add(1); return; }
    inFlight = true;
    try {
      const responses = await Promise.all([
        http.asyncRequest('PUT', `${base}/api/v1/chats/${room}/read`, JSON.stringify({ lastReadSequence: sequence, participationVersion: version }), params),
        http.asyncRequest('PUT', `${base}/api/v1/chats/${room}/received`, JSON.stringify({ receivedSequence: sequence, participationVersion: version }), params),
      ]);
      const ok = check(responses, { 'read and ACK 2xx': rs => rs.every(r => r.status >= 200 && r.status < 300) });
      if (ok) lastUpdated = sequence;
      else stopRemote('Read/received request failed');
    } finally { inFlight = false; }
  }, 1000);
  await new Promise(resolve => {
    socket.onclose = () => {
      clearInterval(sendTimer); clearInterval(receiptTimer);
      connected = false;
      if (seen.size !== total) stopRemote('Connection closed before all messages arrived');
      check(seen.size, { 'all room messages received': n => n === total });
      resolve();
    };
    setTimeout(() => socket.close(), Math.max(1000, start + seconds * 1000 + 10000 - Date.now()));
  });
}
export function handleSummary(data) {
  return { [__ENV.CHAT_K6_SUMMARY]: JSON.stringify(data, null, 2) };
}
