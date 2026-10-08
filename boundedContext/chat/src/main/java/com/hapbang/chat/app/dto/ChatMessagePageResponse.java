package com.hapbang.chat.app.dto;

import java.util.List;

/**
 * @param nextCursor 다음 페이지 요청에 쓸 커서(방 메시지 순번). 더 없으면 null
 */
public record ChatMessagePageResponse(List<ChatMessageResponse> messages, Long nextCursor) {
}
