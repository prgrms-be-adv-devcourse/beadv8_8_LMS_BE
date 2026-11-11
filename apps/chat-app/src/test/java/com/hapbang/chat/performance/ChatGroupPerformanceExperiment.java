package com.hapbang.chat.performance;

import static com.hapbang.chat.fixture.ChatFixture.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.*;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.locks.LockSupport;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;

import com.hapbang.chat.app.ChatGroupRoomFacade;
import com.hapbang.chat.app.ChatGroupTestConfig;
import com.hapbang.chat.app.ChatUserUseCase;
import com.hapbang.chat.app.InMemoryChatRecruitSource;
import com.hapbang.chat.app.LocalChatConnections;
import com.hapbang.chat.domain.ChatGroupInviteResult;
import com.hapbang.chat.in.dto.SendChatMessageRequest;
import com.hapbang.chat.in.support.ChatE2ETest;
import com.hapbang.chat.in.support.RealtimeStompClient;
import com.hapbang.shared.member.domain.Role;

import tools.jackson.databind.json.JsonMapper;

/** 로컬 탐색용이며 운영 정원 확정 시험이 아니다. chatPerformance Gradle task로 실행한다. */
@ChatE2ETest
@Import(ChatGroupTestConfig.class)
@TestPropertySource(properties = {"spring.datasource.hikari.maximum-pool-size=20", "logging.level.root=WARN"})
class ChatGroupPerformanceExperiment {

    @LocalServerPort int port;
    @Autowired ChatGroupRoomFacade groups;
    @Autowired InMemoryChatRecruitSource recruits;
    @Autowired ChatUserUseCase users;
    @Autowired LocalChatConnections connections;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

    private final int guests = setting("CHAT_PERF_GUESTS", 25, 1, 199);
    private final int rate = setting("CHAT_PERF_RATE", 10, 1, 100);
    private final int seconds = setting("CHAT_PERF_SECONDS", 20, 1, 300);
    private final boolean receipts = Boolean.parseBoolean(System.getenv().getOrDefault("CHAT_PERF_RECEIPTS", "true"));
    private final AtomicInteger received = new AtomicInteger();
    private final AtomicInteger duplicates = new AtomicInteger();
    private final AtomicInteger controls = new AtomicInteger();
    private final AtomicInteger readFrames = new AtomicInteger();
    private final AtomicInteger httpOk = new AtomicInteger();
    private final AtomicInteger httpErrors = new AtomicInteger();
    private final AtomicInteger skippedUpdates = new AtomicInteger();
    private final AtomicLong heapPeak = new AtomicLong();
    private final String prefix = "perf-" + UUID.randomUUID() + ":";

    @Test
    @WithMockUser(roles = "INFLUENCER")
    void explore() throws Exception {
        int total = rate * seconds;
        AtomicLongArray sentAt = new AtomicLongArray(total);
        List<Peer> peers = new ArrayList<>();
        CountDownLatch ready = new CountDownLatch(guests + 1);
        users.createIfAbsent(activeUser(HOST_ID, "실험호스트", Role.INFLUENCER));
        long recruitId = 900_001L;
        recruits.putRecruit(recruitId, HOST_ID, "로컬 성능 실험", "RECRUITING");
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < guests; i++) {
            long id = 10_000L + i;
            users.createIfAbsent(activeUser(id, "실험게스트" + i, Role.INFLUENCER));
            ids.add(id);
        }
        recruits.addApplicants(recruitId, ids.toArray(Long[]::new));
        long roomId = groups.createRoom(HOST_ID, recruitId).room().chatRoomId();
        for (int i = 0; i < guests; i += 50) {
            assertThat(groups.invite(roomId, HOST_ID, UUID.randomUUID(), ids.subList(i, Math.min(i + 50, guests)))
                    .results()).allSatisfy(result -> assertThat(result.result()).isEqualTo(ChatGroupInviteResult.JOINED));
        }

