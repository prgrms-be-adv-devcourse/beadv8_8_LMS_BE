package com.hapbang.lecture.global.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 내부 API 설정 (hapbang.internal-api.*).
 *
 * @param key 모듈 간 내부 HTTP API 키. 공개 레포라 서버는 환경 변수(HAPBANG_INTERNAL_API_KEY)로만 받는다.
 *            비어 있으면 앱이 시작하지 않는다 (#300)
 */
@ConfigurationProperties("hapbang.internal-api")
record InternalApiProperties(String key) {

    InternalApiProperties {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("hapbang.internal-api.key 설정이 필요합니다.");
        }
    }

    @Override
    public String toString() {
        // 설정 오류 로그 등에 키가 찍히지 않게 한다
        return "InternalApiProperties[key=****]";
    }
}
