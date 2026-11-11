package com.hapbang.auxiliary.notification.app;

import java.util.Optional;

/** 관리자 Backfill 실행 상태 저장소(ND-092). 운영은 Redis(7일 TTL)다 */
public interface BackfillRunStore {

    /** 같은 실행 ID면 덮어쓴다 */
    void save(BackfillRun run);

    Optional<BackfillRun> find(String runId);
}
