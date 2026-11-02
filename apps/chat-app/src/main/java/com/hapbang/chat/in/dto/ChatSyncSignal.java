package com.hapbang.chat.in.dto;

import java.time.LocalDateTime;

import com.hapbang.chat.app.dto.ChatResyncReason;

/**
 * 재동기화 신호. /user/queue/chats/sync로 로컬 연결 회원에게 보낸다(resync-frame.md, Q11 초안).
 * FE는 방 목록과 열어 둔 방의 이력을 자기 확인 위치부터 다시 대조한다. 모르는 reason도 재동기화로 처리한다.
 *
 * @param occurredAt 서버 시각
 */
public record ChatSyncSignal(SignalType type, ChatResyncReason reason, LocalDateTime occurredAt) {

    public enum SignalType {
        RESYNC
    }

    public static ChatSyncSignal resync(ChatResyncReason reason, LocalDateTime occurredAt) {
        return new ChatSyncSignal(SignalType.RESYNC, reason, occurredAt);
    }
}
