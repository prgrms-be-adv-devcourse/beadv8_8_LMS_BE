package com.hapbang.chat.app;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.chat.app.ChatRecruitSource.ChatRecruit;
import com.hapbang.chat.app.dto.ChatGroupCloseResponse;
import com.hapbang.chat.app.dto.ChatGroupRoomsResponse;
import com.hapbang.chat.app.dto.ChatInviteCandidatePageResponse;
import com.hapbang.chat.app.dto.ChatInviteCandidateResponse;
import com.hapbang.chat.app.dto.ChatRoomOpenResult;
import com.hapbang.chat.app.dto.ChatRoomResponse;
import com.hapbang.chat.app.exception.ChatErrorCode;
import com.hapbang.chat.app.exception.ChatException;
import com.hapbang.chat.domain.ChatGroupInviteFailure;
import com.hapbang.chat.domain.ChatGroupInviteOutcome;
import com.hapbang.chat.domain.ChatGroupInviteRequest;
import com.hapbang.chat.domain.ChatMessage;
import com.hapbang.chat.domain.ChatNotifyRule;
import com.hapbang.chat.domain.ChatRecipientSet;
import com.hapbang.chat.domain.ChatRoom;
import com.hapbang.chat.domain.ChatRoomEventType;
import com.hapbang.chat.domain.ChatRoomLog;
import com.hapbang.chat.domain.ChatRoomMember;
import com.hapbang.chat.domain.ChatUser;
import com.hapbang.chat.domain.GroupMemberKickedData;
import com.hapbang.chat.domain.GroupMembersInvitedData;
import com.hapbang.chat.domain.GroupRoomClosedData;
import com.hapbang.chat.out.ChatGroupInviteRequestRepository;
import com.hapbang.chat.out.ChatMessageRepository;
import com.hapbang.chat.out.ChatRoomLogRepository;
import com.hapbang.chat.out.ChatRoomMemberRepository;
import com.hapbang.chat.out.ChatRoomRepository;
import com.hapbang.chat.out.ChatUserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * {@link ChatGroupRoomService}의 구현. 쓰기는 방 잠금 아래 한 트랜잭션이고 출연 앱을 부르지 않는다.
 * <ul>
 *     <li>참여 변경(생성·초대·강퇴)은 1:1 방과 같이 참여 행·인원 집계·불변 수신자 집합·현재 포인터를 원자 저장한다. 여러 명을 초대해도
 *     수신자 집합은 요청마다 한 번만 만든다.</li>
 *     <li>안내는 값만 저장하고(본문 빈 문자열) 알림 없이({@code NONE}) 전달 작업을 남긴다. 초대 안내는 참여시킨 뒤 저장해 초대된 회원도
 *     보고, 강퇴 안내는 내보낸 뒤 저장해 남은 참여자만 본다. 폐쇄 안내는 읽기 전용으로 바꾸기 전에 저장한다(읽기 전용 방의 마지막
 *     메시지).</li>
 *     <li>호스트 권한은 방에 저장한 호스트 ID로 확인한다. 클라이언트가 보낸 호스트·지원 여부는 쓰지 않는다.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@EnableConfigurationProperties(ChatGroupProperties.class)
public class DefaultChatGroupRoomService implements ChatGroupRoomService {

    private final ChatRoomRepository chatRoomRepository;
    private final ChatRoomMemberRepository chatRoomMemberRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final ChatRoomLogRepository chatRoomLogRepository;
    private final ChatUserRepository chatUserRepository;
    private final ChatGroupInviteRequestRepository chatGroupInviteRequestRepository;
    private final ChatValidationService chatValidationService;
    private final ChatRoomSupporter chatRoomSupporter;
    private final ChatRecipientSetRecorder chatRecipientSetRecorder;
    private final ChatDeliveryRecorder chatDeliveryRecorder;
    private final ChatGroupProperties properties;

    @Override
    @Transactional(readOnly = true)
    public Optional<Long> findCurrentRoomOf(Long requesterId, Long recruitId) {
        chatValidationService.validateCanChat(requesterId);
        return chatRoomRepository.findCurrentGroup(recruitId).map(room -> requireRecruitHost(room, requesterId));
    }

