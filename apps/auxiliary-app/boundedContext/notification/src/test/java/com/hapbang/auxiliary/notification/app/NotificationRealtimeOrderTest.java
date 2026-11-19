package com.hapbang.auxiliary.notification.app;

import static com.hapbang.auxiliary.notification.support.NotificationFixtures.provider;
import static com.hapbang.auxiliary.notification.support.NotificationFixtures.unread;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
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
 * 실시간 전달의 프레임 순서와 안 읽은 수를 읽는 때(ND-205, NT-RT-10·NT-RT-11). N17-1 진단에서 묶음 처리 전 동작을 이 시험으로 고정했고
 * (스레드 1개 실행기, regression-log NR-110), 묶음 처리 뒤에도 같아야 한다.
 * <ul>
 *     <li>발행 스레드가 밀린 동안 쌓인 프레임이 같은 받는 사람 안에서 넘긴(commit 뒤) 순서대로 나간다. 파기 순서 잠금 경로로 가는
 *     알림이 섞여도 같다.</li>
 *     <li>안 읽은 수는 넘긴 때가 아니라 발행 직전에 센 값이다.</li>
 * </ul>
 */
class NotificationRealtimeOrderTest {

    private static final Instant T0 = Instant.parse("2026-11-17T05:00:00Z");

    private final NotificationRepository notifications = mock(NotificationRepository.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final MemberMaskLocks maskLocks = mock(MemberMaskLocks.class);
    private final Map<Long, Notification> stored = new HashMap<>();
    private final AtomicLong unread = new AtomicLong();
    private NotificationRealtimeWorker worker;

    @AfterEach
    void shutdown() {
        if (worker != null) {
            worker.stop();
        }
    }

    @Test
    void 발행이_밀려도_같은_받는_사람의_프레임은_넘긴_순서대로_나간다_잠금_경로가_섞여도_같다() throws Exception {
        List<String> published = new CopyOnWriteArrayList<>();
        int total = 300;
        CountDownLatch done = new CountDownLatch(total);
        NotificationRealtimeRelay relay = relay((receiver, frame) -> {
            published.add(receiver + ":" + frame.notification().targetId());
            done.countDown();
        });

        // 발행 스레드를 시작하기 전에 프레임을 줄에 쌓는다(저장이 발행보다 빠른 백로그)
        Map<Long, List<String>> expected = new LinkedHashMap<>();
        for (int i = 1; i <= total; i++) {
            long receiver = 10L + (i % 3);
            boolean mention = i % 7 == 0;
            stored(i, receiver, mention);
            expected.computeIfAbsent(receiver, r -> new ArrayList<>()).add(receiver + ":t-" + i);
            relay.publishAfterCommit((long) i, receiver);
        }
        start(relay);

        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        for (Map.Entry<Long, List<String>> entry : expected.entrySet()) {
            String prefix = entry.getKey() + ":";
            assertThat(published.stream().filter(p -> p.startsWith(prefix)).toList())
                    .as("받는 사람 %d", entry.getKey())
                    .containsExactlyElementsOf(entry.getValue());
        }
        assertThat(published).hasSize(total);
    }

    @Test
    void 안_읽은_수는_넘긴_때가_아니라_발행하기_직전에_센다() throws Exception {
        List<NotificationFrame> frames = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(3);
        NotificationRealtimeRelay relay = relay((receiver, frame) -> {
            frames.add(frame);
            done.countDown();
        });
        for (int i = 1; i <= 3; i++) {
            stored(i, 10L, false);
            relay.publishAfterCommit((long) i, 10L);
        }
        // 세 건을 넘긴 뒤(발행 전)에 안 읽은 수가 바뀐다: 세 프레임 모두 바뀐 값을 담는다
        unread.set(3);
        start(relay);

        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(frames).extracting(NotificationFrame::unreadCount).containsExactly(3L, 3L, 3L);
        assertThat(frames).extracting(f -> f.notification().targetId()).containsExactly("t-1", "t-2", "t-3");
    }

    // ---------------------------------------------------------------------------------------------

    private NotificationRealtimeRelay relay(NotificationRealtimePublisher publisher) {
        when(notifications.findAllByIdInAndDeletedAtIsNull(anyCollection())).thenAnswer(invocation -> {
            Collection<Long> ids = invocation.getArgument(0);
            return ids.stream().filter(stored::containsKey).map(stored::get).toList();
        });
        when(notifications.countUnreadByReceivers(anyCollection())).thenAnswer(invocation -> {
            Collection<Long> receivers = invocation.getArgument(0);
            return receivers.stream().map(r -> unread(r, unread.get())).toList();
        });
        when(notifications.countByReceiverUserIdAndDeletedAtIsNullAndReadAtIsNull(anyLong()))
                .thenAnswer(invocation -> unread.get());
        return new NotificationRealtimeRelay(notifications, provider(NotificationRealtimePublisher.class, publisher),
                transactions, maskLocks, provider(MeterRegistry.class, new SimpleMeterRegistry()), Duration.ofSeconds(5),
                1000, 100, Clock.fixed(T0, ZoneOffset.UTC));
    }

    private void start(NotificationRealtimeRelay relay) {
        worker = new NotificationRealtimeWorker(relay);
        worker.start();
    }

    private void stored(long id, long receiver, boolean mention) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("v", 1);
        if (mention) {
            payload.put("nicknames", Map.of("7", "호스트"));
        }
        NotificationDraft draft = new NotificationDraft(receiver, "TEST_ORDER", "TestEvent", "s-" + id, "LECTURE",
                "t-" + id, payload, T0);
        Notification notification = Notification.create(draft, p -> "문구");
        ReflectionTestUtils.setField(notification, "id", id);
        stored.put(id, notification);
        when(notifications.findByIdAndReceiverUserIdAndDeletedAtIsNull(id, receiver))
                .thenReturn(Optional.of(notification));
    }
}
