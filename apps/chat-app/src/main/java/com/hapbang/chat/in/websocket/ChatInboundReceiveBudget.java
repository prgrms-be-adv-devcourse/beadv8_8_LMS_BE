package com.hapbang.chat.in.websocket;

import java.util.ArrayDeque;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageHandler;
import org.springframework.messaging.support.MessageHandlingRunnable;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;

/**
 * 앱 전체 수신 예산(설계 4.6). STOMP {@code clientInboundChannel}(클라이언트의 CONNECT·SUBSCRIBE·SEND 등)의 처리 실행기다.
 * 배치·재시도가 쓰는 공용 {@code taskScheduler}와 나눠, 전송 폭주가 배치를 굶기거나 배치가 전송을 막지 않게 한다.
 * <p>
 * <b>연결별 순서와 몫</b>: 채널에는 연결(STOMP 세션)마다 줄을 세우는 실행기({@link #channelExecutor()})를 넣는다.
 * <ul>
 *     <li>한 연결의 작업은 받은 순서대로 하나씩 처리한다. 그래서 한 연결이 빠르게 보낸 프레임은 보낸 순서대로 처리된다
 *     (메시지 매핑·내장 브로커·회원 목적지 처리기 모두).</li>
 *     <li>공용 실행기에는 연결마다 줄을 비우는 작업 하나만 들어간다. 한 연결이 프레임을 쏟아내도 공용 대기열을 채우지 못하고
 *     자기 줄에만 쌓인다. 한 줄 비우기 작업은 몇 개 처리한 뒤 공용 실행기 뒤로 돌아가 다른 연결에 차례를 넘긴다.</li>
 *     <li>한 연결의 줄에 아직 처리를 시작하지 않은 프레임이 {@code per-session-frames}개 있는데 새 프레임이 오면, 그 프레임을
 *     버리고 <b>그 연결</b>을 {@link ChatWebSocketSessionRegistry#INBOUND_OVERFLOW}로 닫는다. 이미 받은 프레임은 처리한다.
 *     프레임 단위로 센다: Spring 채널은 프레임 하나를 처리기 셋에 따로 넣으므로 같은 메시지 객체의 작업은 한 프레임이다.
 *     버린 프레임은 남은 처리기 몫도 함께 버린다(일부 처리기만 처리되지 않게).</li>
 *     <li>공용 실행기의 스레드·대기열 상한은 마지막 방어선으로 남는다. 줄 비우기 작업을 거절당한 연결은 닫는다.</li>
 *     <li>세션 종료 정리(DISCONNECT)는 몫·닫힘과 관계없이 줄에 넣고, 공용 실행기가 거절해도 호출한 스레드에서 실행한다.
 *     버리면 내장 브로커가 닫힌 연결의 구독을 지우지 못한다. 되풀이된 정리는 처리기마다 처리 대기 중인 하나로 합친다
 *     (줄이 늘지 않고, 처리기마다 정리가 적어도 한 번 실행된다).</li>
 *     <li>세션 종료 정리를 줄에 받은 뒤에는(처리 전이라도) 그 연결의 다른 작업을 받지 않는다(STOMP: DISCONNECT 뒤에는 프레임이
 *     없다). 받으면 정리보다 늦게 처리되어 구독이 남는다. 버린 작업 수는 {@value #CLOSED_SESSION_DROPPED_METRIC}으로 센다.</li>
 * </ul>
 * Spring의 {@code setPreserveReceiveOrder}는 쓰지 않는다. 7.0.9에서 그 순서 보장 장치는 채널 전송 예외를 기록만 하고 삼켜
 * ({@code ChatStompInterceptor}의 거절이 ERROR·연결 종료로 이어지지 않는다), 한 회원의 연결이 여럿이면 회원 목적지 전달에서
 * 같은 메시지를 다시 보내다 실패한다.
 * <p>
 * 오류 프레임만 보내지 않고 연결을 닫는 이유: 버린 프레임이 SUBSCRIBE면 클라이언트는 구독된 줄 알고 메시지를 놓치고, CONNECT면
 * CONNECTED를 받지 못한다. 오류 응답으로는 이 상태를 바로잡을 수 없다. 연결을 닫으면 FE가 재연결해 구독을 다시 만들고 재동기화하며,
 * 버린 SEND는 결과 미확인으로 남아 같은 {@code clientMessageId}로 다시 보내도 중복 저장되지 않는다. 버린 프레임 수는
 * {@value #REJECTED_METRIC}으로 센다.
 * <p>
 * 인증·경로 검사({@link ChatStompInterceptor})는 줄에 넣기 전 호출 스레드에서 끝난다.
 * <p>
 * <b>줄의 수명</b>(한 연결에 살아 있는 줄은 언제나 하나, 줄 비우기도 하나):
 * <ul>
 *     <li>지우기: 줄 모니터 안에서 {@code removed}를 세운다(줄이 비었고 세션 종료 정리를 처리한 뒤에만). 모니터를 놓은 뒤
 *     세션 종료 표식을 먼저 남기고 목록에서 뺀다. 지워진 줄은 다시 살아나지 않는다.</li>
 *     <li>넣기: 목록의 칸 잠금({@code compute}) 안에서 살아 있는 줄을 찾거나 만든다. 지워진 줄은 새 줄로 바꾼다. 새 줄은
 *     표식(또는 바꾸는 지워진 줄)의 세션 종료를 이어받아 세션 종료 정리만 받는다. 표식이 있는 연결에 다른 작업이 오면 줄을
 *     만들지 않고 버린다. 칸 잠금을 놓은 뒤
 *     줄 모니터 안에서 {@code removed}를 확인하고, 살아 있으면 같은 모니터 안에서 종료·닫힘 확인·넣기·줄 비우기 맡기를 한 번에 한다.
 *     그 사이 지워졌으면 한 번 더 하되, 이번에는 칸 잠금 안에서 줄 모니터를 잡고 확인과 넣기를 함께 해 반드시 끝난다.</li>
 *     <li>잠금 순서는 "칸 잠금 → 줄 모니터" 하나뿐이다. 줄 모니터를 쥔 채 칸 잠금을 잡는 곳은 없다.</li>
 * </ul>
 */
