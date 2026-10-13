package com.hapbang.billing.out;

import java.util.List;
import java.util.Optional;

import org.springframework.data.repository.Repository;

import com.hapbang.billing.domain.PointHoldDetail;

// 보류 처리 상세 정보의 저장·조회를 담당하며 금융 데이터 삭제 메서드는 제공하지 않는다.
public interface PointHoldDetailRepository extends Repository<PointHoldDetail, Long> {

    // 보류 처리 상세 정보를 저장한다.
    PointHoldDetail save(PointHoldDetail entity);

    // 기본키 ID로 보류 처리 상세 정보를 조회한다.
    Optional<PointHoldDetail> findById(Long id);

    // 멱등키로 기존 보류 처리 상세 정보를 조회한다.
    Optional<PointHoldDetail> findByIdempotencyKey(String idempotencyKey);

    // 특정 보류에 속한 반환·지급 상세를 ID 오름차순으로 조회한다.
    List<PointHoldDetail> findAllByPointHoldIdOrderByIdAsc(Long pointHoldId);
}
