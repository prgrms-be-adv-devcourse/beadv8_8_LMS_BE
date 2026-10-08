package com.hapbang.chat.out;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.hapbang.chat.domain.ChatRoomLog;

public interface ChatRoomLogRepository extends JpaRepository<ChatRoomLog, Long> {

    List<ChatRoomLog> findByChatRoom_IdOrderByIdAsc(Long chatRoomId);
}
