package com.hapbang.chat.app;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.chat.app.dto.ChatGroupRoomSummary;
import com.hapbang.chat.app.dto.ChatHistoryCursor;
import com.hapbang.chat.app.dto.ChatHistoryCursor.Kind;
import com.hapbang.chat.app.dto.ChatMessageHistoryResponse;
import com.hapbang.chat.app.dto.ChatMessageResponse;
import com.hapbang.chat.app.dto.ChatRoomHistoryResponse;
import com.hapbang.chat.app.dto.ChatRoomLogResponse;
import com.hapbang.chat.app.dto.ChatRoomMemberPageResponse;
import com.hapbang.chat.app.dto.ChatRoomMemberResponse;
import com.hapbang.chat.app.dto.ChatRoomResponse;
import com.hapbang.chat.app.exception.ChatErrorCode;
import com.hapbang.chat.app.exception.ChatException;
import com.hapbang.chat.domain.ChatMessage;
import com.hapbang.chat.domain.ChatRoom;
import com.hapbang.chat.domain.ChatRoomLog;
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

    /** 관리자 기록의 시간순: 시각 → 같은 시각이면 기록이 먼저 → ID */
    private static final Comparator<ChatHistoryCursor> HISTORY_ORDER = Comparator.comparing(ChatHistoryCursor::at)
            .thenComparing(ChatHistoryCursor::kind)
            .thenComparing(ChatHistoryCursor::id);

    private final ChatRoomRepository chatRoomRepository;
    private final ChatRoomMemberRepository chatRoomMemberRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final ChatRoomLogRepository chatRoomLogRepository;
    private final ChatUserRepository chatUserRepository;
    private final ChatValidationService chatValidationService;
    private final ChatMessageSenders chatMessageSenders;

    /**
     * 내 채팅방 목록. 방·참여자·닉네임·마지막 메시지·안 읽은 수를 여러 번 나눠 읽으므로, 그 사이 다른 트랜잭션의 퇴장·재입장·전송이
     * 커밋돼도 한 시점의 스냅샷으로 읽도록 REPEATABLE READ로 연다(참여 세대와 마지막 메시지가 섞이지 않게).
     * <p>
     * 단체방은 사용 중·일시 중지 방과 조회 만료 전의 읽기 전용 방이 나온다. 단체방의 참여자는 싣지 않고 인원과 내 참여 세대만 싣는다.
     * <p>
     * 이 격리 수준은 새 트랜잭션을 열 때만 적용된다. 이미 열린 트랜잭션 안에서 부르면 그 트랜잭션에 참여하고 격리 수준은 무시된다.
     * 그래서 트랜잭션 밖(트랜잭션이 없는 Facade·Controller)에서 부른다.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<ChatRoomResponse> getMyRooms(Long userId) {
        chatValidationService.validateCanChat(userId);
        LocalDateTime now = LocalDateTime.now();
        return toResponses(userId, chatRoomMemberRepository.findActiveByUserId(userId, now), now);
    }

    /**
     * 조회한 회원의 참여 행들로 방 응답을 만든다. 1:1 방만 참여자 전체를 읽고, 단체방은 조회한 회원의 참여 세대만 쓴다.
     * 같은 트랜잭션에서 부른다.
     *
     * @param myMembers 조회한 회원의 활성 참여 행(방을 함께 읽은 것). 이 순서대로 응답한다
     */
    public List<ChatRoomResponse> toResponses(Long userId, List<ChatRoomMember> myMembers, LocalDateTime now) {
        if (myMembers.isEmpty()) {
            return List.of();
        }
        List<Long> roomIds = myMembers.stream().map(member -> member.getChatRoom().getId()).toList();
        List<Long> directRoomIds = myMembers.stream().map(ChatRoomMember::getChatRoom)
                .filter(room -> !room.isGroup()).map(ChatRoom::getId).toList();
        Map<Long, List<ChatRoomMember>> membersByRoom = directRoomIds.isEmpty() ? Map.of()
                : chatRoomMemberRepository.findByChatRoom_IdInAndDeletedAtIsNull(directRoomIds).stream()
                        .collect(Collectors.groupingBy(member -> member.getChatRoom().getId()));
        Map<Long, String> nicknames = findNicknames(membersByRoom.values().stream().flatMap(List::stream).toList());
        Map<Long, ChatMessageResponse> lastMessages = findLastMessages(userId, roomIds);
        Map<Long, Long> unreadCounts = countUnread(userId, roomIds);
        return myMembers.stream()
                .map(member -> {
                    ChatRoom room = member.getChatRoom();
                    ChatMessageResponse lastMessage = lastMessages.get(room.getId());
                    long unreadCount = unreadCounts.getOrDefault(room.getId(), 0L);
                    return room.isGroup()
                            ? ChatRoomResponse.ofGroup(room,
                                    ChatGroupRoomSummary.of(room, member.getParticipationVersion()), lastMessage,
                                    unreadCount, now)
                            : ChatRoomResponse.of(room, toMemberResponses(membersByRoom.get(room.getId()), nicknames),
                                    lastMessage, unreadCount);
                })
                .toList();
    }

    /**
     * 방 상세. 참여 중인 회원만 볼 수 있고, 단체방은 조회 만료 전까지다. 응답 형식은 방 목록의 한 항목과 같다.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ChatRoomResponse getRoom(Long chatRoomId, Long userId) {
        chatValidationService.validateCanChat(userId);
        LocalDateTime now = LocalDateTime.now();
        ChatRoomMember member = findViewerMembership(chatRoomId, userId, now);
        return toResponses(userId, List.of(member), now).getFirst();
    }

    /**
     * 방의 활성 참여자 한 페이지(참여 행 순서의 키셋). 참여 중인 회원만 볼 수 있고, 단체방은 조회 만료 전까지다.
     *
     * @param cursor 이전 응답의 nextCursor. 없으면 처음부터
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ChatRoomMemberPageResponse getMembers(Long chatRoomId, Long userId, Long cursor, int size) {
        chatValidationService.validateCanChat(userId);
        findViewerMembership(chatRoomId, userId, LocalDateTime.now());
        List<ChatRoomMember> members = chatRoomMemberRepository.findActivePage(chatRoomId,
                cursor == null ? 0L : cursor, Limit.of(size + 1));
        List<ChatRoomMember> page = members.subList(0, Math.min(size, members.size()));
        Long nextCursor = members.size() > size ? page.getLast().getId() : null;
        return new ChatRoomMemberPageResponse(toMemberResponses(page, findNicknames(page)), nextCursor);
    }

    /** 볼 수 있는 방의 조회한 회원 활성 참여 행. 방이 없거나 볼 수 없으면 404, 참여 중이 아니면 403 */
    private ChatRoomMember findViewerMembership(Long chatRoomId, Long userId, LocalDateTime now) {
        chatRoomRepository.findById(chatRoomId)
                .filter(room -> room.isViewable(now))
                .orElseThrow(() -> new ChatException(ChatErrorCode.CHAT_ROOM_NOT_FOUND));
        return chatRoomMemberRepository.findByChatRoom_IdAndUserIdAndDeletedAtIsNull(chatRoomId, userId)
                .filter(ChatRoomMember::isActive)
                .orElseThrow(() -> new ChatException(ChatErrorCode.NOT_CHAT_ROOM_MEMBER));
    }

    /**
     * 관리자 분쟁 확인용 기록 조회. 종료된 방과 참여자별로 숨겨진 메시지까지 포함한다.
     * <p>
     * 메시지와 입퇴장 기록을 (시각, 기록 → 메시지, ID) 순서의 한 흐름으로 보고 커서로 나눠 조회한다.
     * 두 테이블에서 커서 뒤 항목을 각각 {@code size + 1}개 읽어 합친 뒤 앞에서 {@code size}개를 자르므로,
     * 페이지 경계에서 누락·중복이 없다.
     *
     * <p>
     * 기록과 메시지를 같은 DB 스냅샷에서 읽도록 REPEATABLE_READ로 실행한다. 기본(READ COMMITTED)이면 두 조회 사이에 커밋된
     * 퇴장의 안내 메시지만 페이지에 들어가, 커서가 그 메시지를 지나며 같은 시각의 퇴장 기록이 이후 페이지에서 빠진다.
     *
     * @param cursor 이전 응답의 nextCursor. 없으면 처음부터
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ChatRoomHistoryResponse getHistory(Long chatRoomId, Long adminId, String cursor, int size) {
        chatValidationService.validateAdmin(adminId);
        ChatRoom chatRoom = chatRoomRepository.findById(chatRoomId)
                .orElseThrow(() -> new ChatException(ChatErrorCode.CHAT_ROOM_NOT_FOUND));
        ChatHistoryCursor after = cursor == null ? null : ChatHistoryCursor.decode(cursor);
        Limit limit = Limit.of(size + 1);
        List<ChatRoomLog> logs = after == null
                ? chatRoomLogRepository.findByChatRoom_IdOrderByOccurredAtAscIdAsc(chatRoomId, limit)
                : chatRoomLogRepository.findHistoryAfter(chatRoomId, after.at(), after.logIdAfter(), limit);
        List<ChatMessage> messages = after == null
                ? chatMessageRepository.findByChatRoom_IdOrderBySentAtAscIdAsc(chatRoomId, limit)
                : chatMessageRepository.findHistoryAfter(chatRoomId, after.at(), after.messageIdAfter(), limit);

        List<ChatHistoryCursor> timeline = new ArrayList<>();
        logs.forEach(log -> timeline.add(new ChatHistoryCursor(log.getOccurredAt(), Kind.LOG, log.getId())));
        messages.forEach(message -> timeline.add(
                new ChatHistoryCursor(message.getSentAt(), Kind.MESSAGE, message.getId())));
        timeline.sort(HISTORY_ORDER);
        List<ChatHistoryCursor> page = timeline.subList(0, Math.min(size, timeline.size()));
        String nextCursor = timeline.size() > size ? page.getLast().encode() : null;

        Set<Long> pageLogIds = idsOf(page, Kind.LOG);
        Set<Long> pageMessageIds = idsOf(page, Kind.MESSAGE);
        List<ChatRoomMember> members = chatRoomMemberRepository.findByChatRoom_IdAndDeletedAtIsNull(chatRoomId);
        return new ChatRoomHistoryResponse(chatRoom.getId(), chatRoom.statusAt(LocalDateTime.now()),
                chatRoom.getCreatedAt(), chatRoom.getEndedAt(), toMemberResponses(members, findNicknames(members)),
                logs.stream().filter(log -> pageLogIds.contains(log.getId())).map(ChatRoomLogResponse::from).toList(),
                messages.stream().filter(message -> pageMessageIds.contains(message.getId()))
                        .map(ChatMessageHistoryResponse::from).toList(),
                nextCursor, chatRoom.getRoomType(),
                chatRoom.isGroup() ? ChatGroupRoomSummary.of(chatRoom, null) : null);
    }

    private static Set<Long> idsOf(List<ChatHistoryCursor> page, Kind kind) {
        return page.stream().filter(entry -> entry.kind() == kind).map(ChatHistoryCursor::id)
                .collect(Collectors.toSet());
    }

    /**
     * 채팅 시작 응답. 저장 트랜잭션이 커밋된 뒤 {@link ChatRoomFacade}가 부르는 별도 읽기 트랜잭션이다(설계 4.6).
     * 저장 잠금이 풀린 뒤라 참여자·마지막 메시지·안 읽은 수를 읽는 사이 다른 트랜잭션이 커밋할 수 있다. 한 시점의 스냅샷으로
     * 읽도록 REPEATABLE READ로 연다. 응답의 참여 세대와 마지막 메시지가 서로 다른 세대에서 오지 않는다.
     * <p>
     * 이 격리 수준은 새 트랜잭션을 열 때만 적용된다. 이미 열린 트랜잭션(예: 저장 트랜잭션) 안에서 부르면 그 트랜잭션에 참여하고
     * 격리 수준은 무시된다. 그래서 저장 트랜잭션이 끝난 뒤 트랜잭션이 없는 {@link ChatRoomFacade}에서 부른다.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ChatRoomResponse toResponse(Long chatRoomId, Long userId) {
        ChatRoom chatRoom = chatRoomRepository.findById(chatRoomId)
                .orElseThrow(() -> new ChatException(ChatErrorCode.CHAT_ROOM_NOT_FOUND));
        return toResponse(chatRoom, userId);
    }

    /**
     * 채팅방 응답 (조회한 회원 기준 마지막 메시지·안 읽은 수 포함). 1:1 방은 참여자·닉네임을 싣고, 단체방은 참여자 대신 조회한 회원의
     * 참여 세대를 싣는다(참여 중이 아니면 null).
     */
    public ChatRoomResponse toResponse(ChatRoom chatRoom, Long userId) {
        List<Long> roomIds = List.of(chatRoom.getId());
        ChatMessageResponse lastMessage = findLastMessages(userId, roomIds).get(chatRoom.getId());
        long unreadCount = countUnread(userId, roomIds).getOrDefault(chatRoom.getId(), 0L);
        if (chatRoom.isGroup()) {
            Integer myParticipationVersion = chatRoomMemberRepository
                    .findByChatRoom_IdAndUserIdAndDeletedAtIsNull(chatRoom.getId(), userId)
                    .filter(ChatRoomMember::isActive)
                    .map(ChatRoomMember::getParticipationVersion)
                    .orElse(null);
            return ChatRoomResponse.ofGroup(chatRoom, ChatGroupRoomSummary.of(chatRoom, myParticipationVersion),
                    lastMessage, unreadCount, LocalDateTime.now());
        }
        List<ChatRoomMember> members = chatRoomMemberRepository.findByChatRoom_IdAndDeletedAtIsNull(chatRoom.getId());
        return ChatRoomResponse.of(chatRoom, toMemberResponses(members, findNicknames(members)), lastMessage,
                unreadCount);
    }

    /** 표시용 닉네임. 탈퇴 회원은 '탈퇴한 회원', 복제 정보가 없으면 '회원 {id}' */
    public String displayNicknameOf(Long userId) {
        return chatUserRepository.findById(userId).map(ChatUser::getDisplayNickname).orElse("회원 " + userId);
    }

    private Map<Long, ChatMessageResponse> findLastMessages(Long userId, List<Long> chatRoomIds) {
        List<ChatMessage> lastMessages = chatMessageRepository.findLastVisible(userId, chatRoomIds);
        Map<Long, ChatUser> senders = chatMessageSenders.usersOf(lastMessages);
        return lastMessages.stream()
                .collect(Collectors.toMap(message -> message.getChatRoom().getId(),
                        message -> ChatMessageResponse.of(message, userId, senders)));
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
