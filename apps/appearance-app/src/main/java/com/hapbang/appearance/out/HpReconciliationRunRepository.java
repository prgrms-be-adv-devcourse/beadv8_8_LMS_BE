package com.hapbang.appearance.out;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.hapbang.appearance.domain.HpReconciliationRun;
import com.hapbang.appearance.domain.HpReconciliationStatus;

public interface HpReconciliationRunRepository extends JpaRepository<HpReconciliationRun, Long> {

    /** 마지막으로 시작한 실행 (관리자 결과 조회) */
    Optional<HpReconciliationRun> findFirstByOrderByStartedAtDescIdDesc();

    /** 앱이 멈춰 실행 중으로 남은 실행을 찾는다 */
    List<HpReconciliationRun> findByStatus(HpReconciliationStatus status);

    /** 이 상태로 마지막에 끝난 실행 (기동할 때 지표 복원, #821) */
    Optional<HpReconciliationRun> findFirstByStatusOrderByFinishedAtDescIdDesc(HpReconciliationStatus status);
}
