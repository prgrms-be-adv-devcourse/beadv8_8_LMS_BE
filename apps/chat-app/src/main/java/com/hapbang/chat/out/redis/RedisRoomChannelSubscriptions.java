package com.hapbang.chat.out.redis;

import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

import com.hapbang.chat.app.RoomChannelSubscriptions;
import com.hapbang.chat.global.config.ConditionalOnChatRealtime;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;

/**
 * 방 채널({@link ChatRedisKeys#roomChannel(long)})의 동적 구독(설계 4.2 "Redis 채널 구조", Q29·Q30).
 * <ul>
 *     <li>구독 진입 어댑터의 {@link RedisMessageListenerContainer}(구독 연결 하나)에 방 채널을 더하고 뺀다. 구독은 비동기다.
 *     구독이 확인되기 전에 발행된 메시지는 받지 못한다(구독 순간의 공백은 허용, 설계 Q23).</li>
 *     <li>방마다 참조 수를 센다. 0이 되면 바로 빼지 않고 유예 뒤 뺀다. 유예 중 다시 참조하면 그대로 쓴다.</li>
 *     <li>앱이 구독하는 방 채널 수에 상한을 둔다. 넘으면 먼저 유예 중인 채널을 정리하고, 그래도 넘으면 더하지 않는다. 놓았지만 아직
 *     컨테이너에서 빼지 못한 방(해제 대기)도 같은 값의 상한으로 따로 센다. 그래서 Redis가 느려도 대기 작업과 실제 구독 수가 묶인다.</li>
 *     <li>참조 수는 한 잠금 안에서 Redis 명령 없이 센다. 컨테이너의 추가·제거는 스레드 하나인 실행기가 방마다 그때의 참조 상태로
 *     맞춘다. 컨테이너의 추가는 구독 확인까지 기다리므로, Redis가 느려도 잠금(다른 방의 구독·회수·연결 종료)은 기다리지 않는다.</li>
 *     <li>컨테이너 명령은 따로 띄운 가상 스레드에서 하고 제한 시간까지만 기다린다. 멈춘 컨테이너에 대한 추가는 오지 않는 구독 확인을
 *     끝없이 기다리므로(인터럽트로도 풀리지 않는다) 실행기 스레드가 거기 묶이지 않게 한다.</li>
 *     <li><b>실패하면 재설정한다.</b> 방 채널 명령이 실패하거나 제한 시간을 넘기면 그 방만 고치지 않는다. 실패한 명령 뒤의
 *     컨테이너·드라이버 상태는 라이브러리 내부에 따라 달라지기 때문이다. 컨테이너를 멈추고(구독 연결을 닫는다), 멈춘 동안 이 리스너의
 *     채널 등록을 서버 채널 + 지금 원하는 방 채널로 다시 쓴 뒤(멈춘 컨테이너의 추가·제거는 등록만 바꾼다), 다시 시작한다(새 연결이
 *     등록된 채널을 모두 구독한다). 제한 시간 뒤 늦게 끝난 명령의 결과는 버린다. 재설정은 서버 채널도 다시 구독하므로 구독 진입 어댑터가
 *     재구독을 감지해 로컬 연결에 {@code RESYNC(REALTIME_RESUBSCRIBED)}를 보낸다. 재설정이 실패하면 잠시 뒤 다시 한다.</li>
 *     <li>컨테이너보다 먼저 멈춘다(컨테이너를 주입받은 빈이라 생명주기 정지가 이 빈을 먼저 부른다). 멈추면 새 구독을 받지 않고
 *     실행기를 끝내며, 제한 시간 안에 그 스레드가 끝나는지 확인한다.</li>
 * </ul>
 */
@Slf4j
@Component
@ConditionalOnChatRealtime
public class RedisRoomChannelSubscriptions implements RoomChannelSubscriptions, SmartLifecycle, DisposableBean {

