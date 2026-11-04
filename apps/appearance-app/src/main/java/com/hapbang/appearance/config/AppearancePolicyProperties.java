package com.hapbang.appearance.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import com.hapbang.appearance.domain.AppearancePolicy;

/**
 * {@code appearance.yml}의 {@code appearance.policy.*} 값을 받는 설정 객체. {@link AppearanceConfig}가
 * 도메인의 {@link AppearancePolicy}로 바꿔 빈으로 등록한다.
 * <p>
 * 설정 바인딩(Spring Boot)은 여기서만 하고, 도메인의 정책 객체는 Spring을 모르게 둔다.
 * 값 검증은 {@link AppearancePolicy}가 하므로 값이 틀리면 앱이 시작되지 않는다.
 */
@ConfigurationProperties(prefix = "appearance.policy")
public record AppearancePolicyProperties(
        long minimumFee,
        int penaltyRatePercent,
        Duration confirmPeriod,
        int maxCancelRequestCount,
        int maxRecruitCount
) {

    public AppearancePolicy toPolicy() {
        return new AppearancePolicy(minimumFee, penaltyRatePercent, confirmPeriod, maxCancelRequestCount,
                maxRecruitCount);
    }
}
