package com.hapbang.appearance.support;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.hapbang.appearance.out.billing.BillingClient;
import com.hapbang.testsupport.IntegrationTest;

/**
 * 출연 모듈 통합 테스트 (#289). 팀 공통 {@link IntegrationTest}에 Billing 대역을 더한다.
 * <ul>
 *     <li>출연 모듈 테스트에는 Billing 모듈이 없다. {@link BillingClient}를 Mockito 대역으로 바꿔, 기본은 성공(반환값 null)으로 둔다.</li>
 *     <li>대역은 Billing 규칙(잔액·보류·멱등)을 흉내 내지 않는다. 테스트는 출연 업무 규칙과 Billing에 보낸 요청 내용만 확인한다
 *     ({@link BillingCalls}). 실패는 {@link BillingStubs}로 만든다.</li>
 *     <li>돈이 실제로 맞게 움직이는지는 apps의 {@code AppearanceBillingFlowIntegrationTest}(실제 Billing)가 확인한다.</li>
 *     <li>대역은 테스트마다 초기화된다 (Mockito 기본 동작).</li>
 * </ul>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@IntegrationTest
@MockitoBean(types = BillingClient.class)
public @interface AppearanceIntegrationTest {
}
