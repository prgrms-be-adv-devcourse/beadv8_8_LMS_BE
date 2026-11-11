package com.hapbang.testsupport.billing;

import java.util.List;
import java.util.Set;

import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.test.context.ContextConfigurationAttributes;
import org.springframework.test.context.ContextCustomizer;
import org.springframework.test.context.ContextCustomizerFactory;
import org.springframework.test.context.MergedContextConfiguration;

import com.hapbang.shared.billing.out.BillingApiClient;

/** 명시적으로 켠 강의·쿠폰 앱 통합 테스트만 실제 billing 프로세스에 연결한다. */
public class RealBillingContextCustomizerFactory implements ContextCustomizerFactory {
    @Override
    public ContextCustomizer createContextCustomizer(Class<?> testClass, List<ContextConfigurationAttributes> attributes) {
        return Boolean.getBoolean("hapbang.billing.e2e.enabled")
                && AnnotatedElementUtils.hasAnnotation(testClass, SpringBootTest.class) ? Customizer.INSTANCE : null;
    }

    private enum Customizer implements ContextCustomizer {
        INSTANCE;
        private static final Set<String> OBSERVED = Set.of("hold", "release", "payout", "voidHold", "queryHolds",
                "listHolds", "settlePenalty");

        @Override
        public void customizeContext(ConfigurableApplicationContext context, MergedContextConfiguration configuration) {
            RealBillingTestFixture fixture = new RealBillingTestFixture(RealBillingServer.get());
            TestPropertyValues.of("hapbang.clients.billing.base-url=" + fixture.baseUrl(),
                    "hapbang.internal-api.key=test-internal-api-key").applyTo(context.getEnvironment());
            context.getBeanFactory().registerSingleton("realBillingTestFixture", fixture);
            context.getBeanFactory().addBeanPostProcessor(new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String name) {
                    if (!(bean instanceof BillingApiClient target)) return bean;
                    ProxyFactory proxy = new ProxyFactory(target);
                    proxy.setProxyTargetClass(true);
                    proxy.addAdvice((MethodInterceptor) invocation -> {
                        if (OBSERVED.contains(invocation.getMethod().getName()))
                            fixture.beforeCall(invocation.getMethod().getName(), invocation.getArguments());
                        return invocation.proceed();
                    });
                    BillingApiClient observed = (BillingApiClient) proxy.getProxy();
                    fixture.attach(observed, target);
                    return observed;
                }
            });
        }
    }
}
