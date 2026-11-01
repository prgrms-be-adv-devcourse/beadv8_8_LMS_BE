package com.hapbang.chat.in.websocket;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.*;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.support.ExecutorSubscribableChannel;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.test.util.ReflectionTestUtils;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import lombok.extern.slf4j.Slf4j;

/**
 * 통합 재검증(2026-10-30): 연결별 줄 실행기({@link ChatInboundReceiveBudget#channelExecutor()})의 정리·경쟁·공정성을 Spring 채널로 확인한다.
 * <ul>
 *     <li>연결별 줄(내부 {@code queues})은 세션 종료 정리(DISCONNECT)를 처리하면 지워져야 한다(메모리).</li>
 *     <li>여러 연결이 동시에 보내도 프레임을 잃거나 두 번 처리하지 않고, 처리 대기 줄이 남지 않는다(깨우기 유실·중복 예약 없음).</li>
 *     <li>뜨거운 연결이 있어도 다른 연결의 대기 시간은 한 줄 비우기 단위로 묶인다(공정성). 이전 방식(공용 실행기 직접)과 비교한다.</li>
 * </ul>
 */
@Slf4j
class ChatInboundSessionQueueTest {

    private final ChatWebSocketSessionRegistry registry = mock(ChatWebSocketSessionRegistry.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final CountDownLatch release = new CountDownLatch(1);
    private final List<ChatInboundReceiveBudget> budgets = new ArrayList<>();

    @AfterEach
    void tearDown() {
        release.countDown();
        budgets.forEach(ChatInboundReceiveBudget::destroy);
    }

    /**
     * 연결이 끊길 때 그 연결의 줄에 SEND 9개(처리기 셋 = 작업 27개)가 남아 있으면 세션 종료 정리(작업 3개)가 정확히 한 줄 비우기 단위(30)의
     * 끝에 놓인다. 그 줄 비우기는 DISCONNECT를 처리한 뒤 차례를 넘기고, 다음 실행은 빈 줄을 보지만 DISCONNECT를 본 적이 없어 줄을 지우지 않는다.
     */
    @Test
    void 세션_종료_정리가_한_줄_비우기의_마지막_작업이어도_연결의_줄은_지워진다() throws Exception {
        ChatInboundReceiveBudget budget = budget(1, 1000, 100);
        ExecutorSubscribableChannel inbound = threeHandlerChannel(budget);
        blockWorker(inbound);

        // 연결이 처리를 기다리는 SEND 9개를 쌓아 둔 채 끊긴다(작업 27개 + 세션 종료 정리 3개 = 30)
        for (int i = 0; i < 9; i++) {
            inbound.send(frame("s-leak", "send-" + i));
        }
        inbound.send(disconnect("s-leak"));
        release.countDown();

        await().atMost(Duration.ofSeconds(5)).until(() -> budget.sessionsWithQueuedWork() == 0
                && budget.executor().getActiveCount() == 0 && budget.executor().getQueueSize() == 0);
        assertThat(queues(budget)).doesNotContainKey("s-leak");
    }

    @Test
    void 세션_종료_정리가_한_줄_비우기_중간에_오면_연결의_줄은_지워진다() throws Exception {
        ChatInboundReceiveBudget budget = budget(1, 1000, 100);
        ExecutorSubscribableChannel inbound = threeHandlerChannel(budget);
        blockWorker(inbound);

        for (int i = 0; i < 8; i++) {
            inbound.send(frame("s-ok", "send-" + i));
        }
        inbound.send(disconnect("s-ok"));
        release.countDown();

        await().atMost(Duration.ofSeconds(5)).until(() -> budget.sessionsWithQueuedWork() == 0
                && budget.executor().getActiveCount() == 0 && budget.executor().getQueueSize() == 0);
        // s-block은 세션 종료 정리를 보내지 않았으므로 남는다
        await().atMost(Duration.ofSeconds(1)).until(() -> !queues(budget).containsKey("s-ok"));
    }

    /**
     * 연결마다 한 스레드가 보내고(WebSocket 수신 스레드처럼) 처리기 셋이 구독한 채널에서, 64개 연결이 2,000개씩 동시에 보낸다.
     * 모든 프레임이 처리기마다 정확히 한 번, 연결 안에서는 보낸 순서대로 처리되고, 끝나면 처리를 기다리는 줄이 남지 않는다.
     * 줄에 넣기와 줄 비우기 종료가 엇갈려 줄은 비지 않았는데 비우기 작업이 없는 상태(깨우기 유실)나, 한 줄을 두 작업이 동시에 비우는
     * 상태(중복 예약)가 생기면 처리 수·순서·동시 처리 검사에서 드러난다.
     */
    @RepeatedTest(3)
    void 여러_연결이_동시에_보내도_프레임을_잃거나_겹쳐_처리하지_않고_줄이_남지_않는다() throws Exception {
        int sessions = 64;
        int framesPerSession = 2_000;
        ChatInboundReceiveBudget budget = budget(8, 100_000, 1_000_000);
        ExecutorSubscribableChannel inbound = new ExecutorSubscribableChannel(budget.channelExecutor());
        AtomicIntegerArray[] lastSeen = new AtomicIntegerArray[3];
        AtomicIntegerArray inFlight = new AtomicIntegerArray(sessions);
        AtomicInteger outOfOrder = new AtomicInteger();
        AtomicInteger overlapped = new AtomicInteger();
        AtomicInteger handled = new AtomicInteger();
        for (int h = 0; h < 3; h++) {
            AtomicIntegerArray seen = new AtomicIntegerArray(sessions);
            for (int s = 0; s < sessions; s++) {
                seen.set(s, -1);
            }
            lastSeen[h] = seen;
            inbound.subscribe(message -> {
                String[] parts = payloadOf(message).split(":");
                int session = Integer.parseInt(parts[0]);
                int index = Integer.parseInt(parts[1]);
                if (inFlight.incrementAndGet(session) != 1) {
                    overlapped.incrementAndGet();
                }
                if (seen.getAndSet(session, index) != index - 1) {
                    outOfOrder.incrementAndGet();
                }
                handled.incrementAndGet();
                inFlight.decrementAndGet(session);
            });
        }

        ExecutorService producers = Executors.newFixedThreadPool(sessions);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();
            for (int s = 0; s < sessions; s++) {
                int session = s;
                futures.add(producers.submit(() -> {
                    start.await();
                    for (int i = 0; i < framesPerSession; i++) {
                        inbound.send(frame("s-" + session, session + ":" + i));
                        if (i % 97 == 0) {
                            Thread.yield();
                        }
                    }
                    inbound.send(disconnect("s-" + session));
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            producers.shutdownNow();
        }

        int expected = sessions * (framesPerSession + 1) * 3;
        await().atMost(Duration.ofSeconds(30)).until(() -> handled.get() == expected);
        assertThat(overlapped.get()).as("한 연결의 작업이 동시에 처리된 횟수").isZero();
        // DISCONNECT 메시지의 payload는 "<세션>:<프레임 수>"로 순서 검사에 포함된다
        assertThat(outOfOrder.get()).as("연결 안에서 순서가 바뀐 횟수").isZero();
        assertThat(budget.sessionsWithQueuedWork()).isZero();
        assertThat(meterRegistry.counter(ChatInboundReceiveBudget.REJECTED_METRIC).count()).isZero();
        verify(registry, never()).closeSession(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any());
        await().atMost(Duration.ofSeconds(5)).until(() -> budget.executor().getActiveCount() == 0
                && budget.executor().getQueueSize() == 0);
        Thread.sleep(200);
        System.out.println("STRESS handled=" + handled.get() + " leftoverSessionQueues=" + queues(budget).size()
                + "/" + sessions);
    }

    /**
     * 공정성: 처리 스레드 2개에 뜨거운 연결 4개가 몫 바로 아래(19프레임)를 계속 채우고(작업 하나 1ms), 일반 연결 20개가 20ms마다 한 프레임씩
     * 보낸다. 일반 연결의 프레임이 처리 시작까지 기다린 시간을 연결별 줄(지금)과 공용 실행기 직접(이전 방식, 연결별 몫 없음)으로 잰다.
     */
    @Test
    void 뜨거운_연결이_몫_가장자리를_채워도_다른_연결의_대기_시간은_한_줄_비우기_단위로_묶인다() throws Exception {
        Latencies perSession = measureFairness(true);
        Latencies plainPool = measureFairness(false);
        log.info("공정성(일반 연결 처리 대기, ms) 연결별 줄: {} / 공용 실행기 직접(이전): {}", perSession, plainPool);
        System.out.println("FAIRNESS per-session=" + perSession + " plain-pool=" + plainPool);

        // 뜨거운 연결 4개 x 한 줄 비우기 30작업 x 1ms / 스레드 2개 ~ 60ms 차례. 여유를 두고 300ms 안
        assertThat(perSession.p99()).isLessThan(300);
        assertThat(perSession.hotClosed()).isZero();
    }

    private Latencies measureFairness(boolean perSessionQueue) throws Exception {
        int hotSessions = 4;
        int normalSessions = 20;
        int perSessionFrames = 20;
        ChatInboundReceiveBudget budget = budget(2, 1000, perSessionFrames);
        ExecutorSubscribableChannel inbound = new ExecutorSubscribableChannel(
                perSessionQueue ? budget.channelExecutor() : budget.executor());
        Map<String, Long> sentAt = new ConcurrentHashMap<>();
        List<Long> waits = new CopyOnWriteArrayList<>();
        AtomicIntegerArray hotOutstanding = new AtomicIntegerArray(hotSessions);
        for (int h = 0; h < 3; h++) {
            int handler = h;
            inbound.subscribe(message -> {
                String payload = payloadOf(message);
                if (payload.startsWith("hot")) {
                    sleepMillis(1);
                    if (handler == 2) {
                        hotOutstanding.decrementAndGet(Integer.parseInt(payload.split(":")[1]));
                    }
                } else if (handler == 0) {
                    Long sent = sentAt.remove(payload);
                    if (sent != null) {
                        waits.add(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - sent));
                    }
                }
            });
        }
        AtomicBoolean running = new AtomicBoolean(true);
        ExecutorService producers = Executors.newFixedThreadPool(hotSessions + normalSessions);
        try {
            for (int h = 0; h < hotSessions; h++) {
                int hot = h;
                producers.submit(() -> {
                    int i = 0;
                    while (running.get()) {
                        // 몫 바로 아래까지만 채운다(처리를 시작하지 않은 프레임 19개 이하)
                        if (hotOutstanding.get(hot) < perSessionFrames - 1) {
                            hotOutstanding.incrementAndGet(hot);
                            inbound.send(frame("s-hot-" + hot, "hot:" + hot + ":" + i++));
                        } else {
                            Thread.onSpinWait();
                        }
                    }
                    return null;
                });
            }
            Thread.sleep(200);
            for (int n = 0; n < normalSessions; n++) {
                int normal = n;
                producers.submit(() -> {
                    for (int i = 0; i < 40 && running.get(); i++) {
                        String payload = "normal:" + normal + ":" + i;
                        sentAt.put(payload, System.nanoTime());
                        inbound.send(frame("s-normal-" + normal, payload));
                        Thread.sleep(20);
                    }
                    return null;
                });
            }
            Thread.sleep(1_200);
        } finally {
            running.set(false);
            producers.shutdownNow();
            producers.awaitTermination(5, TimeUnit.SECONDS);
        }
        await().atMost(Duration.ofSeconds(20)).until(() -> sentAt.isEmpty() || budget.executor().getActiveCount() == 0
                && budget.executor().getQueueSize() == 0);
        long[] sorted = waits.stream().mapToLong(Long::longValue).sorted().toArray();
        int hotClosed = (int) meterRegistry.counter(ChatInboundReceiveBudget.REJECTED_METRIC).count();
        budget.destroy();
        return new Latencies(sorted.length, percentile(sorted, 50), percentile(sorted, 95), percentile(sorted, 99),
                sorted.length == 0 ? -1 : sorted[sorted.length - 1], hotClosed);
    }

    private record Latencies(int samples, long p50, long p95, long p99, long max, int hotClosed) {
    }

    private static long percentile(long[] sorted, int percentile) {
        if (sorted.length == 0) {
            return -1;
        }
        int index = (int) Math.ceil(percentile / 100.0 * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(sorted.length - 1, index))];
    }

    /** 처리 스레드 하나를 잡아 두고, 그 사이 다른 연결의 작업은 줄에 쌓이게 한다 */
    private void blockWorker(ExecutorSubscribableChannel inbound) throws InterruptedException {
        CountDownLatch blocking = new CountDownLatch(1);
        inbound.subscribe(message -> {
            if (payloadOf(message).equals("block")) {
                blocking.countDown();
                awaitLatch(release);
            }
        });
        inbound.send(frame("s-block", "block"));
        assertThat(blocking.await(5, TimeUnit.SECONDS)).isTrue();
    }

    /** 수신 채널처럼 처리기 셋(앞의 blockWorker 처리기를 포함해 셋)을 구독한 채널 */
    private static ExecutorSubscribableChannel threeHandlerChannel(ChatInboundReceiveBudget budget) {
        ExecutorSubscribableChannel inbound = new ExecutorSubscribableChannel(budget.channelExecutor());
        inbound.subscribe(message -> {
        });
        inbound.subscribe(message -> {
        });
        return inbound;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, ?> queues(ChatInboundReceiveBudget budget) {
        return (Map<String, ?>) ReflectionTestUtils.getField(budget, "queues");
    }

    private ChatInboundReceiveBudget budget(int workers, int queueCapacity, int perSessionFrames) {
        ChatInboundReceiveBudget budget = new ChatInboundReceiveBudget(registry, meterRegistry, workers, queueCapacity,
                10, perSessionFrames);
        budgets.add(budget);
        return budget;
    }

    private static Message<byte[]> frame(String sessionId, String payload) {
        return message(SimpMessageType.MESSAGE, sessionId, payload);
    }

    private static Message<byte[]> disconnect(String sessionId) {
        String[] parts = sessionId.split("-");
        String payload = parts.length == 2 && Arrays.stream(parts).allMatch(part -> !part.isEmpty())
                && parts[1].chars().allMatch(Character::isDigit) ? parts[1] + ":2000" : "disconnect";
        return message(SimpMessageType.DISCONNECT, sessionId, payload);
    }

    private static Message<byte[]> message(SimpMessageType type, String sessionId, String payload) {
        SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.create(type);
        accessor.setSessionId(sessionId);
        return MessageBuilder.createMessage(payload.getBytes(), accessor.getMessageHeaders());
    }

    private static String payloadOf(Message<?> message) {
        return new String((byte[]) message.getPayload());
    }

    private static void sleepMillis(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
