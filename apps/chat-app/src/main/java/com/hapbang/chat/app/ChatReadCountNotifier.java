package com.hapbang.chat.app;

import com.hapbang.chat.app.dto.ReadChangedFanout;

/**
 * 방을 연 로컬 구독에 안 읽은 사람 수 갱신({@code READ_COUNTS})을 방별로 묶어 보낸다(설계 Q36).
 * 계약: {@code apps/chat-app/docs/contracts/unread-count.md}
 */
public interface ChatReadCountNotifier {

    /** 변경 신호를 받는다. 그 방을 연 로컬 구독이 있으면 그 방을 다시 셀 방으로 표시한다 */
    void record(ReadChangedFanout readChanged);

    /** 표시된 방마다 한 번 다시 세어 그 방의 로컬 구독에 보낸다 */
    void flush();
}
