package com.hapbang.chat.app;

import static com.hapbang.chat.fixture.ChatFixture.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * 하트비트의 두 번째 확인(설계 문서 6장·Q8): 연결 만료가 지난 연결과 로그아웃 표식보다 먼저 인증한 연결을 닫고, 표식을 읽지 못하면
 * 그 확인만 건너뛴다.
 */
class ChatPresenceHeartbeatTest {

    private final ChatPresence presence = mock(ChatPresence.class);
    private final ChatLogoutMarkers markers = mock(ChatLogoutMarkers.class);
    private final LocalChatConnections connections = mock(LocalChatConnections.class);
    private final ChatPresenceHeartbeat heartbeat = new ChatPresenceHeartbeat(presence, markers, connections);

    @Test
    void 로컬_회원의_로그아웃_표식을_한_번에_읽어_넘기고_만료가_지난_연결도_닫는다() {
        Instant marker = Instant.parse("2026-11-02T01:00:00Z");
        connected(HOST_ID, GUEST_ID);
        when(markers.loggedOutAt(anyCollection())).thenReturn(Map.of(HOST_ID, marker));

        heartbeat.run();

        verify(markers, times(1)).loggedOutAt(argThat(ids -> Set.copyOf(ids).equals(Set.of(HOST_ID, GUEST_ID))));
        verify(connections).closeLoggedOutBefore(Map.of(HOST_ID, marker));
        verify(connections).closeExpired();
        verify(presence).heartbeat(anyMap());
    }

    @Test
    void 로그아웃_표식을_읽지_못하면_그_확인만_건너뛰고_만료_확인과_접속_정보_갱신은_한다() {
        connected(HOST_ID);
        when(markers.loggedOutAt(anyCollection())).thenThrow(new IllegalStateException("Redis 실패"));

        heartbeat.run();

        verify(connections, never()).closeLoggedOutBefore(any());
        verify(connections).closeExpired();
        verify(presence).heartbeat(anyMap());
    }

    @Test
    void 접속_정보_갱신이_실패해도_만료와_로그아웃_확인은_한다() {
        connected(HOST_ID);
        doThrow(new IllegalStateException("Redis 실패")).when(presence).heartbeat(anyMap());
        when(markers.loggedOutAt(anyCollection())).thenReturn(Map.of());

        heartbeat.run();

        verify(connections).closeExpired();
        verify(connections).closeLoggedOutBefore(Map.of());
    }

    @Test
    void 로컬_연결이_없으면_로그아웃_표식을_읽지_않는다() {
        when(connections.connectedUserIds()).thenReturn(Set.of());

        heartbeat.run();

        verifyNoInteractions(markers);
        verify(connections).closeExpired();
    }

    private void connected(Long... userIds) {
        when(connections.connectedUserIds()).thenReturn(Set.of(userIds));
        for (Long userId : userIds) {
            when(connections.sessionIdsOf(userId)).thenReturn(Set.of("s-" + userId));
        }
    }
}
