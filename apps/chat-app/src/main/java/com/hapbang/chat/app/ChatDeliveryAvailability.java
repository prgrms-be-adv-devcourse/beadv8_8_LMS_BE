package com.hapbang.chat.app;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.function.IntToLongFunction;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import com.hapbang.chat.global.config.ConditionalOnChatDeliveryPipeline;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;

/**
 * 전달 파이프라인의 인프라 가용성 판단 (2026-10-30 사용자 결정). 인프라 장애로 작업을 격리(DEAD)하지 않도록, 장애 동안 작업을
 * 멈추고 정해진 간격으로 재확인한다. 이 동안의 실패는 작업의 시도 횟수에 넣지 않는다.
 * <ul>
 *     <li><b>Redis 연결</b>(relay·새 작업 읽기·회수·재구성·정리 공통): {@link ChatDeliveryUnavailableException}을 받으면 멈춘다.
 *     재확인 시각이 되면 한 스레드만 {@link ChatDeliveryQueue#ping()}으로 확인하고, 성공하면 모두 이어 간다.</li>
 *     <li><b>큐 쓰기 거절</b>(relay·재적재): {@link ChatDeliveryBackpressureException}(OOM·MISCONF)을 받으면 적재만 멈춘다.
 *     읽기·ACK·발행·정리는 이어 가서 적체를 비운다. OOM에서도 PING은 성공하므로, 재확인은 실제 쓰기 경로로 한다: 재확인 시각마다
 *     relay·재적재 한 건의 XADD를 허락하고({@link #tryAcquireWrite}), 성공하면 적재를 이어 간다. {@code INFO memory}의 사용량
 *     비교는 정책·단편화·상한 변경 시점에 따라 실제 쓰기 가능 여부와 어긋날 수 있어 쓰지 않는다.</li>
 *     <li><b>자기 구독</b>(큐별): replica 1개에서 발행 구독자 0명은 자기 구독 단절이다. 그 큐의 새 작업 읽기를 멈추고, 재확인 시각마다
 *     미확인 작업 한 건만 시험 발행한다(회수 경로). 구독자가 1명 이상이면 이어 간다. 구독 단절을 알리는 별도 계약 없이 발행 결과를
 *     신호로 쓴다(구독자는 실시간 전파 구현이 소유한다). 재확인 시각이 지났는데 시험 발행할 미확인 작업이 없거나 시험 발행이 구독자 수 없이 끝났으면
 *     ({@link #reopenIfNothingToProbe}) 큐를 잠정적으로 다시 연다. 그러면 다음 발행이 재확인이 되고, 또 0명이면 늘어난 간격으로
 *     다시 멈춘다(멈춘 큐가 재시작 전까지 조용히 멈춰 있지 않게 한다).</li>
 * </ul>
 * 장애의 첫 보고는 {@code pause.probe-initial} 뒤 재확인을 잡는다. 같은 장애를 여러 호출이 보고해도 간격을 늘리지 않고, 재확인이
 * 실패할 때마다 두 배로 늘려 {@code pause.probe-max}에서 멈춘다. 잠정적으로 다시 연 큐가 다시 0명을 받으면 재확인 실패로 센다.
 * <p>
 * 재확인 권한은 허락 결과({@link WritePermit}·{@link PublishPermit})의 토큰으로만 결과를 알리고 돌려준다. 재확인 권한이 없는
 * 호출(게이트가 열려 있을 때 시작해 늦게 끝난 호출 등)은 규칙대로 게이트를 열거나 닫을 수는 있지만 다른 호출의 재확인 권한을
 * 지우지 않는다. 그래서 재확인은 장애마다 한 번에 하나만 진행한다. 장애가 끝나면(게이트가 열리면) 그 장애의 재확인 권한은 무효다.
 * <p>
 * Redis 연결 재확인은 PING이 아니라 TTL이 있는 작은 쓰기({@link ChatDeliveryQueue#ping()})로 한다. READONLY(복제본·failover)·
 * LOADING·MASTERDOWN에서도 PING은 성공하지만 XADD·XREADGROUP은 계속 실패하므로, PING으로 열면 1초마다 열고 닫기를 되풀이한다.
 * 쓰기가 메모리 상한으로만 거절되면(OOM) 연결은 정상이므로 열고, 적재는 쓰기 게이트가 따로 멈춘다.
 * 지금 멈춤 상태는 {@code chat.delivery.paused.state{target}} 게이지(1이면 멈춤)로, 멈춘 횟수는
 * {@code chat.delivery.paused{target}} 카운터로 본다({@code target}: {@code redis}, {@code redis-write},
 * {@code subscription-DELIVERY}, {@code subscription-CONTROL}).
 */
