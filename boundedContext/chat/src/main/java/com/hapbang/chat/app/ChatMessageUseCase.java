package com.hapbang.chat.app;

import java.time.LocalDateTime;
import java.util.List;

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
     */
    public SentChatMessage send(Long chatRoomId, Long senderId, String content) {
        if (!ChatMessage.hasContent(content)) {
            throw new ChatException(ChatErrorCode.EMPTY_MESSAGE);
        }
        ChatRoom chatRoom = chatRoomRepository.findByIdForUpdate(chatRoomId)
                .filter(room -> !room.isEnded())
                .orElseThrow(() -> new ChatException(ChatErrorCode.CHAT_ROOM_NOT_FOUND));
        List<ChatRoomMember> activeMembers = chatRoomMemberRepository.findByChatRoom_IdAndDeletedAtIsNull(chatRoomId)
                .stream()
                .filter(ChatRoomMember::isActive)
                .toList();
        if (activeMembers.stream().noneMatch(member -> member.getUserId().equals(senderId))) {
            throw new ChatException(ChatErrorCode.NOT_CHAT_ROOM_MEMBER);
        }
        chatValidationService.validateCanChat(senderId);
        List<Long> notifyUserIds = activeMembers.stream()
                .map(ChatRoomMember::getUserId)
                .filter(userId -> !userId.equals(senderId))
                .toList();
        if (notifyUserIds.isEmpty()) {
            throw new ChatException(ChatErrorCode.CHAT_PARTNER_LEFT);
        }

        ChatMessage message = chatMessageRepository.save(
                ChatMessage.text(chatRoom, senderId, content, LocalDateTime.now()));
        List<Long> deliverToUserIds = activeMembers.stream().map(ChatRoomMember::getUserId).toList();
        return new SentChatMessage(ChatMessageResponse.from(message), deliverToUserIds, notifyUserIds);
    }

}
