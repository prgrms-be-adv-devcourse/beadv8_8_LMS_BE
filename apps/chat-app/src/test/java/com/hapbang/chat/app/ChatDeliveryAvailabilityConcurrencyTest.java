package com.hapbang.chat.app;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import com.hapbang.chat.app.ChatDeliveryAvailability.PublishPermit;
import com.hapbang.chat.app.ChatDeliveryAvailability.WriteGate;
import com.hapbang.chat.app.ChatDeliveryAvailability.WritePermit;
import com.hapbang.chat.app.ChatOutboxStore.ClaimedJob;
import com.hapbang.chat.app.ChatOutboxStore.DeliveryJob;
import com.hapbang.chat.domain.ChatDeliveryType;
import com.hapbang.chat.domain.ChatNotifyRule;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * {@link ChatDeliveryAvailability} 게이트의 동시성 (재검증). Spring·Redis 없이 PING을 흉내 내는 가짜 큐로
 * 여러 worker·주기 작업이 동시에 게이트를 부르는 상황을 만든다.
 * <ul>
 *     <li>재확인(PING)은 한 번에 한 스레드만 하고, Redis가 오락가락해도 교착 없이 돌아오면 모두 이어 간다.</li>
 *     <li>장애 순간에 진행 중이던 여러 명령이 한꺼번에 실패를 알려도 첫 재확인은 {@code probe-initial} 근처에서 한다
 *     (재확인 실패마다 두 배, 2026-10-30 결정: 1s→30s).</li>
 *     <li>메모리 상한 재확인 권한(PROBE)을 받은 relay가 적재 전에 DB 오류로 끝나도 권한을 돌려준다.</li>
 * </ul>
 */
class ChatDeliveryAvailabilityConcurrencyTest {

    private static final Duration PROBE_INITIAL = Duration.ofMillis(50);
    private static final Duration PROBE_MAX = Duration.ofSeconds(3);

    private final AtomicBoolean redisUp = new AtomicBoolean(true);
    private final AtomicInteger concurrentPings = new AtomicInteger();
    private final AtomicInteger maxConcurrentPings = new AtomicInteger();
    private final AtomicInteger pings = new AtomicInteger();
    private final ChatDeliveryQueue queue = mock(ChatDeliveryQueue.class);
    private final ChatDeliveryProperties properties = properties();
    private final ChatDeliveryAvailability availability = new ChatDeliveryAvailability(queue, properties,
            meterRegistry());
    private ExecutorService executor;

    ChatDeliveryAvailabilityConcurrencyTest() {
        doAnswer(invocation -> {
            int now = concurrentPings.incrementAndGet();
            maxConcurrentPings.accumulateAndGet(now, Math::max);
            pings.incrementAndGet();
            try {
                // 재확인이 겹칠 틈을 넓힌다
                Thread.sleep(5);
                if (!redisUp.get()) {
                    throw new ChatDeliveryUnavailableException("down", null);
                }
                return null;
            } finally {
                concurrentPings.decrementAndGet();
            }
        }).when(queue).ping();
    }

    @AfterEach
    void tearDown() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    @Test
    void 여러_스레드가_Redis가_오락가락하는_동안_게이트를_불러도_재확인은_한_번에_하나고_돌아오면_모두_이어_간다() throws Exception {
        int threads = 11;
        executor = Executors.newFixedThreadPool(threads);
        AtomicBoolean running = new AtomicBoolean(true);
        AtomicInteger opened = new AtomicInteger();
        CountDownLatch started = new CountDownLatch(threads);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int kind = i % 4;
            futures.add(executor.submit(() -> {
                started.countDown();
                while (running.get()) {
                    boolean open = switch (kind) {
                        case 0 -> availability.mayConsume(ChatJobStream.DELIVERY);
                        case 1 -> availability.redisAvailable();
                        case 2 -> {
                            WritePermit permit = availability.tryAcquireWrite();
                            if (permit.isProbe()) {
                                availability.writeSucceeded(permit);
                            }
                            availability.writeEnded(permit);
                            yield permit.gate() != WriteGate.CLOSED;
                        }
                        default -> {
                            PublishPermit permit = availability.tryAcquirePublish(ChatJobStream.CONTROL);
                            if (permit.granted()) {
                                availability.published(permit);
                                availability.publishEnded(permit);
                            }
                            yield permit.granted();
                        }
                    };
                    if (open) {
                        opened.incrementAndGet();
                        if (!redisUp.get()) {
                            // 게이트가 열린 채 Redis 명령을 보냈다가 연결 불가를 받은 경우
                            availability.redisUnavailable(new ChatDeliveryUnavailableException("down", null));
                        }
                    }
                    Thread.onSpinWait();
                }
                return null;
            }));
        }
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

        for (int cycle = 0; cycle < 6; cycle++) {
            redisUp.set(false);
            availability.redisUnavailable(new ChatDeliveryUnavailableException("down", null));
            Thread.sleep(80);
            redisUp.set(true);
            Thread.sleep(40);
        }
        redisUp.set(true);

