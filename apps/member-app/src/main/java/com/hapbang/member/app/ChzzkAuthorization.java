package com.hapbang.member.app;

import java.time.Duration;

/**
 * 치지직 연동 시작 결과 (#370). authorizeUrl은 프론트가 이동할 치지직 화면,
 * browserId는 이 브라우저에서 시작했음을 콜백에서 확인하려고 쿠키로 주는 값, ttl은 그 유효 시간
 */
public record ChzzkAuthorization(String authorizeUrl, String browserId, Duration ttl) {
}
