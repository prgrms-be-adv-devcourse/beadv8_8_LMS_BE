package com.hapbang.auxiliary.chatbot.out;

/**
 * 원본 없음으로 새로 표시한 사본 행 (#716).
 *
 * @param sourceId   원본 ID
 * @param ourVersion 우리 version. version 없는 데이터면 null
 */
public record MissingCopy(long sourceId, Long ourVersion) {
}
