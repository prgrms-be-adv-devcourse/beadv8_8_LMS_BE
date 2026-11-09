package com.hapbang.chat.app;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.*;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import com.hapbang.chat.app.ChatDeliveryAvailability.PublishPermit;
import com.hapbang.chat.app.ChatDeliveryAvailability.WriteGate;
import com.hapbang.chat.app.ChatDeliveryAvailability.WritePermit;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * {@link ChatDeliveryAvailability} 게이트 네 진입점({@code redisAvailable}·{@code mayConsume}·{@code tryAcquireWrite}·
 * {@code tryAcquirePublish})의 상태별 결과. 정상 상태에서는 모니터를 잡지 않고, 장애 상태의 재확인 권한은 지금처럼 모니터 안에서
 * 하나만 준다. 게이트가 열려 있을 때 받은 허락으로 늦게 성공을 알려도 게이트를 연다.
 */
class ChatDeliveryAvailabilityGateTest {

    private static final Duration PROBE_INITIAL = Duration.ofMillis(50);

    private final ChatDeliveryQueue queue = mock(ChatDeliveryQueue.class);
    private final ChatDeliveryAvailability availability = new ChatDeliveryAvailability(queue, properties(),
            noMeterRegistry());
    private final ExecutorService other = Executors.newSingleThreadExecutor();

    @AfterEach
    void tearDown() {
        other.shutdownNow();
    }

    @Test
    void 정상_상태의_네_진입점은_재확인_권한_없이_허락하고_Redis를_확인하지_않는다() {
        assertThat(availability.redisAvailable()).isTrue();
        assertThat(availability.mayConsume(ChatJobStream.DELIVERY)).isTrue();
        assertThat(availability.mayConsume(ChatJobStream.CONTROL)).isTrue();
        assertThat(availability.tryAcquireWrite()).isEqualTo(new WritePermit(WriteGate.OPEN, 0));
        assertThat(availability.tryAcquirePublish(ChatJobStream.DELIVERY))
                .isEqualTo(new PublishPermit(ChatJobStream.DELIVERY, true, 0));

        verifyNoInteractions(queue);
    }

