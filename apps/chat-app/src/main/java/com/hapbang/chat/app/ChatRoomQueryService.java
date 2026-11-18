package com.hapbang.chat.app;

import java.util.List;

import com.hapbang.chat.app.dto.ChatRoomHistoryResponse;
import com.hapbang.chat.app.dto.ChatRoomMemberPageResponse;
import com.hapbang.chat.app.dto.ChatRoomResponse;

/**
 * 채팅방 읽기 전용 기능. 쓰기({@link ChatRoomService})와 나눈다. 한 시점의 스냅샷으로 읽도록 새 읽기 트랜잭션을 열므로
 * 트랜잭션 밖(Facade)에서 부른다.
 */
public interface ChatRoomQueryService {

    /** 내 채팅방 목록 */
    List<ChatRoomResponse> getMyRooms(Long userId);

    /** 방 상세. 참여 중인 회원만 볼 수 있고, 단체방은 조회 만료 전까지다 */
    ChatRoomResponse getRoom(Long chatRoomId, Long userId);

    /**
     * 방의 활성 참여자 한 페이지. 참여 중인 회원만 볼 수 있고, 단체방은 조회 만료 전까지다.
     *
     * @param cursor 이전 응답의 nextCursor. 없으면 처음부터
     */
    ChatRoomMemberPageResponse getMembers(Long chatRoomId, Long userId, Long cursor, int size);

    /**
     * 관리자 분쟁 확인용 기록 조회. 종료된 방과 참여자별로 숨겨진 메시지까지 포함한다.
     *
     * @param cursor 이전 응답의 nextCursor. 없으면 처음부터
     */
    ChatRoomHistoryResponse getHistory(Long chatRoomId, Long adminId, String cursor, int size);

    /** 채팅 시작 응답. 저장 트랜잭션이 커밋된 뒤 부른다 */
    ChatRoomResponse toResponse(Long chatRoomId, Long userId);
}
