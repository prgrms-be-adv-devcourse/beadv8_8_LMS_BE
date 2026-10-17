package com.hapbang.shared.appearance.out;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 출연 내부 API Client 설정 (hapbang.clients.appearance.*, D62). 단일 앱은 자기 주소, 서비스를 나누면 출연 서비스 주소다.
 *
 * @param baseUrl        출연 서비스 주소 (예: {@code http://localhost:8080})
 * @param connectTimeout 연결 제한 시간. 기본 2초
 * @param readTimeout    응답 제한 시간. 기본 5초
 */
@ConfigurationProperties("hapbang.clients.appearance")
public record AppearanceClientProperties(String baseUrl, Duration connectTimeout, Duration readTimeout) {

    public AppearanceClientProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("hapbang.clients.appearance.base-url 설정이 필요합니다.");
        }
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(2) : connectTimeout;
        readTimeout = readTimeout == null ? Duration.ofSeconds(5) : readTimeout;
    }
}
