package com.hapbang.auxiliary.global.config;

import java.time.Clock;
import java.time.ZoneId;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 앱 공통 시계. 모든 일시는 KST다(DB {@code timestamp} + {@code LocalDateTime}). 테스트는 이 빈을 바꿔 시각을 고정한다 (#715) */
@Configuration(proxyBeanMethods = false)
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.system(ZoneId.of("Asia/Seoul"));
    }
}
