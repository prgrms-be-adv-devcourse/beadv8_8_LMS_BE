package com.hapbang.member.out;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 치지직 Open API 설정 (치지직 개발자 센터 > 애플리케이션).
 * 앱 하나에 리디렉션 URL을 하나만 등록할 수 있어 환경(local·dev·prod)마다 앱과 redirectUri가 다르다
 */
@ConfigurationProperties(prefix = "hapbang.chzzk")
public record ChzzkProperties(String clientId, String clientSecret, String redirectUri) {
}
