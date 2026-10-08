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
import com.hapbang.chat.app.dto.ChatMessageResponse;
import com.hapbang.chat.app.dto.ChatRoomCreateResult;
import com.hapbang.chat.app.dto.ChatRoomHistoryResponse;
import com.hapbang.chat.app.dto.ChatRoomLeaveResult;
import com.hapbang.chat.app.dto.ChatRoomLogResponse;
import com.hapbang.chat.app.dto.ChatRoomMemberResponse;
import com.hapbang.chat.app.dto.ChatRoomResponse;
import com.hapbang.chat.domain.ChatErrorCode;
import com.hapbang.chat.domain.ChatException;
import com.hapbang.chat.domain.ChatMessage;
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
     *     <li>두 사람의 종료되지 않은 방이 있으면 재사용하고, 퇴장했던 사람은 바로 재입장시킨다.
     *     재입장한 사람은 재입장 이전 메시지를 볼 수 없다.</li>
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
            ChatRoom chatRoom = existing.get();
            rejoinLeftMembers(chatRoom, now);
            return new ChatRoomCreateResult(toResponse(chatRoom), false);
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

    @Transactional(readOnly = true)
    public List<ChatRoomResponse> getMyRooms(Long userId) {
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
        return rooms.stream()
                .map(room -> ChatRoomResponse.of(room, toMemberResponses(membersByRoom.get(room.getId()), nicknames)))
                .toList();
    }

    /**
     * 채팅방을 나간다.
     * <ul>
     *     <li>메시지는 지우지 않는다. 나간 사람은 활성 참여가 아니므로 조회·수신할 수 없다.</li>
     *     <li>남은 사람이 볼 퇴장 안내 메시지를 같은 트랜잭션에서 남긴다.</li>
     *     <li>마지막 참여자가 나가면 방을 종료한다.</li>
     *     <li>이미 나갔거나 옛 참여 세대의 요청이면 아무것도 바꾸지 않는다(멱등).</li>
     * </ul>
     *
     * @param participationVersion 요청한 참여 세대. null이면 현재 세대로 본다.
     */
    public ChatRoomLeaveResult leave(Long chatRoomId, Long userId, Integer participationVersion) {
        ChatRoom chatRoom = chatRoomRepository.findByIdForUpdate(chatRoomId)
                .orElseThrow(() -> new ChatException(ChatErrorCode.CHAT_ROOM_NOT_FOUND));
        ChatRoomMember member = chatRoomMemberRepository
                .findByChatRoom_IdAndUserIdAndDeletedAtIsNull(chatRoomId, userId)
                .orElseThrow(() -> new ChatException(ChatErrorCode.NOT_CHAT_ROOM_MEMBER));
        if (!member.isActive() || !member.isCurrentParticipation(participationVersion)) {
            return ChatRoomLeaveResult.alreadyLeft();
        }
        if (chatRoom.isEnded()) {
            throw new ChatException(ChatErrorCode.CHAT_ROOM_NOT_FOUND);
        }

        LocalDateTime now = LocalDateTime.now();
        member.leave(now);
        chatRoomLogRepository.save(ChatRoomLog.record(chatRoom, userId, ChatRoomEventType.MEMBER_LEFT, now));
        ChatMessage leftNotice = chatMessageRepository.save(
                ChatMessage.leftNotice(chatRoom, userId, nicknameOf(userId), now));

        List<Long> remainingUserIds = chatRoomMemberRepository.findByChatRoom_IdAndDeletedAtIsNull(chatRoomId)
                .stream()
                .filter(ChatRoomMember::isActive)
                .map(ChatRoomMember::getUserId)
                .toList();
        boolean roomEnded = remainingUserIds.isEmpty();
        if (roomEnded) {
            chatRoom.end(now);
            chatRoomLogRepository.save(ChatRoomLog.record(chatRoom, userId, ChatRoomEventType.ROOM_ENDED, now));
        }
        log.info("채팅방 퇴장 chatRoomId={}, userId={}, roomEnded={}", chatRoomId, userId, roomEnded);
        return new ChatRoomLeaveResult(true, ChatMessageResponse.from(leftNotice), remainingUserIds, roomEnded);
    }

    /**
     * 관리자 분쟁 확인용 기록 조회. 종료된 방과 참여자별로 숨겨진 메시지까지 포함한다.
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
                chatRoom.getEndedAt(), toMemberResponses(members, findNicknames(members)), logs, messages);
    }

    private void rejoinLeftMembers(ChatRoom chatRoom, LocalDateTime now) {
        for (ChatRoomMember member : chatRoomMemberRepository.findByChatRoom_IdAndDeletedAtIsNull(chatRoom.getId())) {
            if (!member.isActive()) {
                member.rejoin(now);
                chatRoomLogRepository.save(
                        ChatRoomLog.record(chatRoom, member.getUserId(), ChatRoomEventType.MEMBER_REJOINED, now));
                log.info("채팅방 재입장 chatRoomId={}, userId={}, visibleFromSequence={}", chatRoom.getId(),
                        member.getUserId(), member.getVisibleFromSequence());
            }
        }
    }

    private String nicknameOf(Long userId) {
        return chatUserRepository.findById(userId).map(ChatUser::getNickname).orElse("회원 " + userId);
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
