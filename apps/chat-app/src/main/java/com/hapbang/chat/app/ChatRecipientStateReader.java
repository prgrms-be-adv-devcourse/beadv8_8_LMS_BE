package com.hapbang.chat.app;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.chat.app.dto.ChatRecipientState;
import com.hapbang.chat.app.port.ChatRoomMemberRows.UserWithMembership;
import com.hapbang.chat.app.port.ChatRoomRepository;
import com.hapbang.chat.app.port.ChatUserRepository;
import com.hapbang.chat.domain.ChatUser;

import lombok.RequiredArgsConstructor;

/**
 * 방 topic 구독 허가·연결 회원 재검사에 필요한 최신 상태를 chat DB에서 읽는다. 회원 복제본과 참여 행을 함께 읽어 회원마다 쿼리를
 * 반복하지 않는다. 회원 자격·참여 상태는 {@link ChatRecipientState#of}로 만든다. 방 활성 참여자 전원의 상태는
 * {@link com.hapbang.chat.app.port.ChatRoomMemberships}가 읽는다.
 * <p>
 * 읽기 전용 트랜잭션은 READ COMMITTED라 문장마다 그때 커밋된 값을 본다. 조회 실패는 예외로 알린다. 호출하는 쪽은 실패를 자격
 * 있음으로 취급하지 않는다.
 */
@Component
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class ChatRecipientStateReader {

    private final ChatRoomRepository chatRoomRepository;
    private final ChatUserRepository chatUserRepository;

    /**
     * @param roomOpen 방이 있고 참여자가 볼 수 있다(1:1 방은 종료 전, 단체방은 조회 만료 전)
     * @param state    회원 자격과 그 방의 참여 상태. 회원 복제본이 없으면 null
     */
    public record SubscriptionCheck(boolean roomOpen, ChatRecipientState state) {
    }

    /**
     * 방 topic 구독을 허가할 때 그 회원의 상태. 방이 없거나 볼 수 없으면(종료된 1:1 방, 조회 만료된 단체방) {@code roomOpen}이
     * false다
     */
    public SubscriptionCheck checkSubscription(Long roomId, Long userId) {
        // 구독 허가는 조회 권한과 같다: 1:1 방은 종료 전, 단체방은 조회 만료 전(읽기 전용·일시 중지 포함)
        boolean roomOpen = chatRoomRepository.findById(roomId)
                .map(room -> room.isViewable(LocalDateTime.now()))
                .orElse(false);
        Optional<UserWithMembership> row = chatUserRepository.findWithMembership(userId, roomId);
        if (row.isEmpty()) {
            return new SubscriptionCheck(roomOpen, null);
        }
        return new SubscriptionCheck(roomOpen, ChatRecipientState.of(row.get().user(), row.get().member()));
    }

    /** 주어진 회원 중 지금 채팅할 수 있는 회원. 복제본이 없는 회원은 채팅할 수 없다 */
    public Set<Long> findChatAllowedUserIds(Collection<Long> userIds) {
        if (userIds.isEmpty()) {
            return Set.of();
        }
        return chatUserRepository.findAllById(userIds).stream()
                .filter(ChatUser::canChat)
                .map(ChatUser::getId)
                .collect(Collectors.toSet());
    }
}
