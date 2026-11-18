package com.hapbang.chat.app.port;

import java.util.Map;
import java.util.UUID;

/**
 * 작업 큐에서 읽은 항목.
 *
 * @param entryId 큐 안의 항목 위치. 작업 식별에는 {@code jobId}를 쓴다
 * @param jobId   항목에 담은 안정 ID. 없거나 형식이 틀리면 null(격리 대상)
 * @param fields  적재한 참조 필드
 */
public record QueuedJob(String entryId, UUID jobId, Map<String, String> fields) {
}
