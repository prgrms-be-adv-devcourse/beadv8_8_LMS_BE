package com.hapbang.chat.app;

import com.hapbang.chat.app.dto.ChatResyncReason;
import com.hapbang.chat.app.dto.MessageFanout;
import com.hapbang.chat.app.dto.RoomActivityFanout;

/**
 * 실시간 경로로 받은 방 메시지를 로컬 방 topic 구독에 내보낸다. 보내기 직전에 구독의 참여 상태를 버전 확인 규칙으로 확인한다.
 */
public interface ChatFanoutDeliveryService {

    /**
     * 그 방의 로컬 구독 중 참여 상태(현재 활성 참여·구독 세대 일치·가시 범위·회원 자격)가 맞는 구독에만 방 topic 프레임을 쓴다.
     * 맞지 않는 구독은 회수한다. 확인에 실패하면 아무에게도 쓰지 않는다.
     */
    void deliver(MessageFanout fanout);

    /**
     * 보낸 사람 회원의 로컬 연결에 전송 결과 확인을 보낸다(서버 채널의 알림 재료로 받는다. 방 구독과 무관하다). 전송 ID가 없으면
     * 전송 ID 없이 보낸다. 회원이 보낸 일반 메시지가 아니거나(시스템 안내·알림) 그 회원의 로컬 연결이 없으면 하지 않는다.
     */
    void confirmToSender(RoomActivityFanout activity);

    /** 모든 로컬 연결 회원에게 재동기화 신호를 보낸다 */
    void resyncAll(ChatResyncReason reason);

    /**
     * 전달하지 못하고 버린 방 메시지의 로컬 구독 회원에게만 재동기화 신호를 보낸다. 신호에는 내용이 없으므로 참여 상태는 다시
     * 확인하지 않는다(받은 회원은 이력 API의 권한 검사를 거쳐 다시 읽는다).
     */
    void resyncRoomSubscribers(Long roomId, ChatResyncReason reason);
}
