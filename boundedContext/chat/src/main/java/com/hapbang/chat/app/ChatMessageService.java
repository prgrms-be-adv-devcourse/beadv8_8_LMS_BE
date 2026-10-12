package com.hapbang.chat.app;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.chat.app.dto.ChatMessagePageResponse;
import com.hapbang.chat.app.dto.ChatMessageResponse;
import com.hapbang.chat.app.dto.SentChatMessage;
import com.hapbang.chat.domain.ChatErrorCode;
import com.hapbang.chat.domain.ChatException;
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
public class ChatMessageService {

    private final ChatRoomRepository chatRoomRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final ChatRoomMemberRepository chatRoomMemberRepository;
    private final ChatValidationService chatValidationService;

    /**
     * 메시지를 보낸다. 방을 잠그고 순번을 발급한다. 상대가 나간 동안에는 보낼 수 없다(조회만 허용).
     */
    public SentChatMessage send(Long chatRoomId, Long senderId, String content) {
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

    /**
     * 참여자 본인의 조회 범위 안에서 최신순으로 조회한다. cursor는 방 메시지 순번이며, 없으면 가장 최근부터 조회한다.
     * 오래된 cursor를 보내도 조회 시작점 이전 메시지는 나오지 않는다.
     */
    @Transactional(readOnly = true)
    public ChatMessagePageResponse getMessages(Long chatRoomId, Long userId, Long cursor, int size) {
        chatRoomRepository.findById(chatRoomId)
                .filter(room -> !room.isEnded() && room.getDeletedAt() == null)
                .orElseThrow(() -> new ChatException(ChatErrorCode.CHAT_ROOM_NOT_FOUND));
        ChatRoomMember member = chatRoomMemberRepository
                .findByChatRoom_IdAndUserIdAndDeletedAtIsNull(chatRoomId, userId)
                .filter(ChatRoomMember::isActive)
                .orElseThrow(() -> new ChatException(ChatErrorCode.NOT_CHAT_ROOM_MEMBER));
        chatValidationService.validateCanChat(userId);

        long beforeSequence = cursor == null ? Long.MAX_VALUE : cursor;
        List<ChatMessageResponse> messages = chatMessageRepository
                .findVisible(chatRoomId, member.getVisibleFromSequence(), beforeSequence, Limit.of(size + 1))
                .stream()
                .map(ChatMessageResponse::from)
                .toList();
        if (messages.size() <= size) {
            return new ChatMessagePageResponse(messages, null);
        }
        List<ChatMessageResponse> page = messages.subList(0, size);
        return new ChatMessagePageResponse(page, page.getLast().roomSequence());
    }
}
