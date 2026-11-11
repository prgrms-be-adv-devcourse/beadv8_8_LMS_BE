package com.hapbang.testsupport.billing;

import org.springframework.test.context.TestContext;
import org.springframework.test.context.support.AbstractTestExecutionListener;

/** 테스트 트랜잭션을 시작하기 전에 이번 테스트 전용 billing 상태를 초기화한다. */
public class RealBillingResetListener extends AbstractTestExecutionListener {
    @Override
    public int getOrder() { return 2_900; }

    @Override
    public void beforeTestMethod(TestContext context) {
        // spring.factories로 모든 앱의 테스트에 붙으므로 실제 billing을 켠 실행에서만 빈을 찾는다.
        if (!Boolean.getBoolean("hapbang.billing.e2e.enabled")) return;
        var fixtures = context.getApplicationContext().getBeansOfType(RealBillingTestFixture.class);
        fixtures.values().forEach(RealBillingTestFixture::reset);
    }

    @Override
    public void afterTestMethod(TestContext context) {
        if (context.getTestException() != null) RealBillingServer.markTestFailed();
    }
}
