package com.hapbang.chat.app;

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

import com.hapbang.chat.app.dto.ChatHistoryCursor;
import com.hapbang.chat.app.dto.ChatHistoryCursor.Kind;
import com.hapbang.chat.app.dto.ChatMessageHistoryResponse;
import com.hapbang.chat.app.dto.ChatMessageResponse;
import com.hapbang.chat.app.dto.ChatRoomHistoryResponse;
import com.hapbang.chat.app.dto.ChatRoomLogResponse;
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
     * <p>
     * 메시지와 입퇴장 기록을 (시각, 기록 → 메시지, ID) 순서의 한 흐름으로 보고 커서로 나눠 조회한다(#263).
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
        return new ChatRoomHistoryResponse(chatRoom.getId(), chatRoom.getStatus(), chatRoom.getCreatedAt(),
                chatRoom.getEndedAt(), toMemberResponses(members, findNicknames(members)),
                logs.stream().filter(log -> pageLogIds.contains(log.getId())).map(ChatRoomLogResponse::from).toList(),
                messages.stream().filter(message -> pageMessageIds.contains(message.getId()))
                        .map(ChatMessageHistoryResponse::from).toList(),
                nextCursor);
    }

    private static Set<Long> idsOf(List<ChatHistoryCursor> page, Kind kind) {
        return page.stream().filter(entry -> entry.kind() == kind).map(ChatHistoryCursor::id)
                .collect(Collectors.toSet());
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
                .collect(Collectors.toMap(message -> message.getChatRoom().getId(),
                        message -> ChatMessageResponse.of(message, userId)));
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
