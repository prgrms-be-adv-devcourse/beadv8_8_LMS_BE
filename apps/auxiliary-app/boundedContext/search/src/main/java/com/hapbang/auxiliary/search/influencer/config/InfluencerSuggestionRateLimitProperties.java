package com.hapbang.auxiliary.search.influencer.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 인플루언서 검색어 자동완성 호출 제한({@code hapbang.search.influencer-suggestion-rate-limit.*}, #1012). 입력할 때마다 불러 본 검색보다
 * 잦으므로 본 검색 한도({@link InfluencerRateLimitProperties})와 따로 센다. 자동완성을 많이 불러도 본 검색이 막히지 않는다. 본 검색만 막으면
 * 자동완성으로 같은 ES를 채울 수 있어 함께 둔다. 처음 값은 강의 자동완성과 같은 분당 120회이고 429 지표를 보고 고친다.
 *
 * @param member 회원 한 명(회원 ID)의 한도
 */
@Validated
@ConfigurationProperties("hapbang.search.influencer-suggestion-rate-limit")
public record InfluencerSuggestionRateLimitProperties(@DefaultValue @Valid Limit member) {

    /**
     * @param limit   한 주기에 부를 수 있는 횟수
     * @param period  주기
     * @param maxKeys 메모리에 둘 키 수 상한(넘으면 오래 안 쓴 키부터 지운다)
     */
    public record Limit(
            @DefaultValue("120") @Positive int limit,
            @DefaultValue("1m") @NotNull Duration period,
            @DefaultValue("10000") @Positive long maxKeys) {
    }
}
