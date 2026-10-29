package com.hapbang.customersupport.global.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("hapbang.internal-api")
record InternalApiPortProperties(int port) {
    InternalApiPortProperties {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("내부 API 포트는 1~65535여야 합니다.");
        }
    }
}
