package com.hapbang.chat.app.dto;

import java.util.List;

/**
 * @param nextCursor           다음 페이지 요청에 쓸 커서(방 메시지 순번). 더 없으면 null
 * @param participationVersion 조회한 회원의 현재 참여 세대. 수신 확인 요청에 그대로 쓴다
 * @param visibleFromSequence  조회한 회원이 볼 수 있는 첫 메시지 순번. 이 앞 순번은 누락으로 보지 않는다
 */
public record ChatMessagePageResponse(List<ChatMessageResponse> messages, Long nextCursor, int participationVersion,
        long visibleFromSequence) {
}
