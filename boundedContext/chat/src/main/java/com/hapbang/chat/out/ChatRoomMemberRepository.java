package com.hapbang.chat.out;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.hapbang.chat.domain.ChatRoomMember;

public interface ChatRoomMemberRepository extends JpaRepository<ChatRoomMember, Long> {

    List<ChatRoomMember> findByChatRoom_IdAndDeletedAtIsNull(Long chatRoomId);

    List<ChatRoomMember> findByChatRoom_IdInAndDeletedAtIsNull(Collection<Long> chatRoomIds);

    Optional<ChatRoomMember> findByChatRoom_IdAndUserIdAndDeletedAtIsNull(Long chatRoomId, Long userId);

    /**
     * 회원이 참여 중인, 종료되지 않은 방의 참여 정보.
     */
    @Query("""
            select m from ChatRoomMember m join fetch m.chatRoom r
            where m.userId = :userId and m.leftAt is null and m.deletedAt is null
              and r.status = com.hapbang.chat.domain.ChatRoomStatus.ACTIVE and r.deletedAt is null
            order by r.id desc
            """)
    List<ChatRoomMember> findActiveByUserId(@Param("userId") Long userId);

    /**
     * 마지막으로 읽은 순번을 올린다. 지금보다 작은 값이면 바꾸지 않아서, 읽음 처리가 동시에 와도 줄어들지 않는다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update ChatRoomMember m set m.lastReadSequence = :sequence
            where m.id = :memberId and m.lastReadSequence < :sequence
            """)
    int advanceLastReadSequence(@Param("memberId") Long memberId, @Param("sequence") long sequence);
}
