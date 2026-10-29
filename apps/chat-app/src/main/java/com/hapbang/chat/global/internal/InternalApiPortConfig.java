package com.hapbang.chat.global.internal;

import org.apache.catalina.connector.Connector;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** k8s에서만 내부 API listener를 분리한다. 미설정 시 기존 단일 포트 동작을 유지한다. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "hapbang.internal-api.port")
@EnableConfigurationProperties(InternalApiPortProperties.class)
class InternalApiPortConfig {

    @Bean
    WebServerFactoryCustomizer<TomcatServletWebServerFactory> internalApiConnector(InternalApiPortProperties properties) {
        return factory -> {
            int port = properties.port();
            if (port == factory.getPort()) {
                throw new IllegalArgumentException("내부 API 포트는 외부 API 포트와 달라야 합니다.");
            }
            Connector connector = new Connector("org.apache.coyote.http11.Http11NioProtocol");
            connector.setPort(port);
            factory.addAdditionalConnectors(connector);
            factory.addContextValves(new InternalApiPortValve(port));
        };
    }
}
