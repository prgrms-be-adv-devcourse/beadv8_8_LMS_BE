package com.hapbang.chat.in.dto;

import java.util.List;

import com.hapbang.chat.app.dto.ChatReadCounts;

/**
 * 방 topic 구독으로 보내는 안 읽은 사람 수 갱신 프레임(헤더 {@code chat-frame-type: READ_COUNTS}와 같은 {@code type}).
 * 계약: {@code apps/chat-app/docs/contracts/unread-count.md}
 */
public record ChatReadCountsFrame(
        FrameType type,
        Long roomId,
        long readCountVersion,
        long fromSequence,
        long toSequence,
        List<ChatReadCounts.Count> counts
) {

    public enum FrameType {
        READ_COUNTS
    }

    public static ChatReadCountsFrame of(ChatReadCounts readCounts) {
        return new ChatReadCountsFrame(FrameType.READ_COUNTS, readCounts.roomId(), readCounts.readCountVersion(),
                readCounts.fromSequence(), readCounts.toSequence(), readCounts.counts());
    }
}
