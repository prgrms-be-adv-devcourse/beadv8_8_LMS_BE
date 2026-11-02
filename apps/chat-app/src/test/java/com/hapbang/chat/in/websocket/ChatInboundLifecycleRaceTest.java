package com.hapbang.chat.in.websocket;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageHandler;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.support.ExecutorSubscribableChannel;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.support.MessageHandlingRunnable;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;

import ch.qos.logback.classic.Level;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/** Independent review: lifecycle races of the per-session inbound queue. */
class ChatInboundLifecycleRaceTest {

    private final ChatWebSocketSessionRegistry registry = mock(ChatWebSocketSessionRegistry.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final CountDownLatch release = new CountDownLatch(1);
    private final List<ChatInboundReceiveBudget> budgets = new ArrayList<>();

    @org.junit.jupiter.api.BeforeEach
    void fastAwait() {
        org.awaitility.Awaitility.setDefaultPollInterval(Duration.ofMillis(1));
        org.awaitility.Awaitility.setDefaultPollDelay(Duration.ZERO);
    }

    @AfterEach
    void tearDown() {
        org.awaitility.Awaitility.reset();
        release.countDown();
        budgets.forEach(ChatInboundReceiveBudget::destroy);
    }

    /**
     * BUG A (coalescing reorders cleanup before later frames). A client DISCONNECT is still pending when the client
     * also sends SUBSCRIBE, then the socket closes and the server DISCONNECT arrives. The server DISCONNECT is
     * coalesced into the pending client one, which sits BEFORE the SUBSCRIBE in FIFO. Result: the SUBSCRIBE is the last
     * thing every handler sees -> SimpleBroker/user-destination subscription for a dead session is never removed.
     */
    @Test
    void coalescedServerDisconnectMustStillRunAfterLaterFrames() throws Exception {
        ChatInboundReceiveBudget budget = budget(1, 100, 20);
        ExecutorSubscribableChannel inbound = new ExecutorSubscribableChannel(budget.channelExecutor());
        List<String> log = new CopyOnWriteArrayList<>();
        for (String name : List.of("annotation", "broker", "user")) {
            inbound.subscribe(m -> {
                if (!sessionOf(m).equals("s-1")) {
                    return;
                }
                log.add(name + ":" + typeOf(m) + ":" + payloadOf(m));
            });
        }
        blockWorker(budget);

        inbound.send(message(SimpMessageType.DISCONNECT, "s-1", "client"));
        inbound.send(message(SimpMessageType.SUBSCRIBE, "s-1", "sub"));
        // socket closes -> StompSubProtocolHandler.afterSessionEnded sends the server DISCONNECT
        inbound.send(message(SimpMessageType.DISCONNECT, "s-1", "server"));
        release.countDown();
        await().atMost(Duration.ofSeconds(5)).until(() -> !queues(budget).containsKey("s-1"));

        // every handler must see a DISCONNECT after the last SUBSCRIBE task
        int lastSubscribe = lastIndexOf(log, ":SUBSCRIBE:");
        assertThat(log.subList(lastSubscribe + 1, log.size()))
                .as("cleanup after the last SUBSCRIBE, full log=%s", log)
                .contains("broker:DISCONNECT:client", "user:DISCONNECT:client", "annotation:DISCONNECT:client");
    }

    /**
     * BUG B (late handler task after a non-overflow close). The receive thread is dispatching frame F to three handlers
     * when the session is closed from another thread (outbound overflow close, kick...). The server DISCONNECT runs and
     * the queue is removed between F.h1 and F.h2. No marker exists (not an overflow close), so F.h2/F.h3 create a fresh
     * queue, run after cleanup, and the fresh queue is never removed (no further DISCONNECT will ever come).
     */
    @Test
    void lateHandlerTaskAfterNonOverflowCloseRunsAfterCleanupAndLeaksQueue() {
        ChatInboundReceiveBudget budget = budget(2, 100, 20);
        Executor executor = budget.channelExecutor();
        List<String> handled = new CopyOnWriteArrayList<>();
        MessageHandler h1 = m -> handled.add("h1:" + payloadOf(m));
        MessageHandler h2 = m -> handled.add("h2:" + payloadOf(m));
        Message<byte[]> subscribe = message(SimpMessageType.SUBSCRIBE, "s-1", "sub");
        Message<byte[]> serverDisconnect = message(SimpMessageType.DISCONNECT, "s-1", "server");

        executor.execute(task(subscribe, h1));
        await().atMost(Duration.ofSeconds(5)).until(() -> handled.contains("h1:sub"));
        // closer thread: server DISCONNECT for both handlers
        executor.execute(task(serverDisconnect, h1));
        executor.execute(task(serverDisconnect, h2));
        await().atMost(Duration.ofSeconds(5)).until(() -> !queues(budget).containsKey("s-1"));
        // receive thread continues the same dispatch loop
        executor.execute(task(subscribe, h2));
        await().pollDelay(Duration.ofMillis(200)).atMost(Duration.ofSeconds(1))
                .until(() -> budget.executor().getActiveCount() == 0);

        org.assertj.core.api.SoftAssertions.assertSoftly(soft -> {
            soft.assertThat(handled).as("SUBSCRIBE ran after cleanup").doesNotContain("h2:sub");
            soft.assertThat(queues(budget)).as("queue leaked").doesNotContainKey("s-1");
        });
    }

    /**
     * Claim check (5): the second offer cannot return REMOVED. Remove the looked-up queue between lookup and the first
     * offer, every time, for many frames; the retry must always succeed (an IllegalStateException would surface as a
     * lost frame since the channel executor throws).
     */
    @Test
    void retryNeverSeesRemoved() throws Exception {
        AtomicReferenceHolder holder = new AtomicReferenceHolder();
        ChatInboundReceiveBudget budget = new ChatInboundReceiveBudget(registry, meterRegistry, 4, 10_000, 10, 10_000) {
            @Override
            void beforeOffer(String sessionId) {
                Runnable r = holder.onBeforeOffer;
                if (r != null) {
                    r.run();
                }
            }
        };
        budgets.add(budget);
        Executor executor = budget.channelExecutor();
        AtomicInteger ran = new AtomicInteger();
        AtomicInteger thrown = new AtomicInteger();
        MessageHandler h = m -> ran.incrementAndGet();
        int rounds = 300;
        for (int i = 0; i < rounds; i++) {
            String sid = "r-" + i;
            executor.execute(task(message(SimpMessageType.MESSAGE, sid, "a"), h));
            await().atMost(Duration.ofSeconds(5)).until(() -> ran.get() > 0);
            ran.set(0);
            // the disconnect drains and removes the queue while the next submitter sits between lookup and offer
            Message<byte[]> d = message(SimpMessageType.DISCONNECT, sid, "d");
            AtomicBoolean once = new AtomicBoolean();
            holder.onBeforeOffer = () -> {
                if (once.compareAndSet(false, true)) {
                    Thread t = new Thread(() -> executor.execute(task(d, h)));
                    t.start();
                    await().atMost(Duration.ofSeconds(5)).until(() -> ran.get() == 1);
                    await().atMost(Duration.ofSeconds(5)).until(() -> isRemoved(budget, sid));
                }
            };
            try {
                executor.execute(task(message(SimpMessageType.DISCONNECT, sid, "d2"), h));
            } catch (IllegalStateException e) {
                thrown.incrementAndGet();
            }
            holder.onBeforeOffer = null;
            await().atMost(Duration.ofSeconds(5)).until(() -> ran.get() == 2);
            await().atMost(Duration.ofSeconds(5)).until(() -> !queues(budget).containsKey(sid));
            ran.set(0);
        }
        assertThat(thrown.get()).isZero();
    }

    private static final class AtomicReferenceHolder {
        volatile Runnable onBeforeOffer;
    }

    private static boolean isRemoved(ChatInboundReceiveBudget budget, String sid) {
        Object q = queues(budget).get(sid);
        return q == null || (boolean) ReflectionTestUtils.getField(q, "removed");
    }

    // ---------------------------------------------------------------------------------------------------------------
    // randomized stress

    private static final String[] HANDLERS = {"annotation", "broker", "user"};

    private static final class Session {
        final String id;
        final AtomicBoolean socketClosed = new AtomicBoolean();
        final AtomicBoolean overflowClosed = new AtomicBoolean();
        volatile boolean kicked;
        volatile boolean clientDisconnectSent;
        final AtomicInteger inFlight = new AtomicInteger();
        final List<String> log = Collections.synchronizedList(new ArrayList<>());
        int framesSent;

        Session(String id) {
            this.id = id;
        }
    }

    private static final class Stats {
        final AtomicLong overlap = new AtomicLong();
        long iterations, sessions, frames, outOfOrder, duplicates, undelivered, missingCleanup;
        long afterCleanupPlain, afterCleanupOverflow, afterCleanupKicked, afterCleanupClientD;
        long leakPlain, leakOverflow, leakKicked, leakClientD;
        long overflowSessions, kickedSessions, clientDSessions, maxMarkers;
        double rejected, coalesced, closedDropped;
    }

    @Test
    void randomizedLifecycleStress() throws Exception {
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(ChatInboundReceiveBudget.class)).setLevel(Level.OFF);
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(ChatChannelBudget.class)).setLevel(Level.OFF);
        int iterations = Integer.parseInt(System.getenv().getOrDefault("STRESS_ITERATIONS", "150"));
        Stats stats = new Stats();
        for (int it = 0; it < iterations; it++) {
            runIteration(it, stats);
        }
        System.out.printf(
                "STRESS iterations=%d sessions=%d frames=%d overflowSessions=%d kicked=%d clientD=%d%n"
                        + "  overlap=%d outOfOrder=%d duplicates=%d undeliveredOnUntouched=%d missingCleanup=%d%n"
                        + "  ranAfterFinalCleanup plain=%d overflow=%d kicked=%d clientD=%d%n"
                        + "  leakedQueues plain=%d overflow=%d kicked=%d clientD=%d maxMarkers=%d%n"
                        + "  metrics rejected=%.0f coalesced=%.0f closedSessionDropped=%.0f%n",
                stats.iterations, stats.sessions, stats.frames, stats.overflowSessions, stats.kickedSessions,
                stats.clientDSessions, stats.overlap.get(), stats.outOfOrder, stats.duplicates, stats.undelivered,
                stats.missingCleanup, stats.afterCleanupPlain, stats.afterCleanupOverflow, stats.afterCleanupKicked,
                stats.afterCleanupClientD, stats.leakPlain, stats.leakOverflow, stats.leakKicked, stats.leakClientD,
                stats.maxMarkers, stats.rejected, stats.coalesced, stats.closedDropped);
        // invariants the design claims
        assertThat(stats.overlap.get()).as("concurrent execution within a session").isZero();
        assertThat(stats.outOfOrder).as("per-handler FIFO").isZero();
        assertThat(stats.duplicates).as("task ran twice").isZero();
        assertThat(stats.undelivered).as("frames lost on sessions never closed").isZero();
        assertThat(stats.missingCleanup).as("handler never saw a DISCONNECT").isZero();
        assertThat(stats.afterCleanupPlain + stats.afterCleanupOverflow).as("ran after final cleanup").isZero();
        assertThat(stats.leakPlain + stats.leakOverflow).as("leaked queues").isZero();
        // DISCONNECT를 받은 뒤의 프레임은 버린다(STOMP: DISCONNECT 뒤 프레임 없음). 서버가 끊은 연결·클라이언트 DISCONNECT도 같다
        assertThat(stats.afterCleanupKicked + stats.afterCleanupClientD).as("ran after cleanup (kicked/clientD)").isZero();
        assertThat(stats.leakKicked + stats.leakClientD).as("leaked queues (kicked/clientD)").isZero();
    }

    private void runIteration(int it, Stats stats) throws Exception {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        int[] capacities = {1, 2, 8, 64, 512, 100_000};
        int[] perSession = {2, 5, 20, 200, 100_000};
        int queueCapacity = capacities[rnd.nextInt(capacities.length)];
        int perSessionFrames = perSession[rnd.nextInt(perSession.length)];
        int sessionCount = 24;
        int frames = 120;
        double kickProbability = 0.25;
        double clientDisconnectProbability = 0.15;

        ChatWebSocketSessionRegistry reg = mock(ChatWebSocketSessionRegistry.class);
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        ChatInboundReceiveBudget budget = new ChatInboundReceiveBudget(reg, meters, 2, queueCapacity, 100_000,
                perSessionFrames) {
            @Override
            void beforeOffer(String sessionId) {
                maybeYield();
            }

            @Override
            void beforeSessionQueue(String sessionId) {
                maybeYield();
            }
        };
        budgets.add(budget);
        ExecutorSubscribableChannel inbound = new ExecutorSubscribableChannel(budget.channelExecutor());
        Map<String, Session> sessions = new HashMap<>();
        for (int s = 0; s < sessionCount; s++) {
            Session session = new Session("it" + it + "-s" + s);
            sessions.put(session.id, session);
        }
        for (String name : HANDLERS) {
            inbound.subscribe(m -> {
                Session session = sessions.get(sessionOf(m));
                if (session.inFlight.incrementAndGet() != 1) {
                    stats.overlap.incrementAndGet();
                }
                session.log.add(name + ":" + typeOf(m) + ":" + payloadOf(m));
                if (ThreadLocalRandom.current().nextInt(16) == 0) {
                    Thread.yield();
                }
                session.inFlight.decrementAndGet();
            });
        }
        when(reg.closeSession(anyString(), any())).thenAnswer(inv -> {
            Session session = sessions.get((String) inv.getArgument(0));
            session.overflowClosed.set(true);
            if (session.socketClosed.compareAndSet(false, true)) {
                // Tomcat fires onClose on the closing thread -> afterSessionEnded -> server DISCONNECT
                inbound.send(message(SimpMessageType.DISCONNECT, session.id, "server"));
                return true;
            }
            return false;
        });

        List<Thread> threads = new ArrayList<>();
        CountDownLatch start = new CountDownLatch(1);
        for (Session session : sessions.values()) {
            boolean kick = rnd.nextDouble() < kickProbability;
            int kickAt = rnd.nextInt(frames);
            AtomicInteger progress = new AtomicInteger();
            Thread producer = new Thread(() -> {
                awaitLatch(start);
                ThreadLocalRandom r = ThreadLocalRandom.current();
                for (int i = 0; i < frames && !session.socketClosed.get(); i++) {
                    if (r.nextDouble() < clientDisconnectProbability / frames * 4) {
                        session.clientDisconnectSent = true;
                        inbound.send(message(SimpMessageType.DISCONNECT, session.id, "client"));
                    }
                    SimpMessageType type = r.nextBoolean() ? SimpMessageType.SUBSCRIBE : SimpMessageType.MESSAGE;
                    inbound.send(message(type, session.id, Integer.toString(i)));
                    session.framesSent = i + 1;
                    progress.set(i + 1);
                }
                if (session.socketClosed.compareAndSet(false, true)) {
                    inbound.send(message(SimpMessageType.DISCONNECT, session.id, "server"));
                }
            });
            threads.add(producer);
            if (kick) {
                threads.add(new Thread(() -> {
                    awaitLatch(start);
                    while (progress.get() < kickAt && producer.isAlive()) {
                        Thread.onSpinWait();
                    }
                    if (session.socketClosed.compareAndSet(false, true)) {
                        session.kicked = true;
                        inbound.send(message(SimpMessageType.DISCONNECT, session.id, "server"));
                    }
                }));
            }
        }
        threads.forEach(Thread::start);
        start.countDown();
        for (Thread t : threads) {
            t.join(30_000);
            assertThat(t.isAlive()).isFalse();
        }
        awaitQuiescence(budget);

        Map<String, ?> queues = queues(budget);
        stats.iterations++;
        stats.maxMarkers = Math.max(stats.maxMarkers, budget.closedSessionMarkers());
        stats.rejected += meters.counter(ChatInboundReceiveBudget.REJECTED_METRIC).count();
        stats.coalesced += meters.counter(ChatInboundReceiveBudget.DISCONNECT_COALESCED_METRIC).count();
        stats.closedDropped += meters.counter(ChatInboundReceiveBudget.CLOSED_SESSION_DROPPED_METRIC).count();
        for (Session session : sessions.values()) {
            stats.sessions++;
            stats.frames += session.framesSent;
            boolean overflow = session.overflowClosed.get();
            if (overflow) {
                stats.overflowSessions++;
            }
            if (session.kicked) {
                stats.kickedSessions++;
            }
            if (session.clientDisconnectSent) {
                stats.clientDSessions++;
            }
            List<String> log = new ArrayList<>(session.log);
            Map<String, Integer> last = new HashMap<>();
            Set<String> seen = new HashSet<>();
            Map<String, Integer> perHandlerCount = new HashMap<>();
            int lastNonDisconnect = -1;
            for (int i = 0; i < log.size(); i++) {
                String[] parts = log.get(i).split(":");
                if (parts[1].equals("DISCONNECT")) {
                    continue;
                }
                lastNonDisconnect = i;
                int index = Integer.parseInt(parts[2]);
                if (!seen.add(parts[0] + ":" + index)) {
                    stats.duplicates++;
                }
                Integer previous = last.put(parts[0], index);
                if (previous != null && previous >= index) {
                    stats.outOfOrder++;
                }
                perHandlerCount.merge(parts[0], 1, Integer::sum);
            }
            // every handler must run a DISCONNECT after the last non-DISCONNECT task of the session
            Set<String> cleanedAfter = new HashSet<>();
            for (int i = lastNonDisconnect + 1; i < log.size(); i++) {
                String[] parts = log.get(i).split(":");
                if (parts[1].equals("DISCONNECT")) {
                    cleanedAfter.add(parts[0]);
                }
            }
            for (String h : HANDLERS) {
                if (log.stream().noneMatch(e -> e.startsWith(h + ":DISCONNECT"))) {
                    stats.missingCleanup++;
                }
            }
            boolean afterCleanup = cleanedAfter.size() < HANDLERS.length;
            boolean leaked = queues.containsKey(session.id);
            if (afterCleanup || leaked) {
                if (session.kicked) {
                    stats.afterCleanupKicked += afterCleanup ? 1 : 0;
                    stats.leakKicked += leaked ? 1 : 0;
                } else if (session.clientDisconnectSent) {
                    stats.afterCleanupClientD += afterCleanup ? 1 : 0;
                    stats.leakClientD += leaked ? 1 : 0;
                } else if (overflow) {
                    stats.afterCleanupOverflow += afterCleanup ? 1 : 0;
                    stats.leakOverflow += leaked ? 1 : 0;
                    System.out.println("OVERFLOW violation " + session.id + " leaked=" + leaked + " log tail="
                            + log.subList(Math.max(0, log.size() - 12), log.size()));
                } else {
                    stats.afterCleanupPlain += afterCleanup ? 1 : 0;
                    stats.leakPlain += leaked ? 1 : 0;
                    System.out.println("PLAIN violation " + session.id + " leaked=" + leaked + " log tail="
                            + log.subList(Math.max(0, log.size() - 12), log.size()));
                }
            }
            if (!overflow && !session.kicked && !session.clientDisconnectSent) {
                for (String h : HANDLERS) {
                    if (perHandlerCount.getOrDefault(h, 0) != session.framesSent) {
                        stats.undelivered++;
                    }
                }
            }
        }
        budget.destroy();
    }

    private static void maybeYield() {
        int r = ThreadLocalRandom.current().nextInt(8);
        if (r == 0) {
            Thread.yield();
        } else if (r == 1) {
            long until = System.nanoTime() + 2_000;
            while (System.nanoTime() < until) {
                Thread.onSpinWait();
            }
        }
    }

    private static void awaitQuiescence(ChatInboundReceiveBudget budget) {
        ThreadPoolTaskExecutor closer = (ThreadPoolTaskExecutor) ReflectionTestUtils.getField(budget, "closer");
        AtomicInteger stable = new AtomicInteger();
        await().pollInterval(Duration.ofMillis(10)).atMost(Duration.ofSeconds(20)).until(() -> {
            boolean idle = budget.executor().getActiveCount() == 0 && budget.executor().getQueueSize() == 0
                    && closer.getActiveCount() == 0 && closer.getQueueSize() == 0
                    && budget.sessionsWithQueuedWork() == 0;
            return idle ? stable.incrementAndGet() >= 5 : stable.getAndSet(0) < 0;
        });
    }

    // ---------------------------------------------------------------------------------------------------------------

    private ChatInboundReceiveBudget budget(int workers, int queueCapacity, int perSessionFrames) {
        ChatInboundReceiveBudget budget = new ChatInboundReceiveBudget(registry, meterRegistry, workers, queueCapacity,
                10, perSessionFrames);
        budgets.add(budget);
        return budget;
    }

    private void blockWorker(ChatInboundReceiveBudget budget) throws InterruptedException {
        CountDownLatch blocking = new CountDownLatch(1);
        budget.channelExecutor().execute(task(message(SimpMessageType.MESSAGE, "s-block", "block"), m -> {
            blocking.countDown();
            awaitLatch(release);
        }));
        assertThat(blocking.await(5, TimeUnit.SECONDS)).isTrue();
    }

    private static int lastIndexOf(List<String> log, String part) {
        for (int i = log.size() - 1; i >= 0; i--) {
            if (log.get(i).contains(part)) {
                return i;
            }
        }
        return -1;
    }

    private static MessageHandlingRunnable task(Message<?> message, MessageHandler handler) {
        return new MessageHandlingRunnable() {
            @Override
            public Message<?> getMessage() {
                return message;
            }

            @Override
            public MessageHandler getMessageHandler() {
                return handler;
            }

            @Override
            public void run() {
                handler.handleMessage(message);
            }
        };
    }

    @SuppressWarnings("unchecked")
    private static Map<String, ?> queues(ChatInboundReceiveBudget budget) {
        return (Map<String, ?>) ReflectionTestUtils.getField(budget, "queues");
    }

    private static Message<byte[]> message(SimpMessageType type, String sessionId, String payload) {
        SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.create(type);
        accessor.setSessionId(sessionId);
        return MessageBuilder.createMessage(payload.getBytes(), accessor.getMessageHeaders());
    }

    private static String sessionOf(Message<?> message) {
        return SimpMessageHeaderAccessor.getSessionId(message.getHeaders());
    }

    private static String typeOf(Message<?> message) {
        return String.valueOf(SimpMessageHeaderAccessor.getMessageType(message.getHeaders()));
    }

    private static String payloadOf(Message<?> message) {
        return new String((byte[]) message.getPayload());
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
