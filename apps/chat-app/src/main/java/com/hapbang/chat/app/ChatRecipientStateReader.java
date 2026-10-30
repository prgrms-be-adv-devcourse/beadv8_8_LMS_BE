package com.hapbang.chat.app;

import java.util.Collection;
import java.util.Map;
import java.util.Set;

import com.hapbang.chat.app.dto.ChatRecipientState;

/**
 * 실시간 전달·방 topic 구독 허가에 필요한 최신 상태를 chat DB에서 읽는다. 회원마다 쿼리를 반복하지 않는다.
 * 조회 실패는 예외로 알린다. 호출하는 쪽은 실패를 자격 있음으로 취급하지 않는다.
 */
public interface ChatRecipientStateReader {

    /**
     * 방의 현재 수신자 집합 버전과 활성 참여자 전원의 상태. 버전을 먼저 읽고 참여 상태를 나중에 읽는다. 그래서 돌려준 상태는 적어도
     * 돌려준 버전까지의 참여 변경을 반영한다(그 뒤에 커밋된 변경이 섞이면 더 엄격해질 뿐이다).
     *
     * @return 방이 없거나 수신자 집합이 없으면 버전 0과 빈 참여자
     */
    RoomMembership findRoomMembership(Long roomId);

    /** 방 topic 구독을 허가할 때 그 회원의 상태. 방이 없거나 종료됐으면 {@code roomOpen}이 false다 */
    SubscriptionCheck checkSubscription(Long roomId, Long userId);

    /** 주어진 회원 중 지금 채팅할 수 있는 회원. 복제본이 없는 회원은 채팅할 수 없다 */
    Set<Long> findChatAllowedUserIds(Collection<Long> userIds);

    /**
     * @param setVersion 상태를 읽기 전에 읽은 방의 현재 수신자 집합 버전
     * @param members    활성 참여자의 회원 ID → 상태
     */
    record RoomMembership(int setVersion, Map<Long, ChatRecipientState> members) {
    }

    /**
     * @param roomOpen 방이 있고 종료되지 않았다
     * @param state    회원 자격과 그 방의 참여 상태. 회원 복제본이 없으면 null
     */
    record SubscriptionCheck(boolean roomOpen, ChatRecipientState state) {
    }
}
