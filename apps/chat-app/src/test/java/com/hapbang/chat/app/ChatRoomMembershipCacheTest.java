package com.hapbang.chat.app;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;

import com.hapbang.chat.app.ChatRecipientStateReader.RoomMembership;
import com.hapbang.chat.app.dto.ChatRecipientState;

/**
 * 방 참여 캐시의 버전 확인 규칙: 버전이 그대로면 다시 읽지 않고, 메시지 버전이 더 크면 다시 읽고, 더 낮은 버전으로 덮지 않는다.
 * 같은 방의 다시 읽기는 한 번에 하나이고, 조회가 실패하면 캐시를 바꾸지 않으며, 방 수 상한을 넘으면 방을 버린다.
 */
class ChatRoomMembershipCacheTest {

    private final ChatRecipientStateReader reader = mock(ChatRecipientStateReader.class);

    @Test
    void 캐시_버전_이하의_메시지는_다시_읽지_않고_더_큰_버전에서만_다시_읽는다() {
        ChatRoomMembershipCache cache = new ChatRoomMembershipCache(reader, 10, Duration.ofHours(1));
        when(reader.findRoomMembership(1L)).thenReturn(membership(3), membership(4));

        assertThat(cache.get(1L, 3).setVersion()).isEqualTo(3);
        assertThat(cache.get(1L, 2).setVersion()).isEqualTo(3);
        assertThat(cache.get(1L, 3).setVersion()).isEqualTo(3);
        assertThat(cache.get(1L, 4).setVersion()).isEqualTo(4);

        verify(reader, times(2)).findRoomMembership(1L);
    }

    @Test
    void 자격_재조회_주기가_지나면_버전이_같아도_다시_읽는다() throws Exception {
        ChatRoomMembershipCache cache = new ChatRoomMembershipCache(reader, 10, Duration.ofMillis(50));
        when(reader.findRoomMembership(1L)).thenReturn(membership(3));

        cache.get(1L, 3);
        Thread.sleep(80);
        cache.get(1L, 3);

        verify(reader, times(2)).findRoomMembership(1L);
    }

