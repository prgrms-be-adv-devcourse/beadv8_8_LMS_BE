package com.hapbang.chat.app;

import static com.hapbang.chat.fixture.ChatFixture.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Function;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import com.hapbang.chat.app.ChatRecipientStateReader.RoomMembership;
import com.hapbang.chat.app.LocalRoomSubscriptions.Subscription;
import com.hapbang.chat.app.dto.ChatMessageResponse;
import com.hapbang.chat.app.dto.ChatRecipientState;
import com.hapbang.chat.app.dto.ChatRoomActivity;
import com.hapbang.chat.app.dto.RoomActivityFanout;
import com.hapbang.chat.domain.ChatMessageType;
import com.hapbang.chat.domain.ChatNotifyRule;

/**
 * 새 메시지 알림 묶음(설계 Q15·Q21): 방별 묶음, 회원별 셈(자기 메시지·가시 범위 밖 제외), 구독 세션 제외, 접속 정보, 퇴장 회원 제외,
 * 1000명 방에서 묶음마다 DB 조회 없음·접속 확인 1회, 기록과 묶음 교체가 겹쳐도 메시지를 잃지 않음.
 */
class DefaultChatRoomActivityNotifierTest {

    private static final Long ROOM_ID = 10L;

    private final ChatRecipientStateReader reader = mock(ChatRecipientStateReader.class);
    private final LocalChatConnections connections = mock(LocalChatConnections.class);
    private final ChatPresence presence = mock(ChatPresence.class);
    private final LocalChatSender sender = mock(LocalChatSender.class);
    private final LocalRoomSubscriptions subscriptions = new LocalRoomSubscriptions(provider(null), 20);
    private final ChatRoomMembershipCache cache = new ChatRoomMembershipCache(reader, 100, Duration.ofHours(1));
    private final ChatRoomActivityNotifier notifier = new DefaultChatRoomActivityNotifier(cache, connections, subscriptions,
            provider(presence), sender, 1000);

    @BeforeEach
    void setUp() {
        when(connections.connectedUserIds()).thenReturn(Set.of(HOST_ID, GUEST_ID));
        when(connections.connectedAmong(any())).thenAnswer(invocation -> {
            Set<Long> local = new HashSet<>(invocation.<Collection<Long>>getArgument(0));
            local.retainAll(Set.of(HOST_ID, GUEST_ID));
            return local;
        });
        when(presence.onlineAmong(any())).thenAnswer(invocation -> new HashSet<>(invocation.<Collection<Long>>getArgument(0)));
        when(connections.sessionIdsOf(HOST_ID)).thenReturn(Set.of("h-1"));
        when(connections.sessionIdsOf(GUEST_ID)).thenReturn(Set.of("g-open", "g-list"));
    }

    @Test
    void 방별로_묶어_한_번_보내고_구독한_세션과_자기_메시지만_있는_회원은_뺀다() {
        when(reader.findRoomMembership(ROOM_ID)).thenReturn(membership(1, member(HOST_ID, 1, 1), member(GUEST_ID, 1, 1)));
        subscriptions.register(new Subscription("g-open", "sub", ROOM_ID, GUEST_ID, 1));

        notifier.record(activity(1, 1, HOST_ID));
        notifier.record(activity(2, 1, HOST_ID));
        notifier.record(activity(3, 1, HOST_ID));
        notifier.flush();

        ArgumentCaptor<ChatRoomActivity> captor = ArgumentCaptor.forClass(ChatRoomActivity.class);
        verify(sender).sendRoomActivity(eq(GUEST_ID), eq("g-list"), captor.capture());
        assertThat(captor.getValue()).isEqualTo(new ChatRoomActivity(ROOM_ID, 1, 3, 3, message(3, HOST_ID)));
        verify(sender, never()).sendRoomActivity(eq(GUEST_ID), eq("g-open"), any());
        verify(sender, never()).sendRoomActivity(eq(HOST_ID), any(), any());

        notifier.flush();
        verifyNoMoreInteractions(sender);
    }

