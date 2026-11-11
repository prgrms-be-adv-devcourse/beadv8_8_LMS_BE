package com.hapbang.auxiliary.notification.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * 알림 시작 시각 정하기(ND-076·ND-088·ND-095, test-spec NT-KAF-04). 설정값이 있으면 그 값을 쓰고 DB 기록은 하지 않는다. 설정값이
 * 없으면 빈을 만들 때(Kafka 소비자가 뜨기 전) DB에 지금 시각을 처음 한 번 기록하고 기록된 값을 쓴다. 설정값이 있는데 시간대 있는
 * ISO-8601이 아니면 기동이 실패한다.
 */
class AlertStartPolicyConfigTest {

    private static final Instant RECORDED = Instant.parse("2026-11-11T05:46:00Z");

    private final CountingStore store = new CountingStore();
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(AlertStartStore.class, () -> store)
            .withBean(AlertStartPolicy.class);

    @Test
    void 설정값이_없으면_DB에_기록된_시각을_쓴다() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(AlertStartPolicy.class).startAt()).isEqualTo(RECORDED);
            assertThat(store.calls.get()).isEqualTo(1);
        });
    }

    @Test
    void 설정값이_비어_있어도_DB에_기록된_시각을_쓴다() {
        runner.withPropertyValues(AlertStartPolicy.PROPERTY + "=")
                .run(context -> assertThat(context.getBean(AlertStartPolicy.class).startAt()).isEqualTo(RECORDED));
    }

    @Test
    void 설정값이_있으면_그_값을_쓰고_DB에_기록하지_않는다() {
        runner.withPropertyValues(AlertStartPolicy.PROPERTY + "=2026-11-11T13:00:00+09:00")
                .run(context -> {
                    assertThat(context.getBean(AlertStartPolicy.class).startAt())
                            .isEqualTo(Instant.parse("2026-11-11T04:00:00Z"));
                    assertThat(store.calls.get()).isZero();
                });
    }

    @Test
    void 설정값이_시각으로_읽을_수_없는_값이면_기동에_실패한다() {
        runner.withPropertyValues(AlertStartPolicy.PROPERTY + "=2026-11-11 13:00")
                .run(context -> assertThat(context).hasFailed());
    }

    /** 처음 기록된 값을 돌려주는 가짜 저장소. 부른 횟수를 센다 */
    static final class CountingStore implements AlertStartStore {

        final AtomicInteger calls = new AtomicInteger();

        @Override
        public Instant recordIfAbsent(String key, Instant now) {
            calls.incrementAndGet();
            return RECORDED;
        }
    }
}
