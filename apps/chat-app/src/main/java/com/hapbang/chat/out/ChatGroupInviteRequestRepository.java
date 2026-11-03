package com.hapbang.chat.out;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.hapbang.chat.domain.ChatGroupInviteRequest;

public interface ChatGroupInviteRequestRepository extends JpaRepository<ChatGroupInviteRequest, Long> {

    /** 방·요청 ID의 초대 결과 기록 */
    Optional<ChatGroupInviteRequest> findByChatRoomIdAndRequestId(Long chatRoomId, UUID requestId);
}
