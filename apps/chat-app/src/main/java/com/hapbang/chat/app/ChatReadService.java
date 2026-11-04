package com.hapbang.chat.app;

/**
 * 참여자의 읽음 위치를 올린다. 방 목록의 안 읽은 수가 이 위치 뒤의 상대 메시지 수로 바뀐다.
 * 수신 확인({@link ChatReceiptService})과 독립이다. 참여 세대 조건은 읽음 처리 계약을 따른다.
 */
public interface ChatReadService {

    /**
     * 로그인한 회원 본인의 읽음 순번을 올린다. 줄어들지 않고, 방의 마지막 순번을 넘지 않는다. 같은 값·낮은 값은 바꾸지 않는다.
     *
     * @param lastReadSequence     화면에 보여 준 가장 최근 메시지의 방 순번
     * @param participationVersion 읽은 참여 세대. 현재 세대가 아니면 {@code STALE_PARTICIPATION}.
     *                             null이면 처리 시점의 현재 세대로 본다(전환 전 FE 호환)
     */
    void markRead(Long chatRoomId, Long userId, long lastReadSequence, Integer participationVersion);
}
