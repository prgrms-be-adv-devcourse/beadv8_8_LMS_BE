package com.hapbang.chat.in.websocket;

import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.support.MessageHandlingRunnable;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.socket.CloseStatus;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;

/**
 * STOMP 채널(clientInbound·clientOutbound)의 앱 전체 처리 예산(설계 4.6). 스레드 수와 대기열에 상한을 둔 실행기를 만든다.
 * 대기열 단위는 처리 작업이다. Spring 채널은 프레임 하나를 구독한 처리기마다 따로 넣는다(출력 채널 1개, 수신 채널 3개).
 * 세션 종료 정리(DISCONNECT) 작업은 넘쳐도 버리지 않고 호출한 스레드에서 실행한다(내장 브로커의 구독 정리).
 * <p>
 * <b>넘칠 때</b>: 대기열이 가득 차면 실행기의 거절 처리기가 그 프레임을 버리고, 프레임의 {@code simpSessionId}
 * (= WebSocket 세션 ID) 연결을 지정한 종료 사유로 닫는다. 정책 종료가 아니므로 FE는 재연결하고 재동기화한다. 버린 프레임 수는
 * 지표로 센다.
 * <ul>
 *     <li>거절을 예외로 던지지 않는다. Spring의 {@code ExecutorSubscribableChannel}은 {@code RejectedExecutionException}을 받으면
 *     그 프레임을 호출한 스레드에서 그대로 실행하므로(호출자 실행), 예외를 던지면 상한이 사라지고 호출 스레드가 막힌다.
 *     그래서 거절 처리기에서 끝낸다.</li>
 *     <li>닫기는 소켓 쓰기를 동반할 수 있어 별도의 한 스레드에서 한다. 같은 연결은 닫기를 기다리는 동안 한 번만 넣는다.
 *     닫기 대기열마저 차면 그 연결은 다음에 버려지는 프레임이나 송신 시간·버퍼 상한으로 닫힌다.</li>
 *     <li>빈으로 등록하지 않는다. Executor 빈을 늘리면 Boot의 기본 실행기 선택이 바뀐다.
 *     {@link ChatWebSocketConfig}가 {@code registration.executor(...)}로 직접 넣는다(Boot configurer보다 나중에 불려 이긴다).</li>
 * </ul>
 */
@Slf4j
public abstract class ChatChannelBudget implements DisposableBean {

    private final ChatWebSocketSessionRegistry chatWebSocketSessionRegistry;
    private final CloseStatus overflowStatus;
    private final ThreadPoolTaskExecutor executor;
    private final ThreadPoolTaskExecutor closer;
    private final Set<String> closing = ConcurrentHashMap.newKeySet();
    private final Counter rejected;

    protected ChatChannelBudget(String name, ChatWebSocketSessionRegistry chatWebSocketSessionRegistry,
            CloseStatus overflowStatus, Counter rejected, int workers, int queueCapacity, int closeQueueCapacity) {
        this.chatWebSocketSessionRegistry = chatWebSocketSessionRegistry;
        this.overflowStatus = overflowStatus;
        this.rejected = rejected;
        this.executor = new ThreadPoolTaskExecutor();
        this.executor.setThreadNamePrefix("chat-" + name + "-");
        this.executor.setCorePoolSize(workers);
        this.executor.setMaxPoolSize(workers);
        this.executor.setQueueCapacity(queueCapacity);
        this.executor.setRejectedExecutionHandler(this::onOverflow);
        this.executor.initialize();
        this.closer = new ThreadPoolTaskExecutor();
        this.closer.setThreadNamePrefix("chat-" + name + "-overflow-");
        this.closer.setCorePoolSize(1);
        this.closer.setMaxPoolSize(1);
        this.closer.setQueueCapacity(closeQueueCapacity);
        this.closer.initialize();
    }

    protected static Counter counter(MeterRegistry meterRegistry, String metric) {
        return meterRegistry.counter(metric);
    }

    /** 상한이 있는 공용 처리 실행기(스레드·대기열 상한) */
    public ThreadPoolTaskExecutor executor() {
        return executor;
    }

    /** 채널에 넣을 실행기. 기본은 공용 처리 실행기 그대로다 */
    public Executor channelExecutor() {
        return executor;
    }

    /**
     * 처리 예산을 넘긴 연결을 닫는다. 같은 연결은 닫기를 기다리는 동안 한 번만 넣는다. 닫기는 별도 스레드에서 한다.
     *
     * @return 닫기를 맡겼거나 이미 닫는 중이면 true. 닫기 대기열이 가득 차 맡기지 못했으면 false(호출한 쪽이 다시 시도한다)
     */
    protected boolean closeOverflowing(String sessionId) {
        if (sessionId == null || !closing.add(sessionId)) {
            return true;
        }
        try {
            closer.execute(() -> {
                try {
                    if (chatWebSocketSessionRegistry.closeSession(sessionId, overflowStatus)) {
                        log.warn("처리 예산을 넘겨 프레임을 버린 연결을 닫음 sessionId={}, reason={}", sessionId,
                                overflowStatus.getReason());
                    }
                } finally {
                    closing.remove(sessionId);
                }
            });
            return true;
        } catch (TaskRejectedException closerFull) {
            closing.remove(sessionId);
            log.error("처리 예산 초과 연결의 닫기 대기열도 가득 참. 다음에 버리는 프레임에서 다시 닫는다 sessionId={}", sessionId);
            return false;
        }
    }

    /** 버린 프레임 수를 센다 */
    protected void countRejected() {
        rejected.increment();
    }

    /** 공용 실행기가 거절한 작업. 하위 클래스가 자기 작업을 직접 처리하려면 이 인터페이스를 구현한다 */
    protected interface RejectionAware {
        void onRejected();
    }

    /** 세션 종료 정리(DISCONNECT)는 버리지 않는다. 버리면 내장 브로커가 닫힌 연결의 구독을 지우지 못한다 */
    protected static boolean isDisconnect(Message<?> message) {
        return SimpMessageHeaderAccessor.getMessageType(message.getHeaders()) == SimpMessageType.DISCONNECT;
    }

    @Override
    public void destroy() {
        executor.shutdown();
        closer.shutdown();
    }

    private void onOverflow(Runnable task, ThreadPoolExecutor pool) {
        if (pool.isShutdown()) {
            return;
        }
        if (task instanceof RejectionAware aware) {
            aware.onRejected();
            return;
        }
        if (task instanceof MessageHandlingRunnable handling && isDisconnect(handling.getMessage())) {
            // 세션 종료 정리는 가볍고 반드시 돌아야 하므로 호출한 스레드에서 바로 실행한다
            task.run();
            return;
        }
        countRejected();
        closeOverflowing(task instanceof MessageHandlingRunnable handling ? sessionIdOf(handling.getMessage()) : null);
    }

    protected static String sessionIdOf(Message<?> message) {
        return SimpMessageHeaderAccessor.getSessionId(message.getHeaders());
    }
}
