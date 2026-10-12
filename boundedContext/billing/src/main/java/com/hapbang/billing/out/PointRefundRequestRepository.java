package com.hapbang.billing.out;

import java.util.Optional;

import org.springframework.data.repository.Repository;

import com.hapbang.billing.domain.PointRefundRequest;

// 환불 요청 정보의 저장·조회를 담당하며 금융 데이터 삭제 메서드는 제공하지 않는다.
public interface PointRefundRequestRepository extends Repository<PointRefundRequest, Long> {

    // 환불 요청 정보를 저장한다.
    PointRefundRequest save(PointRefundRequest entity);

    // 기본키 ID로 환불 요청 정보를 조회한다.
    Optional<PointRefundRequest> findById(Long id);

    // 멱등키로 기존 환불 요청 정보를 조회한다.
    Optional<PointRefundRequest> findByIdempotencyKey(String idempotencyKey);
}
