package com.hapbang.auxiliary.notification.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import com.hapbang.auxiliary.notification.app.dto.NotificationFrame;
import com.hapbang.auxiliary.notification.domain.Notification;
import com.hapbang.auxiliary.notification.domain.NotificationDraft;
import com.hapbang.auxiliary.notification.out.MemberMaskLocks;
import com.hapbang.auxiliary.notification.out.NotificationRepository;
import com.hapbang.auxiliary.notification.out.NotificationRepository.ReceiverUnread;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * 실시간 전달 묶음 처리(N17-1, ND-205, NT-RT-10·NT-RT-11).
 * <ul>
 *     <li>줄에 쌓인 프레임을 묶음(상한 있음)으로 꺼내, 알림은 한 번에 읽고 안 읽은 수는 받는 사람별로 한 번에 센다. 프레임마다 DB를 읽지
 *     않는다.</li>
 *     <li>꺼낸 순서대로 발행한다. 다른 회원 닉네임이 든 알림은 그 자리에서 파기 순서 잠금 경로로 한 건씩 보낸다(ND-054).</li>
 *     <li>줄이 차면 {@code queue_full}로 버린다. 묶음 읽기가 실패하면 그 묶음 프레임을 {@code read_failed}로 버린다.</li>
 * </ul>
 */
class NotificationRealtimeBatchTest {