    @Test
    void 회원마다_자기가_보낸_메시지와_가시_범위_밖_메시지는_세지_않는다() {
        // 게스트는 순번 3부터 보인다(재입장)
        when(reader.findRoomMembership(ROOM_ID)).thenReturn(membership(2, member(HOST_ID, 1, 1), member(GUEST_ID, 2, 3)));

        notifier.record(activity(1, 1, HOST_ID));
        notifier.record(activity(2, 1, GUEST_ID));
        notifier.record(activity(3, 2, HOST_ID));
        notifier.record(activity(4, 2, GUEST_ID));
        notifier.flush();

        ArgumentCaptor<ChatRoomActivity> toGuest = ArgumentCaptor.forClass(ChatRoomActivity.class);
        verify(sender, times(2)).sendRoomActivity(eq(GUEST_ID), any(), toGuest.capture());
        assertThat(toGuest.getValue().messageCount()).isEqualTo(1);
        assertThat(toGuest.getValue().participationVersion()).isEqualTo(2);
        ArgumentCaptor<ChatRoomActivity> toHost = ArgumentCaptor.forClass(ChatRoomActivity.class);
        verify(sender).sendRoomActivity(eq(HOST_ID), eq("h-1"), toHost.capture());
        assertThat(toHost.getValue().messageCount()).isEqualTo(2);
    }

    @Test
    void 묶음_마지막_메시지_버전으로_참여를_다시_읽어_그_전에_나간_회원에게_보내지_않는다() {
        when(reader.findRoomMembership(ROOM_ID))
                .thenReturn(membership(1, member(HOST_ID, 1, 1), member(GUEST_ID, 1, 1)))
                .thenReturn(membership(2, member(HOST_ID, 1, 1)));
        notifier.record(activity(1, 1, HOST_ID));
        notifier.flush();
        clearInvocations(sender);

        // 게스트 퇴장(버전 2) 뒤 저장된 메시지
        notifier.record(activity(3, 2, HOST_ID));
        notifier.flush();

        verify(sender, never()).sendRoomActivity(eq(GUEST_ID), any(), any());
        verify(reader, times(2)).findRoomMembership(ROOM_ID);
    }

    @Test
    void 접속_중이_아닌_회원과_알림_대상이_아닌_메시지는_보내지_않는다() {
        when(reader.findRoomMembership(ROOM_ID)).thenReturn(membership(1, member(HOST_ID, 1, 1), member(GUEST_ID, 1, 1)));
        doReturn(Set.of()).when(presence).onlineAmong(any());

        notifier.record(activity(1, 1, HOST_ID));
        notifier.record(new RoomActivityFanout(UUID.randomUUID(), ROOM_ID, 1, 2, ChatNotifyRule.NONE, HOST_ID,
                message(2, HOST_ID), null));
        notifier.flush();

        verifyNoInteractions(sender);
    }

    @Test
    void 천명_방에서_참여가_고정된_동안_묶음마다_DB를_읽지_않고_접속_확인은_한_번이다() {
        Map<Long, ChatRecipientState> members = new HashMap<>();
        for (long id = 1; id <= 1000; id++) {
            members.put(id, member(id, 1, 1));
        }
        Set<Long> local = new HashSet<>(members.keySet());
        when(connections.connectedUserIds()).thenReturn(local);
        doReturn(local).when(connections).connectedAmong(any());
        when(connections.sessionIdsOf(any())).thenAnswer(invocation -> Set.of("s-" + invocation.getArgument(0)));
        when(reader.findRoomMembership(ROOM_ID)).thenReturn(new RoomMembership(1, members));

        for (int batch = 0; batch < 5; batch++) {
            for (int i = 1; i <= 20; i++) {
                notifier.record(activity(batch * 20L + i, 1, 1L));
            }
            notifier.flush();
        }

        verify(reader, times(1)).findRoomMembership(ROOM_ID);
        verify(presence, times(5)).onlineAmong(any());
        // 보낸 사람(1번)을 뺀 999명에게 묶음마다 한 번(초당 20건이어도 회원당 한 번)
        verify(sender, times(999 * 5)).sendRoomActivity(any(), any(), any());
    }