@Slf4j
@Component
public class ChatInboundReceiveBudget extends ChatChannelBudget {

    /** 앱 전체·연결별 수신 예산을 넘겨 버린 프레임(공용 실행기는 줄 비우기 작업) 수 */
    public static final String REJECTED_METRIC = "chat.websocket.inbound.rejected";
    /** 이미 처리 대기 중인 세션 종료 정리가 있어 합친(버린) DISCONNECT 프레임 수 */
    public static final String DISCONNECT_COALESCED_METRIC = "chat.websocket.inbound.disconnect_coalesced";
    /** 세션 종료 정리를 받은 뒤(정리 전후 모두) 같은 연결에 들어와 버린 작업 수 */
    public static final String CLOSED_SESSION_DROPPED_METRIC = "chat.websocket.inbound.closed_session_dropped";

    /** 한 줄 비우기 작업이 다른 연결에 차례를 넘기기 전에 처리하는 작업 수 */
    private static final int DRAIN_BATCH = 30;

    private final Map<String, SessionQueue> queues = new ConcurrentHashMap<>();
    private final int perSessionFrames;
    private final Counter disconnectCoalesced;
    private final Counter closedSessionDropped;
    /**
     * 세션 종료 정리까지 끝나 줄을 지운 연결의 표식(연결 ID → 만료 시각). 한 프레임의 처리기별 작업 사이에 다른 스레드의 세션 종료
     * 정리가 끝나도(넘침 닫기, 서버가 끊은 연결 등), 남은 처리기 작업이 새 줄을 만들어 정리 뒤에 처리되거나 줄이 남지 않게 한다.
     * 연결 ID는 연결마다 새로 만들어지므로 다시 연결한 클라이언트에는 걸리지 않는다. 만료되거나 상한을 넘으면 지운다
     */
    private final Map<String, Long> closedSessions = new ConcurrentHashMap<>();
    private Duration closedSessionTtl = Duration.ofSeconds(30);
    private int maxClosedSessions = 10_000;
    /** Spring의 메시지 브로커 통계 빈이 채널 실행기를 {@link TaskExecutor}로 받으므로 그 형태로 둔다 */
    private final TaskExecutor channelExecutor = this::submit;
    /** 이 스레드에서 거절 처리 중인 줄 비우기({@link Drain#onRejected}가 되부르지 않고 반복하도록) */
    private final ThreadLocal<RejectionRound> rejectionRound = new ThreadLocal<>();

