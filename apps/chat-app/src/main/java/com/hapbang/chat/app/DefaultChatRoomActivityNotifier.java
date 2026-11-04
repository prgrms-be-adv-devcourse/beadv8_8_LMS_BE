package com.hapbang.chat.app;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

import com.hapbang.chat.app.ChatRecipientStateReader.RoomMembership;
import com.hapbang.chat.app.dto.ChatMessageResponse;
import com.hapbang.chat.app.dto.ChatRecipientState;
import com.hapbang.chat.app.dto.ChatRoomActivity;
import com.hapbang.chat.app.dto.RoomActivityFanout;
import com.hapbang.chat.domain.ChatNotifyRule;

import lombok.extern.slf4j.Slf4j;

/**
 * 방을 열지 않은 참여자에게 새 메시지 알림({@code ROOM_ACTIVITY})을 방별로 묶어 보낸다(설계 4.2, Q15·Q21).
 * <ul>
 *     <li>서버 채널로 받은 알림 재료를 방별로 모으고, 묶음 주기마다({@link #flush}) 방마다 한 번 보낸다.</li>
 *     <li>대상 = 방 참여자(캐시, 묶음 마지막 메시지의 수신자 집합 버전 이상) ∩ 접속 중인 회원(Redis) 중 이 앱의 연결에서 그 방
 *     topic을 구독한 연결을 뺀 연결. 묶음 마지막 메시지 저장 전에 나간 회원은 대상이 아니다.</li>
 *     <li>회원마다 그 회원에게 보이고(가시 범위) 그 회원이 보내지 않은 메시지만 센다. 셀 메시지가 없으면 보내지 않는다.</li>
 *     <li>알림은 최선 노력이다. 조회에 실패하면 그 묶음을 버리고 FE의 주기 대조가 메운다.</li>
 * </ul>
 */
@Slf4j
@Component
public class DefaultChatRoomActivityNotifier implements ChatRoomActivityNotifier {

    private final ChatRoomMembershipCache chatRoomMembershipCache;
    private final LocalChatConnections localChatConnections;
    private final LocalRoomSubscriptions localRoomSubscriptions;
    private final ObjectProvider<ChatPresence> chatPresence;
    private final LocalChatSender localChatSender;
    private final int maxEntriesPerRoom;
    private final Map<Long, Batch> batches = new ConcurrentHashMap<>();

    /** 한 묶음에 기억하는 메시지 수 상한(1000)은 미확정 초기값(Q12)이다. 넘으면 오래된 것부터 세지 않는다 */
    public DefaultChatRoomActivityNotifier(ChatRoomMembershipCache chatRoomMembershipCache,
            LocalChatConnections localChatConnections, LocalRoomSubscriptions localRoomSubscriptions,
            ObjectProvider<ChatPresence> chatPresence, LocalChatSender localChatSender,
            @Value("${hapbang.chat.realtime.activity.max-messages-per-batch:1000}") int maxEntriesPerRoom) {
        Assert.isTrue(maxEntriesPerRoom > 0, "hapbang.chat.realtime.activity.max-messages-per-batch는 1 이상이어야 합니다.");
        this.chatRoomMembershipCache = chatRoomMembershipCache;
        this.localChatConnections = localChatConnections;
        this.localRoomSubscriptions = localRoomSubscriptions;
        this.chatPresence = chatPresence;
        this.localChatSender = localChatSender;
        this.maxEntriesPerRoom = maxEntriesPerRoom;
    }

    @Override
    public void record(RoomActivityFanout activity) {
        if (activity.notifyRule() != ChatNotifyRule.RECIPIENTS_EXCEPT_SENDER || activity.roomId() == null
                || activity.lastMessage() == null) {
            return;
        }
        // 묶음에 넣기를 방 키의 원자 연산 안에서 한다. 묶음 주기가 떼어 간 묶음에 넣어 메시지를 잃는 일을 막는다
        batches.compute(activity.roomId(), (roomId, batch) -> {
            Batch target = batch == null ? new Batch() : batch;
            target.add(activity, maxEntriesPerRoom);
            return target;
        });
    }

    @Override
    public void flush() {
        for (Long roomId : new ArrayList<>(batches.keySet())) {
            Batch batch = batches.remove(roomId);
            if (batch == null) {
                continue;
            }
            try {
                send(roomId, batch.snapshot());
            } catch (RuntimeException exception) {
                log.warn("새 메시지 알림 묶음을 보내지 못해 버림 roomId={}", roomId, exception);
            }
        }
    }

    private void send(Long roomId, Snapshot batch) {
        if (localChatConnections.connectedUserIds().isEmpty()) {
            return;
        }
        RoomMembership membership = chatRoomMembershipCache.get(roomId, batch.maxSetVersion());
        Map<Long, ChatRecipientState> candidates = new HashMap<>();
        for (ChatRecipientState state : membership.members().values()) {
            if (state.canChat() && state.activeParticipant() && batch.countFor(state) > 0) {
                candidates.put(state.userId(), state);
            }
        }
        if (candidates.isEmpty()) {
            return;
        }
        Set<Long> local = localChatConnections.connectedAmong(candidates.keySet());
        if (local.isEmpty()) {
            return;
        }
        ChatPresence presence = chatPresence.getIfAvailable();
        Set<Long> online = presence == null ? local : presence.onlineAmong(local);
        Set<String> subscribed = localRoomSubscriptions.subscribedSessions(roomId);
        for (Long userId : online) {
            ChatRecipientState state = candidates.get(userId);
            if (state == null) {
                continue;
            }
            ChatRoomActivity activity = new ChatRoomActivity(roomId, state.participationVersion(),
                    batch.lastMessage().roomSequence(), batch.countFor(state), batch.lastMessage());
            for (String sessionId : localChatConnections.sessionIdsOf(userId)) {
                if (!subscribed.contains(sessionId)) {
                    localChatSender.sendRoomActivity(userId, sessionId, activity);
                }
            }
        }
    }

    /** 한 방의 모인 알림 재료 */
    private static final class Batch {

        private final List<Entry> entries = new ArrayList<>();
        private ChatMessageResponse lastMessage;
        private int maxSetVersion;

        synchronized void add(RoomActivityFanout activity, int maxEntries) {
            if (entries.size() >= maxEntries) {
                entries.removeFirst();
            }
            entries.add(new Entry(activity.roomSequence(), activity.senderUserId()));
            if (lastMessage == null || activity.roomSequence() > lastMessage.roomSequence()) {
                lastMessage = activity.lastMessage();
            }
            maxSetVersion = Math.max(maxSetVersion, activity.recipientSetVersion());
        }

        synchronized Snapshot snapshot() {
            return new Snapshot(List.copyOf(entries), lastMessage, maxSetVersion);
        }
    }

    private record Entry(long roomSequence, Long senderUserId) {
    }

    private record Snapshot(List<Entry> entries, ChatMessageResponse lastMessage, int maxSetVersion) {

        /** 그 회원에게 보이고 그 회원이 보내지 않은 메시지 수. 마지막 메시지가 보이지 않으면 0 */
        int countFor(ChatRecipientState state) {
            if (lastMessage.roomSequence() < state.visibleFromSequence()) {
                return 0;
            }
            Set<Long> counted = new HashSet<>();
            int count = 0;
            for (Entry entry : entries) {
                if (entry.roomSequence() >= state.visibleFromSequence()
                        && !state.userId().equals(entry.senderUserId()) && counted.add(entry.roomSequence())) {
                    count++;
                }
            }
            return count;
        }
    }
}
