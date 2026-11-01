package com.hapbang.chat.app.dto;

import java.util.Map;

/**
 * 작업 큐에서 읽은 항목.
 *
 * @param entryId 큐 안의 항목 위치. 작업 식별에는 {@code fields}의 안정 ID를 쓴다
 * @param fields  적재한 참조 필드
 */
public record QueuedJob(String entryId, Map<String, String> fields) {
}
