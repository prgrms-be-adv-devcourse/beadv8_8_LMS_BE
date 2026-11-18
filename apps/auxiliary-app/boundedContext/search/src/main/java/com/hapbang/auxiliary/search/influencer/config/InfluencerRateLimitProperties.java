package com.hapbang.auxiliary.search.influencer.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 인플루언서 검색 호출 제한({@code hapbang.search.influencer-rate-limit.*}, #1012). 처음 값은 분당 60회다. #993 운영 부하 측정에서 초당
 * 50건까지 p95 500ms 안이었고 초당 100건에서 공용 node CPU가 차서 넘었다. 회원 한 명이 초당 1건이면 50명이 동시에 최대로 불러야 그 선을
 * 넘는다. 429 지표를 보고 고친다. 로그인 전용이라 비회원 한도는 없다.
 *
 * @param member 회원 한 명(회원 ID)의 한도
 */
@Validated
@ConfigurationProperties("hapbang.search.influencer-rate-limit")
public record InfluencerRateLimitProperties(@DefaultValue @Valid Limit member) {

    /**
     * @param limit   한 주기에 부를 수 있는 횟수
     * @param period  주기
     * @param maxKeys 메모리에 둘 키 수 상한(넘으면 오래 안 쓴 키부터 지운다)
     */
    public record Limit(
            @DefaultValue("60") @Positive int limit,
            @DefaultValue("1m") @NotNull Duration period,
            @DefaultValue("10000") @Positive long maxKeys) {
    }
}