    /** 구독 중인 방 채널 수 게이지 */
    public static final String SUBSCRIBED_METRIC = "chat.realtime.room_channels";
    /** 재설정이 실패했을 때 다시 하기까지의 간격 */
    private static final long RETRY_DELAY_MILLIS = TimeUnit.SECONDS.toMillis(1);
    /** 컨테이너 명령 하나를 기다리는 시간. 넘기면 실패로 보고 재설정한다 */
    private static final long CALL_TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(5);
    /** 멈출 때 실행기 스레드가 끝나기를 기다리는 시간 */
    private static final long STOP_TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(5);

    private final RedisMessageListenerContainer container;
    private final MessageListener listener;
    private final ChatRedisKeys chatRedisKeys;
    private final long graceNanos;
    private final int maxChannels;
    /** 방 ID → 참조 상태. 키가 있으면 구독해야 하는 방 채널이다. 이 객체의 잠금으로 지킨다 */
    private final Map<Long, Channel> channels = new HashMap<>();
    /** 맞추기가 실행기에 걸려 있는 방. 한 방에 하나만 건다. 이 객체의 잠금으로 지킨다 */
    private final Set<Long> pending = new HashSet<>();
    /** 놓았지만 컨테이너에서 아직 빼지 못한 방(해제 대기). 이 객체의 잠금으로 지킨다 */
    private final Set<Long> draining = new HashSet<>();
    /** 컨테이너에 등록한 방 채널. 실행기 스레드만 읽고 쓴다 */
    private final Set<Long> registered = new HashSet<>();
    /** 맞추기와 주기 정리·재설정을 하는 스레드 하나 */
    private final ScheduledExecutorService executor;
    /** 컨테이너 명령을 하나씩 띄우는 가상 스레드 */
    private final ExecutorService calls;
    /** 새 구독과 맞추기를 받는 중인지. 만들 때부터 받는다. 이 객체의 잠금으로 지킨다 */
    private boolean running = true;
    /** 재설정이 실행기에 걸려 있는지. 걸려 있으면 방별 맞추기를 건너뛴다(재설정이 원하는 상태로 다시 쓴다). 이 객체의 잠금으로 지킨다 */
    private boolean resetPending;

