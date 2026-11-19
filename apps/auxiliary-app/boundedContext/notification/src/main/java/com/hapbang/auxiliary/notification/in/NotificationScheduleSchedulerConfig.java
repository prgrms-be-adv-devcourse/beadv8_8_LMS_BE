package com.hapbang.auxiliary.notification.in;

import java.time.ZoneId;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.task.ThreadPoolTaskSchedulerBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import com.hapbang.auxiliary.global.scheduling.ConditionalOnSchedulingEnabled;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * 예약 배치 전용 스케줄러(ND-219). 앱 공통 {@code taskScheduler}(스레드 2개, 챗봇·검색 배치와 나눠 씀)가 차 있어도 예약 회차가 정해진
 * 시각에 시작하게 한다(ND-220).
 * <ul>
 *     <li>Boot builder로 만들어 trace decorator가 붙는다(docs/dev/logging.md). builder가 가져오는 {@code spring.task.scheduling.*}
 *     중 스레드 수·이름은 명시한다. 종료 대기({@code spring.task.scheduling.shutdown.*})는 logging.md "동작에 영향을 주는 값은 모두
 *     명시한다"의 예외로 일부러 물려받는다. 공용 설정을 바꾸면 함께 바뀌어 종료 동작이 공용 스케줄러와 계속 같다(ND-219).</li>
 *     <li>{@code defaultCandidate = false}라 기본 스케줄러·실행기 선택({@code taskScheduler}, {@code @Async})에 끼어들지 않는다.
 *     예약 배치는 {@code @Scheduled(scheduler = SCHEDULER)}로 이름을 지정해 쓴다.</li>
 *     <li>예약 배치와 같은 조건으로만 만든다. 스케줄링을 끈 환경에는 스레드가 없다.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnSchedulingEnabled
class NotificationScheduleSchedulerConfig {

    static final String SCHEDULER = "notificationScheduleTaskScheduler";
    static final String CRON = "${hapbang.notification.schedule.cron:35 * * * * *}";
    static final String ZONE = "Asia/Seoul";

    @Bean(name = SCHEDULER, defaultCandidate = false)
    ThreadPoolTaskScheduler notificationScheduleTaskScheduler(ThreadPoolTaskSchedulerBuilder builder) {
        return builder.poolSize(1).threadNamePrefix("notification-schedule-").build();
    }

    @Bean
    NotificationScheduleStartDelay notificationScheduleStartDelay(@Value(CRON) String cron,
            ObjectProvider<MeterRegistry> meterRegistry) {
        return new NotificationScheduleStartDelay(cron, ZoneId.of(ZONE), meterRegistry.getIfAvailable());
    }
}
