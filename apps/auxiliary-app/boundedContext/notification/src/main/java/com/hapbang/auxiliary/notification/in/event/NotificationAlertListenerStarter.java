package com.hapbang.auxiliary.notification.in.event;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.stereotype.Component;

import com.hapbang.auxiliary.notification.app.NotificationUserBackfillService;
import com.hapbang.auxiliary.notification.app.exception.NotificationException;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;

/**
 * 출연·지갑 알림 소비자를 첫 회원 복제본 Backfill 뒤에 시작한다(ND-078). 기존 회원의 알림이 복제본이 없어 DLT로 빠지지 않게 한다.
 * <ul>
 *     <li>앱이 뜬 뒤({@link ApplicationReadyEvent}) 별도 스레드에서 돈다. 앱 기동, 회원 복제본 리스너(가입 환영·승급 승인 포함,
 *     ND-080), 챗봇·검색, REST·WebSocket은 기다리지 않는다.</li>
 *     <li>회원 API·DB 장애나 다른 실행(다른 pod, 관리자 재실행)이 잠금을 쥔 동안은 완료가 아니므로 백오프로 다시 시도하고, 그동안
 *     소비자는 멈춰 있다. 이벤트는 토픽에 남는다(72시간).</li>
 *     <li>처음 시도부터 {@code stall-alert-after}(기본 30분)가 지나도 마치지 못하면 사람이 봐야 하므로 alert 표식
 *     {@code [NOTIFICATION_BACKFILL_STALLED]}을 붙인 ERROR로 남긴다(docs/dev/logging.md). 그 전의 실패는 WARN(잠금 경쟁은 INFO)이다.</li>
 *     <li>기다리는 동안 게이지 {@value #WAITING_METRIC}은 1, 소비자를 시작하면 0이다(ND-093). pod마다 따로 내므로 값은 "이 pod의
 *     소비자가 기다리는 중"이다. 공용 alert는 앱별 max가 30분 넘게 1이면 울린다(어느 pod든 30분 넘게 기다림).</li>
 *     <li>{@value #ON_STARTUP}이 false면 Backfill 없이 바로 시작한다(모듈 테스트).</li>
 * </ul>
 */
@Slf4j
@Component
public class NotificationAlertListenerStarter implements DisposableBean {

    public static final String ON_STARTUP = "hapbang.notification.user-backfill.on-startup";
    public static final String WAITING_METRIC = "hapbang.notification.alert.listeners.waiting";
    /** Backfill 뒤에 시작하는 컨테이너 */
    static final List<String> ALERT_LISTENERS = List.of(NotificationAppearanceEventListener.LISTENER_ID,
            NotificationBillingEventListener.LISTENER_ID);

    private final NotificationUserBackfillService backfill;
    private final KafkaListenerEndpointRegistry registry;
    private final boolean onStartup;
    private final Duration retryInitialInterval;
    private final Duration retryMaxInterval;
    private final Duration stallAlertAfter;
    /** 대기 게이지 값(ND-093): 기동 Backfill을 기다리는 중이면 1, 소비자를 시작하면 0 */
    private final AtomicInteger waiting = new AtomicInteger();
    private volatile Thread worker;
    private volatile boolean closing;

    public NotificationAlertListenerStarter(NotificationUserBackfillService backfill,
            KafkaListenerEndpointRegistry registry,
            @Value("${" + ON_STARTUP + ":true}") boolean onStartup,
            @Value("${hapbang.notification.user-backfill.retry-initial-interval:5s}") Duration retryInitialInterval,
            @Value("${hapbang.notification.user-backfill.retry-max-interval:5m}") Duration retryMaxInterval,
            @Value("${hapbang.notification.user-backfill.stall-alert-after:30m}") Duration stallAlertAfter,
            ObjectProvider<MeterRegistry> meterRegistry) {
        this.backfill = backfill;
        this.registry = registry;
        this.onStartup = onStartup;
        this.retryInitialInterval = retryInitialInterval;
        this.retryMaxInterval = retryMaxInterval;
        this.stallAlertAfter = stallAlertAfter;
        meterRegistry.ifAvailable(meters -> Gauge.builder(WAITING_METRIC, waiting, AtomicInteger::get)
                .description("출연·지갑 알림 소비자가 기동 Backfill을 기다리는 중이면 1(pod마다)")
                .register(meters));
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (onStartup) {
            waiting.set(1);
            worker = Thread.ofVirtual().name("notification-user-backfill").start(this::backfillThenStart);
        } else {
            log.info("기동 Backfill이 꺼져 있어 알림 소비자를 바로 시작한다 property={}", ON_STARTUP);
            startAlertListeners();
        }
    }

    private void backfillThenStart() {
        Duration wait = retryInitialInterval;
        long startedAt = System.nanoTime();
        while (!closing && !Thread.currentThread().isInterrupted()) {
            try {
                backfill.run();
                startAlertListeners();
                return;
            } catch (RuntimeException e) {
                logRetry(e, Duration.ofNanos(System.nanoTime() - startedAt), wait);
            }
            try {
                Thread.sleep(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            wait = wait.multipliedBy(2).compareTo(retryMaxInterval) > 0 ? retryMaxInterval : wait.multipliedBy(2);
        }
    }

    /**
     * 다시 시도하기 전 로그. 처음 시도부터 {@code stall-alert-after}가 지났으면 사람이 봐야 하므로 alert 표식을 붙인 ERROR, 그 전에는
     * 잠금 경쟁(다른 pod·관리자 재실행, 정상)은 INFO, 장애는 WARN이다(docs/dev/logging.md 레벨)
     */
    private void logRetry(RuntimeException e, Duration stalled, Duration wait) {
        boolean lockHeld = e instanceof NotificationException;
        if (stalled.compareTo(stallAlertAfter) >= 0) {
            log.error("[NOTIFICATION_BACKFILL_STALLED] 기동 Backfill을 오래 마치지 못해 알림 소비자가 멈춰 있다 "
                    + "lockHeld={}, stalledFor={}, retryIn={}", lockHeld, stalled, wait, lockHeld ? null : e);
        } else if (lockHeld) {
            log.info("다른 실행이 Backfill 중이라 알림 소비자를 아직 시작하지 않았다 retryIn={}", wait);
        } else {
            log.warn("기동 Backfill을 마치지 못해 알림 소비자를 아직 시작하지 않았다 retryIn={}", wait, e);
        }
    }

    /** 종료 중이면 시작하지 않는다(종료 신호는 Kafka 컨테이너가 멈추기 전에 온다) */
    private synchronized void startAlertListeners() {
        if (closing) {
            return;
        }
        for (String id : ALERT_LISTENERS) {
            MessageListenerContainer container = registry.getListenerContainer(id);
            if (container != null && !container.isRunning()) {
                container.start();
                log.info("알림 소비자를 시작했다 listenerId={}", id);
            }
        }
        waiting.set(0);
    }

    /**
     * 컨텍스트 종료 신호. {@link ContextClosedEvent}는 lifecycle 빈(Kafka 컨테이너)을 멈추기 전에 오므로, Backfill이 종료 중에
     * 끝나도 컨테이너를 다시 시작하지 않는다
     */
    @EventListener(ContextClosedEvent.class)
    public synchronized void onClosing() {
        closing = true;
        stopWorker();
    }

    @Override
    public void destroy() {
        closing = true;
        stopWorker();
    }

    private void stopWorker() {
        Thread running = worker;
        if (running != null) {
            running.interrupt();
        }
    }
}
