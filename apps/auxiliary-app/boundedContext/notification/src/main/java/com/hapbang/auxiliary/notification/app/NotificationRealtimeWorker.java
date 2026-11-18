package com.hapbang.auxiliary.notification.app;

import java.time.Duration;

import org.springframework.context.SmartLifecycle;

import lombok.extern.slf4j.Slf4j;

/**
 * 실시간 전달 발행 스레드(ND-205). 스레드 1개가 {@link NotificationRealtimeRelay}의 줄을 묶음으로 비운다. 스레드가 하나라 같은 받는
 * 사람의 프레임 순서가 지켜진다.
 * <ul>
 *     <li>한 묶음에서 예외가 나도 WARN을 남기고 다음 묶음으로 넘어간다(스레드는 멈추지 않는다). 그 묶음의 남은 프레임은 보내지 못했을
 *     수 있고, FE가 REST로 맞춘다(ND-027).</li>
 *     <li>멈추면 스레드를 끝낸 뒤 Relay를 닫는다. 남은 줄은 보내지 않고 버리고 그 수를 WARN 한 줄로 남긴다. 닫힌 뒤 넘긴 프레임은
 *     Relay가 거절한다. 멈출 때 스레드를 {@value #STOP_WAIT_SECONDS}초 기다리는데 Redis 시간 제한(10초)보다 짧아, 발행 중이던 1건은
 *     끝나지 않은 채 종료가 이어질 수 있다(그 1건은 {@code publish_failed}가 되거나 그대로 나간다).</li>
 *     <li>Kafka 리스너 컨테이너보다 먼저 시작하고 나중에 멈춘다. 저장이 멈춘 뒤에 발행 스레드가 멈춘다.</li>
 * </ul>
 */
@Slf4j
public class NotificationRealtimeWorker implements SmartLifecycle {

    /** 발행 스레드 이름(스레드 덤프에서 찾는다) */
    public static final String THREAD_NAME = "notification-realtime-";

    /** Kafka 리스너 컨테이너({@code AbstractMessageListenerContainer.DEFAULT_PHASE})보다 앞 */
    private static final int PHASE = Integer.MAX_VALUE - 200;
    /** 줄이 비었을 때 한 번에 기다리는 시간. 멈춤 요청을 이 간격 안에 알아챈다 */
    private static final Duration POLL = Duration.ofMillis(500);
    private static final long STOP_WAIT_SECONDS = 5;
    private static final Duration STOP_WAIT = Duration.ofSeconds(STOP_WAIT_SECONDS);

    private final NotificationRealtimeRelay relay;
    private volatile boolean running;
    private Thread thread;

    public NotificationRealtimeWorker(NotificationRealtimeRelay relay) {
        this.relay = relay;
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        relay.open();
        thread = new Thread(this::loop, THREAD_NAME + "1");
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public void stop() {
        Thread current;
        synchronized (this) {
            if (!running) {
                return;
            }
            running = false;
            current = thread;
        }
        current.interrupt();
        try {
            current.join(STOP_WAIT.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        int discarded = relay.close();
        if (discarded > 0) {
            log.warn("멈추면서 보내지 못한 알림 실시간 전달 프레임을 버림 count={}", discarded);
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return PHASE;
    }

    private void loop() {
        while (running) {
            try {
                relay.deliverPending(POLL);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException exception) {
                log.warn("알림 실시간 전달 묶음을 끝까지 처리하지 못해 다음 묶음으로 넘어감", exception);
            }
        }
    }
}
