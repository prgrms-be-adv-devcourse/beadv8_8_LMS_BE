package com.hapbang.chat.app;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Limit;

import com.hapbang.chat.domain.ChatRoom;

/**
 * 채팅방 조회. 잠그는 조회({@code @Lock})와 쓰기는 repository 메서드를 그대로 쓴다.
 */
public interface ChatRoomReader {

    /**
     * 모집글의 현재 단체방(사용 중·일시 중지). 부분 유일 인덱스로 하나뿐이다. 읽기 전용으로 남은 이전 방은 나오지 않는다.
     */
    Optional<ChatRoom> findCurrentGroup(Long recruitId);

    /**
     * 조회 만료 시각에 도달한(같은 시각 포함) 읽기 전용 단체방 ID 한 묶음. 방 ID 순서의 키셋이며 잠그지 않는다. 상태는 방마다 잠근
     * 트랜잭션에서 다시 확인하고 바꾼다. 부분 인덱스 {@code idx_chat_room_group_history_expiry}를 쓴다.
     */
    List<Long> findHistoryExpiryDueIds(LocalDateTime now, long afterId, Limit limit);
}
