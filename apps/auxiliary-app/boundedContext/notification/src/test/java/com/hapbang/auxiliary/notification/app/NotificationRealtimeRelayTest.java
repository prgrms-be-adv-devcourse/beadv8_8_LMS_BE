package com.hapbang.auxiliary.notification.app;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.transaction.PlatformTransactionManager;

import com.hapbang.auxiliary.notification.out.MemberMaskLocks;
import com.hapbang.auxiliary.notification.out.NotificationRepository;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * 실시간 전달 줄이 가득 차 프레임을 버릴 때 관측할 수 있어야 한다(ND-027: 유실은 허용하지만 몇 건 버렸는지는 보여야 한다).
 * 버린 프레임은 지표 {@value NotificationRealtimeRelay#DROPPED_METRIC}{reason}으로 센다.
 */
class NotificationRealtimeRelayTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    @Test
    void 줄이_가득_차_넘기지_못한_프레임은_queue_full로_센다() {
        TaskExecutor rejecting = task -> {
            throw new TaskRejectedException("full");
        };
        NotificationRealtimeRelay relay = relay(rejecting);

        relay.publishAfterCommit(1L, 10L);
        relay.publishAfterCommit(2L, 10L);

        assertThat(dropped("queue_full")).isEqualTo(2.0);
    }

    @Test
    void 줄에_넘기기만_하면_버린_것으로_세지_않는다() {
        NotificationRealtimeRelay relay = relay(task -> { });

        relay.publishAfterCommit(1L, 10L);

        assertThat(registry.find(NotificationRealtimeRelay.DROPPED_METRIC).counters()).isEmpty();
    }

    private NotificationRealtimeRelay relay(TaskExecutor executor) {
        return new NotificationRealtimeRelay(mock(NotificationRepository.class),
                provider(NotificationRealtimePublisher.class, (receiver, frame) -> { }),
                provider(TaskExecutor.class, executor), mock(PlatformTransactionManager.class),
                mock(MemberMaskLocks.class), provider(io.micrometer.core.instrument.MeterRegistry.class, registry));
    }

    private double dropped(String reason) {
        return registry.get(NotificationRealtimeRelay.DROPPED_METRIC).tag("reason", reason).counter().count();
    }

    private static <T> ObjectProvider<T> provider(Class<T> type, T bean) {
        return new StaticListableBeanFactory(Map.of("bean", bean)).getBeanProvider(type);
    }
}
