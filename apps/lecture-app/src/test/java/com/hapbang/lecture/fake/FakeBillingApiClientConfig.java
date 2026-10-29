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

    /**
     * 성공한 보류를 {@link FakeBillingPort}에도 남겨 같은 지갑처럼 동작하게 한다. 보류는 HP 작업(#276)이, 구매 취소·반환·지급은
     * 아직 {@code BillingPort}가 하므로, 주문으로 만든 보류를 반환·지급할 수 있어야 한다. #277에서 반환·지급을 옮기면 없앤다
     */
    @Bean
    FakeBillingApiClient fakeBillingApiClient(FakeBillingPort fakeBillingPort) {
        FakeBillingApiClient client = new FakeBillingApiClient();
        client.onHeld(hold -> fakeBillingPort.assumeHeld(hold.userId(), hold.referenceId(), hold.amountHp()));
        return client;
    }
}
