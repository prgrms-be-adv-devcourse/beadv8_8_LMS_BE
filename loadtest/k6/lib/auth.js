// 회원 앱 로그인. 로그인 응답은 refresh token 쿠키만 주고, access token은 재발급 API 본문으로 받는다.
import http from 'k6/http';
import { BASE_URL, headers } from './http.js';

// setup()에서 한 번 부른다. 반환값: { token, expiresIn(초) }
export function login(email, password) {
    const jar = http.cookieJar();
    const loginRes = http.post(`${BASE_URL}/api/v1/members/login`,
        JSON.stringify({ email, password }),
        { headers: Object.assign(headers(), { 'Content-Type': 'application/json' }), jar });
    if (loginRes.status !== 200) {
        throw new Error(`로그인에 실패했습니다. status=${loginRes.status}`);
    }
    const refreshRes = http.post(`${BASE_URL}/api/v1/members/auth/refresh`, null, { headers: headers(), jar });
    if (refreshRes.status !== 200) {
        throw new Error(`access token 발급에 실패했습니다. status=${refreshRes.status}`);
    }
    return {
        token: refreshRes.json('data.accessToken'),
        expiresIn: refreshRes.json('data.expiresIn'),
    };
}
