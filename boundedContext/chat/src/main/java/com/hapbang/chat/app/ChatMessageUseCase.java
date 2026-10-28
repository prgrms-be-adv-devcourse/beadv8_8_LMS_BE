package com.hapbang.chat.app;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.chat.app.dto.ChatMessageResponse;
import com.hapbang.chat.app.dto.SentChatMessage;
import com.hapbang.chat.app.exception.ChatErrorCode;
import com.hapbang.chat.app.exception.ChatException;
import com.hapbang.chat.domain.ChatMessage;
import com.hapbang.chat.domain.ChatRoom;
import com.hapbang.chat.domain.ChatRoomMember;
import com.hapbang.chat.out.ChatMessageRepository;
import com.hapbang.chat.out.ChatRoomMemberRepository;
import com.hapbang.chat.out.ChatRoomRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class ChatMessageUseCase {

    private final ChatRoomRepository chatRoomRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final ChatRoomMemberRepository chatRoomMemberRepository;
    private final ChatValidationService chatValidationService;

    /**
     * 메시지를 보낸다. 방을 잠그고 순번을 발급한다. 상대가 나간 동안에는 보낼 수 없다(조회만 허용).
     * <p>
     * 같은 참여 안에서 같은 전송 ID로 이미 저장한 메시지가 있으면(응답을 놓친 클라이언트의 재전송) 새로 저장하지 않고
     * 기존 메시지를 보낸 사람에게만 다시 전달한다. 본문이 달라도 기존 메시지를 돌려준다. 재입장 이전에 쓴 전송 ID는 새 메시지로 저장한다(#311). 알림·안 읽은 수는 다시 올리지 않는다(#261). 같은 방의 전송은 방 잠금으로 줄을 서므로
     * 동시 재전송도 한 번만 저장된다.
     *
     * @param clientMessageId 보낸 사람이 붙인 전송 ID. 없으면 중복을 확인하지 않는다
     */
    public SentChatMessage send(Long chatRoomId, Long senderId, String content, String clientMessageId) {
        if (!ChatMessage.hasContent(content)) {
            throw new ChatException(ChatErrorCode.EMPTY_MESSAGE);
        }
        if (!ChatMessage.fitsLength(content)) {
            throw new ChatException(ChatErrorCode.INVALID_REQUEST,
                    "메시지는 " + ChatMessage.MAX_CONTENT_LENGTH + "자 이하여야 합니다.");
        }
        ChatRoom chatRoom = chatRoomRepository.findByIdForUpdate(chatRoomId)
                .filter(room -> !room.isEnded())
                .orElseThrow(() -> new ChatException(ChatErrorCode.CHAT_ROOM_NOT_FOUND));
        List<ChatRoomMember> activeMembers = chatRoomMemberRepository.findByChatRoom_IdAndDeletedAtIsNull(chatRoomId)
                .stream()
                .filter(ChatRoomMember::isActive)
                .toList();
        ChatRoomMember sender = activeMembers.stream()
                .filter(member -> member.getUserId().equals(senderId))
                .findFirst()
                .orElseThrow(() -> new ChatException(ChatErrorCode.NOT_CHAT_ROOM_MEMBER));
        chatValidationService.validateCanChat(senderId);
        if (clientMessageId != null) {
            // 재전송은 같은 참여 안에서만 본다. 재입장 이전 메시지는 재입장 때 전송 ID를 비우지만, 한 번 더 확인한다(#311)
            Optional<ChatMessage> resent = chatMessageRepository
                    .findByChatRoom_IdAndSenderUserIdAndClientMessageId(chatRoomId, senderId, clientMessageId)
                    .filter(message -> sender.canView(message.getRoomSequence()));
            if (resent.isPresent()) {
                log.info("재전송이라 기존 메시지를 돌려줌 chatRoomId={}, messageId={}", chatRoomId, resent.get().getId());
                return new SentChatMessage(ChatMessageResponse.from(resent.get()), List.of(senderId), List.of());
            }
        }
        List<Long> notifyUserIds = activeMembers.stream()
                .map(ChatRoomMember::getUserId)
                .filter(userId -> !userId.equals(senderId))
                .toList();
        if (notifyUserIds.isEmpty()) {
            throw new ChatException(ChatErrorCode.CHAT_PARTNER_LEFT);
        }

        ChatMessage message = chatMessageRepository.save(
                ChatMessage.text(chatRoom, senderId, content, clientMessageId, LocalDateTime.now()));
        List<Long> deliverToUserIds = activeMembers.stream().map(ChatRoomMember::getUserId).toList();
        return new SentChatMessage(ChatMessageResponse.from(message), deliverToUserIds, notifyUserIds);
    }

}
