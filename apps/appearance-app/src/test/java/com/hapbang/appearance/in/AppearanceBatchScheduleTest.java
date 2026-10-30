package com.hapbang.appearance.in;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.scheduling.support.CronExpression;

import com.hapbang.appearance.app.AppearanceAutoConfirmUseCase;
import com.hapbang.appearance.app.AppearanceCancelAutoRejectUseCase;
import com.hapbang.appearance.app.AppearanceHoldRecheckUseCase;
import com.hapbang.appearance.app.AppearanceEventRepublishUseCase;
import com.hapbang.appearance.app.AppearanceRecruitDeadlineCloseUseCase;
import com.hapbang.appearance.app.AppearanceRequestExpireUseCase;
import com.hapbang.appearance.app.HpTaskRetryUseCase;

/**
 * 출연 배치 7개의 실행 주기 (#297). appearance.yml의 cron이 그대로 등록되는지, 매분 한 번씩 서로 다른 초에 도는지,
 * 각 배치가 맞는 UseCase를 부르는지 확인한다. 배치 자체는 시각을 판단하지 않는다 (UseCase가 Clock으로 판단).
 */
class AppearanceBatchScheduleTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    /** 배치 메서드 → 설정 키·실행 초. appearance.yml·AppearanceBatch 주석의 순서 (보류 재확인 → … → 자동 확인) */
    private static final Map<String, Schedule> SCHEDULES = new LinkedHashMap<>();

    static {
        SCHEDULES.put("recheckHolds", new Schedule("hold-recheck-cron", 0));
        SCHEDULES.put("republishEvents", new Schedule("event-republish-cron", 5));
        SCHEDULES.put("closeRecruits", new Schedule("recruit-close-cron", 10));
        SCHEDULES.put("expireRequests", new Schedule("expire-cron", 20));
        SCHEDULES.put("autoRejectCancelRequests", new Schedule("cancel-auto-reject-cron", 30));
        SCHEDULES.put("retryHpTasks", new Schedule("hp-task-retry-cron", 40));
        SCHEDULES.put("autoConfirmAppearances", new Schedule("auto-confirm-cron", 50));
    }

    private final AppearanceHoldRecheckUseCase holdRecheck = mock(AppearanceHoldRecheckUseCase.class);
    private final AppearanceRecruitDeadlineCloseUseCase recruitClose = mock(AppearanceRecruitDeadlineCloseUseCase.class);
    private final AppearanceRequestExpireUseCase expire = mock(AppearanceRequestExpireUseCase.class);
    private final AppearanceCancelAutoRejectUseCase cancelAutoReject = mock(AppearanceCancelAutoRejectUseCase.class);
    private final HpTaskRetryUseCase hpTaskRetry = mock(HpTaskRetryUseCase.class);
    private final AppearanceAutoConfirmUseCase autoConfirm = mock(AppearanceAutoConfirmUseCase.class);
    private final AppearanceEventRepublishUseCase eventRepublish = mock(AppearanceEventRepublishUseCase.class);

    /** 앱처럼 appearance.yml을 읽고 스위치를 켠 컨텍스트 (UseCase는 대역) */
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(context -> {
                for (PropertySource<?> source : loadAppearanceYaml()) {
                    context.getEnvironment().getPropertySources().addLast(source);
                }
            })
            .withPropertyValues("hapbang.scheduling.enabled=true")
            .withBean(AppearanceHoldRecheckUseCase.class, () -> holdRecheck)
            .withBean(AppearanceRecruitDeadlineCloseUseCase.class, () -> recruitClose)
            .withBean(AppearanceRequestExpireUseCase.class, () -> expire)
            .withBean(AppearanceCancelAutoRejectUseCase.class, () -> cancelAutoReject)
            .withBean(HpTaskRetryUseCase.class, () -> hpTaskRetry)
            .withBean(AppearanceAutoConfirmUseCase.class, () -> autoConfirm)
            .withBean(AppearanceEventRepublishUseCase.class, () -> eventRepublish)
            .withUserConfiguration(AppearanceBatch.class);

    private static Iterable<PropertySource<?>> loadAppearanceYaml() {
        try {
            return new YamlPropertySourceLoader().load("appearance.yml", new ClassPathResource("appearance.yml"));
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    @Test
    @DisplayName("appearance.yml의 cron 7개는 모두 올바르고, 매분 한 번(간격 60초)씩 서로 다른 초(0·5·10·20·30·40·50초)에 돈다")
    void cronsInYaml() {
        contextRunner.run(context -> {
            Set<Integer> seconds = new HashSet<>();
            for (Schedule schedule : SCHEDULES.values()) {
                String cron = context.getEnvironment().getRequiredProperty("appearance.batch." + schedule.key());
                CronExpression expression = CronExpression.parse(cron);
                ZonedDateTime first = expression.next(ZonedDateTime.of(2026, 10, 16, 12, 0, 0, 0, KST).minusNanos(1));
                ZonedDateTime second = expression.next(first);

                assertThat(first.getSecond()).as(schedule.key()).isEqualTo(schedule.second());
                assertThat(Duration.between(first, second)).as(schedule.key()).isEqualTo(Duration.ofMinutes(1));
                seconds.add(first.getSecond());
            }
            assertThat(seconds).hasSize(SCHEDULES.size());
        });
    }

    @Test
    @DisplayName("스위치를 켜면 배치 7개(@Scheduled 메서드 전부)가 appearance.yml의 cron 그대로 스케줄러에 등록된다")
    void registeredWithYamlCron() {
        contextRunner.withUserConfiguration(SchedulingOn.class).run(context -> {
            Map<String, String> registered = new HashMap<>();
            for (ScheduledTask task : context.getBean(ScheduledTaskHolder.class).getScheduledTasks()) {
                if (task.getTask() instanceof CronTask cronTask) {
                    String runnable = cronTask.getRunnable().toString();
                    SCHEDULES.keySet().stream().filter(method -> runnable.endsWith("AppearanceBatch." + method))
                            .findFirst().ifPresent(method -> registered.put(method, cronTask.getExpression()));
                }
            }

            // 배치를 새로 추가하면 이 표(SCHEDULES)에도 넣어야 검사에 들어온다
            assertThat(Arrays.stream(AppearanceBatch.class.getDeclaredMethods())
                    .filter(method -> method.isAnnotationPresent(Scheduled.class)).count())
                    .isEqualTo(SCHEDULES.size());
            assertThat(registered).hasSize(SCHEDULES.size());
            SCHEDULES.forEach((method, schedule) -> assertThat(registered.get(method)).as(method)
                    .isEqualTo(context.getEnvironment().getRequiredProperty("appearance.batch." + schedule.key())));
        });
    }

    @Test
    @DisplayName("@Scheduled의 기본값(설정이 없을 때)도 appearance.yml과 같고, 시간대는 한국 시간이다")
    void annotationDefaultsMatchYaml() {
        contextRunner.run(context -> {
            for (Map.Entry<String, Schedule> entry : SCHEDULES.entrySet()) {
                Method method = AppearanceBatch.class.getDeclaredMethod(entry.getKey());
                Scheduled scheduled = method.getAnnotation(Scheduled.class);
                String key = "appearance.batch." + entry.getValue().key();

                assertThat(scheduled.cron()).as(entry.getKey())
                        .isEqualTo("${" + key + ":" + context.getEnvironment().getRequiredProperty(key) + "}");
                assertThat(scheduled.zone()).as(entry.getKey()).isEqualTo("Asia/Seoul");
            }
        });
    }

    @Test
    @DisplayName("각 배치는 맞는 UseCase를 한 번 부른다 (보류 재확인·알림 재발행·기한 마감·만료·자동 거절·HP 작업 재시도·자동 확인)")
    void callsUseCase() {
        contextRunner.run(context -> {
            AppearanceBatch batch = context.getBean(AppearanceBatch.class);

            batch.recheckHolds();
            batch.republishEvents();
            batch.closeRecruits();
            batch.expireRequests();
            batch.autoRejectCancelRequests();
            batch.retryHpTasks();
            batch.autoConfirmAppearances();

            verify(holdRecheck).recheckDue();
            verify(eventRepublish).republishDue();
            verify(recruitClose).closeDue();
            verify(expire).expireDue();
            verify(cancelAutoReject).rejectDue();
            verify(hpTaskRetry).retryDue();
            verify(autoConfirm).confirmDue();
        });
    }

    private record Schedule(String key, int second) {
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    static class SchedulingOn {
    }
}
