package com.hapbang.lecture.fake;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 모듈 테스트 컨텍스트에 {@link FakeBillingApiClient}를 등록한다 (#275).
 * {@code @Component}로 스캔하면 부모 {@code BillingApiClient}의 {@code @EnableConfigurationProperties}까지 처리돼
 * base-url이 없는 테스트에서 설정 바인딩이 실패하므로 {@code @Bean} 메서드로 만든다.
 */
@Configuration(proxyBeanMethods = false)
public class FakeBillingApiClientConfig {

    @Bean
    FakeBillingApiClient fakeBillingApiClient() {
        return new FakeBillingApiClient();
    }
}
