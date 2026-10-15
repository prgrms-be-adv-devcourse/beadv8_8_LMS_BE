package com.hapbang.lecture.out;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.hapbang.lecture.domain.LectureOrder;
import com.hapbang.lecture.domain.OrderItem;
import com.hapbang.lecture.domain.OrderItemStatus;

import jakarta.persistence.LockModeType;

public interface LectureOrderRepository extends JpaRepository<LectureOrder, Long> {

    /** 보유 중인 항목 상태 (D5). 부분 유일 인덱스 {@code uk_order_item_buyer_lecture_owned}의 조건과 같다 */
    Set<OrderItemStatus> HELD_STATUSES = Set.of(OrderItemStatus.PURCHASED, OrderItemStatus.CONFIRMED,
            OrderItemStatus.PAID_OUT);

    /** 구매자 본인의 주문. 남의 주문은 없는 주문과 같게 본다 (D32) */
    Optional<LectureOrder> findByIdAndBuyerUserId(Long id, Long buyerUserId);

    Page<LectureOrder> findByBuyerUserId(Long buyerUserId, Pageable pageable);

    /**
     * 구매자 본인의 주문을 행 잠금으로 읽는다. 주문을 닫을 수 있는 변경(구매 취소)이 같은 주문의 다른 변경을 기다리게 해,
     * 앞 변경이 커밋한 항목 상태를 보고 마지막 항목인지 판정한다. 항목은 잠근 뒤에 읽어야 하므로 함께 읽지 않는다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from LectureOrder o where o.id = :id and o.buyerUserId = :buyerUserId")
    Optional<LectureOrder> findForUpdate(@Param("id") Long id, @Param("buyerUserId") Long buyerUserId);

    /**
     * 주문을 행 잠금으로 읽는다. 구매자를 모르는 변경(이의 제기 인정)이 주문을 닫을 수 있을 때 쓴다 (D46).
     * 항목은 잠근 뒤에 읽어야 하므로 함께 읽지 않는다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from LectureOrder o where o.id = :id")
    Optional<LectureOrder> findForUpdateById(@Param("id") Long id);

    /** 이 주문 항목이 속한 주문의 ID. 주문을 잠그기 전에 항목 엔티티를 읽지 않도록 ID만 읽는다 */
    @Query("select i.order.id from OrderItem i where i.id = :itemId")
    Optional<Long> findOrderIdByItemId(@Param("itemId") Long itemId);

    /** 구매자가 이 강의를 보유한 항목. 부분 유일 인덱스로 많아야 하나다 */
    default Optional<OrderItem> findHeldItem(Long buyerUserId, Long lectureId) {
        return findItem(buyerUserId, lectureId, HELD_STATUSES);
    }

    /** 구매자가 보유한(시청할 수 있는) 항목. 최근 구매순으로 고정한다 */
    default Page<OrderItem> findHeldItems(Long buyerUserId, Pageable pageable) {
        return findItems(buyerUserId, HELD_STATUSES, pageable);
    }

    /** 주어진 강의 중 구매자가 보유한 강의 ID (주문 생성 검사용) */
    default Set<Long> findHeldLectureIds(Long buyerUserId, Collection<Long> lectureIds) {
        return findLectureIds(buyerUserId, lectureIds, HELD_STATUSES);
    }

    @Query("select i.lectureId from OrderItem i "
            + "where i.buyerUserId = :buyerUserId and i.lectureId in :lectureIds and i.status in :statuses")
    Set<Long> findLectureIds(@Param("buyerUserId") Long buyerUserId, @Param("lectureIds") Collection<Long> lectureIds,
                             @Param("statuses") Set<OrderItemStatus> statuses);

    /** 이 주문 항목이 속한 주문 */
    @Query("select i.order from OrderItem i where i.id = :itemId")
    Optional<LectureOrder> findByItemId(@Param("itemId") Long itemId);

    /** 자동 구매 확정 대상(구매 상태이고 기한이 지난) 항목 ID. 기한이 빠른 순, 같으면 ID 순이다 (D41) */
    @Query("select i.id from OrderItem i "
            + "where i.status = com.hapbang.lecture.domain.OrderItemStatus.PURCHASED and i.autoConfirmAt <= :now "
            + "order by i.autoConfirmAt, i.id")
    List<Long> findAutoConfirmDueItemIds(@Param("now") LocalDateTime now);

    /**
     * 지급 대상(구매 확정 상태이고 지급 예정 시각이 지났으며 이의 제기가 없거나 기각되었고 지급이 격리되지 않은) 항목 ID.
     * 지급 예정 시각이 빠른 순, 같으면 ID 순이다 (D50, D54). 조건은 {@code OrderItem.isPayable}과 같다
     */
    @Query("select i.id from OrderItem i "
            + "where i.status = com.hapbang.lecture.domain.OrderItemStatus.CONFIRMED and i.payoutScheduledAt <= :now "
            + "and i.objectionStatus in (com.hapbang.lecture.domain.ObjectionStatus.NONE, "
            + "com.hapbang.lecture.domain.ObjectionStatus.REJECTED) "
            + "and i.payoutQuarantinedAt is null "
            + "order by i.payoutScheduledAt, i.id")
    List<Long> findPayoutDueItemIds(@Param("now") LocalDateTime now);

    /** 지급 실패 기록이 있는 미지급(구매 확정) 항목. 최근 실패순으로 고정한다 (관리자, D54) */
    @Query(value = "select i from OrderItem i "
            + "where i.status = com.hapbang.lecture.domain.OrderItemStatus.CONFIRMED and i.payoutFailureCount > 0 "
            + "order by i.lastPayoutFailedAt desc, i.id desc",
            countQuery = "select count(i) from OrderItem i "
                    + "where i.status = com.hapbang.lecture.domain.OrderItemStatus.CONFIRMED "
                    + "and i.payoutFailureCount > 0")
    Page<OrderItem> findPayoutFailures(Pageable pageable);

    /** 판매자의 판매 내역(모든 상태의 주문 항목). 최근 구매순으로 고정한다 (D51) */
    @Query("select i from OrderItem i where i.sellerUserId = :sellerUserId order by i.purchasedAt desc, i.id desc")
    Page<OrderItem> findSales(@Param("sellerUserId") Long sellerUserId, Pageable pageable);

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
