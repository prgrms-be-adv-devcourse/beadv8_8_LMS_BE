package com.hapbang.chat.app;

import java.util.List;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;

import com.hapbang.chat.app.dto.ChatRoomCreateResult;
import com.hapbang.chat.app.dto.ChatRoomOpenResult;
import com.hapbang.chat.app.dto.ChatRoomHistoryResponse;
import com.hapbang.chat.app.dto.ChatRoomLeaveResult;
import com.hapbang.chat.app.dto.ChatRoomResponse;
import com.hapbang.chat.app.dto.ChatSubscriptionReason;
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
    private final ChatReadService chatReadService;
    private final ChatRoomTopicService chatRoomTopicService;

    /**
     * 같은 두 회원의 방을 동시에 만들면 한쪽은 활성 방 유일 제약(direct_key)에 걸려 롤백된다.
     * 새 트랜잭션에서 한 번 더 시도하면 먼저 만든 방을 재사용한다. 이 Facade는 트랜잭션을 열지 않으므로
     * 시도마다 UseCase 프록시가 새 저장 트랜잭션을 연다.
     * <p>
     * 방 응답(참여자·닉네임·마지막 메시지·안 읽은 수)은 저장 트랜잭션이 커밋된 뒤 별도 읽기 트랜잭션으로 조립한다(설계 4.6).
     */
    @PreAuthorize(ChatRoles.CHAT)
    public ChatRoomCreateResult createOrGet(Long requesterId, Long targetUserId) {
        ChatRoomOpenResult opened = openWithRetry(requesterId, targetUserId);
        return new ChatRoomCreateResult(chatRoomSupporter.toResponse(opened.chatRoomId(), requesterId),
                opened.created());
    }

    private ChatRoomOpenResult openWithRetry(Long requesterId, Long targetUserId) {
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

    /**
     * 나간다. 퇴장이 커밋된 뒤 그 회원의 그 방 로컬 방 topic 구독 중 끝난 세대 이하의 구독만 회수한다(FE가 바로 알게 하는 것이다.
     * 이 회수가 늦게 실행돼도 재입장한 새 세대의 구독은 남는다. 퇴장 뒤 저장된 메시지를 막는 보장은 전달할 때의 버전 확인 규칙이
     * 한다).
     */
    public ChatRoomLeaveResult leave(Long chatRoomId, Long userId, Integer participationVersion) {
        ChatRoomLeaveResult result = chatRoomUseCase.leave(chatRoomId, userId, participationVersion);
        if (result.left()) {
            chatRoomTopicService.revokeMember(chatRoomId, userId, result.leftParticipationVersion(),
                    ChatSubscriptionReason.LEFT);
        }
        return result;
    }

    /**
     * @param participationVersion 읽은 참여 세대. null이면 처리 시점의 현재 세대로 본다
     */
    @PreAuthorize(ChatRoles.CHAT)
    public void markRead(Long chatRoomId, Long userId, long lastReadSequence, Integer participationVersion) {
        chatReadService.markRead(chatRoomId, userId, lastReadSequence, participationVersion);
    }

    @PreAuthorize(ChatRoles.ADMIN)
    public ChatRoomHistoryResponse getHistory(Long chatRoomId, Long adminId, String cursor, int size) {
        return chatRoomSupporter.getHistory(chatRoomId, adminId, cursor, size);
    }
}