    @Test
    void 묶음을_얻은_직후_묶음_주기가_끼어들어도_그_메시지를_잃지_않는다() throws Exception {
        when(reader.findRoomMembership(ROOM_ID)).thenReturn(membership(1, member(HOST_ID, 1, 1), member(GUEST_ID, 1, 1)));
        PausingBatches batches = new PausingBatches();
        ReflectionTestUtils.setField(notifier, "batches", batches);
        notifier.record(activity(1, 1, HOST_ID));

        // 기록하는 스레드가 방 묶음을 얻은 직후 멈춘 사이에 묶음 주기가 그 묶음을 떼어 보낸다
        Thread recorder = new Thread(() -> notifier.record(activity(2, 1, HOST_ID)));
        batches.pauseAfterLookup(recorder);
        recorder.start();
        assertThat(batches.paused.await(5, TimeUnit.SECONDS)).isTrue();
        notifier.flush();
        batches.resume.countDown();
        recorder.join(5_000);
        notifier.flush();

        ArgumentCaptor<ChatRoomActivity> captor = ArgumentCaptor.forClass(ChatRoomActivity.class);
        verify(sender, atLeastOnce()).sendRoomActivity(eq(GUEST_ID), eq("g-list"), captor.capture());
        assertThat(captor.getAllValues()).as("두 메시지 모두 어느 묶음에서든 센다")
                .extracting(ChatRoomActivity::messageCount).containsExactly(2);
        assertThat(captor.getAllValues().getLast().lastSequence()).isEqualTo(2);
    }

    private RoomActivityFanout activity(long sequence, int setVersion, Long senderId) {
        return new RoomActivityFanout(UUID.randomUUID(), ROOM_ID, setVersion, sequence,
                ChatNotifyRule.RECIPIENTS_EXCEPT_SENDER, senderId, message(sequence, senderId), null);
    }

    private static ChatMessageResponse message(long sequence, Long senderId) {
        return new ChatMessageResponse(100 + sequence, ROOM_ID, sequence, senderId, ChatMessageType.TEXT, "m",
                NOW, null, null, null, null, null, null);
    }

    private static ChatRecipientState member(Long userId, int participationVersion, long visibleFrom) {
        return new ChatRecipientState(userId, true, true, participationVersion, visibleFrom);
    }

    private static RoomMembership membership(int version, ChatRecipientState... states) {
        Map<Long, ChatRecipientState> members = new HashMap<>();
        for (ChatRecipientState state : states) {
            members.put(state.userId(), state);
        }
        return new RoomMembership(version, members);
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

    /** 정해 둔 스레드가 방 묶음을 얻은(만들거나 고친) 직후 한 번 멈추는 묶음 목록 */
    private static final class PausingBatches extends ConcurrentHashMap<Long, Object> {

        private final CountDownLatch paused = new CountDownLatch(1);
        private final CountDownLatch resume = new CountDownLatch(1);
        private volatile Thread target;

        void pauseAfterLookup(Thread thread) {
            target = thread;
        }

        @Override
        public Object computeIfAbsent(Long key, Function<? super Long, ?> mappingFunction) {
            return pause(super.computeIfAbsent(key, mappingFunction));
        }

        @Override
        public Object compute(Long key, BiFunction<? super Long, ? super Object, ?> remappingFunction) {
            return pause(super.compute(key, remappingFunction));
        }

        private Object pause(Object batch) {
            if (Thread.currentThread() == target) {
                target = null;
                paused.countDown();
                try {
                    resume.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            }
            return batch;
        }
    }
}
