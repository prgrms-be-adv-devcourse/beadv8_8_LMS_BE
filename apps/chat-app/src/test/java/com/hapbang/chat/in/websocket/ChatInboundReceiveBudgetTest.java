package com.hapbang.chat.in.websocket;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageHandler;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.support.ExecutorSubscribableChannel;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.support.MessageHandlingRunnable;
import org.springframework.test.util.ReflectionTestUtils;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * 수신 예산을 clientInboundChannel과 같은 Spring 채널로 검증한다: 연결별 순서, 연결별 몫(넘긴 연결만 닫음), 공용 실행기 상한,
 * 세션 종료 정리(DISCONNECT)는 버리지 않음.
 */
class ChatInboundReceiveBudgetTest {

    private final ChatWebSocketSessionRegistry registry = mock(ChatWebSocketSessionRegistry.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final CountDownLatch release = new CountDownLatch(1);
    private final List<ChatInboundReceiveBudget> budgets = new ArrayList<>();

    @AfterEach
    void tearDown() {
        release.countDown();
        budgets.forEach(ChatInboundReceiveBudget::destroy);
    }

    @Test
    void 한_연결의_프레임은_여러_스레드에서도_모든_처리기에서_보낸_순서대로_처리된다() {
        ExecutorSubscribableChannel inbound = channel(budget(4, 1000, 1000));
        // 수신 채널처럼 처리기 셋을 구독한다(프레임 하나 = 작업 셋)
        List<List<String>> handled = List.of(new CopyOnWriteArrayList<>(), new CopyOnWriteArrayList<>(),
                new CopyOnWriteArrayList<>());
        for (List<String> byHandler : handled) {
            inbound.subscribe(message -> byHandler.add(payloadOf(message)));
        }
        List<String> sent = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            sent.add("f-" + i);
            inbound.send(frame("s-1", "f-" + i));
        }

        await().atMost(Duration.ofSeconds(5)).until(() -> handled.stream().allMatch(list -> list.size() == 200));
        assertThat(handled).allSatisfy(byHandler -> assertThat(byHandler).containsExactlyElementsOf(sent));
    }

    @Test
    void 몫을_넘긴_연결만_닫고_다른_연결의_프레임은_차례를_기다려_처리된다() throws Exception {
        ChatInboundReceiveBudget budget = budget(1, 5, 2);
        ExecutorSubscribableChannel inbound = channel(budget);
        List<String> handled = new CopyOnWriteArrayList<>();
        CountDownLatch blocking = new CountDownLatch(1);
        inbound.subscribe(message -> {
            if (payloadOf(message).equals("flood-1")) {
                blocking.countDown();
                awaitLatch(release);
            }
            handled.add(payloadOf(message));
        });
        inbound.subscribe(message -> {
        });
        inbound.subscribe(message -> {
        });
        when(registry.closeSession(anyString(), any())).thenReturn(true);

        inbound.send(frame("s-flood", "flood-1"));
        assertThat(blocking.await(5, TimeUnit.SECONDS)).isTrue();
        inbound.send(frame("s-flood", "flood-2"));
        // flood-1(남은 처리기 몫)·flood-2가 몫 2프레임을 채워 flood-3에서 넘친다
        inbound.send(frame("s-flood", "flood-3"));
        inbound.send(frame("s-by", "by-1"));

        verify(registry, timeout(2_000)).closeSession("s-flood", ChatWebSocketSessionRegistry.INBOUND_OVERFLOW);
        verify(registry, never()).closeSession(eq("s-by"), any());
        assertThat(meterRegistry.counter(ChatInboundReceiveBudget.REJECTED_METRIC).count()).isEqualTo(1);

        release.countDown();
        await().atMost(Duration.ofSeconds(5)).until(() -> handled.size() == 3);
        assertThat(handled).containsExactlyInAnyOrder("flood-1", "flood-2", "by-1");
        assertThat(handled.indexOf("flood-1")).isLessThan(handled.indexOf("flood-2"));
        // 몫을 넘겨 닫는 연결의 다음 프레임은 받지 않는다
        inbound.send(frame("s-flood", "flood-4"));
        await().during(Duration.ofMillis(200)).atMost(Duration.ofSeconds(1)).until(() -> handled.size() == 3);
    }

