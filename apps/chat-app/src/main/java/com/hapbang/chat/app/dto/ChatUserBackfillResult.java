package com.hapbang.chat.app.dto;

import java.util.List;

/**
 * 기존 회원 백필 한 번 실행의 결과 (설계 문서 5.4). 회원 정보(닉네임 등)는 담지 않고 집계와 회원 ID만 담는다.
 * <p>
 * {@code scanned = created + updated + skippedStale + failed}. {@code failedIds}는 이번 실행에서 실패한 회원 ID 전부다
 * (한 실행은 최대 {@code max-limit}명이라 크기가 제한된다).
 * {@code reachedEnd}는 회원 목록을 끝까지 읽었다는 뜻이고, {@code completed}는 끝까지 읽었고 실패가 없을 때만 true다.
 * 끝까지 읽지 않았으면 {@code nextAfterId}를 다음 실행의 afterId로 넘긴다. 실패한 회원은 다시 실행해 반영한다
 * (같은 회원을 다시 반영해도 버전 규칙으로 최신 값을 되돌리지 않는다).
 *
 * @param lastProcessedId 마지막으로 처리한(성공·실패 무관) 회원 ID. 처리한 회원이 없으면 null
 * @param nextAfterId     재개 커서. 끝까지 읽었으면 null
 */
public record ChatUserBackfillResult(
        long scanned,
        long created,
        long updated,
        long skippedStale,
        long failed,
        List<Long> failedIds,
        Long lastProcessedId,
        Long nextAfterId,
        boolean reachedEnd,
        boolean completed
) {

    public ChatUserBackfillResult {
        failedIds = List.copyOf(failedIds);
    }
}
