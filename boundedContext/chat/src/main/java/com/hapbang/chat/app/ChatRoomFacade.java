package com.hapbang.chat.app;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import com.hapbang.chat.app.dto.ChatRoomCreateResult;
import com.hapbang.chat.app.dto.ChatRoomHistoryResponse;
import com.hapbang.chat.app.dto.ChatRoomLeaveResult;
import com.hapbang.chat.domain.ChatErrorCode;
import com.hapbang.chat.domain.ChatException;

import lombok.RequiredArgsConstructor;

/**
 * Controller가 채팅방 기능을 쓰는 진입점. Controller는 Service를 직접 쓰지 않고 이 Facade를 통한다.
 */
@Component
@RequiredArgsConstructor
public class ChatRoomFacade {

    private final ChatRoomService chatRoomService;

    /**
     * 같은 두 회원의 방을 동시에 만들면 한쪽은 활성 방 유일 제약(direct_key)에 걸려 롤백된다.
     * 새 트랜잭션에서 한 번 더 시도하면 먼저 만든 방을 재사용한다.
     */
    public ChatRoomCreateResult createOrGet(Long requesterId, Long targetUserId) {
        try {
            return chatRoomService.createOrGet(requesterId, targetUserId);
        } catch (DataIntegrityViolationException firstConflict) {
            try {
                return chatRoomService.createOrGet(requesterId, targetUserId);
            } catch (DataIntegrityViolationException secondConflict) {
                throw new ChatException(ChatErrorCode.CHAT_ROOM_BUSY);
            }
        }
    }

    public ChatRoomLeaveResult leave(Long chatRoomId, Long userId, Integer participationVersion) {
        return chatRoomService.leave(chatRoomId, userId, participationVersion);
    }

    public ChatRoomHistoryResponse getHistory(Long chatRoomId, Long adminId) {
        return chatRoomService.getHistory(chatRoomId, adminId);
    }
}
