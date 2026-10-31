package com.hapbang.billing.out;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

// TossPayments 코어 API 설정이다 (#224).
// - secretKey: 테스트 시크릿 키. 환경 변수 TOSS_PAYMENTS_SECRET_KEY로만 넣는다. 없으면 충전 API가 503으로 거절한다.
// - baseUrl: 기본 https://api.tosspayments.com (테스트는 가짜 서버 주소).
// - connectTimeout·readTimeout: 기본 3초·10초. 승인 응답이 늦으면 결과 불명확으로 보고 결제 조회로 다시 확인한다.
@ConfigurationProperties(prefix = "hapbang.billing.toss")
public record TossPaymentsProperties(String baseUrl, String secretKey, Duration connectTimeout, Duration readTimeout) {

    static final String DEFAULT_BASE_URL = "https://api.tosspayments.com";

    public TossPaymentsProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = DEFAULT_BASE_URL;
        }
        if (connectTimeout == null) {
            connectTimeout = Duration.ofSeconds(3);
        }
        if (readTimeout == null) {
            readTimeout = Duration.ofSeconds(10);
        }
    }

    // 시크릿 키가 설정되어 TossPayments를 호출할 수 있는지 확인한다.
    public boolean isConfigured() {
        return secretKey != null && !secretKey.isBlank();
    }

    // 시크릿 키가 로그·예외 메시지에 찍히지 않게 한다.
    @Override
    public String toString() {
        return "TossPaymentsProperties[baseUrl=" + baseUrl + ", secretKey=" + (isConfigured() ? "****" : "(none)")
                + ", connectTimeout=" + connectTimeout + ", readTimeout=" + readTimeout + "]";
    }
}
