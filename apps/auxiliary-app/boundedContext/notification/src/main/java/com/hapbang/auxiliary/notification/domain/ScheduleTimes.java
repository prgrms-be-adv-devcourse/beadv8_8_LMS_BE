package com.hapbang.auxiliary.notification.domain;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/**
 * 예약 알림의 보낼 시각과 업무 기한(알림 종류 7장). 출연은 시각 기준, 강의는 날짜 기준이다(policy.md "기한 계산").
 * <ul>
 *     <li>업무 기한은 예약 알림이 알리는 기한이다. 지나면 그 예약 알림을 보내지 않는다(ND-135). 기한과 같은 시각이면 지난 것이다(ND-144).</li>
 *     <li>출연 시각은 KST {@code LocalDateTime}이다(출연 앱 "모든 기간·일시는 KST").</li>
 *     <li>구매일은 구매 시각의 KST 날짜이고 자동 구매 확정은 (구매일 + 8일) 00:00이다(강의 {@code OrderItem.markPurchased}).</li>
 * </ul>
 *
 * @param sendAt           보낼 시각
 * @param businessDeadline 업무 기한
 */
public record ScheduleTimes(Instant sendAt, Instant businessDeadline) {

    /** 예약 시각의 시간대 */
    public static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private static final Duration DAY = Duration.ofHours(24);
    /** 자동 구매 확정까지의 날 수(강의 {@code OrderItem.AUTO_CONFIRM_DAYS}) */
    private static final int AUTO_CONFIRM_DAYS = 8;
    /** 구매 취소 마감 임박을 보내는 날(구매일 + 7일)과 시각(ND-031) */
    private static final int CONFIRMING_NOTICE_DAYS = 7;
    private static final LocalTime CONFIRMING_NOTICE_TIME = LocalTime.of(9, 0);
    /** 출연 자동 확인 기간 7×24시간(policy.md "기한 계산")과 출연 확인 부탁 시점 3×24시간 전(ND-030) */
    private static final Duration CONFIRM_PERIOD = DAY.multipliedBy(7);
    private static final Duration CONFIRM_NOTICE_BEFORE = DAY.multipliedBy(3);

    /** 출연 요청 만료 임박: 수락 기한 − 12시간 */
    public static ScheduleTimes requestExpiring(LocalDateTime acceptDeadline) {
        Instant deadline = kst(acceptDeadline);
        return new ScheduleTimes(deadline.minus(Duration.ofHours(12)), deadline);
    }

    /** 모집 마감 임박: 모집 마감 일시 − 24시간 */
    public static ScheduleTimes recruitClosing(LocalDateTime recruitDeadline) {
        Instant deadline = kst(recruitDeadline);
        return new ScheduleTimes(deadline.minus(DAY), deadline);
    }

    /** 방송 임박: 방송 예정 일시 − 24시간. 업무 기한은 방송 예정 일시 */
    public static ScheduleTimes starting(LocalDateTime scheduledAt) {
        Instant deadline = kst(scheduledAt);
        return new ScheduleTimes(deadline.minus(DAY), deadline);
    }

    /** 출연 확인 부탁: 자동 확인 시각(방송 예정 일시 + 7×24시간) − 3×24시간. 업무 기한은 자동 확인 시각 */
    public static ScheduleTimes confirmPeriodEnding(LocalDateTime scheduledAt) {
        Instant deadline = kst(scheduledAt).plus(CONFIRM_PERIOD);
        return new ScheduleTimes(deadline.minus(CONFIRM_NOTICE_BEFORE), deadline);
    }

    /** 구매 취소 마감 임박: (구매일 + 7일) 09:00. 업무 기한은 자동 구매 확정 (구매일 + 8일) 00:00 */
    public static ScheduleTimes orderItemConfirming(OffsetDateTime purchasedAt) {
        LocalDate purchaseDate = kstDate(purchasedAt);
        return new ScheduleTimes(
                purchaseDate.plusDays(CONFIRMING_NOTICE_DAYS).atTime(CONFIRMING_NOTICE_TIME).atZone(KST).toInstant(),
                autoConfirmAt(purchaseDate));
    }

    /**
     * 구매일을 모르는 "취소" 먼저 행의 대체 기한: (끝낸 사건 시각의 KST 날짜 + 8일) 00:00(ND-143·ND-149). 구매 취소·구매 확정·이의
     * 제기 접수 시각은 구매 시각 이후라(이벤트 계약 2장 불변식) 실제 기한보다 이르지 않다
     *
     * @param endedAt 예약을 끝낸 사건 시각(`canceledAt`·`confirmedAt`·`filedAt`)
     */
    public static Instant orderItemFallbackDeadline(OffsetDateTime endedAt) {
        return autoConfirmAt(kstDate(endedAt));
    }

    /** 업무 기한이 지났는지. 같은 시각이면 지났다(ND-144) */
    public static boolean isPast(Instant businessDeadline, Instant now) {
        return !now.isBefore(businessDeadline);
    }

    private static Instant autoConfirmAt(LocalDate purchaseDate) {
        return purchaseDate.plusDays(AUTO_CONFIRM_DAYS).atStartOfDay(KST).toInstant();
    }

    private static Instant kst(LocalDateTime localDateTime) {
        return localDateTime.atZone(KST).toInstant();
    }

    private static LocalDate kstDate(OffsetDateTime at) {
        return at.atZoneSameInstant(KST).toLocalDate();
    }
}
