package com.hapbang.auxiliary.notification.domain;

import static org.assertj.core.api.Assertions.*;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

/**
 * 예약 5종의 보낼 시각과 업무 기한(알림 종류 7장, ND-029~ND-031·ND-135·ND-143·ND-144). test-spec NT-SCH-01~04·12·15.
 * 출연 시각은 KST {@code LocalDateTime}이다.
 */
class ScheduleTimesTest {

    private static final ZoneOffset KST = ZoneOffset.ofHours(9);

    private static Instant kst(String localDateTime) {
        return LocalDateTime.parse(localDateTime).toInstant(KST);
    }

    @Test
    void 출연_요청_만료_임박은_수락_기한_12시간_전에_보내고_업무_기한은_수락_기한이다() {
        ScheduleTimes times = ScheduleTimes.requestExpiring(LocalDateTime.parse("2026-11-20T15:00:00"));

        assertThat(times.sendAt()).isEqualTo(kst("2026-11-20T03:00:00"));
        assertThat(times.businessDeadline()).isEqualTo(kst("2026-11-20T15:00:00"));
    }

    @Test
    void 모집_마감_임박은_마감_24시간_전에_보낸다() {
        ScheduleTimes times = ScheduleTimes.recruitClosing(LocalDateTime.parse("2026-11-20T15:00:00"));

        assertThat(times.sendAt()).isEqualTo(kst("2026-11-19T15:00:00"));
        assertThat(times.businessDeadline()).isEqualTo(kst("2026-11-20T15:00:00"));
    }

    @Test
    void 방송_임박은_방송_24시간_전에_보내고_업무_기한은_방송_예정_일시다() {
        ScheduleTimes times = ScheduleTimes.starting(LocalDateTime.parse("2026-11-20T20:00:00"));

        assertThat(times.sendAt()).isEqualTo(kst("2026-11-19T20:00:00"));
        assertThat(times.businessDeadline()).isEqualTo(kst("2026-11-20T20:00:00"));
    }

    @Test
    void 출연_확인_부탁은_방송_4x24시간_뒤에_보내고_업무_기한은_자동_확인_시각_7x24시간_뒤다() {
        ScheduleTimes times = ScheduleTimes.confirmPeriodEnding(LocalDateTime.parse("2026-11-20T20:00:00"));

        assertThat(times.sendAt()).isEqualTo(kst("2026-11-24T20:00:00"));
        assertThat(times.businessDeadline()).isEqualTo(kst("2026-11-27T20:00:00"));
    }

    @Test
    void 구매_취소_마감_임박은_구매일_7일_뒤_09시에_보내고_업무_기한은_8일_뒤_00시다() {
        ScheduleTimes times = ScheduleTimes.orderItemConfirming(OffsetDateTime.parse("2026-11-13T10:30:00+09:00"));

        assertThat(times.sendAt()).isEqualTo(kst("2026-11-20T09:00:00"));
        assertThat(times.businessDeadline()).isEqualTo(kst("2026-11-21T00:00:00"));
    }

    @Test
    void 구매일은_KST_날짜다_UTC로_와도_KST_23시59분59초와_00시를_가른다() {
        // KST 2026-11-13 23:59:59 = UTC 14:59:59
        ScheduleTimes lastSecond = ScheduleTimes.orderItemConfirming(OffsetDateTime.parse("2026-11-13T14:59:59Z"));
        // KST 2026-11-14 00:00:00
        ScheduleTimes midnight = ScheduleTimes.orderItemConfirming(OffsetDateTime.parse("2026-11-13T15:00:00Z"));

        assertThat(lastSecond.businessDeadline()).isEqualTo(kst("2026-11-21T00:00:00"));
        assertThat(midnight.businessDeadline()).isEqualTo(kst("2026-11-22T00:00:00"));
        assertThat(midnight.sendAt()).isEqualTo(kst("2026-11-21T09:00:00"));
    }

    // NT-SCH-15 (ND-143)

    @Test
    void 취소가_먼저_온_구매_취소_마감_임박의_대체_기한은_이벤트_시각_KST_날짜_8일_뒤_00시다() {
        assertThat(ScheduleTimes.orderItemFallbackDeadline(OffsetDateTime.parse("2026-11-15T23:59:59+09:00")))
                .isEqualTo(kst("2026-11-23T00:00:00"));
    }

    @Test
    void 대체_기한은_구매_뒤의_취소_확정이면_실제_기한보다_이르지_않다() {
        OffsetDateTime purchasedAt = OffsetDateTime.parse("2026-11-13T23:59:59+09:00");
        Instant actual = ScheduleTimes.orderItemConfirming(purchasedAt).businessDeadline();

        // 같은 시각(구매 직후 취소), 다음 날, 실제 기한을 넘은 반환 완료(OrderItem.completeCancel)
        for (OffsetDateTime eventAt : new OffsetDateTime[] {purchasedAt,
                OffsetDateTime.parse("2026-11-14T00:00:00+09:00"),
                OffsetDateTime.parse("2026-11-25T08:00:00+09:00")}) {
            assertThat(ScheduleTimes.orderItemFallbackDeadline(eventAt)).isAfterOrEqualTo(actual);
        }
    }

    // NT-SCH-12 경계 (ND-144)

    @Test
    void 업무_기한과_같은_시각이면_지났고_1초_전이면_지나지_않았다() {
        Instant deadline = kst("2026-11-21T00:00:00");

        assertThat(ScheduleTimes.isPast(deadline, deadline)).isTrue();
        assertThat(ScheduleTimes.isPast(deadline, deadline.plusSeconds(1))).isTrue();
        assertThat(ScheduleTimes.isPast(deadline, deadline.minusSeconds(1))).isFalse();
    }
}
