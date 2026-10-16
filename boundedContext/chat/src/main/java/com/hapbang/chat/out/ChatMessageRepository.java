package com.hapbang.chat.out;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.hapbang.chat.domain.ChatMessage;
import com.hapbang.chat.domain.NotificationKind;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    /**
     * 참여자의 조회 범위([fromSequence, beforeSequence)) 안의 메시지를 최신순으로 조회한다.
     */
    @Query("""
            select m from ChatMessage m
            where m.chatRoom.id = :chatRoomId
              and m.roomSequence >= :fromSequence and m.roomSequence < :beforeSequence
              and m.deletedAt is null
            order by m.roomSequence desc
            """)
    List<ChatMessage> findVisible(@Param("chatRoomId") Long chatRoomId, @Param("fromSequence") long fromSequence,
            @Param("beforeSequence") long beforeSequence, Limit limit);

    /**
     * 회원이 참여한 방마다 조회 범위 안의 가장 최근 메시지. 볼 수 있는 메시지가 없는 방은 빠진다.
     */
    @Query("""
            select m from ChatMessage m, ChatRoomMember cm
            where cm.chatRoom.id = m.chatRoom.id and cm.userId = :userId and cm.deletedAt is null
              and m.chatRoom.id in :chatRoomIds
              and m.roomSequence = (select max(m2.roomSequence) from ChatMessage m2
                                    where m2.chatRoom.id = m.chatRoom.id and m2.deletedAt is null
                                      and m2.roomSequence >= cm.visibleFromSequence)
            """)
    List<ChatMessage> findLastVisible(@Param("userId") Long userId,
            @Param("chatRoomIds") Collection<Long> chatRoomIds);

    /**
     * 방마다 회원이 안 읽은 메시지 수. 조회 범위 안에서 마지막으로 읽은 순번 뒤에 온 상대(시스템 안내 포함) 메시지만 센다.
     * 안 읽은 메시지가 없는 방은 빠진다.
     */
    @Query("""
            select new com.hapbang.chat.out.ChatRoomUnreadCount(m.chatRoom.id, count(m))
            from ChatMessage m, ChatRoomMember cm
            where cm.chatRoom.id = m.chatRoom.id and cm.userId = :userId and cm.deletedAt is null
              and m.chatRoom.id in :chatRoomIds
              and m.roomSequence >= cm.visibleFromSequence and m.roomSequence > cm.lastReadSequence
              and m.senderUserId <> :userId and m.deletedAt is null
            group by m.chatRoom.id
            """)
    List<ChatRoomUnreadCount> countUnread(@Param("userId") Long userId,
            @Param("chatRoomIds") Collection<Long> chatRoomIds);

    /** 같은 전송 ID로 이미 저장한 메시지 (재전송 확인) */
    Optional<ChatMessage> findByChatRoom_IdAndSenderUserIdAndClientMessageId(Long chatRoomId, Long senderUserId,
            String clientMessageId);

    /** 같은 출연 이벤트로 이미 알림을 남겼는지 */
    boolean existsByNotificationKindAndReferenceId(NotificationKind notificationKind, Long referenceId);

    /** 방의 모든 메시지 (Soft Delete 포함, 순번 순서) */
    List<ChatMessage> findByChatRoom_IdOrderByRoomSequenceAsc(Long chatRoomId);

    /**
     * 관리자 기록 조회 첫 페이지. 참여자별 조회 범위와 Soft Delete 여부와 관계없이 (보낸 시각, ID) 순서로 조회한다.
     */
    List<ChatMessage> findByChatRoom_IdOrderBySentAtAscIdAsc(Long chatRoomId, Limit limit);

    /**
     * 관리자 기록 조회 다음 페이지. 커서 시각 이후이거나, 같은 시각이면 {@code idAfter}보다 큰 메시지 (#263).
     * 행 비교로 써야 (chat_room_id, sent_at, id) 인덱스의 범위 조건이 되어 커서 앞 항목을 다시 훑지 않는다.
     */
    @Query("""
            select m from ChatMessage m
            where m.chatRoom.id = :chatRoomId
              and (m.sentAt, m.id) > (:at, :idAfter)
            order by m.sentAt asc, m.id asc
            """)
    List<ChatMessage> findHistoryAfter(@Param("chatRoomId") Long chatRoomId, @Param("at") LocalDateTime at,
            @Param("idAfter") long idAfter, Limit limit);
}
