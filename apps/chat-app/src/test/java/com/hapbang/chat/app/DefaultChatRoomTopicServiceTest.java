package com.hapbang.chat.app;

import static com.hapbang.chat.fixture.ChatFixture.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;

import com.hapbang.chat.app.ChatRecipientStateReader.SubscriptionCheck;
import com.hapbang.chat.app.dto.ChatRecipientState;
import com.hapbang.chat.app.dto.ChatSubscriptionReason;
import com.hapbang.chat.app.dto.ChatSubscriptionSignal;
import com.hapbang.chat.app.dto.ChatSubscriptionSignalEvent;
import com.hapbang.chat.app.port.LocalChatConnections;

/**
 * 방 topic 구독 허가: 거절 사유, 거절해도 연결은 닫지 않음(프레임만), 허가 중 닫힌 연결, 퇴장 뒤 회수. 거절·회수 프레임은
 * {@link ChatSubscriptionSignalEvent}로 내고, 진입 어댑터가 받은 스레드에서 바로 쓴다.
 */
class DefaultChatRoomTopicServiceTest {

    private static final Long ROOM_ID = 10L;

    private final ChatRecipientStateReader reader = mock(ChatRecipientStateReader.class);
    private final LocalChatConnections connections = mock(LocalChatConnections.class);
    /** 낸 이벤트와, 낼 때 그 방에 남아 있던 구독 수 */
    private final List<Object> events = new ArrayList<>();
    private final List<Integer> subscriptionsAtPublish = new ArrayList<>();
    @SuppressWarnings("unchecked")
    private final LocalRoomSubscriptions subscriptions = new LocalRoomSubscriptions(new AcceptingRoomChannels(), 20);
    private final DefaultChatRoomTopicService facade = new DefaultChatRoomTopicService(reader, subscriptions,
            connections, event -> {
                events.add(event);
                subscriptionsAtPublish.add(subscriptions.subscriptions(ROOM_ID).size());
            });

    @Test
    void 참여_중이고_세대가_맞으면_넣고_성공_프레임은_쓰지_않는다() {
        allowed(1);
        when(connections.isConnected("s-1")).thenReturn(true);

        assertThat(facade.subscribe(ROOM_ID, GUEST_ID, "s-1", "sub", 1)).isTrue();

        assertThat(subscriptions.subscriptions(ROOM_ID)).hasSize(1);
        assertThat(events).isEmpty();
    }

    @Test
    void 세대가_다르면_현재_세대를_실어_거절한다() {
        allowed(2);

        assertThat(facade.subscribe(ROOM_ID, GUEST_ID, "s-1", "sub", 1)).isFalse();

        assertThat(events).containsExactly(new ChatSubscriptionSignalEvent(ROOM_ID, "s-1", "sub", 1,
                ChatSubscriptionSignal.SUBSCRIPTION_REJECTED, ChatSubscriptionReason.STALE_PARTICIPATION, 2));
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

        assertThat(events).containsExactly(
                rejected("a", ChatSubscriptionReason.ROOM_NOT_FOUND),
                rejected("b", ChatSubscriptionReason.CHAT_NOT_ALLOWED),
                rejected("c", ChatSubscriptionReason.CHAT_NOT_ALLOWED),
                rejected("d", ChatSubscriptionReason.NOT_MEMBER),
                rejected("e", ChatSubscriptionReason.SERVER_BUSY));
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
    void 퇴장하면_그_회원의_그_방_구독만_회수하고_구독을_뺀_직후마다_회수_이벤트를_낸다() {
        allowed(1);
        when(connections.isConnected(any())).thenReturn(true);
        facade.subscribe(ROOM_ID, GUEST_ID, "s-1", "sub", 1);
        facade.subscribe(ROOM_ID, GUEST_ID, "s-2", "sub", 1);
        when(reader.checkSubscription(ROOM_ID, HOST_ID)).thenReturn(new SubscriptionCheck(true, state(true, true, 1)));
        facade.subscribe(ROOM_ID, HOST_ID, "s-3", "sub", 1);

        facade.revokeMember(ROOM_ID, GUEST_ID, 1, ChatSubscriptionReason.LEFT);

        assertThat(events).containsExactly(
                new ChatSubscriptionSignalEvent(ROOM_ID, "s-1", "sub", 1, ChatSubscriptionSignal.SUBSCRIPTION_REVOKED,
                        ChatSubscriptionReason.LEFT, null),
                new ChatSubscriptionSignalEvent(ROOM_ID, "s-2", "sub", 1, ChatSubscriptionSignal.SUBSCRIPTION_REVOKED,
                        ChatSubscriptionReason.LEFT, null));
        // 각 이벤트는 그 구독을 뺀 직후에 낸다(3개 → 2개, 2개 → 1개)
        assertThat(subscriptionsAtPublish).containsExactly(2, 1);
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
        assertThat(events).containsExactly(new ChatSubscriptionSignalEvent(ROOM_ID, "s-old", "sub", 1,
                ChatSubscriptionSignal.SUBSCRIPTION_REVOKED, ChatSubscriptionReason.LEFT, null));
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

    private static ChatSubscriptionSignalEvent rejected(String subscriptionId, ChatSubscriptionReason reason) {
        return new ChatSubscriptionSignalEvent(ROOM_ID, "s-1", subscriptionId, 1,
                ChatSubscriptionSignal.SUBSCRIPTION_REJECTED, reason, null);
    }

    private void allowed(int currentVersion) {
        when(reader.checkSubscription(ROOM_ID, GUEST_ID)).thenReturn(new SubscriptionCheck(true,
                state(true, true, currentVersion)));
    }

    private static ChatRecipientState state(boolean canChat, boolean active, int version) {
        return new ChatRecipientState(GUEST_ID, canChat, active, version, 1);
    }
}
