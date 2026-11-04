package com.hapbang.chat.app.dto;

import com.hapbang.chat.domain.ChatGroupInviteFailure;

/**
 * 초대 후보 한 명. 닉네임은 같을 수 있으므로 초대는 회원 ID로 지정한다.
 *
 * @param nickname  표시 닉네임(채팅 회원 정보). 탈퇴 회원은 '탈퇴한 회원', 채팅 회원 정보가 없으면 '알 수 없는 회원'(회원 ID는 드러내지 않는다)
 * @param invitable 지금 초대할 수 있는지
 * @param reason    초대할 수 없는 사유. 초대할 수 있으면 null
 */
public record ChatInviteCandidateResponse(Long userId, String nickname, boolean invitable,
        ChatGroupInviteFailure reason) {
}