    /**
     * 수신 스레드 수(8)·공용 대기열 상한(1000)·닫기 대기열 상한(1000)·연결별 처리 대기 프레임 상한(64)은 미확정 초기값(Q12)이다.
     * 기본값은 이 코드가 원본이고 application.yaml에 두지 않는다. 같은 키의 yaml·환경 변수가 덮는다.
     */
    @Autowired
    public ChatInboundReceiveBudget(ChatWebSocketSessionRegistry chatWebSocketSessionRegistry,
            MeterRegistry meterRegistry,
            @Value("${hapbang.chat.realtime.inbound.workers:8}") int workers,
            @Value("${hapbang.chat.realtime.inbound.queue-capacity:1000}") int queueCapacity,
            @Value("${hapbang.chat.realtime.inbound.close-queue-capacity:1000}") int closeQueueCapacity,
            @Value("${hapbang.chat.realtime.inbound.per-session-frames:64}") int perSessionFrames) {
        this(chatWebSocketSessionRegistry, counter(meterRegistry, REJECTED_METRIC),
                counter(meterRegistry, DISCONNECT_COALESCED_METRIC), counter(meterRegistry, CLOSED_SESSION_DROPPED_METRIC),
                workers, queueCapacity, closeQueueCapacity, perSessionFrames);
    }

    private ChatInboundReceiveBudget(ChatWebSocketSessionRegistry chatWebSocketSessionRegistry, Counter rejected,
            Counter disconnectCoalesced, Counter closedSessionDropped, int workers, int queueCapacity,
            int closeQueueCapacity, int perSessionFrames) {
        super("inbound", chatWebSocketSessionRegistry, ChatWebSocketSessionRegistry.INBOUND_OVERFLOW, rejected,
                workers, queueCapacity, closeQueueCapacity);
        Assert.isTrue(perSessionFrames > 0, "hapbang.chat.realtime.inbound.per-session-frames는 1 이상이어야 합니다.");
        this.perSessionFrames = perSessionFrames;
        this.disconnectCoalesced = disconnectCoalesced;
        this.closedSessionDropped = closedSessionDropped;
    }

    /** 세션 종료 표식을 남기는 시간. 미확정 초기값(Q12) */
    @Value("${hapbang.chat.realtime.inbound.closed-session-ttl:30s}")
    public void setClosedSessionTtl(Duration closedSessionTtl) {
        Assert.isTrue(closedSessionTtl.isPositive(), "hapbang.chat.realtime.inbound.closed-session-ttl은 0보다 커야 합니다.");
        this.closedSessionTtl = closedSessionTtl;
    }

    /** 세션 종료 표식의 최대 개수. 미확정 초기값(Q12) */
    @Value("${hapbang.chat.realtime.inbound.max-closed-sessions:10000}")
    public void setMaxClosedSessions(int maxClosedSessions) {
        Assert.isTrue(maxClosedSessions > 0, "hapbang.chat.realtime.inbound.max-closed-sessions는 1 이상이어야 합니다.");
        this.maxClosedSessions = maxClosedSessions;
    }

    /** 만료되지 않은 세션 종료 표식 수(관측·테스트용). 만료된 표식은 이때 지운다 */
    public int closedSessionMarkers() {
        purgeExpiredClosedSessions(System.nanoTime());
        return closedSessions.size();
    }

    /** 표식을 남긴다. 칸 잠금 안에서도 부르므로 이 키 하나만 쓴다(만료·상한 정리는 {@link #trimClosedSessions}) */
    private void rememberClosed(String sessionId) {
        closedSessions.put(sessionId, System.nanoTime() + closedSessionTtl.toNanos());
    }

