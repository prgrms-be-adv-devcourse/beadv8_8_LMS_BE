package com.hapbang.chat.app;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.chat.app.ChatUnreadMemberCounter.MessageCounts;
import com.hapbang.chat.app.dto.ChatMessagePageResponse;
import com.hapbang.chat.app.dto.ChatMessageResponse;
import com.hapbang.chat.app.exception.ChatErrorCode;
import com.hapbang.chat.app.exception.ChatException;
import com.hapbang.chat.domain.ChatMessage;
import com.hapbang.chat.domain.ChatUser;
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
    private final ChatMessageSenders chatMessageSenders;
    private final ChatUnreadMemberCounter chatUnreadMemberCounter;

    /**
     * 참여자 본인의 조회 범위 안에서 최신순으로 조회한다. cursor는 방 메시지 순번이며, 없으면 가장 최근부터 조회한다.
     * 오래된 cursor를 보내도 조회 시작점 이전 메시지는 나오지 않는다.
     * 응답에 현재 참여 세대와 조회 시작점을 담아, 클라이언트가 세대 변경과 조회 완료 구간을 판단하게 한다.
     * 일반 메시지마다 안 읽은 사람 수를 담고, 페이지에 그 값을 센 시점(버전)을 담는다. 페이지의 일반 메시지를 집계 문장 하나로 센다.
     */
    public ChatMessagePageResponse getMessages(Long chatRoomId, Long userId, Long cursor, int size) {
        chatRoomRepository.findById(chatRoomId)
                .filter(room -> room.isViewable(LocalDateTime.now()))
                .orElseThrow(() -> new ChatException(ChatErrorCode.CHAT_ROOM_NOT_FOUND));
        ChatRoomMember member = chatRoomMemberRepository
                .findByChatRoom_IdAndUserIdAndDeletedAtIsNull(chatRoomId, userId)
                .filter(ChatRoomMember::isActive)
                .orElseThrow(() -> new ChatException(ChatErrorCode.NOT_CHAT_ROOM_MEMBER));
        chatValidationService.validateCanChat(userId);

        long beforeSequence = cursor == null ? Long.MAX_VALUE : cursor;
        List<ChatMessage> found = chatMessageRepository
                .findVisible(chatRoomId, member.getVisibleFromSequence(), beforeSequence, Limit.of(size + 1));
        List<ChatMessage> page = found.size() <= size ? found : found.subList(0, size);
        // 발신자 복제본은 페이지마다 한 번에 읽는다. 조회 시점의 닉네임이라 바뀐 닉네임이 지난 메시지에도 보인다
        Map<Long, ChatUser> senders = chatMessageSenders.usersOf(page);
        MessageCounts counts = chatUnreadMemberCounter.countMessages(page);
        List<ChatMessageResponse> messages = page.stream()
                .map(message -> ChatMessageResponse.of(message, userId, senders)
                        .withReadCount(counts.of(message.getId()), counts.version()))
                .toList();
        Long nextCursor = found.size() > size ? messages.getLast().roomSequence() : null;
        return new ChatMessagePageResponse(messages, nextCursor, member.getParticipationVersion(),
                member.getVisibleFromSequence(), counts.version());
    }
}
