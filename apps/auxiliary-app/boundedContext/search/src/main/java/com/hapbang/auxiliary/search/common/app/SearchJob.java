package com.hapbang.auxiliary.search.common.app;

import com.hapbang.auxiliary.global.scheduling.JobLockKey;

/**
 * 한 번에 한 곳에서만 실행하는 검색 작업과 그 advisory lock 번호(#771).
 * <p>
 * auxiliary database를 챗봇·알림 모듈과 함께 쓰므로 번호가 모듈 사이에서도 겹치면 안 된다. 그 작업을 더한 이슈 번호 × 1000 + 순번을 쓰고,
 * 한 번 정한 번호는 바꾸지 않는다({@link JobLockKey}). enum 안에서 겹치지 않는지는 테스트가 본다.
 */
public enum SearchJob implements JobLockKey {

    /** 7일 지난 tombstone 정리(하루 1번) */
    TOMBSTONE_CLEANUP(771_001L, "search-tombstone-cleanup"),
    /** 통계·분야 이름 동기화(10분마다) */
    STATS_SYNC(771_002L, "search-stats-sync"),
    /** 회원 변경분 증분 보정(10분마다, #772) */
    MEMBER_RECONCILE(772_001L, "search-member-reconcile"),
    /** 처음 적재·재색인(시작 때와 10분마다 확인, #773) */
    REINDEX(773_001L, "search-reindex"),
    /** 동의어 묶음 확인(10분마다, 모든 대상 공통 한 곳, #895) */
    SYNONYMS_CHECK(895_001L, "search-synonyms-check"),
    /** 강의 index의 7일 지난 tombstone 정리(하루 1번, #882). 대상마다 키를 따로 둔다(같은 키면 한 대상이 건너뛴다) */
    LECTURE_TOMBSTONE_CLEANUP(882_001L, "search-lecture-tombstone-cleanup");

    private final long lockKey;
    private final String tag;

    SearchJob(long lockKey, String tag) {
        this.lockKey = lockKey;
        this.tag = tag;
    }

    @Override
    public long lockKey() {
        return lockKey;
    }

    @Override
    public String tag() {
        return tag;
    }
}