    /**
     * 상한을 넘었으면 만료된 표식을 지우고, 그래도 넘으면 방금 남긴 표식 밖의 것부터 지운다. 잠금 밖에서 부른다. 연결이 끝날 때마다
     * 표식을 남기므로 매번 전체를 훑지 않는다: 만료된 표식은 상한에 닿을 때와 읽을 때({@code recentlyClosed}) 지운다
     */
    private void trimClosedSessions(String sessionId) {
        if (closedSessions.size() <= maxClosedSessions) {
            return;
        }
        purgeExpiredClosedSessions(System.nanoTime());
        // 상한을 넘으면 다른 표식부터 지운다(늦은 작업은 보통 곧바로 오므로 오래된 표식의 쓸모는 작다)
        var iterator = closedSessions.keySet().iterator();
        while (closedSessions.size() > maxClosedSessions && iterator.hasNext()) {
            if (!iterator.next().equals(sessionId)) {
                iterator.remove();
            }
        }
    }

    /** 연결별 줄을 찾거나 만들기 직전. 테스트가 이 순간의 선점을 재현할 때만 덮어쓴다 */
    void beforeSessionQueue(String sessionId) {
    }

    private boolean recentlyClosed(String sessionId) {
        Long expiresAt = closedSessions.get(sessionId);
        if (expiresAt == null) {
            return false;
        }
        if (expiresAt - System.nanoTime() <= 0) {
            closedSessions.remove(sessionId, expiresAt);
            return false;
        }
        return true;
    }

    private void purgeExpiredClosedSessions(long now) {
        closedSessions.entrySet().removeIf(entry -> entry.getValue() - now <= 0);
    }

    /** 연결마다 줄을 세우는 채널 실행기 */
    @Override
    public Executor channelExecutor() {
        return channelExecutor;
    }

    /** 처리를 시작하지 않은 작업이 남아 있는 연결 수(관측·테스트용) */
    public int sessionsWithQueuedWork() {
        return (int) queues.values().stream().filter(SessionQueue::hasQueued).count();
    }

    private void submit(Runnable task) {
        Message<?> message = task instanceof MessageHandlingRunnable handling ? handling.getMessage() : null;
        String sessionId = message == null ? null : sessionIdOf(message);
        if (sessionId == null) {
            executor().execute(task);
            return;
        }
        boolean disconnect = isDisconnect(message);
        MessageHandler handler = ((MessageHandlingRunnable) task).getMessageHandler();
        beforeSessionQueue(sessionId);
        // 1차: 칸 잠금 안에서 살아 있는 줄을 찾거나 만들고, 칸 잠금을 놓은 뒤 줄 모니터 안에서 넣는다
        SessionQueue queue = queues.compute(sessionId, (id, existing) -> liveOrNew(id, existing, disconnect));
        Offer offer = queue == null ? null : offerAfterLookup(queue, task, message, handler);
        if (offer == Offer.REMOVED) {
            // 찾은 뒤 넣기 전에 줄이 지워졌다. 2차: 칸 잠금 안에서 줄 모니터를 잡아 확인과 넣기를 함께 한다(잠금 순서 그대로)
            Offer[] placed = {null};
            queue = queues.compute(sessionId, (id, existing) -> {
                if (existing != null) {
                    synchronized (existing) {
                        if (!existing.removed) {
                            placed[0] = existing.offer(task, message, handler);
                            return existing;
                        }
                    }
                }
                SessionQueue live = liveOrNew(id, existing, disconnect);
                if (live != null) {
                    // 방금 만든 줄은 칸 잠금을 놓기 전까지 누구도 보지 못해 지워질 수 없다
                    placed[0] = live.offer(task, message, handler);
                }
                return live;
            });
            offer = placed[0];
        }
        if (queue == null) {
            closedSessionDropped.increment();
            return;
        }
        switch (offer) {
            case QUEUED -> {
            }
            case QUEUED_DRAIN -> executor().execute(new Drain(queue));
            case OVERFLOW, DROPPED_FRAME -> {
                countRejected();
                requestClose(queue);
            }
            case DROPPED_TASK -> {
                // 이미 버린 프레임의 남은 처리기 몫
            }
            case DISCONNECT_COALESCED -> disconnectCoalesced.increment();
            case CLOSED_SESSION_DROPPED -> closedSessionDropped.increment();
            case REMOVED -> throw new IllegalStateException("지워진 줄에 넣을 수 없습니다. sessionId=" + sessionId);
        }
    }

