package com.hapbang.auxiliary.notification.app;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
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

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;

/**
 * 저장한 알림을 commit 뒤에 실시간으로 넘긴다(ND-027·ND-059).
 * <ul>
 *     <li><b>commit 뒤에만</b>: 저장 트랜잭션에 동기화를 걸어 {@code afterCommit}에서만 작업을 넘긴다. 롤백되면 아무것도 하지 않는다.</li>
 *     <li><b>저장 스레드 밖에서</b>: 프레임을 만들고(DB 읽기) Redis에 발행하는 일은 전용 실행기({@value #EXECUTOR_BEAN_NAME}, 스레드 1개,
 *     줄 상한 있음)가 한다. Redis가 멈추거나 느려도 저장(이벤트 소비)은 기다리지 않고, 저장 결과는 되돌려지지 않는다.</li>
 *     <li><b>파기와 순서를 지켜(ND-054)</b>: 다른 회원의 닉네임이 든 알림(언급 회원 칸이나 값의 닉네임이 하나라도 있음)은 언급 회원의
 *     파기 순서 공유 잠금을 잡은 트랜잭션 안에서 다시 읽고 그 안에서 발행한다. 파기가 진행 중이면 끝난 뒤 파기된 닉네임으로 보낸다.
 *     다른 회원의 닉네임이 없는 알림은 파기로 바뀔 값이 없으므로 잠금 없이 읽고 트랜잭션을 끝낸 뒤 발행한다(DB 연결을 쥐지 않는다).</li>
 *     <li><b>장애 때 생략</b>: 발행이 실패하면 {@code failure-backoff}(기본 5초) 동안은 DB를 읽거나 잠금을 잡기 전에 바로 버린다.
 *     시간이 지나면 다시 시도하고, 성공하면 정상으로 돌아온다. Redis가 계속 멈춰 있으면 생략 시간마다 시도 1건이 Redis 시간 제한
 *     (auxiliary-app 10초)만큼 기다릴 수 있다(언급 회원이 있는 알림이면 그동안 DB 연결 1개와 잠금을 쥔다).</li>
 *     <li><b>버린 프레임은 이유별로 센다</b>: {@value #DROPPED_METRIC}{reason=queue_full|read_failed|publish_failed|skipped}. 로그는 버리기
 *     시작할 때 WARN, 다시 보낼 때 INFO만 남긴다(docs/dev/logging.md). 버린 프레임은 다시 보내지 않고 FE가 REST로 맞춘다.</li>
 *     <li>밀려난(Soft Delete된) 알림은 보내지 않는다. 안 읽은 수는 발행 때 센다.</li>
 * </ul>
 * 실시간 전달을 끈 실행(발행기·실행기 빈 없음)에서는 아무것도 하지 않는다.
 */
@Slf4j
@Component
public class NotificationRealtimeRelay {

    /** 발행 실행기 빈 이름 */
    public static final String EXECUTOR_BEAN_NAME = "notificationRealtimeExecutor";
    /**
     * 버린 프레임 수 지표. 태그 {@code reason}: {@code queue_full}(줄 포화), {@code read_failed}(DB 다시 읽기·파기 순서 잠금 실패),
     * {@code publish_failed}(Redis 발행 실패, 생략 시간 시작), {@code skipped}(생략 시간 중)
     */
    public static final String DROPPED_METRIC = "hapbang.notification.realtime.dropped";

    private final NotificationRepository notifications;
    private final ObjectProvider<NotificationRealtimePublisher> publisher;
    private final ObjectProvider<TaskExecutor> executor;
    private final TransactionTemplate readOnly;
    private final MemberMaskLocks maskLocks;
    private final ObjectProvider<MeterRegistry> meterRegistry;
    private final Duration failureBackoff;
    private final Clock clock;
    /** 발행을 생략하는 끝 시각. null이면 정상 */
    private final AtomicReference<Instant> skipUntil = new AtomicReference<>();
    /** 버리기 시작했다는 WARN을 남긴 뒤 아직 다시 보내지 못했으면 true(로그를 상태가 바뀔 때만 남긴다) */
    private final AtomicBoolean dropping = new AtomicBoolean();

    @Autowired
    public NotificationRealtimeRelay(NotificationRepository notifications,
            ObjectProvider<NotificationRealtimePublisher> publisher,
            @Qualifier(EXECUTOR_BEAN_NAME) ObjectProvider<TaskExecutor> executor,
            PlatformTransactionManager transactionManager, MemberMaskLocks maskLocks,
            ObjectProvider<MeterRegistry> meterRegistry,
            @Value("${hapbang.notification.realtime.publish.failure-backoff:5s}") Duration failureBackoff) {
        this(notifications, publisher, executor, transactionManager, maskLocks, meterRegistry, failureBackoff,
                Clock.systemUTC());
    }