    @Override
    @Transactional
    public ChatRoomOpenResult createRoom(Long requesterId, ChatRecruit recruit) {
        chatValidationService.validateCanChat(requesterId);
        if (!recruit.hostId().equals(requesterId)) {
            throw new ChatException(ChatErrorCode.NOT_RECRUIT_HOST);
        }
        Optional<ChatRoom> current = chatRoomRepository.findCurrentGroup(recruit.recruitId());
        if (current.isPresent()) {
            // 먼저 만든 요청이 커밋했다(동시 생성의 재시도 포함). 같은 방을 돌려준다
            return new ChatRoomOpenResult(requireRecruitHost(current.get(), requesterId), false, List.of());
        }
        if (!recruit.isRecruiting()) {
            throw new ChatException(ChatErrorCode.RECRUIT_NOT_OPEN);
        }
        LocalDateTime now = LocalDateTime.now();
        ChatRoom chatRoom = chatRoomRepository.saveAndFlush(ChatRoom.createGroup(recruit.recruitId(), requesterId,
                recruit.title(), properties.maxGuestMembers(), now));
        chatRoomLogRepository.save(ChatRoomLog.record(chatRoom, requesterId, ChatRoomEventType.ROOM_CREATED, now));
        chatRoomMemberRepository.save(ChatRoomMember.join(chatRoom, requesterId, now));
        chatRoomLogRepository.save(ChatRoomLog.record(chatRoom, requesterId, ChatRoomEventType.MEMBER_JOINED, now));
        chatRecipientSetRecorder.snapshot(chatRoom, now);
        log.info("단체방 생성 chatRoomId={}, recruitId={}, hostId={}", chatRoom.getId(), recruit.recruitId(),
                requesterId);
        return new ChatRoomOpenResult(chatRoom.getId(), true, List.of());
    }

    /**
     * 같은 트랜잭션의 스냅샷으로 방들을 읽도록 REPEATABLE READ로 연다(방 목록과 같다). 트랜잭션 밖(Facade)에서 부른다.
     */
    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ChatGroupRoomsResponse getRooms(Long userId, Long recruitId) {
        chatValidationService.validateCanChat(userId);
        LocalDateTime now = LocalDateTime.now();
        List<ChatRoomMember> memberships = chatRoomMemberRepository.findActiveGroupMemberships(recruitId, userId)
                .stream()
                .filter(member -> member.getChatRoom().isViewable(now))
                .toList();
        List<ChatRoomResponse> rooms = chatRoomSupporter.toResponses(userId, memberships, now);
        ChatRoomResponse current = null;
        List<ChatRoomResponse> previous = new ArrayList<>();
        for (int i = 0; i < memberships.size(); i++) {
            if (memberships.get(i).getChatRoom().isCurrentGroup()) {
                current = rooms.get(i);
            } else {
                previous.add(rooms.get(i));
            }
        }
        return new ChatGroupRoomsResponse(recruitId, current, previous);
    }

    @Override
    @Transactional(readOnly = true)
    public Long checkManageable(Long chatRoomId, Long requesterId) {
        chatValidationService.validateCanChat(requesterId);
        ChatRoom chatRoom = findViewableGroup(chatRoomId);
        requireManageable(chatRoom, requesterId);
        return chatRoom.getRecruitId();
    }

    /**
     * 후보는 지원 이력이 있는 회원 중 지금 참여 중이 아닌 회원이다. 나갔거나 내보낸 회원, 초대에 실패한 회원도 다시 나온다.
     * 검색어가 없으면 페이지의 회원 정보만 읽고, 있으면 후보 전체의 채팅 회원 정보로 거른다(닉네임은 chat 복제본 값).
     */
    @Override
    @Transactional
    public ChatInviteCandidatePageResponse findInviteCandidates(Long chatRoomId, Long requesterId,
            String recruitTitle, Collection<Long> applicantUserIds, String keyword, Long cursor, int size) {
        chatValidationService.validateCanChat(requesterId);
        ChatRoom chatRoom = findViewableGroup(chatRoomId);
        requireManageable(chatRoom, requesterId);
        int remainingGuestSlots = Math.max(0, chatRoom.getMaxGuestMembers() - chatRoom.activeGuestCount());
        // 제목 컬럼만 바꾼다(방을 잠그지 않는다). 영속성 컨텍스트를 비우므로 방 값을 먼저 읽어 둔다
        chatRoomRepository.updateGroupTitle(chatRoomId, recruitTitle, LocalDateTime.now());

        Set<Long> members = new HashSet<>(chatRoomMemberRepository.findActiveUserIds(chatRoomId));
        long after = cursor == null ? 0L : cursor;
        List<Long> candidateIds = applicantUserIds.stream()
                .distinct()
                .filter(userId -> userId > after && !members.contains(userId))
                .sorted()
                .toList();
        String term = keyword == null ? "" : keyword.strip();
        List<Long> matched;
        Map<Long, ChatUser> users;
        if (term.isEmpty()) {
            matched = candidateIds.subList(0, Math.min(size + 1, candidateIds.size()));
            users = findUsers(matched);
        } else {
            users = findUsers(candidateIds);
            matched = candidateIds.stream()
                    .filter(userId -> matches(userId, users.get(userId), term))
                    .limit(size + 1L)
                    .toList();
        }
        List<Long> page = matched.subList(0, Math.min(size, matched.size()));
        Long nextCursor = matched.size() > size ? page.getLast() : null;
        List<ChatInviteCandidateResponse> candidates = page.stream()
                .map(userId -> toCandidate(userId, users.get(userId)))
                .toList();
        return new ChatInviteCandidatePageResponse(candidates, nextCursor, remainingGuestSlots);
    }

