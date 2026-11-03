package com.hapbang.appearance.out;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.hapbang.appearance.domain.HpReconciliationMismatch;

public interface HpReconciliationMismatchRepository extends JpaRepository<HpReconciliationMismatch, Long> {

    List<HpReconciliationMismatch> findByRunIdOrderByIdAsc(Long runId);
}