@Slf4j
@Component
@ConditionalOnChatDeliveryPipeline
public class ChatDeliveryAvailability {

    private final ChatDeliveryQueue chatDeliveryQueue;
    private final ChatDeliveryProperties.Pause settings;
    private final MeterRegistry meterRegistry;
    private final IntToLongFunction delay = this::delayNanos;
    private final Probe redis = new Probe();
    /** 큐 쓰기 거절(OOM·MISCONF) 동안의 적재 멈춤. 읽기·ACK·발행은 이어 간다 */
    private final Probe writes = new Probe();
    private final Map<ChatJobStream, Probe> subscriptions = new EnumMap<>(ChatJobStream.class);
    /** 재확인 권한 토큰. 0은 재확인 권한 없음이다. 잠금 아래에서만 늘린다 */
    private long lastToken;

    public ChatDeliveryAvailability(ChatDeliveryQueue chatDeliveryQueue, ChatDeliveryProperties properties,
            ObjectProvider<MeterRegistry> meterRegistry) {
        this.chatDeliveryQueue = chatDeliveryQueue;
        this.settings = properties.pause();
        this.meterRegistry = meterRegistry.getIfAvailable();
        for (ChatJobStream stream : ChatJobStream.values()) {
            subscriptions.put(stream, new Probe());
        }
        registerGauge("redis", redis);
        registerGauge("redis-write", writes);
        subscriptions.forEach((stream, probe) -> registerGauge("subscription-" + stream.name(), probe));
    }

    // ----------------------------------------------------------------------------------------------------------
    // Redis 연결
    // ----------------------------------------------------------------------------------------------------------

    /**
     * Redis를 써도 되는지. 멈춘 상태면 재확인 시각에 한 스레드만 작은 쓰기로 확인한다.
     */
    public boolean redisAvailable() {
        long token;
        synchronized (this) {
            if (!redis.down) {
                return true;
            }
            if (redis.owner != 0 || System.nanoTime() < redis.nextProbeAt) {
                return false;
            }
            token = acquire(redis);
        }
        boolean reachable = false;
        try {
            chatDeliveryQueue.ping();
            reachable = true;
        } catch (ChatDeliveryBackpressureException e) {
            // 연결돼 명령을 받지만 메모리 상한으로 쓰기만 거절했다. 연결 게이트는 열고, 적재는 쓰기 게이트가 멈춘다
            reachable = true;
        } catch (RuntimeException e) {
            log.debug("Redis 재확인 실패: {}", ChatDeliveryErrors.describe(e));
        } finally {
            synchronized (this) {
                if (redis.owner == token) {
                    redis.owner = 0;
                    if (reachable) {
                        log.warn("Redis 연결이 돌아와 전달 파이프라인을 이어 감 (재확인 실패 {}회)", redis.failures);
                        redis.up();
                    } else {
                        redis.probeFailed(delay);
                    }
                }
            }
        }
        return reachable;
    }

    /** Redis 연결 불가를 알린다. 멈춰 있지 않았으면 멈추고 {@code probe-initial} 뒤 재확인한다. 이미 멈춘 중이면 그대로 둔다 */
    public void redisUnavailable(Throwable cause) {
        synchronized (this) {
            if (redis.down) {
                return;
            }
            log.error("Redis에 연결할 수 없어 전달 파이프라인을 멈춤(작업 시도 횟수에 넣지 않음): {}",
                    ChatDeliveryErrors.describe(cause));
            count("redis");
            redis.open(delay);
        }
    }

    /**
     * 발행 실패가 Redis 연결 때문인지 확인한다. 재확인이 연결 불가로 실패하면 멈추고 false다. 연결돼 있으면 true다.
     */
    public boolean confirmRedisConnected() {
        try {
            chatDeliveryQueue.ping();
            return true;
        } catch (ChatDeliveryUnavailableException e) {
            redisUnavailable(e);
            return false;
        } catch (RuntimeException e) {
            // Redis가 오류로라도 응답했다
            return true;
        }
    }

