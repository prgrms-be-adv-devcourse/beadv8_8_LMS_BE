package com.hapbang.chat.app;

import static com.hapbang.chat.fixture.ChatFixture.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.QueryTimeoutException;

import com.hapbang.chat.app.ChatRecipientStateReader.SubscriptionCheck;
import com.hapbang.chat.app.LocalChatSender.RoomTarget;
import com.hapbang.chat.app.LocalChatSender.SubscriptionSignal;
import com.hapbang.chat.app.dto.ChatRecipientState;
import com.hapbang.chat.app.dto.ChatSubscriptionReason;

/**
 * 방 topic 구독 허가: 거절 사유, 거절해도 연결은 닫지 않음(프레임만), 허가 중 닫힌 연결, 퇴장 뒤 회수.
 */
class DefaultChatRoomTopicServiceTest {

    private static final Long ROOM_ID = 10L;

    private final ChatRecipientStateReader reader = mock(ChatRecipientStateReader.class);
    private final LocalChatConnections connections = mock(LocalChatConnections.class);
    private final LocalChatSender sender = mock(LocalChatSender.class);
    @SuppressWarnings("unchecked")
    private final LocalRoomSubscriptions subscriptions = new LocalRoomSubscriptions(mock(ObjectProvider.class), 20);
    private final DefaultChatRoomTopicService facade = new DefaultChatRoomTopicService(reader, subscriptions,
            connections, sender);

    @Test
    void 참여_중이고_세대가_맞으면_넣고_성공_프레임은_쓰지_않는다() {
        allowed(1);
        when(connections.isConnected("s-1")).thenReturn(true);

        assertThat(facade.subscribe(ROOM_ID, GUEST_ID, "s-1", "sub", 1)).isTrue();

        assertThat(subscriptions.subscriptions(ROOM_ID)).hasSize(1);
        verifyNoInteractions(sender);
    }

    @Test
    void 세대가_다르면_현재_세대를_실어_거절한다() {
        allowed(2);

        assertThat(facade.subscribe(ROOM_ID, GUEST_ID, "s-1", "sub", 1)).isFalse();

        verify(sender).sendSubscriptionSignal(ROOM_ID, new RoomTarget("s-1", "sub", 1),
                SubscriptionSignal.SUBSCRIPTION_REJECTED, ChatSubscriptionReason.STALE_PARTICIPATION, 2);
        assertThat(subscriptions.subscriptions(ROOM_ID)).isEmpty();
    }

    @Test
    void 방이_없거나_자격이_없거나_참여가_아니면_사유를_가려_거절한다() {
        when(reader.checkSubscription(ROOM_ID, GUEST_ID)).thenReturn(new SubscriptionCheck(false, state(true, true, 1)));
        facade.subscribe(ROOM_ID, GUEST_ID, "s-1", "a", 1);
        when(reader.checkSubscription(ROOM_ID, GUEST_ID)).thenReturn(new SubscriptionCheck(true, state(false, true, 1)));
        facade.subscribe(ROOM_ID, GUEST_ID, "s-1", "b", 1);
        when(reader.checkSubscription(ROOM_ID, GUEST_ID)).thenReturn(new SubscriptionCheck(true, null));
        facade.subscribe(ROOM_ID, GUEST_ID, "s-1", "c", 1);
        when(reader.checkSubscription(ROOM_ID, GUEST_ID)).thenReturn(new SubscriptionCheck(true, state(true, false, 1)));
        facade.subscribe(ROOM_ID, GUEST_ID, "s-1", "d", 1);
        when(reader.checkSubscription(ROOM_ID, GUEST_ID)).thenThrow(new QueryTimeoutException("timeout"));
        facade.subscribe(ROOM_ID, GUEST_ID, "s-1", "e", 1);

        verify(sender).sendSubscriptionSignal(eq(ROOM_ID), eq(new RoomTarget("s-1", "a", 1)), any(),
                eq(ChatSubscriptionReason.ROOM_NOT_FOUND), isNull());
        verify(sender).sendSubscriptionSignal(eq(ROOM_ID), eq(new RoomTarget("s-1", "b", 1)), any(),
                eq(ChatSubscriptionReason.CHAT_NOT_ALLOWED), isNull());
        verify(sender).sendSubscriptionSignal(eq(ROOM_ID), eq(new RoomTarget("s-1", "c", 1)), any(),
                eq(ChatSubscriptionReason.CHAT_NOT_ALLOWED), isNull());
        verify(sender).sendSubscriptionSignal(eq(ROOM_ID), eq(new RoomTarget("s-1", "d", 1)), any(),
                eq(ChatSubscriptionReason.NOT_MEMBER), isNull());
        verify(sender).sendSubscriptionSignal(eq(ROOM_ID), eq(new RoomTarget("s-1", "e", 1)), any(),
                eq(ChatSubscriptionReason.SERVER_BUSY), isNull());
        assertThat(subscriptions.subscriptions(ROOM_ID)).isEmpty();
    }

