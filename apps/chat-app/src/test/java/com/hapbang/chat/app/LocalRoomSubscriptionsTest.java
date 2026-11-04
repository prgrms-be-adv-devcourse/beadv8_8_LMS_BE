package com.hapbang.chat.app;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import com.hapbang.chat.app.LocalRoomSubscriptions.Registered;
import com.hapbang.chat.app.LocalRoomSubscriptions.Subscription;

/**
 * 방 topic 구독 목록: 연결별 상한, 같은 구독 ID, 방 채널 참조 수, 연결 종료 정리, 모르는 구독 ID 무시.
 */
class LocalRoomSubscriptionsTest {

    private final RoomChannelSubscriptions channels = mock(RoomChannelSubscriptions.class);
    private final LocalRoomSubscriptions subscriptions = new LocalRoomSubscriptions(provider(channels), 2);

    @Test
    void 넣을_때마다_방_채널_참조를_늘리고_뺄_때마다_줄인다() {
        when(channels.acquire(anyLong())).thenReturn(true);

        assertThat(subscriptions.register(sub("s-1", "a", 10L))).isEqualTo(Registered.OK);
        assertThat(subscriptions.register(sub("s-2", "a", 10L))).isEqualTo(Registered.OK);
        assertThat(subscriptions.subscribedSessions(10L)).containsExactlyInAnyOrder("s-1", "s-2");

        assertThat(subscriptions.unregister("s-1", "a")).isNotNull();
        assertThat(subscriptions.unregisterSession("s-2")).hasSize(1);

        verify(channels, times(2)).acquire(10L);
        verify(channels, times(2)).release(10L);
        assertThat(subscriptions.subscriptions(10L)).isEmpty();
    }

    @Test
    void 연결의_구독_수_상한을_넘거나_같은_구독_ID면_넣지_않고_참조를_되돌린다() {
        when(channels.acquire(anyLong())).thenReturn(true);
        subscriptions.register(sub("s-1", "a", 10L));
        subscriptions.register(sub("s-1", "b", 11L));

        assertThat(subscriptions.register(sub("s-1", "c", 12L))).isEqualTo(Registered.TOO_MANY);
        assertThat(subscriptions.register(sub("s-1", "a", 13L))).isEqualTo(Registered.DUPLICATE);

        verify(channels).release(12L);
        verify(channels).release(13L);
        assertThat(subscriptions.subscriptions(12L)).isEmpty();
    }

    @Test
    void 앱의_방_채널_상한이면_넣지_않는다() {
        when(channels.acquire(10L)).thenReturn(false);

        assertThat(subscriptions.register(sub("s-1", "a", 10L))).isEqualTo(Registered.SERVER_BUSY);

        assertThat(subscriptions.subscriptions(10L)).isEmpty();
        verify(channels, never()).release(anyLong());
    }

    @Test
    void 모르는_구독_ID의_해제는_무시한다() {
        assertThat(subscriptions.unregister("s-1", "unknown")).isNull();
        assertThat(subscriptions.unregisterSession("s-9")).isEmpty();

        verifyNoInteractions(channels);
    }

    @Test
    void 회수는_그_구독이_지금도_같을_때만_뺀다() {
        when(channels.acquire(anyLong())).thenReturn(true);
        Subscription subscription = sub("s-1", "a", 10L);
        subscriptions.register(subscription);

        assertThat(subscriptions.remove(subscription)).isTrue();
        assertThat(subscriptions.remove(subscription)).as("이미 회수됨").isFalse();
        verify(channels, times(1)).release(10L);
    }

    @Test
    void Redis가_없는_실행에서도_목록은_동작한다() {
        LocalRoomSubscriptions withoutRedis = new LocalRoomSubscriptions(provider(null), 20);

        assertThat(withoutRedis.register(sub("s-1", "a", 10L))).isEqualTo(Registered.OK);
        assertThat(withoutRedis.subscriptionsOf(10L, 7L)).hasSize(1);
    }

    private static Subscription sub(String sessionId, String subscriptionId, Long roomId) {
        return new Subscription(sessionId, subscriptionId, roomId, 7L, 1);
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<RoomChannelSubscriptions> provider(RoomChannelSubscriptions channels) {
        ObjectProvider<RoomChannelSubscriptions> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(channels);
        return provider;
    }
}
