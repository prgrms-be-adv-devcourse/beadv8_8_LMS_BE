package com.hapbang.chat.out.member;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 회원 내부 API 호출 설정 ({@code hapbang.clients.member.*}). 다른 앱의 공통 HTTP Client와 같은 키·기본값을 쓴다.
 * 주소는 환경 값이라 yaml의 환경 변수 자리표시자({@code MEMBER_API_BASE_URL})로 준다.
 *
 * @param baseUrl        회원 서비스 주소 (예: {@code http://localhost:8081})
 * @param connectTimeout 연결 제한 시간. 기본 2초
 * @param readTimeout    응답 제한 시간. 기본 5초
 */
@ConfigurationProperties("hapbang.clients.member")
public record ChatMemberApiProperties(String baseUrl, Duration connectTimeout, Duration readTimeout) {

    public static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(2);
    public static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(5);

    public ChatMemberApiProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("hapbang.clients.member.base-url 설정이 필요합니다.");
        }
        connectTimeout = connectTimeout == null ? DEFAULT_CONNECT_TIMEOUT : connectTimeout;
        readTimeout = readTimeout == null ? DEFAULT_READ_TIMEOUT : readTimeout;
    }
}
