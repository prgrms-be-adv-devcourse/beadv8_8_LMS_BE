package com.hapbang.chat.app;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;

import com.hapbang.chat.app.ChatRecipientStateReader.RoomMembership;
import com.hapbang.chat.app.dto.ChatRecipientState;

/**
 * 방 참여 캐시의 버전 확인 규칙: 버전이 그대로면 다시 읽지 않고, 메시지 버전이 더 크면 다시 읽고, 더 낮은 버전으로 덮지 않는다.
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
    void 방_수_상한을_넘으면_오래_쓰지_않은_방부터_버린다() {
        ChatRoomMembershipCache cache = new ChatRoomMembershipCache(reader, 2, Duration.ofHours(1));
        when(reader.findRoomMembership(anyLong())).thenReturn(membership(1));

        cache.get(1L, 1);
        cache.get(2L, 1);
        cache.get(1L, 1);
        cache.get(3L, 1);
        cache.get(1L, 1);
        cache.get(2L, 1);

        verify(reader, times(1)).findRoomMembership(1L);
        verify(reader, times(2)).findRoomMembership(2L);
    }

    private static RoomMembership membership(int version) {
        return new RoomMembership(version, Map.of(7L, new ChatRecipientState(7L, true, true, 1, 1)));
    }
}
