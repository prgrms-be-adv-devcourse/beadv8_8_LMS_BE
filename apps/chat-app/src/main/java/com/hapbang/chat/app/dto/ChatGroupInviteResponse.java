package com.hapbang.chat.app.dto;

import java.util.List;
import java.util.UUID;

import com.hapbang.chat.domain.ChatGroupInviteOutcome;

/**
 * 단체방 초대 결과. 회원별로 참여·이미 참여 중·실패(사유)를 알린다(Q34). 같은 요청 ID의 재시도는 저장한 결과를 그대로 돌려준다.
 *
 * @param results 중복을 뺀 요청 순서의 회원별 결과
 */
public record ChatGroupInviteResponse(Long chatRoomId, UUID requestId, List<ChatGroupInviteOutcome> results) {
}
