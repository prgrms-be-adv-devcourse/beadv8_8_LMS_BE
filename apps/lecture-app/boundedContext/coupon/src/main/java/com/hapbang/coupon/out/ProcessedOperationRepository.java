package com.hapbang.coupon.out;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.hapbang.coupon.domain.ProcessedOperation;

/** 처리한 작업의 멱등 기록 저장소. 작업 ID는 UNIQUE({@code uk_processed_operation_operation_id})다 */
public interface ProcessedOperationRepository extends JpaRepository<ProcessedOperation, Long> {

    /** 작업 ID UNIQUE 제약 이름. 같은 작업을 동시에 넣으면 이 제약이 막는다 */
    String OPERATION_ID_UNIQUE = "uk_processed_operation_operation_id";

    Optional<ProcessedOperation> findByOperationId(String operationId);
}
