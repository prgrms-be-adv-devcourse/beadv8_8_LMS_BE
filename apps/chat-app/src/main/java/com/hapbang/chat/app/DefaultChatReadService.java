package com.hapbang.chat.app;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.chat.app.exception.ChatErrorCode;
import com.hapbang.chat.app.exception.ChatException;
import com.hapbang.chat.domain.ChatRoom;
import com.hapbang.chat.domain.ChatRoomMember;
import com.hapbang.chat.out.ChatRoomMemberRepository;
import com.hapbang.chat.out.ChatRoomRepository;

import lombok.RequiredArgsConstructor;

/**
 * {@link ChatReadService}의 구현. 짧은 트랜잭션에서 회원 본인의 참여 행 하나만 조건부로 갱신한다.
 * <ul>
 *     <li>메시지 전송과 겹쳐도 되도록 방을 잠그지 않는다. 그 사이 발급된 순번은 다음 읽음 처리에 반영된다.</li>
 *     <li>확인한 참여 세대(요청 값, 없으면 이 트랜잭션에서 읽은 현재 세대)를 갱신 조건에 넣는다. 확인과 갱신 사이에
 *     퇴장·재입장이 커밋되면 반영하지 않고, 바뀐 행이 없으면 다시 읽어 결과를 정한다.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class DefaultChatReadService implements ChatReadService {

    private final ChatRoomRepository chatRoomRepository;
    private final ChatRoomMemberRepository chatRoomMemberRepository;
    private final ChatValidationService chatValidationService;

    @Override
    @Transactional
    public void markRead(Long chatRoomId, Long userId, long lastReadSequence, Integer participationVersion) {
        ChatRoom chatRoom = chatRoomRepository.findById(chatRoomId)
                .filter(room -> !room.isEnded() && room.getDeletedAt() == null)
                .orElseThrow(() -> new ChatException(ChatErrorCode.CHAT_ROOM_NOT_FOUND));
        ChatRoomMember member = findActiveMember(chatRoomId, userId);
        chatValidationService.validateCanChat(userId);
        int checkedVersion = participationVersion == null ? member.getParticipationVersion() : participationVersion;
        validateCurrentParticipation(member, checkedVersion);
        long sequence = chatRoom.readableSequence(lastReadSequence);

        int updated = chatRoomMemberRepository.advanceLastReadSequenceInParticipation(chatRoomId, userId,
                checkedVersion, sequence);
        if (updated == 1) {
            return;
        }
        // 바뀐 행이 없다: 이미 같거나 큰 값이거나, 확인 뒤 퇴장·재입장했다. 커밋된 최신 상태를 다시 읽는다
        ChatRoomMember current = findActiveMember(chatRoomId, userId);
        validateCurrentParticipation(current, checkedVersion);
        if (current.getLastReadSequence() < sequence) {
            // 같은 참여 세대에서는 읽음 순번이 줄지 않으므로 오지 않는 경로다
            throw new IllegalStateException("읽음 순번을 반영하지 못했습니다. chatRoomId=" + chatRoomId
                    + ", userId=" + userId + ", participationVersion=" + checkedVersion);
        }
    }

    private ChatRoomMember findActiveMember(Long chatRoomId, Long userId) {
        return chatRoomMemberRepository.findByChatRoom_IdAndUserIdAndDeletedAtIsNull(chatRoomId, userId)
                .filter(ChatRoomMember::isActive)
                .orElseThrow(() -> new ChatException(ChatErrorCode.NOT_CHAT_ROOM_MEMBER));
    }

    private static void validateCurrentParticipation(ChatRoomMember member, int participationVersion) {
        if (member.getParticipationVersion() != participationVersion) {
            throw new ChatException(ChatErrorCode.STALE_PARTICIPATION);
        }
    }
}
