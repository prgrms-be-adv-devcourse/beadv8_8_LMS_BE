package com.hapbang.auxiliary.chatbot.out;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.hapbang.auxiliary.chatbot.domain.SyncRun;
import com.hapbang.auxiliary.chatbot.domain.SyncRunStatus;
import com.hapbang.auxiliary.chatbot.domain.SyncType;

public interface SyncRunRepository extends JpaRepository<SyncRun, Long> {

    /** 그 종류에 이 상태의 회차가 있는지(첫 실행 판단: 성공 회차가 없으면 첫 실행) */
    boolean existsByTypeAndStatus(SyncType type, SyncRunStatus status);

    /** 그 종류에 이 시각 이후 시작한 이 상태의 회차가 있는지(재시도: 오늘 성공 회차) */
    boolean existsByTypeAndStatusAndStartedAtGreaterThanEqual(SyncType type, SyncRunStatus status, LocalDateTime from);

    /** 그 종류의 이 상태 회차들(끝을 기록하지 못한 RUNNING 회차 정리) */
    List<SyncRun> findByTypeAndStatus(SyncType type, SyncRunStatus status);

    Page<SyncRun> findByOrderByStartedAtDescIdDesc(Pageable pageable);

    Page<SyncRun> findByTypeOrderByStartedAtDescIdDesc(SyncType type, Pageable pageable);
}
