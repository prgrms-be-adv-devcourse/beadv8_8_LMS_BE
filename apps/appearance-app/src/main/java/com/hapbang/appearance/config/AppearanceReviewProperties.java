package com.hapbang.appearance.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import com.hapbang.appearance.domain.AppearanceReviewPolicy;

/**
 * {@code appearance.yml}의 {@code appearance.review.*} 값을 받는 설정 객체 (#512). {@link AppearanceConfig}가
 * 도메인의 {@link AppearanceReviewPolicy}로 바꿔 빈으로 등록한다. 값 검증은 {@link AppearanceReviewPolicy}가 한다.
 *
 * @param writePeriod 출연 확인 시각부터 리뷰를 쓸 수 있는 기간. 비우면 무기한
 */
@ConfigurationProperties(prefix = "appearance.review")
public record AppearanceReviewProperties(Duration writePeriod) {

    public AppearanceReviewPolicy toPolicy() {
        return new AppearanceReviewPolicy(writePeriod);
    }
}
