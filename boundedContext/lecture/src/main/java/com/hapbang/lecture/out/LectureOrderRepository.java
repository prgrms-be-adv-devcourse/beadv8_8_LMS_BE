package com.hapbang.lecture.out;

import java.util.Optional;
import java.util.Set;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.hapbang.lecture.domain.LectureOrder;
import com.hapbang.lecture.domain.OrderItem;
import com.hapbang.lecture.domain.OrderItemStatus;

public interface LectureOrderRepository extends JpaRepository<LectureOrder, Long> {

    /** 보유 중인 항목 상태 (D5). 부분 유일 인덱스 {@code uk_order_item_buyer_lecture_owned}의 조건과 같다 */
    Set<OrderItemStatus> HELD_STATUSES = Set.of(OrderItemStatus.PURCHASED, OrderItemStatus.CONFIRMED,
            OrderItemStatus.PAID_OUT);

    /** 구매자 본인의 주문. 남의 주문은 없는 주문과 같게 본다 (D32) */
    Optional<LectureOrder> findByIdAndBuyerUserId(Long id, Long buyerUserId);

    Page<LectureOrder> findByBuyerUserId(Long buyerUserId, Pageable pageable);

    /** 구매자가 이 강의를 보유한 항목. 부분 유일 인덱스로 많아야 하나다 */
    default Optional<OrderItem> findHeldItem(Long buyerUserId, Long lectureId) {
        return findItem(buyerUserId, lectureId, HELD_STATUSES);
    }

    /** 구매자가 보유한(시청할 수 있는) 항목. 최근 구매순으로 고정한다 */
    default Page<OrderItem> findHeldItems(Long buyerUserId, Pageable pageable) {
        return findItems(buyerUserId, HELD_STATUSES, pageable);
    }

    /** 구매자 본인의 주문 항목. 남의 항목은 없는 항목과 같게 본다 (D32) */
    @Query("select i from OrderItem i where i.id = :orderItemId and i.buyerUserId = :buyerUserId")
    Optional<OrderItem> findItemOfBuyer(@Param("orderItemId") Long orderItemId,
                                        @Param("buyerUserId") Long buyerUserId);

    @Query("select i from OrderItem i "
            + "where i.buyerUserId = :buyerUserId and i.lectureId = :lectureId and i.status in :statuses")
    Optional<OrderItem> findItem(@Param("buyerUserId") Long buyerUserId, @Param("lectureId") Long lectureId,
                                 @Param("statuses") Set<OrderItemStatus> statuses);

    @Query("select i from OrderItem i where i.buyerUserId = :buyerUserId and i.status in :statuses "
            + "order by i.purchasedAt desc, i.id desc")
    Page<OrderItem> findItems(@Param("buyerUserId") Long buyerUserId,
                              @Param("statuses") Set<OrderItemStatus> statuses, Pageable pageable);
}
