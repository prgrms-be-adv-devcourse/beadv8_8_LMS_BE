package com.hapbang.chat.app;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

import com.hapbang.chat.app.ChatRecipientStateReader.RoomMembership;

import lombok.extern.slf4j.Slf4j;

/**
 * 방 활성 참여자의 상태 캐시. 방 topic 전달·새 메시지 알림이 버전 확인 규칙(설계 4.2)으로 쓴다.
 * <ul>
 *     <li>메시지는 저장 때 가리킨 수신자 집합 버전 V를 싣는다. 캐시 버전이 V보다 작으면 DB에서 다시 읽는다(버전을 먼저, 참여를
 *     나중에). 퇴장·재입장은 방 잠금 아래 버전을 올리므로, 퇴장 커밋 뒤 저장된 메시지를 처음 내보낼 때 반드시 그 퇴장을 본다.</li>
 *     <li>회원 자격(정지·강등)은 버전을 바꾸지 않는다. 주기({@code membership-refresh})가 지난 캐시도 다시 읽는다. 자격 상실은
 *     회원 상태 재검사가 연결을 닫는다(설계 Q17).</li>
 *     <li>방마다 잠금 하나로 다시 읽기를 직렬화하고, 더 낮은 버전으로 읽은 결과로 덮지 않는다.</li>
 *     <li>방 수에 상한을 두고 오래 쓰지 않은 방부터 버린다.</li>
 * </ul>
 * 조회 실패는 예외로 알린다. 호출하는 쪽은 실패를 자격 있음으로 보지 않는다.
 */
@Slf4j
@Component
public class ChatRoomMembershipCache {

    private final ChatRecipientStateReader chatRecipientStateReader;
    private final long refreshNanos;
    private final Map<Long, Entry> entries;

    /**
     * 캐시하는 방 수(1000)·자격 재조회 주기(60s)는 미확정 초기값(Q12)이다. 기본값은 코드가 원본이고 같은 키의 yaml·환경 변수가
     * 덮는다.
     */
    public ChatRoomMembershipCache(ChatRecipientStateReader chatRecipientStateReader,
            @Value("${hapbang.chat.realtime.room.membership-cache-rooms:1000}") int maxRooms,
            @Value("${hapbang.chat.realtime.room.membership-refresh:60s}") Duration refresh) {
        Assert.isTrue(maxRooms > 0, "hapbang.chat.realtime.room.membership-cache-rooms는 1 이상이어야 합니다.");
        Assert.isTrue(refresh.isPositive(), "hapbang.chat.realtime.room.membership-refresh는 0보다 커야 합니다.");
        this.chatRecipientStateReader = chatRecipientStateReader;
        this.refreshNanos = refresh.toNanos();
        this.entries = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Long, Entry> eldest) {
                return size() > maxRooms;
            }
        };
    }

    /**
     * 적어도 {@code minSetVersion}까지의 참여 변경을 반영한 방 참여 상태.
     *
     * @throws RuntimeException 조회 실패
     */
    public RoomMembership get(Long roomId, int minSetVersion) {
        Entry entry;
        synchronized (entries) {
            entry = entries.computeIfAbsent(roomId, ignored -> new Entry());
        }
        synchronized (entry) {
            RoomMembership cached = entry.membership;
            if (cached == null || cached.setVersion() < minSetVersion
                    || System.nanoTime() - entry.loadedAt >= refreshNanos) {
                RoomMembership loaded = chatRecipientStateReader.findRoomMembership(roomId);
                if (cached == null || loaded.setVersion() >= cached.setVersion()) {
                    entry.membership = loaded;
                    entry.loadedAt = System.nanoTime();
                }
                if (entry.membership.setVersion() < minSetVersion) {
                    // 메시지가 커밋된 뒤에 읽었으므로 일어나지 않는다. 일어나면 확인하지 못한 것으로 본다
                    throw new IllegalStateException("방 참여 상태가 메시지의 수신자 집합 버전보다 오래됐습니다. roomId=" + roomId
                            + ", expected>=" + minSetVersion + ", actual=" + entry.membership.setVersion());
                }
            }
            return entry.membership;
        }
    }

    /**
     * 캐시와 관계없이 지금 DB에서 다시 읽는다(더 낮은 버전으로 덮지는 않는다). 캐시가 구독보다 먼저 읽힌 탓에 판단이 어긋날 수 있을
     * 때(재입장 뒤 새 세대로 등록한 구독을 재입장 전에 읽은 상태로 판단하는 경우) 회수하기 전에 부른다.
     */
    public RoomMembership refresh(Long roomId) {
        Entry entry;
        synchronized (entries) {
            entry = entries.computeIfAbsent(roomId, ignored -> new Entry());
        }
        synchronized (entry) {
            RoomMembership loaded = chatRecipientStateReader.findRoomMembership(roomId);
            if (entry.membership == null || loaded.setVersion() >= entry.membership.setVersion()) {
                entry.membership = loaded;
                entry.loadedAt = System.nanoTime();
            }
            return entry.membership;
        }
    }

    /** 그 방의 캐시를 버린다(다음 조회가 다시 읽는다) */
    public void invalidate(Long roomId) {
        synchronized (entries) {
            entries.remove(roomId);
        }
    }

    private static final class Entry {
        private RoomMembership membership;
        private long loadedAt;
    }
}
