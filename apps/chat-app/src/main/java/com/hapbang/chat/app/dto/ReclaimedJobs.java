package com.hapbang.chat.app.dto;

import java.util.List;

/**
 * 미확인 작업 회수 결과.
 *
 * @param nextCursor      다음 회수를 시작할 위치. {@code 0-0}이면 한 바퀴를 다 돌았다
 * @param jobs            가져온 작업
 * @param deletedEntryIds 미확인 목록에 있었지만 본문이 사라진 항목. 성공으로 보지 않고 DB 재구성으로 넘긴다
 */
public record ReclaimedJobs(String nextCursor, List<QueuedJob> jobs, List<String> deletedEntryIds) {
}
