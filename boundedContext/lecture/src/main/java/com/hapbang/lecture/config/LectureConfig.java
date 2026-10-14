package com.hapbang.lecture.config;

import java.time.Clock;
import java.time.ZoneId;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 강의 모듈 설정. 주문 기한(자동 구매 확정·지급 예정 시각)은 한국 시간 기준이므로(설계 5장),
 * 서버 기본 시간대와 관계없이 현재 시각은 {@link #CLOCK} 빈으로 얻는다 (D37).
 */
@Configuration(proxyBeanMethods = false)
public class LectureConfig {

    /** 다른 모듈의 Clock 빈과 겹치지 않도록 이름을 구분한다. 주입할 때는 {@code @Qualifier(CLOCK)}를 붙인다. */
    public static final String CLOCK = "lectureClock";

    public static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    // 이름(@Qualifier)으로 주입할 때만 쓰인다. 타입만으로 Clock 하나를 찾는 다른 빈(Spring Modulith 이벤트 발행 기록 등)이
    // 두 Clock 사이에서 고르지 못해 앱이 뜨지 않는 것을 막는다.
    @Bean(name = CLOCK, defaultCandidate = false)
    Clock lectureClock() {
        return Clock.system(ZONE);
    }
}
