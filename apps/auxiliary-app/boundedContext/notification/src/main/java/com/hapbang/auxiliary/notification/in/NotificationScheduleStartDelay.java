package com.hapbang.auxiliary.notification.in;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import org.jspecify.annotations.Nullable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * 예약 배치 회차 시작 지연(ND-221). 지연 = 회차 시작 시각 − 시작 시각 이전(같은 시각 포함)의 가장 늦은 cron 발화 시각이다. cron은 배치와
 * 같은 키·zone으로 읽는다. 다음 발화까지 1초 이하로 남은 회차는 일찍 깨어난 것으로 보고 0으로 잰다. 한 주기 넘게 밀려 건너뛴 회차는 직전 발화 시각을 기준으로 재므로 이 지표에 드러나지 않는다(L5 기준으로 본다).
 * 지표 저장소 빈이 없거나 cron이 끔 값({@code -})이면 기록하지 않는다(ND-150과 같다. 끔 값이면 배치가 돌지 않는다).
 */
final class NotificationScheduleStartDelay {

    static final String METRIC = "hapbang.notification.schedule.start.delay";
    /** 1초 판정부터 챗봇 색인이 공용 스레드를 쥐는 최대치(분 단위, #988)까지 가른다 */
    private static final List<Duration> BUCKETS = List.of(Duration.ofMillis(100), Duration.ofMillis(500),
            Duration.ofSeconds(1), Duration.ofSeconds(5), Duration.ofSeconds(10), Duration.ofSeconds(30),
            Duration.ofSeconds(60), Duration.ofSeconds(120), Duration.ofSeconds(300));
    /** 다음 발화까지 이만큼 이하로 남았으면 일찍 깨어난 회차로 보고 지연 0으로 잰다(ND-221). 운영 cron 주기(1분)보다 훨씬 짧다 */
    private static final Duration EARLY_WAKE_TOLERANCE = Duration.ofSeconds(1);
    /** 직전 발화 시각을 찾을 때 처음 거슬러 보는 길이. 못 찾으면 두 배씩 늘린다 */
    private static final Duration FIRST_LOOKBACK = Duration.ofMinutes(1);
    private static final Duration MAX_LOOKBACK = Duration.ofDays(400);

    /** cron이 끔 값({@link Scheduled#CRON_DISABLED})이면 null이다. 배치가 돌지 않으므로 잴 것이 없다 */
    private final @Nullable CronExpression cron;
    private final ZoneId zone;
    private final @Nullable Timer timer;

    NotificationScheduleStartDelay(String cron, ZoneId zone, @Nullable MeterRegistry registry) {
        this.cron = Scheduled.CRON_DISABLED.equals(cron) ? null : CronExpression.parse(cron);
        this.zone = zone;
        this.timer = registry == null || this.cron == null ? null
                : Timer.builder(METRIC).serviceLevelObjectives(BUCKETS.toArray(Duration[]::new)).register(registry);
    }

    void record(Instant startedAt) {
        if (timer == null) {
            return;
        }
        Duration delay = delayAt(startedAt);
        if (delay != null) {
            timer.record(delay);
        }
    }

    /**
     * 직전 발화 시각을 찾지 못하면(cron 끔, 또는 최대 거슬러 보는 길이 안에 발화가 없음) null. 회차는 발화 직후에 시작하므로 실제 회차에서는
     * 직전 발화가 항상 가까이 있다
     */
    @Nullable Duration delayAt(Instant startedAt) {
        if (cron == null) {
            return null;
        }
        ZonedDateTime start = startedAt.atZone(zone);
        // 스케줄러는 정해진 시각보다 몇 ms 일찍 깨어날 수 있다(L5-S #992에서 약 3ms). 그대로 재면 직전 발화가 한 주기 전이 된다
        ZonedDateTime upcoming = cron.next(start);
        if (upcoming != null && !Duration.between(start, upcoming).minus(EARLY_WAKE_TOLERANCE).isPositive()) {
            return Duration.ZERO;
        }
        for (Duration lookback = FIRST_LOOKBACK; lookback.compareTo(MAX_LOOKBACK) <= 0;
                lookback = lookback.multipliedBy(2)) {
            ZonedDateTime previous = null;
            // next는 주어진 시각보다 뒤의 발화를 준다. 1나노초 앞에서 시작해야 시작 시각과 같은 발화도 찾는다
            ZonedDateTime next = cron.next(start.minus(lookback).minusNanos(1));
            while (next != null && !next.isAfter(start)) {
                previous = next;
                next = cron.next(next);
            }
            if (previous != null) {
                return Duration.between(previous, start);
            }
        }
        return null;
    }
}
