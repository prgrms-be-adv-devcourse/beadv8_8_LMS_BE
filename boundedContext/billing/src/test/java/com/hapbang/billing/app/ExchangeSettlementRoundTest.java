package com.hapbang.billing.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

// 환전 잠정 정산 예정일(#285)을 DB 없이 검증한다. 정산 기준일 선택과 주말 보정을 따로 검증하고, 둘을 합친 결과(KST 00:00 저장값)도 확인한다.
// 공휴일은 반영하지 않는다(잠정값, 실제 송금일은 정산 Scheduler가 정한다). 계좌번호 마스킹도 여기서 함께 검증한다.
class ExchangeSettlementRoundTest {

    // ---------------------------------------------------------------------------------------------
    // 1) 정산 기준일 선택(주말 보정 전)
    // ---------------------------------------------------------------------------------------------

    @ParameterizedTest
    @CsvSource({
            // 신청일(KST), 정산 기준일
            "2026-10-01, 2026-10-10",
            "2026-10-09, 2026-10-10",
            "2026-10-10, 2026-11-10", // 10일 당일은 다음 달
            "2026-10-11, 2026-11-10",
            "2026-10-31, 2026-11-10",
            "2026-12-10, 2027-01-10", // 해를 넘긴다
            "2026-12-31, 2027-01-10",
            "2027-01-31, 2027-02-10",
    })
    @DisplayName("기준일: 신청일이 1~9일이면 당월 10일, 10일 이후(당일 포함)면 다음 달 10일")
    void baseDate(LocalDate requestedOn, LocalDate expected) {
        assertThat(ExchangeSettlementRound.baseDate(requestedOn)).isEqualTo(expected);
    }

    // ---------------------------------------------------------------------------------------------
    // 2) 주말 보정
    // ---------------------------------------------------------------------------------------------

    @ParameterizedTest
    @CsvSource({
            // 기준일, 보정 결과
            "2026-10-10, 2026-10-12", // 토 → 다음 월요일 12일
            "2027-01-10, 2027-01-11", // 일 → 다음 월요일 11일
            "2026-11-10, 2026-11-10", // 화 → 그대로
            "2026-12-10, 2026-12-10", // 목 → 그대로
            "2027-07-10, 2027-07-12", // 토
            "2027-10-10, 2027-10-11", // 일
    })
    @DisplayName("주말 보정: 토요일이면 다음 월요일(+2일), 일요일이면 다음 월요일(+1일), 평일은 그대로")
    void adjustForWeekend(LocalDate date, LocalDate expected) {
        LocalDate adjusted = ExchangeSettlementRound.adjustForWeekend(date);

        assertThat(adjusted).isEqualTo(expected);
        assertThat(adjusted.getDayOfWeek()).isNotIn(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY);
    }

    @Test
    @DisplayName("주말 보정은 월~금 모든 요일을 그대로 두고, 공휴일(예: 2026-10-09 한글날 금요일)도 보정하지 않는다")
    void weekdaysAndHolidaysUnchanged() {
        for (LocalDate day = LocalDate.parse("2026-11-09"); day.isBefore(LocalDate.parse("2026-11-14"));
                day = day.plusDays(1)) {
            assertThat(ExchangeSettlementRound.adjustForWeekend(day)).isEqualTo(day);
        }
        assertThat(ExchangeSettlementRound.adjustForWeekend(LocalDate.parse("2026-10-09")))
                .isEqualTo(LocalDate.parse("2026-10-09"));
    }

    // ---------------------------------------------------------------------------------------------
    // 3) 저장값: 기준일 선택 + 주말 보정, 그 날 00:00 KST
    // ---------------------------------------------------------------------------------------------

    @ParameterizedTest
    @CsvSource({
            // 신청 시각(UTC), 잠정 정산 예정일(KST)
            "2026-10-09T03:00:00Z, 2026-10-12", // 10-09(금) 신청 → 기준 10-10(토) → 10-12(월)
            "2026-10-10T03:00:00Z, 2026-11-10", // 10-10(토) 신청 → 11-10(화)
            "2026-10-16T03:00:00Z, 2026-11-10", // 10-16(금) 신청 → 11-10(화)
            "2026-11-09T03:00:00Z, 2026-11-10", // 11-09(월) 신청 → 11-10(화)
            "2026-12-20T03:00:00Z, 2027-01-11", // 연도 변경: 기준 2027-01-10(일) → 01-11(월)
            // KST 날짜 경계
            "2026-10-09T14:59:59Z, 2026-10-12", // KST 10-09 23:59:59 → 당월
            "2026-10-09T15:00:00Z, 2026-11-10", // KST 10-10 00:00:00 → 다음 달
            "2026-12-31T14:59:59Z, 2027-01-11", // KST 12-31 23:59:59
            "2026-12-31T15:00:00Z, 2027-01-11", // KST 2027-01-01 00:00 → 당월(1월) 10일(일) → 11일
    })
    @DisplayName("잠정 정산 예정 시각은 KST 신청일로 기준일을 고르고 주말을 보정한 날짜의 00:00 KST다")
    void provisionalPayoutAt(Instant requestedAt, LocalDate expected) {
        Instant scheduledAt = ExchangeSettlementRound.of(requestedAt);

        assertThat(ExchangeSettlementRound.dateOf(scheduledAt)).isEqualTo(expected);
        assertThat(scheduledAt).isEqualTo(expected.atStartOfDay(ExchangeSettlementRound.KST).toInstant());
        assertThat(scheduledAt).isAfter(requestedAt);
    }

    // ---------------------------------------------------------------------------------------------
    // 계좌번호 마스킹
    // ---------------------------------------------------------------------------------------------

    @ParameterizedTest
    @CsvSource({"12345678, ****5678", "12345678901234, **********1234", "12345678901234567890, ****************7890"})
    @DisplayName("계좌번호는 끝 4자리만 남기고 *로 가린다")
    void mask(String accountNumber, String expected) {
        assertThat(ExchangeAccountCipher.mask(accountNumber)).isEqualTo(expected);
    }
}
