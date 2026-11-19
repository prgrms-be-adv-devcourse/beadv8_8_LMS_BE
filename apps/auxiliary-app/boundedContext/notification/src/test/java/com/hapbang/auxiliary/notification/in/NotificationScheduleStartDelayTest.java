package com.hapbang.auxiliary.notification.in;

import static org.assertj.core.api.Assertions.*;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.CountAtBucket;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * 예약 배치 회차 시작 지연(ND-221, test-spec NT-SCH-27). 지연 = 회차 시작 시각 − 시작 시각 이전(같은 시각 포함)의 가장 늦은 cron 발화
 * 시각. cron은 배치와 같은 zone(KST)으로 읽는다.
 */
class NotificationScheduleStartDelayTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final String EVERY_MINUTE_AT_35 = "35 * * * * *";

    @Test
    void 정해진_시각에_시작하면_지연은_0이다() {
        var delay = new NotificationScheduleStartDelay(EVERY_MINUTE_AT_35, KST, null);

        assertThat(delay.delayAt(Instant.parse("2026-11-18T07:00:35Z"))).isZero();
    }

    @Test
    void 정해진_시각보다_늦게_시작하면_그_차이가_지연이다() {
        var delay = new NotificationScheduleStartDelay(EVERY_MINUTE_AT_35, KST, null);

        assertThat(delay.delayAt(Instant.parse("2026-11-18T07:00:35.400Z"))).isEqualTo(Duration.ofMillis(400));
    }

    @Test
    void 스케줄러가_정해진_시각보다_조금_일찍_깨어나면_지연은_0이다() {
        // L5-S(#992)에서 회차가 약 3ms 일찍 깨어나 직전 발화를 한 분 전으로 잡아 약 60초로 기록됐다
        var delay = new NotificationScheduleStartDelay(EVERY_MINUTE_AT_35, KST, null);

        assertThat(delay.delayAt(Instant.parse("2026-11-18T07:00:34.997Z"))).isZero();
        assertThat(delay.delayAt(Instant.parse("2026-11-18T07:00:34Z"))).as("다음 발화까지 1초면 일찍 깨어난 것으로 본다")
                .isZero();
    }

    @Test
    void 다음_발화까지_1초보다_많이_남았으면_직전_발화를_기준으로_잰다() {
        var delay = new NotificationScheduleStartDelay(EVERY_MINUTE_AT_35, KST, null);

        assertThat(delay.delayAt(Instant.parse("2026-11-18T07:00:33.900Z"))).isEqualTo(Duration.ofMillis(58_900));
    }

    @Test
    void 분이_바뀐_뒤에_시작해도_직전_발화_시각을_기준으로_잰다() {
        var delay = new NotificationScheduleStartDelay(EVERY_MINUTE_AT_35, KST, null);

        assertThat(delay.delayAt(Instant.parse("2026-11-18T07:01:10Z"))).isEqualTo(Duration.ofSeconds(35));
    }

    @Test
    void 한_주기_넘게_밀려도_직전_발화_시각을_기준으로_잰다() {
        // 건너뛴 회차는 이 지표가 아니라 L5 기준으로 본다(ND-221)
        var delay = new NotificationScheduleStartDelay(EVERY_MINUTE_AT_35, KST, null);

        // 다음 발화 1초 안(예: 59.9초 늦음)은 일찍 깨어남과 구분할 수 없어 0으로 잰다(위 시험). 그 밖에서 확인한다
        assertThat(delay.delayAt(Instant.parse("2026-11-18T07:02:20Z"))).isEqualTo(Duration.ofSeconds(45));
    }

    @Test
    void cron은_KST로_읽는다() {
        var delay = new NotificationScheduleStartDelay("0 0 9 * * *", KST, null);

        // 09:00:05 KST = 00:00:05 UTC. UTC로 읽으면 전날 09:00 UTC 기준 15시간이 된다
        assertThat(delay.delayAt(Instant.parse("2026-11-18T00:00:05Z"))).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    void 기록하면_Timer에_지연이_쌓이고_버킷은_100ms부터_300s까지다() {
        var registry = new SimpleMeterRegistry();
        var delay = new NotificationScheduleStartDelay(EVERY_MINUTE_AT_35, KST, registry);

        delay.record(Instant.parse("2026-11-18T07:00:36Z"));

        Timer timer = registry.get(NotificationScheduleStartDelay.METRIC).timer();
        assertThat(timer.count()).isEqualTo(1);
        assertThat(timer.totalTime(TimeUnit.MILLISECONDS)).isEqualTo(1_000);
        assertThat(Arrays.stream(timer.takeSnapshot().histogramCounts()).map(CountAtBucket::bucket))
                .containsExactly(Duration.ofMillis(100).toNanos() * 1.0, Duration.ofMillis(500).toNanos() * 1.0,
                        Duration.ofSeconds(1).toNanos() * 1.0, Duration.ofSeconds(5).toNanos() * 1.0,
                        Duration.ofSeconds(10).toNanos() * 1.0, Duration.ofSeconds(30).toNanos() * 1.0,
                        Duration.ofSeconds(60).toNanos() * 1.0, Duration.ofSeconds(120).toNanos() * 1.0,
                        Duration.ofSeconds(300).toNanos() * 1.0);
    }

    @Test
    void cron이_끔이면_기록하지_않고_예외도_없다() {
        // Spring의 끔 값("-")이면 배치가 돌지 않는다. 지연 계산이 이 값으로 기동을 막으면 안 된다
        var registry = new SimpleMeterRegistry();
        var delay = new NotificationScheduleStartDelay("-", KST, registry);

        delay.record(Instant.parse("2026-11-18T07:00:36Z"));

        assertThat(registry.find(NotificationScheduleStartDelay.METRIC).timer()).isNull();
    }

    @Test
    void 지표_저장소가_없으면_기록하지_않고_예외도_없다() {
        var delay = new NotificationScheduleStartDelay(EVERY_MINUTE_AT_35, KST, null);

        assertThatCode(() -> delay.record(Instant.parse("2026-11-18T07:00:36Z"))).doesNotThrowAnyException();
    }
}
