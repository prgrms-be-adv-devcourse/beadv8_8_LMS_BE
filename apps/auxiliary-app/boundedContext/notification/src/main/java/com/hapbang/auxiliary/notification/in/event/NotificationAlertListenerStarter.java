package com.hapbang.auxiliary.notification.in.event;

import java.time.Duration;
import java.util.List;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.stereotype.Component;

import com.hapbang.auxiliary.notification.app.NotificationUserBackfillService;
import com.hapbang.auxiliary.notification.app.exception.NotificationException;

import lombok.extern.slf4j.Slf4j;

/**
 * 출연·지갑 알림 소비자를 첫 회원 복제본 Backfill 뒤에 시작한다(ND-078). 기존 회원의 알림이 복제본이 없어 DLT로 빠지지 않게 한다.
 * <ul>
 *     <li>앱이 뜬 뒤({@link ApplicationReadyEvent}) 별도 스레드에서 돈다. 앱 기동, 회원 복제본 리스너(가입 환영·승급 승인 포함,
 *     ND-080), 챗봇·검색, REST·WebSocket은 기다리지 않는다.</li>
 *     <li>회원 API·DB 장애나 다른 실행(다른 pod, 관리자 재실행)이 잠금을 쥔 동안은 완료가 아니므로 백오프로 다시 시도하고, 그동안
 *     소비자는 멈춰 있다. 이벤트는 토픽에 남는다(72시간).</li>
 *     <li>{@value #ON_STARTUP}이 false면 Backfill 없이 바로 시작한다(모듈 테스트).</li>
 * </ul>
 */
@Slf4j
@Component
public class NotificationAlertListenerStarter implements DisposableBean {

    public static final String ON_STARTUP = "hapbang.notification.user-backfill.on-startup";
    /** Backfill 뒤에 시작하는 컨테이너 */
    static final List<String> ALERT_LISTENERS = List.of(NotificationAppearanceEventListener.LISTENER_ID,
            NotificationBillingEventListener.LISTENER_ID);

    private final NotificationUserBackfillService backfill;
    private final KafkaListenerEndpointRegistry registry;
    private final boolean onStartup;
    private final Duration retryInitialInterval;
    private final Duration retryMaxInterval;
    private volatile Thread worker;
    private volatile boolean closing;

    public NotificationAlertListenerStarter(NotificationUserBackfillService backfill,
            KafkaListenerEndpointRegistry registry,
            @Value("${" + ON_STARTUP + ":true}") boolean onStartup,
            @Value("${hapbang.notification.user-backfill.retry-initial-interval:5s}") Duration retryInitialInterval,
            @Value("${hapbang.notification.user-backfill.retry-max-interval:5m}") Duration retryMaxInterval) {
        this.backfill = backfill;
        this.registry = registry;
        this.onStartup = onStartup;
        this.retryInitialInterval = retryInitialInterval;
        this.retryMaxInterval = retryMaxInterval;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (!onStartup) {
            log.info("기동 Backfill이 꺼져 있어 알림 소비자를 바로 시작한다 property={}", ON_STARTUP);
            startAlertListeners();
            return;
        }
        worker = Thread.ofVirtual().name("notification-user-backfill").start(this::backfillThenStart);
    }

    private void backfillThenStart() {
        Duration wait = retryInitialInterval;
        while (!closing && !Thread.currentThread().isInterrupted()) {
            try {
                backfill.run();
                startAlertListeners();
                return;
            } catch (NotificationException e) {
                // 다른 pod·관리자 재실행이 잠금을 쥔 정상 경쟁이다. 끝나면 이 pod도 다시 돌려 완료를 확인한다
                log.info("다른 실행이 Backfill 중이라 알림 소비자를 아직 시작하지 않았다 code={}, retryIn={}",
                        e.getErrorCode(), wait);
            } catch (RuntimeException e) {
                log.warn("기동 Backfill을 마치지 못해 알림 소비자를 아직 시작하지 않았다 retryIn={}", wait, e);
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
