package com.hapbang.chat.app;

import static com.hapbang.chat.fixture.ChatFixture.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.QueryTimeoutException;

import com.hapbang.chat.app.ChatRecipientStateReader.RoomMembership;
import com.hapbang.chat.app.LocalChatSender.RoomTarget;
import com.hapbang.chat.app.LocalChatSender.SubscriptionSignal;
import com.hapbang.chat.app.LocalRoomSubscriptions.Subscription;
import com.hapbang.chat.app.dto.ChatMessageResponse;
import com.hapbang.chat.app.dto.ChatRecipientState;
import com.hapbang.chat.app.dto.ChatResyncReason;
import com.hapbang.chat.app.dto.ChatSubscriptionReason;
import com.hapbang.chat.app.dto.MessageFanout;
import com.hapbang.chat.app.dto.RoomActivityFanout;
import com.hapbang.chat.domain.ChatMessageType;
import com.hapbang.chat.domain.ChatNotifyRule;

/**
 * 방 topic 전달의 버전 확인 규칙(설계 4.2): 구독 세대·활성 참여·가시 범위·자격, 퇴장·세대 변경 뒤 회수, 메시지마다 DB를 읽지 않음.
 */
class DefaultChatFanoutDeliveryServiceTest {

    private static final Long ROOM_ID = 10L;

