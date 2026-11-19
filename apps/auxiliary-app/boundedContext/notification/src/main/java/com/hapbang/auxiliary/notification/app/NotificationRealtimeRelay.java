package com.hapbang.auxiliary.notification.app;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.hapbang.auxiliary.notification.app.dto.NotificationFrame;
import com.hapbang.auxiliary.notification.app.dto.NotificationItem;
import com.hapbang.auxiliary.notification.domain.Notification;
import com.hapbang.auxiliary.notification.domain.NotificationPayload;
import com.hapbang.auxiliary.notification.out.MemberMaskLocks;
import com.hapbang.auxiliary.notification.out.NotificationRepository;
import com.hapbang.auxiliary.notification.out.NotificationRepository.ReceiverUnread;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;

/**
 * 저장한 알림을 commit 뒤에 실시간으로 넘긴다(ND-027·ND-059·ND-205·ND-209·ND-211).
 * <ul>
 *     <li><b>commit 뒤에만</b>: 저장 트랜잭션에 동기화를 걸어 {@code afterCommit}에서만 줄에 넣는다. 롤백되면 아무것도 하지 않는다.</li>
 *     <li><b>저장 스레드 밖에서, 묶음으로</b>: 줄(상한 {@code queue-capacity}, 기본 1000)은 발행 스레드 1개
 *     ({@link NotificationRealtimeWorker})가 비운다. 한 번에 최대 {@code batch-size}(기본 100)개를 꺼내 알림은 한 번에 읽고, 안 읽은
 *     수는 받는 사람별로 한 번에 센 뒤(읽기 전용 트랜잭션 하나, DB 연결 1개), 꺼낸 순서대로 Redis에 발행한다. 스레드가 하나라 같은 받는
 *     사람의 프레임은 {@code afterCommit}에서 줄에 넣은 순서대로 나간다(ND-211). 한 스레드가 차례로 저장한 알림은 commit 순서와 같지만,
 *     서로 다른 저장 스레드에서 거의 동시에 commit된 알림끼리는 commit 순서와 다를 수 있다. 안 읽은 수는 묶음을 읽을 때(묶음의 모든 알림이 commit된 뒤, 발행 전) 센 값이다. 같은
 *     받는 사람의 닉네임 알림을 잠금 경로에서 다시 셌으면 그 뒤 프레임은 다시 센 값을 이어받는다(앞 프레임보다 먼저 읽은 값이 뒤에
 *     오지 않는다). Redis가 멈추거나 느려도 저장(이벤트 소비)은 기다리지 않고, 저장 결과는 되돌려지지 않는다.</li>
 *     <li><b>파기와 순서를 지켜(ND-054)</b>: 다른 회원의 닉네임이 든 알림(언급 회원 칸이나 값의 닉네임이 하나라도 있음)은 그 자리에서
 *     언급 회원의 파기 순서 공유 잠금을 잡은 트랜잭션 안에서 다시 읽고(안 읽은 수도 다시 센다) 그 안에서 발행한다. 파기가 진행 중이면
 *     끝난 뒤 파기된 닉네임으로 보낸다. 다른 회원의 닉네임이 없는 알림은 파기로 바뀔 값이 없으므로 묶음으로 읽은 값으로 트랜잭션을 끝낸
 *     뒤 발행한다(DB 연결을 쥐지 않는다).</li>
 *     <li><b>장애 때 생략</b>: 발행이 실패하면 {@code failure-backoff}(기본 5초) 동안은 DB를 읽거나 잠금을 잡기 전에 바로 버린다.
 *     시간이 지나면 다시 시도하고, 성공하면 정상으로 돌아온다. Redis가 계속 멈춰 있으면 생략 시간마다 시도 1건이 Redis 시간 제한
 *     (auxiliary-app 10초)만큼 기다릴 수 있다(언급 회원이 있는 알림이면 그동안 DB 연결 1개와 잠금을 쥔다).</li>
 *     <li><b>버린 프레임은 이유별로 센다</b>: {@value #DROPPED_METRIC}{reason=queue_full|read_failed|publish_failed|skipped}. 로그는 버리기
 *     시작할 때 WARN, 다시 보낼 때 INFO만 남긴다(docs/dev/logging.md). 버린 프레임은 다시 보내지 않고 FE가 REST로 맞춘다. 묶음을 읽지
 *     못하면 그 묶음의 프레임을 모두 {@code read_failed}로 버린다.</li>
 *     <li>밀려난(Soft Delete된) 알림과 받는 사람이 다른 알림은 보내지 않는다.</li>
 *     <li><b>멈출 때</b>: 발행 스레드가 멈추면 Relay를 닫는다. 남은 줄은 보내지 않고 버리며 그 수를 돌려준다(지표에는 세지 않고 발행
 *     스레드가 WARN 한 줄로 남긴다). 닫힌 뒤 넘긴 프레임은 줄에 넣지 않고 {@code queue_full}로 센다(이전 실행기의
 *     {@code acceptTasksAfterContextClose(false)} 거절과 같다).</li>
 * </ul>
 * 실시간 전달을 끈 실행(발행기 빈 없음)에서는 줄에 넣지 않는다.
 */
