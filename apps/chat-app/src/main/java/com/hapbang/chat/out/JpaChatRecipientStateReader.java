package com.hapbang.chat.out;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.chat.app.ChatRecipientStateReader;
import com.hapbang.chat.app.dto.ChatRecipientState;
import com.hapbang.chat.domain.ChatRoom;
import com.hapbang.chat.domain.ChatRoomMember;
import com.hapbang.chat.domain.ChatUser;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * 실시간 전달·방 topic 구독 허가에 필요한 상태를 chat DB에서 읽는다. 회원 복제본과 참여 행을 함께 읽는다.
 * 회원 자격 판단은 도메인({@link ChatUser#canChat()}), 참여 판단은 {@link ChatRoomMember}의 값을 그대로 쓴다.
 * 읽기 전용 트랜잭션은 READ COMMITTED라 문장마다 그때 커밋된 값을 본다.
 */
@Component
@Transactional(readOnly = true)
public class JpaChatRecipientStateReader implements ChatRecipientStateReader {

    @PersistenceContext
    private EntityManager entityManager;

    private final ChatUserRepository chatUserRepository;

    public JpaChatRecipientStateReader(ChatUserRepository chatUserRepository) {
        this.chatUserRepository = chatUserRepository;
    }

    @Override
    public RoomMembership findRoomMembership(Long roomId) {
        // 버전을 먼저 읽는다. 그 뒤에 읽는 참여 상태는 이 버전까지의 참여 변경을 반드시 반영한다
        List<Integer> versions = entityManager.createQuery("""
                        select s.setVersion from ChatRoom r, ChatRecipientSet s
                        where r.id = :roomId and s.id = r.currentRecipientSetId
                        """, Integer.class)
                .setParameter("roomId", roomId)
                .getResultList();
        if (versions.isEmpty()) {
            return new RoomMembership(0, Map.of());
        }
        List<Object[]> rows = entityManager.createQuery("""
                        select u, m from ChatRoomMember m join ChatUser u on u.id = m.userId
                        where m.chatRoom.id = :roomId and m.leftAt is null and m.deletedAt is null
                        """, Object[].class)
                .setParameter("roomId", roomId)
                .getResultList();
        Map<Long, ChatRecipientState> members = new HashMap<>();
        for (Object[] row : rows) {
            ChatUser user = (ChatUser) row[0];
            members.put(user.getId(), stateOf(user, (ChatRoomMember) row[1]));
        }
        return new RoomMembership(versions.getFirst(), members);
    }

    @Override
    public SubscriptionCheck checkSubscription(Long roomId, Long userId) {
        List<ChatRoom> rooms = entityManager.createQuery("select r from ChatRoom r where r.id = :roomId",
                        ChatRoom.class)
                .setParameter("roomId", roomId)
                .getResultList();
        boolean roomOpen = !rooms.isEmpty() && !rooms.getFirst().isEnded();
        List<Object[]> rows = entityManager.createQuery("""
                        select u, m from ChatUser u
                        left join ChatRoomMember m on m.userId = u.id and m.chatRoom.id = :roomId
                            and m.deletedAt is null
                        where u.id = :userId
                        """, Object[].class)
                .setParameter("roomId", roomId)
                .setParameter("userId", userId)
                .getResultList();
        if (rows.isEmpty()) {
            return new SubscriptionCheck(roomOpen, null);
        }
        Object[] row = rows.getFirst();
        return new SubscriptionCheck(roomOpen, stateOf((ChatUser) row[0], (ChatRoomMember) row[1]));
    }

    @Override
    public Set<Long> findChatAllowedUserIds(Collection<Long> userIds) {
        if (userIds.isEmpty()) {
            return Set.of();
        }
        return chatUserRepository.findAllById(userIds).stream()
                .filter(ChatUser::canChat)
                .map(ChatUser::getId)
                .collect(Collectors.toSet());
    }

    private static ChatRecipientState stateOf(ChatUser user, ChatRoomMember member) {
        return member == null
                ? new ChatRecipientState(user.getId(), user.canChat(), false, 0, Long.MAX_VALUE)
                : new ChatRecipientState(user.getId(), user.canChat(), member.isActive(),
                        member.getParticipationVersion(), member.getVisibleFromSequence());
    }
}
