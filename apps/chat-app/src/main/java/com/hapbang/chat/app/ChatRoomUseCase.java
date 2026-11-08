package com.hapbang.chat.app;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.chat.app.dto.ChatMessageResponse;
import com.hapbang.chat.app.dto.ChatRoomOpenResult;
import com.hapbang.chat.app.dto.ChatRoomLeaveResult;
import com.hapbang.chat.app.exception.ChatErrorCode;
import com.hapbang.chat.app.exception.ChatException;
import com.hapbang.chat.domain.ChatMessage;
import com.hapbang.chat.domain.ChatNotifyRule;
import com.hapbang.chat.domain.ChatRecipientSet;
import com.hapbang.chat.domain.ChatRoom;
import com.hapbang.chat.domain.ChatRoomEventType;
import com.hapbang.chat.domain.ChatRoomLog;
import com.hapbang.chat.domain.ChatRoomMember;
import com.hapbang.chat.domain.GroupMemberLeftData;
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
    private final ChatRecipientSetRecorder chatRecipientSetRecorder;
    private final ChatDeliveryRecorder chatDeliveryRecorder;
    private final ChatReadCountSignals chatReadCountSignals;

    /**
     * 1:1 채팅을 시작한다.
     * <ul>
     *     <li>두 사람의 종료되지 않은 방이 있으면 재사용하고, 퇴장했던 사람은 바로 재입장시킨다.
     *     재입장한 사람은 재입장 이전 메시지를 볼 수 없다. 남아 있던 사람에게 보낼 재입장 안내를 같은 트랜잭션에서 남긴다.</li>
     *     <li>없으면 새 방을 만든다. 동시에 만들면 유일 제약에 걸리므로 {@link ChatRoomFacade}가 다시 시도한다.</li>
     * </ul>
     * 저장만 한다. 방 응답(참여자·닉네임·마지막 메시지·안 읽은 수)은 커밋 뒤 {@link ChatRoomFacade}가 따로 조립한다(설계 4.6).
     */
    public ChatRoomOpenResult createOrGet(Long requesterId, Long targetUserId) {
        OpenedRoom opened = openDirectRoom(requesterId, targetUserId, LocalDateTime.now());
        return new ChatRoomOpenResult(opened.chatRoom().getId(), opened.created(), opened.rejoinNotices());
    }

    /**
     * 두 회원의 1:1 방을 연다. 종료되지 않은 방이 있으면 잠그고 재사용하며 나갔던 사람을 재입장시키고, 없으면 새로 만든다.
     * 채팅 시작({@link #createOrGet})과 출연 알림({@link ChatNotificationUseCase})이 같은 규칙을 쓴다.
     * 새 방을 동시에 만들면 유일 제약(direct_key)에 걸리므로 호출한 Facade가 다시 시도한다.
     * <p>
     * 참여가 바뀌면(생성·재입장) 같은 트랜잭션에서 불변 수신자 집합의 새 버전을 만든다. 수신자 집합이 없는 기존 방
     * (전환 전 데이터 이상)은 지금의 활성 참여로 집합을 만든 뒤 연다.
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
            if (!chatRoom.hasRecipientSet()) {
                log.warn("수신자 집합이 없는 방이라 지금의 활성 참여로 만든다 chatRoomId={}", chatRoom.getId());
                chatRecipientSetRecorder.snapshot(chatRoom, now);
            }
            // 방 참여자 전체가 아니라 두 회원의 참여 행만 읽는다(설계 4.6)
            List<ChatRoomMember> members = new ArrayList<>();
            for (Long userId : List.of(requesterId, targetUserId)) {
                chatRoomMemberRepository.findByChatRoom_IdAndUserIdAndDeletedAtIsNull(chatRoom.getId(), userId)
                        .ifPresent(members::add);
            }
            return new OpenedRoom(chatRoom, false, rejoinLeftMembers(chatRoom, members, now));
        }

        ChatRoom chatRoom = chatRoomRepository.saveAndFlush(ChatRoom.createDirect(requesterId, targetUserId, now));
        chatRoomLogRepository.save(ChatRoomLog.record(chatRoom, requesterId, ChatRoomEventType.ROOM_CREATED, now));
        for (Long userId : List.of(requesterId, targetUserId)) {
            chatRoomMemberRepository.save(ChatRoomMember.join(chatRoom, userId, now));
            chatRoomLogRepository.save(ChatRoomLog.record(chatRoom, userId, ChatRoomEventType.MEMBER_JOINED, now));
        }
        chatRecipientSetRecorder.snapshot(chatRoom, now);
        log.info("채팅방 생성 chatRoomId={}, requesterId={}, targetUserId={}", chatRoom.getId(), requesterId,
                targetUserId);
        return new OpenedRoom(chatRoom, true, List.of());
    }

    /**
     * 채팅방을 나간다.
     * <ul>
     *     <li>메시지는 지우지 않는다. 나간 사람은 활성 참여가 아니므로 조회·수신할 수 없다.</li>
     *     <li>남은 사람이 볼 퇴장 안내 메시지를 같은 트랜잭션에서 남긴다.</li>
     *     <li>마지막 참여자가 나가면 방을 종료한다.</li>
     *     <li>퇴장을 반영한 불변 수신자 집합을 만들고, 퇴장 안내는 그 집합(남은 참여자)에게 알림 없이 전달하도록 기록한다.
     *     남은 참여자가 없으면 전달 작업을 남기지 않는다.</li>
     *     <li>이미 나갔거나 옛 참여 세대의 요청이면 아무것도 바꾸지 않는다(멱등).</li>
     *     <li>단체방은 {@link #leaveGroup} 규칙을 따른다(호스트 제한, 방 종료 없음).</li>
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
        if (chatRoom.isGroup()) {
            return leaveGroup(chatRoom, member, now);
        }
        member.leave(now);
        chatRoomLogRepository.save(ChatRoomLog.record(chatRoom, userId, ChatRoomEventType.MEMBER_LEFT, now));
        ChatRecipientSet remaining = chatRecipientSetRecorder.snapshot(chatRoom, now);
        ChatMessage leftNotice = chatMessageRepository.save(
                ChatMessage.leftNotice(chatRoom, userId, chatRoomSupporter.displayNicknameOf(userId), now));
        if (remaining.getMemberCount() > 0) {
            chatDeliveryRecorder.recordMessage(leftNotice, remaining.getId(), ChatNotifyRule.NONE, now);
        }
        // 나간 회원이 빠져 남은 참여자의 메시지의 안 읽은 사람 수가 줄 수 있다. 커밋된 뒤 알린다
        chatReadCountSignals.membershipChanged(chatRoomId, remaining.getSetVersion());

        // 남은 참여자 목록을 만들지 않고, 퇴장을 반영한 수신자 집합의 인원수로 방 종료를 정한다
        boolean roomEnded = remaining.getMemberCount() == 0;
        if (roomEnded) {
            chatRoom.end(now);
            chatRoomLogRepository.save(ChatRoomLog.record(chatRoom, userId, ChatRoomEventType.ROOM_ENDED, now));
        }
        log.info("채팅방 퇴장 chatRoomId={}, userId={}, roomEnded={}", chatRoomId, userId, roomEnded);
        return new ChatRoomLeaveResult(true, ChatMessageResponse.from(leftNotice), roomEnded,
                member.getParticipationVersion());
    }

    /**
     * 단체방에서 나간다. 방을 잠근 이 트랜잭션 안에서 부른다.
     * <ul>
     *     <li>조회 만료된 방은 볼 수 없는 방이라 없는 방으로 본다.</li>
     *     <li>호스트는 사용 중·일시 중지 방을 나갈 수 없고(나가기 대신 폐쇄) 직접 폐쇄한 읽기 전용 방만 나갈 수 있다(Q33).</li>
     *     <li>모두 나가도 방을 종료하지 않는다.</li>
     *     <li>나가기를 반영한 수신자 집합을 만들고, 사용 중·일시 중지 방이면 남은 참여자에게 나가기 안내를 알림 없이 전달한다.
     *     읽기 전용 방에는 새 메시지를 남기지 않으므로(마지막 메시지는 폐쇄 안내) 기록만 남긴다.</li>
     * </ul>
     */
    private ChatRoomLeaveResult leaveGroup(ChatRoom chatRoom, ChatRoomMember member, LocalDateTime now) {
        Long userId = member.getUserId();
        if (!chatRoom.isViewable(now)) {
            throw new ChatException(ChatErrorCode.CHAT_ROOM_NOT_FOUND);
        }
        if (chatRoom.isOwnedBy(userId) && !chatRoom.isClosedByHost()) {
            throw new ChatException(ChatErrorCode.HOST_CANNOT_LEAVE);
        }
        member.leave(now);
        chatRoomLogRepository.save(ChatRoomLog.record(chatRoom, userId, ChatRoomEventType.MEMBER_LEFT, now));
        ChatRecipientSet remaining = chatRecipientSetRecorder.snapshot(chatRoom, now);
        ChatMessageResponse leftNotice = null;
        if (chatRoom.isCurrentGroup()) {
            ChatMessage notice = chatMessageRepository.save(
                    ChatMessage.groupMemberLeft(chatRoom, userId, GroupMemberLeftData.of(userId), now));
            if (remaining.getMemberCount() > 0) {
                chatDeliveryRecorder.recordMessage(notice, remaining.getId(), ChatNotifyRule.NONE, now);
            }
            leftNotice = ChatMessageResponse.from(notice);
        }
        chatReadCountSignals.membershipChanged(chatRoom.getId(), remaining.getSetVersion());
        log.info("단체방 퇴장 chatRoomId={}, userId={}, status={}", chatRoom.getId(), userId, chatRoom.getStatus());
        return new ChatRoomLeaveResult(true, leftNotice, false, member.getParticipationVersion());
    }

    /**
     * 연 방. 기존 방이면 잠긴 상태다.
     *
     * @param rejoinNotices 나갔던 회원을 재입장시켰으면 남아 있던 회원에게 보일 재입장 안내. 전달은 outbox가 맡는다
     */
    public record OpenedRoom(ChatRoom chatRoom, boolean created, List<ChatMessageResponse> rejoinNotices) {
    }

    /**
     * 퇴장했던 회원을 재입장시킨다. 재입장 안내를 먼저 저장한 뒤 재입장시켜서, 안내는 남아 있던 회원에게만 보이고
     * 재입장한 회원의 조회 범위(안내 다음 순번부터)에는 들어가지 않는다.
     * 안내의 전달 작업은 재입장 전 집합(남아 있던 회원)을 알림 없이 참조하고, 재입장 뒤에는 새 집합을 만든다.
     *
     * @return 남아 있던 회원에게 보낼 재입장 안내. 재입장한 회원이 없으면 빈 목록
     */
    private List<ChatMessageResponse> rejoinLeftMembers(ChatRoom chatRoom, List<ChatRoomMember> members,
            LocalDateTime now) {
        List<ChatMessageResponse> rejoinNotices = new ArrayList<>();
        for (ChatRoomMember member : members) {
            if (!member.isActive()) {
                // 이전 참여에서 쓴 전송 ID는 무효다. 재입장 뒤 같은 ID로 보내면 새 메시지로 저장한다
                chatMessageRepository.clearClientMessageIds(chatRoom.getId(), member.getUserId());
                ChatMessage rejoinNotice = chatMessageRepository.save(ChatMessage.rejoinNotice(chatRoom,
                        member.getUserId(), chatRoomSupporter.displayNicknameOf(member.getUserId()), now));
                if (chatRoom.getActiveMemberCount() > 0) {
                    chatDeliveryRecorder.recordMessage(rejoinNotice, chatRoom.getCurrentRecipientSetId(),
                            ChatNotifyRule.NONE, now);
                }
                member.rejoin(now);
                chatRecipientSetRecorder.snapshot(chatRoom, now);
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