    @Override
    @Transactional(readOnly = true)
    public InvitePreparation prepareInvite(Long chatRoomId, Long requesterId, UUID requestId) {
        chatValidationService.validateCanChat(requesterId);
        ChatRoom chatRoom = findViewableGroup(chatRoomId);
        requireOwner(chatRoom, requesterId);
        Optional<List<ChatGroupInviteOutcome>> retained = findRetained(chatRoomId, requestId, LocalDateTime.now());
        if (retained.isEmpty()) {
            requireManageable(chatRoom, requesterId);
        }
        return new InvitePreparation(chatRoom.getRecruitId(), retained.orElse(null));
    }

    @Override
    @Transactional
    public List<ChatGroupInviteOutcome> invite(Long chatRoomId, Long requesterId, UUID requestId,
            List<Long> userIds, Set<Long> appliedUserIds) {
        ChatRoom chatRoom = lockViewableGroup(chatRoomId);
        requireOwner(chatRoom, requesterId);
        LocalDateTime now = LocalDateTime.now();
        // 결과 기록을 잠가 읽는다. 보존 기간이 지난 행을 새 결과로 바꾸기 전에 정리 배치가 지우지 못하게 한다
        Optional<ChatGroupInviteRequest> stored = chatGroupInviteRequestRepository.findForUpdate(chatRoomId, requestId);
        if (stored.isPresent() && stored.get().isRetained(now, properties.inviteRequestRetention())) {
            // 같은 요청의 재시도(동시에 온 재시도 포함): 저장한 결과를 그대로 돌려주고 다시 초대하지 않는다
            return stored.get().outcomes();
        }
        chatValidationService.validateCanChat(requesterId);
        requireManageable(chatRoom, requesterId);

        // 초대할 회원의 참여 행과 채팅 회원 정보만 읽는다(방 참여자 전체를 읽지 않는다)
        Map<Long, ChatRoomMember> members = chatRoomMemberRepository
                .findByChatRoom_IdAndUserIdInAndDeletedAtIsNull(chatRoomId, userIds).stream()
                .collect(Collectors.toMap(ChatRoomMember::getUserId, Function.identity()));
        Set<Long> chattable = chatUserRepository.findAllById(userIds).stream()
                .filter(ChatUser::canChat)
                .map(ChatUser::getId)
                .collect(Collectors.toSet());
        int guestCount = chatRoom.activeGuestCount();
        List<ChatGroupInviteOutcome> outcomes = new ArrayList<>();
        List<Long> joined = new ArrayList<>();
        for (Long userId : userIds) {
            ChatRoomMember member = members.get(userId);
            if (member != null && member.isActive()) {
                outcomes.add(ChatGroupInviteOutcome.alreadyMember(userId));
            } else if (!appliedUserIds.contains(userId)) {
                outcomes.add(ChatGroupInviteOutcome.failed(userId, ChatGroupInviteFailure.NOT_APPLICANT));
            } else if (!chattable.contains(userId)) {
                outcomes.add(ChatGroupInviteOutcome.failed(userId, ChatGroupInviteFailure.CHAT_NOT_ALLOWED));
            } else if (guestCount >= chatRoom.getMaxGuestMembers()) {
                outcomes.add(ChatGroupInviteOutcome.failed(userId, ChatGroupInviteFailure.ROOM_FULL));
            } else {
                join(chatRoom, member, userId, now);
                chatRoomLogRepository.save(ChatRoomLog.recordOn(chatRoom, requesterId, userId,
                        ChatRoomEventType.MEMBER_INVITED, now));
                guestCount++;
                joined.add(userId);
                outcomes.add(ChatGroupInviteOutcome.joined(userId));
            }
        }
        if (!joined.isEmpty()) {
            ChatRecipientSet recipientSet = chatRecipientSetRecorder.snapshot(chatRoom, now);
            ChatMessage notice = chatMessageRepository.save(ChatMessage.groupMembersInvited(chatRoom, requesterId,
                    GroupMembersInvitedData.of(requesterId, joined), now));
            chatDeliveryRecorder.recordMessage(notice, recipientSet.getId(), ChatNotifyRule.NONE, now);
        }
        if (stored.isPresent()) {
            stored.get().replace(requesterId, outcomes, now);
        } else {
            chatGroupInviteRequestRepository.save(
                    ChatGroupInviteRequest.record(chatRoomId, requestId, requesterId, outcomes, now));
        }
        log.info("단체방 초대 chatRoomId={}, requestId={}, requested={}, joined={}", chatRoomId, requestId,
                userIds.size(), joined.size());
        return outcomes;
    }

