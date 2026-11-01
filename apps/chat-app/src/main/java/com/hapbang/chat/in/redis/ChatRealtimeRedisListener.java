package com.hapbang.chat.in.redis;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.SubscriptionListener;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import com.hapbang.chat.app.ChatRealtimeFacade;
import com.hapbang.chat.app.dto.ControlFanout;
import com.hapbang.chat.app.dto.MessageFanout;
import com.hapbang.chat.app.dto.RoomActivityFanout;
import com.hapbang.chat.global.config.ConditionalOnChatRealtime;
import com.hapbang.chat.out.redis.ChatRealtimeRedisConfig;
import com.hapbang.chat.out.redis.ChatRedisKeys;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Redis 실시간 채널의 진입 어댑터. 방 채널·알림 재료(서버 채널)는 방별 처리 실행기, 제어 채널과 구독 복구 처리는 제어 실행기에서
 * {@link ChatRealtimeFacade}를 부른다(설계 4.6: 제어·복구가 메시지 폭주에 굶지 않도록 실행 예산을 나눈다). 방별 처리 실행기는
 * 방 ID로 고르므로 같은 방의 메시지는 한 스레드가 차례로 처리한다(설계 Q22, 프레임 순서 보장은 아니다).
 * <p>
 * Redis 구독 스레드에서 불리므로 여기서는 막지 않는다. 실행기의 대기열이 차면 그 작업을 버리고
 * {@value #DROPPED_METRIC}(queue 태그)로 센다. Pub/Sub은 원래 다시 보내지 않으므로 아래처럼 회복한다.
 * <ul>
 *     <li>버린 방 메시지: 제어 실행기에서 그 방의 로컬 구독 회원에게만 {@code RESYNC/DELIVERY_DROPPED}를 보낸다
 *     (resync-frame.md). 제어 실행기마저 차 있으면 FE 주기 대조가 회복한다.</li>
 *     <li>버린 알림 재료: 알림은 최선 노력이라 FE 주기 대조가 회복한다.</li>
 *     <li>버린 제어: 주기 재검사가 회복한다.</li>
 * </ul>
 * <p>
 * <b>재구독 감지</b>: 서버 채널마다 첫 구독 확인은 시작 구독이라 무시한다. 같은 서버 채널의 구독 확인이 다시 오면 연결이 끊겼다가
 * 다시 구독된 것이다(Lettuce 재연결의 재구독, 컨테이너 재시작). 알림 재료 채널이면 모든 로컬 연결에 재동기화 신호를, 제어 채널이면
 * 로컬 연결 회원 전체 재검사를 한 번 실행한다. 방 채널은 같은 구독 연결로 함께 다시 구독되며, 해제 뒤 다시 더하면 확인이 또
 * 오므로 재구독 판단에 쓰지 않는다.
 */
@Slf4j
@Component(ChatRealtimeRedisConfig.LISTENER_BEAN_NAME)
@ConditionalOnChatRealtime
public class ChatRealtimeRedisListener implements MessageListener, SubscriptionListener, DisposableBean {

    /** 대기열이 넘쳐 버린 작업 수. 태그 queue=message|control */
    public static final String DROPPED_METRIC = "chat.realtime.listener.dropped";

    private final ChatRealtimeFacade chatRealtimeFacade;
    private final JsonMapper jsonMapper;
    private final ChatRedisKeys chatRedisKeys;
    private final String activityChannel;
    private final String controlChannel;
    /** 방별 처리 실행기. 방 ID로 하나를 골라 같은 방의 메시지는 한 스레드가 차례로 처리한다(설계 Q22, 순서 보장은 아니다) */
    private final ThreadPoolTaskExecutor[] messageExecutors;
    private final ThreadPoolTaskExecutor controlExecutor;
    private final Counter messageDropped;
    private final Counter controlDropped;
    /** 한 번이라도 구독 확인을 받은 서버 채널. 방 채널은 해제 뒤 다시 더하면 확인이 또 오므로 재구독 판단에 쓰지 않는다 */
    private final Set<String> subscribedChannels = ConcurrentHashMap.newKeySet();
    /**
     * 받기 여부 확인과 처리 중 작업 수 올리기를 한 번에 하는 잠금. {@link #pause()}도 이 잠금으로 막으므로, pause가 돌아온 뒤에는
     * 새 작업이 등록되지 않는다(확인 직후 선점된 콜백이 비우기가 끝난 뒤 작업을 넣는 경쟁을 막는다)
     */
    private final Object intakeGate = new Object();
    /** 새 payload를 받아 처리할지. {@link #pause()} 중이면 받은 payload를 버린다. {@link #intakeGate} 안에서만 쓴다 */
    private boolean accepting = true;
    /**
     * 받기 세대. {@link #pause()}·{@link #resume()}마다 올린다. 콜백은 들어올 때 세대를 잡아 두고, 작업을 등록할 때 세대가 바뀌었으면
     * 버린다. 그래서 멈추기 전에 들어와 선점됐던 콜백이 멈춤·비우기·재개가 모두 끝난 뒤 옛 payload를 처리하지 않는다
     */
    private volatile long intakeGeneration;
    /** 실행기에 넣었지만 아직 끝나지 않은 작업 수(대기 + 실행 중) */
    private final AtomicInteger inFlight = new AtomicInteger();
    private final Duration shutdownAwait;

    /**
     * 방별 처리 실행기 수(2, 각각 스레드 하나)·실행기별 대기열 상한(1000)과 제어 실행기의 스레드 수(1)·대기열 상한(1000), 종료 대기
     * 상한(5s)은 미확정 초기값(Q12)이다. 기본값은 이 코드가 원본이고 application.yaml에 두지 않는다. 같은 키의 yaml·환경 변수가
     * 덮는다.
     */
    public ChatRealtimeRedisListener(ChatRealtimeFacade chatRealtimeFacade, JsonMapper jsonMapper,
            ChatRedisKeys chatRedisKeys,
            @Value("${hapbang.chat.realtime.listener.message-workers:2}") int messageWorkers,
            @Value("${hapbang.chat.realtime.listener.message-queue-capacity:1000}") int messageQueueCapacity,
            @Value("${hapbang.chat.realtime.listener.control-workers:1}") int controlWorkers,
            @Value("${hapbang.chat.realtime.listener.control-queue-capacity:1000}") int controlQueueCapacity,
            @Value("${hapbang.chat.realtime.listener.shutdown-await:5s}") Duration shutdownAwait,
            MeterRegistry meterRegistry) {
        this.chatRealtimeFacade = chatRealtimeFacade;
        this.jsonMapper = jsonMapper;
        this.chatRedisKeys = chatRedisKeys;
        this.activityChannel = chatRedisKeys.activityChannel();
        this.controlChannel = chatRedisKeys.controlChannel();
        this.messageExecutors = new ThreadPoolTaskExecutor[messageWorkers];
        for (int i = 0; i < messageWorkers; i++) {
            messageExecutors[i] = boundedExecutor("chat-realtime-message-" + i + "-", 1, messageQueueCapacity);
        }
        this.controlExecutor = boundedExecutor("chat-realtime-control-", controlWorkers, controlQueueCapacity);
        this.messageDropped = meterRegistry.counter(DROPPED_METRIC, "queue", "message");
        this.controlDropped = meterRegistry.counter(DROPPED_METRIC, "queue", "control");
        this.shutdownAwait = shutdownAwait;
    }

    @Override
    public void onMessage(Message message, byte @Nullable [] pattern) {
        long generation = intakeGeneration;
        afterIntakeEntry();
        String channel = new String(message.getChannel(), StandardCharsets.UTF_8);
        byte[] body = message.getBody();
        Long roomId = chatRedisKeys.roomIdOf(channel);
        if (roomId != null) {
            Submitted submitted = submit(generation, messageExecutorOf(roomId), channel, () -> {
                MessageFanout fanout = read(body, MessageFanout.class, channel);
                if (fanout != null) {
                    chatRealtimeFacade.deliverMessage(fanout);
                }
            });
            if (submitted == Submitted.REJECTED) {
                messageDropped.increment();
                submitControl(generation, channel, () -> chatRealtimeFacade.onMessageDropped(roomId));
            }
        } else if (activityChannel.equals(channel)) {
            RoomActivityFanout activity = read(body, RoomActivityFanout.class, channel);
            if (activity == null || activity.roomId() == null) {
                return;
            }
            // 알림은 최선 노력이다. 대기열이 넘쳐 버리면 FE 주기 대조가 메운다
            if (submit(generation, messageExecutorOf(activity.roomId()), channel,
                    () -> chatRealtimeFacade.onRoomActivity(activity)) == Submitted.REJECTED) {
                messageDropped.increment();
            }
        } else if (controlChannel.equals(channel)) {
            submitControl(generation, channel, () -> {
                ControlFanout control = read(body, ControlFanout.class, channel);
                if (control != null) {
                    chatRealtimeFacade.applyControl(control);
                }
            });
        }
    }

    @Override
    public void onChannelSubscribed(byte[] channel, long count) {
        long generation = intakeGeneration;
        String name = new String(channel, StandardCharsets.UTF_8);
        if (!activityChannel.equals(name) && !controlChannel.equals(name)) {
            log.debug("방 채널 구독 확인 channel={}", name);
            return;
        }
        if (subscribedChannels.add(name)) {
            log.info("실시간 채널 구독 시작 channel={}", name);
            return;
        }
        // 재동기화·재검사도 처리 작업과 같은 받기 잠금을 지난다. 멈춘 중이면 예약하지 않는다.
        // 방 채널은 서버 채널과 같은 구독 연결이라 서버 채널의 재구독이 곧 방 채널의 재구독이다
        log.warn("실시간 채널 재구독 감지 channel={}", name);
        if (activityChannel.equals(name)) {
            submitControl(generation, name, chatRealtimeFacade::onMessageSubscriptionRecovered);
        } else {
            submitControl(generation, name, chatRealtimeFacade::onControlSubscriptionRecovered);
        }
    }

    @Override
    public void onChannelUnsubscribed(byte[] channel, long count) {
        log.info("실시간 채널 구독 해제 channel={}", new String(channel, StandardCharsets.UTF_8));
    }

    /**
     * 새 payload 처리를 멈춘다. 멈춘 동안 받은 전달·제어 payload는 버린다(Pub/Sub은 원래 다시 보내지 않는다. 메시지는 FE 이력 대조,
     * 제어는 주기 재검사가 회복한다). 앱 종료·점검 때 {@link #awaitIdle}와 함께 처리 중인 작업을 비우는 데 쓴다.
     */
    public void pause() {
        synchronized (intakeGate) {
            accepting = false;
            intakeGeneration++;
        }
    }

    /** {@link #pause()} 뒤 새 payload 처리를 다시 시작한다 */
    public void resume() {
        synchronized (intakeGate) {
            // 멈춘 동안 들어와 아직 등록하지 않은 콜백도 재개 뒤에 처리하지 않는다
            intakeGeneration++;
            accepting = true;
        }
    }

    /**
     * 실행기에 들어간 작업(대기·실행 중)이 모두 끝날 때까지 기다린다. 먼저 {@link #pause()}하지 않으면 새 작업이 계속 들어올 수 있다.
     *
     * @return 제한 시간 안에 비었으면 true
     */
    public boolean awaitIdle(Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (inFlight.get() > 0) {
            if (System.nanoTime() >= deadline) {
                log.warn("실시간 처리 작업이 제한 시간 안에 끝나지 않음 inFlight={}", inFlight.get());
                return false;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    /** 종료할 때 새 payload를 받지 않고, 처리 중인 작업을 {@code shutdown-await}까지 기다린 뒤 실행기를 닫는다 */
    @Override
    public void destroy() {
        pause();
        awaitIdle(shutdownAwait);
        for (ThreadPoolTaskExecutor executor : messageExecutors) {
            executor.shutdown();
        }
        controlExecutor.shutdown();
    }

    private <T> @Nullable T read(byte[] body, Class<T> type, String channel) {
        try {
            return jsonMapper.readValue(body, type);
        } catch (JacksonException exception) {
            log.error("실시간 채널 payload를 읽지 못해 버림 channel={}, type={}", channel, type.getSimpleName(), exception);
            return null;
        }
    }

    private ThreadPoolTaskExecutor messageExecutorOf(long roomId) {
        return messageExecutors[Math.floorMod(Long.hashCode(roomId), messageExecutors.length)];
    }

    private void submitControl(long generation, String channel, Runnable task) {
        if (submit(generation, controlExecutor, channel, task) == Submitted.REJECTED) {
            controlDropped.increment();
        }
    }

    /** 작업 등록 결과 */
    private enum Submitted {
        /** 실행기가 받았다 */
        ACCEPTED,
        /** 멈춘 중이라 받지 않았다 */
        PAUSED,
        /** 실행기 대기열이 가득 차 버렸다 */
        REJECTED
    }

    /**
     * 받기 여부와 세대를 확인하고 처리 중 작업 수를 올린다. 셋을 한 잠금 안에서 해 {@link #pause()}와 겹치지 않게 한다.
     *
     * @param generation 콜백이 들어올 때 잡은 받기 세대
     */
    private boolean tryEnter(long generation) {
        synchronized (intakeGate) {
            if (!accepting || generation != intakeGeneration) {
                return false;
            }
            afterAcceptCheck();
            inFlight.incrementAndGet();
            return true;
        }
    }

    /** 받기 확인을 통과한 직후(아직 처리 중 작업 수를 올리기 전). 테스트가 이 순간의 선점을 재현할 때만 덮어쓴다 */
    void afterAcceptCheck() {
    }

    /** 콜백이 받기 세대를 잡은 직후(아직 받기 확인 전). 테스트가 이 순간의 선점을 재현할 때만 덮어쓴다 */
    void afterIntakeEntry() {
    }

    private Submitted submit(long generation, ThreadPoolTaskExecutor executor, String channel, Runnable task) {
        if (!tryEnter(generation)) {
            log.debug("실시간 처리를 멈춘 동안 받은 작업을 버림 channel={}", channel);
            return Submitted.PAUSED;
        }
        try {
            executor.execute(() -> {
                try {
                    task.run();
                } catch (RuntimeException exception) {
                    log.error("실시간 채널 처리 실패 channel={}", channel, exception);
                } finally {
                    inFlight.decrementAndGet();
                }
            });
            return Submitted.ACCEPTED;
        } catch (TaskRejectedException exception) {
            inFlight.decrementAndGet();
            log.error("실시간 처리 대기열이 가득 차 버림 channel={}, executor={}", channel,
                    executor.getThreadNamePrefix());
            return Submitted.REJECTED;
        }
    }

    private static ThreadPoolTaskExecutor boundedExecutor(String threadNamePrefix, int workers, int queueCapacity) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix(threadNamePrefix);
        executor.setCorePoolSize(workers);
        executor.setMaxPoolSize(workers);
        executor.setQueueCapacity(queueCapacity);
        executor.initialize();
        return executor;
    }
}
