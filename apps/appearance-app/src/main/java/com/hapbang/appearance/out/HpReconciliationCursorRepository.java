package com.hapbang.appearance.out;

import org.springframework.data.jpa.repository.JpaRepository;

import com.hapbang.appearance.domain.HpReconciliationCursor;
import com.hapbang.appearance.domain.HpTaskRefType;

public interface HpReconciliationCursorRepository extends JpaRepository<HpReconciliationCursor, HpTaskRefType> {
}