    /** 칸 잠금을 놓은 뒤 줄 모니터 안에서 넣는다. 테스트가 찾기와 넣기 사이의 선점을 재현하도록 그 사이에 hook을 둔다 */
    private Offer offerAfterLookup(SessionQueue queue, Runnable task, Message<?> message, MessageHandler handler) {
        beforeOffer(queue.sessionId);
        return queue.offer(task, message, handler);
    }

    /**
     * 칸 잠금({@code compute}) 안에서 부른다. 살아 있는 줄은 그대로, 지워졌거나 없으면 새 줄을 만든다. 지워진 줄은 언제나 세션
     * 종료 정리를 처리한 줄이므로, 그 줄을 바꾸거나 표식이 있으면 새 줄은 세션 종료를 이어받는다. 그런 연결에 세션 종료 정리가 아닌
     * 작업이 오면 줄을 만들지 않는다(null).
     */
    private SessionQueue liveOrNew(String sessionId, SessionQueue existing, boolean disconnect) {
        if (existing != null && !existing.removed) {
            return existing;
        }
        if (existing != null) {
            // 지운 쪽이 표식을 남기기 전에 이 자리를 바꿀 수 있으므로 여기서도 남긴다(지운 줄이 목록에 없으면 표식이 있다는
            // 규칙을 지킨다). 상한 정리는 지운 쪽의 unlink가 한다
            rememberClosed(sessionId);
        }
        boolean ended = existing != null || recentlyClosed(sessionId);
        if (ended && !disconnect) {
            return null;
        }
        return new SessionQueue(sessionId, ended);
    }

    /**
     * 지운 줄을 목록에서 뺀다. 세션 종료 표식을 <b>먼저</b> 남긴다: 목록에서 빠진 순간 들어오는 작업이 표식을 보게 한다. 넘침
     * 닫기만이 아니라 모든 연결 종료에 남긴다(서버가 끊은 연결도 한 프레임의 처리기별 작업 사이에 정리가 끝날 수 있다).
     * 줄 모니터 밖에서 부른다(잠금 순서). 여러 번 불러도 된다.
     */
    private void unlink(SessionQueue queue) {
        rememberClosed(queue.sessionId);
        queues.remove(queue.sessionId, queue);
        trimClosedSessions(queue.sessionId);
    }

    /** 칸 잠금을 놓고 줄 모니터를 잡기 직전. 테스트가 이 순간의 선점을 재현할 때만 덮어쓴다 */
    void beforeOffer(String sessionId) {
    }

    /** 닫기를 아직 맡기지 못했으면(닫기 대기열이 가득 참) 다시 맡긴다. 그래서 몫을 넘긴 연결이 열린 채 프레임만 버려지지 않는다 */
    private void requestClose(SessionQueue queue) {
        if (!queue.closeRequested()) {
            queue.closeRequested(closeOverflowing(queue.sessionId));
        }
    }

    /** {@link SessionQueue#offer} 결과 */
    private enum Offer {
        /** 줄에 넣었다. 줄 비우기는 이미 돌고 있다 */
        QUEUED,
        /** 줄에 넣었고 이 호출이 줄 비우기를 맡았다(같은 모니터 안에서 맡아, 한 줄에 줄 비우기가 둘 생기지 않는다) */
        QUEUED_DRAIN,
        /** 몫을 넘겨 이 프레임을 버렸다. 연결을 닫는다 */
        OVERFLOW,
        /** 닫는 중인 연결의 새 프레임을 버렸다. 닫기를 아직 맡기지 못했으면 다시 맡긴다 */
        DROPPED_FRAME,
        /** 버린 프레임의 남은 처리기 몫을 버렸다 */
        DROPPED_TASK,
        /** 이미 처리 대기 중인 세션 종료 정리가 있어 이 DISCONNECT를 합쳤다(같은 정리를 한 번만 한다) */
        DISCONNECT_COALESCED,
        /** 세션 종료 정리를 받은(또는 이어받은) 줄이다. 세션 종료 정리가 아닌 작업은 버린다 */
        CLOSED_SESSION_DROPPED,
        /** 줄이 이미 지워졌다. 호출한 쪽이 살아 있는 줄을 다시 찾는다 */
        REMOVED
    }

    /** 한 연결의 처리 줄 */
    private final class SessionQueue {

