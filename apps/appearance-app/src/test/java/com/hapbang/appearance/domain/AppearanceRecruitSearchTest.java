package com.hapbang.appearance.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AppearanceRecruitSearchTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 21);

    private static AppearanceRecruitSearch keyword(String keyword) {
        return new AppearanceRecruitSearch(keyword, null, null, null, null, false, null);
    }

    private static AppearanceRecruitSearch scheduled(LocalDate from, LocalDate to) {
        return new AppearanceRecruitSearch(null, from, to, null, null, false, null);
    }

    private static AppearanceRecruitSearch fee(Long min, Long max) {
        return new AppearanceRecruitSearch(null, null, null, min, max, false, null);
    }

    private static void assertInvalid(Runnable action, String detail) {
        assertThatThrownBy(action::run)
                .isInstanceOf(AppearanceException.class)
                .hasMessage(detail)
                .extracting("errorCode").isEqualTo(AppearanceErrorCode.INVALID_REQUEST);
    }

    @Test
    @DisplayName("조건 없음(NONE)은 모든 조건이 비어 있고 최신순이다. 정렬을 비우면 최신순이다")
    void none() {
        assertThat(AppearanceRecruitSearch.NONE).isEqualTo(
                new AppearanceRecruitSearch(null, null, null, null, null, false, AppearanceRecruitSort.LATEST));
        assertThat(keyword(null).sort()).isEqualTo(AppearanceRecruitSort.LATEST);
        assertThat(AppearanceRecruitSearch.NONE.scheduledAtFrom()).isNull();
        assertThat(AppearanceRecruitSearch.NONE.scheduledAtBefore()).isNull();
    }

    @Test
    @DisplayName("검색어는 앞뒤 공백을 지우고, 비거나 공백뿐이면 조건 없음(null)이다")
    void keywordStripped() {
        assertThat(keyword("  게임 합방  ").keyword()).isEqualTo("게임 합방");
        assertThat(keyword("").keyword()).isNull();
        assertThat(keyword(" \t ").keyword()).isNull();
    }

    @Test
    @DisplayName("검색어는 공백을 지운 뒤 50자까지 허용하고 51자면 INVALID_REQUEST")
    void keywordLength() {
        String fifty = "가".repeat(AppearanceRecruitSearch.KEYWORD_MAX);

        assertThat(keyword("  " + fifty + "  ").keyword()).isEqualTo(fifty);
        assertInvalid(() -> keyword(fifty + "나"), "검색어는 50자 이하여야 합니다.");
    }

    @Test
    @DisplayName("방송 날짜 범위는 양 끝 날짜를 포함한다: 시작 날짜 0시 이상, 끝 날짜 다음 날 0시 미만")
    void scheduledRange() {
        AppearanceRecruitSearch search = scheduled(DAY, DAY);

        assertThat(search.scheduledAtFrom()).isEqualTo(LocalDateTime.of(2026, 10, 21, 0, 0));
        assertThat(search.scheduledAtBefore()).isEqualTo(LocalDateTime.of(2026, 10, 22, 0, 0));
        assertThat(scheduled(DAY, null).scheduledAtBefore()).isNull();
        assertThat(scheduled(null, DAY).scheduledAtFrom()).isNull();
    }

    @Test
    @DisplayName("방송 날짜 범위의 시작이 끝보다 늦으면 INVALID_REQUEST")
    void scheduledRangeReversed() {
        assertInvalid(() -> scheduled(DAY.plusDays(1), DAY),
                "방송 날짜 범위의 시작(scheduledFrom)이 끝(scheduledTo)보다 늦습니다.");
    }

    @ParameterizedTest(name = "{0}년")
    @ValueSource(ints = {0, 10_000})
    @DisplayName("연도가 네 자리(0001~9999)가 아닌 날짜는 INVALID_REQUEST")
    void yearOutOfRange(int year) {
        LocalDate date = LocalDate.of(year, 1, 1);

        assertInvalid(() -> scheduled(date, null), "방송 날짜는 yyyy-MM-dd 형식(0001~9999년)이어야 합니다.");
        assertInvalid(() -> scheduled(null, date), "방송 날짜는 yyyy-MM-dd 형식(0001~9999년)이어야 합니다.");
    }

    @Test
    @DisplayName("출연료 범위는 0 이상이고 최소와 최대가 같아도 된다")
    void feeRange() {
        AppearanceRecruitSearch search = fee(0L, 0L);

        assertThat(search.minFee()).isZero();
        assertThat(fee(30_000L, 30_000L).maxFee()).isEqualTo(30_000L);
    }

    @Test
    @DisplayName("음수 출연료나 최소가 최대보다 큰 출연료 범위는 INVALID_REQUEST")
    void feeInvalid() {
        assertInvalid(() -> fee(-1L, null), "출연료 조건(minFee·maxFee)은 0 이상이어야 합니다.");
        assertInvalid(() -> fee(null, -1L), "출연료 조건(minFee·maxFee)은 0 이상이어야 합니다.");
        assertInvalid(() -> fee(30_001L, 30_000L), "최소 출연료(minFee)가 최대 출연료(maxFee)보다 큽니다.");
    }
}
