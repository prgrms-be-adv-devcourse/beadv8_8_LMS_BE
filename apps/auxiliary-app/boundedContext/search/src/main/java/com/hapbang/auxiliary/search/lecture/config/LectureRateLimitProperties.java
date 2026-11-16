package com.hapbang.auxiliary.search.lecture.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 강의 검색 호출 제한({@code hapbang.search.lecture-rate-limit.*}, #883 L18). 처음 값은 둘 다 분당 60회이고 k6·429 지표를 보고 고친다.
 *
 * @param member 회원 한 명(회원 ID)의 한도
 * @param guest  비회원 한 명(client IP, IPv6는 /64)의 한도
 */
@Validated
@ConfigurationProperties("hapbang.search.lecture-rate-limit")
public record LectureRateLimitProperties(@DefaultValue @Valid Limit member, @DefaultValue @Valid Limit guest) {

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
