package com.hapbang.chat.app.dto;

import java.util.List;

/**
 * 방의 활성 참여자 한 페이지(참여 순서). 단체방 목록·상세는 참여자를 싣지 않으므로 이 페이지로 읽는다(Q37).
 *
 * @param nextCursor 다음 페이지 요청의 {@code cursor}. 더 없으면 null
 */
public record ChatRoomMemberPageResponse(List<ChatRoomMemberResponse> members, Long nextCursor) {
}