        RestTestClient rest = RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
        ThreadPoolExecutor updates = new ThreadPoolExecutor(16, 16, 0, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(1024));
        var ticker = Executors.newSingleThreadScheduledExecutor();
        try (HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            RealtimeStompClient stomp = new RealtimeStompClient(rest, port, connections);
            try {
                List<Long> all = new ArrayList<>(List.of(HOST_ID));
                all.addAll(ids);
                for (Long id : all) {
                    StompSession session = stomp.connect(id);
                    Peer peer = new Peer(id, session, total);
                    peers.add(peer);
                    StompHeaders headers = new StompHeaders();
                    headers.setDestination("/topic/chats/rooms/" + roomId);
                    headers.add("participation-version", "1");
                    session.subscribe(headers, new StompFrameHandler() {
                        @Override public Type getPayloadType(StompHeaders h) { return Map.class; }
                        @Override public void handleFrame(StompHeaders h, Object payload) {
                            @SuppressWarnings("unchecked") var body = (Map<String, Object>) payload;
                            String type = h.getFirst("chat-frame-type");
                            if ("READ_COUNTS".equals(type)) { readFrames.incrementAndGet(); return; }
                            if (type != null) { controls.incrementAndGet(); return; }
                            String content = String.valueOf(body.get("content"));
                            long sequence = ((Number) body.get("roomSequence")).longValue();
                            if (content.equals(prefix + "probe")) {
                                synchronized (peer) { peer.base = sequence; }
                                ready.countDown();
                            } else if (content.startsWith(prefix)) {
                                int index = Integer.parseInt(content.substring(prefix.length()).split(":", 2)[0]);
                                synchronized (peer) {
                                    if (peer.seen.get(index)) { duplicates.incrementAndGet(); return; }
                                    peer.seen.set(index);
                                    peer.latency[index] = System.nanoTime() - sentAt.get(index);
                                    received.incrementAndGet();
                                }
                            }
                        }
                    });
                }
                Thread.sleep(1000);
                StompSession sender = peers.getFirst().session;
                String destination = "/pub/chats/" + roomId + "/messages";
                sender.send(destination, new SendChatMessageRequest(prefix + "probe", prefix + "probe"));
                assertThat(ready.await(20, TimeUnit.SECONDS)).as("전체 연결 구독 및 파이프라인 준비").isTrue();
                long cpuBefore = cpuTime();
                long started = System.nanoTime();
                ticker.scheduleAtFixedRate(() -> {
                    heapPeak.accumulateAndGet(ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed(), Math::max);
                    if (receipts) for (Peer peer : peers) enqueueUpdate(http, updates, peer, roomId);
                }, 1, 1, TimeUnit.SECONDS);
                long maxLag = 0;
                for (int i = 0; i < total; i++) {
                    long due = started + i * 1_000_000_000L / rate;
                    while (System.nanoTime() < due) LockSupport.parkNanos(due - System.nanoTime());
                    maxLag = Math.max(maxLag, System.nanoTime() - due);
                    sentAt.set(i, System.nanoTime());
                    sender.send(destination, new SendChatMessageRequest(prefix + i + ":" + "가".repeat(100), prefix + i));
                }
                long sentFinished = System.nanoTime();
                long deadline = sentFinished + TimeUnit.SECONDS.toNanos(30);
                int expected = total * peers.size();
                while (received.get() < expected && System.nanoTime() < deadline) Thread.sleep(50);
                long finished = System.nanoTime();
                // 마지막 읽음·ACK까지 흘려보내고 read-count 묶음도 관찰한다.
                Thread.sleep(2500);
                ticker.shutdownNow();
                updates.shutdown();
                assertThat(updates.awaitTermination(20, TimeUnit.SECONDS)).isTrue();

                long[] latencies = peers.stream().flatMapToLong(peer -> Arrays.stream(peer.latency))
                        .filter(value -> value > 0).sorted().toArray();
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("at", Instant.now().toString());
                result.put("environment", "local same-JVM server+clients; isolated Testcontainers PostgreSQL18/Redis8; pool20; heap2GiB");
                result.put("guests", guests);
                result.put("connections", peers.size());
                result.put("receiptsEnabled", receipts);
                result.put("targetMessagesPerSecond", rate);
                result.put("scheduledSeconds", seconds);
                result.put("attemptedMessages", total);
                result.put("sendSpanSeconds", (sentFinished - started) / 1e9);
                result.put("deliverySpanSeconds", (finished - started) / 1e9);
                result.put("maxSendScheduleLagMs", maxLag / 1e6);
                result.put("expectedFrames", expected);
                result.put("uniqueFrames", received.get());
                result.put("missingFrames", expected - received.get());
                result.put("duplicateFrames", duplicates.get());
                result.put("controlFrames", controls.get());
                result.put("connectedAtEnd", peers.stream().filter(p -> p.session.isConnected()).count());
                result.put("latencyP50Ms", percentile(latencies, .50));
                result.put("latencyP95Ms", percentile(latencies, .95));
                result.put("latencyP99Ms", percentile(latencies, .99));
                result.put("readCountFrames", readFrames.get());
                result.put("receiptHttpOk", httpOk.get());
                result.put("receiptHttpErrors", httpErrors.get());
                result.put("skippedReceiptTicks", skippedUpdates.get());
                result.put("sharedJvmPeakSampledHeapMiB", heapPeak.get() / 1048576.0);
                result.put("sharedJvmCpuSeconds", (cpuTime() - cpuBefore) / 1e9);
                result.put("storedMessages", jdbc.queryForObject(
                        "select count(*) from chats.chat_message where chat_room_id=? and content like ?",
                        Long.class, roomId, prefix + "%") - 1);
                Path output = Path.of("build/reports/chat-performance", guests + "-" + rate + "-" + receipts + ".json");
                Files.createDirectories(output.getParent());
                Files.writeString(output, JsonMapper.builder().build().writerWithDefaultPrettyPrinter().writeValueAsString(result));
                System.out.println("CHAT_PERF_RESULT " + output.toAbsolutePath());
                assertThat(received.get()).isEqualTo(expected);
                assertThat(duplicates.get()).isZero();
                assertThat(controls.get()).isZero();
                assertThat(httpErrors.get()).isZero();
                assertThat(result.get("storedMessages")).isEqualTo((long) total);
                assertThat(result.get("connectedAtEnd")).isEqualTo((long) peers.size());
            } finally {
                ticker.shutdownNow();
                updates.shutdownNow();
                stomp.close();
            }
        }
    }

    private void enqueueUpdate(HttpClient http, ThreadPoolExecutor executor, Peer peer, long roomId) {
        long sequence;
        synchronized (peer) { sequence = peer.base + peer.seen.nextClearBit(0); }
        if (sequence <= peer.lastUpdated) return;
        if (!peer.inFlight.compareAndSet(false, true)) { skippedUpdates.incrementAndGet(); return; }
        try {
            executor.execute(() -> {
                try {
                    boolean read = put(http, peer.id, roomId, "read", "{\"lastReadSequence\":" + sequence + ",\"participationVersion\":1}");
                    boolean ack = put(http, peer.id, roomId, "received", "{\"receivedSequence\":" + sequence + ",\"participationVersion\":1}");
                    if (read && ack) peer.lastUpdated = sequence;
                } finally { peer.inFlight.set(false); }
            });
        } catch (java.util.concurrent.RejectedExecutionException full) {
            peer.inFlight.set(false);
            skippedUpdates.incrementAndGet();
        }
    }

    private boolean put(HttpClient http, long id, long room, String endpoint, String json) {
        try {
            int status = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/chats/" + room + "/" + endpoint))
                    .timeout(Duration.ofSeconds(10)).header("X-User-Id", String.valueOf(id))
                    .header("X-User-Role", "INFLUENCER").header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(json)).build(), HttpResponse.BodyHandlers.discarding()).statusCode();
            if (status >= 200 && status < 300) { httpOk.incrementAndGet(); return true; }
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
        }
        httpErrors.incrementAndGet();
        return false;
    }

    private static long cpuTime() {
        return ((com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean()).getProcessCpuTime();
    }

    private static double percentile(long[] values, double quantile) {
        return values.length == 0 ? -1 : values[(int) Math.ceil(values.length * quantile) - 1] / 1e6;
    }

    private static int setting(String name, int fallback, int min, int max) {
        int value = Integer.parseInt(System.getenv().getOrDefault(name, String.valueOf(fallback)));
        if (value < min || value > max) throw new IllegalArgumentException(name + " 범위: " + min + ".." + max);
        return value;
    }

    private static final class Peer {
        final long id;
        final StompSession session;
        final BitSet seen = new BitSet();
        final long[] latency;
        final AtomicBoolean inFlight = new AtomicBoolean();
        long base;
        volatile long lastUpdated;
        Peer(long id, StompSession session, int total) {
            this.id = id; this.session = session; this.latency = new long[total];
        }
    }
}
