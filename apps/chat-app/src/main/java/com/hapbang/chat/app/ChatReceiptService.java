package com.hapbang.chat.app;

import com.hapbang.chat.app.dto.ChatReceiptResponse;

/**
 * 클라이언트의 누적 수신 확인(ACK)을 참여자의 수신 확인 순번에 반영한다(설계 문서 4.4).
 * 읽음 위치·안 읽은 수와 독립이며, 서버 발행 완료로 대신하지 않는다.
 */
public interface ChatReceiptService {

    /**
     * 로그인한 회원 본인의 수신 확인 순번을 올린다. 같은 값·낮은 값은 바꾸지 않고 현재 값을 돌려준다(멱등).
     *
     * @param userId               로그인 principal의 회원 ID. 요청 값으로 다른 회원을 지정하지 않는다
     * @param participationVersion 클라이언트가 확인한 참여 세대. 현재 세대가 아니면 {@code STALE_PARTICIPATION}
     * @param receivedSequence     현재 참여 세대의 가시 범위에서 빠짐없이 반영한 연속 구간의 끝 순번.
     *                             조회 시작점보다 작거나 방의 마지막 순번보다 크면 {@code RECEIVED_SEQUENCE_OUT_OF_RANGE}
     * @return 반영 뒤 서버의 수신 확인 순번
     */
    ChatReceiptResponse markReceived(Long chatRoomId, Long userId, int participationVersion, long receivedSequence);
}
