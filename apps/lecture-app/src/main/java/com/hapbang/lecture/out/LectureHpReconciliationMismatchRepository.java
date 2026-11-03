package com.hapbang.lecture.out;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.hapbang.lecture.domain.LectureHpReconciliationMismatch;

/** 서비스 간 HP 대사에서 어긋난 건 (#580, D74) */
public interface LectureHpReconciliationMismatchRepository
        extends JpaRepository<LectureHpReconciliationMismatch, Long> {

    /** 실행 하나의 어긋난 건 (남긴 순서) */
    List<LectureHpReconciliationMismatch> findByRunIdOrderByIdAsc(Long runId);
}
