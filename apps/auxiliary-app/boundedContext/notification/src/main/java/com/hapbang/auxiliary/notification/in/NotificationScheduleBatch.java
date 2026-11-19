package com.hapbang.auxiliary.notification.in;

import java.time.Clock;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.hapbang.auxiliary.global.scheduling.ConditionalOnSchedulingEnabled;
import com.hapbang.auxiliary.global.scheduling.JobLock.JobLockException;
import com.hapbang.auxiliary.notification.app.ScheduleDispatcher;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 예약 배치를 1분마다 부른다(ND-029). 처리는 {@link ScheduleDispatcher}가 하고, 테스트는 그 메서드를 직접 부른다. 서버가 꺼진 동안의
 * 회차는 돌지 않고, 켜진 뒤 첫 회차가 밀린 예약을 보낸다(ND-134). 앱 공통 스케줄러가 아니라 알림 소유 스케줄러에서 돌고(ND-219), 회차를
 * 시작할 때 시작 지연을 기록한다(ND-221).
 */
@Slf4j
@Component
@ConditionalOnSchedulingEnabled
@RequiredArgsConstructor
class NotificationScheduleBatch {

    private final ScheduleDispatcher dispatcher;
    private final NotificationScheduleStartDelay startDelay;

    @Scheduled(cron = NotificationScheduleSchedulerConfig.CRON, zone = NotificationScheduleSchedulerConfig.ZONE,
            scheduler = NotificationScheduleSchedulerConfig.SCHEDULER)
    void run() {
        Clock clock = Clock.systemUTC();
        // 회차 결과보다 앞서 정해지므로 잠금 경합·실패 회차도 기록한다(ND-221)
        startDelay.record(clock.instant());
        try {
            dispatcher.runOnce(clock);
        } catch (JobLockException e) {
            // 회차 실패로 세어 alert 대상이므로 ERROR다(ND-150)
            log.error("예약 배치 잠금을 시도하지 못해 이번 회차를 건너뛰었다", e);
        } catch (RuntimeException e) {
            log.error("예약 배치 회차가 실패했다. 다음 회차에 다시 실행한다", e);
        }
    }
}
