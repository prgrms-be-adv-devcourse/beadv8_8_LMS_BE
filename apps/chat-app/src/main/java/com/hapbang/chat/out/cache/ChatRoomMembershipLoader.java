package com.hapbang.chat.out.cache;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.chat.app.dto.ChatRecipientState;
import com.hapbang.chat.app.port.ChatRoomMemberRepository;
import com.hapbang.chat.app.port.ChatRoomMemberRows.MemberWithUser;
import com.hapbang.chat.app.port.ChatRoomMemberships.RoomMembership;
import com.hapbang.chat.app.port.ChatRoomRepository;

import lombok.RequiredArgsConstructor;

/**
 * 방 참여 캐시({@link CachedChatRoomMemberships})가 담을 방 참여 상태를 chat DB에서 repository port로 읽는다. 캐시와 다른 빈이라
 * 캐시가 Spring 프록시를 거쳐 부르고, 한 번 읽을 때 읽기 전용 트랜잭션 하나로 읽는다. 읽기 전용 트랜잭션은 READ COMMITTED라 문장마다
 * 그때 커밋된 값을 본다.
 */
@Component
@RequiredArgsConstructor
public class ChatRoomMembershipLoader {

    private final ChatRoomRepository chatRoomRepository;
    private final ChatRoomMemberRepository chatRoomMemberRepository;

    /**
     * 방의 현재 수신자 집합 버전과 활성 참여자 전원의 상태. 버전을 먼저 읽고 참여 상태를 나중에 읽는다. 그래서 돌려준 상태는 적어도
     * 돌려준 버전까지의 참여 변경을 반영한다(그 뒤에 커밋된 변경이 섞이면 더 엄격해질 뿐이다).
     *
     * @return 방이 없거나 수신자 집합이 없으면 버전 0과 빈 참여자
     */
    @Transactional(readOnly = true)
    public RoomMembership load(Long roomId) {
        // 버전을 먼저 읽는다. 그 뒤에 읽는 참여 상태는 이 버전까지의 참여 변경을 반드시 반영한다
        Optional<Integer> version = chatRoomRepository.findCurrentRecipientSetVersion(roomId);
        if (version.isEmpty()) {
            return new RoomMembership(0, Map.of());
        }
        Map<Long, ChatRecipientState> members = new HashMap<>();
        for (MemberWithUser row : chatRoomMemberRepository.findActiveWithUser(roomId)) {
            members.put(row.user().getId(), ChatRecipientState.of(row.user(), row.member()));
        }
        return new RoomMembership(version.get(), members);
    }
}
