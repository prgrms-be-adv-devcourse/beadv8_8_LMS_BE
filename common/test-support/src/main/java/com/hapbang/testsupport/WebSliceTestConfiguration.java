package com.hapbang.testsupport;

import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.context.annotation.Configuration;

/** MVC 설정 테스트가 운영 앱의 컴포넌트·DB 설정을 함께 스캔하지 않도록 하는 진입점. */
@TestComponent
@Configuration(proxyBeanMethods = false)
@EnableAutoConfiguration
public class WebSliceTestConfiguration {
}
