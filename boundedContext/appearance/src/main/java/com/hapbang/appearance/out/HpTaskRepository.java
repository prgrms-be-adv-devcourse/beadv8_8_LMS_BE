package com.hapbang.appearance.out;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.hapbang.appearance.domain.HpTask;
import com.hapbang.appearance.domain.HpTaskRefType;
import com.hapbang.appearance.domain.HpTaskType;

public interface HpTaskRepository extends JpaRepository<HpTask, Long> {

    /** 대상(요청서·모집글)의 가장 최근 작업 (종류별) */
    Optional<HpTask> findFirstByRefTypeAndRefIdAndTaskTypeOrderByIdDesc(HpTaskRefType refType, Long refId,
                                                                       HpTaskType taskType);
}
