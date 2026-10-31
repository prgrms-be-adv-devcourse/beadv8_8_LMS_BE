package com.hapbang.chat.app;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.*;
import static org.mockito.Mockito.*;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import com.hapbang.chat.app.ChatDeliveryAvailability.PublishPermit;
import com.hapbang.chat.app.ChatDeliveryAvailability.WriteGate;
import com.hapbang.chat.app.ChatDeliveryAvailability.WritePermit;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * 재확인 권한은 그 권한을 받은 호출만 결과를 알리고 돌려준다 (Codex 리뷰). 게이트가 열려 있을 때 시작해 늦게 끝난 호출이 다른
 * 호출의 재확인 권한을 지워 재확인이 둘 진행되면 안 된다. 잠정적으로 다시 연 큐의 첫 0명은 재확인 실패로 센다.
 */
class ChatDeliveryAvailabilityProbeOwnershipTest {

    private static final Duration PROBE_INITIAL = Duration.ofMillis(50);

    private final ChatDeliveryQueue queue = mock(ChatDeliveryQueue.class);
    private final ChatDeliveryAvailability availability = new ChatDeliveryAvailability(queue, properties(),
            noMeterRegistry());

    @Test
    void 늦게_끝난_비재확인_발행이_다른_호출의_시험_발행_권한을_지우지_않는다() {
        // A: 게이트가 열려 있을 때 발행을 시작했다가 늦어진다
        PublishPermit late = availability.tryAcquirePublish(ChatJobStream.DELIVERY);
        assertThat(late.granted()).isTrue();
        assertThat(late.isProbe()).isFalse();
        // B: 구독자 0명을 받아 큐를 멈춘다
        availability.noSubscriber(availability.tryAcquirePublish(ChatJobStream.DELIVERY));
        assertThat(availability.tryAcquirePublish(ChatJobStream.DELIVERY).granted()).isFalse();
        // C: 재확인 시각에 시험 발행 권한을 받고 발행 중이다
        PublishPermit probe = awaitPublishProbe();

        // A가 늦게 끝나 권한을 돌려주고 결과도 알린다
        availability.publishEnded(late);
        availability.noSubscriber(late);

        // D: C가 끝나기 전에는 두 번째 시험 발행 권한이 없다
        assertThat(availability.tryAcquirePublish(ChatJobStream.DELIVERY).granted()).isFalse();
        assertThat(availability.mayConsume(ChatJobStream.DELIVERY)).isFalse();

        // C의 시험 발행도 0명이었다. 간격이 늘어(100ms) probe-initial(50ms) 뒤에는 아직 권한이 없다
        availability.noSubscriber(probe);
        availability.publishEnded(probe);
        await().pollDelay(PROBE_INITIAL.plusMillis(20)).atMost(Duration.ofSeconds(1)).until(() -> true);
        assertThat(availability.tryAcquirePublish(ChatJobStream.DELIVERY).granted()).isFalse();
        PublishPermit next = awaitPublishProbe();
        availability.published(next);
        availability.publishEnded(next);
        assertThat(availability.mayConsume(ChatJobStream.DELIVERY)).isTrue();
    }

    @Test
    void 늦게_끝난_비재확인_적재가_다른_호출의_적재_재확인_권한을_지우지_않는다() {
        WritePermit late = availability.tryAcquireWrite();
        assertThat(late.gate()).isEqualTo(WriteGate.OPEN);
        availability.memoryFull(availability.tryAcquireWrite(), new ChatDeliveryBackpressureException("OOM", null));
        WritePermit probe = awaitWriteProbe();

        availability.writeEnded(late);
        availability.memoryFull(late, new ChatDeliveryBackpressureException("OOM", null));

        assertThat(availability.tryAcquireWrite().gate()).isEqualTo(WriteGate.CLOSED);
        availability.writeSucceeded(probe);
        availability.writeEnded(probe);
        assertThat(availability.tryAcquireWrite().gate()).isEqualTo(WriteGate.OPEN);
    }

