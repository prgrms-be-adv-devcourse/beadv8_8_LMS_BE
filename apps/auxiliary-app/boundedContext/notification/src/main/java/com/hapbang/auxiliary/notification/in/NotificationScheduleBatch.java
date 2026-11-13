package com.hapbang.auxiliary.notification.in;

import java.time.Instant;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.hapbang.auxiliary.global.scheduling.ConditionalOnSchedulingEnabled;
import com.hapbang.auxiliary.global.scheduling.JobLock.JobLockException;
import com.hapbang.auxiliary.notification.app.ScheduleDispatcher;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 예약 배치를 1분마다 부른다(ND-029). 처리는 {@link ScheduleDispatcher}가 하고, 테스트는 그 메서드를 직접 부른다. 서버가 꺼진 동안의
 * 회차는 돌지 않고, 켜진 뒤 첫 회차가 밀린 예약을 보낸다(ND-134).
 */
@Slf4j
@Component
@ConditionalOnSchedulingEnabled
@RequiredArgsConstructor
class NotificationScheduleBatch {

    private final ScheduleDispatcher dispatcher;

    @Scheduled(cron = "${hapbang.notification.schedule.cron:35 * * * * *}", zone = "Asia/Seoul")
    void run() {
        try {
            dispatcher.runOnce(Instant.now());
        } catch (JobLockException e) {
            log.warn("예약 배치 잠금을 시도하지 못해 이번 회차를 건너뛰었다", e);
        } catch (RuntimeException e) {
            log.error("예약 배치 회차가 실패했다. 다음 회차에 다시 실행한다", e);
        }
    }
}
