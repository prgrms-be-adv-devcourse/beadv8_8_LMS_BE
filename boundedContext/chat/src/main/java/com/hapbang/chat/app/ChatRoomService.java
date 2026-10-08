package com.hapbang.chat.app;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.chat.app.dto.ChatMessageHistoryResponse;
import com.hapbang.chat.app.dto.ChatRoomCreateResult;
import com.hapbang.chat.app.dto.ChatRoomHistoryResponse;
import com.hapbang.chat.app.dto.ChatRoomLogResponse;
import com.hapbang.chat.app.dto.ChatRoomMemberResponse;
import com.hapbang.chat.app.dto.ChatRoomResponse;
import com.hapbang.chat.domain.ChatErrorCode;
import com.hapbang.chat.domain.ChatException;
import com.hapbang.chat.domain.ChatRoom;
import com.hapbang.chat.domain.ChatRoomEventType;
import com.hapbang.chat.domain.ChatRoomLog;
import com.hapbang.chat.domain.ChatRoomMember;
import com.hapbang.chat.domain.ChatUser;
import com.hapbang.chat.out.ChatMessageRepository;
import com.hapbang.chat.out.ChatRoomLogRepository;
import com.hapbang.chat.out.ChatRoomMemberRepository;
import com.hapbang.chat.out.ChatRoomRepository;
import com.hapbang.chat.out.ChatUserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class ChatRoomService {

    private final ChatRoomRepository chatRoomRepository;
    private final ChatRoomMemberRepository chatRoomMemberRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final ChatRoomLogRepository chatRoomLogRepository;
    private final ChatUserRepository chatUserRepository;
    private final ChatValidationService chatValidationService;

    /**
     * 1:1 채팅을 시작한다.
     * <ul>
     *     <li>두 사람의 방이 있으면 재사용한다.</li>
     *     <li>없으면 새 방을 만든다. 동시에 만들면 유일 제약에 걸리므로 {@link ChatRoomFacade}가 다시 시도한다.</li>
     * </ul>
     */
    public ChatRoomCreateResult createOrGet(Long requesterId, Long targetUserId) {
        if (requesterId == null || targetUserId == null) {
            throw new ChatException(ChatErrorCode.INVALID_REQUEST);
        }
        if (requesterId.equals(targetUserId)) {
            throw new ChatException(ChatErrorCode.CANNOT_CHAT_WITH_SELF);
        }
        chatValidationService.validateCanChat(requesterId);
        chatValidationService.validateCanChat(targetUserId);

        LocalDateTime now = LocalDateTime.now();
        Optional<ChatRoom> existing = chatRoomRepository.findActiveDirectForUpdate(
                ChatRoom.directKeyOf(requesterId, targetUserId));
        if (existing.isPresent()) {
            return new ChatRoomCreateResult(toResponse(existing.get()), false);
        }

        ChatRoom chatRoom = chatRoomRepository.saveAndFlush(ChatRoom.createDirect(requesterId, targetUserId, now));
        chatRoomLogRepository.save(ChatRoomLog.record(chatRoom, requesterId, ChatRoomEventType.ROOM_CREATED, now));
        for (Long userId : List.of(requesterId, targetUserId)) {
            chatRoomMemberRepository.save(ChatRoomMember.join(chatRoom, userId, now));
            chatRoomLogRepository.save(ChatRoomLog.record(chatRoom, userId, ChatRoomEventType.MEMBER_JOINED, now));
        }
        log.info("채팅방 생성 chatRoomId={}, requesterId={}, targetUserId={}", chatRoom.getId(), requesterId,
                targetUserId);
        return new ChatRoomCreateResult(toResponse(chatRoom), true);
    }

    /**
     * 관리자 분쟁 확인용 기록 조회. Soft Delete 된 메시지도 포함한다.
     */
    @Transactional(readOnly = true)
    public ChatRoomHistoryResponse getHistory(Long chatRoomId, Long adminId) {
        chatValidationService.validateAdmin(adminId);
        ChatRoom chatRoom = chatRoomRepository.findById(chatRoomId)
                .orElseThrow(() -> new ChatException(ChatErrorCode.CHAT_ROOM_NOT_FOUND));
        List<ChatRoomMember> members = chatRoomMemberRepository.findByChatRoom_IdAndDeletedAtIsNull(chatRoomId);
        List<ChatRoomLogResponse> logs = chatRoomLogRepository.findByChatRoom_IdOrderByIdAsc(chatRoomId).stream()
                .map(ChatRoomLogResponse::from)
                .toList();
        List<ChatMessageHistoryResponse> messages = chatMessageRepository
                .findByChatRoom_IdOrderByRoomSequenceAsc(chatRoomId).stream()
                .map(ChatMessageHistoryResponse::from)
                .toList();
        return new ChatRoomHistoryResponse(chatRoom.getId(), chatRoom.getStatus(), chatRoom.getCreatedAt(),
                toMemberResponses(members, findNicknames(members)), logs, messages);
    }

    private ChatRoomResponse toResponse(ChatRoom chatRoom) {
        List<ChatRoomMember> members = chatRoomMemberRepository.findByChatRoom_IdAndDeletedAtIsNull(chatRoom.getId());
        return ChatRoomResponse.of(chatRoom, toMemberResponses(members, findNicknames(members)));
    }

    private Map<Long, String> findNicknames(List<ChatRoomMember> members) {
        List<Long> userIds = members.stream().map(ChatRoomMember::getUserId).distinct().toList();
        return chatUserRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(ChatUser::getId, ChatUser::getNickname));
    }

    private List<ChatRoomMemberResponse> toMemberResponses(List<ChatRoomMember> members, Map<Long, String> nicknames) {
        return members.stream()
                .sorted(Comparator.comparing(ChatRoomMember::getId))
                .map(member -> ChatRoomMemberResponse.of(member, nicknames.get(member.getUserId())))
                .toList();
    }
}