    @Test
    void 잠정적으로_다시_연_큐의_첫_0명은_재확인_실패로_세어_간격을_늘린다() {
        availability.noSubscriber(availability.tryAcquirePublish(ChatJobStream.CONTROL));
        // 재확인 시각이 지났는데 시험 발행할 작업이 없어 잠정적으로 연다
        await().pollDelay(PROBE_INITIAL.plusMillis(10)).atMost(Duration.ofSeconds(1)).until(() -> true);
        availability.reopenIfNothingToProbe(ChatJobStream.CONTROL);
        assertThat(availability.mayConsume(ChatJobStream.CONTROL)).isTrue();

        // 다시 연 뒤 첫 발행도 0명: 재확인 실패로 세어 다음 간격은 100ms다
        availability.noSubscriber(availability.tryAcquirePublish(ChatJobStream.CONTROL));
        long reclosedAt = System.nanoTime();
        PublishPermit probe = awaitPublishProbe(ChatJobStream.CONTROL);
        assertThat(Duration.ofNanos(System.nanoTime() - reclosedAt)).isGreaterThanOrEqualTo(PROBE_INITIAL.multipliedBy(2));
        availability.publishEnded(probe);
    }

    @Test
    void 잠정적으로_다시_연_큐가_발행에_성공하면_이후의_별도_단절은_새_장애로_시작한다() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ChatDeliveryAvailability counted = new ChatDeliveryAvailability(queue, properties(), meterRegistry(registry));
        // 첫 단절 → 재확인 시각에 시험 발행할 작업이 없어 잠정적으로 다시 연다(재확인 실패 0회)
        counted.noSubscriber(counted.tryAcquirePublish(ChatJobStream.CONTROL));
        await().pollDelay(PROBE_INITIAL.plusMillis(10)).atMost(Duration.ofSeconds(1)).until(() -> true);
        counted.reopenIfNothingToProbe(ChatJobStream.CONTROL);
        // 다시 연 뒤의 발행이 구독자에게 닿았다
        PublishPermit delivered = counted.tryAcquirePublish(ChatJobStream.CONTROL);
        counted.published(delivered);
        counted.publishEnded(delivered);
        assertThat(lossCount(registry)).isEqualTo(1);

        // 나중의 별도 단절: 새 장애로 세고 첫 재확인은 probe-initial 뒤다
        counted.noSubscriber(counted.tryAcquirePublish(ChatJobStream.CONTROL));
        long lostAt = System.nanoTime();
        PublishPermit[] probe = new PublishPermit[1];
        await().atMost(Duration.ofSeconds(2)).pollInterval(Duration.ofMillis(5)).until(() -> {
            PublishPermit permit = counted.tryAcquirePublish(ChatJobStream.CONTROL);
            probe[0] = permit;
            return permit.granted();
        });
        Duration waited = Duration.ofNanos(System.nanoTime() - lostAt);

        assertThat(lossCount(registry)).as("새 단절 카운터").isEqualTo(2);
        assertThat(probe[0].isProbe()).isTrue();
        assertThat(waited).isGreaterThanOrEqualTo(PROBE_INITIAL).isLessThan(PROBE_INITIAL.multipliedBy(2));
        counted.publishEnded(probe[0]);
    }

    private static double lossCount(SimpleMeterRegistry registry) {
        var counter = registry.find("chat.delivery.paused").tag("target", "subscription-CONTROL").counter();
        return counter == null ? 0 : counter.count();
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<MeterRegistry> meterRegistry(MeterRegistry registry) {
        ObjectProvider<MeterRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(registry);
        return provider;
    }

    private PublishPermit awaitPublishProbe() {
        return awaitPublishProbe(ChatJobStream.DELIVERY);
    }

    private PublishPermit awaitPublishProbe(ChatJobStream stream) {
        PublishPermit[] acquired = new PublishPermit[1];
        await().atMost(Duration.ofSeconds(2)).pollInterval(Duration.ofMillis(5)).until(() -> {
            PublishPermit permit = availability.tryAcquirePublish(stream);
            if (permit.granted()) {
                acquired[0] = permit;
                return true;
            }
            return false;
        });
        assertThat(acquired[0].isProbe()).isTrue();
        return acquired[0];
    }

    private WritePermit awaitWriteProbe() {
        WritePermit[] acquired = new WritePermit[1];
        await().atMost(Duration.ofSeconds(2)).pollInterval(Duration.ofMillis(5)).until(() -> {
            WritePermit permit = availability.tryAcquireWrite();
            if (permit.gate() != WriteGate.CLOSED) {
                acquired[0] = permit;
                return true;
            }
            return false;
        });
        assertThat(acquired[0].isProbe()).isTrue();
        return acquired[0];
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