        private final String sessionId;
        private final ArrayDeque<Entry> entries = new ArrayDeque<>();
        /**
         * 줄에 남은 작업이 있는 프레임별 작업 수. 프레임은 메시지 객체로 구분한다: 한 프레임의 처리기별 작업은 같은 메시지 객체를 쓰고,
         * STOMP 수신 메시지는 헤더가 바뀔 수 있는 상태라 메시지 ID가 없다
         */
        private final Map<Message<?>, Integer> queuedPerFrame = new IdentityHashMap<>();
        private Message<?> tailFrame;
        private boolean draining;
        /** 몫을 넘겨 닫는 중이다. 세션 종료 정리 말고는 더 받지 않는다 */
        private boolean closed;
        /** 닫기를 닫기 실행기에 맡겼다 */
        private boolean closeRequested;
        /** 마지막으로 버린 프레임. 같은 프레임의 남은 처리기 몫은 한 번만 센다 */
        private Message<?> droppedFrame;
        /** 세션 종료 정리(DISCONNECT)를 처리했다. 이 뒤 줄이 비면 목록에서 지운다 */
        private boolean disconnectSeen;
        /**
         * 세션 종료 정리 작업이 줄에 남아 아직 처리를 시작하지 않은 처리기. 정리는 몫·닫힘과 관계없이 받으므로, 같은 연결이
         * DISCONNECT를 되풀이해도 줄이 늘지 않게 처리기마다 처리 대기 중인 정리 하나와 합친다. 처리기 단위로 합쳐야 두 DISCONNECT
         * (클라이언트가 보낸 것과 연결 종료 때 서버가 만든 것)가 엇갈려 들어와도 처리기마다 정리가 적어도 한 번 실행된다
         */
        private final Set<MessageHandler> pendingDisconnectHandlers = Collections.newSetFromMap(new IdentityHashMap<>());
        /** 마지막으로 합친 DISCONNECT 프레임. 같은 프레임의 남은 처리기 몫은 한 번만 센다 */
        private Message<?> coalescedDisconnect;
        /**
         * 세션 종료 정리를 줄에 받았다(처리 전이라도), 또는 정리까지 끝난 연결을 이어받은 줄이다. 이 뒤로 세션 종료 정리가 아닌
         * 작업은 받지 않는다: 받으면 정리보다 늦게 처리돼 구독이 남는다
         */
        private boolean ended;
        /**
         * 목록에서 지웠다(또는 지우는 중이다). 줄 모니터 안에서만 세우고 다시 내리지 않는다. 세운 뒤에는 아무것도 받지 않는다.
         * 칸 잠금 안에서 지워진 줄을 새 줄로 바꿀 때 모니터 없이 읽으므로 volatile이다
         */
        private volatile boolean removed;

        private SessionQueue(String sessionId, boolean ended) {
            this.sessionId = sessionId;
            this.ended = ended;
        }

        /** 넣기와 줄 비우기 맡기를 한 모니터 안에서 한다. 지워진 줄이면 아무것도 바꾸지 않고 {@link Offer#REMOVED} */
        synchronized Offer offer(Runnable task, Message<?> message, MessageHandler handler) {
            if (removed) {
                return Offer.REMOVED;
            }
            boolean disconnect = isDisconnect(message);
            if (disconnect) {
                ended = true;
                if (!pendingDisconnectHandlers.add(handler)) {
                    // 이 처리기의 정리는 이미 처리를 기다리고 있다. 프레임마다 한 번만 센다
                    if (message == coalescedDisconnect) {
                        return Offer.DROPPED_TASK;
                    }
                    coalescedDisconnect = message;
                    return Offer.DISCONNECT_COALESCED;
                }
            } else {
                if (closed) {
                    // 닫는 중인 연결의 새 프레임과 버린 프레임의 남은 처리기 몫은 받지 않는다(닫기를 못 맡겼으면 다시 맡긴다)
                    if (message == droppedFrame) {
                        return Offer.DROPPED_TASK;
                    }
                    droppedFrame = message;
                    return Offer.DROPPED_FRAME;
                }
                if (ended) {
                    return Offer.CLOSED_SESSION_DROPPED;
                }
                if (message != tailFrame && queuedPerFrame.size() >= perSessionFrames) {
                    closed = true;
                    droppedFrame = message;
                    log.warn("연결의 처리 대기 프레임이 몫을 넘어 연결을 닫음 sessionId={}, queuedFrames={}", sessionId,
                            queuedPerFrame.size());
                    return Offer.OVERFLOW;
                }
                tailFrame = message;
                // 뒤에 프레임이 선 정리와는 합치지 않는다(합치면 마지막 정리가 프레임보다 앞선다). 정리를 받은 줄은 프레임을 받지
                // 않으므로 여기서 비어 있어야 하지만, 순서 규칙을 이 자리에서도 지킨다
                pendingDisconnectHandlers.clear();
            }
            entries.add(new Entry(task, message, disconnect, handler));
            queuedPerFrame.merge(message, 1, Integer::sum);
            if (draining) {
                return Offer.QUEUED;
            }
            draining = true;
            return Offer.QUEUED_DRAIN;
        }

