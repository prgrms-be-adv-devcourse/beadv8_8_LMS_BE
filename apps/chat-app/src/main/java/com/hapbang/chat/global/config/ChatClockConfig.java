package com.hapbang.chat.global.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 채팅의 현재 시각. 연결 티켓·연결 만료·이벤트 재발행처럼 {@code Instant}로 시각을 재는 빈이 이 시계를 주입받는다.
 * 시각은 UTC다.
 */
@Configuration(proxyBeanMethods = false)
public class ChatClockConfig {

    @Bean
    Clock chatClock() {
        return Clock.systemUTC();
    }
}
