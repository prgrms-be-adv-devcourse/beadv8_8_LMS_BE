package com.hapbang.auxiliary.notification.app;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.hapbang.auxiliary.global.scheduling.AdvisoryLockSession;
import com.hapbang.auxiliary.global.scheduling.JobLock;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * 예약 배치의 회차 결과 지표(ND-150, test-spec NT-SCH-23). 저장소는 가짜로 두고 회차의 성공·실패·잠금 경합만 본다.
 */
class ScheduleDispatcherMetricsTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-14T00:00:00Z"), ZoneOffset.UTC);

    private final MeterRegistry meters = new SimpleMeterRegistry();
    private final ScheduleStore store = mock(ScheduleStore.class);
    private final FakeLockSession session = new FakeLockSession();

    @Test
    void 결과값마다_카운터를_0으로_미리_만든다() {
        dispatcher();

        for (String result : List.of("sent", "skipped", "retry", "abandoned", "record_failed")) {
            assertThat(count("hapbang.notification.schedule.outcomes", "result", result)).as(result).isZero();
        }
        assertThat(meters.find("hapbang.notification.schedule.runs").tag("outcome", "completed").counter()).isNotNull();
        assertThat(meters.find("hapbang.notification.schedule.runs").tag("outcome", "failed").counter()).isNotNull();
    }

    @Test
    void 끝까지_돈_회차는_completed로_센다() {
        when(store.findDueIds(any(), anyInt())).thenReturn(List.of());

        dispatcher().runOnce(CLOCK);

        assertThat(runs("completed")).isEqualTo(1);
        assertThat(runs("failed")).isZero();
    }

    @Test
    void 고른_뒤_다른_실행이_끝낸_예약은_어떤_결과로도_세지_않는다() {
        when(store.findDueIds(any(), anyInt())).thenReturn(List.of(1L));
        when(store.lockById(1L)).thenReturn(Optional.empty());

        dispatcher().runOnce(CLOCK);

        for (String result : List.of("sent", "skipped", "retry", "abandoned", "record_failed")) {
            assertThat(count("hapbang.notification.schedule.outcomes", "result", result)).as(result).isZero();
        }
        assertThat(runs("completed")).isEqualTo(1);
    }

    @Test
    void 회차_중_예외가_나면_failed로_세고_예외를_그대로_던진다() {
        when(store.findDueIds(any(), anyInt())).thenThrow(new IllegalStateException("test: 조회 실패"));
        ScheduleDispatcher dispatcher = dispatcher();

        assertThatThrownBy(() -> dispatcher.runOnce(CLOCK)).isInstanceOf(IllegalStateException.class);

        assertThat(runs("failed")).isEqualTo(1);
        assertThat(runs("completed")).isZero();
    }

    @Test
    void 잠금_시도가_실패하면_failed로_센다() {
        session.failure = new SQLException("test: 연결 끊김");
        ScheduleDispatcher dispatcher = dispatcher();

        assertThatThrownBy(() -> dispatcher.runOnce(CLOCK)).isInstanceOf(JobLock.JobLockException.class);

        assertThat(runs("failed")).isEqualTo(1);
    }

    @Test
    void 다른_실행이_잠금을_쥐어_건너뛴_회차는_세지_않는다() {
        session.locked = false;

        assertThat(dispatcher().runOnce(CLOCK).ran()).isFalse();

        assertThat(runs("completed")).isZero();
        assertThat(runs("failed")).isZero();
    }

    private ScheduleDispatcher dispatcher() {
        var meterProvider = new StaticListableBeanFactory(Map.of("meters", meters)).getBeanProvider(MeterRegistry.class);
        var lockMeters = new StaticListableBeanFactory().getBeanProvider(MeterRegistry.class);
        TransactionTemplate transaction = new TransactionTemplate(mock(PlatformTransactionManager.class));
        return new ScheduleDispatcher(store, mock(NotificationWriter.class), transaction,
                new JobLock(session, lockMeters), meterProvider, 100, 100, 7);
    }

    private double runs(String outcome) {
        return count("hapbang.notification.schedule.runs", "outcome", outcome);
    }

    private double count(String name, String tag, String value) {
        Counter counter = meters.find(name).tag(tag, value).counter();
        return counter == null ? 0 : counter.count();
    }

    /** 잡기 결과나 실패를 정할 수 있는 advisory lock session */
    private static final class FakeLockSession implements AdvisoryLockSession {

        boolean locked = true;
        SQLException failure;

        @Override
        public boolean tryLock(long key) throws SQLException {
            if (failure != null) {
                throw failure;
            }
            return locked;
        }

        @Override
        public boolean unlock(long key) {
            return true;
        }

        @Override
        public void close() {
        }
    }
}