    @Test
    void 공용_실행기가_넘쳐도_세션_종료_정리는_버리지_않고_다른_프레임을_버린_연결은_닫는다() throws Exception {
        ChatInboundReceiveBudget budget = budget(1, 1, 20);
        ExecutorSubscribableChannel inbound = channel(budget);
        List<String> handled = new CopyOnWriteArrayList<>();
        CountDownLatch blocking = new CountDownLatch(1);
        inbound.subscribe(message -> {
            if (payloadOf(message).equals("block")) {
                blocking.countDown();
                awaitLatch(release);
            }
            handled.add(payloadOf(message));
        });
        when(registry.closeSession(anyString(), any())).thenReturn(true);

        inbound.send(frame("s-1", "block"));
        assertThat(blocking.await(5, TimeUnit.SECONDS)).isTrue();
        inbound.send(frame("s-2", "queued"));
        // 공용 대기열(1칸)이 찼다: 다른 연결의 일반 프레임은 버리고 그 연결을 닫는다
        inbound.send(frame("s-3", "dropped"));
        verify(registry, timeout(2_000)).closeSession("s-3", ChatWebSocketSessionRegistry.INBOUND_OVERFLOW);
        // 세션 종료 정리는 넘쳐도 호출한 스레드에서 바로 처리한다(내장 브로커가 구독을 지울 수 있게)
        inbound.send(disconnect("s-4", "disconnect-s-4"));
        assertThat(handled).contains("disconnect-s-4");

        release.countDown();
        await().atMost(Duration.ofSeconds(5)).until(() -> handled.containsAll(List.of("block", "queued")));
        assertThat(handled).doesNotContain("dropped");
    }

    /**
     * 거절된 프레임의 남은 처리기 작업이 표식을 확인한 뒤 줄을 만들기 전에 선점되고, 그 사이 세션 종료 정리가 줄을 지우고 표식을 남겨도
     * 그 작업은 새 줄을 만들지 않고 버려진다(표식 확인·줄 만들기와 줄 지우기·표식 남기기가 엇갈리지 않음).
     */
    @Test
    void 표식_확인_뒤_선점된_늦은_작업은_그_사이_정리가_끝나도_새_줄을_만들지_않는다() throws Exception {
        CountDownLatch parked = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        Thread[] lateThread = new Thread[1];
        ChatInboundReceiveBudget budget = new ChatInboundReceiveBudget(registry, meterRegistry, 1, 1, 10, 20) {
            @Override
            void beforeSessionQueue(String sessionId) {
                if (Thread.currentThread() == lateThread[0]) {
                    parked.countDown();
                    awaitLatch(resume);
                }
            }
        };
        budgets.add(budget);
        Executor executor = budget.channelExecutor();
        List<String> handled = new CopyOnWriteArrayList<>();
        MessageHandler first = message -> handled.add("first:" + payloadOf(message));
        MessageHandler second = message -> handled.add("second:" + payloadOf(message));
        CountDownLatch blocking = new CountDownLatch(1);
        MessageHandler slow = message -> {
            blocking.countDown();
            awaitLatch(release);
        };
        when(registry.closeSession(anyString(), any())).thenReturn(true);
        // 처리 스레드가 붙잡히고 공용 대기열(1칸)도 찬다
        executor.execute(task(frame("s-1", "block"), slow));
        assertThat(blocking.await(5, TimeUnit.SECONDS)).isTrue();
        executor.execute(task(frame("s-2", "queued"), first));
        Message<byte[]> rejectedFrame = frame("s-3", "f");
        // 첫 처리기 작업: 줄 비우기를 거절당해 프레임을 버리고 닫는 중 표식(줄은 정리 전까지 남는다)
        executor.execute(task(rejectedFrame, first));
        assertThat(queues(budget)).containsKey("s-3");

        // 남은 처리기 작업이 들어와 줄을 찾기 직전(표식은 아직 없다)에 선점된다
        Thread late = new Thread(() -> executor.execute(task(rejectedFrame, second)));
        lateThread[0] = late;
        late.start();
        assertThat(parked.await(5, TimeUnit.SECONDS)).isTrue();
        // 그 사이 세션 종료 정리가 거절 경로에서 바로 실행돼 줄을 지우고 표식을 남긴다
        executor.execute(task(disconnect("s-3", "disconnect"), first));
        assertThat(handled).contains("first:disconnect");
        assertThat(queues(budget)).doesNotContainKey("s-3");
        // 처리 스레드와 공용 대기열에 여유가 생긴 뒤 늦은 작업이 이어서 실행된다
        release.countDown();
        await().atMost(Duration.ofSeconds(5)).until(() -> handled.contains("first:queued"));
        resume.countDown();
        late.join(5_000);

        assertThat(late.isAlive()).isFalse();
        assertThat(queues(budget)).doesNotContainKey("s-3");
        assertThat(meterRegistry.counter(ChatInboundReceiveBudget.CLOSED_SESSION_DROPPED_METRIC).count()).isEqualTo(1);
        await().during(Duration.ofMillis(200)).atMost(Duration.ofSeconds(1))
                .until(() -> !handled.contains("first:f") && !handled.contains("second:f"));
    }

