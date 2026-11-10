package com.hapbang.lecture.out;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.hapbang.lecture.domain.OrderCouponWork;
import com.hapbang.lecture.domain.OrderCouponWorkKind;
import com.hapbang.lecture.domain.OrderCouponWorkStatus;

/** 주문의 쿠폰 예약·해제 작업. 행을 지우지 않는다 */
public interface OrderCouponWorkRepository extends JpaRepository<OrderCouponWork, Long> {

    /** 주문의 이 종류 작업. 주문마다 종류별로 하나다 */
    Optional<OrderCouponWork> findByOrderIdAndKind(Long orderId, OrderCouponWorkKind kind);

    /**
     * 관리자 목록: 작업과 그 주문의 상태를 한 번에 읽는다(작업마다 주문을 따로 읽지 않는다). 최신순이고 정렬은 쿼리에 고정한다.
     * 상태·종류는 선택 필터다(null이면 전부)
     */
    @Query(value = """
            select new com.hapbang.lecture.out.OrderCouponWorkRow(w, o.status)
            from OrderCouponWork w, LectureOrder o
            where o.id = w.orderId
              and (:status is null or w.status = :status)
              and (:kind is null or w.kind = :kind)
            order by w.createdAt desc, w.id desc
            """,
            countQuery = """
                    select count(w) from OrderCouponWork w
                    where (:status is null or w.status = :status)
                      and (:kind is null or w.kind = :kind)
                    """)
    Page<OrderCouponWorkRow> search(@Param("status") OrderCouponWorkStatus status,
                                    @Param("kind") OrderCouponWorkKind kind, Pageable pageable);

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