    @Override
    @Transactional
    public Optional<Integer> kick(Long chatRoomId, Long requesterId, Long targetUserId, int participationVersion) {
        ChatRoom chatRoom = lockViewableGroup(chatRoomId);
        chatValidationService.validateCanChat(requesterId);
        requireManageable(chatRoom, requesterId);
        if (chatRoom.isOwnedBy(targetUserId)) {
            throw new ChatException(ChatErrorCode.CANNOT_KICK_HOST);
        }
        ChatRoomMember member = chatRoomMemberRepository
                .findByChatRoom_IdAndUserIdAndDeletedAtIsNull(chatRoomId, targetUserId)
                .orElseThrow(() -> new ChatException(ChatErrorCode.KICK_TARGET_NOT_MEMBER));
        if (member.getParticipationVersion() != participationVersion) {
            // 옛 세대를 보고 보낸 요청이 재초대된 새 참여를 끝내지 않는다
            throw new ChatException(ChatErrorCode.STALE_PARTICIPATION);
        }
        if (!member.isActive()) {
            return Optional.empty();
        }
        LocalDateTime now = LocalDateTime.now();
        member.leave(now);
        chatRoomLogRepository.save(ChatRoomLog.recordOn(chatRoom, requesterId, targetUserId,
                ChatRoomEventType.MEMBER_KICKED, now));
        ChatRecipientSet remaining = chatRecipientSetRecorder.snapshot(chatRoom, now);
        ChatMessage notice = chatMessageRepository.save(ChatMessage.groupMemberKicked(chatRoom, requesterId,
                GroupMemberKickedData.of(requesterId, targetUserId), now));
        chatDeliveryRecorder.recordMessage(notice, remaining.getId(), ChatNotifyRule.NONE, now);
        log.info("단체방 강퇴 chatRoomId={}, targetUserId={}, participationVersion={}", chatRoomId, targetUserId,
                participationVersion);
        return Optional.of(member.getParticipationVersion());
    }

    /**
     * 강등·정지된 호스트도 폐쇄할 수 있게 채팅 자격은 검사하지 않는다(호스트에게는 나가기 대신이다).
     */
    @Override
    @Transactional
    public ChatGroupCloseResponse close(Long chatRoomId, Long requesterId) {
        ChatRoom chatRoom = lockViewableGroup(chatRoomId);
        requireOwner(chatRoom, requesterId);
        if (chatRoom.isClosedByHost()) {
            return ChatGroupCloseResponse.from(chatRoom);
        }
        if (!chatRoom.isCurrentGroup()) {
            throw new ChatException(ChatErrorCode.GROUP_ROOM_NOT_ACTIVE);
        }
        LocalDateTime now = LocalDateTime.now();
        ChatMessage notice = chatMessageRepository.save(
                ChatMessage.groupRoomClosed(chatRoom, requesterId, GroupRoomClosedData.of(requesterId), now));
        if (chatRoom.getActiveMemberCount() > 0) {
            chatDeliveryRecorder.recordMessage(notice, chatRoom.getCurrentRecipientSetId(), ChatNotifyRule.NONE, now);
        }
        chatRoom.closeByHost(now);
        chatRoomLogRepository.save(ChatRoomLog.record(chatRoom, requesterId, ChatRoomEventType.ROOM_CLOSED, now));
        log.info("단체방 폐쇄 chatRoomId={}, historyExpiresAt={}", chatRoomId, chatRoom.getHistoryExpiresAt());
        return ChatGroupCloseResponse.from(chatRoom);
    }

