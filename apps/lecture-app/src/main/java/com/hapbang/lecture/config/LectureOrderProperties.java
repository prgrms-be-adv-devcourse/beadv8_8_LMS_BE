package com.hapbang.lecture.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 강의 주문 설정 ({@code lecture.order.*}). 값을 주지 않으면 기본값이다.
 *
 * @param holdRequestBudget 주문 요청 안에서 보류·보상 반환을 시도하는 시간 예산. 넘으면 남은 작업은 배치에 넘긴다(D68). 기본 10초
 */
@ConfigurationProperties("lecture.order")
public record LectureOrderProperties(Duration holdRequestBudget) {

    public LectureOrderProperties {
        holdRequestBudget = holdRequestBudget == null ? Duration.ofSeconds(10) : holdRequestBudget;
    }
}
