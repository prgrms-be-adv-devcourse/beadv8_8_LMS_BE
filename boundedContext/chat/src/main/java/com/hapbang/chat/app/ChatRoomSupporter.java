package com.hapbang.chat.app;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.chat.app.dto.ChatMessageHistoryResponse;
import com.hapbang.chat.app.dto.ChatMessageResponse;
import com.hapbang.chat.app.dto.ChatRoomHistoryResponse;
import com.hapbang.chat.app.dto.ChatRoomLogResponse;
import com.hapbang.chat.app.dto.ChatRoomMemberResponse;
import com.hapbang.chat.app.dto.ChatRoomResponse;
import com.hapbang.chat.app.exception.ChatErrorCode;
import com.hapbang.chat.app.exception.ChatException;
import com.hapbang.chat.domain.ChatRoom;
import com.hapbang.chat.domain.ChatRoomMember;
import com.hapbang.chat.domain.ChatUser;
import com.hapbang.chat.out.ChatMessageRepository;
import com.hapbang.chat.out.ChatRoomLogRepository;
import com.hapbang.chat.out.ChatRoomMemberRepository;
import com.hapbang.chat.out.ChatRoomRepository;
import com.hapbang.chat.out.ChatRoomUnreadCount;
import com.hapbang.chat.out.ChatUserRepository;

import lombok.RequiredArgsConstructor;

/**
 * 채팅방 읽기 전용 기능. 쓰기({@link ChatRoomUseCase})와 나눈다.
 * 응답·닉네임 조회는 {@link ChatRoomUseCase}도 쓴다. 쓰기 트랜잭션 안에서 부르면 그 트랜잭션에 참여한다.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class ChatRoomSupporter {

    private final ChatRoomRepository chatRoomRepository;
    private final ChatRoomMemberRepository chatRoomMemberRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final ChatRoomLogRepository chatRoomLogRepository;
    private final ChatUserRepository chatUserRepository;
    private final ChatValidationService chatValidationService;

    public List<ChatRoomResponse> getMyRooms(Long userId) {
        chatValidationService.validateCanChat(userId);
        List<ChatRoom> rooms = chatRoomMemberRepository.findActiveByUserId(userId).stream()
                .map(ChatRoomMember::getChatRoom)
                .toList();
        if (rooms.isEmpty()) {
            return List.of();
        }
        Map<Long, List<ChatRoomMember>> membersByRoom = chatRoomMemberRepository
                .findByChatRoom_IdInAndDeletedAtIsNull(rooms.stream().map(ChatRoom::getId).toList()).stream()
                .collect(Collectors.groupingBy(member -> member.getChatRoom().getId()));
        Map<Long, String> nicknames = findNicknames(membersByRoom.values().stream().flatMap(List::stream).toList());
        List<Long> roomIds = rooms.stream().map(ChatRoom::getId).toList();
        Map<Long, ChatMessageResponse> lastMessages = findLastMessages(userId, roomIds);
        Map<Long, Long> unreadCounts = countUnread(userId, roomIds);
        return rooms.stream()
                .map(room -> ChatRoomResponse.of(room, toMemberResponses(membersByRoom.get(room.getId()), nicknames),
                        lastMessages.get(room.getId()), unreadCounts.getOrDefault(room.getId(), 0L)))
                .toList();
    }

    /**
     * 관리자 분쟁 확인용 기록 조회. 종료된 방과 참여자별로 숨겨진 메시지까지 포함한다.
     */
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
                chatRoom.getEndedAt(), toMemberResponses(members, findNicknames(members)), logs, messages);
    }

    /** 채팅방 응답 (참여자·닉네임, 조회한 회원 기준 마지막 메시지·안 읽은 수 포함). 채팅 시작 응답에도 쓴다 */
    public ChatRoomResponse toResponse(ChatRoom chatRoom, Long userId) {
        List<ChatRoomMember> members = chatRoomMemberRepository.findByChatRoom_IdAndDeletedAtIsNull(chatRoom.getId());
        List<Long> roomIds = List.of(chatRoom.getId());
        return ChatRoomResponse.of(chatRoom, toMemberResponses(members, findNicknames(members)),
                findLastMessages(userId, roomIds).get(chatRoom.getId()),
                countUnread(userId, roomIds).getOrDefault(chatRoom.getId(), 0L));
    }

    /** 표시용 닉네임. 탈퇴 회원은 '탈퇴한 회원', 복제 정보가 없으면 '회원 {id}' */
    public String displayNicknameOf(Long userId) {
        return chatUserRepository.findById(userId).map(ChatUser::getDisplayNickname).orElse("회원 " + userId);
    }

    private Map<Long, ChatMessageResponse> findLastMessages(Long userId, List<Long> chatRoomIds) {
        return chatMessageRepository.findLastVisible(userId, chatRoomIds).stream()
                .collect(Collectors.toMap(message -> message.getChatRoom().getId(), ChatMessageResponse::from));
    }

    private Map<Long, Long> countUnread(Long userId, List<Long> chatRoomIds) {
        return chatMessageRepository.countUnread(userId, chatRoomIds).stream()
                .collect(Collectors.toMap(ChatRoomUnreadCount::chatRoomId, ChatRoomUnreadCount::unreadCount));
    }

    private Map<Long, String> findNicknames(List<ChatRoomMember> members) {
        List<Long> userIds = members.stream().map(ChatRoomMember::getUserId).distinct().toList();
        return chatUserRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(ChatUser::getId, ChatUser::getDisplayNickname));
    }

    private List<ChatRoomMemberResponse> toMemberResponses(List<ChatRoomMember> members, Map<Long, String> nicknames) {
        return members.stream()
                .sorted(Comparator.comparing(ChatRoomMember::getId))
                .map(member -> ChatRoomMemberResponse.of(member, nicknames.get(member.getUserId())))
                .toList();
    }
}
