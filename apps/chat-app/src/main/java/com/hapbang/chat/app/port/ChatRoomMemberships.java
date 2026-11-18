package com.hapbang.chat.app.port;

import java.util.Map;

import com.hapbang.chat.app.dto.ChatRecipientState;

/**
 * 방 활성 참여자의 상태. 방 topic 전달·새 메시지 알림이 버전 확인 규칙(설계 4.2)으로 쓴다. 구현은 DB 조회를 메모리에 담아 두는
 * {@code out/cache}의 캐시다.
 * <p>
 * 조회 실패는 예외로 알린다. 호출하는 쪽은 실패를 자격 있음으로 보지 않는다.
 */
public interface ChatRoomMemberships {

    /**
     * 적어도 {@code minSetVersion}까지의 참여 변경을 반영한 방 참여 상태.
     *
     * @throws RuntimeException 조회 실패
     */
    RoomMembership get(Long roomId, int minSetVersion);

    /**
     * 담아 둔 상태와 관계없이 지금 DB에서 다시 읽는다(더 낮은 버전으로 덮지는 않는다). 담아 둔 상태가 구독보다 먼저 읽힌 탓에 판단이
     * 어긋날 수 있을 때(재입장 뒤 새 세대로 등록한 구독을 재입장 전에 읽은 상태로 판단하는 경우) 회수하기 전에 부른다.
     */
    RoomMembership refresh(Long roomId);

    /**
     * @param setVersion 상태를 읽기 전에 읽은 방의 현재 수신자 집합 버전
     * @param members    활성 참여자의 회원 ID → 상태
     */
    record RoomMembership(int setVersion, Map<Long, ChatRecipientState> members) {
    }
}