    /**
     * 저장 트랜잭션이 커밋된 뒤의 방 응답. 한 시점의 스냅샷으로 읽도록 REPEATABLE READ로 연다(1:1 방 응답과 같다).
     */
    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ChatRoomResponse toRoomResponse(Long chatRoomId, Long userId) {
        return chatRoomSupporter.toResponse(chatRoomId, userId);
    }

    /**
     * 참여시킨다. 처음이면 참여 행을 만들고, 나갔거나 내보내진 회원이면 같은 행에서 새 참여 세대로 재입장시킨다(이전 메시지는 볼 수
     * 없다). 이전 참여에서 쓴 전송 ID는 무효로 한다.
     */
    private void join(ChatRoom chatRoom, ChatRoomMember member, Long userId, LocalDateTime now) {
        if (member == null) {
            chatRoomMemberRepository.save(ChatRoomMember.join(chatRoom, userId, now));
            return;
        }
        chatMessageRepository.clearClientMessageIds(chatRoom.getId(), userId);
        member.rejoin(now);
    }

    private Optional<List<ChatGroupInviteOutcome>> findRetained(Long chatRoomId, UUID requestId, LocalDateTime now) {
        return chatGroupInviteRequestRepository.findByChatRoomIdAndRequestId(chatRoomId, requestId)
                .filter(request -> request.isRetained(now, properties.inviteRequestRetention()))
                .map(ChatGroupInviteRequest::outcomes);
    }

    private ChatRoom findViewableGroup(Long chatRoomId) {
        LocalDateTime now = LocalDateTime.now();
        return chatRoomRepository.findById(chatRoomId)
                .filter(room -> room.isGroup() && room.isViewable(now))
                .orElseThrow(DefaultChatGroupRoomService::groupNotFound);
    }

    private ChatRoom lockViewableGroup(Long chatRoomId) {
        LocalDateTime now = LocalDateTime.now();
        return chatRoomRepository.findByIdForUpdate(chatRoomId)
                .filter(room -> room.isGroup() && room.isViewable(now))
                .orElseThrow(DefaultChatGroupRoomService::groupNotFound);
    }

    /** 현재 방의 호스트가 요청했는지 확인하고 방 ID를 돌려준다(생성 요청용 오류 코드) */
    private static Long requireRecruitHost(ChatRoom chatRoom, Long requesterId) {
        if (!chatRoom.isOwnedBy(requesterId)) {
            throw new ChatException(ChatErrorCode.NOT_RECRUIT_HOST);
        }
        return chatRoom.getId();
    }

    private static void requireOwner(ChatRoom chatRoom, Long requesterId) {
        if (!chatRoom.isOwnedBy(requesterId)) {
            throw new ChatException(ChatErrorCode.NOT_GROUP_HOST);
        }
    }

    /** 호스트이고 사용 중인 방이어야 초대 후보 검색·초대·강퇴를 할 수 있다 */
    private static void requireManageable(ChatRoom chatRoom, Long requesterId) {
        requireOwner(chatRoom, requesterId);
        if (!chatRoom.isGroupActive()) {
            throw new ChatException(ChatErrorCode.GROUP_ROOM_NOT_ACTIVE);
        }
    }

    private Map<Long, ChatUser> findUsers(Collection<Long> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        return chatUserRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(ChatUser::getId, Function.identity()));
    }

    /** 회원 ID 정확 일치 또는 닉네임 부분 일치(대소문자 무시). 채팅 회원 정보가 없으면 ID로만 찾는다 */
    private static boolean matches(Long userId, ChatUser user, String term) {
        if (term.equals(String.valueOf(userId))) {
            return true;
        }
        return user != null && user.getNickname() != null
                && user.getNickname().toLowerCase(Locale.ROOT).contains(term.toLowerCase(Locale.ROOT));
    }

    private static ChatInviteCandidateResponse toCandidate(Long userId, ChatUser user) {
        if (user == null) {
            return new ChatInviteCandidateResponse(userId, "회원 " + userId, false,
                    ChatGroupInviteFailure.CHAT_NOT_ALLOWED);
        }
        boolean invitable = user.canChat();
        return new ChatInviteCandidateResponse(userId, user.getDisplayNickname(), invitable,
                invitable ? null : ChatGroupInviteFailure.CHAT_NOT_ALLOWED);
    }

    private static ChatException groupNotFound() {
        return new ChatException(ChatErrorCode.CHAT_ROOM_NOT_FOUND, "단체방을 찾을 수 없습니다.");
    }
}
