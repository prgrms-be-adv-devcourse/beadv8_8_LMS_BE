package com.hapbang.auxiliary.notification.in;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.scheduling.support.ScheduledTaskObservationContext;

import com.hapbang.auxiliary.global.config.SchedulingConfig;
import com.hapbang.auxiliary.notification.app.ScheduleDispatcher;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;

/**
 * 예약 배치 전용 스케줄러(ND-219·ND-220, test-spec NT-SCH-25·NT-SCH-26). 앱 공통 {@code taskScheduler}의 스레드 2개가 모두 막혀
 * 있어도 예약 회차는 알림 소유 스케줄러 스레드에서 정해진 시각 + 1초 안에 시작한다. 다른 배치는 그대로 공통 스케줄러에서 돌고, 예약 회차도
 * 실행마다 {@code @Scheduled} 관측(trace의 바탕)이 생긴다. 조건부 활성화와 cron 끔 값은 기동을 막지 않는다.
 */
class NotificationScheduleSchedulerTest {

    private static final String EVERY_TWO_SECONDS = "*/2 * * * * *";
    private static final String EVERY_FIVE_SECONDS = "*/5 * * * * *";
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TaskSchedulingAutoConfiguration.class))
            .withUserConfiguration(SchedulingConfig.class, NotificationScheduleSchedulerConfig.class)
            .withPropertyValues("spring.task.scheduling.pool.size=2");

    @Test
    void 공통_스케줄러_스레드가_모두_막혀도_예약_회차가_전용_스레드에서_정해진_시각_1초_안에_시작한다() {
        // 막은 뒤에 시작한 회차만 센다. 막기 전 회차나 기동 직후 지연이 판정에 섞이지 않게 한다
        AtomicBoolean blocked = new AtomicBoolean();
        CountDownLatch started = new CountDownLatch(1);
        AtomicReference<String> thread = new AtomicReference<>();
        AtomicReference<Instant> startedAt = new AtomicReference<>();
        ScheduleDispatcher dispatcher = mock(ScheduleDispatcher.class);
        when(dispatcher.runOnce(any())).thenAnswer(invocation -> {
            if (blocked.get() && startedAt.compareAndSet(null, Instant.now())) {
                thread.set(Thread.currentThread().getName());
                started.countDown();
            }
            return null;
        });

        runner.withBean(ScheduleDispatcher.class, () -> dispatcher)
                .withBean(NotificationScheduleBatch.class)
                .withPropertyValues("hapbang.scheduling.enabled=true",
                        "hapbang.notification.schedule.cron=" + EVERY_FIVE_SECONDS)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    ThreadPoolTaskScheduler shared = context.getBean("taskScheduler", ThreadPoolTaskScheduler.class);
                    CountDownLatch release = new CountDownLatch(1);
                    CountDownLatch blocking = new CountDownLatch(2);
                    try {
                        for (int i = 0; i < 2; i++) {
                            shared.execute(() -> {
                                blocking.countDown();
                                awaitQuietly(release);
                            });
                        }
                        assertThat(blocking.await(5, TimeUnit.SECONDS)).as("공통 스케줄러 스레드 2개가 모두 막혔다").isTrue();
                        blocked.set(true);

                        assertThat(started.await(10, TimeUnit.SECONDS)).as("막힌 동안 예약 회차가 시작했다").isTrue();
                    } finally {
                        release.countDown();
                    }
                    assertThat(thread.get()).startsWith("notification-schedule-");
                    // 지연 지표(delayAt)는 다음 발화 1초 안을 0으로 보므로 짧은 주기에서는 늦은 시작을 가리지 못한다(ND-221).
                    // 시작 1초 전 이후의 첫 cron 발화와 직접 비교한다. 1초 넘게 늦으면 그 발화는 다음 회차가 되어 차이가 크게 음수가 된다
                    Instant start = startedAt.get();
                    ZonedDateTime fire = CronExpression.parse(EVERY_FIVE_SECONDS)
                            .next(start.minusSeconds(1).atZone(KST));
                    assertThat(Duration.between(fire.toInstant(), start).abs())
                            .as("정해진 시각부터 시작까지").isLessThan(Duration.ofSeconds(1));
                });
    }

    @Test
    void 다른_배치는_그대로_공통_스케줄러에서_돌고_예약_회차에도_관측이_생긴다() {
        List<ScheduledTaskObservationContext> observed = new CopyOnWriteArrayList<>();
        ObservationRegistry observations = ObservationRegistry.create();
        observations.observationConfig().observationHandler(new ObservationHandler<>() {
            @Override
            public boolean supportsContext(Observation.Context context) {
                return context instanceof ScheduledTaskObservationContext;
            }

            @Override
            public void onStart(Observation.Context context) {
                observed.add((ScheduledTaskObservationContext) context);
            }
        });
        CountDownLatch notificationRan = new CountDownLatch(1);
        ScheduleDispatcher dispatcher = mock(ScheduleDispatcher.class);
        when(dispatcher.runOnce(any())).thenAnswer(invocation -> {
            notificationRan.countDown();
            return null;
        });

        runner.withBean(ScheduleDispatcher.class, () -> dispatcher)
                .withBean(NotificationScheduleBatch.class)
                .withBean(ObservationRegistry.class, () -> observations)
                .withUserConfiguration(OtherBatchConfig.class)
                .withPropertyValues("hapbang.scheduling.enabled=true",
                        "hapbang.notification.schedule.cron=" + EVERY_TWO_SECONDS)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    OtherBatch other = context.getBean(OtherBatch.class);
                    assertThat(other.ran.await(5, TimeUnit.SECONDS)).isTrue();
                    assertThat(notificationRan.await(10, TimeUnit.SECONDS)).isTrue();

                    assertThat(other.thread.get()).as("다른 배치는 공통 스케줄러(Boot 기본 이름)에서 돈다")
                            .startsWith("scheduling-");
                    assertThat(observed).as("예약 회차 실행마다 @Scheduled 관측이 생긴다")
                            .anyMatch(observation -> observation.getTargetClass() == NotificationScheduleBatch.class);
                });
    }

    @Test
    void 전용_스케줄러는_스레드_1개이고_기본_스케줄러는_그대로_taskScheduler다() {
        runner.withPropertyValues("hapbang.scheduling.enabled=true").run(context -> {
            ThreadPoolTaskScheduler own = context.getBean(NotificationScheduleSchedulerConfig.SCHEDULER,
                    ThreadPoolTaskScheduler.class);
            assertThat(own.getScheduledThreadPoolExecutor().getCorePoolSize()).isEqualTo(1);
            assertThat(own.getThreadNamePrefix()).isEqualTo("notification-schedule-");
            assertThat(context.getBean(TaskScheduler.class)).isSameAs(context.getBean("taskScheduler"));
        });
    }

    @Test
    void 스케줄링을_끄면_전용_스케줄러를_만들지_않는다() {
        runner.withPropertyValues("hapbang.scheduling.enabled=false").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(NotificationScheduleSchedulerConfig.SCHEDULER);
            assertThat(context).doesNotHaveBean(NotificationScheduleStartDelay.class);
        });
    }

    @Test
    void cron을_끔_값으로_두어도_기동한다() {
        runner.withBean(ScheduleDispatcher.class, () -> mock(ScheduleDispatcher.class))
                .withBean(NotificationScheduleBatch.class)
                .withPropertyValues("hapbang.scheduling.enabled=true", "hapbang.notification.schedule.cron=-")
                .run(context -> assertThat(context).hasNotFailed());
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 앱의 다른 배치와 STOMP 스케줄러 자리. 운영 앱처럼 TaskScheduler가 여럿이어도 이름 없는 {@code @Scheduled}는 {@code taskScheduler}를
     * 쓴다. 관측은 Boot의 관측 자동 설정이 하는 것처럼 등록기에 레지스트리를 넘겨 켠다(이 모듈 시험 classpath에는 그 자동 설정이 없다).
     */
    @Configuration(proxyBeanMethods = false)
    static class OtherBatchConfig {

        @Bean
        OtherBatch otherBatch() {
            return new OtherBatch();
        }

        @Bean
        ThreadPoolTaskScheduler messageBrokerTaskScheduler() {
            ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
            scheduler.setThreadNamePrefix("broker-");
            return scheduler;
        }

        @Bean
        SchedulingConfigurer observedScheduling(ObservationRegistry observations) {
            return registrar -> registrar.setObservationRegistry(observations);
        }
    }

    static class OtherBatch {

        final CountDownLatch ran = new CountDownLatch(1);
        final AtomicReference<String> thread = new AtomicReference<>();

        @Scheduled(fixedDelay = 200)
        void run() {
            thread.compareAndSet(null, Thread.currentThread().getName());
            ran.countDown();
        }
    }
}
