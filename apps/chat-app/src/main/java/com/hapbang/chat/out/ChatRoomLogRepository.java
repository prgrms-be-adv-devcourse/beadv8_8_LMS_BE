package com.hapbang.chat.out;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.hapbang.chat.domain.ChatRoomLog;

public interface ChatRoomLogRepository extends JpaRepository<ChatRoomLog, Long> {

    List<ChatRoomLog> findByChatRoom_IdOrderByIdAsc(Long chatRoomId);

    /** 관리자 기록 조회 첫 페이지. (일어난 시각, ID) 순서 */
    List<ChatRoomLog> findByChatRoom_IdOrderByOccurredAtAscIdAsc(Long chatRoomId, Limit limit);

    /**
     * 관리자 기록 조회 다음 페이지. 커서 시각 이후이거나, 같은 시각이면 {@code idAfter}보다 큰 기록 (#263).
     * 행 비교로 써야 (chat_room_id, occurred_at, id) 인덱스의 범위 조건이 된다.
     */
    @Query("""
            select l from ChatRoomLog l
            where l.chatRoom.id = :chatRoomId
              and (l.occurredAt, l.id) > (:at, :idAfter)
            order by l.occurredAt asc, l.id asc
            """)
    List<ChatRoomLog> findHistoryAfter(@Param("chatRoomId") Long chatRoomId, @Param("at") LocalDateTime at,
            @Param("idAfter") long idAfter, Limit limit);
}
