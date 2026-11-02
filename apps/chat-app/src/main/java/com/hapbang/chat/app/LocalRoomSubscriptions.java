package com.hapbang.chat.app;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

import lombok.extern.slf4j.Slf4j;

/**
 * 이 앱의 방 topic 구독 목록(방 → 연결·구독 ID·회원·참여 세대). 브로커가 아니라 이 목록으로 방 topic 프레임을 쓴다(설계 4.2).
 * <ul>
 *     <li>구독은 허가({@code ChatRoomTopicService})를 통과한 것만 넣는다. 구독은 그 세대에 묶인다.</li>
 *     <li>방 채널은 방 단위 참조 수로 구독한다({@link RoomChannelSubscriptions}). 넣을 때 하나 늘리고 뺄 때 하나 줄인다.</li>
 *     <li>목록 변경은 한 잠금으로 한다. Redis 명령(방 채널 추가)은 잠금 밖에서 한다.</li>
 * </ul>
 */
@Slf4j
@Component
public class LocalRoomSubscriptions {

    private final ObjectProvider<RoomChannelSubscriptions> roomChannels;
    private final int maxPerSession;
    /** 방 ID → (연결 ID, 구독 ID) → 구독. 이 객체의 잠금으로 지킨다 */
    private final Map<Long, Map<Key, Subscription>> byRoom = new HashMap<>();
    /** 연결 ID → 구독 ID → 구독 */
    private final Map<String, Map<String, Subscription>> bySession = new HashMap<>();

    /** 한 연결의 방 구독 수 상한(20)은 미확정 초기값(Q12)이다. 기본값은 코드가 원본이고 같은 키의 yaml·환경 변수가 덮는다 */
    public LocalRoomSubscriptions(ObjectProvider<RoomChannelSubscriptions> roomChannels,
            @Value("${hapbang.chat.realtime.room.max-subscriptions-per-session:20}") int maxPerSession) {
        Assert.isTrue(maxPerSession > 0, "hapbang.chat.realtime.room.max-subscriptions-per-session은 1 이상이어야 합니다.");
        this.roomChannels = roomChannels;
        this.maxPerSession = maxPerSession;
    }

    /**
     * 구독을 넣는다. 방 채널 참조를 먼저 늘리고(잠금 밖), 연결의 구독 수 상한을 넘거나 같은 구독 ID가 이미 있으면 되돌린다.
     */
    public Registered register(Subscription subscription) {
        RoomChannelSubscriptions channels = roomChannels.getIfAvailable();
        if (channels != null && !channels.acquire(subscription.roomId())) {
            return Registered.SERVER_BUSY;
        }
        Registered result;
        synchronized (this) {
            Map<String, Subscription> sessionSubscriptions = bySession.computeIfAbsent(subscription.sessionId(),
                    ignored -> new LinkedHashMap<>());
            if (sessionSubscriptions.containsKey(subscription.subscriptionId())) {
                result = Registered.DUPLICATE;
            } else if (sessionSubscriptions.size() >= maxPerSession) {
                result = Registered.TOO_MANY;
            } else {
                sessionSubscriptions.put(subscription.subscriptionId(), subscription);
                byRoom.computeIfAbsent(subscription.roomId(), ignored -> new LinkedHashMap<>())
                        .put(subscription.key(), subscription);
                result = Registered.OK;
            }
            if (sessionSubscriptions.isEmpty()) {
                bySession.remove(subscription.sessionId());
            }
        }
        if (result != Registered.OK && channels != null) {
            channels.release(subscription.roomId());
        }
        return result;
    }

    /**
     * 구독 하나를 뺀다. 모르는 구독 ID는 무시한다.
     *
     * @return 뺀 구독. 없었으면 null
     */
    public Subscription unregister(String sessionId, String subscriptionId) {
        Subscription removed;
        synchronized (this) {
            Map<String, Subscription> sessionSubscriptions = bySession.get(sessionId);
            removed = sessionSubscriptions == null ? null : sessionSubscriptions.remove(subscriptionId);
            if (removed == null) {
                return null;
            }
            if (sessionSubscriptions.isEmpty()) {
                bySession.remove(sessionId);
            }
            removeFromRoom(removed);
        }
        releaseChannel(removed.roomId());
        return removed;
    }

    /** 그 구독이 지금도 같은 구독이면 뺀다(회수). 이미 바뀌었거나 없으면 false */
    public boolean remove(Subscription subscription) {
        synchronized (this) {
            Map<String, Subscription> sessionSubscriptions = bySession.get(subscription.sessionId());
            if (sessionSubscriptions == null
                    || !sessionSubscriptions.remove(subscription.subscriptionId(), subscription)) {
                return false;
            }
            if (sessionSubscriptions.isEmpty()) {
                bySession.remove(subscription.sessionId());
            }
            removeFromRoom(subscription);
        }
        releaseChannel(subscription.roomId());
        return true;
    }

    /** 연결의 구독을 모두 뺀다(연결 종료) */
    public List<Subscription> unregisterSession(String sessionId) {
        List<Subscription> removed;
        synchronized (this) {
            Map<String, Subscription> sessionSubscriptions = bySession.remove(sessionId);
            if (sessionSubscriptions == null) {
                return List.of();
            }
            removed = new ArrayList<>(sessionSubscriptions.values());
            removed.forEach(this::removeFromRoom);
        }
        removed.forEach(subscription -> releaseChannel(subscription.roomId()));
        return removed;
    }

    /** 그 방의 구독 스냅샷 */
    public synchronized List<Subscription> subscriptions(Long roomId) {
        Map<Key, Subscription> room = byRoom.get(roomId);
        return room == null ? List.of() : List.copyOf(room.values());
    }

    /** 그 방을 구독한 연결 ID */
    public synchronized Set<String> subscribedSessions(Long roomId) {
        Map<Key, Subscription> room = byRoom.get(roomId);
        if (room == null) {
            return Set.of();
        }
        Set<String> sessionIds = new HashSet<>();
        room.values().forEach(subscription -> sessionIds.add(subscription.sessionId()));
        return sessionIds;
    }

    /** 그 방에서 그 회원의 구독 스냅샷 */
    public synchronized List<Subscription> subscriptionsOf(Long roomId, Long userId) {
        return subscriptions(roomId).stream().filter(subscription -> subscription.userId().equals(userId)).toList();
    }

    private void removeFromRoom(Subscription subscription) {
        Map<Key, Subscription> room = byRoom.get(subscription.roomId());
        if (room != null) {
            room.remove(subscription.key(), subscription);
            if (room.isEmpty()) {
                byRoom.remove(subscription.roomId());
            }
        }
    }

    private void releaseChannel(Long roomId) {
        RoomChannelSubscriptions channels = roomChannels.getIfAvailable();
        if (channels != null) {
            channels.release(roomId);
        }
    }

    /** 넣은 결과 */
    public enum Registered {
        OK,
        /** 같은 연결에 같은 구독 ID가 이미 있다 */
        DUPLICATE,
        /** 연결의 방 구독 수 상한 */
        TOO_MANY,
        /** 앱의 방 채널 수 상한 */
        SERVER_BUSY
    }

    /**
     * 방 topic 구독 하나.
     *
     * @param participationVersion 허가할 때 확인한 그 회원의 참여 세대. 구독은 이 세대에 묶인다
     */
    public record Subscription(String sessionId, String subscriptionId, Long roomId, Long userId,
            int participationVersion) {

        Key key() {
            return new Key(sessionId, subscriptionId);
        }
    }

    private record Key(String sessionId, String subscriptionId) {
    }
}