        // 돌아온 뒤에는 재확인 간격 상한 안에 게이트가 열린다(교착·영구 멈춤 없음)
        await().atMost(PROBE_MAX.plusSeconds(2)).pollInterval(Duration.ofMillis(10))
                .until(() -> availability.redisAvailable() && availability.mayConsume(ChatJobStream.DELIVERY)
                        && availability.tryAcquireWrite().gate() == WriteGate.OPEN
                        && availability.tryAcquirePublish(ChatJobStream.CONTROL).granted());
        int before = opened.get();
        await().atMost(Duration.ofSeconds(2)).until(() -> opened.get() > before + 1000);

        running.set(false);
        for (Future<?> future : futures) {
            future.get(5, TimeUnit.SECONDS);
        }
        assertThat(maxConcurrentPings.get()).as("동시에 진행한 재확인 PING 수").isEqualTo(1);
        assertThat(pings.get()).isPositive();
    }

    @Test
    void 장애_순간_진행_중이던_명령_여러_개가_한꺼번에_실패를_알려도_첫_재확인은_probe_initial_근처에서_한다() throws Exception {
        // 새 작업 worker 3개 + 큐별 주기 작업(relay·회수·재구성·정리) 일부가 같은 장애로 동시에 실패한 상황
        int inFlight = 8;
        redisUp.set(false);
        executor = Executors.newFixedThreadPool(inFlight);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < inFlight; i++) {
            futures.add(executor.submit(() -> {
                go.await();
                availability.redisUnavailable(new ChatDeliveryUnavailableException("Connection closed", null));
                return null;
            }));
        }
        go.countDown();
        for (Future<?> future : futures) {
            future.get(5, TimeUnit.SECONDS);
        }
        long failedAt = System.nanoTime();
        // 짧은 순단: 바로 돌아왔다
        redisUp.set(true);

        await().atMost(PROBE_MAX.plusSeconds(1)).pollInterval(Duration.ofMillis(5))
                .until(availability::redisAvailable);
        Duration resumedAfter = Duration.ofNanos(System.nanoTime() - failedAt);

        // 재확인 실패는 한 번도 없었으므로 첫 재확인은 probe-initial(50ms) 뒤여야 한다.
        // 같은 장애를 알린 호출 수만큼 간격이 두 배씩 늘면 probe-max(3s)까지 기다린다
        assertThat(resumedAfter).as("짧은 순단 뒤 다시 이어 가기까지 걸린 시간")
                .isLessThan(PROBE_INITIAL.multipliedBy(6));
    }

    @Test
    void 메모리_상한_재확인_권한을_받은_relay가_적재_전에_DB_오류로_끝나도_적재를_다시_재확인한다() {
        ChatOutboxStore store = mock(ChatOutboxStore.class);
        ChatOutboxRelay relay = new ChatOutboxRelay(store, queue, properties, availability);
        DeliveryJob job = new DeliveryJob(1L, UUID.randomUUID(), ChatDeliveryType.MESSAGE, 1L, 1L, 1L, 1L,
                ChatNotifyRule.NONE, 1);
        when(store.claim(eq(ChatJobStream.DELIVERY), any(), any(), any(), anyInt()))
                .thenReturn(List.of(new ClaimedJob(job, UUID.randomUUID(), 1)));
        when(store.markEnqueued(eq(ChatJobStream.DELIVERY), anyLong(), any(), any(), any())).thenReturn(true);
        when(queue.enqueue(eq(ChatJobStream.DELIVERY), anyMap())).thenReturn("1-0");
        // 처리권 만료 회수(선점 전 DB 문장)가 한 번 실패한다
        when(store.releaseExpiredLeases(eq(ChatJobStream.DELIVERY), any(), anyInt(), anyInt()))
                .thenThrow(new IllegalStateException("DB 연결 실패"))
                .thenReturn(0);

        availability.memoryFull(new ChatDeliveryBackpressureException("OOM", null));
        await().pollDelay(PROBE_INITIAL.multipliedBy(2)).atMost(Duration.ofSeconds(1)).until(() -> true);
        assertThatThrownBy(() -> relay.relayBatch(ChatJobStream.DELIVERY)).hasMessageContaining("DB 연결 실패");

        // DB가 돌아왔고 Redis 메모리도 비었다: 다음 재확인 시각에 한 건을 적재해 보고 적재를 이어 가야 한다
        await().atMost(PROBE_MAX.plusSeconds(1)).pollInterval(Duration.ofMillis(20)).until(() -> {
            relay.relayBatch(ChatJobStream.DELIVERY);
            return mockingDetails(queue).getInvocations().stream()
                    .anyMatch(invocation -> invocation.getMethod().getName().equals("enqueue"));
        });
        assertThat(availability.tryAcquireWrite().gate()).isEqualTo(WriteGate.OPEN);
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
                new ChatDeliveryProperties.Pause(PROBE_INITIAL, PROBE_MAX),
                Duration.ofSeconds(10));
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<MeterRegistry> meterRegistry() {
        ObjectProvider<MeterRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return provider;
    }
}
