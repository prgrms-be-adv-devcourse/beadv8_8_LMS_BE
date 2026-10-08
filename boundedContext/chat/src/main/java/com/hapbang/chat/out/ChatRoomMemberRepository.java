package com.hapbang.chat.out;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.hapbang.chat.domain.ChatRoomMember;

public interface ChatRoomMemberRepository extends JpaRepository<ChatRoomMember, Long> {

    List<ChatRoomMember> findByChatRoom_IdAndDeletedAtIsNull(Long chatRoomId);

    Optional<ChatRoomMember> findByChatRoom_IdAndUserIdAndDeletedAtIsNull(Long chatRoomId, Long userId);
}
