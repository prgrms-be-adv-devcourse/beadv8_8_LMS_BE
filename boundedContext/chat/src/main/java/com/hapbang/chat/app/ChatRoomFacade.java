package com.hapbang.chat.app;

import java.util.List;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;

import com.hapbang.chat.app.dto.ChatRoomCreateResult;
import com.hapbang.chat.app.dto.ChatRoomHistoryResponse;
import com.hapbang.chat.app.dto.ChatRoomLeaveResult;
import com.hapbang.chat.app.dto.ChatRoomResponse;
import com.hapbang.chat.app.exception.ChatErrorCode;
import com.hapbang.chat.app.exception.ChatException;

import lombok.RequiredArgsConstructor;

/**
 * Controller가 채팅방 기능을 쓰는 진입점. Controller는 UseCase를 직접 쓰지 않고 이 Facade를 통한다.
 * 역할 검사({@code @PreAuthorize})는 여기서 한다(팀 규칙). 채팅방 나가기는 강등·정지된 회원도 나갈 수 있게 검사하지 않는다.
 */
@Component
@RequiredArgsConstructor
public class ChatRoomFacade {

    private final ChatRoomUseCase chatRoomUseCase;
    private final ChatRoomSupporter chatRoomSupporter;

    /**
     * 같은 두 회원의 방을 동시에 만들면 한쪽은 활성 방 유일 제약(direct_key)에 걸려 롤백된다.
     * 새 트랜잭션에서 한 번 더 시도하면 먼저 만든 방을 재사용한다.
     */
    @PreAuthorize(ChatRoles.CHAT)
    public ChatRoomCreateResult createOrGet(Long requesterId, Long targetUserId) {
        try {
            return chatRoomUseCase.createOrGet(requesterId, targetUserId);
        } catch (DataIntegrityViolationException firstConflict) {
            try {
                return chatRoomUseCase.createOrGet(requesterId, targetUserId);
            } catch (DataIntegrityViolationException secondConflict) {
                throw new ChatException(ChatErrorCode.CHAT_ROOM_BUSY);
            }
        }
    }

    @PreAuthorize(ChatRoles.CHAT)
    public List<ChatRoomResponse> getMyRooms(Long userId) {
        return chatRoomSupporter.getMyRooms(userId);
    }

    public ChatRoomLeaveResult leave(Long chatRoomId, Long userId, Integer participationVersion) {
        return chatRoomUseCase.leave(chatRoomId, userId, participationVersion);
    }

    @PreAuthorize(ChatRoles.CHAT)
    public void markRead(Long chatRoomId, Long userId, long lastReadSequence) {
        chatRoomUseCase.markRead(chatRoomId, userId, lastReadSequence);
    }

    @PreAuthorize(ChatRoles.ADMIN)
    public ChatRoomHistoryResponse getHistory(Long chatRoomId, Long adminId, String cursor, int size) {
        return chatRoomSupporter.getHistory(chatRoomId, adminId, cursor, size);
    }
}
