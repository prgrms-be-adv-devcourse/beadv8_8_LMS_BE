package com.hapbang.testsupport;

import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.context.annotation.Configuration;

/**
 * MVC 설정 테스트가 운영 앱의 컴포넌트·DB 설정을 함께 스캔하지 않도록 하는 진입점.
 * <p>
 * {@code @WebMvcTest} 같은 슬라이스 테스트는 {@code @OverrideAutoConfiguration(enabled = false)}로 자동 설정을
 * 슬라이스 범위로 제한하므로, 여기의 {@code @EnableAutoConfiguration}은 Kafka·Redis·DataSource 자동 설정을 켜지 않는다.
 */
@TestComponent
@Configuration(proxyBeanMethods = false)
@EnableAutoConfiguration
public class WebSliceTestConfiguration {
}
