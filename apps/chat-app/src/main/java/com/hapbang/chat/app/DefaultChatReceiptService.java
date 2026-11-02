package com.hapbang.chat.app;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.chat.app.dto.ChatReceiptResponse;
import com.hapbang.chat.app.exception.ChatErrorCode;
import com.hapbang.chat.app.exception.ChatException;
import com.hapbang.chat.domain.ChatRoom;
import com.hapbang.chat.domain.ChatRoomMember;
import com.hapbang.chat.out.ChatRoomMemberRepository;
import com.hapbang.chat.out.ChatRoomRepository;

import lombok.RequiredArgsConstructor;

/**
 * {@link ChatReceiptService}의 구현. 짧은 트랜잭션에서 회원 본인의 참여 행 하나만 조건부로 갱신한다.
 * <ul>
 *     <li>메시지 순번 발급용 방 잠금({@code PESSIMISTIC_WRITE})을 잡지 않는다. 방과 참여 행은 잠그지 않고 읽는다.</li>
 *     <li>방 참여자 전체를 읽거나, 수신자별 행을 추가하거나, 외부 I/O·재방송을 하지 않는다.</li>
 *     <li>사전 확인과 갱신 사이에 퇴장·재입장이 끼어들 수 있다. 갱신 조건에 활성 참여와 참여 세대를 넣어 옛 세대의 수신 확인이
 *     새 세대에 반영되지 않게 하고, 바뀐 행이 없으면 다시 읽어 결과를 정한다.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class DefaultChatReceiptService implements ChatReceiptService {

    private final ChatRoomRepository chatRoomRepository;
    private final ChatRoomMemberRepository chatRoomMemberRepository;
    private final ChatValidationService chatValidationService;

    @Override
    @Transactional
    public ChatReceiptResponse markReceived(Long chatRoomId, Long userId, int participationVersion,
            long receivedSequence) {
        ChatRoom chatRoom = chatRoomRepository.findById(chatRoomId)
                .filter(room -> !room.isEnded() && room.getDeletedAt() == null)
                .orElseThrow(() -> new ChatException(ChatErrorCode.CHAT_ROOM_NOT_FOUND));
        ChatRoomMember member = findActiveMember(chatRoomId, userId);
        chatValidationService.validateCanChat(userId);
        validateCurrentParticipation(member, participationVersion);
        // 범위 밖 순번은 마지막 순번으로 보정해 성공시키지 않는다. 실시간 전달은 커밋 뒤라 클라이언트가 받은 순번은 여기서 읽은
        // 마지막 순번 이하다
        if (receivedSequence < member.getVisibleFromSequence() || receivedSequence > chatRoom.getLastSequence()) {
            throw new ChatException(ChatErrorCode.RECEIVED_SEQUENCE_OUT_OF_RANGE);
        }

        int updated = chatRoomMemberRepository.advanceLastReceivedSequence(chatRoomId, userId, participationVersion,
                receivedSequence);
        if (updated == 1) {
            return new ChatReceiptResponse(chatRoomId, participationVersion, receivedSequence);
        }
        // 바뀐 행이 없다: 이미 같거나 큰 값이거나, 사전 확인 뒤 퇴장·재입장했다. 커밋된 최신 상태를 다시 읽는다
        ChatRoomMember current = findActiveMember(chatRoomId, userId);
        validateCurrentParticipation(current, participationVersion);
        Long lastReceivedSequence = current.getLastReceivedSequence();
        if (lastReceivedSequence == null || lastReceivedSequence < receivedSequence) {
            // 같은 참여 세대에서는 수신 확인 순번이 줄거나 비워지지 않으므로 오지 않는 경로다
            throw new IllegalStateException("수신 확인 순번을 반영하지 못했습니다. chatRoomId=" + chatRoomId
                    + ", userId=" + userId + ", participationVersion=" + participationVersion);
        }
        return new ChatReceiptResponse(chatRoomId, participationVersion, lastReceivedSequence);
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
