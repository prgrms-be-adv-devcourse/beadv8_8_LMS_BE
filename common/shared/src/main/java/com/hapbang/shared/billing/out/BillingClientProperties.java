package com.hapbang.shared.billing.out;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 지갑 내부 API Client 설정 (hapbang.clients.billing.*, D62). 호출하는 앱마다 billing-app 주소를 준다.
 *
 * @param baseUrl        billing-app 주소 (예: {@code http://localhost:8082})
 * @param connectTimeout 연결 제한 시간. 기본 2초
 * @param readTimeout    응답 제한 시간. 기본 5초
 */
@ConfigurationProperties("hapbang.clients.billing")
public record BillingClientProperties(String baseUrl, Duration connectTimeout, Duration readTimeout) {

    public BillingClientProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("hapbang.clients.billing.base-url 설정이 필요합니다.");
        }
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(2) : connectTimeout;
        readTimeout = readTimeout == null ? Duration.ofSeconds(5) : readTimeout;
    }
}
