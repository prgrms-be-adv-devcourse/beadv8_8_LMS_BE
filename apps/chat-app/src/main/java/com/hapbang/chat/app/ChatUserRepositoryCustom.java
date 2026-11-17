package com.hapbang.chat.app;

import java.util.Optional;

import com.hapbang.chat.app.ChatRoomMemberRows.UserWithMembership;

/**
 * {@link ChatUserRepository}의 QueryDSL 조회. 구현은 out의 {@code ChatUserRepositoryCustomImpl}이다. 잠그는 조회와 저장은
 * {@link ChatUserRepository}에 둔다.
 */
public interface ChatUserRepositoryCustom {

    /**
     * 회원 복제본과 그 방의 참여 행(나간 행 포함, 삭제한 행 제외)을 한 문장으로 읽는다. 방 topic 구독 허가가 쓴다.
     *
     * @return 회원 복제본이 없으면 빈 값. 참여 행이 없으면 {@code member}가 null이다
     */
    Optional<UserWithMembership> findWithMembership(Long userId, Long roomId);
}
