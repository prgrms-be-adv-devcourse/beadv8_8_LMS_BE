package com.hapbang.chat.out;

import java.util.List;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.hapbang.chat.domain.ChatMessage;

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
     * 관리자 기록 조회용. 참여자별 조회 범위와 Soft Delete 여부와 관계없이 모두 조회한다.
     */
    List<ChatMessage> findByChatRoom_IdOrderByRoomSequenceAsc(Long chatRoomId);
}
