package com.hapbang.chat.app;

import java.time.LocalDateTime;

import com.hapbang.chat.app.dto.ChatRoomLeaveResult;
import com.hapbang.chat.app.dto.ChatRoomOpenResult;
import com.hapbang.chat.domain.ChatRoom;

/**
 * 1:1 채팅방을 열고 나간다. 메서드마다 하나의 쓰기 트랜잭션이고 저장만 한다. 방 응답 조립은
 * {@link ChatRoomQueryService}가 커밋 뒤 따로 한다(설계 4.6).
 */
public interface ChatRoomService {

    /**
     * 1:1 채팅을 시작한다. 두 사람의 종료되지 않은 방이 있으면 재사용하고(나갔던 사람은 재입장), 없으면 새로 만든다.
     * 동시에 만들면 유일 제약(direct_key)에 걸리므로 호출한 Facade가 새 트랜잭션에서 다시 시도한다.
     */
    ChatRoomOpenResult createOrGet(Long requesterId, Long targetUserId);

    /**
     * 나간다. 이미 나갔거나 옛 참여 세대의 요청이면 아무것도 바꾸지 않는다(멱등).
     *
     * @param participationVersion 요청한 참여 세대. null이면 현재 세대로 본다.
     */
    ChatRoomLeaveResult leave(Long chatRoomId, Long userId, Integer participationVersion);

    /**
     * 두 회원의 1:1 방을 연다. 채팅 시작({@link #createOrGet})과 출연 알림이 같은 규칙을 쓴다. 부른 쪽의 쓰기 트랜잭션에서 한다.
     * 새 방을 동시에 만들면 유일 제약(direct_key)에 걸리므로 호출한 Facade가 다시 시도한다.
     */
    OpenedRoom openDirectRoom(Long requesterId, Long targetUserId, LocalDateTime now);

    /**
     * 연 방. 기존 방이면 잠긴 상태다. 나갔던 회원을 재입장시켰으면 남아 있던 회원에게 보일 재입장 안내의 전달은 outbox가 맡는다.
     */
    record OpenedRoom(ChatRoom chatRoom, boolean created) {
    }
}