    // ----------------------------------------------------------------------------------------------------------
    // 큐 쓰기(적재)
    // ----------------------------------------------------------------------------------------------------------

    /** 적재 허락 결과 */
    public enum WriteGate {
        /** 적재해도 된다 */
        OPEN,
        /** 쓰기 거절로 멈춘 중 재확인 차례다. 한 건만 적재해 본다 */
        PROBE,
        /** 멈춘 중이다. 적재하지 않는다 */
        CLOSED
    }

    /**
     * 적재 허락. PROBE면 재확인 권한 토큰을 가진다. 결과는 {@link #writeSucceeded}·{@link #memoryFull(WritePermit, Throwable)}로
     * 알리고, 끝에서 결과와 관계없이 {@link #writeEnded}로 돌려준다.
     */
    public record WritePermit(WriteGate gate, long token) {

        static final WritePermit OPEN = new WritePermit(WriteGate.OPEN, 0);
        static final WritePermit CLOSED = new WritePermit(WriteGate.CLOSED, 0);

        public boolean isProbe() {
            return gate == WriteGate.PROBE;
        }
    }

    /**
     * 적재(XADD)해도 되는지. Redis 연결이 안 되면 CLOSED다. 쓰기 거절로 멈춘 중이면 재확인 시각에 한 호출만 PROBE를 받는다.
     */
    public WritePermit tryAcquireWrite() {
        if (!redisAvailable()) {
            return WritePermit.CLOSED;
        }
        synchronized (this) {
            if (!writes.down) {
                return WritePermit.OPEN;
            }
            if (writes.owner != 0 || System.nanoTime() < writes.nextProbeAt) {
                return WritePermit.CLOSED;
            }
            return new WritePermit(WriteGate.PROBE, acquire(writes));
        }
    }

    /**
     * 큐가 쓰기를 거절했다(OOM·MISCONF). 이 허락이 지금 재확인 권한이면 재확인 실패로 간격을 늘린다. 아니면 적재를 멈춘다
     * (이미 멈춘 중이면 그대로).
     */
    public void memoryFull(WritePermit permit, Throwable cause) {
        synchronized (this) {
            if (owns(writes, permit == null ? 0 : permit.token())) {
                writes.owner = 0;
                writes.probeFailed(delay);
                return;
            }
            if (writes.down) {
                return;
            }
            log.error("Redis가 쓰기를 거절해(메모리 상한·영속화 오류) 적재를 멈춤(작업 시도 횟수에 넣지 않음, 미적재 작업은 outbox에 남김): "
                    + "{}", ChatDeliveryErrors.describe(cause));
            count("redis-write");
            writes.open(delay);
        }
    }

    /** 재확인 권한 없이 쓰기 거절을 알린다(운영 재처리의 즉시 재적재 등) */
    public void memoryFull(Throwable cause) {
        memoryFull(null, cause);
    }

    /** 적재가 성공했다. 쓰기 거절로 멈췄었으면 이어 간다(쓰기가 됐다는 사실이므로 누가 알려도 연다) */
    public void writeSucceeded(WritePermit permit) {
        synchronized (this) {
            if (writes.down) {
                log.warn("Redis 쓰기가 다시 성공해 적재를 이어 감 (재확인 실패 {}회)", writes.failures);
                writes.up();
            } else if (owns(writes, permit.token())) {
                writes.owner = 0;
            }
        }
    }

    /** 이 허락의 재확인 권한을 돌려준다. 다른 호출의 재확인 권한은 건드리지 않는다 */
    public void writeEnded(WritePermit permit) {
        synchronized (this) {
            if (owns(writes, permit.token())) {
                writes.owner = 0;
            }
        }
    }

    // ----------------------------------------------------------------------------------------------------------
    // 자기 구독
    // ----------------------------------------------------------------------------------------------------------

    /** 이 큐의 새 작업을 읽어도 되는지(Redis 연결과 자기 구독이 모두 정상) */
    public boolean mayConsume(ChatJobStream stream) {
        if (!redisAvailable()) {
            return false;
        }
        synchronized (this) {
            return !subscriptions.get(stream).down;
        }
    }

    /**
     * 발행 허락. 재확인 차례면 재확인 권한 토큰을 가진다. 결과는 {@link #published}·{@link #noSubscriber}로 알리고, 끝에서 결과와
     * 관계없이 {@link #publishEnded}로 돌려준다.
     */
    public record PublishPermit(ChatJobStream stream, boolean granted, long token) {