    /**
     * 해제 유예(60s)·앱 방 채널 상한(10000)은 미확정 초기값(Q12)이다. 기본값은 코드가 원본이고 같은 키의 yaml·환경 변수가 덮는다.
     */
    public RedisRoomChannelSubscriptions(
            @Qualifier(ChatRealtimeRedisConfig.CONTAINER_BEAN_NAME) RedisMessageListenerContainer container,
            @Qualifier(ChatRealtimeRedisConfig.LISTENER_BEAN_NAME) MessageListener listener,
            ChatRedisKeys chatRedisKeys, MeterRegistry meterRegistry,
            @Value("${hapbang.chat.realtime.room.channel-grace:60s}") Duration grace,
            @Value("${hapbang.chat.realtime.room.max-channels:10000}") int maxChannels) {
        Assert.isTrue(!grace.isNegative(), "hapbang.chat.realtime.room.channel-grace는 0 이상이어야 합니다.");
        Assert.isTrue(maxChannels > 0, "hapbang.chat.realtime.room.max-channels는 1 이상이어야 합니다.");
        this.container = container;
        this.listener = listener;
        this.chatRedisKeys = chatRedisKeys;
        this.graceNanos = grace.toNanos();
        this.maxChannels = maxChannels;
        Gauge.builder(SUBSCRIBED_METRIC, this, RedisRoomChannelSubscriptions::subscribedChannels)
                .register(meterRegistry);
        this.executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "chat-room-channel-io");
            thread.setDaemon(true);
            return thread;
        });
        this.calls = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("chat-room-channel-call-", 0).factory());
        long period = Math.max(TimeUnit.SECONDS.toMillis(1), grace.toMillis() / 4);
        executor.scheduleWithFixedDelay(this::reapIdle, period, period, TimeUnit.MILLISECONDS);
    }

    @Override
    public synchronized boolean acquire(long roomId) {
        if (!running) {
            log.debug("멈추는 중이라 방 채널을 구독하지 않음 roomId={}", roomId);
            return false;
        }
        Channel channel = channels.get(roomId);
        if (channel == null) {
            if (channels.size() >= maxChannels) {
                reap(System.nanoTime(), true);
            }
            if (channels.size() >= maxChannels || (!draining.contains(roomId) && draining.size() >= maxChannels)) {
                log.warn("방 채널 상한에 닿아 구독하지 않음 roomId={}, channels={}, draining={}", roomId, channels.size(),
                        draining.size());
                return false;
            }
            channel = new Channel();
            channels.put(roomId, channel);
            draining.remove(roomId);
            requestSync(roomId);
        }
        channel.refs++;
        return true;
    }

    @Override
    public synchronized void release(long roomId) {
        Channel channel = channels.get(roomId);
        if (channel == null || channel.refs == 0) {
            return;
        }
        channel.refs--;
        if (channel.refs == 0) {
            channel.idleSince = System.nanoTime();
            if (graceNanos == 0) {
                channels.remove(roomId);
                drop(roomId);
            }
        }
    }

    /** 구독 중인 방 채널 수(유예 중 포함) */
    public synchronized int subscribedChannels() {
        return channels.size();
    }

    /** 유예가 지난 방 채널을 뺀다. 주기 작업이므로 실패해도 예외를 밖으로 내지 않는다(내면 다음 주기가 멈춘다) */
    void reapIdle() {
        try {
            synchronized (this) {
                reap(System.nanoTime(), false);
            }
        } catch (RuntimeException exception) {
            log.warn("방 채널 주기 정리 실패, 다음 주기에 다시 함", exception);
        }
    }

    private void reap(long now, boolean ignoreGrace) {
        Iterator<Map.Entry<Long, Channel>> iterator = channels.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Long, Channel> entry = iterator.next();
            Channel channel = entry.getValue();
            if (channel.refs == 0 && (ignoreGrace || now - channel.idleSince >= graceNanos)) {
                iterator.remove();
                drop(entry.getKey());
            }
        }
    }

    /** 참조 목록에서 뺀 방을 해제 대기로 두고 맞추기를 건다. 잠금 안에서 부른다 */
    private void drop(long roomId) {
        draining.add(roomId);
        requestSync(roomId);
    }

    /** 그 방의 맞추기를 실행기에 건다. 이미 걸려 있으면 그 작업이 실행 때의 상태로 맞춘다. 잠금 안에서 부른다 */
    private void requestSync(long roomId) {
        if (!running || !pending.add(roomId)) {
            return;
        }
        executor.execute(() -> sync(roomId));
    }

    /** 재설정을 실행기에 건다. 이미 걸려 있으면 그것으로 충분하다. 잠금 안에서 부른다 */
    private void requestReset(long delayMillis) {
        if (!running || resetPending) {
            return;
        }
        resetPending = true;
        executor.schedule(this::reset, delayMillis, TimeUnit.MILLISECONDS);
    }

    /** 실행기 스레드에서 그 방 채널의 컨테이너 등록을 지금 참조 상태에 맞춘다. 실패하면 재설정을 건다 */
    private void sync(long roomId) {
        boolean wanted;
        synchronized (this) {
            pending.remove(roomId);
            if (!running || resetPending) {
                return;
            }
            wanted = channels.containsKey(roomId);
        }
        if (wanted != registered.contains(roomId)) {
            ChannelTopic topic = topic(roomId);
            try {
                if (wanted) {
                    call(() -> container.addMessageListener(listener, topic));
                    registered.add(roomId);
                    log.debug("방 채널 구독 roomId={}", roomId);
                } else {
                    call(() -> container.removeMessageListener(listener, topic));
                    registered.remove(roomId);
                    log.debug("방 채널 구독 해제 roomId={}", roomId);
                }
            } catch (RuntimeException exception) {
                log.warn("방 채널 구독 {} 실패, 구독을 재설정함 roomId={}", wanted ? "추가" : "해제", roomId, exception);
                synchronized (this) {
                    requestReset(0);
                }
                return;
            }
        }
        synchronized (this) {
            settleDraining();
        }
    }

    /**
     * 컨테이너를 멈추고, 이 리스너의 채널 등록을 서버 채널 + 지금 원하는 방 채널로 다시 쓴 뒤 다시 시작한다. 멈추면 구독 연결을 닫고,
     * 멈춘 동안의 추가·제거는 등록만 바꾸며, 시작하면 새 연결이 등록된 채널을 모두 구독한다. 그래서 Redis의 실제 구독과 드라이버가
     * 기억하는 채널이 원하는 상태에서 새로 만들어진다.
     */
    private void reset() {
        synchronized (this) {
            resetPending = false;
            if (!running) {
                return;
            }
        }
        try {
            container.stop();
            List<ChannelTopic> wanted;
            synchronized (this) {
                registered.clear();
                registered.addAll(channels.keySet());
                wanted = registered.stream().map(this::topic).toList();
            }
            container.removeMessageListener(listener);
            container.addMessageListener(listener, ChatRealtimeRedisConfig.serverTopics(chatRedisKeys));
            if (!wanted.isEmpty()) {
                container.addMessageListener(listener, wanted);
            }
            synchronized (this) {
                settleDraining();
                if (!running) {
                    // 앱이 멈추는 중이다. 컨테이너는 생명주기가 정리한다
                    return;
                }
            }
            container.start();
            log.info("방 채널 구독 재설정 완료 roomChannels={}", wanted.size());
        } catch (RuntimeException exception) {
            log.warn("방 채널 구독 재설정 실패, 다시 함", exception);
            synchronized (this) {
                requestReset(RETRY_DELAY_MILLIS);
            }
        }
    }

    /** 원하지 않고, 컨테이너에 등록되지 않았고, 맞추기도 걸려 있지 않은 방은 해제 대기에서 뺀다. 잠금 안에서 부른다 */
    private void settleDraining() {
        draining.removeIf(roomId -> !channels.containsKey(roomId) && !registered.contains(roomId)
                && !pending.contains(roomId));
    }

    /** 컨테이너 명령을 가상 스레드에서 하고 제한 시간까지만 기다린다. 넘기면 실패로 보고, 늦게 끝나도 그 결과는 버린다 */
    private void call(Runnable command) {
        Future<?> future = calls.submit(command);
        try {
            future.get(CALL_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            throw new IllegalStateException("방 채널 명령 실패", exception.getCause());
        } catch (TimeoutException exception) {
            throw new IllegalStateException("방 채널 명령이 제한 시간 안에 끝나지 않음", exception);
        } catch (InterruptedException exception) {
            // 멈추는 중이다. 명령을 더 기다리지 않고 이 스레드를 끝낸다
            Thread.currentThread().interrupt();
            throw new IllegalStateException("멈추는 중이라 방 채널 명령을 기다리지 않음", exception);
        }
    }

    private ChannelTopic topic(long roomId) {
        return new ChannelTopic(chatRedisKeys.roomChannel(roomId));
    }

    /** 만들 때부터 받으므로 시작할 일은 없다 */
    @Override
    public void start() {
    }

    /**
     * 새 구독과 맞추기·재설정을 그만 받고 실행기를 끝낸다. 컨테이너가 멈추기 전에 불린다. 끝나지 않은 컨테이너 명령은 기다리지
     * 않는다(컨테이너가 멈추며 구독 연결을 닫는다).
     */
    @Override
    public void stop() {
        synchronized (this) {
            if (!running) {
                return;
            }
            running = false;
            pending.clear();
        }
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(STOP_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                log.warn("방 채널 실행기가 {}ms 안에 끝나지 않음", STOP_TIMEOUT_MILLIS);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
        calls.shutdownNow();
    }

    @Override
    public synchronized boolean isRunning() {
        return running;
    }

    @Override
    public void destroy() {
        stop();
    }

    private static final class Channel {
        private int refs;
        private long idleSince;
    }
}