    /**
     * 넘침으로 닫혀 표식만 남은 연결에 다른 처리기의 세션 종료 정리가 들어와 줄이 새로 생겨도, 그 줄은 닫힘을 이어받는다. 늦게 온
     * 거절된 SUBSCRIBE는 그 줄에서 처리되지 않는다(세션 종료 정리는 처리된다).
     */
    @Test
    void 표식만_남은_연결에_세션_종료_정리로_줄이_생겨도_늦은_SUBSCRIBE는_처리되지_않는다() throws Exception {
        ChatInboundReceiveBudget budget = budget(1, 1, 20);
        Executor executor = budget.channelExecutor();
        List<String> handled = new CopyOnWriteArrayList<>();
        MessageHandler first = message -> handled.add("first:" + payloadOf(message));
        CountDownLatch blocking = new CountDownLatch(1);
        MessageHandler slow = message -> {
            blocking.countDown();
            awaitLatch(release);
        };
        when(registry.closeSession(anyString(), any())).thenReturn(true);
        // 처리 스레드가 붙잡히고 공용 대기열(1칸)도 찬 상태에서 s-3이 넘쳐 닫히고, 세션 종료 정리가 바로 실행돼 표식만 남는다
        executor.execute(task(frame("s-1", "block"), slow));
        assertThat(blocking.await(5, TimeUnit.SECONDS)).isTrue();
        executor.execute(task(frame("s-2", "queued"), first));
        executor.execute(task(frame("s-3", "f"), first));
        executor.execute(task(disconnect("s-3", "disconnect"), first));
        assertThat(queues(budget)).doesNotContainKey("s-3");
        assertThat(budget.closedSessionMarkers()).isEqualTo(1);
        release.countDown();
        await().atMost(Duration.ofSeconds(5)).until(() -> handled.contains("first:queued"));

        // 다른 처리기의 세션 종료 정리(연결 종료 때 서버가 만든 DISCONNECT)가 새 줄을 만들고 처리 중에 붙잡힌다
        CountDownLatch cleanupStarted = new CountDownLatch(1);
        CountDownLatch cleanupRelease = new CountDownLatch(1);
        MessageHandler cleanup = message -> {
            handled.add("cleanup:" + payloadOf(message));
            cleanupStarted.countDown();
            awaitLatch(cleanupRelease);
        };
        executor.execute(task(disconnect("s-3", "server-disconnect"), cleanup));
        assertThat(cleanupStarted.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(queues(budget)).containsKey("s-3");
        // 거절된 SUBSCRIBE의 늦은 작업이 살아 있는 그 줄을 찾는다
        executor.execute(task(message(SimpMessageType.SUBSCRIBE, "s-3", "subscribe"), first));
        cleanupRelease.countDown();

        await().atMost(Duration.ofSeconds(5)).until(() -> !queues(budget).containsKey("s-3"));
        await().during(Duration.ofMillis(200)).atMost(Duration.ofSeconds(1))
                .until(() -> !handled.contains("first:subscribe"));
        assertThat(handled).contains("cleanup:server-disconnect");
        assertThat(meterRegistry.counter(ChatInboundReceiveBudget.CLOSED_SESSION_DROPPED_METRIC).count()).isEqualTo(1);
        // 닫힘을 이어받은 줄을 지울 때도 표식은 남는다
        assertThat(budget.closedSessionMarkers()).isEqualTo(1);
    }

    /**
     * 줄을 찾은 뒤 넣기 전에 선점된 사이 그 줄이 지워져도, 작업은 지워진 줄에 들어가지 않고 새 줄에 들어간다. 같은 읽기 스레드의 다음
     * 작업도 같은 새 줄에 들어가 줄 비우기가 둘 생기지 않고, 보낸 순서대로 하나씩 처리된다. 세션 종료 정리를 받은 연결은 정리만 받으므로
     * 이어지는 작업은 서버·클라이언트의 DISCONNECT다.
     */
    @Test
    void 줄을_찾은_뒤_넣기_전에_줄이_지워져도_줄_비우기가_둘_생기지_않고_순서가_지켜진다() throws Exception {
        CountDownLatch parked = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        Thread[] readThread = new Thread[1];
        ChatInboundReceiveBudget budget = new ChatInboundReceiveBudget(registry, meterRegistry, 4, 100, 10, 20) {
            @Override
            void beforeOffer(String sessionId) {
                if (Thread.currentThread() == readThread[0] && parked.getCount() > 0) {
                    parked.countDown();
                    awaitLatch(resume);
                }
            }
        };
        budgets.add(budget);
        Executor executor = budget.channelExecutor();
        List<String> handled = new CopyOnWriteArrayList<>();
        java.util.concurrent.atomic.AtomicInteger inFlight = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger maxInFlight = new java.util.concurrent.atomic.AtomicInteger();
        CountDownLatch firstFrameRelease = new CountDownLatch(1);
        CountDownLatch disconnectRelease = new CountDownLatch(1);
        MessageHandler handler = message -> {
            maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            try {
                String payload = payloadOf(message);
                if (payload.equals("disconnect")) {
                    awaitLatch(disconnectRelease);
                } else if (payload.equals("d1")) {
                    awaitLatch(firstFrameRelease);
                }
                handled.add(payload);
            } finally {
                inFlight.decrementAndGet();
            }
        };
        // 세션 종료 정리가 처리 중인 줄이 있다
        executor.execute(task(disconnect("s-1", "disconnect"), handler));
        Object oldQueue = queues(budget).get("s-1");
        assertThat(oldQueue).isNotNull();

        // 읽기 스레드가 그 줄을 찾은 뒤 넣기 직전에 선점된다
        // d2는 다른 처리기의 정리다(같은 처리기면 d1이 시작되기 전에 들어올 때 합쳐진다)
        MessageHandler otherHandler = handler::handleMessage;
        Thread read = new Thread(() -> {
            executor.execute(task(disconnect("s-1", "d1"), handler));
            executor.execute(task(disconnect("s-1", "d2"), otherHandler));
        });
        readThread[0] = read;
        read.start();
        assertThat(parked.await(5, TimeUnit.SECONDS)).isTrue();
        // 그 사이 정리가 끝나 줄이 비고 지워진다
        disconnectRelease.countDown();
        await().atMost(Duration.ofSeconds(5)).until(() -> !queues(budget).containsKey("s-1"));
        resume.countDown();

        // d1이 붙잡힌 동안 d2는 처리되지 않는다(d2를 비우는 다른 줄 비우기가 없다)
        await().atMost(Duration.ofSeconds(5)).until(() -> queuedEntries(budget, "s-1") == 1);
        assertThat(queues(budget).get("s-1")).isNotSameAs(oldQueue);
        await().during(Duration.ofMillis(200)).atMost(Duration.ofSeconds(1)).until(() -> !handled.contains("d2"));
        firstFrameRelease.countDown();
        read.join(5_000);
        await().atMost(Duration.ofSeconds(5)).until(() -> handled.size() == 3);

        assertThat(handled).containsExactly("disconnect", "d1", "d2");
        assertThat(maxInFlight.get()).isEqualTo(1);
        await().atMost(Duration.ofSeconds(5)).until(() -> !queues(budget).containsKey("s-1"));
    }

    /**
     * 여러 연결이 각자 한 읽기 스레드에서 프레임과 세션 종료 정리를 섞어 빠르게 보내, 줄 지우기·새 줄 만들기가 넣기와 계속 엇갈려도
     * 한 연결의 작업이 동시에 둘 처리되지 않고 보낸 순서대로 처리된다. 첫 DISCONNECT 앞의 프레임은 모두 처리되고, 그 뒤의 프레임은
     * 버리며, DISCONNECT는 처리되거나 합쳐진다.
     */
    @Test
    void 프레임과_줄_지우기가_계속_엇갈려도_한_연결은_동시에_하나씩_보낸_순서대로_처리된다() throws Exception {
        int sessions = 8;
        int framesPerSession = 3_000;
        ChatInboundReceiveBudget budget = new ChatInboundReceiveBudget(registry, meterRegistry, 4, 10_000, 10, 10_000) {
            @Override
            void beforeOffer(String sessionId) {
                // 찾기와 넣기 사이를 넓혀 그 사이 줄이 지워지는 엇갈림을 자주 만든다
                Thread.yield();
            }
        };
        budgets.add(budget);
        Executor executor = budget.channelExecutor();
        Map<String, java.util.concurrent.atomic.AtomicInteger> inFlight = new ConcurrentHashMap<>();
        Map<String, Integer> lastSeen = new ConcurrentHashMap<>();
        List<String> violations = new CopyOnWriteArrayList<>();
        java.util.concurrent.atomic.AtomicInteger processed = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger processedFrames = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger processedDisconnects = new java.util.concurrent.atomic.AtomicInteger();
        MessageHandler handler = message -> {
            String sessionId = SimpMessageHeaderAccessor.getSessionId(message.getHeaders());
            java.util.concurrent.atomic.AtomicInteger running = inFlight.computeIfAbsent(sessionId,
                    id -> new java.util.concurrent.atomic.AtomicInteger());
            if (running.incrementAndGet() != 1) {
                violations.add("동시 처리 " + sessionId);
            }
            try {
                int seq = Integer.parseInt(payloadOf(message));
                Integer previous = lastSeen.put(sessionId, seq);
                if (previous != null && previous >= seq) {
                    violations.add("순서 " + sessionId + " " + previous + "->" + seq);
                }
                processed.incrementAndGet();
                (ChatChannelBudget.isDisconnect(message) ? processedDisconnects : processedFrames).incrementAndGet();
            } finally {
                running.decrementAndGet();
            }
        };
        List<Thread> readers = new ArrayList<>();
        for (int s = 0; s < sessions; s++) {
            String sessionId = "s-" + s;
            Thread reader = new Thread(() -> {
                for (int seq = 0; seq < framesPerSession; seq++) {
                    Message<byte[]> message = seq % 5 == 4 ? disconnect(sessionId, String.valueOf(seq))
                            : frame(sessionId, String.valueOf(seq));
                    executor.execute(task(message, handler));
                }
            });
            readers.add(reader);
            reader.start();
        }
        for (Thread reader : readers) {
            reader.join(30_000);
        }

        // 첫 DISCONNECT(4번) 앞의 프레임 넷은 처리되고 그 뒤 프레임은 버린다. DISCONNECT는 처리되거나 합쳐진다
        int disconnectsPerSession = framesPerSession / 5;
        int droppedPerSession = framesPerSession - disconnectsPerSession - 4;
        await().atMost(Duration.ofSeconds(30)).until(() -> processedDisconnects.get()
                + meterRegistry.counter(ChatInboundReceiveBudget.DISCONNECT_COALESCED_METRIC).count()
                == sessions * disconnectsPerSession);
        assertThat(processedFrames.get()).isEqualTo(sessions * 4);
        assertThat(meterRegistry.counter(ChatInboundReceiveBudget.CLOSED_SESSION_DROPPED_METRIC).count())
                .isEqualTo(sessions * droppedPerSession);
        assertThat(violations).isEmpty();
        await().atMost(Duration.ofSeconds(5)).until(() -> queues(budget).isEmpty());
        assertThat(meterRegistry.counter(ChatInboundReceiveBudget.REJECTED_METRIC).count()).isZero();
    }

    /**
     * 공용 실행기가 줄 비우기를 거절해 세션 종료 정리를 호출한 스레드에서 실행하는 동안 같은 연결의 프레임·DISCONNECT가 들어와도,
     * 그 연결을 맡은 실행은 하나뿐이다. 새 DISCONNECT는 정리가 끝난 뒤 처리되고, 정리를 받은 뒤의 프레임은 버린다.
     */
    @Test
    void 거절_뒤_세션_종료_정리를_바로_실행하는_동안_들어온_작업은_정리가_끝난_뒤_차례대로_처리된다() throws Exception {
        ChatInboundReceiveBudget budget = budget(1, 1, 20);
        Executor executor = budget.channelExecutor();
        List<String> handled = new CopyOnWriteArrayList<>();
        MessageHandler first = message -> handled.add("first:" + payloadOf(message));
        CountDownLatch blocking = new CountDownLatch(1);
        MessageHandler slow = message -> {
            blocking.countDown();
            awaitLatch(release);
        };
        java.util.concurrent.atomic.AtomicInteger inFlight = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger maxInFlight = new java.util.concurrent.atomic.AtomicInteger();
        List<String> s3Handled = new CopyOnWriteArrayList<>();
        CountDownLatch cleanupStarted = new CountDownLatch(1);
        CountDownLatch cleanupRelease = new CountDownLatch(1);
        MessageHandler tracked = message -> {
            maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            try {
                String payload = payloadOf(message);
                if (payload.equals("disconnect")) {
                    cleanupStarted.countDown();
                    awaitLatch(cleanupRelease);
                }
                s3Handled.add(payload);
            } finally {
                inFlight.decrementAndGet();
            }
        };
        // 처리 스레드가 붙잡히고 공용 대기열(1칸)도 찬다
        executor.execute(task(frame("s-1", "block"), slow));
        assertThat(blocking.await(5, TimeUnit.SECONDS)).isTrue();
        executor.execute(task(frame("s-2", "queued"), first));
        // s-3의 세션 종료 정리: 줄 비우기가 거절돼 다른 스레드(호출한 스레드)에서 바로 실행되고 붙잡힌다
        Thread caller = new Thread(() -> executor.execute(task(disconnect("s-3", "disconnect"), tracked)));
        caller.start();
        assertThat(cleanupStarted.await(5, TimeUnit.SECONDS)).isTrue();
        // 공용 실행기에 여유가 생긴다
        release.countDown();
        await().atMost(Duration.ofSeconds(5)).until(() -> handled.contains("first:queued"));

        // 정리 실행 중에 같은 연결의 프레임과 다른 DISCONNECT가 들어온다
        executor.execute(task(frame("s-3", "f"), tracked));
        executor.execute(task(disconnect("s-3", "disconnect-2"), tracked));
        await().during(Duration.ofMillis(200)).atMost(Duration.ofSeconds(1)).until(() -> s3Handled.isEmpty());
        cleanupRelease.countDown();
        caller.join(5_000);

        await().atMost(Duration.ofSeconds(5)).until(() -> s3Handled.size() == 2);
        await().during(Duration.ofMillis(200)).atMost(Duration.ofSeconds(1)).until(() -> !s3Handled.contains("f"));
        assertThat(s3Handled).containsExactly("disconnect", "disconnect-2");
        assertThat(meterRegistry.counter(ChatInboundReceiveBudget.CLOSED_SESSION_DROPPED_METRIC).count()).isEqualTo(1);
        assertThat(maxInFlight.get()).isEqualTo(1);
        await().atMost(Duration.ofSeconds(5)).until(() -> !queues(budget).containsKey("s-3"));
    }

    private ChatInboundReceiveBudget budget(int workers, int queueCapacity, int perSessionFrames) {
        return budget(workers, queueCapacity, 10, perSessionFrames);
    }

    private ChatInboundReceiveBudget budget(int workers, int queueCapacity, int closeQueueCapacity,
            int perSessionFrames) {
        ChatInboundReceiveBudget budget = new ChatInboundReceiveBudget(registry, meterRegistry, workers, queueCapacity,
                closeQueueCapacity, perSessionFrames);
        budgets.add(budget);
        return budget;
    }

    /**
     * 닫기 대기열이 가득 차 몫을 넘긴 연결의 닫기를 맡기지 못하면, 그 연결의 다음 프레임을 버릴 때 다시 맡긴다. 열린 채 프레임만 버려지는
     * 연결이 남지 않는다. 버린 프레임은 프레임마다 한 번씩 센다.
     */
    @Test
    void 닫기_대기열이_가득_차_닫지_못한_연결은_다음에_버리는_프레임에서_다시_닫는다() throws Exception {
        // 닫기 스레드 1개 + 닫기 대기열 1칸. 첫 닫기가 스레드를 붙잡게 한다
        ChatInboundReceiveBudget budget = budget(1, 10, 1, 1);
        ExecutorSubscribableChannel inbound = channel(budget);
        CountDownLatch blocking = new CountDownLatch(1);
        inbound.subscribe(message -> {
            if (payloadOf(message).equals("block")) {
                blocking.countDown();
                awaitLatch(release);
            }
        });
        CountDownLatch closerBusy = new CountDownLatch(1);
        CountDownLatch closerRelease = new CountDownLatch(1);
        when(registry.closeSession(anyString(), any())).thenAnswer(invocation -> {
            if ("s-a".equals(invocation.getArgument(0))) {
                closerBusy.countDown();
                awaitLatch(closerRelease);
            }
            return true;
        });
        inbound.send(frame("s-x", "block"));
        assertThat(blocking.await(5, TimeUnit.SECONDS)).isTrue();

        // s-a의 닫기가 닫기 스레드를, s-b의 닫기가 닫기 대기열을 차지하고, s-c의 닫기는 맡기지 못한다
        inbound.send(frame("s-a", "a-1"));
        inbound.send(frame("s-a", "a-2"));
        assertThat(closerBusy.await(5, TimeUnit.SECONDS)).isTrue();
        inbound.send(frame("s-b", "b-1"));
        inbound.send(frame("s-b", "b-2"));
        inbound.send(frame("s-c", "c-1"));
        inbound.send(frame("s-c", "c-2"));
        closerRelease.countDown();
        verify(registry, timeout(2_000)).closeSession("s-b", ChatWebSocketSessionRegistry.INBOUND_OVERFLOW);
        verify(registry, after(200).never()).closeSession(eq("s-c"), any());

        // s-c의 다음 프레임은 버리면서 닫기를 다시 맡긴다
        inbound.send(frame("s-c", "c-3"));

        verify(registry, timeout(2_000)).closeSession("s-c", ChatWebSocketSessionRegistry.INBOUND_OVERFLOW);
        // 버린 프레임: a-2, b-2, c-2, c-3
        assertThat(meterRegistry.counter(ChatInboundReceiveBudget.REJECTED_METRIC).count()).isEqualTo(4);
    }

    /**
     * 연결의 SEND가 기다리는 동안 DISCONNECT를 수천 번 보내도 세션 종료 정리는 처리 대기 중인 하나와 합쳐 줄이 늘지 않고,
     * 정리는 한 번만 실행된다. 세션 종료 정리는 몫·닫힘과 관계없이 받으므로 합치지 않으면 메모리를 무한히 쓸 수 있다.
     */
    @Test
    void 처리_대기_중인_SEND_뒤에_DISCONNECT를_수천_번_보내도_줄은_늘지_않고_정리는_한_번만_실행된다() throws Exception {
        ChatInboundReceiveBudget budget = budget(1, 10, 1);
        ExecutorSubscribableChannel inbound = channel(budget);
        List<String> handled = new CopyOnWriteArrayList<>();
        CountDownLatch blocking = new CountDownLatch(1);
        // 수신 채널처럼 처리기 셋(프레임 하나 = 작업 셋)
        inbound.subscribe(message -> {
            if (payloadOf(message).equals("block")) {
                blocking.countDown();
                awaitLatch(release);
            }
            handled.add("h1:" + payloadOf(message));
        });
        inbound.subscribe(message -> handled.add("h2:" + payloadOf(message)));
        inbound.subscribe(message -> handled.add("h3:" + payloadOf(message)));
        inbound.send(frame("s-1", "block"));
        assertThat(blocking.await(5, TimeUnit.SECONDS)).isTrue();

        for (int i = 0; i < 5_000; i++) {
            inbound.send(disconnect("s-1", "disconnect"));
        }

        // 줄: block의 남은 처리기 몫 2 + 정리 하나의 처리기 몫 3
        assertThat(queuedEntries(budget, "s-1")).isLessThanOrEqualTo(5);
        assertThat(meterRegistry.counter(ChatInboundReceiveBudget.DISCONNECT_COALESCED_METRIC).count())
                .isEqualTo(4_999);
        release.countDown();
        await().atMost(Duration.ofSeconds(5)).until(() -> handled.size() == 6);
        await().during(Duration.ofMillis(200)).atMost(Duration.ofSeconds(1)).until(() -> handled.size() == 6);
        assertThat(handled).containsExactly("h1:block", "h2:block", "h3:block", "h1:disconnect", "h2:disconnect",
                "h3:disconnect");
        await().atMost(Duration.ofSeconds(1)).until(() -> !queues(budget).containsKey("s-1"));
    }

    /**
     * 공용 실행기가 줄 비우기를 거절해 프레임을 버리고 닫는 중인 연결은, 닫기가 끝나기 전에 처리 여유가 생겨도 다음 프레임을 처리하지
     * 않는다. 닫는 중 표식은 세션 종료 정리(DISCONNECT)를 실제로 처리할 때까지 남는다.
     */
    @Test
    void 줄_비우기를_거절당한_연결은_세션_종료_정리_전까지_여유가_생겨도_다음_프레임을_처리하지_않는다() throws Exception {
        ChatInboundReceiveBudget budget = budget(1, 1, 20);
        ExecutorSubscribableChannel inbound = channel(budget);
        List<String> handled = new CopyOnWriteArrayList<>();
        CountDownLatch blocking = new CountDownLatch(1);
        inbound.subscribe(message -> {
            if (payloadOf(message).equals("block")) {
                blocking.countDown();
                awaitLatch(release);
            }
            handled.add(payloadOf(message));
        });
        when(registry.closeSession(anyString(), any())).thenReturn(true);
        inbound.send(frame("s-1", "block"));
        assertThat(blocking.await(5, TimeUnit.SECONDS)).isTrue();
        inbound.send(frame("s-2", "queued"));
        inbound.send(frame("s-3", "f-1"));
        verify(registry, timeout(2_000)).closeSession("s-3", ChatWebSocketSessionRegistry.INBOUND_OVERFLOW);

        // 처리 여유가 생긴 뒤(닫기는 아직 끝나지 않음) 같은 연결의 다음 프레임이 온다
        release.countDown();
        await().atMost(Duration.ofSeconds(5)).until(() -> handled.containsAll(List.of("block", "queued")));
        inbound.send(frame("s-3", "f-2"));
        await().during(Duration.ofMillis(200)).atMost(Duration.ofSeconds(1))
                .until(() -> !handled.contains("f-1") && !handled.contains("f-2"));
        assertThat(queues(budget)).containsKey("s-3");

        // 세션 종료 정리는 처리하고, 그 뒤에야 표식을 지운다
        inbound.send(disconnect("s-3", "disconnect-s-3"));
        await().atMost(Duration.ofSeconds(5)).until(() -> handled.contains("disconnect-s-3"));
        await().atMost(Duration.ofSeconds(1)).until(() -> !queues(budget).containsKey("s-3"));
        assertThat(handled).doesNotContain("f-1", "f-2");
    }

    /**
     * Spring은 한 프레임을 처리기마다 따로 넣는다. 클라이언트가 보낸 DISCONNECT(D1)와 연결 종료 때 서버가 만든 DISCONNECT(D2)의
     * 처리기별 작업이 엇갈려 들어오고 그 사이 줄 비우기가 D1의 첫 작업을 처리해도, 처리기마다 정리가 적어도 한 번 실행되고
     * 내장 브로커(가짜)의 구독이 남지 않는다.
     */
    @Test
    void 두_DISCONNECT의_처리기별_작업이_엇갈려도_처리기마다_정리가_실행되고_구독이_남지_않는다() throws Exception {
        ChatInboundReceiveBudget budget = budget(1, 10, 20);
        Executor executor = budget.channelExecutor();
        Set<String> subscriptions = ConcurrentHashMap.newKeySet();
        subscriptions.add("s-1:sub-0");
        List<String> cleaned = new CopyOnWriteArrayList<>();
        MessageHandler first = message -> cleaned.add("first:" + payloadOf(message));
        MessageHandler broker = message -> {
            subscriptions.removeIf(subscription -> subscription.startsWith("s-1:"));
            cleaned.add("broker:" + payloadOf(message));
        };
        MessageHandler third = message -> cleaned.add("third:" + payloadOf(message));
        CountDownLatch blocking = new CountDownLatch(1);
        MessageHandler slow = message -> {
            blocking.countDown();
            awaitLatch(release);
        };
        Message<byte[]> d1 = disconnect("s-1", "d1");
        Message<byte[]> d2 = disconnect("s-1", "d2");

        // 다른 연결이 유일한 처리 스레드를 붙잡아 s-1의 줄 비우기가 대기열에서 기다린다
        executor.execute(task(frame("s-x", "block"), slow));
        assertThat(blocking.await(5, TimeUnit.SECONDS)).isTrue();
        executor.execute(task(d1, first));
        executor.execute(task(d2, first));
        executor.execute(task(d2, broker));
        // 줄 비우기가 D1의 첫 작업(과 그때까지 받은 작업)을 처리한다
        release.countDown();
        await().atMost(Duration.ofSeconds(5)).until(() -> cleaned.contains("first:d1")
                && budget.sessionsWithQueuedWork() == 0 && budget.executor().getActiveCount() == 0);
        // 나머지 처리기 작업이 엇갈려 들어온다
        executor.execute(task(d2, third));
        executor.execute(task(d1, broker));
        executor.execute(task(d1, third));

        await().atMost(Duration.ofSeconds(5)).until(() -> cleaned.stream().anyMatch(c -> c.startsWith("first:"))
                && cleaned.stream().anyMatch(c -> c.startsWith("broker:"))
                && cleaned.stream().anyMatch(c -> c.startsWith("third:")));
        assertThat(subscriptions).isEmpty();
        await().atMost(Duration.ofSeconds(2)).until(() -> budget.sessionsWithQueuedWork() == 0
                && !queues(budget).containsKey("s-1"));
    }

    /**
     * 공용 실행기가 프레임의 첫 처리기 작업을 거절해 닫는 중 표식이 생기고, 남은 처리기 작업이 들어오기 전에 세션 종료 정리까지 끝나
     * 표식이 지워져도, 그 프레임의 남은 처리기 작업은 새 줄에서 처리되지 않고 버려진다. 짧게 남는 종료 표식은 만료되면 지워진다.
     */
    @Test
    void 거절된_프레임의_남은_처리기_작업이_정리_뒤에_들어와도_새_줄에서_처리되지_않는다() throws Exception {
        ChatInboundReceiveBudget budget = budget(1, 1, 20);
        budget.setClosedSessionTtl(Duration.ofMillis(500));
        Executor executor = budget.channelExecutor();
        List<String> handled = new CopyOnWriteArrayList<>();
        MessageHandler first = message -> handled.add("first:" + payloadOf(message));
        MessageHandler second = message -> handled.add("second:" + payloadOf(message));
        CountDownLatch blocking = new CountDownLatch(1);
        MessageHandler slow = message -> {
            blocking.countDown();
            awaitLatch(release);
        };
        when(registry.closeSession(anyString(), any())).thenReturn(true);
        // 처리 스레드가 붙잡히고 공용 대기열(1칸)도 찬다
        executor.execute(task(frame("s-1", "block"), slow));
        assertThat(blocking.await(5, TimeUnit.SECONDS)).isTrue();
        executor.execute(task(frame("s-2", "queued"), first));

        Message<byte[]> rejectedFrame = frame("s-3", "f");
        // 첫 처리기 작업: 줄 비우기를 거절당해 프레임을 버리고 닫는 중 표식
        executor.execute(task(rejectedFrame, first));
        // 남은 처리기 작업이 들어오기 전에 세션 종료 정리가 거절 경로에서 바로 실행되고 표식이 지워진다
        executor.execute(task(disconnect("s-3", "disconnect"), first));
        assertThat(handled).contains("first:disconnect");
        assertThat(queues(budget)).doesNotContainKey("s-3");
        // 거절된 프레임의 남은 처리기 작업이 늦게 들어온다
        executor.execute(task(rejectedFrame, second));

        assertThat(meterRegistry.counter(ChatInboundReceiveBudget.CLOSED_SESSION_DROPPED_METRIC).count()).isEqualTo(1);
        assertThat(queues(budget)).doesNotContainKey("s-3");
        release.countDown();
        await().atMost(Duration.ofSeconds(5)).until(() -> handled.contains("first:queued"));
        await().during(Duration.ofMillis(200)).atMost(Duration.ofSeconds(1))
                .until(() -> !handled.contains("first:f") && !handled.contains("second:f"));
        // 표식은 오래 남지 않는다
        assertThat(budget.closedSessionMarkers()).isEqualTo(1);
        await().atMost(Duration.ofSeconds(2)).until(() -> budget.closedSessionMarkers() == 0);
    }

    /** Spring 채널이 처리기마다 만드는 작업과 같은 모양 */
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

    private static int queuedEntries(ChatInboundReceiveBudget budget, String sessionId) {
        Object queue = queues(budget).get(sessionId);
        return queue == null ? 0 : ((Collection<?>) ReflectionTestUtils.getField(queue, "entries")).size();
    }

    private static ExecutorSubscribableChannel channel(ChatInboundReceiveBudget budget) {
        return new ExecutorSubscribableChannel(budget.channelExecutor());
    }

    private static Message<byte[]> frame(String sessionId, String payload) {
        return message(SimpMessageType.MESSAGE, sessionId, payload);
    }

    private static Message<byte[]> disconnect(String sessionId, String payload) {
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

    private static void awaitLatch(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
