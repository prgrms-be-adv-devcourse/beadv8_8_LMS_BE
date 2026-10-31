package com.hapbang.chat.app;

import com.hapbang.chat.app.dto.RoomActivityFanout;

/**
 * 방을 열지 않은 참여자에게 새 메시지 알림({@code ROOM_ACTIVITY})을 방별로 묶어 보낸다(설계 4.2, Q15·Q21).
 */
public interface ChatRoomActivityNotifier {

    /** 알림 재료를 묶음에 넣는다. 알림 대상이 없는 메시지(퇴장·재입장 안내)는 넣지 않는다 */
    void record(RoomActivityFanout activity);

    /** 모인 묶음을 방마다 보낸다 */
    void flush();
}
