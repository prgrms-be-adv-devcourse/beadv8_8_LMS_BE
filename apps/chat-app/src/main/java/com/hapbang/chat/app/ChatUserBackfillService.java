package com.hapbang.chat.app;

import com.hapbang.chat.app.dto.ChatUserBackfillResult;

/**
 * 기존 회원 백필(설계 문서 5.4). Kafka 전환 이전 회원과 전환 중 놓친 변경을 회원 원본 스냅샷으로 채팅 회원 복제본에 맞춘다.
 */
public interface ChatUserBackfillService {

    /**
     * afterId보다 큰 회원부터 최대 limit명을 반영한다.
     *
     * @throws com.hapbang.chat.app.exception.ChatUserBackfillException 회원 원본 조회가 실패해 실행을 멈췄을 때(집계·재개 커서 포함)
     */
    ChatUserBackfillResult backfill(long afterId, int limit);
}
