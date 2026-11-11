package com.hapbang.chat.app;

import static com.hapbang.chat.fixture.ChatFixture.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import com.hapbang.chat.app.ChatRecipientStateReader.RoomMembership;
import com.hapbang.chat.app.ChatUnreadMemberCounter.RecentCounts;
import com.hapbang.chat.app.LocalChatSender.RoomTarget;
import com.hapbang.chat.app.LocalRoomSubscriptions.Subscription;
import com.hapbang.chat.app.dto.ChatReadCounts;
import com.hapbang.chat.app.dto.ChatRecipientState;
import com.hapbang.chat.app.dto.ReadChangedFanout;

/**
 * 안 읽은 사람 수 갱신 묶음(설계 Q36): 로컬 구독이 있는 방만 표시, 방별로 합쳐 묶음마다 집계 한 번, 현재 세대·활성·채팅 가능한
 * 구독에만, 구독 회원의 가시 범위로 자르기, 0인 순번은 빼기, 같은 범위 구독은 payload 한 번, 참여 변경 버전으로 참여 캐시 다시 읽기,
 * 조회 실패 시 그 묶음만 버리기.
 */
class DefaultChatReadCountNotifierTest {

    private static final Long ROOM_ID = 10L;
    private static final Long GUEST_A = 11L;
    private static final long VERSION = 1_700_000_000_000_000L;

    private final ChatRecipientStateReader reader = mock(ChatRecipientStateReader.class);
    private final ChatUnreadMemberCounter counter = mock(ChatUnreadMemberCounter.class);
    private final LocalChatSender sender = mock(LocalChatSender.class);
    private final LocalRoomSubscriptions subscriptions = new LocalRoomSubscriptions(noChannels(), 20);
    private final ChatRoomMembershipCache cache = new ChatRoomMembershipCache(reader, 100, Duration.ofHours(1));
    private final ChatReadCountNotifier notifier = new DefaultChatReadCountNotifier(subscriptions, cache, counter,
            sender, 100);

    @Test
    void 그_방을_연_로컬_구독이_없으면_표시하지_않고_세지도_않는다() {
        notifier.record(new ReadChangedFanout(ROOM_ID, 0));
        notifier.flush();

        verifyNoInteractions(counter, sender, reader);
    }

    @Test
    void 같은_방의_신호는_묶음마다_한_번만_세고_마지막_100개_구간으로_센다() {
        when(reader.findRoomMembership(ROOM_ID))
                .thenReturn(membership(1, member(HOST_ID, 1, 1), member(GUEST_ID, 1, 1)));
        when(counter.countRecent(eq(ROOM_ID), eq(100), any()))
                .thenReturn(Optional.of(counts(1, 3, Map.of(1L, 0, 3L, 1))));
        subscriptions.register(new Subscription("h-1", "sub", ROOM_ID, HOST_ID, 1));

        notifier.record(new ReadChangedFanout(ROOM_ID, 0));
        notifier.record(new ReadChangedFanout(ROOM_ID, 0));
        notifier.record(new ReadChangedFanout(ROOM_ID, 0));
        notifier.flush();

        verify(counter, times(1)).countRecent(eq(ROOM_ID), eq(100), any());
        verify(sender).sendReadCounts(ROOM_ID, new ChatReadCounts(ROOM_ID, VERSION, 1, 3,
                List.of(new ChatReadCounts.Count(3, 1))), List.of(new RoomTarget("h-1", "sub", 1)));

        notifier.flush();
        verifyNoMoreInteractions(counter, sender);
    }

    @Test
    void 현재_세대이고_참여_중이고_채팅할_수_있는_구독에만_보낸다() {
        Long suspended = 12L;
        when(reader.findRoomMembership(ROOM_ID)).thenReturn(membership(3, member(HOST_ID, 1, 1),
                member(GUEST_ID, 2, 1), new ChatRecipientState(suspended, false, true, 1, 1)));
        when(counter.countRecent(eq(ROOM_ID), eq(100), any()))
                .thenReturn(Optional.of(counts(1, 2, Map.of(2L, 1))));
        subscriptions.register(new Subscription("h-1", "sub", ROOM_ID, HOST_ID, 1));
        // 재입장 전 세대의 구독, 나간 회원의 구독, 채팅할 수 없는 회원의 구독
        subscriptions.register(new Subscription("g-1", "sub", ROOM_ID, GUEST_ID, 1));
        subscriptions.register(new Subscription("a-1", "sub", ROOM_ID, GUEST_A, 1));
        subscriptions.register(new Subscription("s-1", "sub", ROOM_ID, suspended, 1));

        notifier.record(new ReadChangedFanout(ROOM_ID, 0));
        notifier.flush();

        ArgumentCaptor<List<RoomTarget>> targets = targetsCaptor();
        verify(sender).sendReadCounts(eq(ROOM_ID), any(), targets.capture());
        assertThat(targets.getValue()).containsExactly(new RoomTarget("h-1", "sub", 1));
        // 맞지 않는 구독의 회수는 메시지 전달이 맡는다
        assertThat(subscriptions.subscriptions(ROOM_ID)).hasSize(4);
    }

