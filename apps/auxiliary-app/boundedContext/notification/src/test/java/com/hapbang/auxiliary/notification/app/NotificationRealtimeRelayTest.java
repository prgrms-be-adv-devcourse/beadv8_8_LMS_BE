package com.hapbang.auxiliary.notification.app;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionSystemException;

import com.hapbang.auxiliary.notification.domain.Notification;
import com.hapbang.auxiliary.notification.domain.NotificationDraft;
import com.hapbang.auxiliary.notification.out.MemberMaskLocks;
import com.hapbang.auxiliary.notification.out.NotificationRepository;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * 실시간 전달의 장애 처리와 파기 순서(ND-027·ND-054, #829 Bot 리뷰).
 * <ul>
 *     <li>버린 프레임은 이유별로 센다: 줄 포화 {@code queue_full}, 발행 실패 {@code publish_failed}, 실패 뒤 생략 {@code skipped}.</li>
 *     <li>발행이 실패하면 생략 시간(기본 5초) 동안 DB 조회·파기 순서 잠금 전에 바로 버린다. 시간이 지나면 다시 시도하고, 성공하면
 *     정상으로 돌아온다. 버린 프레임은 다시 보내지 않는다.</li>
 *     <li>언급 회원이 있는 알림은 파기 순서 공유 잠금 안에서 발행한다(commit 전 발행). 언급 회원(값의 닉네임)이 없는 알림은
 *     트랜잭션을 끝낸 뒤 발행하고 잠금을 잡지 않는다.</li>
 * </ul>
 */
@ExtendWith(OutputCaptureExtension.class)
class NotificationRealtimeRelayTest {

    private static final Instant T0 = Instant.parse("2026-11-10T05:00:00Z");
    private static final Duration BACKOFF = Duration.ofSeconds(5);

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final NotificationRepository notifications = mock(NotificationRepository.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final MemberMaskLocks maskLocks = mock(MemberMaskLocks.class);
    private final MutableClock clock = new MutableClock(T0);

    @Test
    void 줄이_가득_차_넘기지_못한_프레임은_queue_full로_센다() {
        TaskExecutor rejecting = task -> {
            throw new TaskRejectedException("full");
        };
        NotificationRealtimeRelay relay = relay(rejecting, (receiver, frame) -> { });

        relay.publishAfterCommit(1L, 10L);
        relay.publishAfterCommit(2L, 10L);

        assertThat(dropped("queue_full")).isEqualTo(2.0);
    }

    @Test
    void 발행이_실패하면_publish_failed로_세고_생략_시간_동안은_DB_조회와_잠금_전에_skipped로_버린다(CapturedOutput output) {
        givenNotification(1L, 10L, mentioning());
        givenNotification(2L, 10L, mentioning());
        NotificationRealtimeRelay relay = relay(Runnable::run, (receiver, frame) -> {
            throw new IllegalStateException("redis down");
        });

        relay.publishAfterCommit(1L, 10L);
        clearInvocations(notifications, maskLocks);
        clock.advance(BACKOFF.minusMillis(1));
        relay.publishAfterCommit(2L, 10L);

        assertThat(dropped("publish_failed")).isEqualTo(1.0);
        assertThat(dropped("skipped")).isEqualTo(1.0);
        verifyNoInteractions(notifications, maskLocks);
        assertThat(countOf(output, "알림 실시간 전달 프레임을 버리기 시작함")).isEqualTo(1);
    }

    @Test
    void 생략_시간이_지나면_다시_시도하고_성공하면_정상으로_돌아온다(CapturedOutput output) {
        givenNotification(1L, 10L, mentioning());
        givenNotification(2L, 10L, mentioning());
        givenNotification(3L, 10L, mentioning());
        AtomicBoolean down = new AtomicBoolean(true);
        AtomicInteger sent = new AtomicInteger();
        NotificationRealtimeRelay relay = relay(Runnable::run, (receiver, frame) -> {
            if (down.get()) {
                throw new IllegalStateException("redis down");
            }
            sent.incrementAndGet();
        });

        relay.publishAfterCommit(1L, 10L);
        down.set(false);
        clock.advance(BACKOFF);
        relay.publishAfterCommit(2L, 10L);
        relay.publishAfterCommit(3L, 10L);

        assertThat(sent.get()).isEqualTo(2);
        assertThat(dropped("skipped")).isZero();
        assertThat(countOf(output, "알림 실시간 전달이 다시 나감")).isEqualTo(1);
    }

    @Test
    void 언급_회원이_있는_알림은_파기_순서_잠금을_잡고_commit_전에_발행한다() {
        givenNotification(1L, 10L, mentioning());
        NotificationRealtimePublisher publisher = mock(NotificationRealtimePublisher.class);
        NotificationRealtimeRelay relay = relay(Runnable::run, publisher);

        relay.publishAfterCommit(1L, 10L);

        InOrder order = inOrder(maskLocks, publisher, transactions);
        order.verify(maskLocks).lockShared(List.of(7L));
        order.verify(publisher).publish(eq(10L), any());
        order.verify(transactions).commit(any());
    }

    @Test
    void 언급_회원이_없는_알림은_잠금을_잡지_않고_트랜잭션을_끝낸_뒤_발행한다() {
        givenNotification(1L, 10L, noMention());
        NotificationRealtimePublisher publisher = mock(NotificationRealtimePublisher.class);
        NotificationRealtimeRelay relay = relay(Runnable::run, publisher);

        relay.publishAfterCommit(1L, 10L);

        verify(maskLocks, never()).lockShared(any());
        InOrder order = inOrder(transactions, publisher);
        order.verify(transactions).commit(any());
        order.verify(publisher).publish(eq(10L), any());
        verifyNoMoreInteractions(publisher);
    }

    @Test
    void 알림을_다시_읽지_못하면_read_failed로_세고_생략_시간을_걸지_않는다() {
        when(notifications.findByIdAndReceiverUserIdAndDeletedAtIsNull(1L, 10L))
                .thenThrow(new IllegalStateException("db down"));
        givenNotification(2L, 10L, noMention());
        NotificationRealtimePublisher publisher = mock(NotificationRealtimePublisher.class);
        NotificationRealtimeRelay relay = relay(Runnable::run, publisher);

        relay.publishAfterCommit(1L, 10L);
        relay.publishAfterCommit(2L, 10L);

        assertThat(dropped("read_failed")).isEqualTo(1.0);
        assertThat(dropped("publish_failed")).isZero();
        assertThat(dropped("skipped")).isZero();
        verify(publisher).publish(eq(10L), any());
    }

    @Test
    void 언급_회원이_없는_알림의_발행이_실패해도_publish_failed로_세고_생략_시간을_건다() {
        givenNotification(1L, 10L, noMention());
        givenNotification(2L, 10L, noMention());
        NotificationRealtimeRelay relay = relay(Runnable::run, (receiver, frame) -> {
            throw new IllegalStateException("redis down");
        });

        relay.publishAfterCommit(1L, 10L);
        relay.publishAfterCommit(2L, 10L);

        assertThat(dropped("publish_failed")).isEqualTo(1.0);
        assertThat(dropped("skipped")).isEqualTo(1.0);
        assertThat(dropped("read_failed")).isZero();
    }

    /** 잠금 경로에서 발행이 실패한 뒤 롤백까지 실패해도, 원래 원인(Redis 발행 실패)으로 센다 */
    @Test
    void 발행_실패_뒤_롤백이_실패해도_publish_failed로_센다() {
        givenNotification(1L, 10L, mentioning());
        doThrow(new TransactionSystemException("rollback failed")).when(transactions).rollback(any());
        NotificationRealtimeRelay relay = relay(Runnable::run, (receiver, frame) -> {
            throw new IllegalStateException("redis down");
        });

        relay.publishAfterCommit(1L, 10L);

        assertThat(dropped("publish_failed")).isEqualTo(1.0);
        assertThat(dropped("read_failed")).isZero();
    }

    /** 잠금 경로의 DB·잠금 실패는 Redis 장애가 아니다. 생략 시간을 걸면 Redis가 정상인데도 5초 동안 모두 버린다(#829 Bot 리뷰 2차) */
    @Test
    void 잠금_경로에서_잠금이나_DB가_실패하면_read_failed로_세고_생략하지_않는다() {
        givenNotification(1L, 10L, mentioning());
        givenNotification(2L, 10L, noMention());
        doThrow(new IllegalStateException("lock failed")).when(maskLocks).lockShared(any());
        NotificationRealtimePublisher publisher = mock(NotificationRealtimePublisher.class);
        NotificationRealtimeRelay relay = relay(Runnable::run, publisher);

        relay.publishAfterCommit(1L, 10L);
        relay.publishAfterCommit(2L, 10L);

        assertThat(dropped("read_failed")).isEqualTo(1.0);
        assertThat(dropped("publish_failed")).isZero();
        assertThat(dropped("skipped")).isZero();
        verify(publisher).publish(eq(10L), any());
    }

    /** 언급 회원 칸이 비어 있어도 값에 다른 회원의 닉네임이 있으면 잠금 경로로 보낸다(ND-054 방어) */
    @Test
    void 언급_회원_칸이_비어도_값에_닉네임이_있으면_잠금을_잡고_보낸다() {
        Notification notification = givenNotification(1L, 10L, mentioning());
        ReflectionTestUtils.setField(notification, "mentionedUserIds", new Long[0]);
        NotificationRealtimePublisher publisher = mock(NotificationRealtimePublisher.class);
        NotificationRealtimeRelay relay = relay(Runnable::run, publisher);

        relay.publishAfterCommit(1L, 10L);

        InOrder order = inOrder(maskLocks, publisher);
        order.verify(maskLocks).lockShared(List.of(7L));
        order.verify(publisher).publish(eq(10L), any());
    }

    // ---------------------------------------------------------------------------------------------

    private NotificationRealtimeRelay relay(TaskExecutor executor, NotificationRealtimePublisher publisher) {
        return new NotificationRealtimeRelay(notifications, provider(NotificationRealtimePublisher.class, publisher),
                provider(TaskExecutor.class, executor), transactions, maskLocks,
                provider(MeterRegistry.class, registry), BACKOFF, clock);
    }

    private Notification givenNotification(long id, long receiver, Map<String, Object> payload) {
        NotificationDraft draft = new NotificationDraft(receiver, "TEST_MENTION", "TestEvent", "s-" + id, "LECTURE",
                "s-" + id, payload, T0);
        Notification notification = Notification.create(draft, p -> "문구");
        when(notifications.findByIdAndReceiverUserIdAndDeletedAtIsNull(id, receiver))
                .thenReturn(Optional.of(notification));
        return notification;
    }

    private static Map<String, Object> mentioning() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("v", 1);
        payload.put("nicknames", Map.of("7", "호스트"));
        return payload;
    }

    private static Map<String, Object> noMention() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("v", 1);
        payload.put("amount", 1000);
        return payload;
    }

    private double dropped(String reason) {
        var counter = registry.find(NotificationRealtimeRelay.DROPPED_METRIC).tag("reason", reason).counter();
        return counter == null ? 0.0 : counter.count();
    }

    private static int countOf(CapturedOutput output, String text) {
        return output.getAll().split(java.util.regex.Pattern.quote(text), -1).length - 1;
    }

    private static <T> ObjectProvider<T> provider(Class<T> type, T bean) {
        return new StaticListableBeanFactory(Map.of("bean", bean)).getBeanProvider(type);
    }

    private static final class MutableClock extends Clock {

        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }
}
