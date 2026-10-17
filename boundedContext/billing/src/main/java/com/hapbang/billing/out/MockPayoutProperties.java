package com.hapbang.billing.out;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

// 가상 송금(Mock 은행) 설정 (#288). 실제 은행·외부 API 주소는 없다.
// - enabled: Mock 은행 API·시나리오 제어를 켠다. 기본 false. 로컬(application-local.yaml)·테스트에서만 true로 두며,
//   true여도 활성 프로필이 전부 local·test가 아니면(다른 프로필이 섞이거나 프로필 미지정) 켜지지 않는다(MockPayoutActivation)
// - baseUrl: HTTP Client가 부를 주소. 비우면 같은 애플리케이션(http://localhost:{실제 서버 포트})을 부른다
// - connectTimeout·readTimeout: HTTP Client 연결·응답 시간 제한(기본 1초·5초). 넘으면 결과 불명확(UNKNOWN)이다
// - delay: DELAY 시나리오의 응답 지연(기본 1초, 응답 시간 제한 안). timeoutDelay: TIMEOUT 시나리오의 응답 지연(기본 8초, 시간 제한 초과)
// - defaultScenario: 시나리오를 지정하지 않은 요청의 결과(기본 SUCCESS)
@ConfigurationProperties(prefix = "hapbang.billing.mock-payout")
public record MockPayoutProperties(boolean enabled, String baseUrl, Duration connectTimeout, Duration readTimeout,
        Duration delay, Duration timeoutDelay, MockPayoutScenario defaultScenario) {

    public MockPayoutProperties {
        if (connectTimeout == null) {
            connectTimeout = Duration.ofSeconds(1);
        }
        if (readTimeout == null) {
            readTimeout = Duration.ofSeconds(5);
        }
        if (delay == null) {
            delay = Duration.ofSeconds(1);
        }
        if (timeoutDelay == null) {
            timeoutDelay = Duration.ofSeconds(8);
        }
        if (defaultScenario == null) {
            defaultScenario = MockPayoutScenario.SUCCESS;
        }
    }
}
