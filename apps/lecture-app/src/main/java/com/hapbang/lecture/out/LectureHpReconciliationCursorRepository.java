package com.hapbang.lecture.out;

import org.springframework.data.jpa.repository.JpaRepository;

import com.hapbang.lecture.domain.LectureHpReconciliationCursor;
import com.hapbang.shared.billing.domain.BillingReferenceType;

/** 지갑 보류 목록 커서 (#580, D74). 참조 유형마다 한 행이다 */
public interface LectureHpReconciliationCursorRepository
        extends JpaRepository<LectureHpReconciliationCursor, BillingReferenceType> {
}
