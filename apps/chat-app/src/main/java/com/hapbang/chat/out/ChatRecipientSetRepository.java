package com.hapbang.chat.out;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.hapbang.chat.domain.ChatRecipientSet;

public interface ChatRecipientSetRepository extends JpaRepository<ChatRecipientSet, Long> {

    /** 방의 마지막 수신자 집합 버전. 없으면 0. 방을 잠근 트랜잭션에서 다음 버전을 정할 때 쓴다 */
    @Query("select coalesce(max(s.setVersion), 0) from ChatRecipientSet s where s.chatRoomId = :chatRoomId")
    int findLastVersion(@Param("chatRoomId") Long chatRoomId);
}
