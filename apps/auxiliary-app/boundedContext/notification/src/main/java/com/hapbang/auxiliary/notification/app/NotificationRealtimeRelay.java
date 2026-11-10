package com.hapbang.auxiliary.notification.app;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.hapbang.auxiliary.notification.app.dto.NotificationFrame;
import com.hapbang.auxiliary.notification.app.dto.NotificationItem;
import com.hapbang.auxiliary.notification.domain.Notification;
import com.hapbang.auxiliary.notification.out.MemberMaskLocks;
import com.hapbang.auxiliary.notification.out.NotificationRepository;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;

/**
 * 저장한 알림을 commit 뒤에 실시간으로 넘긴다(ND-027·ND-059).
 * <ul>
 *     <li><b>commit 뒤에만</b>: 저장 트랜잭션에 동기화를 걸어 {@code afterCommit}에서만 작업을 넘긴다. 롤백되면 아무것도 하지 않는다.</li>
 *     <li><b>저장 스레드 밖에서</b>: 프레임을 만들고(DB 읽기) Redis에 발행하는 일은 전용 실행기({@value #EXECUTOR_BEAN_NAME}, 스레드 1개,
 *     줄 상한 있음)가 한다. 그래서 Redis가 멈추거나 느려도 저장(이벤트 소비)은 기다리지 않고, 저장 결과는 되돌려지지 않는다.</li>
 *     <li><b>버린 프레임은 센다</b>: 줄이 차서 넘기지 못하면 {@code queue_full}, 읽기·발행이 실패하면 {@code publish_failed}로
 *     {@value #DROPPED_METRIC}을 올린다. 로그는 버리기 시작할 때 WARN, 다시 보낼 때 INFO만 남긴다(프레임마다 남기지 않는다,
 *     docs/dev/logging.md). FE는 재접속하면 REST로 맞춘다.</li>
 *     <li><b>그때의 상태로, 파기와 순서를 지켜</b>: 작업 스레드는 언급 회원의 파기 순서 공유 잠금(ND-054)을 잡은 트랜잭션에서 알림을
 *     읽고 그 안에서 발행한다. 파기가 진행 중이면 끝날 때까지 기다렸다가 파기된 닉네임으로 보낸다. 밀려난(Soft Delete된) 알림이면
 *     보내지 않는다. 안 읽은 수도 그때 센다.</li>
 * </ul>
 * 실시간 전달을 끈 실행(발행기·실행기 빈 없음)에서는 아무것도 하지 않는다.
 */
@Slf4j
@Component
public class NotificationRealtimeRelay {

    /** 발행 실행기 빈 이름 */
    public static final String EXECUTOR_BEAN_NAME = "notificationRealtimeExecutor";
    /** 버린 프레임 수 지표. 태그 {@code reason}: {@code queue_full}, {@code publish_failed} */
    public static final String DROPPED_METRIC = "hapbang.notification.realtime.dropped";

    private final NotificationRepository notifications;
    private final ObjectProvider<NotificationRealtimePublisher> publisher;
    private final ObjectProvider<TaskExecutor> executor;
    private final TransactionTemplate readOnly;
    private final MemberMaskLocks maskLocks;
    private final ObjectProvider<MeterRegistry> meterRegistry;
    private final AtomicBoolean dropping = new AtomicBoolean(false);

    public NotificationRealtimeRelay(NotificationRepository notifications,
            ObjectProvider<NotificationRealtimePublisher> publisher,
            @Qualifier(EXECUTOR_BEAN_NAME) ObjectProvider<TaskExecutor> executor,
            PlatformTransactionManager transactionManager, MemberMaskLocks maskLocks,
            ObjectProvider<MeterRegistry> meterRegistry) {
        this.notifications = notifications;
        this.publisher = publisher;
        this.executor = executor;
        this.maskLocks = maskLocks;
        this.meterRegistry = meterRegistry;
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
        try {
            // 언급 회원은 저장 때 정해지고 바뀌지 않으므로 잠금 전에 읽어도 된다
            List<Long> mentioned = readOnly.execute(status -> notifications.findById(notificationId)
                    .map(NotificationRealtimeRelay::mentionedOf).orElse(List.of()));
            Boolean sent = readOnly.execute(status -> {
                maskLocks.lockShared(mentioned == null ? List.of() : mentioned);
                Optional<NotificationFrame> frame = notifications
                        .findByIdAndReceiverUserIdAndDeletedAtIsNull(notificationId, receiverUserId)
                        .map(notification -> new NotificationFrame(NotificationItem.from(notification),
                                notifications.countByReceiverUserIdAndDeletedAtIsNullAndReadAtIsNull(receiverUserId)));
                if (frame.isEmpty()) {
                    log.debug("보이지 않는 알림이라 실시간으로 보내지 않음 notificationId={}", notificationId);
                    return false;
                }
                // 공유 잠금을 쥔 채 발행한다. 파기는 이 발행이 끝난 뒤에 commit된다(ND-054)
                target.publish(receiverUserId, frame.get());
                return true;
            });
            if (Boolean.TRUE.equals(sent) && dropping.compareAndSet(true, false)) {
                log.info("알림 실시간 전달이 다시 나감 notificationId={}", notificationId);
            }
        } catch (RuntimeException exception) {
            dropped("publish_failed", notificationId, exception);
        }
    }

    private void dropped(String reason, Long notificationId, RuntimeException exception) {
        MeterRegistry registry = meterRegistry.getIfAvailable();
        if (registry != null) {
            registry.counter(DROPPED_METRIC, "reason", reason).increment();
        }
        if (dropping.compareAndSet(false, true)) {
            log.warn("알림 실시간 전달 프레임을 버리기 시작함(이후는 지표 {}로 셈) reason={}, notificationId={}",
                    DROPPED_METRIC, reason, notificationId, exception);
        } else {
            log.debug("알림 실시간 전달 프레임을 버림 reason={}, notificationId={}", reason, notificationId);
        }
    }

    private static List<Long> mentionedOf(Notification notification) {
        Long[] ids = notification.getMentionedUserIds();
        return ids == null ? List.of() : Arrays.asList(ids);
    }
}
