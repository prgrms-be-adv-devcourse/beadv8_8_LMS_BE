package com.hapbang.billing.app;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;

// 환전 신청의 잠정 정산 예정일 (#285). 모두 KST(Asia/Seoul) 날짜로 계산하고 그 날 00:00 KST로 저장한다.
// 1) 정산 기준일 선택: 신청일이 1~9일이면 당월 10일, 10일 이후(10일 당일 포함)면 다음 달 10일
// 2) 주말 보정: 기준일이 토·일요일이면 다음 월요일
// 공휴일은 반영하지 않는다. 그래서 이 값은 잠정 정산 예정일이며 실제 송금일을 보장하지 않는다.
// 공휴일 보정·지급 대상 마감 기준·관리자 승인 시점에 따른 회차 확정은 정산 Scheduler Issue에서 최종 반영한다(AGENTS.md 11장 계약).
final class ExchangeSettlementRound {

    static final ZoneId KST = ZoneId.of("Asia/Seoul");
    static final int SETTLEMENT_DAY = 10;

    private ExchangeSettlementRound() {
    }

    // 신청 시각의 잠정 정산 예정 시각(주말 보정한 날짜의 00:00 KST)
    static Instant of(Instant requestedAt) {
        return adjustForWeekend(baseDate(LocalDate.ofInstant(requestedAt, KST))).atStartOfDay(KST).toInstant();
    }

    // 정산 기준일(매월 10일) 선택. 신청일(KST)이 10일보다 앞이면 당월, 아니면 다음 달
    static LocalDate baseDate(LocalDate requestedOn) {
        return requestedOn.getDayOfMonth() < SETTLEMENT_DAY
                ? requestedOn.withDayOfMonth(SETTLEMENT_DAY)
                : requestedOn.plusMonths(1).withDayOfMonth(SETTLEMENT_DAY);
    }

    // 토·일요일이면 다음 월요일로 보정한다. 평일은 그대로다. 공휴일은 보지 않는다
    static LocalDate adjustForWeekend(LocalDate date) {
        DayOfWeek day = date.getDayOfWeek();
        return day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY
                ? date.with(TemporalAdjusters.next(DayOfWeek.MONDAY))
                : date;
    }

    // 저장한 예정 시각의 KST 날짜(응답용)
    static LocalDate dateOf(Instant scheduledAt) {
        return LocalDate.ofInstant(scheduledAt, KST);
    }
}
