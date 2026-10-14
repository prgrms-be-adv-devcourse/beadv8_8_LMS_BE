package com.hapbang.chat.app;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.chat.app.dto.ChatMessageResponse;
import com.hapbang.chat.app.dto.ChatRoomCreateResult;
import com.hapbang.chat.app.dto.ChatRoomLeaveResult;
import com.hapbang.chat.app.exception.ChatErrorCode;
import com.hapbang.chat.app.exception.ChatException;
import com.hapbang.chat.domain.ChatMessage;
import com.hapbang.chat.domain.ChatRoom;
import com.hapbang.chat.domain.ChatRoomEventType;
import com.hapbang.chat.domain.ChatRoomLog;
import com.hapbang.chat.domain.ChatRoomMember;
import com.hapbang.chat.out.ChatMessageRepository;
import com.hapbang.chat.out.ChatRoomLogRepository;
import com.hapbang.chat.out.ChatRoomMemberRepository;
import com.hapbang.chat.out.ChatRoomRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class ChatRoomUseCase {

    private final ChatRoomRepository chatRoomRepository;
    private final ChatRoomMemberRepository chatRoomMemberRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final ChatRoomLogRepository chatRoomLogRepository;
    private final ChatValidationService chatValidationService;
    private final ChatRoomSupporter chatRoomSupporter;

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
            return new ChatRoomCreateResult(chatRoomSupporter.toResponse(chatRoom, requesterId), false);
        }

        ChatRoom chatRoom = chatRoomRepository.saveAndFlush(ChatRoom.createDirect(requesterId, targetUserId, now));
        chatRoomLogRepository.save(ChatRoomLog.record(chatRoom, requesterId, ChatRoomEventType.ROOM_CREATED, now));
        for (Long userId : List.of(requesterId, targetUserId)) {
            chatRoomMemberRepository.save(ChatRoomMember.join(chatRoom, userId, now));
            chatRoomLogRepository.save(ChatRoomLog.record(chatRoom, userId, ChatRoomEventType.MEMBER_JOINED, now));
        }
        log.info("채팅방 생성 chatRoomId={}, requesterId={}, targetUserId={}", chatRoom.getId(), requesterId,
                targetUserId);
        return new ChatRoomCreateResult(chatRoomSupporter.toResponse(chatRoom, requesterId), true);
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
                ChatMessage.leftNotice(chatRoom, userId, chatRoomSupporter.displayNicknameOf(userId), now));

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
     * 메시지를 읽음 처리한다. 마지막으로 읽은 순번은 줄어들지 않고, 방의 마지막 순번을 넘지 않는다.
     * 메시지 전송과 겹쳐도 되도록 방을 잠그지 않는다. 그 사이 발급된 순번은 다음 읽음 처리에 반영된다.
     *
     * @param lastReadSequence 화면에 보여 준 가장 최근 메시지의 방 순번
     */
    public void markRead(Long chatRoomId, Long userId, long lastReadSequence) {
        ChatRoom chatRoom = chatRoomRepository.findById(chatRoomId)
                .filter(room -> !room.isEnded() && room.getDeletedAt() == null)
                .orElseThrow(() -> new ChatException(ChatErrorCode.CHAT_ROOM_NOT_FOUND));
        ChatRoomMember member = chatRoomMemberRepository
                .findByChatRoom_IdAndUserIdAndDeletedAtIsNull(chatRoomId, userId)
                .filter(ChatRoomMember::isActive)
                .orElseThrow(() -> new ChatException(ChatErrorCode.NOT_CHAT_ROOM_MEMBER));
        chatValidationService.validateCanChat(userId);

        chatRoomMemberRepository.advanceLastReadSequence(member.getId(),
                chatRoom.readableSequence(lastReadSequence));
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

}