    private final LocalChatConnections connections = mock(LocalChatConnections.class);
    private final ChatRecipientStateReader reader = mock(ChatRecipientStateReader.class);
    private final LocalChatSender sender = mock(LocalChatSender.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<RoomChannelSubscriptions> noChannels = mock(ObjectProvider.class);
    private final LocalRoomSubscriptions subscriptions = new LocalRoomSubscriptions(noChannels, 20);
    private final ChatRoomMembershipCache cache = new ChatRoomMembershipCache(reader, 100, Duration.ofHours(1));
    private final DefaultChatFanoutDeliveryService service = new DefaultChatFanoutDeliveryService(connections,
            subscriptions, cache, sender);

    private final ChatMessageResponse message = new ChatMessageResponse(100L, ROOM_ID, 5L, HOST_ID,
            ChatMessageType.TEXT, "안녕하세요", NOW, null, null, null, null);

    @BeforeEach
    void setUp() {
        when(connections.sessionIdsOf(any())).thenReturn(Set.of());
    }

    @Test
    void 구독한_세션에만_쓰고_보낸_사람_세션의_프레임에만_전송_ID를_싣는다() {
        subscribe("s-host", HOST_ID, 1);
        subscribe("s-guest", GUEST_ID, 2);
        when(reader.findRoomMembership(ROOM_ID)).thenReturn(membership(1, member(HOST_ID, 1), member(GUEST_ID, 2)));

        service.deliver(fanout(1, "c-1"));

        verify(sender).sendRoomMessage(ROOM_ID, message, List.of(new RoomTarget("s-guest", "sub-s-guest", 2)));
        verify(sender).sendRoomMessage(ROOM_ID, message.withClientMessageId("c-1"),
                List.of(new RoomTarget("s-host", "sub-s-host", 1)));
        verify(sender, never()).sendMessage(any(), any(), anyInt());
    }

    @Test
    void 전송_결과_확인은_방_구독과_관계없이_보낸_사람의_로컬_연결에_세대_헤더와_함께_보낸다() {
        when(reader.findRoomMembership(ROOM_ID)).thenReturn(membership(1, member(HOST_ID, 3)));
        when(connections.sessionIdsOf(HOST_ID)).thenReturn(Set.of("s-host"));

        service.confirmToSender(RoomActivityFanout.from(fanout(1, "c-1")));

        verify(sender).sendMessage(HOST_ID, message.withClientMessageId("c-1"), 3);
    }

    @Test
    void 전송_ID_없이_보낸_일반_메시지도_전송_ID_없는_결과_확인을_보낸다() {
        when(reader.findRoomMembership(ROOM_ID)).thenReturn(membership(1, member(HOST_ID, 3)));
        when(connections.sessionIdsOf(HOST_ID)).thenReturn(Set.of("s-host"));

        service.confirmToSender(RoomActivityFanout.from(fanout(1, null)));

        verify(sender).sendMessage(HOST_ID, message, 3);
    }

    @Test
    void 보낸_사람의_로컬_연결이_없거나_회원이_보낸_메시지가_아니면_결과_확인을_보내지_않고_DB도_읽지_않는다() {
        service.confirmToSender(RoomActivityFanout.from(fanout(1, "c-1")));
        when(connections.sessionIdsOf(HOST_ID)).thenReturn(Set.of("s-host"));
        for (ChatMessageType type : List.of(ChatMessageType.SYSTEM, ChatMessageType.NOTIFICATION)) {
            ChatMessageResponse notice = new ChatMessageResponse(101L, ROOM_ID, 6L, HOST_ID, type, "안내", NOW, null,
                    null, null, null);
            service.confirmToSender(new RoomActivityFanout(UUID.randomUUID(), ROOM_ID, 1, 6L,
                    ChatNotifyRule.RECIPIENTS_EXCEPT_SENDER, HOST_ID, notice, null));
        }

        verifyNoInteractions(reader, sender);
    }

    @Test
    void 참여가_바뀌지_않는_동안은_메시지마다_DB를_읽지_않는다() {
        subscribe("s-guest", GUEST_ID, 1);
        when(reader.findRoomMembership(ROOM_ID)).thenReturn(membership(3, member(GUEST_ID, 1)));

        for (int i = 0; i < 5; i++) {
            service.deliver(fanout(3, null));
        }

        verify(reader, times(1)).findRoomMembership(ROOM_ID);
        verify(sender, times(5)).sendRoomMessage(eq(ROOM_ID), any(), any());
    }

    @Test
    void 퇴장_뒤_저장된_메시지는_구독이_남아_있어도_쓰지_않고_구독을_회수한다() {
        subscribe("s-guest", GUEST_ID, 1);
        // 버전 1에서 읽은 캐시: 아직 참여 중
        when(reader.findRoomMembership(ROOM_ID)).thenReturn(membership(1, member(HOST_ID, 1), member(GUEST_ID, 1)));
        service.deliver(fanout(1, null));
        clearInvocations(sender);
        // 버전 2의 퇴장 뒤 저장된 메시지: 캐시가 버전 2보다 낮으므로 다시 읽어 퇴장을 본다
        when(reader.findRoomMembership(ROOM_ID)).thenReturn(membership(2, member(HOST_ID, 1)));

        service.deliver(fanout(2, null));

        verify(sender, never()).sendRoomMessage(any(), any(), any());
        verify(sender).sendSubscriptionSignal(ROOM_ID, new RoomTarget("s-guest", "sub-s-guest", 1),
                SubscriptionSignal.SUBSCRIPTION_REVOKED, ChatSubscriptionReason.LEFT, null);
        assertThat(subscriptions.subscriptions(ROOM_ID)).isEmpty();
    }

    @Test
    void 늦게_등록된_구독도_방_캐시의_버전으로_판단해_퇴장을_놓치지_않는다() {
        // 다른 구독의 재조회로 방 캐시는 이미 버전 10(그 회원은 버전 9에서 퇴장)
        subscribe("s-host", HOST_ID, 1);
        when(reader.findRoomMembership(ROOM_ID)).thenReturn(membership(10, member(HOST_ID, 1)));
        service.deliver(fanout(10, null));
        // 느린 SUBSCRIBE가 버전 8의 상태(참여 중)로 허가된 뒤 늦게 등록된다
        subscribe("s-guest", GUEST_ID, 1);
        clearInvocations(sender);

        service.deliver(fanout(10, null));

        verify(sender).sendRoomMessage(ROOM_ID, message, List.of(new RoomTarget("s-host", "sub-s-host", 1)));
        verify(sender).sendSubscriptionSignal(eq(ROOM_ID), eq(new RoomTarget("s-guest", "sub-s-guest", 1)),
                eq(SubscriptionSignal.SUBSCRIPTION_REVOKED), eq(ChatSubscriptionReason.LEFT), isNull());
    }

    @Test
    void 재입장으로_세대가_바뀌면_옛_세대_구독에_쓰지_않고_현재_세대를_알려_회수한다() {
        subscribe("s-guest", GUEST_ID, 1);
        when(reader.findRoomMembership(ROOM_ID)).thenReturn(membership(3, member(GUEST_ID, 2)));

        service.deliver(fanout(3, null));

        verify(sender, never()).sendRoomMessage(any(), any(), any());
        verify(sender).sendSubscriptionSignal(ROOM_ID, new RoomTarget("s-guest", "sub-s-guest", 1),
                SubscriptionSignal.SUBSCRIPTION_REVOKED, ChatSubscriptionReason.STALE_PARTICIPATION, 2);
    }

    /**
     * 캐시가 재입장 전에 읽혔다(버전 2: 게스트 퇴장). 게스트가 재입장(버전 3, 세대 2)하고 새 세대로 구독한 뒤, 재입장 전 메시지(버전 1)가
     * 늦게 온다. 캐시 버전이 메시지보다 높아 다시 읽지 않으면 새 구독을 옛 상태(퇴장)로 보고 회수하게 된다. 회수 전에 DB를 다시 읽으므로
     * 새 세대 구독은 남고, 그 메시지는 가시 범위 밖이라 쓰지 않는다.
     */
    @Test
    void 캐시가_재입장_전에_읽혔어도_재입장한_새_세대의_구독을_회수하지_않는다() {
        subscribe("s-host", HOST_ID, 1);
        when(reader.findRoomMembership(ROOM_ID))
                .thenReturn(membership(2, member(HOST_ID, 1)))
                .thenReturn(membership(3, member(HOST_ID, 1), new ChatRecipientState(GUEST_ID, true, true, 2, 9)));
        service.deliver(fanout(2, null));
        subscribe("s-guest", GUEST_ID, 2);
        clearInvocations(sender);

        service.deliver(fanout(1, null));

        verify(sender, never()).sendSubscriptionSignal(any(), any(), any(), any(), any());
        verify(sender).sendRoomMessage(ROOM_ID, message, List.of(new RoomTarget("s-host", "sub-s-host", 1)));
        assertThat(subscriptions.subscriptionsOf(ROOM_ID, GUEST_ID)).hasSize(1);
    }

    @Test
    void 가시_범위_밖이거나_채팅할_수_없는_회원은_이_메시지만_건너뛰고_구독은_둔다() {
        subscribe("s-guest", GUEST_ID, 1);
        subscribe("s-admin", ADMIN_ID, 1);
        Map<Long, ChatRecipientState> members = new HashMap<>();
        members.put(GUEST_ID, new ChatRecipientState(GUEST_ID, true, true, 1, 6));
        members.put(ADMIN_ID, new ChatRecipientState(ADMIN_ID, false, true, 1, 1));
        when(reader.findRoomMembership(ROOM_ID)).thenReturn(new RoomMembership(1, members));

        service.deliver(fanout(1, null));

        verify(sender, never()).sendRoomMessage(any(), any(), any());
        verify(sender, never()).sendSubscriptionSignal(any(), any(), any(), any(), any());
        assertThat(subscriptions.subscriptions(ROOM_ID)).hasSize(2);
    }

    @Test
    void 참여_상태_조회에_실패하면_아무에게도_쓰지_않고_구독_회원에게_재동기화를_알린다() {
        subscribe("s-guest", GUEST_ID, 1);
        when(reader.findRoomMembership(ROOM_ID)).thenThrow(new QueryTimeoutException("timeout"));

        service.deliver(fanout(1, null));

        verify(sender, never()).sendRoomMessage(any(), any(), any());
        verify(sender).sendResync(eq(GUEST_ID), eq(ChatResyncReason.DELIVERY_DROPPED), any());
    }

    @Test
    void 방_구독이_없으면_DB를_읽지_않는다() {
        service.deliver(fanout(1, "c-1"));

        verifyNoInteractions(reader, sender);
    }

    @Test
    void 방_ID와_메시지의_방이_다르면_버린다() {
        subscribe("s-guest", GUEST_ID, 1);

        service.deliver(new MessageFanout(UUID.randomUUID(), MessageFanout.Type.MESSAGE, 99L, 1, message,
                ChatNotifyRule.RECIPIENTS_EXCEPT_SENDER, null));

        verifyNoInteractions(reader, sender);
    }

    @Test
    void 재동기화_신호는_모든_로컬_연결_회원에게_보낸다() {
        when(connections.connectedUserIds()).thenReturn(Set.of(HOST_ID, GUEST_ID));

        service.resyncAll(ChatResyncReason.REALTIME_RESUBSCRIBED);

        verify(sender).sendResync(eq(HOST_ID), eq(ChatResyncReason.REALTIME_RESUBSCRIBED), any());
        verify(sender).sendResync(eq(GUEST_ID), eq(ChatResyncReason.REALTIME_RESUBSCRIBED), any());
    }

    @Test
    void 버린_방_메시지의_재동기화는_그_방_구독_회원에게_한_번씩_보낸다() {
        subscribe("s-guest-1", GUEST_ID, 1);
        subscribe("s-guest-2", GUEST_ID, 1);

        service.resyncRoomSubscribers(ROOM_ID, ChatResyncReason.DELIVERY_DROPPED);

        verify(sender, times(1)).sendResync(eq(GUEST_ID), eq(ChatResyncReason.DELIVERY_DROPPED), any());
        verifyNoMoreInteractions(sender);
    }

    private void subscribe(String sessionId, Long userId, int participationVersion) {
        assertThat(subscriptions.register(new Subscription(sessionId, "sub-" + sessionId, ROOM_ID, userId,
                participationVersion))).isEqualTo(LocalRoomSubscriptions.Registered.OK);
    }

    private MessageFanout fanout(int setVersion, String senderClientMessageId) {
        return new MessageFanout(UUID.randomUUID(), MessageFanout.Type.MESSAGE, ROOM_ID, setVersion, message,
                ChatNotifyRule.RECIPIENTS_EXCEPT_SENDER, senderClientMessageId);
    }

    private static ChatRecipientState member(Long userId, int participationVersion) {
        return new ChatRecipientState(userId, true, true, participationVersion, 1);
    }

    private static RoomMembership membership(int setVersion, ChatRecipientState... members) {
        Map<Long, ChatRecipientState> map = new HashMap<>();
        for (ChatRecipientState member : members) {
            map.put(member.userId(), member);
        }
        return new RoomMembership(setVersion, map);
    }
}
