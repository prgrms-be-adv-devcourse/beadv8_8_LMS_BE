package com.hapbang.chat.app;

import java.util.List;

import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.chat.app.dto.ChatMessagePageResponse;
import com.hapbang.chat.app.dto.ChatMessageResponse;
import com.hapbang.chat.app.exception.ChatErrorCode;
import com.hapbang.chat.app.exception.ChatException;
import com.hapbang.chat.domain.ChatRoomMember;
import com.hapbang.chat.out.ChatMessageRepository;
import com.hapbang.chat.out.ChatRoomMemberRepository;
import com.hapbang.chat.out.ChatRoomRepository;

import lombok.RequiredArgsConstructor;

/** 메시지 읽기 전용 기능. 쓰기({@link ChatMessageUseCase})와 나눈다 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class ChatMessageSupporter {

    private final ChatRoomRepository chatRoomRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final ChatRoomMemberRepository chatRoomMemberRepository;
    private final ChatValidationService chatValidationService;

    /**
     * 참여자 본인의 조회 범위 안에서 최신순으로 조회한다. cursor는 방 메시지 순번이며, 없으면 가장 최근부터 조회한다.
     * 오래된 cursor를 보내도 조회 시작점 이전 메시지는 나오지 않는다.
     */
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
                .map(message -> ChatMessageResponse.of(message, userId))
                .toList();
        if (messages.size() <= size) {
            return new ChatMessagePageResponse(messages, null);
        }
        List<ChatMessageResponse> page = messages.subList(0, size);
        return new ChatMessagePageResponse(page, page.getLast().roomSequence());
    }
}