        public boolean isProbe() {
            return token != 0;
        }
    }

    /**
     * 발행해도 되는지. 자기 구독이 끊긴 큐는 재확인 시각마다 한 호출만 시험 발행을 허락한다.
     */
    public PublishPermit tryAcquirePublish(ChatJobStream stream) {
        if (!redisAvailable()) {
            return new PublishPermit(stream, false, 0);
        }
        synchronized (this) {
            Probe subscription = subscriptions.get(stream);
            if (!subscription.down) {
                return new PublishPermit(stream, true, 0);
            }
            if (subscription.owner != 0 || System.nanoTime() < subscription.nextProbeAt) {
                return new PublishPermit(stream, false, 0);
            }
            return new PublishPermit(stream, true, acquire(subscription));
        }
    }

    /** 구독자 1명 이상에게 발행했다. 자기 구독이 끊겨 멈췄던 큐면 이어 간다(구독이 살아 있다는 사실이므로 누가 알려도 연다) */
    public void published(PublishPermit permit) {
        synchronized (this) {
            Probe subscription = subscriptions.get(permit.stream());
            if (subscription.down || subscription.failures > 0 || subscription.tentative) {
                if (subscription.down || subscription.failures > 0) {
                    log.warn("자기 구독이 돌아와 {} 큐를 이어 감 (재확인 실패 {}회)", permit.stream(), subscription.failures);
                } else {
                    log.info("잠정적으로 다시 연 {} 큐의 발행이 구독자에게 닿아 정상으로 확인함", permit.stream());
                }
                // 장애 상태(실패 횟수·잠정 재개 표시)를 모두 지워, 이후의 별도 단절은 새 장애로 시작한다
                subscription.up();
            } else if (owns(subscription, permit.token())) {
                subscription.owner = 0;
            }
        }
    }

    /**
     * 발행 구독자가 0명이었다(자기 구독 단절). 이 허락이 지금 재확인 권한이면 재확인 실패로 간격을 늘린다. 아니면 그 큐의 새 작업
     * 읽기를 멈춘다. 잠정적으로 다시 연 큐의 첫 0명은 재확인 실패로 센다(간격을 이어 늘린다).
     */
    public void noSubscriber(PublishPermit permit) {
        synchronized (this) {
            ChatJobStream stream = permit.stream();
            Probe subscription = subscriptions.get(stream);
            if (owns(subscription, permit.token())) {
                subscription.owner = 0;
                subscription.probeFailed(delay);
                return;
            }
            if (subscription.down) {
                return;
            }
            if (subscription.tentative) {
                log.warn("잠정적으로 다시 연 {} 큐도 구독자 0명이라 다시 멈춤 (재확인 실패 {}회)", stream, subscription.failures + 1);
                subscription.failures++;
            } else {
                log.error("발행 구독자 0명(자기 구독 단절)이라 {} 큐를 멈춤(작업 시도 횟수에 넣지 않음)", stream);
                count("subscription-" + stream.name());
            }
            subscription.open(delay);
        }
    }

    /** 이 허락의 시험 발행 권한을 돌려준다. 결과(구독자 수) 없이 끝났으면 재확인 시각은 그대로 둔다 */
    public void publishEnded(PublishPermit permit) {
        synchronized (this) {
            Probe subscription = subscriptions.get(permit.stream());
            if (owns(subscription, permit.token())) {
                subscription.owner = 0;
            }
        }
    }

    /**
     * 회수 한 바퀴를 마친 뒤 부른다. 구독 단절로 멈춘 큐의 재확인 시각이 지났는데 아무도 시험 발행하지 않았으면(시험 발행할 미확인
     * 작업이 없거나, 시험 발행이 격리·중복 확인 등으로 구독자 수 없이 끝났다) 큐를 잠정적으로 다시 연다. 다음 발행이 재확인이 되고,
     * 또 0명이면 늘어난 간격으로 다시 멈춘다.
     */
    public void reopenIfNothingToProbe(ChatJobStream stream) {
        synchronized (this) {
            Probe subscription = subscriptions.get(stream);
            if (!subscription.down || subscription.owner != 0 || System.nanoTime() < subscription.nextProbeAt) {
                return;
            }
            log.warn("{} 큐를 재확인할 미확인 작업이 없어 잠정적으로 다시 엶(다음 발행이 재확인, 재확인 실패 {}회)", stream,
                    subscription.failures);
            subscription.reopenTentatively();
        }
    }