    /** 시계를 직접 넘긴다(테스트) */
    NotificationRealtimeRelay(NotificationRepository notifications,
            ObjectProvider<NotificationRealtimePublisher> publisher, ObjectProvider<TaskExecutor> executor,
            PlatformTransactionManager transactionManager, MemberMaskLocks maskLocks,
            ObjectProvider<MeterRegistry> meterRegistry, Duration failureBackoff, Clock clock) {
        this.notifications = notifications;
        this.publisher = publisher;
        this.executor = executor;
        this.maskLocks = maskLocks;
        this.meterRegistry = meterRegistry;
        this.failureBackoff = failureBackoff;
        this.clock = clock;
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
        this.readOnly.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** 지금 트랜잭션이 commit되면 그 알림의 프레임을 넘긴다. 트랜잭션 밖에서 부르면 바로 넘긴다 */
    public void publishAfterCommit(Long notificationId, Long receiverUserId) {
        NotificationRealtimePublisher target = publisher.getIfAvailable();
        TaskExecutor taskExecutor = executor.getIfAvailable();
        if (target == null || taskExecutor == null) {
            return;
        }
        Runnable submit = () -> submit(taskExecutor, target, notificationId, receiverUserId);
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

    private void submit(TaskExecutor taskExecutor, NotificationRealtimePublisher target, Long notificationId,
            Long receiverUserId) {
        try {
            taskExecutor.execute(() -> deliver(target, notificationId, receiverUserId));
        } catch (RuntimeException exception) {
            // afterCommit 안이다. 저장은 이미 commit됐으므로 예외를 밖으로 내지 않는다
            dropped("queue_full", notificationId, exception);
        }
    }

    private void deliver(NotificationRealtimePublisher target, Long notificationId, Long receiverUserId) {
        if (skipping()) {
            // 발행이 실패한 직후다. DB를 읽거나 잠금을 잡기 전에 버린다
            skipped(notificationId);
            return;
        }
        Delivery delivery;
        try {
            delivery = readOnly.execute(status -> notifications
                    .findByIdAndReceiverUserIdAndDeletedAtIsNull(notificationId, receiverUserId)
                    .map(notification -> {
                        List<Long> mentioned = mentionedOf(notification);
                        return mentioned.isEmpty()
                                ? Delivery.ready(frameOf(notification, receiverUserId))
                                : Delivery.locked(mentioned);
                    })
                    .orElse(Delivery.NONE));
        } catch (RuntimeException exception) {
            // DB 읽기 실패는 Redis 장애가 아니므로 생략 시간을 걸지 않는다
            dropped("read_failed", notificationId, exception);
            return;
        }
        if (delivery == null || delivery == Delivery.NONE) {
            log.debug("보이지 않는 알림이라 실시간으로 보내지 않음 notificationId={}", notificationId);
            return;
        }
        try {
            if (delivery.frame() != null) {
                // 다른 회원의 닉네임이 없다: 트랜잭션을 끝낸 뒤 발행한다
                publish(target, receiverUserId, delivery.frame());
            } else {
                publishUnderMaskLock(target, notificationId, receiverUserId, delivery.mentioned());
            }
        } catch (RuntimeException exception) {
            PublishFailedException publishFailure = publishFailureOf(exception);
            if (publishFailure != null) {
                // Redis 발행 실패만 생략 시간을 건다
                skipUntil.set(clock.instant().plus(failureBackoff));
                dropped("publish_failed", notificationId, publishFailure.publishCause());
            } else {
                // 잠금 경로의 잠금·다시 읽기(DB) 실패. Redis 장애가 아니므로 생략하지 않는다
                dropped("read_failed", notificationId, exception);
            }
            return;
        }
        skipUntil.set(null);
        if (dropping.compareAndSet(true, false)) {
            log.info("알림 실시간 전달이 다시 나감 notificationId={}", notificationId);
        }
    }

    /** 언급 회원의 파기 순서 공유 잠금을 잡고 다시 읽은 값으로, 잠금을 쥔 채 발행한다. 파기는 이 발행이 끝난 뒤 commit된다(ND-054) */
    private void publishUnderMaskLock(NotificationRealtimePublisher target, Long notificationId, Long receiverUserId,
            List<Long> mentioned) {
        readOnly.executeWithoutResult(status -> {
            maskLocks.lockShared(mentioned);
            notifications.findByIdAndReceiverUserIdAndDeletedAtIsNull(notificationId, receiverUserId)
                    .ifPresent(notification -> publish(target, receiverUserId, frameOf(notification, receiverUserId)));
        });
    }

    /** Redis 발행. 실패를 {@link PublishFailedException}으로 감싸 DB·잠금 실패와 구분한다 */
    private static void publish(NotificationRealtimePublisher target, Long receiverUserId, NotificationFrame frame) {
        try {
            target.publish(receiverUserId, frame);
        } catch (RuntimeException exception) {
            throw new PublishFailedException(exception);
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

    /** 다시 읽은 결과: 보낼 프레임(잠금 없음) 또는 잠금 경로로 보낼 언급 회원, 또는 보낼 것 없음 */
    private record Delivery(NotificationFrame frame, List<Long> mentioned) {

        static final Delivery NONE = new Delivery(null, List.of());

        static Delivery ready(NotificationFrame frame) {
            return new Delivery(frame, List.of());
        }

        static Delivery locked(List<Long> mentioned) {
            return new Delivery(null, mentioned);
        }
    }
}