    @Test
    void 허가하는_사이_연결이_닫혔으면_목록에서_다시_뺀다() {
        allowed(1);
        when(connections.isConnected("s-1")).thenReturn(false);

        assertThat(facade.subscribe(ROOM_ID, GUEST_ID, "s-1", "sub", 1)).isFalse();

        assertThat(subscriptions.subscriptions(ROOM_ID)).isEmpty();
    }

    @Test
    void 퇴장하면_그_회원의_그_방_구독만_회수하고_회수_프레임을_쓴다() {
        allowed(1);
        when(connections.isConnected(any())).thenReturn(true);
        facade.subscribe(ROOM_ID, GUEST_ID, "s-1", "sub", 1);
        facade.subscribe(ROOM_ID, GUEST_ID, "s-2", "sub", 1);
        when(reader.checkSubscription(ROOM_ID, HOST_ID)).thenReturn(new SubscriptionCheck(true, state(true, true, 1)));
        facade.subscribe(ROOM_ID, HOST_ID, "s-3", "sub", 1);

        facade.revokeMember(ROOM_ID, GUEST_ID, 1, ChatSubscriptionReason.LEFT);

        verify(sender, times(2)).sendSubscriptionSignal(eq(ROOM_ID), any(), eq(SubscriptionSignal.SUBSCRIPTION_REVOKED),
                eq(ChatSubscriptionReason.LEFT), isNull());
        assertThat(subscriptions.subscriptions(ROOM_ID)).extracting(LocalRoomSubscriptions.Subscription::userId)
                .containsExactly(HOST_ID);
    }

    /**
     * 요청 A가 세대 1의 퇴장을 커밋 → 요청 B가 재입장(세대 2)하고 새 구독 → 요청 A가 뒤늦게 회수를 실행. 끝난 세대(1) 이하의 구독만
     * 회수하므로 세대 2의 구독은 남는다(회수 프레임도 쓰지 않는다).
     */
    @Test
    void 늦게_실행된_퇴장_회수는_재입장한_새_세대의_구독을_지우지_않는다() {
        when(connections.isConnected(any())).thenReturn(true);
        allowed(1);
        facade.subscribe(ROOM_ID, GUEST_ID, "s-old", "sub", 1);
        // 요청 A: 세대 1 퇴장 커밋(회수는 아직). 요청 B: 재입장해 세대 2로 새 연결에서 구독
        allowed(2);
        facade.subscribe(ROOM_ID, GUEST_ID, "s-new", "sub", 2);

        facade.revokeMember(ROOM_ID, GUEST_ID, 1, ChatSubscriptionReason.LEFT);

        assertThat(subscriptions.subscriptionsOf(ROOM_ID, GUEST_ID))
                .extracting(LocalRoomSubscriptions.Subscription::sessionId).containsExactly("s-new");
        verify(sender).sendSubscriptionSignal(ROOM_ID, new RoomTarget("s-old", "sub", 1),
                SubscriptionSignal.SUBSCRIPTION_REVOKED, ChatSubscriptionReason.LEFT, null);
        verify(sender, never()).sendSubscriptionSignal(eq(ROOM_ID), eq(new RoomTarget("s-new", "sub", 2)), any(),
                any(), any());
    }

    @Test
    void 연결이_닫히면_그_연결의_구독을_모두_빼고_모르는_구독_해제는_무시한다() {
        allowed(1);
        when(connections.isConnected("s-1")).thenReturn(true);
        facade.subscribe(ROOM_ID, GUEST_ID, "s-1", "sub", 1);

        facade.unsubscribe("s-1", "unknown");
        assertThat(subscriptions.subscriptions(ROOM_ID)).hasSize(1);
        facade.sessionClosed("s-1");

        assertThat(subscriptions.subscriptions(ROOM_ID)).isEmpty();
    }

    private void allowed(int currentVersion) {
        when(reader.checkSubscription(ROOM_ID, GUEST_ID)).thenReturn(new SubscriptionCheck(true,
                state(true, true, currentVersion)));
    }

    private static ChatRecipientState state(boolean canChat, boolean active, int version) {
        return new ChatRecipientState(GUEST_ID, canChat, active, version, 1);
    }
}