    @Test
    void 메시지보다_낮은_버전만_읽히면_확인하지_못한_것으로_본다() {
        ChatRoomMembershipCache cache = new ChatRoomMembershipCache(reader, 10, Duration.ofHours(1));
        when(reader.findRoomMembership(1L)).thenReturn(membership(2));

        assertThatThrownBy(() -> cache.get(1L, 3)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 조회_실패는_그대로_알린다() {
        ChatRoomMembershipCache cache = new ChatRoomMembershipCache(reader, 10, Duration.ofHours(1));
        when(reader.findRoomMembership(1L)).thenThrow(new QueryTimeoutException("timeout"));

        assertThatThrownBy(() -> cache.get(1L, 1)).isInstanceOf(QueryTimeoutException.class);
    }

    @Test
    void 조회가_실패하면_캐시는_그대로이고_다음_요청은_이전_상태를_쓴다() {
        ChatRoomMembershipCache cache = new ChatRoomMembershipCache(reader, 10, Duration.ofHours(1));
        when(reader.findRoomMembership(1L)).thenReturn(membership(3)).thenThrow(new QueryTimeoutException("timeout"));

        cache.get(1L, 3);
        assertThatThrownBy(() -> cache.get(1L, 4)).isInstanceOf(QueryTimeoutException.class);

        assertThat(cache.get(1L, 3).setVersion()).isEqualTo(3);
        verify(reader, times(2)).findRoomMembership(1L);
    }

    @Test
    void 처음_조회가_실패해도_다음_요청은_다시_읽는다() {
        ChatRoomMembershipCache cache = new ChatRoomMembershipCache(reader, 10, Duration.ofHours(1));
        when(reader.findRoomMembership(1L)).thenThrow(new QueryTimeoutException("timeout")).thenReturn(membership(3));

        assertThatThrownBy(() -> cache.get(1L, 1)).isInstanceOf(QueryTimeoutException.class);

        assertThat(cache.get(1L, 1).setVersion()).isEqualTo(3);
        verify(reader, times(2)).findRoomMembership(1L);
    }

    @Test
    void 메시지보다_낮은_버전을_읽어도_읽은_결과는_캐시에_남는다() {
        ChatRoomMembershipCache cache = new ChatRoomMembershipCache(reader, 10, Duration.ofHours(1));
        when(reader.findRoomMembership(1L)).thenReturn(membership(2));

        assertThatThrownBy(() -> cache.get(1L, 3)).isInstanceOf(IllegalStateException.class);

        assertThat(cache.get(1L, 2).setVersion()).isEqualTo(2);
        verify(reader, times(1)).findRoomMembership(1L);
    }

    @Test
    void 주기가_지나_다시_읽은_버전이_더_낮으면_덮지_않고_다음_요청에서_다시_읽는다() throws Exception {
        ChatRoomMembershipCache cache = new ChatRoomMembershipCache(reader, 10, Duration.ofMillis(50));
        when(reader.findRoomMembership(1L)).thenReturn(membership(5), membership(4), membership(5));

        cache.get(1L, 5);
        Thread.sleep(80);

        assertThat(cache.get(1L, 1).setVersion()).isEqualTo(5);
        assertThat(cache.get(1L, 1).setVersion()).isEqualTo(5);
        verify(reader, times(3)).findRoomMembership(1L);
    }

    @Test
    void refresh는_캐시와_관계없이_다시_읽고_더_낮은_버전으로_덮지_않는다() {
        ChatRoomMembershipCache cache = new ChatRoomMembershipCache(reader, 10, Duration.ofHours(1));
        when(reader.findRoomMembership(1L)).thenReturn(membership(5), membership(4), membership(6));

        assertThat(cache.refresh(1L).setVersion()).isEqualTo(5);
        assertThat(cache.refresh(1L).setVersion()).isEqualTo(5);
        assertThat(cache.get(1L, 5).setVersion()).isEqualTo(5);
        assertThat(cache.refresh(1L).setVersion()).isEqualTo(6);
        assertThat(cache.get(1L, 6).setVersion()).isEqualTo(6);

        verify(reader, times(3)).findRoomMembership(1L);
    }

    @Test
    void refresh_조회가_실패하면_캐시는_그대로다() {
        ChatRoomMembershipCache cache = new ChatRoomMembershipCache(reader, 10, Duration.ofHours(1));
        when(reader.findRoomMembership(1L)).thenReturn(membership(3)).thenThrow(new QueryTimeoutException("timeout"));

        cache.get(1L, 3);
        assertThatThrownBy(() -> cache.refresh(1L)).isInstanceOf(QueryTimeoutException.class);

        assertThat(cache.get(1L, 3).setVersion()).isEqualTo(3);
        verify(reader, times(2)).findRoomMembership(1L);
    }

    @Test
    void 같은_방의_동시_요청은_DB를_한_번만_읽는다() throws Exception {
        ChatRoomMembershipCache cache = new ChatRoomMembershipCache(reader, 10, Duration.ofHours(1));
        CountDownLatch reading = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(reader.findRoomMembership(1L)).thenAnswer(invocation -> {
            reading.countDown();
            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            return membership(3);
        });

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<RoomMembership> first = executor.submit(() -> cache.get(1L, 3));
            assertThat(reading.await(5, TimeUnit.SECONDS)).isTrue();
            AtomicReference<Thread> secondThread = new AtomicReference<>();
            Future<RoomMembership> second = executor.submit(() -> {
                secondThread.set(Thread.currentThread());
                return cache.get(1L, 3);
            });
            awaitBlocked(secondThread);
            release.countDown();

            assertThat(first.get(5, TimeUnit.SECONDS).setVersion()).isEqualTo(3);
            assertThat(second.get(5, TimeUnit.SECONDS).setVersion()).isEqualTo(3);
        }
        verify(reader, times(1)).findRoomMembership(1L);
    }

    @Test
    void 방_수_상한을_넘으면_방을_버리고_다시_읽는다() {
        ChatRoomMembershipCache cache = new ChatRoomMembershipCache(reader, 2, Duration.ofHours(1));
        when(reader.findRoomMembership(anyLong())).thenReturn(membership(1));

        for (int pass = 0; pass < 2; pass++) {
            for (long roomId = 1; roomId <= 10; roomId++) {
                cache.get(roomId, 1);
            }
        }

        // 상한이 2이므로 두 번째 차례에서 적어도 8개 방은 버려져 다시 읽는다. 어떤 방을 버릴지는 정하지 않는다
        verify(reader, atLeast(18)).findRoomMembership(anyLong());
    }

    /** 두 번째 요청이 같은 방의 첫 조회를 기다리며 멈출 때까지 기다린다 */
    private static void awaitBlocked(AtomicReference<Thread> thread) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            Thread current = thread.get();
            if (current != null && (current.getState() == Thread.State.BLOCKED
                    || current.getState() == Thread.State.WAITING)) {
                return;
            }
            Thread.sleep(5);
        }
        fail("두 번째 요청이 첫 조회를 기다리지 않았습니다.");
    }

    private static RoomMembership membership(int version) {
        return new RoomMembership(version, Map.of(7L, new ChatRecipientState(7L, true, true, 1, 1)));
    }
}
