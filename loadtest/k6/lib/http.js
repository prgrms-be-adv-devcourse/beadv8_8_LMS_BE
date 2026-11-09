// 여러 시나리오가 같이 쓰는 HTTP 도우미. 실행 방법은 docs/load-test/README.md.
import { check } from 'k6';
import { Rate } from 'k6/metrics';

export const BASE_URL = (__ENV.BASE_URL || 'https://hapbang.duckdns.org').replace(/\/$/, '');

// Envoy access log에서 부하 요청을 거를 수 있게 한다
export const USER_AGENT = 'hapbang-loadtest-k6';

// 5xx 비율. http_req_failed는 4xx도 실패로 세서 "5xx 1% 미만" 기준에 따로 둔다
export const serverErrors = new Rate('server_errors');

export function requiredEnv(name) {
    const value = __ENV[name];
    if (!value) {
        throw new Error(`환경 변수 ${name}가 필요합니다.`);
    }
    return value;
}

export function headers(token) {
    const result = { 'User-Agent': USER_AGENT, Accept: 'application/json' };
    if (token) {
        result.Authorization = `Bearer ${token}`;
    }
    return result;
}

// RsData(statusCode, message, data) 성공 응답인지 본다. 이름은 요약의 check 이름이 된다
export function checkRsData(res, name) {
    serverErrors.add(res.status >= 500, { name });
    return check(res, {
        [`${name} 200`]: (r) => r.status === 200,
        [`${name} RsData`]: (r) => {
            try {
                return r.json('statusCode') === 200;
            } catch (e) {
                return false;
            }
        },
    });
}