@Slf4j
@Component
public class NotificationRealtimeRelay {

    /**
     * 버린 프레임 수 지표. 태그 {@code reason}: {@code queue_full}(줄 포화), {@code read_failed}(DB 다시 읽기·파기 순서 잠금 실패),
     * {@code publish_failed}(Redis 발행 실패, 생략 시간 시작), {@code skipped}(생략 시간 중)
     */
    public static final String DROPPED_METRIC = "hapbang.notification.realtime.dropped";

    /**
     * Redis 발행 구간 지표(ND-162): commit 뒤 넘긴 시각부터 Redis 발행 완료까지. 줄 대기와 다시 읽기(DB)가 들어 있다. 버리거나
     * 발행에 실패한 프레임은 재지 않는다. 서버 내부 outbound 구간(STOMP)과 합해도 클라이언트 수신까지의 전체 지연이 아니다
     */
    public static final String PUBLISH_LATENCY_METRIC = "hapbang.notification.realtime.publish.latency";

    /** 실시간 구간 지표(발행·STOMP outbound)의 histogram 경계 */
    public static final List<Duration> LATENCY_BUCKETS = List.of(Duration.ofMillis(5), Duration.ofMillis(10),
            Duration.ofMillis(25), Duration.ofMillis(50), Duration.ofMillis(100), Duration.ofMillis(250),
            Duration.ofMillis(500), Duration.ofSeconds(1), Duration.ofMillis(2_500), Duration.ofSeconds(5));

    private final NotificationRepository notifications;
    private final ObjectProvider<NotificationRealtimePublisher> publisher;
    private final TransactionTemplate readOnly;
    private final MemberMaskLocks maskLocks;
    private final ObjectProvider<MeterRegistry> meterRegistry;
    private final Duration failureBackoff;
    private final int batchSize;
    private final Clock clock;
    /** commit된 뒤 발행을 기다리는 프레임. 발행 스레드 하나만 꺼낸다 */
    private final BlockingQueue<Pending> pending;
    /** 발행을 생략하는 끝 시각. null이면 정상 */
    private final AtomicReference<Instant> skipUntil = new AtomicReference<>();
    /** 버리기 시작했다는 WARN을 남긴 뒤 아직 다시 보내지 못했으면 true(로그를 상태가 바뀔 때만 남긴다) */
    private final AtomicBoolean dropping = new AtomicBoolean();
    /** 발행 스레드가 멈춰 닫혔으면 true. 닫힌 뒤 넘긴 프레임은 줄에 넣지 않는다 */
    private volatile boolean closed;

    @Autowired
    public NotificationRealtimeRelay(NotificationRepository notifications,
            ObjectProvider<NotificationRealtimePublisher> publisher, PlatformTransactionManager transactionManager,
            MemberMaskLocks maskLocks, ObjectProvider<MeterRegistry> meterRegistry,
            NotificationRealtimePublishProperties properties) {
        this(notifications, publisher, transactionManager, maskLocks, meterRegistry, properties.failureBackoff(),
                properties.queueCapacity(), properties.batchSize(), Clock.systemUTC());
    }

