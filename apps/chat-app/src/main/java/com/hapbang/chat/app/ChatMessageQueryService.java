package com.hapbang.chat.app;

import com.hapbang.chat.app.dto.ChatMessagePageResponse;

/** 메시지 읽기 전용 기능. 쓰기({@link ChatMessageService})와 나눈다 */
public interface ChatMessageQueryService {

    /**
     * 참여자 본인의 조회 범위 안에서 최신순으로 조회한다. cursor는 방 메시지 순번이며, 없으면 가장 최근부터 조회한다.
     */
    ChatMessagePageResponse getMessages(Long chatRoomId, Long userId, Long cursor, int size);
}