        synchronized boolean closeRequested() {
            return closeRequested;
        }

        synchronized void closeRequested(boolean requested) {
            closeRequested |= requested;
        }

        /**
         * 다음 작업. 비었으면 줄 비우기 차례를 내려놓고 null. 세션 종료 정리를 처리한 뒤 비었으면 {@code removed}를 세운다(어느
         * 줄 비우기 실행에서 비든: 세션 종료 정리가 한 실행의 마지막 작업이어도 남지 않는다). 목록에서 빼기({@link #unlink})는
         * 호출한 쪽이 모니터를 놓은 뒤 한다.
         */
        synchronized Entry poll() {
            Entry entry = entries.poll();
            if (entry == null) {
                draining = false;
                if (disconnectSeen) {
                    removed = true;
                }
                return null;
            }
            disconnectSeen |= entry.disconnect();
            queuedPerFrame.computeIfPresent(entry.frame(), (frame, count) -> count == 1 ? null : count - 1);
            if (entry.disconnect()) {
                // 이 처리기의 정리를 시작한다. 이후 이 처리기의 DISCONNECT는 새 정리로 받는다
                pendingDisconnectHandlers.remove(entry.handler());
            }
            return entry;
        }

        synchronized boolean hasQueued() {
            return !entries.isEmpty();
        }

        /**
         * 공용 실행기가 줄 비우기를 거절했다: 세션 종료 정리만 남겨 꺼내고 나머지는 버린다. 버린 프레임이 있으면 닫는 중으로 둔다.
         * 닫는 중인 줄은 세션 종료 정리를 실제로 처리할 때까지 목록에 남겨(표식), 그 사이 같은 연결의 프레임이 새 줄로 처리되지 않게 한다.
         * 줄 비우기 차례({@code draining})는 내려놓지 않는다: 꺼낸 정리를 호출한 스레드에서 실행하는 동안에도 이 줄을 맡은 실행은
         * 그 하나뿐이어야 한다(그 사이 들어온 작업은 줄에서 기다린다). 실행이 끝나면 {@link #finishInline}이 차례를 정리한다.
         *
         * @return 버린 일반 프레임이 있었는지와 꺼낸 세션 종료 정리
         */
        synchronized Rejected rejectAndTakeDisconnects() {
            List<Runnable> disconnects = new ArrayList<>();
            boolean droppedFrames = false;
            for (Entry entry : entries) {
                if (entry.disconnect()) {
                    disconnects.add(entry.task());
                } else {
                    droppedFrames = true;
                }
            }
            entries.clear();
            queuedPerFrame.clear();
            pendingDisconnectHandlers.clear();
            if (droppedFrames) {
                closed = true;
                // 거절 순간 아직 들어오는 중인 같은 프레임의 남은 처리기 몫은 한 번만 센다
                droppedFrame = tailFrame;
            }
            // 꺼낸 정리는 호출한 쪽이 곧바로 실행한다
            disconnectSeen |= !disconnects.isEmpty();
            return new Rejected(droppedFrames, disconnects);
        }

        /**
         * 세션 종료 정리까지 처리해 줄이 비었으면 {@code removed}를 세운다. 정리 전이면 닫는 중 표식으로 남긴다.
         *
         * @return 지웠으면 true. 호출한 쪽이 모니터를 놓은 뒤 {@link #unlink}한다
         */
        synchronized boolean releaseIfFinished() {
            if (!removed && disconnectSeen && entries.isEmpty() && !draining) {
                removed = true;
            }
            return removed;
        }

