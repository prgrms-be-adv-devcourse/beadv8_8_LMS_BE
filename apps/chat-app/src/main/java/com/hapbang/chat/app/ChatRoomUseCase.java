package com.hapbang.chat.app;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.chat.app.dto.ChatMessageResponse;
import com.hapbang.chat.app.dto.ChatRoomCreateResult;
import com.hapbang.chat.app.dto.ChatRoomLeaveResult;
import com.hapbang.chat.app.dto.ChatRoomResponse;
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
     *     재입장한 사람은 재입장 이전 메시지를 볼 수 없다. 남아 있던 사람에게 보낼 재입장 안내를 같은 트랜잭션에서 남긴다.</li>
     *     <li>없으면 새 방을 만든다. 동시에 만들면 유일 제약에 걸리므로 {@link ChatRoomFacade}가 다시 시도한다.</li>
     * </ul>
     */
    public ChatRoomCreateResult createOrGet(Long requesterId, Long targetUserId) {
        OpenedRoom opened = openDirectRoom(requesterId, targetUserId, LocalDateTime.now());
        ChatRoomResponse room = chatRoomSupporter.toResponse(opened.chatRoom(), requesterId);
        if (opened.created()) {
            return ChatRoomCreateResult.created(room);
        }
        return new ChatRoomCreateResult(room, false, opened.rejoinNotices(), opened.remainingUserIds());
    }

    /**
     * 두 회원의 1:1 방을 연다. 종료되지 않은 방이 있으면 잠그고 재사용하며 나갔던 사람을 재입장시키고, 없으면 새로 만든다.
     * 채팅 시작({@link #createOrGet})과 출연 알림({@link ChatNotificationUseCase})이 같은 규칙을 쓴다.
     * 새 방을 동시에 만들면 유일 제약(direct_key)에 걸리므로 호출한 Facade가 다시 시도한다.
     */
    public OpenedRoom openDirectRoom(Long requesterId, Long targetUserId, LocalDateTime now) {
        if (requesterId == null || targetUserId == null) {
            throw new ChatException(ChatErrorCode.INVALID_REQUEST);
        }
        if (requesterId.equals(targetUserId)) {
            throw new ChatException(ChatErrorCode.CANNOT_CHAT_WITH_SELF);
        }
        chatValidationService.validateCanChat(requesterId);
        chatValidationService.validateCanChat(targetUserId);

        Optional<ChatRoom> existing = chatRoomRepository.findActiveDirectForUpdate(
                ChatRoom.directKeyOf(requesterId, targetUserId));
        if (existing.isPresent()) {
            ChatRoom chatRoom = existing.get();
            List<ChatRoomMember> members = chatRoomMemberRepository.findByChatRoom_IdAndDeletedAtIsNull(chatRoom.getId());
            List<Long> remainingUserIds = members.stream()
                    .filter(ChatRoomMember::isActive)
                    .map(ChatRoomMember::getUserId)
                    .toList();
            List<ChatMessageResponse> rejoinNotices = rejoinLeftMembers(chatRoom, members, now);
            return new OpenedRoom(chatRoom, false, rejoinNotices, rejoinNotices.isEmpty() ? List.of() : remainingUserIds);
        }

        ChatRoom chatRoom = chatRoomRepository.saveAndFlush(ChatRoom.createDirect(requesterId, targetUserId, now));
        chatRoomLogRepository.save(ChatRoomLog.record(chatRoom, requesterId, ChatRoomEventType.ROOM_CREATED, now));
        for (Long userId : List.of(requesterId, targetUserId)) {
            chatRoomMemberRepository.save(ChatRoomMember.join(chatRoom, userId, now));
            chatRoomLogRepository.save(ChatRoomLog.record(chatRoom, userId, ChatRoomEventType.MEMBER_JOINED, now));
        }
        log.info("채팅방 생성 chatRoomId={}, requesterId={}, targetUserId={}", chatRoom.getId(), requesterId,
                targetUserId);
        return new OpenedRoom(chatRoom, true, List.of(), List.of());
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
     * 퇴장했던 회원을 재입장시킨다. 재입장 안내를 먼저 저장한 뒤 재입장시켜서, 안내는 남아 있던 회원에게만 보이고
     * 재입장한 회원의 조회 범위(안내 다음 순번부터)에는 들어가지 않는다.
     *
     * @return 남아 있던 회원에게 보낼 재입장 안내. 재입장한 회원이 없으면 빈 목록
     */
    /**
     * 연 방. 기존 방이면 잠긴 상태다.
     *
     * @param rejoinNotices    나갔던 회원을 재입장시켰으면 남아 있던 회원에게 보낼 재입장 안내
     * @param remainingUserIds 재입장 안내를 받을 회원
     */
    public record OpenedRoom(ChatRoom chatRoom, boolean created, List<ChatMessageResponse> rejoinNotices,
            List<Long> remainingUserIds) {
    }

    private List<ChatMessageResponse> rejoinLeftMembers(ChatRoom chatRoom, List<ChatRoomMember> members,
            LocalDateTime now) {
        List<ChatMessageResponse> rejoinNotices = new ArrayList<>();
        for (ChatRoomMember member : members) {
            if (!member.isActive()) {
                // 이전 참여에서 쓴 전송 ID는 무효다. 재입장 뒤 같은 ID로 보내면 새 메시지로 저장한다(#311)
                chatMessageRepository.clearClientMessageIds(chatRoom.getId(), member.getUserId());
                ChatMessage rejoinNotice = chatMessageRepository.save(ChatMessage.rejoinNotice(chatRoom,
                        member.getUserId(), chatRoomSupporter.displayNicknameOf(member.getUserId()), now));
                member.rejoin(now);
                chatRoomLogRepository.save(
                        ChatRoomLog.record(chatRoom, member.getUserId(), ChatRoomEventType.MEMBER_REJOINED, now));
                rejoinNotices.add(ChatMessageResponse.from(rejoinNotice));
                log.info("채팅방 재입장 chatRoomId={}, userId={}, visibleFromSequence={}", chatRoom.getId(),
                        member.getUserId(), member.getVisibleFromSequence());
            }
        }
        return rejoinNotices;
    }

}
