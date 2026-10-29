package com.hapbang.appearance.support;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Mock Billing 서버를 쓰는 출연 통합 테스트의 부모 (#391). 테스트 클래스가 이 클래스를 상속한다.
 * <ul>
 *     <li>{@link AppearanceIntegrationTest}에 더해 {@code hapbang.clients.billing.base-url}을 {@link MockBilling} 주소로 넣는다.
 *     그러면 shared {@code BillingApiClient} 빈이 생긴다.</li>
 *     <li>Client 응답 대기 시간은 {@value #READ_TIMEOUT}로 짧게 둔다 (지연 시나리오를 빨리 끝내려고).</li>
 *     <li>설정({@code @DynamicPropertySource})을 이 클래스 한 곳에 둔다. 테스트 클래스마다 따로 두면 설정이 같아도
 *     Spring 테스트 컨텍스트가 클래스마다 새로 떠서 느려진다. 상속하면 같은 컨텍스트를 같이 쓴다.</li>
 *     <li>테스트마다 {@link MockBilling#reset()}으로 받은 요청·응답 설정을 지우고 모든 경로를 성공으로 되돌린다.</li>
 * </ul>
 */
@AppearanceIntegrationTest
public abstract class MockBillingIntegrationTest {

    static final String READ_TIMEOUT = "1s";

    @DynamicPropertySource
    static void mockBilling(DynamicPropertyRegistry registry) {
        registry.add("hapbang.clients.billing.base-url", MockBilling::baseUrl);
        registry.add("hapbang.clients.billing.read-timeout", () -> READ_TIMEOUT);
    }

    @BeforeEach
    void resetMockBilling() {
        MockBilling.reset();
    }
}