    @Test
    void 구독_회원의_가시_범위_앞_순번은_싣지_않고_같은_범위의_구독끼리_payload를_한_번만_만든다() {
        // 게스트 A는 순번 3부터 보인다(늦게 초대됨). 구간에 없는 순번(0인 메시지)은 0이다
        when(reader.findRoomMembership(ROOM_ID)).thenReturn(membership(2, member(HOST_ID, 1, 1),
                member(GUEST_ID, 1, 1), member(GUEST_A, 1, 3)));
        when(counter.countRecent(eq(ROOM_ID), eq(100), any()))
                .thenReturn(Optional.of(counts(1, 4,
                Map.of(1L, 1, 2L, 2, 4L, 1))));
        subscriptions.register(new Subscription("h-1", "sub", ROOM_ID, HOST_ID, 1));
        subscriptions.register(new Subscription("g-1", "sub", ROOM_ID, GUEST_ID, 1));
        subscriptions.register(new Subscription("a-1", "sub", ROOM_ID, GUEST_A, 1));

        notifier.record(new ReadChangedFanout(ROOM_ID, 0));
        notifier.flush();

        Map<Long, ChatReadCounts> byFrom = new HashMap<>();
        Map<Long, List<RoomTarget>> targetsByFrom = new HashMap<>();
        ArgumentCaptor<ChatReadCounts> frames = ArgumentCaptor.forClass(ChatReadCounts.class);
        ArgumentCaptor<List<RoomTarget>> targets = targetsCaptor();
        verify(sender, times(2)).sendReadCounts(eq(ROOM_ID), frames.capture(), targets.capture());
        for (int i = 0; i < 2; i++) {
            byFrom.put(frames.getAllValues().get(i).fromSequence(), frames.getAllValues().get(i));
            targetsByFrom.put(frames.getAllValues().get(i).fromSequence(), targets.getAllValues().get(i));
        }
        assertThat(byFrom.get(1L).counts()).containsExactly(new ChatReadCounts.Count(1, 1),
                new ChatReadCounts.Count(2, 2), new ChatReadCounts.Count(4, 1));
        assertThat(targetsByFrom.get(1L)).extracting(RoomTarget::sessionId).containsExactly("h-1", "g-1");
        assertThat(byFrom.get(3L).counts()).containsExactly(new ChatReadCounts.Count(4, 1));
        assertThat(byFrom.get(3L).toSequence()).isEqualTo(4);
        assertThat(targetsByFrom.get(3L)).extracting(RoomTarget::sessionId).containsExactly("a-1");
    }

    @Test
    void 가시_범위가_센_구간_뒤인_구독에는_보내지_않는다() {
        when(reader.findRoomMembership(ROOM_ID)).thenReturn(membership(2, member(HOST_ID, 1, 1),
                member(GUEST_A, 1, 5)));
        when(counter.countRecent(eq(ROOM_ID), eq(100), any()))
                .thenReturn(Optional.of(counts(1, 4, Map.of(4L, 1))));
        subscriptions.register(new Subscription("a-1", "sub", ROOM_ID, GUEST_A, 1));

        notifier.record(new ReadChangedFanout(ROOM_ID, 0));
        notifier.flush();

        verify(counter).countRecent(eq(ROOM_ID), eq(100), any());
        verifyNoInteractions(sender);
    }

    @Test
    void 참여_변경_신호의_수신자_집합_버전이_캐시보다_새로우면_참여를_다시_읽는다() {
        when(reader.findRoomMembership(ROOM_ID))
                .thenReturn(membership(1, member(HOST_ID, 1, 1), member(GUEST_ID, 1, 1)))
                .thenReturn(membership(2, member(HOST_ID, 1, 1)));
        when(counter.countRecent(eq(ROOM_ID), eq(100), any()))
                .thenReturn(Optional.of(counts(1, 2, Map.of(2L, 1))));
        subscriptions.register(new Subscription("h-1", "sub", ROOM_ID, HOST_ID, 1));
        subscriptions.register(new Subscription("g-1", "sub", ROOM_ID, GUEST_ID, 1));
        notifier.record(new ReadChangedFanout(ROOM_ID, 0));
        notifier.flush();
        clearInvocations(sender);

        // 게스트 강퇴(버전 2)와 읽음 신호가 한 묶음에 모였다. 가장 높은 버전으로 참여를 맞춘다
        notifier.record(new ReadChangedFanout(ROOM_ID, 0));
        notifier.record(new ReadChangedFanout(ROOM_ID, 2));
        notifier.record(new ReadChangedFanout(ROOM_ID, 0));
        notifier.flush();

        verify(reader, times(2)).findRoomMembership(ROOM_ID);
        ArgumentCaptor<List<RoomTarget>> targets = targetsCaptor();
        verify(sender).sendReadCounts(eq(ROOM_ID), any(), targets.capture());
        assertThat(targets.getValue()).extracting(RoomTarget::sessionId).containsExactly("h-1");
    }

