package com.hapbang.testsupport;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationExcludeFilter;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/** 앱의 실행 설정을 가져오지 않고 현재 테스트 클래스패스의 업무 코드만 조립한다. */
@TestComponent
@SpringBootConfiguration(proxyBeanMethods = false)
@EnableAutoConfiguration
@ComponentScan(basePackages = "com.hapbang", excludeFilters = {
        @ComponentScan.Filter(type = FilterType.CUSTOM, classes = TypeExcludeFilter.class),
        @ComponentScan.Filter(type = FilterType.CUSTOM, classes = AutoConfigurationExcludeFilter.class),
        @ComponentScan.Filter(type = FilterType.ANNOTATION, classes = SpringBootApplication.class),
        // JPA auditing은 테스트 컨텍스트에서 한 번만 켠다.
        @ComponentScan.Filter(type = FilterType.ANNOTATION, classes = EnableJpaAuditing.class)
})
@AutoConfigurationPackage(basePackages = "com.hapbang")
@EnableJpaAuditing
public class ModuleTestConfiguration {
}
