package com.hapbang.billing.app;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;

// 환전 신청의 잠정 정산 예정일 (#285). 모두 KST(Asia/Seoul) 날짜로 계산하고 그 날 00:00 KST로 저장한다.
// 1) 정산 기준일 선택: 신청일이 1~9일이면 당월 10일, 10일 이후(10일 당일 포함)면 다음 달 10일
// 2) 주말 보정: 기준일이 토·일요일이면 다음 월요일
// 공휴일은 반영하지 않는다. 그래서 신청 때 값은 잠정 정산 예정일이다.
// 관리자 승인 때 회차를 확정한다(#305, forApproval): 정산일 00:00 KST 전에 승인된 환전만 그 회차, 그 뒤 승인은 다음 회차.
// 공휴일 보정은 아직 없다.
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

    // 관리자 승인 때 확정하는 정산 회차 (#305). 정산일 전(그 정산일 00:00 KST 직전까지)에 승인된 환전만 그 회차에 들어간다.
    // 승인이 잠정 정산 예정 시각보다 앞이면 잠정 회차 그대로, 그 시각 또는 이후면 승인 시각 이후 첫 회차로 옮긴다(주말 보정 포함).
    static Instant forApproval(Instant provisionalScheduledAt, Instant approvedAt) {
        return approvedAt.isBefore(provisionalScheduledAt) ? provisionalScheduledAt : firstRoundAfter(approvedAt);
    }

    // 주어진 시각 뒤에 오는 첫 정산 회차 시각. 그 달 정산일(주말이면 다음 월요일) 00:00 KST가 아직 오지 않았으면 그 회차,
    // 이미 왔으면(같은 시각 포함) 다음 달 회차다. 주말 보정으로 정산일이 11·12일이 되어도 그 날 00:00 전이면 그 달 회차다.
    static Instant firstRoundAfter(Instant at) {
        LocalDate date = LocalDate.ofInstant(at, KST);
        Instant thisMonth = roundStart(date.withDayOfMonth(SETTLEMENT_DAY));
        return thisMonth.isAfter(at) ? thisMonth : roundStart(date.plusMonths(1).withDayOfMonth(SETTLEMENT_DAY));
    }

    // 정산 기준일(10일)의 주말 보정 날짜 00:00 KST
    private static Instant roundStart(LocalDate settlementDay) {
        return adjustForWeekend(settlementDay).atStartOfDay(KST).toInstant();
    }

    // 저장한 예정 시각의 KST 날짜(응답용)
    static LocalDate dateOf(Instant scheduledAt) {
        return LocalDate.ofInstant(scheduledAt, KST);
    }
}
