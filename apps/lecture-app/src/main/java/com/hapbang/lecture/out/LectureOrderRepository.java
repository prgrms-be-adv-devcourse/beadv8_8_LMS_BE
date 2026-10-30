package com.hapbang.lecture.out;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.hapbang.lecture.domain.LectureOrder;
import com.hapbang.lecture.domain.OrderItem;
import com.hapbang.lecture.domain.OrderItemStatus;
import com.hapbang.shared.lecture.domain.LectureEarningStatus;

import jakarta.persistence.LockModeType;

public interface LectureOrderRepository extends JpaRepository<LectureOrder, Long> {

    /**
     * 보유 중인 항목 상태 (D5, D69 (4)). 같은 강의를 다시 살 수 없다. 부분 유일 인덱스 {@code uk_order_item_buyer_lecture_owned}의
     * 조건과 같다
     */
    Set<OrderItemStatus> HELD_STATUSES = Arrays.stream(OrderItemStatus.values())
            .filter(OrderItemStatus::isHeld)
            .collect(Collectors.toUnmodifiableSet());

    /** 시청 가능한 항목 상태 (D69 (4)). 보유와 달리 결제가 끝나지 않은 항목은 빠진다 */
    Set<OrderItemStatus> VIEWABLE_STATUSES = Arrays.stream(OrderItemStatus.values())
            .filter(OrderItemStatus::isViewable)
            .collect(Collectors.toUnmodifiableSet());

    /** 팔린 항목(결제가 끝난 항목). 판매 내역·판매 수익은 결제가 끝나지 않은 항목을 뺀다 (D58 (8), D69 (3)) */
    String SOLD = "i.status not in (com.hapbang.lecture.domain.OrderItemStatus.HOLD_PENDING, "
            + "com.hapbang.lecture.domain.OrderItemStatus.HOLD_COMPENSATING, "
            + "com.hapbang.lecture.domain.OrderItemStatus.HOLD_FAILED)";

    // 판매 수익 분류 조건 (#300). 상태와 상태 변경 시각이 같은 조건을 같은 순서로 쓴다
    String EARNING_ON_HOLD = "i.objectionStatus = com.hapbang.lecture.domain.ObjectionStatus.FILED";
    String EARNING_PURCHASED = "i.status = com.hapbang.lecture.domain.OrderItemStatus.PURCHASED";
    String EARNING_FAILED = "i.status = com.hapbang.lecture.domain.OrderItemStatus.CONFIRMED "
            + "and i.payoutQuarantinedAt is not null";
    String EARNING_DUE = "i.status = com.hapbang.lecture.domain.OrderItemStatus.CONFIRMED "
            + "and i.payoutScheduledAt <= :now";
    String EARNING_CONFIRMED = "i.status = com.hapbang.lecture.domain.OrderItemStatus.CONFIRMED";
    String EARNING_PAID_OUT = "i.status = com.hapbang.lecture.domain.OrderItemStatus.PAID_OUT";

    /**
     * 판매 수익 상태 (#300). 요약·목록·필터가 모두 이 식 하나로 분류한다. 위에서부터 먼저 맞는 조건을 쓴다.
     * 이의 제기 접수 중이면 구매 확정 전후와 관계없이 지급 중단(구매 취소도 막힌다, D44), 지급이 격리되었으면 실패다.
     * 처리 중은 지급 대상이라 {@link #findPayoutDueItemIds}·{@code OrderItem.isPayable}과 같은 조건이다. 일시 실패와
     * 격리 해제 뒤의 항목은 다음 지급 배치가 다시 시도하므로 처리 중이다 (D54).
     * 구매 취소·반환은 모두 {@code CANCELED}다. Hibernate가 매핑하지 않은 enum은 식의 결과로 쓸 수 없어
     * {@link LectureEarningStatus} 상수 이름 문자열을 낸다
     */
    String EARNING_STATUS = "case "
            + "when " + EARNING_ON_HOLD + " then 'ON_HOLD' "
            + "when " + EARNING_PURCHASED
            + " then 'CONFIRMATION_PENDING' "
            + "when " + EARNING_FAILED + " then 'FAILED' "
            + "when " + EARNING_DUE + " then 'PROCESSING' "
            + "when " + EARNING_CONFIRMED + " then 'SCHEDULED' "
            + "when " + EARNING_PAID_OUT + " then 'COMPLETED' "
            + "else 'CANCELED' end";

