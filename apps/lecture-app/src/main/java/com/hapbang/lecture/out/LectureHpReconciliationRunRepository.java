package com.hapbang.lecture.out;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.hapbang.lecture.domain.LectureHpReconciliationRun;
import com.hapbang.lecture.domain.LectureHpReconciliationStatus;

/** 서비스 간 HP 대사 실행 기록 (#580, D74) */
public interface LectureHpReconciliationRunRepository extends JpaRepository<LectureHpReconciliationRun, Long> {

    /** 앱이 멈춰 실행 중으로 남은 실행을 찾는다 */
    List<LectureHpReconciliationRun> findByStatus(LectureHpReconciliationStatus status);

    /** 마지막으로 시작한 실행 */
    Optional<LectureHpReconciliationRun> findFirstByOrderByStartedAtDescIdDesc();

    /** 이 상태로 마지막에 끝난 실행 (지표 복원은 COMPLETED, #805) */
    Optional<LectureHpReconciliationRun> findFirstByStatusOrderByFinishedAtDescIdDesc(
            LectureHpReconciliationStatus status);
}
