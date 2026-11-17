package com.hapbang.chat.app;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.domain.Limit;

import com.hapbang.chat.domain.ChatRoomLog;

/**
 * 방 이벤트 기록 조회. 저장과 이름으로 만드는 조회는 repository 메서드를 그대로 쓴다.
 */
public interface ChatRoomLogReader {

    /**
     * 관리자 기록 조회 다음 페이지. 커서 시각 이후이거나, 같은 시각이면 {@code idAfter}보다 큰 기록.
     * 행 비교로 써야 (chat_room_id, occurred_at, id) 인덱스의 범위 조건이 된다.
     */
    List<ChatRoomLog> findHistoryAfter(Long chatRoomId, LocalDateTime at, long idAfter, Limit limit);
}
