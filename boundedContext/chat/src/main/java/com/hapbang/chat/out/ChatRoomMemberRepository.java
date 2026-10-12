package com.hapbang.chat.out;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
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
}