        /**
         * 거절 뒤 꺼낸 세션 종료 정리를 호출한 스레드에서 다 실행했다. 그 사이 들어온 작업이 있으면 차례를 그대로 쥐고
         * {@link InlineFinish#RESUME}(호출한 쪽이 줄 비우기를 다시 맡긴다). 없으면 차례를 내려놓고, 정리를 처리했으면 지운다.
         */
        synchronized InlineFinish finishInline() {
            if (!entries.isEmpty()) {
                return InlineFinish.RESUME;
            }
            draining = false;
            return releaseIfFinished() ? InlineFinish.REMOVED : InlineFinish.IDLE;
        }
    }

    private record Entry(Runnable task, Message<?> frame, boolean disconnect, MessageHandler handler) {
    }

    private record Rejected(boolean droppedFrames, List<Runnable> disconnects) {
    }

    /** 한 스레드에서 진행 중인 줄 비우기 거절 처리. 같은 줄 비우기가 다시 거절됐는지 바깥 반복에 알린다 */
    private static final class RejectionRound {

        private final Drain drain;
        private boolean rejectedAgain;

        private RejectionRound(Drain drain) {
            this.drain = drain;
        }
    }

    /** {@link SessionQueue#finishInline} 결과 */
    private enum InlineFinish {
        /** 그 사이 들어온 작업이 있다. 차례를 쥔 채 줄 비우기를 다시 맡긴다 */
        RESUME,
        /** 줄이 비어 차례를 내려놓았다 */
        IDLE,
        /** 줄이 비었고 세션 종료 정리를 처리해 지웠다. 목록에서 뺀다 */
        REMOVED
    }

    /** 한 연결의 줄을 비운다. 여러 작업을 처리한 뒤에는 공용 실행기의 뒤로 돌아가 다른 연결에 차례를 넘긴다 */
    private final class Drain implements Runnable, RejectionAware {

        private final SessionQueue queue;

        private Drain(SessionQueue queue) {
            this.queue = queue;
        }

        @Override
        public void run() {
            for (int processed = 0; processed < DRAIN_BATCH; processed++) {
                Entry entry = queue.poll();
                if (entry == null) {
                    if (queue.removed) {
                        unlink(queue);
                    }
                    return;
                }
                runSafely(entry.task());
            }
            // 차례를 넘긴다. 그 사이 줄이 비었으면 다음 실행이 비었음을 보고 차례를 내려놓는다
            executor().execute(this);
        }

        /**
         * 공용 실행기가 이 줄 비우기를 거절했다. 다시 맡긴 줄 비우기가 같은 스레드에서 또 거절되면 되부르지 않고(호출 깊이가 쌓이지
         * 않게) 바깥 반복이 한 번 더 돈다.
         */
        @Override
        public void onRejected() {
            RejectionRound outer = rejectionRound.get();
            if (outer != null && outer.drain == this) {
                outer.rejectedAgain = true;
                return;
            }
            RejectionRound round = new RejectionRound(this);
            rejectionRound.set(round);
            try {
                do {
                    round.rejectedAgain = false;
                    rejectOnce();
                } while (round.rejectedAgain);
            } finally {
                if (outer == null) {
                    rejectionRound.remove();
                } else {
                    rejectionRound.set(outer);
                }
            }
        }

        private void rejectOnce() {
            Rejected rejected = queue.rejectAndTakeDisconnects();
            if (rejected.droppedFrames()) {
                countRejected();
                requestClose(queue);
            }
            // 세션 종료 정리는 버리지 않고 호출한 스레드에서 실행한다. 줄 모니터 밖에서 실행하고, 그동안 차례는 이 실행이 쥔다
            rejected.disconnects().forEach(Drain::runSafely);
            switch (queue.finishInline()) {
                // 다시 거절되면 onRejected가 rejectedAgain만 세우고 돌아와 바깥 반복이 이어 간다
                case RESUME -> executor().execute(this);
                case REMOVED -> unlink(queue);
                case IDLE -> {
                }
            }
        }

        private static void runSafely(Runnable task) {
            try {
                task.run();
            } catch (RuntimeException exception) {
                log.error("수신 프레임 처리 실패", exception);
            }
        }
    }
}
