package com.hapbang.lecture.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.hapbang.lecture.domain.LectureHpTaskRetryPolicy;

/** 강의 HP 작업 설정과 재시도 정책 빈 (D57 (7)) */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LectureHpTaskProperties.class)
public class LectureHpTaskConfig {

    @Bean
    LectureHpTaskRetryPolicy lectureHpTaskRetryPolicy(LectureHpTaskProperties properties) {
        return new LectureHpTaskRetryPolicy(properties.retryBaseDelay(), properties.retryMaxDelay(),
                properties.retryMaxAttempts(), properties.retryJitterRatio());
    }
}