    @Test
    void 캐시를_읽은_뒤_강퇴와_새_메시지가_커밋돼도_센_스냅샷의_버전으로_참여를_다시_읽어_강퇴된_회원에게_보내지_않는다() {
        // 캐시는 강퇴 전(버전 1)이다. 그 뒤 게스트 강퇴(버전 2)와 새 메시지(순번 3)가 커밋되고, 집계가 그 스냅샷을 센다
        when(reader.findRoomMembership(ROOM_ID))
                .thenReturn(membership(1, member(HOST_ID, 1, 1), member(GUEST_ID, 1, 1)))
                .thenReturn(membership(2, member(HOST_ID, 1, 1)));
        cache.get(ROOM_ID, 1);
        when(counter.countRecent(eq(ROOM_ID), eq(100), any()))
                .thenReturn(Optional.of(counts(1, 3, 2, Map.of(3L, 1))));
        subscriptions.register(new Subscription("h-1", "sub", ROOM_ID, HOST_ID, 1));
        subscriptions.register(new Subscription("g-1", "sub", ROOM_ID, GUEST_ID, 1));

        notifier.record(new ReadChangedFanout(ROOM_ID, 0));
        notifier.flush();

        // 집계를 먼저 하고, 참여는 그 스냅샷의 버전 이상으로 읽는다
        var order = inOrder(counter, reader);
        order.verify(counter).countRecent(eq(ROOM_ID), eq(100), any());
        order.verify(reader).findRoomMembership(ROOM_ID);
        ArgumentCaptor<List<RoomTarget>> targets = targetsCaptor();
        verify(sender).sendReadCounts(eq(ROOM_ID), any(), targets.capture());
        assertThat(targets.getValue()).extracting(RoomTarget::sessionId).containsExactly("h-1");
    }

    @Test
    void 집계에_실패하면_그_묶음만_버리고_다음_신호는_다시_센다() {
        when(reader.findRoomMembership(ROOM_ID)).thenReturn(membership(1, member(HOST_ID, 1, 1)));
        when(counter.countRecent(eq(ROOM_ID), eq(100), any()))
                .thenThrow(new IllegalStateException("DB 장애"))
                .thenReturn(Optional.of(counts(1, 1, Map.of())));
        subscriptions.register(new Subscription("h-1", "sub", ROOM_ID, HOST_ID, 1));

        notifier.record(new ReadChangedFanout(ROOM_ID, 0));
        assertThatCode(notifier::flush).doesNotThrowAnyException();
        verifyNoInteractions(sender);

        notifier.record(new ReadChangedFanout(ROOM_ID, 0));
        notifier.flush();
        verify(sender).sendReadCounts(ROOM_ID, new ChatReadCounts(ROOM_ID, VERSION, 1, 1, List.of()),
                List.of(new RoomTarget("h-1", "sub", 1)));
    }

    @Test
    void 없거나_볼_수_없게_된_방이면_세지_않은_것으로_보고_보내지_않는다() {
        when(reader.findRoomMembership(ROOM_ID)).thenReturn(membership(1, member(HOST_ID, 1, 1)));
        when(counter.countRecent(eq(ROOM_ID), eq(100), any()))
                .thenReturn(Optional.empty());
        subscriptions.register(new Subscription("h-1", "sub", ROOM_ID, HOST_ID, 1));

        notifier.record(new ReadChangedFanout(ROOM_ID, 0));
        notifier.flush();

        // 집계가 빈 값이면 참여도 읽지 않고 아무에게도 보내지 않는다
        verifyNoInteractions(sender, reader);
    }

    @Test
    void 구간_크기는_1_이상이어야_한다() {
        assertThatThrownBy(() -> new DefaultChatReadCountNotifier(subscriptions, cache, counter, sender, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static RecentCounts counts(long from, long to, Map<Long, Integer> bySequence) {
        return counts(from, to, 0, bySequence);
    }

    private static RecentCounts counts(long from, long to, int recipientSetVersion, Map<Long, Integer> bySequence) {
        return new RecentCounts(from, to, recipientSetVersion, bySequence, VERSION);
    }

    private static RoomMembership membership(int setVersion, ChatRecipientState... members) {
        Map<Long, ChatRecipientState> byUser = new HashMap<>();
        for (ChatRecipientState member : members) {
            byUser.put(member.userId(), member);
        }
        return new RoomMembership(setVersion, byUser);
    }

    private static ChatRecipientState member(Long userId, int participationVersion, long visibleFromSequence) {
        return new ChatRecipientState(userId, true, true, participationVersion, visibleFromSequence);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ArgumentCaptor<List<RoomTarget>> targetsCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(List.class);
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<RoomChannelSubscriptions> noChannels() {
        ObjectProvider<RoomChannelSubscriptions> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return provider;
    }
}
