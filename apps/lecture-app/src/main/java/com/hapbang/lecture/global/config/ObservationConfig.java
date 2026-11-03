package com.hapbang.lecture.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.micrometer.observation.ObservationPredicate;

/**
 * 관리 포트(actuator) 요청이 trace를 만들지 않게 한다 (#579).
 * <p>
 * 관리 포트에는 HTTP 요청 observation이 없어 Spring Security filter chain observation({@code spring.security.*})이
 * 부모 없이 시작된다. 그대로 두면 probe(5초마다 3종)와 Prometheus 수집마다 root trace가 Tempo에 쌓인다.
 * 업무 요청에서는 HTTP 요청 observation 아래에서 시작되므로 그 trace에는 남는다.
 */
@Configuration(proxyBeanMethods = false)
public class ObservationConfig {

    @Bean
    ObservationPredicate noRootSecurityObservation() {
        return (name, context) -> !(name.startsWith("spring.security.") && context.getParentObservation() == null);
    }
}