    /** 시계를 직접 넘긴다(테스트) */
    NotificationRealtimeRelay(NotificationRepository notifications,
            ObjectProvider<NotificationRealtimePublisher> publisher, PlatformTransactionManager transactionManager,
            MemberMaskLocks maskLocks, ObjectProvider<MeterRegistry> meterRegistry, Duration failureBackoff,
            int queueCapacity, int batchSize, Clock clock) {
        if (queueCapacity < 1 || batchSize < 1) {
            throw new IllegalArgumentException("queue-capacity와 batch-size는 1 이상이어야 한다: "
                    + queueCapacity + ", " + batchSize);
        }
        this.notifications = notifications;
        this.publisher = publisher;
        this.maskLocks = maskLocks;
        this.meterRegistry = meterRegistry;
        this.failureBackoff = failureBackoff;
        this.batchSize = batchSize;
        this.clock = clock;
        this.pending = new ArrayBlockingQueue<>(queueCapacity);
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
        this.readOnly.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** 지금 트랜잭션이 commit되면 그 알림의 프레임을 줄에 넣는다. 트랜잭션 밖에서 부르면 바로 넣는다 */
    public void publishAfterCommit(Long notificationId, Long receiverUserId) {
        if (publisher.getIfAvailable() == null) {
            return;
        }
        Runnable submit = () -> submit(notificationId, receiverUserId, clock.instant());
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    submit.run();
                }
            });
        } else {
            submit.run();
        }
    }

    /**
     * 줄에서 최대 {@code batch-size}개를 꺼내 보낸다(발행 스레드 전용). 줄이 비어 있으면 {@code wait}만큼 기다린다.
     *
     * @return 꺼낸 프레임 수(버린 것 포함). 기다려도 없으면 0
     */
    int deliverPending(Duration wait) throws InterruptedException {
        Pending first = wait.isZero() ? pending.poll() : pending.poll(wait.toNanos(), TimeUnit.NANOSECONDS);
        if (first == null) {
            return 0;
        }
        List<Pending> batch = new ArrayList<>(Math.min(batchSize, pending.size() + 1));
        batch.add(first);
        pending.drainTo(batch, batchSize - 1);
        NotificationRealtimePublisher target = publisher.getIfAvailable();
        if (target != null) {
            deliver(target, batch);
        }
        return batch.size();
    }

    /** 발행 스레드가 시작할 때 연다. 처음에는 열려 있다 */
    void open() {
        closed = false;
    }

    /**
     * 발행 스레드가 멈춘 뒤 닫는다. 남은 프레임은 보내지 않고 버린다(지표에는 세지 않는다). 닫힌 뒤 넘긴 프레임은 거절한다.
     *
     * @return 버린 프레임 수
     */
    int close() {
        closed = true;
        List<Pending> remaining = new ArrayList<>();
        pending.drainTo(remaining);
        return remaining.size();
    }

    private void submit(Long notificationId, Long receiverUserId, Instant committedAt) {
        // afterCommit 안이다. 저장은 이미 commit됐으므로 예외를 밖으로 내지 않는다
        if (closed) {
            dropped("queue_full", notificationId, null);
            return;
        }
        Pending frame = new Pending(notificationId, receiverUserId, committedAt);
        if (!pending.offer(frame)) {
            dropped("queue_full", notificationId, null);
        } else if (closed && pending.remove(frame)) {
            // 넣는 사이에 닫혔다. 아무도 비우지 않으므로 거절로 센다
            dropped("queue_full", notificationId, null);
        }
    }

    private void deliver(NotificationRealtimePublisher target, List<Pending> batch) {
        Loaded loaded = null;
        for (int i = 0; i < batch.size(); i++) {
            Pending frame = batch.get(i);
            if (skipping()) {
                // 발행이 실패한 직후다. DB를 읽거나 잠금을 잡기 전에 버린다
                skipped(frame.notificationId());
                continue;
            }
            if (loaded == null) {
                List<Pending> rest = batch.subList(i, batch.size());
                try {
                    loaded = readOnly.execute(status -> load(rest));
                } catch (RuntimeException exception) {
                    // DB 읽기 실패는 Redis 장애가 아니므로 생략 시간을 걸지 않는다
                    for (Pending failed : rest) {
                        dropped("read_failed", failed.notificationId(), exception);
                    }
                    return;
                }
            }
            deliverOne(target, frame, loaded);
        }
    }

    /** 묶음의 알림과 받는 사람별 안 읽은 수를 한 번에 읽는다 */
    private Loaded load(List<Pending> frames) {
        Set<Long> ids = new LinkedHashSet<>();
        Set<Long> receivers = new LinkedHashSet<>();
        for (Pending frame : frames) {
            ids.add(frame.notificationId());
            receivers.add(frame.receiverUserId());
        }
        Map<Long, Notification> byId = new HashMap<>();
        for (Notification notification : notifications.findAllByIdInAndDeletedAtIsNull(List.copyOf(ids))) {
            byId.put(notification.getId(), notification);
        }
        Map<Long, Long> unread = new HashMap<>();
        for (ReceiverUnread count : notifications.countUnreadByReceivers(List.copyOf(receivers))) {
            unread.put(count.getReceiverUserId(), count.getUnread());
        }
        return new Loaded(byId, unread);
    }

    private void deliverOne(NotificationRealtimePublisher target, Pending frame, Loaded loaded) {
        Notification notification = loaded.notifications().get(frame.notificationId());
        if (notification == null || !frame.receiverUserId().equals(notification.getReceiverUserId())) {
            log.debug("보이지 않는 알림이라 실시간으로 보내지 않음 notificationId={}", frame.notificationId());
            return;
        }
        List<Long> mentioned = mentionedOf(notification);
        try {
            if (mentioned.isEmpty()) {
                // 다른 회원의 닉네임이 없다: 묶음으로 읽은 값으로, 트랜잭션 밖에서 발행한다
                long unread = loaded.unread().getOrDefault(frame.receiverUserId(), 0L);
                publish(target, frame.receiverUserId(), new NotificationFrame(NotificationItem.from(notification), unread),
                        frame.committedAt());
            } else {
                Long recounted = publishUnderMaskLock(target, frame.notificationId(), frame.receiverUserId(),
                        mentioned, frame.committedAt());
                if (recounted != null) {
                    // 잠금 경로는 발행할 때 다시 센다. 뒤에 오는 같은 받는 사람 프레임이 이보다 오래된 묶음 값을 받지 않게 한다
                    loaded.unread().put(frame.receiverUserId(), recounted);
                }
            }
        } catch (RuntimeException exception) {
            PublishFailedException publishFailure = publishFailureOf(exception);
            if (publishFailure != null) {
                // Redis 발행 실패만 생략 시간을 건다
                skipUntil.set(clock.instant().plus(failureBackoff));
                dropped("publish_failed", frame.notificationId(), publishFailure.publishCause());
            } else {
                // 잠금 경로의 잠금·다시 읽기(DB) 실패. Redis 장애가 아니므로 생략하지 않는다
                dropped("read_failed", frame.notificationId(), exception);
            }
            return;
        }
        skipUntil.set(null);
        if (dropping.compareAndSet(true, false)) {
            log.info("알림 실시간 전달이 다시 나감 notificationId={}", frame.notificationId());
        }
    }

    /**
     * 언급 회원의 파기 순서 공유 잠금을 잡고 다시 읽은 값으로, 잠금을 쥔 채 발행한다. 파기는 이 발행이 끝난 뒤 commit된다(ND-054)
     *
     * @return 발행한 프레임의 안 읽은 수. 알림이 보이지 않아 보내지 않았으면 null
     */
    private Long publishUnderMaskLock(NotificationRealtimePublisher target, Long notificationId, Long receiverUserId,
            List<Long> mentioned, Instant committedAt) {
        return readOnly.execute(status -> {
            maskLocks.lockShared(mentioned);
            return notifications.findByIdAndReceiverUserIdAndDeletedAtIsNull(notificationId, receiverUserId)
                    .map(notification -> {
                        NotificationFrame frame = frameOf(notification, receiverUserId);
                        publish(target, receiverUserId, frame, committedAt);
                        return frame.unreadCount();
                    })
                    .orElse(null);
        });
    }

    /**
     * Redis 발행. 실패를 {@link PublishFailedException}으로 감싸 DB·잠금 실패와 구분한다. 성공하면 commit 뒤부터 발행 완료까지를
     * {@value #PUBLISH_LATENCY_METRIC}에 잰다
     */
    private void publish(NotificationRealtimePublisher target, Long receiverUserId, NotificationFrame frame,
            Instant committedAt) {
        try {
            target.publish(receiverUserId, frame);
        } catch (RuntimeException exception) {
            throw new PublishFailedException(exception);
        }
        recordPublishLatency(committedAt);
    }

    private void recordPublishLatency(Instant committedAt) {
        try {
            MeterRegistry registry = meterRegistry.getIfAvailable();
            if (registry != null) {
                Timer.builder(PUBLISH_LATENCY_METRIC)
                        .serviceLevelObjectives(LATENCY_BUCKETS.toArray(Duration[]::new))
                        .register(registry)
                        .record(Duration.between(committedAt, clock.instant()));
            }
        } catch (RuntimeException exception) {
            log.warn("알림 실시간 발행 구간 지표를 기록하지 못했다(전달에는 영향 없음)", exception);
        }
    }

    /**
     * 예외가 Redis 발행 실패인지 본다. 잠금 경로에서 발행이 실패한 뒤 롤백까지 실패하면 {@link TransactionTemplate}이
     * {@link TransactionSystemException}을 던지고 원래 예외는 그 안의 응용 예외로 남는다.
     */
    private static PublishFailedException publishFailureOf(RuntimeException exception) {
        if (exception instanceof PublishFailedException publishFailure) {
            return publishFailure;
        }
        if (exception instanceof TransactionSystemException system
                && system.getApplicationException() instanceof PublishFailedException publishFailure) {
            return publishFailure;
        }
        return null;
    }

    /** Redis 발행 실패 표시. 잠금 경로의 트랜잭션 밖으로 그대로 나온다 */
    private static final class PublishFailedException extends RuntimeException {

        PublishFailedException(RuntimeException cause) {
            super(cause);
        }

        RuntimeException publishCause() {
            return (RuntimeException) getCause();
        }
    }

    private boolean skipping() {
        Instant until = skipUntil.get();
        return until != null && clock.instant().isBefore(until);
    }

    private NotificationFrame frameOf(Notification notification, Long receiverUserId) {
        return new NotificationFrame(NotificationItem.from(notification),
                notifications.countByReceiverUserIdAndDeletedAtIsNullAndReadAtIsNull(receiverUserId));
    }

    /** 생략 시간에 버린 프레임. 버리기 시작한 WARN은 발행 실패 때 이미 남겼으므로 DEBUG만 남긴다 */
    private void skipped(Long notificationId) {
        count("skipped");
        log.debug("발행 실패 뒤 생략 시간이라 알림 실시간 전달을 건너뜀 notificationId={}", notificationId);
    }

    private void count(String reason) {
        MeterRegistry registry = meterRegistry.getIfAvailable();
        if (registry != null) {
            registry.counter(DROPPED_METRIC, "reason", reason).increment();
        }
    }

    private void dropped(String reason, Long notificationId, RuntimeException exception) {
        count(reason);
        if (dropping.compareAndSet(false, true)) {
            log.warn("알림 실시간 전달 프레임을 버리기 시작함(이후는 지표 {}로 셈) reason={}, notificationId={}",
                    DROPPED_METRIC, reason, notificationId, exception);
        } else {
            log.debug("알림 실시간 전달 프레임을 버림 reason={}, notificationId={}", reason, notificationId);
        }
    }

    /**
     * 파기로 바뀔 수 있는 다른 회원 ID. 언급 회원 칸과 값의 닉네임 키를 함께 본다. 둘 중 하나라도 있으면 잠금 경로로 보낸다
     * (칸이 비었더라도 값에 닉네임이 있으면 잠금 없이 보내지 않는다).
     */
    private static List<Long> mentionedOf(Notification notification) {
        Long[] column = notification.getMentionedUserIds();
        Stream<Long> fromColumn = column == null ? Stream.empty() : Arrays.stream(column);
        Stream<Long> fromPayload = NotificationPayload.nicknames(notification.getPayload()).keySet().stream()
                .map(Long::valueOf);
        return Stream.concat(fromColumn, fromPayload).distinct().sorted().toList();
    }

    /** commit된 뒤 발행을 기다리는 프레임 */
    private record Pending(Long notificationId, Long receiverUserId, Instant committedAt) {
    }

    /** 묶음으로 읽은 알림(ID별)과 받는 사람별 안 읽은 수 */
    private record Loaded(Map<Long, Notification> notifications, Map<Long, Long> unread) {
    }
}