    /**
     * 판매 수익 상태가 바뀐 시각 (#300). {@link #EARNING_STATUS}와 같은 순서다. 이의 제기가 기각되면 그 시각부터 원래 상태다.
     * 이의 제기 철회·격리 해제는 시각이 남지 않아 이전 시각 그대로다
     */
    String EARNING_STATUS_UPDATED_AT = "case "
            + "when " + EARNING_ON_HOLD + " then i.objectionFiledAt "
            + "when " + EARNING_PURCHASED + " then greatest(i.purchasedAt, coalesce(i.objectionResolvedAt, i.purchasedAt)) "
            + "when " + EARNING_FAILED + " then i.payoutQuarantinedAt "
            + "when " + EARNING_DUE
            + " then greatest(i.payoutScheduledAt, coalesce(i.objectionResolvedAt, i.payoutScheduledAt)) "
            + "when " + EARNING_CONFIRMED + " then greatest(i.confirmedAt, coalesce(i.objectionResolvedAt, i.confirmedAt)) "
            + "when " + EARNING_PAID_OUT + " then i.paidOutAt "
            + "else coalesce(i.canceledAt, i.returnedAt) end";

    /** 아직 지급하지 않은 판매 수익 상태. 요약은 이 상태만 합한다 */
    Set<LectureEarningStatus> UNPAID_EARNING_STATUSES = Arrays.stream(LectureEarningStatus.values())
            .filter(LectureEarningStatus::isUnpaid)
            .collect(Collectors.toUnmodifiableSet());

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

    /** 구매자가 이 강의를 시청할 수 있는 항목. 부분 유일 인덱스로 많아야 하나다 */
    default Optional<OrderItem> findViewableItem(Long buyerUserId, Long lectureId) {
        return findItem(buyerUserId, lectureId, VIEWABLE_STATUSES);
    }

    /** 구매자가 시청할 수 있는 항목. 최근 구매순으로 고정한다 */
    default Page<OrderItem> findViewableItems(Long buyerUserId, Pageable pageable) {
        return findItems(buyerUserId, VIEWABLE_STATUSES, pageable);
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

    /** 판매자의 판매 내역(결제가 끝난 모든 상태의 주문 항목). 최근 구매순으로 고정한다 (D51, D58 (8)) */
    @Query("select i from OrderItem i where i.sellerUserId = :sellerUserId and " + SOLD
            + " order by i.purchasedAt desc, i.id desc")
    Page<OrderItem> findSales(@Param("sellerUserId") Long sellerUserId, Pageable pageable);

    /** 판매자의 아직 지급하지 않은 판매 수익을 상태별로 합한다. 수익이 없는 상태는 빠진다 (#300) */
    default List<EarningStatusTotal> summarizeEarnings(Long sellerUserId, LocalDateTime now) {
        return summarizeEarningsByName(sellerUserId, names(UNPAID_EARNING_STATUSES), now);
    }

    // 분류 식을 group by에 다시 쓰면 :now가 다른 바인드 변수가 되어 PostgreSQL이 같은 식으로 보지 않는다. 파생 테이블로 한 번만 쓴다
    @Query("select new com.hapbang.lecture.out.EarningStatusTotal(e.status, count(*), sum(e.priceHp)) "
            + "from (select " + EARNING_STATUS + " as status, i.priceHp as priceHp from OrderItem i "
            + "where i.sellerUserId = :sellerUserId and " + SOLD + ") e "
            + "where e.status in :statuses group by e.status")
    List<EarningStatusTotal> summarizeEarningsByName(@Param("sellerUserId") Long sellerUserId,
                                                     @Param("statuses") Collection<String> statuses,
                                                     @Param("now") LocalDateTime now);

    /** 판매자의 판매 수익 중 주어진 상태의 항목. 상태 변경 최신순, 같으면 항목 ID 역순으로 고정한다 (#300) */
    default Page<EarningRow> findEarnings(Long sellerUserId, Collection<LectureEarningStatus> statuses,
                                          LocalDateTime now, Pageable pageable) {
        return findEarningsByName(sellerUserId, names(statuses), now, pageable);
    }

    @Query(value = "select new com.hapbang.lecture.out.EarningRow(i, " + EARNING_STATUS + ", "
            + EARNING_STATUS_UPDATED_AT + ") from OrderItem i "
            + "where i.sellerUserId = :sellerUserId and " + SOLD + " and " + EARNING_STATUS + " in :statuses "
            + "order by " + EARNING_STATUS_UPDATED_AT + " desc, i.id desc",
            countQuery = "select count(i) from OrderItem i "
                    + "where i.sellerUserId = :sellerUserId and " + SOLD + " and " + EARNING_STATUS + " in :statuses")
    Page<EarningRow> findEarningsByName(@Param("sellerUserId") Long sellerUserId,
                                        @Param("statuses") Collection<String> statuses,
                                        @Param("now") LocalDateTime now, Pageable pageable);

    private static Set<String> names(Collection<LectureEarningStatus> statuses) {
        return statuses.stream().map(Enum::name).collect(Collectors.toUnmodifiableSet());
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
