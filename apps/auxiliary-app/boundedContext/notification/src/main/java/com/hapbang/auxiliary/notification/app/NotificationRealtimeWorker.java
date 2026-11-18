package com.hapbang.auxiliary.notification.app;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.context.SmartLifecycle;

import lombok.extern.slf4j.Slf4j;

/**
 * 실시간 전달 발행 스레드(ND-205·ND-211). 스레드 1개가 {@link NotificationRealtimeRelay}의 줄을 묶음으로 비운다. 스레드가 하나라
 * 같은 받는 사람의 프레임은 줄에 넣은 순서대로 나간다.
 * <ul>
 *     <li>한 묶음에서 예외가 나도 WARN을 남기고 다음 묶음으로 넘어간다(스레드는 멈추지 않는다). 그 묶음의 남은 프레임은 보내지 못했을
 *     수 있고, FE가 REST로 맞춘다(ND-027).</li>
 *     <li>멈추면 스레드에 멈춤만 알리고(interrupt하지 않는다) 처리 중인 묶음이 끝나기를 {@value #STOP_WAIT_SECONDS}초까지 기다린 뒤
 *     Relay를 닫는다. 남은 줄은 보내지 않고 버리고 그 수를 WARN 한 줄로 남긴다. 닫힌 뒤 넘긴 프레임은 Relay가 거절한다. 묶음이 대기
 *     시간 안에 끝나지 않으면(Redis 시간 제한 10초, 파기 순서 잠금 대기) 종료가 이어지고, 그 묶음의 남은 프레임(최대 묶음 크기)은 그대로
 *     나가거나 연결이 닫히며 버림 지표({@code publish_failed}·{@code skipped}·{@code read_failed})로 세어진다.</li>
 *     <li>시작과 멈춤은 한 번에 하나씩만 한다. 이전 스레드가 아직 살아 있으면 대기 시간만큼 기다리고, 그래도 살아 있으면 시작하지 않는다.
 *     발행 스레드는 어느 때에도 하나다.</li>
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
    private final Duration stopWait;
    private volatile boolean running;
    /** 지금 스레드의 실행 표시. 스레드마다 따로 두어, 멈춘 뒤 끝나지 않은 이전 스레드가 재시작으로 다시 돌지 않게 한다 */
    private AtomicBoolean active;
    private Thread thread;

    public NotificationRealtimeWorker(NotificationRealtimeRelay relay) {
        this(relay, STOP_WAIT);
    }

    /** 멈출 때 기다리는 시간을 직접 넘긴다(테스트) */
    NotificationRealtimeWorker(NotificationRealtimeRelay relay, Duration stopWait) {
        this.relay = relay;
        this.stopWait = stopWait;
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        if (thread != null && thread.isAlive() && !await(thread)) {
            throw new IllegalStateException("이전 알림 실시간 전달 발행 스레드가 아직 끝나지 않아 시작하지 않음");
        }
        relay.open();
        AtomicBoolean runActive = new AtomicBoolean(true);
        active = runActive;
        thread = new Thread(() -> loop(runActive), THREAD_NAME + "1");
        thread.setDaemon(true);
        thread.start();
        running = true;
    }

    @Override
    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        active.set(false);
        if (!await(thread)) {
            log.warn("알림 실시간 전달 발행 스레드가 대기 시간 안에 끝나지 않아 종료를 이어감 waitMillis={}", stopWait.toMillis());
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

    /** 스레드가 끝나기를 대기 시간만큼 기다린다. 끝났으면 true */
    private boolean await(Thread target) {
        try {
            target.join(stopWait.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return !target.isAlive();
    }

    private void loop(AtomicBoolean runActive) {
        while (runActive.get()) {
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