    @Test
    void 다른_스레드가_모니터를_쥔_동안에도_정상_상태의_네_진입점은_기다리지_않고_돌아온다() throws Exception {
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> holder = other.submit(() -> {
            synchronized (availability) {
                held.countDown();
                release.await();
            }
            return null;
        });
        assertThat(held.await(1, TimeUnit.SECONDS)).isTrue();
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> result = caller.submit(() -> availability.redisAvailable()
                    && availability.mayConsume(ChatJobStream.DELIVERY)
                    && availability.tryAcquireWrite().gate() == WriteGate.OPEN
                    && availability.tryAcquirePublish(ChatJobStream.CONTROL).granted());

            assertThat(result.get(1, TimeUnit.SECONDS)).as("모니터를 기다리지 않고 모두 허락").isTrue();
        } finally {
            release.countDown();
            caller.shutdownNow();
        }
        holder.get(1, TimeUnit.SECONDS);
    }

    @Test
    void 장애_상태의_재확인_권한은_모니터_안에서_준다() throws Exception {
        availability.memoryFull(new ChatDeliveryBackpressureException("OOM", null));
        await().pollDelay(PROBE_INITIAL.plusMillis(10)).atMost(Duration.ofSeconds(1)).until(() -> true);
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> holder = other.submit(() -> {
            synchronized (availability) {
                held.countDown();
                release.await();
            }
            return null;
        });
        assertThat(held.await(1, TimeUnit.SECONDS)).isTrue();
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            Future<WritePermit> probe = caller.submit(availability::tryAcquireWrite);
            assertThatThrownBy(() -> probe.get(100, TimeUnit.MILLISECONDS))
                    .as("멈춘 중이면 모니터를 기다린다").isInstanceOf(TimeoutException.class);

            release.countDown();
            assertThat(probe.get(1, TimeUnit.SECONDS).isProbe()).isTrue();
        } finally {
            release.countDown();
            caller.shutdownNow();
        }
        holder.get(1, TimeUnit.SECONDS);
    }

    @Test
    void Redis가_멈추면_네_진입점이_닫히고_재확인은_한_호출만_하며_돌아오면_모두_열린다() throws Exception {
        availability.redisUnavailable(new ChatDeliveryUnavailableException("down", null));

        assertThat(availability.redisAvailable()).isFalse();
        assertThat(availability.mayConsume(ChatJobStream.DELIVERY)).isFalse();
        assertThat(availability.tryAcquireWrite()).isEqualTo(new WritePermit(WriteGate.CLOSED, 0));
        assertThat(availability.tryAcquirePublish(ChatJobStream.CONTROL))
                .isEqualTo(new PublishPermit(ChatJobStream.CONTROL, false, 0));
        verify(queue, never()).ping();

        // 재확인 시각이 되면 한 호출만 확인하고, 확인 중인 동안 다른 호출은 닫힌 채 돌아온다
        CountDownLatch pinging = new CountDownLatch(1);
        CountDownLatch answer = new CountDownLatch(1);
        doAnswer(invocation -> {
            pinging.countDown();
            answer.await();
            return null;
        }).when(queue).ping();
        await().pollDelay(PROBE_INITIAL.plusMillis(10)).atMost(Duration.ofSeconds(1)).until(() -> true);
        Future<Boolean> prober = other.submit(availability::redisAvailable);
        assertThat(pinging.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(availability.redisAvailable()).isFalse();
        assertThat(availability.tryAcquireWrite().gate()).isEqualTo(WriteGate.CLOSED);
        assertThat(availability.tryAcquirePublish(ChatJobStream.DELIVERY).granted()).isFalse();
        assertThat(availability.mayConsume(ChatJobStream.CONTROL)).isFalse();
        answer.countDown();

        assertThat(prober.get(1, TimeUnit.SECONDS)).isTrue();
        verify(queue, times(1)).ping();
        assertThat(availability.redisAvailable()).isTrue();
        assertThat(availability.mayConsume(ChatJobStream.DELIVERY)).isTrue();
        assertThat(availability.tryAcquireWrite()).isEqualTo(new WritePermit(WriteGate.OPEN, 0));
        assertThat(availability.tryAcquirePublish(ChatJobStream.CONTROL).isProbe()).isFalse();
    }

    @Test
    void 쓰기_거절과_자기_구독_단절은_자기_게이트만_닫는다() {
        availability.memoryFull(new ChatDeliveryBackpressureException("OOM", null));
        assertThat(availability.tryAcquireWrite().gate()).isEqualTo(WriteGate.CLOSED);
        assertThat(availability.redisAvailable()).isTrue();
        assertThat(availability.mayConsume(ChatJobStream.DELIVERY)).isTrue();
        assertThat(availability.tryAcquirePublish(ChatJobStream.DELIVERY).granted()).isTrue();

        availability.noSubscriber(availability.tryAcquirePublish(ChatJobStream.DELIVERY));
        assertThat(availability.mayConsume(ChatJobStream.DELIVERY)).isFalse();
        assertThat(availability.tryAcquirePublish(ChatJobStream.DELIVERY).granted()).isFalse();
        assertThat(availability.mayConsume(ChatJobStream.CONTROL)).isTrue();
        assertThat(availability.tryAcquirePublish(ChatJobStream.CONTROL))
                .isEqualTo(new PublishPermit(ChatJobStream.CONTROL, true, 0));
        assertThat(availability.redisAvailable()).isTrue();
    }

    @Test
    void 장애_상태의_재확인_권한은_토큰을_가지고_한_번에_하나만_준다() {
        availability.memoryFull(new ChatDeliveryBackpressureException("OOM", null));
        availability.noSubscriber(availability.tryAcquirePublish(ChatJobStream.CONTROL));
        await().pollDelay(PROBE_INITIAL.plusMillis(10)).atMost(Duration.ofSeconds(1)).until(() -> true);

        WritePermit write = availability.tryAcquireWrite();
        PublishPermit publish = availability.tryAcquirePublish(ChatJobStream.CONTROL);
        assertThat(write.isProbe()).isTrue();
        assertThat(publish.granted()).isTrue();
        assertThat(publish.isProbe()).isTrue();
        assertThat(write.token()).isNotEqualTo(publish.token());
        assertThat(availability.tryAcquireWrite().gate()).isEqualTo(WriteGate.CLOSED);
        assertThat(availability.tryAcquirePublish(ChatJobStream.CONTROL).granted()).isFalse();
        assertThat(availability.mayConsume(ChatJobStream.CONTROL)).isFalse();

        // 결과 없이 돌려주면 재확인 시각은 그대로라 바로 다시 받는다
        availability.writeEnded(write);
        availability.publishEnded(publish);
        WritePermit again = availability.tryAcquireWrite();
        PublishPermit publishAgain = availability.tryAcquirePublish(ChatJobStream.CONTROL);
        assertThat(again.isProbe()).isTrue();
        assertThat(publishAgain.isProbe()).isTrue();

        availability.writeSucceeded(again);
        availability.writeEnded(again);
        availability.published(publishAgain);
        availability.publishEnded(publishAgain);
        assertThat(availability.tryAcquireWrite()).isEqualTo(new WritePermit(WriteGate.OPEN, 0));
        assertThat(availability.tryAcquirePublish(ChatJobStream.CONTROL))
                .isEqualTo(new PublishPermit(ChatJobStream.CONTROL, true, 0));
        assertThat(availability.mayConsume(ChatJobStream.CONTROL)).isTrue();
    }

    @Test
    void 열려_있을_때_받은_허락으로_늦게_성공을_알려도_멈춘_게이트를_연다() {
        WritePermit lateWrite = availability.tryAcquireWrite();
        PublishPermit latePublish = availability.tryAcquirePublish(ChatJobStream.DELIVERY);
        assertThat(lateWrite.isProbe()).isFalse();
        assertThat(latePublish.isProbe()).isFalse();
        availability.memoryFull(new ChatDeliveryBackpressureException("OOM", null));
        availability.noSubscriber(availability.tryAcquirePublish(ChatJobStream.DELIVERY));
        assertThat(availability.tryAcquireWrite().gate()).isEqualTo(WriteGate.CLOSED);
        assertThat(availability.mayConsume(ChatJobStream.DELIVERY)).isFalse();

        availability.writeSucceeded(lateWrite);
        availability.writeEnded(lateWrite);
        availability.published(latePublish);
        availability.publishEnded(latePublish);

        assertThat(availability.tryAcquireWrite()).isEqualTo(new WritePermit(WriteGate.OPEN, 0));
        assertThat(availability.mayConsume(ChatJobStream.DELIVERY)).isTrue();
        assertThat(availability.tryAcquirePublish(ChatJobStream.DELIVERY))
                .isEqualTo(new PublishPermit(ChatJobStream.DELIVERY, true, 0));
    }

    private static ChatDeliveryProperties properties() {
        ChatDeliveryProperties.Backoff backoff = new ChatDeliveryProperties.Backoff(Duration.ofMillis(1),
                Duration.ofMillis(1), 0);
        return new ChatDeliveryProperties(
                new ChatDeliveryProperties.Pipeline(true),
                "test",
                new ChatDeliveryProperties.Relay(Duration.ofMillis(200), 50, Duration.ofSeconds(30), 10, backoff),
                new ChatDeliveryProperties.Consumer(2, 1, 20, Duration.ofMillis(50), 10, backoff),
                new ChatDeliveryProperties.Reclaim(Duration.ofSeconds(5), Duration.ofSeconds(30), 20, 100, 50),
                new ChatDeliveryProperties.Reconstruct(Duration.ofSeconds(60), Duration.ofMinutes(5),
                        Duration.ofMinutes(5), 5, 100),
                new ChatDeliveryProperties.Trim(Duration.ofMinutes(10), Duration.ofHours(24)),
                new ChatDeliveryProperties.Pause(PROBE_INITIAL, Duration.ofSeconds(3)),
                Duration.ofSeconds(10));
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<MeterRegistry> noMeterRegistry() {
        ObjectProvider<MeterRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return provider;
    }
}
