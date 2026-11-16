package com.hapbang.appearance.out;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.hapbang.appearance.domain.HpReconciliationRun;
import com.hapbang.appearance.domain.HpReconciliationStatus;

public interface HpReconciliationRunRepository extends JpaRepository<HpReconciliationRun, Long> {

    /**
     * 실행 시작 구간 잠금 번호 (#911). advisory lock 번호는 database 하나 안에서만 겹치지 않으면 된다.
     * 출연 database에서 advisory lock을 새로 쓰면 다른 번호를 고른다.
     */
    long START_LOCK_KEY = 581_911L;

    /**
     * 실행 시작 구간을 잠근다 (#911). 트랜잭션이 끝날 때 풀린다. 다른 트랜잭션이 잡고 있으면 풀릴 때까지 기다린다.
     * {@code pg_advisory_xact_lock}은 void를 돌려주므로 FROM에 두고 1을 읽는다.
     */
    @Query(value = "select 1 from pg_advisory_xact_lock(" + START_LOCK_KEY + ")", nativeQuery = true)
    int lockStart();

    /** 마지막으로 시작한 실행 (관리자 결과 조회) */
    Optional<HpReconciliationRun> findFirstByOrderByStartedAtDescIdDesc();

    /** 실행 중인 실행을 찾는다 (앱이 멈춰 남은 실행 정리, 다른 pod 실행 확인 #821) */
    List<HpReconciliationRun> findByStatus(HpReconciliationStatus status);

    /** 이 상태로 마지막에 끝난 실행 (기동할 때 지표 복원, #821) */
    Optional<HpReconciliationRun> findFirstByStatusOrderByFinishedAtDescIdDesc(HpReconciliationStatus status);
}
