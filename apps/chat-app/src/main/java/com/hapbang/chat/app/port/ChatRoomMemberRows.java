package com.hapbang.chat.app.port;

import com.hapbang.chat.domain.ChatRoomMember;
import com.hapbang.chat.domain.ChatUser;

/**
 * 참여 행과 회원 복제본을 함께 읽은 조회 결과 묶음({@link ChatRoomMemberRepositoryCustom}·{@link ChatUserRepositoryCustom}).
 */
public final class ChatRoomMemberRows {

    private ChatRoomMemberRows() {
    }

    /** 활성 참여 행과 그 회원 복제본 */
    public record MemberWithUser(ChatUser user, ChatRoomMember member) {
    }

    /**
     * 회원 복제본과 그 방의 참여 행.
     *
     * @param member 그 방의 참여 행(나간 행 포함, 삭제한 행 제외). 없으면 null
     */
    public record UserWithMembership(ChatUser user, ChatRoomMember member) {
    }
}
