package com.hapbang.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;

import com.hapbang.testsupport.PostgresTestcontainersConfig;

// main application.yaml은 배치를 켜지만, apps 테스트는 test 리소스(config/application.yaml)가 덮어써서 꺼진다.
// 배치가 테스트 데이터를 바꾸지 않게 한다
@SpringBootTest
@Import({PostgresTestcontainersConfig.class, SchedulingProbe.class})
class SchedulingDisabledInTestsTest {

    @Autowired
    ApplicationContext context;

    @Test
    void apps_테스트에서는_배치_빈이_만들어지지_않는다() {
        assertThat(context.getEnvironment().getProperty("hapbang.scheduling.enabled", Boolean.class)).isFalse();
        assertThat(context.getBeansOfType(SchedulingProbe.ProbeBatch.class)).isEmpty();
    }
}
