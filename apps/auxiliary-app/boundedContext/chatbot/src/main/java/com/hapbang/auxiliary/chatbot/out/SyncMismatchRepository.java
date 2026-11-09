package com.hapbang.auxiliary.chatbot.out;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.auxiliary.chatbot.domain.SyncMismatch;
import com.hapbang.auxiliary.chatbot.domain.SyncType;

public interface SyncMismatchRepository extends JpaRepository<SyncMismatch, Long> {

    List<SyncMismatch> findByRunIdOrderByIdAsc(Long runId);

    /**
     * 그 종류의 최근 keep개 회차 밖의 어긋남을 지운다. 회차 기록은 남긴다.
     * 건너뛴 회차({@code SKIPPED})는 어긋남이 없으므로 세지 않는다. 건너뜀이 이어져도 실제로 돈 회차의 기록이 남는다.
     */
    @Transactional
    @Modifying
    @Query(value = """
            DELETE FROM chatbot.sync_mismatch
            WHERE type = :#{#type.name()}
              AND run_id NOT IN (
                  SELECT id FROM chatbot.sync_run
                  WHERE type = :#{#type.name()} AND status <> 'SKIPPED'
                  ORDER BY started_at DESC, id DESC LIMIT :keep)
            """, nativeQuery = true)
    int deleteOutsideRecentRuns(@Param("type") SyncType type, @Param("keep") int keep);
}
