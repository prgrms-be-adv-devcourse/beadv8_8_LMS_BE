package com.hapbang.chat.app.dto;

import java.util.List;

/**
 * 방 topic 구독 하나에 쓰는 안 읽은 사람 수 갱신. 계약: {@code apps/chat-app/docs/contracts/unread-count.md}
 *
 * @param readCountVersion 센 시점(DB 시각, epoch 마이크로초)
 * @param fromSequence     이 갱신이 다루는 첫 순번(그 구독 회원의 가시 범위 시작 이상)
 * @param toSequence       이 갱신이 다루는 끝 순번(센 시점의 방 마지막 순번)
 * @param counts           구간 안에서 값이 0보다 큰 일반 메시지만. 구간 안의 일반 메시지 중 빠진 순번은 0이다
 */
public record ChatReadCounts(
        Long roomId,
        long readCountVersion,
        long fromSequence,
        long toSequence,
        List<Count> counts
) {

    /** 일반 메시지 하나의 안 읽은 사람 수 */
    public record Count(long roomSequence, int unreadMemberCount) {
    }
}
