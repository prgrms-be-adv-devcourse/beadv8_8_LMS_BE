package com.hapbang.billing.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

// 강의 판매 수익 지급 가능 시각(구매일 KST + 31일 00:00 KST)을 DB 없이 검증한다.
class LectureSettlementScheduleTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @ParameterizedTest(name = "{0} KST 구매 → {1} 00:00 KST")
    @CsvSource({
            // 일반 날짜: 10-14 ~ 11-12(30일) 보호, 11-13 00:00부터 지급
            "2026-10-13T15:00:00, 2026-11-13",
            // 새벽·자정 직전·자정: KST 날짜로 센다
            "2026-10-13T00:00:00, 2026-11-13",
            "2026-10-13T00:30:00, 2026-11-13",
            "2026-10-13T23:59:59.999999, 2026-11-13",
            "2026-10-14T00:00:00, 2026-11-14",
            // 월말·연말
            "2026-01-31T10:00:00, 2026-03-03",
            "2026-11-30T12:00:00, 2026-12-31",
            "2026-12-01T12:00:00, 2027-01-01",
            "2026-12-31T23:00:00, 2027-01-31",
            // 윤년(2028년 2월 29일)과 평년
            "2028-02-01T09:00:00, 2028-03-03",
            "2027-02-01T09:00:00, 2027-03-04",
            "2028-02-29T09:00:00, 2028-03-31",
            "2028-01-30T09:00:00, 2028-03-01"})
    @DisplayName("구매 시각의 KST 날짜 + 31일 00:00 KST부터 지급할 수 있다")
    void payableAt(LocalDateTime purchasedAtKst, String payableDate) {
        Instant purchasedAt = purchasedAtKst.atZone(KST).toInstant();

        Instant payableAt = LectureSettlementSchedule.payableAt(purchasedAt);

        assertThat(payableAt).isEqualTo(LocalDateTime.parse(payableDate + "T00:00:00").atZone(KST).toInstant());
    }

    @Test
    @DisplayName("KST 자정 직후 구매는 UTC로는 전날이어도 KST 날짜로 세므로 UTC 날짜에 30일을 더한 결과와 다르다")
    void kstDateNotUtcDate() {
        // 2026-10-14 00:30 KST = 2026-10-13 15:30 UTC
        Instant purchasedAt = Instant.parse("2026-10-13T15:30:00Z");

        Instant payableAt = LectureSettlementSchedule.payableAt(purchasedAt);

        // 2026-11-14 00:00 KST = 2026-11-13 15:00 UTC (UTC 날짜 10-13 기준이면 11-13 00:00 KST가 된다)
        assertThat(payableAt).isEqualTo(Instant.parse("2026-11-13T15:00:00Z"));
    }

    @Test
    @DisplayName("보호 기간 30일 차의 마지막 순간에는 지급할 수 없고, 다음 날 00:00 KST(UTC 전날 15:00)부터 지급할 수 있다")
    void protectionEndsExactly() {
        Instant purchasedAt = LocalDateTime.parse("2026-10-13T15:00:00").atZone(KST).toInstant();

        Instant payableAt = LectureSettlementSchedule.payableAt(purchasedAt);

        assertThat(payableAt).isEqualTo(Instant.parse("2026-11-12T15:00:00Z"));
        assertThat(payableAt.minusNanos(1).atZone(KST).toLocalDateTime())
                .isEqualTo(LocalDateTime.parse("2026-11-12T23:59:59.999999999"));
    }
}
