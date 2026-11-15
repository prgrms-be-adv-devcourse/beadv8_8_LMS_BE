package com.hapbang.auxiliary.notification.in;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.sql.SQLException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import com.hapbang.auxiliary.global.scheduling.AdvisoryLockSession;
import com.hapbang.auxiliary.global.scheduling.JobLock;
import com.hapbang.auxiliary.notification.app.NotificationJob;
import com.hapbang.auxiliary.notification.app.ScheduleDispatcher;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * 예약 배치 진입점의 로그 레벨. 회차 실패는 alert 대상이라 사람이 볼 ERROR로 남긴다(ND-150·ND-151, docs/dev/logging.md 레벨 표)
 */
@ExtendWith(OutputCaptureExtension.class)
class NotificationScheduleBatchTest {

    private final ScheduleDispatcher dispatcher = mock(ScheduleDispatcher.class);

    @Test
    void 잠금_시도가_실패하면_ERROR를_남기고_배치는_예외를_밖으로_내보내지_않는다(CapturedOutput output) {
        when(dispatcher.runOnce(any())).thenThrow(lockFailure());

        assertThatCode(() -> new NotificationScheduleBatch(dispatcher).run()).doesNotThrowAnyException();

        assertThat(output.getAll().lines())
                .anyMatch(line -> line.contains("ERROR") && line.contains("예약 배치 잠금을 시도하지 못해"));
    }

    @Test
    void 회차가_예외로_끝나면_ERROR를_남긴다(CapturedOutput output) {
        when(dispatcher.runOnce(any())).thenThrow(new IllegalStateException("test: 회차 실패"));

        assertThatCode(() -> new NotificationScheduleBatch(dispatcher).run()).doesNotThrowAnyException();

        assertThat(output.getAll().lines())
                .anyMatch(line -> line.contains("ERROR") && line.contains("예약 배치 회차가 실패했다"));
    }

    /** 실제 {@link JobLock}이 연결 실패로 던지는 예외 */
    private static JobLock.JobLockException lockFailure() {
        AdvisoryLockSession broken = new AdvisoryLockSession() {
            @Override
            public boolean tryLock(long key) throws SQLException {
                throw new SQLException("test: 연결 끊김");
            }

            @Override
            public boolean unlock(long key) {
                return true;
            }

            @Override
            public void close() {
            }
        };
        JobLock lock = new JobLock(broken, new StaticListableBeanFactory().getBeanProvider(MeterRegistry.class));
        try {
            lock.tryAcquire(NotificationJob.SCHEDULE_DISPATCH);
        } catch (JobLock.JobLockException e) {
            return e;
        }
        throw new IllegalStateException("잠금 시도가 실패해야 한다");
    }
}
