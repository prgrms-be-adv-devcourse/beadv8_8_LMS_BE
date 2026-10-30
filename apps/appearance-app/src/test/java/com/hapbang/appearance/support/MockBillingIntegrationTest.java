package com.hapbang.appearance.support;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.hapbang.appearance.out.billing.BillingClient;
import com.hapbang.testsupport.IntegrationTest;

/**
 * Mock Billing 서버를 쓰는 출연 통합 테스트의 부모 (#391, #274). 테스트 클래스가 이 클래스를 상속한다.
 * <ul>
 *     <li>{@link AppearanceIntegrationTest}와 같되 {@link BillingClient}를 Mockito 대역으로 바꾸지 않는다. 운영과 같은 구현
 *     ({@code BillingHttpClient} → shared {@code BillingApiClient})이 HTTP로 {@link MockBilling}을 부른다.</li>
 *     <li>{@code hapbang.clients.billing.base-url}을 {@link MockBilling} 주소로 넣는다. 그러면 shared {@code BillingApiClient} 빈이 생긴다.</li>
 *     <li>Client 응답 대기 시간은 {@value #READ_TIMEOUT}로 짧게 둔다 (지연 시나리오를 빨리 끝내려고).</li>
 *     <li>설정({@code @DynamicPropertySource})을 이 클래스 한 곳에 둔다. 테스트 클래스마다 따로 두면 설정이 같아도
 *     Spring 테스트 컨텍스트가 클래스마다 새로 떠서 느려진다. 상속하면 같은 컨텍스트를 같이 쓴다.</li>
 *     <li>테스트마다 {@link MockBilling#reset()}으로 받은 요청·응답 설정을 지우고 모든 경로를 성공으로 되돌린다.</li>
 *     <li>공용 Client의 Circuit Breaker는 열리지 않게 둔다(최소 호출 수를 크게). 상속한 테스트가 한 Client를 같이 써서
 *     앞 테스트의 실패가 쌓여 회로가 열리면 뒤 테스트가 요청을 보내지 못한다. 회로 동작은 shared {@code BillingApiClientTest}가 확인한다.</li>
 * </ul>
 */
@IntegrationTest
@Import(AppearanceTestClockConfig.class)
@ExtendWith(AppearanceTestClock.ResetExtension.class)
public abstract class MockBillingIntegrationTest {

    static final String READ_TIMEOUT = "1s";
    private static final int CIRCUIT_NEVER_OPENS = 10_000;

    @DynamicPropertySource
    static void mockBilling(DynamicPropertyRegistry registry) {
        registry.add("hapbang.clients.billing.base-url", MockBilling::baseUrl);
        registry.add("hapbang.clients.billing.read-timeout", () -> READ_TIMEOUT);
        registry.add("hapbang.clients.billing.resilience.circuit-breaker.sliding-window-size", () -> CIRCUIT_NEVER_OPENS);
        registry.add("hapbang.clients.billing.resilience.circuit-breaker.minimum-number-of-calls", () -> CIRCUIT_NEVER_OPENS);
    }

    @BeforeEach
    void resetMockBilling() {
        MockBilling.reset();
    }
}
