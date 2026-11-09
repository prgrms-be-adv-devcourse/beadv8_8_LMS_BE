package com.hapbang.auxiliary.chatbot.app;

import java.time.LocalDateTime;
import java.util.List;

import com.hapbang.auxiliary.chatbot.domain.SyncType;
import com.hapbang.auxiliary.chatbot.out.CopyWrite;
import com.hapbang.auxiliary.chatbot.out.MissingCopy;

/**
 * 싱크 한 종류 (#716). 원본 키셋 API를 읽고, 항목을 적용기로 반영하고, 끝에 원본 없음을 표시한다.
 * 회원·강의(#717)는 이 인터페이스를 더 구현한다.
 *
 * @param <T> 원본 DTO
 */
interface SyncTarget<T> {

    SyncType type();

    ChatbotJob job();

    /** 이 ID보다 큰 항목을 ID 오름차순으로 size개까지. afterId가 null이면 처음부터 */
    Page<T> fetch(Long afterId, int size);

    /** 항목의 원본 ID (키셋 확인·어긋남 기록) */
    long idOf(T item);

    /** 항목의 원본 version. version 없는 데이터면 null */
    Long versionOf(T item);

    CopyWrite apply(T item, long runId);

    List<MissingCopy> markMissing(long runId, LocalDateTime startedAt);

    /** 원본 한 페이지 */
    record Page<T>(List<T> items, Long nextAfterId) {
    }
}
