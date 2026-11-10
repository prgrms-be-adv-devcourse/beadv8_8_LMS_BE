package com.hapbang.lecture.out;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.hapbang.lecture.domain.OrderCouponWork;
import com.hapbang.lecture.domain.OrderCouponWorkKind;

/** 주문의 쿠폰 예약·해제 작업. 행을 지우지 않는다 */
public interface OrderCouponWorkRepository extends JpaRepository<OrderCouponWork, Long> {

    /** 주문의 이 종류 작업. 주문마다 종류별로 하나다 */
    Optional<OrderCouponWork> findByOrderIdAndKind(Long orderId, OrderCouponWorkKind kind);

    /** 작업 ID(쿠폰 모듈 멱등 키)로 찾는다. 작업 ID는 유일하다 */
    Optional<OrderCouponWork> findByWorkId(String workId);

    /** 재시도 배치 대상: 다음 시도 시각이 지난 {@code PENDING} 작업. 오래 기다린 것부터 */
    @Query("""
            select w from OrderCouponWork w
            where w.status = com.hapbang.lecture.domain.OrderCouponWorkStatus.PENDING
              and w.nextAttemptAt <= :now
            order by w.nextAttemptAt asc, w.id asc
            """)
    List<OrderCouponWork> findDue(@Param("now") LocalDateTime now, Pageable pageable);
}