    private static final Instant T0 = Instant.parse("2026-11-17T05:00:00Z");

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final NotificationRepository notifications = mock(NotificationRepository.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final MemberMaskLocks maskLocks = mock(MemberMaskLocks.class);
    private final Map<Long, Notification> stored = new HashMap<>();
    private final List<String> published = new ArrayList<>();
    private final NotificationRealtimePublisher publisher = (receiver, frame) ->
            published.add(receiver + ":" + frame.notification().targetId() + ":" + frame.unreadCount());

    @Test
    void 쌓인_프레임은_알림을_한_번에_읽고_받는_사람별_안_읽은_수를_한_번에_세어_넘긴_순서대로_보낸다() throws Exception {
        givenUnread(Map.of(10L, 4L, 20L, 2L));
        NotificationRealtimeRelay relay = relay(1000, 100);
        for (long id = 1; id <= 5; id++) {
            long receiver = id % 2 == 1 ? 10L : 20L;
            stored(id, receiver, false);
            relay.publishAfterCommit(id, receiver);
        }

        assertThat(relay.deliverPending(Duration.ZERO)).isEqualTo(5);

        assertThat(published).containsExactly("10:t-1:4", "20:t-2:2", "10:t-3:4", "20:t-4:2", "10:t-5:4");
        verify(notifications, times(1)).findAllByIdInAndDeletedAtIsNull(anyCollection());
        verify(notifications, times(1)).countUnreadByReceivers(anyCollection());
        verify(notifications, never()).findByIdAndReceiverUserIdAndDeletedAtIsNull(anyLong(), anyLong());
        verify(notifications, never()).countByReceiverUserIdAndDeletedAtIsNullAndReadAtIsNull(anyLong());
    }

    @Test
    void 한_번에_꺼내는_수는_묶음_상한까지다() throws Exception {
        givenUnread(Map.of());
        NotificationRealtimeRelay relay = relay(1000, 2);
        for (long id = 1; id <= 5; id++) {
            stored(id, 10L, false);
            relay.publishAfterCommit(id, 10L);
        }

        assertThat(relay.deliverPending(Duration.ZERO)).isEqualTo(2);
        assertThat(relay.deliverPending(Duration.ZERO)).isEqualTo(2);
        assertThat(relay.deliverPending(Duration.ZERO)).isEqualTo(1);
        assertThat(relay.deliverPending(Duration.ZERO)).isZero();
        assertThat(published).containsExactly("10:t-1:0", "10:t-2:0", "10:t-3:0", "10:t-4:0", "10:t-5:0");
    }

    @Test
    void 줄이_차면_넘기지_못한_프레임을_queue_full로_센다() throws Exception {
        givenUnread(Map.of());
        NotificationRealtimeRelay relay = relay(2, 100);
        for (long id = 1; id <= 3; id++) {
            stored(id, 10L, false);
            relay.publishAfterCommit(id, 10L);
        }

        assertThat(dropped("queue_full")).isEqualTo(1.0);
        relay.deliverPending(Duration.ZERO);
        assertThat(published).containsExactly("10:t-1:0", "10:t-2:0");
    }

    @Test
    void 닉네임이_든_알림은_그_자리에서_잠금_경로로_보내_순서가_바뀌지_않는다() throws Exception {
        givenUnread(Map.of(10L, 3L));
        stored(1L, 10L, false);
        Notification mentioning = stored(2L, 10L, true);
        stored(3L, 10L, false);
        when(notifications.findByIdAndReceiverUserIdAndDeletedAtIsNull(2L, 10L)).thenReturn(Optional.of(mentioning));
        when(notifications.countByReceiverUserIdAndDeletedAtIsNullAndReadAtIsNull(10L)).thenReturn(3L);
        NotificationRealtimePublisher spy = mock(NotificationRealtimePublisher.class);
        NotificationRealtimeRelay relay = relay(1000, 100, spy);
        relay.publishAfterCommit(1L, 10L);
        relay.publishAfterCommit(2L, 10L);
        relay.publishAfterCommit(3L, 10L);

        relay.deliverPending(Duration.ZERO);

        InOrder order = inOrder(spy, maskLocks);
        order.verify(spy).publish(eq(10L), frameOf("t-1"));
        order.verify(maskLocks).lockShared(List.of(7L));
        order.verify(spy).publish(eq(10L), frameOf("t-2"));
        order.verify(spy).publish(eq(10L), frameOf("t-3"));
    }

    @Test
    void 잠금_경로로_보낸_뒤의_같은_받는_사람_프레임은_그보다_오래된_안_읽은_수를_받지_않는다() throws Exception {
        // 묶음을 읽을 때 3이었고, 잠금 경로에서 다시 셀 때는 그사이 저장이 commit돼 5다(NT-RT-10)
        givenUnread(Map.of(10L, 3L));
        Notification mentioning = stored(1L, 10L, true);
        stored(2L, 10L, false);
        when(notifications.findByIdAndReceiverUserIdAndDeletedAtIsNull(1L, 10L)).thenReturn(Optional.of(mentioning));
        when(notifications.countByReceiverUserIdAndDeletedAtIsNullAndReadAtIsNull(10L)).thenReturn(5L);
        NotificationRealtimeRelay relay = relay(1000, 100);
        relay.publishAfterCommit(1L, 10L);
        relay.publishAfterCommit(2L, 10L);

        relay.deliverPending(Duration.ZERO);

        assertThat(published).containsExactly("10:t-1:5", "10:t-2:5");
    }

    @Test
    void 받는_사람이_다르거나_밀려난_알림은_보내지_않는다() throws Exception {
        givenUnread(Map.of(10L, 1L));
        stored(1L, 10L, false);
        stored(2L, 99L, false);
        NotificationRealtimeRelay relay = relay(1000, 100);
        relay.publishAfterCommit(1L, 10L);
        relay.publishAfterCommit(2L, 10L); // 다른 회원의 알림 ID
        relay.publishAfterCommit(3L, 10L); // 밀려나 읽히지 않음

        relay.deliverPending(Duration.ZERO);

        assertThat(published).containsExactly("10:t-1:1");
    }

    @Test
    void 묶음을_읽지_못하면_그_묶음의_프레임을_read_failed로_세고_다음_묶음은_보낸다() throws Exception {
        givenUnread(Map.of());
        stored(1L, 10L, false);
        stored(2L, 10L, false);
        NotificationRealtimeRelay relay = relay(1000, 1);
        when(notifications.findAllByIdInAndDeletedAtIsNull(List.of(1L)))
                .thenThrow(new IllegalStateException("db down"));
        relay.publishAfterCommit(1L, 10L);
        relay.publishAfterCommit(2L, 10L);

        relay.deliverPending(Duration.ZERO);
        relay.deliverPending(Duration.ZERO);

        assertThat(dropped("read_failed")).isEqualTo(1.0);
        assertThat(published).containsExactly("10:t-2:0");
    }

    @Test
    void 비어_있으면_기다린_만큼만_기다리고_0을_돌려준다() throws Exception {
        NotificationRealtimeRelay relay = relay(1000, 100);

        assertThat(relay.deliverPending(Duration.ofMillis(20))).isZero();
        verify(notifications, never()).findAllByIdInAndDeletedAtIsNull(anyCollection());
    }

    @Test
    void 묶음_경계를_넘어도_같은_받는_사람의_안_읽은_수는_읽은_시점_순서를_따른다() throws Exception {
        // 첫 묶음을 읽을 때 3, 잠금 경로에서 다시 셀 때 5, 둘째 묶음을 읽을 때 6(그사이 저장이 계속 commit됨)
        givenUnreadInOrder(10L, 3L, 6L);
        stored(1L, 10L, false);
        Notification mentioning = stored(2L, 10L, true);
        stored(3L, 10L, false);
        when(notifications.findByIdAndReceiverUserIdAndDeletedAtIsNull(2L, 10L)).thenReturn(Optional.of(mentioning));
        when(notifications.countByReceiverUserIdAndDeletedAtIsNullAndReadAtIsNull(10L)).thenReturn(5L);
        NotificationRealtimeRelay relay = relay(1000, 2);
        for (long id = 1; id <= 3; id++) {
            relay.publishAfterCommit(id, 10L);
        }

        relay.deliverPending(Duration.ZERO);
        relay.deliverPending(Duration.ZERO);

        assertThat(published).containsExactly("10:t-1:3", "10:t-2:5", "10:t-3:6");
    }

    @Test
    void 읽음으로_실제_안_읽은_수가_줄면_뒤_프레임은_줄어든_최신_값을_받는다() throws Exception {
        // 묶음을 읽을 때 5, 그 뒤 전체 읽음이 commit돼 잠금 경로에서 다시 셀 때 2. 값을 붙잡아 두지 않는다(ND-059: 그때의 안 읽은 수)
        givenUnread(Map.of(10L, 5L));
        stored(1L, 10L, false);
        Notification mentioning = stored(2L, 10L, true);
        stored(3L, 10L, false);
        when(notifications.findByIdAndReceiverUserIdAndDeletedAtIsNull(2L, 10L)).thenReturn(Optional.of(mentioning));
        when(notifications.countByReceiverUserIdAndDeletedAtIsNullAndReadAtIsNull(10L)).thenReturn(2L);
        NotificationRealtimeRelay relay = relay(1000, 100);
        for (long id = 1; id <= 3; id++) {
            relay.publishAfterCommit(id, 10L);
        }

        relay.deliverPending(Duration.ZERO);

        assertThat(published).containsExactly("10:t-1:5", "10:t-2:2", "10:t-3:2");
    }

    @Test
    void 잠금_경로에서_알림이_보이지_않아_보내지_않으면_뒤_프레임은_묶음_값을_그대로_쓴다() throws Exception {
        givenUnread(Map.of(10L, 3L));
        stored(1L, 10L, true);
        stored(2L, 10L, false);
        when(notifications.findByIdAndReceiverUserIdAndDeletedAtIsNull(1L, 10L)).thenReturn(Optional.empty());
        NotificationRealtimeRelay relay = relay(1000, 100);
        relay.publishAfterCommit(1L, 10L);
        relay.publishAfterCommit(2L, 10L);

        relay.deliverPending(Duration.ZERO);

        assertThat(published).containsExactly("10:t-2:3");
        verify(notifications, never()).countByReceiverUserIdAndDeletedAtIsNullAndReadAtIsNull(anyLong());
    }

    @Test
    void 닫으면_남은_프레임을_보내지_않고_그_수를_돌려주며_닫힌_뒤_넘긴_프레임은_거절해_queue_full로_센다() throws Exception {
        givenUnread(Map.of());
        stored(1L, 10L, false);
        stored(2L, 10L, false);
        stored(3L, 10L, false);
        NotificationRealtimeRelay relay = relay(1000, 100);
        relay.publishAfterCommit(1L, 10L);
        relay.publishAfterCommit(2L, 10L);

        assertThat(relay.close()).isEqualTo(2);
        relay.publishAfterCommit(3L, 10L);

        assertThat(dropped("queue_full")).isEqualTo(1.0);
        assertThat(relay.deliverPending(Duration.ZERO)).isZero();
        assertThat(published).isEmpty();
    }

    @Test
    void 다시_열면_새로_넘긴_프레임을_받는다() throws Exception {
        givenUnread(Map.of());
        stored(1L, 10L, false);
        NotificationRealtimeRelay relay = relay(1000, 100);
        relay.close();

        relay.open();
        relay.publishAfterCommit(1L, 10L);
        relay.deliverPending(Duration.ZERO);

        assertThat(published).containsExactly("10:t-1:0");
        assertThat(dropped("queue_full")).isZero();
    }

    // ---------------------------------------------------------------------------------------------

    private NotificationRealtimeRelay relay(int capacity, int batchSize) {
        return relay(capacity, batchSize, publisher);
    }

    private NotificationRealtimeRelay relay(int capacity, int batchSize, NotificationRealtimePublisher target) {
        when(notifications.findAllByIdInAndDeletedAtIsNull(anyCollection())).thenAnswer(invocation -> {
            Collection<Long> ids = invocation.getArgument(0);
            return ids.stream().filter(stored::containsKey).map(stored::get).toList();
        });
        return new NotificationRealtimeRelay(notifications, provider(NotificationRealtimePublisher.class, target),
                transactions, maskLocks, provider(MeterRegistry.class, registry), Duration.ofSeconds(5), capacity,
                batchSize, Clock.fixed(T0, ZoneOffset.UTC));
    }

    private void givenUnread(Map<Long, Long> counts) {
        when(notifications.countUnreadByReceivers(anyCollection())).thenAnswer(invocation -> {
            Collection<Long> receivers = invocation.getArgument(0);
            return receivers.stream().filter(counts::containsKey)
                    .map(r -> (ReceiverUnread) new TestUnread(r, counts.get(r))).toList();
        });
    }

    /** 한 받는 사람의 묶음 안 읽은 수를 묶음을 읽을 때마다 차례로 돌려준다 */
    private void givenUnreadInOrder(Long receiver, Long... counts) {
        List<ReceiverUnread> first = List.of(new TestUnread(receiver, counts[0]));
        @SuppressWarnings("unchecked")
        List<ReceiverUnread>[] rest = java.util.Arrays.stream(counts).skip(1)
                .map(count -> List.<ReceiverUnread>of(new TestUnread(receiver, count))).toArray(List[]::new);
        when(notifications.countUnreadByReceivers(anyCollection())).thenReturn(first, rest);
    }

    private Notification stored(long id, long receiver, boolean mention) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("v", 1);
        if (mention) {
            payload.put("nicknames", Map.of("7", "호스트"));
        }
        NotificationDraft draft = new NotificationDraft(receiver, "TEST_BATCH", "TestEvent", "s-" + id, "LECTURE",
                "t-" + id, payload, T0);
        Notification notification = Notification.create(draft, p -> "문구");
        ReflectionTestUtils.setField(notification, "id", id);
        stored.put(id, notification);
        return notification;
    }

    private static NotificationFrame frameOf(String targetId) {
        return org.mockito.ArgumentMatchers.argThat(frame -> frame.notification().targetId().equals(targetId));
    }

    private double dropped(String reason) {
        var counter = registry.find(NotificationRealtimeRelay.DROPPED_METRIC).tag("reason", reason).counter();
        return counter == null ? 0.0 : counter.count();
    }

    private static <T> ObjectProvider<T> provider(Class<T> type, T bean) {
        return new StaticListableBeanFactory(Map.of("bean", bean)).getBeanProvider(type);
    }

    private record TestUnread(Long receiverUserId, long unread) implements ReceiverUnread {

        @Override
        public Long getReceiverUserId() {
            return receiverUserId;
        }

        @Override
        public long getUnread() {
            return unread;
        }
    }
}
