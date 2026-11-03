package com.hapbang.chat.app;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.support.ScheduledTaskObservationContext;

import com.hapbang.chat.global.config.ObservationConfig;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationPredicate;
import io.micrometer.observation.ObservationRegistry;

/**
 * 짧은 주기 polling 작업의 {@code @Scheduled} 실행은 trace를 만들지 않고, 1분 이상 주기 배치는 만든다 (#631).
 * <p>
 * Spring은 {@code @Scheduled} 실행마다 {@code tasks.scheduled.execution} observation을 대상 객체로 만든
 * {@link ScheduledTaskObservationContext}와 함께 연다. 그 판정만 확인한다.
 */
class ChatPollingTasksTest {

    private static final String SCHEDULED = "tasks.scheduled.execution";

    private final ObservationPredicate predicate = new ObservationConfig().noRootInfrastructureObservation();

    @Test
    void 일초_주기_활동_알림_묶음과_하트비트는_trace를_만들지_않는다() throws Exception {
        assertThat(predicate.test(SCHEDULED, new ScheduledTaskObservationContext(
                new ChatRoomActivityBatch(null), ChatRoomActivityBatch.class.getDeclaredMethod("run")))).isFalse();
        assertThat(predicate.test(SCHEDULED, new ScheduledTaskObservationContext(
                new ChatPresenceHeartbeat(null, null, null), ChatPresenceHeartbeat.class.getDeclaredMethod("run"))))
                .isFalse();
    }

    @Test
    void 일분_이상_주기_배치는_trace를_만든다() throws Exception {
        assertThat(predicate.test(SCHEDULED, new ScheduledTaskObservationContext(
                new ChatGroupRoomBatch(null, null), ChatGroupRoomBatch.class.getDeclaredMethod("expireHistory"))))
                .isTrue();
    }

    @Test
    void 부모가_있는_observation은_막지_않는다() throws Exception {
        ScheduledTaskObservationContext polling = new ScheduledTaskObservationContext(
                new ChatRoomActivityBatch(null), ChatRoomActivityBatch.class.getDeclaredMethod("run"));
        polling.setParentObservation(Observation.start("parent", ObservationRegistry.create()));

        assertThat(predicate.test(SCHEDULED, polling)).isTrue();
        assertThat(predicate.test("spring.security.filterchains", withParent())).isTrue();
    }

    @Test
    void 부모_없는_Security_observation은_막는다() {
        assertThat(predicate.test("spring.security.filterchains", new Observation.Context())).isFalse();
        assertThat(predicate.test("http.server.requests", new Observation.Context())).isTrue();
    }

    private static Observation.Context withParent() {
        Observation.Context context = new Observation.Context();
        context.setParentObservation(Observation.start("parent", ObservationRegistry.create()));
        return context;
    }
}
