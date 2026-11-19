package com.hapbang.auxiliary.notification.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * 실시간 발행 설정값 묶기와 발행 스레드 빈 등록(ND-226·ND-227, test-spec NT-REF-07). 키 이름·기본값은 묶기 전과 같고, 두 개수는
 * 1 이상이어야 기동한다. 설정값은 실시간 전달을 끈 실행에서도 바인딩되고(Relay는 항상 만들어진다), 발행 스레드 빈은 켠 실행에만 있다.
 */
class NotificationRealtimePublishPropertiesTest {

    private static final String PREFIX = "hapbang.notification.realtime.publish.";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(NotificationRealtimeRelay.class, NotificationRealtimePublishPropertiesTest::idleRelay)
            .withUserConfiguration(NotificationRealtimeConfig.class);

    @Test
    void 설정이_없으면_묶기_전과_같은_기본값을_쓴다() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            NotificationRealtimePublishProperties properties =
                    context.getBean(NotificationRealtimePublishProperties.class);
            assertThat(properties.failureBackoff()).isEqualTo(Duration.ofSeconds(5));
            assertThat(properties.queueCapacity()).isEqualTo(1000);
            assertThat(properties.batchSize()).isEqualTo(100);
        });
    }

    @Test
    void 같은_키의_설정값을_읽는다() {
        runner.withPropertyValues(PREFIX + "failure-backoff=200ms", PREFIX + "queue-capacity=50",
                PREFIX + "batch-size=7").run(context -> {
                    NotificationRealtimePublishProperties properties =
                            context.getBean(NotificationRealtimePublishProperties.class);
                    assertThat(properties.failureBackoff()).isEqualTo(Duration.ofMillis(200));
                    assertThat(properties.queueCapacity()).isEqualTo(50);
                    assertThat(properties.batchSize()).isEqualTo(7);
                });
    }

    @Test
    void 줄_상한이_0이면_기동에_실패한다() {
        runner.withPropertyValues(PREFIX + "queue-capacity=0").run(context -> assertThat(context).hasFailed());
    }

    @Test
    void 묶음_크기가_0이면_기동에_실패한다() {
        runner.withPropertyValues(PREFIX + "batch-size=0").run(context -> assertThat(context).hasFailed());
    }

    @Test
    void 실시간_전달을_켜면_발행_스레드_빈이_같은_이름으로_있다() {
        runner.run(context -> assertThat(context).hasSingleBean(NotificationRealtimeWorker.class)
                .hasBean("notificationRealtimeWorker"));
    }

    @Test
    void 실시간_전달을_꺼도_설정값은_바인딩되고_발행_스레드_빈은_없다() {
        runner.withPropertyValues(ConditionalOnNotificationRealtime.REALTIME_ENABLED + "=false").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(NotificationRealtimePublishProperties.class);
            assertThat(context).doesNotHaveBean(NotificationRealtimeWorker.class);
        });
    }

    /** 발행 스레드가 바쁘게 돌지 않도록 기다리는 시간만큼 쉬고 0을 돌려주는 Relay */
    private static NotificationRealtimeRelay idleRelay() {
        NotificationRealtimeRelay relay = mock(NotificationRealtimeRelay.class);
        try {
            doAnswer(invocation -> {
                Thread.sleep(((Duration) invocation.getArgument(0)).toMillis());
                return 0;
            }).when(relay).deliverPending(any());
        } catch (InterruptedException e) {
            // stub 등록은 실제로 부르지 않는다. withBean의 Supplier가 checked 예외를 던질 수 없어 여기서 감싼다
            throw new IllegalStateException(e);
        }
        return relay;
    }
}
