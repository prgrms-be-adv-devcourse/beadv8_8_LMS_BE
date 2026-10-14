package com.hapbang.lecture.out;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.hapbang.lecture.domain.LectureOrder;

public interface LectureOrderRepository extends JpaRepository<LectureOrder, Long> {

    /** 구매자 본인의 주문. 남의 주문은 없는 주문과 같게 본다 (D32) */
    Optional<LectureOrder> findByIdAndBuyerUserId(Long id, Long buyerUserId);

    Page<LectureOrder> findByBuyerUserId(Long buyerUserId, Pageable pageable);
}
