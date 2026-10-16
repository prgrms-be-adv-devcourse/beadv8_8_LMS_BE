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
    // 4) 관리자 승인 때 정산 회차 확정 (#305): 정산일 00:00 KST 전 승인만 그 회차, 당일·이후 승인은 다음 회차
    //    2026-10-10(토)→10-12(월), 2026-11-10(화), 2026-12-10(목), 2027-01-10(일)→01-11(월), 2026-05-10(일)→05-11(월)
    // ---------------------------------------------------------------------------------------------

    @ParameterizedTest
    @CsvSource({
            // 잠정 정산일, 승인 시각(KST), 확정 정산일
            // 정산일이 평일(11-10 화)
            "2026-11-10, 2026-11-09T23:59:59.999999, 2026-11-10", // 정산일 전날 마지막 순간 → 그 회차
            "2026-11-10, 2026-11-10T00:00:00, 2026-12-10",        // 정산일 00:00 정각 → 다음 회차
            "2026-11-10, 2026-11-10T09:00:00, 2026-12-10",        // 정산일 당일 → 다음 회차
            "2026-11-10, 2026-11-15T10:00:00, 2026-12-10",        // 정산일 이후 → 다음 회차
            "2026-11-10, 2026-11-05T10:00:00, 2026-11-10",        // 정산일 전 → 그 회차
            // 10일이 토요일(10-10 → 10-12 월): 주말 보정한 정산일 00:00이 마감
            "2026-10-12, 2026-10-10T15:00:00, 2026-10-12",        // 10일(토) 승인 → 그 회차(12일 월)
            "2026-10-12, 2026-10-11T23:59:59, 2026-10-12",        // 11일(일) 밤 → 그 회차
            "2026-10-12, 2026-10-12T00:00:00, 2026-11-10",        // 12일(월) 00:00 → 다음 회차
            // 10일이 일요일(05-10 → 05-11 월)
            "2026-05-11, 2026-05-10T12:00:00, 2026-05-11",        // 10일(일) 승인 → 그 회차(11일 월)
            "2026-05-11, 2026-05-11T09:00:00, 2026-06-10",        // 11일(월) 승인 → 다음 회차
            // 연말·연초: 12-10(목) 다음 회차는 2027-01-10(일) → 01-11(월)
            "2026-12-10, 2026-12-10T10:00:00, 2027-01-11",
            "2026-12-10, 2026-12-31T23:59:59, 2027-01-11",
            "2027-01-11, 2027-01-10T12:00:00, 2027-01-11",        // 2027-01-10(일) 승인 → 그 회차(11일 월)
            "2027-01-11, 2027-01-11T00:00:00, 2027-02-10",
            // 여러 달 늦은 승인: 승인 시각 뒤 가장 가까운 회차(건너뛴 회차로 돌아가지 않는다)
            "2026-10-12, 2026-11-03T10:00:00, 2026-11-10",
            "2026-10-12, 2026-12-11T10:00:00, 2027-01-11",
    })
    @DisplayName("승인 회차: 정산일 00:00 KST 전 승인이면 잠정 회차 그대로, 그 시각·이후 승인이면 승인 뒤 첫 회차(주말 보정·연도 변경 포함)")
    void forApproval(LocalDate provisional, java.time.LocalDateTime approvedKst, LocalDate expected) {
        Instant provisionalAt = provisional.atStartOfDay(ExchangeSettlementRound.KST).toInstant();
        Instant approvedAt = approvedKst.atZone(ExchangeSettlementRound.KST).toInstant();

        Instant round = ExchangeSettlementRound.forApproval(provisionalAt, approvedAt);

        assertThat(ExchangeSettlementRound.dateOf(round)).isEqualTo(expected);
        assertThat(round).isEqualTo(expected.atStartOfDay(ExchangeSettlementRound.KST).toInstant());
        assertThat(round).isAfter(approvedAt);
        assertThat(round.atZone(ExchangeSettlementRound.KST).getDayOfWeek()).isNotIn(DayOfWeek.SATURDAY,
                DayOfWeek.SUNDAY);
    }

    @Test
    @DisplayName("승인 뒤 첫 회차는 항상 승인 시각보다 뒤이고 평일 00:00 KST다(2026~2027년 매시간 확인)")
    void firstRoundAfterIsAlwaysFutureWeekday() {
        Instant at = LocalDate.of(2026, 1, 1).atStartOfDay(ExchangeSettlementRound.KST).toInstant();
        Instant end = LocalDate.of(2028, 1, 1).atStartOfDay(ExchangeSettlementRound.KST).toInstant();
        for (; at.isBefore(end); at = at.plusSeconds(3_600)) {
            Instant round = ExchangeSettlementRound.firstRoundAfter(at);
            assertThat(round).isAfter(at);
            assertThat(round.atZone(ExchangeSettlementRound.KST).toLocalTime()).isEqualTo(java.time.LocalTime.MIDNIGHT);
            assertThat(round.atZone(ExchangeSettlementRound.KST).getDayOfWeek()).isNotIn(DayOfWeek.SATURDAY,
                    DayOfWeek.SUNDAY);
            // 한 달 안쪽(다음 달 정산일까지) 회차다
            assertThat(round).isBefore(at.plus(java.time.Duration.ofDays(36)));
        }
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