    // ----------------------------------------------------------------------------------------------------------
    // 공통
    // ----------------------------------------------------------------------------------------------------------

    /**
     * 멈춘 동안 worker가 바쁘게 돌지 않도록 다음 재확인 시각까지(최대 {@code max}) 기다린다. 인터럽트되면 바로 돌아온다.
     */
    public void awaitRetry(ChatJobStream stream, Duration max) {
        long waitNanos;
        synchronized (this) {
            long now = System.nanoTime();
            Probe subscription = subscriptions.get(stream);
            long until = Math.max(redis.down ? redis.nextProbeAt : now,
                    subscription.down ? subscription.nextProbeAt : now);
            waitNanos = Math.min(max.toNanos(), Math.max(until - now, 0));
        }
        if (waitNanos <= 0) {
            waitNanos = Math.min(max.toNanos(), Duration.ofMillis(10).toNanos());
        }
        try {
            Thread.sleep(Duration.ofNanos(waitNanos));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 실행기를 다시 시작할 때 정상 상태로 되돌린다. 실제 상태는 다음 Redis 호출·발행으로 다시 알게 된다 */
    public synchronized void reset() {
        redis.up();
        writes.up();
        subscriptions.values().forEach(Probe::up);
    }

    /** 재확인 권한을 준다. 잠금 아래에서 부른다 */
    private long acquire(Probe probe) {
        probe.owner = ++lastToken;
        return probe.owner;
    }

    private static boolean owns(Probe probe, long token) {
        return token != 0 && probe.owner == token;
    }

    /** 재확인 실패 n번 뒤의 간격: {@code probe-initial × 2^n}, {@code probe-max}에서 멈춘다 */
    private long delayNanos(int failures) {
        long max = Math.max(settings.probeInitial().toNanos(), settings.probeMax().toNanos());
        long nanos = settings.probeInitial().toNanos();
        for (int i = 0; i < failures && nanos < max; i++) {
            nanos = Math.min(max, nanos * 2);
        }
        return nanos;
    }

    private void count(String target) {
        if (meterRegistry != null) {
            meterRegistry.counter("chat.delivery.paused", "target", target).increment();
        }
    }

    private void registerGauge(String target, Probe probe) {
        if (meterRegistry != null) {
            Gauge.builder("chat.delivery.paused.state", probe, state -> state.down ? 1 : 0)
                    .tag("target", target)
                    .description("전달 파이프라인 멈춤 상태(1이면 멈춤)")
                    .register(meterRegistry);
        }
    }

    /** 멈춤 상태와 다음 재확인 시각. {@code down}만 게이지가 잠금 없이 읽고, 나머지는 바깥 객체의 잠금 아래에서만 바꾼다 */
    private static final class Probe {
        private volatile boolean down;
        /** 이번 장애에서 재확인이 실패한 횟수. 재확인 간격을 정한다 */
        private int failures;
        private long nextProbeAt;
        /** 재확인 권한을 가진 호출의 토큰. 0이면 없다 */
        private long owner;
        /** 재확인 없이 잠정적으로 다시 열었다. 다음 0명은 재확인 실패로 센다 */
        private boolean tentative;

        /** 장애를 연다. 잠정적으로 다시 연 뒤라면 이전 실패 횟수에서 간격을 이어 간다 */
        void open(IntToLongFunction delay) {
            down = true;
            tentative = false;
            owner = 0;
            nextProbeAt = System.nanoTime() + delay.applyAsLong(failures);
        }

        void probeFailed(IntToLongFunction delay) {
            failures++;
            nextProbeAt = System.nanoTime() + delay.applyAsLong(failures);
        }

        /** 재확인 없이 다시 연다. 실패 횟수는 남겨, 다시 멈추면 늘어난 간격을 쓴다 */
        void reopenTentatively() {
            down = false;
            tentative = true;
            owner = 0;
        }

        /** 장애가 끝났다. 이 장애의 재확인 권한은 무효가 된다 */
        void up() {
            down = false;
            failures = 0;
            tentative = false;
            owner = 0;
        }
    }
}
