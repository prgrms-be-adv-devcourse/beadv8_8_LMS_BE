package com.hapbang.lecture.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.BatchSize;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 강의 주문(결제 단위). 장바구니 주문과 바로 구매(강의 1개)를 같은 모델로 처리한다 (설계 4.6).
 * <ul>
 *     <li>주문은 HP 보류가 성공한 경우에만 남는다. 그래서 결제 대기·실패 상태가 없다.</li>
 *     <li>HP 보류는 주문 항목마다 한다 (D36).</li>
 *     <li>주문 상태는 저장값(PAID, CANCELED)이고, PAID인 주문의 진행 상황은 항목에서 파생한다.</li>
 *     <li>상태로 관리하고 삭제하지 않는다.</li>
 * </ul>
 */
@Entity
@Table(name = "lecture_order", schema = "lectures")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LectureOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long buyerUserId;

    @Column(nullable = false)
    private long totalAmountHp;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private LectureOrderStatus status;

    @Column(nullable = false)
    private LocalDateTime orderedAt;

    private LocalDateTime canceledAt;

    // 저장만 전파한다. 주문 항목은 삭제하지 않는다.
    // 주문 목록은 페이지의 주문 항목을 한 번에 읽는다(주문마다 따로 조회하지 않음). 페이지 크기가 이보다 크면 나눠 읽는다.
    @OneToMany(mappedBy = "order", cascade = CascadeType.PERSIST)
    @OrderBy("id")
    @BatchSize(size = 100)
    private List<OrderItem> items = new ArrayList<>();

    private LectureOrder(Long buyerUserId, LocalDateTime now) {
        this.buyerUserId = buyerUserId;
        this.status = LectureOrderStatus.PAID;
        this.orderedAt = now;
    }

    /**
     * 주문을 만든다. 강의 하나라도 주문할 수 없으면 주문 전체를 거부한다 (D31).
     * 검사 순서는 장바구니 담기와 같이 본인 강의 → 판매 중 → 보유 중이다.
     *
     * @param ownedLectureIds 구매자가 이미 보유한(PURCHASED·CONFIRMED·PAID_OUT 항목이 있는) 강의 ID
     */
    public static LectureOrder place(Long buyerUserId, List<Lecture> lectures, Set<Long> ownedLectureIds,
                                     LocalDateTime now) {
        validateLectures(buyerUserId, lectures, ownedLectureIds);
        LectureOrder order = new LectureOrder(buyerUserId, now);
        for (Lecture lecture : lectures) {
            order.items.add(new OrderItem(order, lecture, now));
            order.totalAmountHp += lecture.getPriceHp();
        }
        return order;
    }

    public List<OrderItem> getItems() {
        return List.copyOf(items);
    }

    /**
     * 모든 항목의 HP 보류가 성공한 뒤의 시각으로 주문 시각과 항목의 구매 시각·기한을 다시 정한다 (D35).
     * 보류에 주문 항목 ID가 필요해 주문을 먼저 저장하므로, 저장할 때 넣은 시각을 이 시각으로 바꾼다.
     */
    public void stampPaidAt(LocalDateTime now) {
        this.orderedAt = now;
        items.forEach(item -> item.stampPurchasedAt(now));
    }

    /** 구매자의 직접 구매 확정. 자동 구매 확정 시각이 지났으면 자동 확정으로 기록한다 (D34) */
    public OrderItem confirmItem(Long orderItemId, LocalDateTime now) {
        OrderItem item = findItem(orderItemId);
        item.confirm(now);
        return item;
    }

    /** 재생 동의. 구매 확정(첫 재생)으로 기록하고, 이미 확정·지급된 항목은 그대로 둔다 (D38) */
    public OrderItem consentToPlay(Long orderItemId, LocalDateTime now) {
        OrderItem item = findItem(orderItemId);
        item.consentToPlay(now);
        return item;
    }

    /** 자동 구매 확정(배치). 기한 전이거나 이미 구매 상태가 아닌 항목은 그대로 둔다 (D41) */
    public OrderItem autoConfirmItem(Long orderItemId, LocalDateTime now) {
        OrderItem item = findItem(orderItemId);
        item.autoConfirm(now);
        return item;
    }

    /**
     * 지급(배치). 지급 대상이면 {@code PAID_OUT}으로 바꾼 항목을, 아니면(배치가 조회한 뒤 이의 제기가 먼저 접수된 경우 등)
     * 그대로 두고 빈 값을 돌려준다 (D50). 지급은 주문을 닫지 않는다. 보류 HP 지급은 호출한 쪽이 같은 트랜잭션에서 한다
     */
    public Optional<OrderItem> payOutItem(Long orderItemId, LocalDateTime now) {
        OrderItem item = findItem(orderItemId);
        if (!item.isPayable(now)) {
            return Optional.empty();
        }
        item.payOut(now);
        return Optional.of(item);
    }

    /**
     * 지급 실패 기록(배치, D54). 기록하면 그 항목을, 지급 대상이 아니게 되어 기록하지 않으면 빈 값을 돌려준다.
     * 주문을 닫지 않는다
     */
    public Optional<OrderItem> recordPayoutFailure(Long orderItemId, String code, boolean quarantine,
                                                   LocalDateTime now) {
        OrderItem item = findItem(orderItemId);
        return item.recordPayoutFailure(code, quarantine, now) ? Optional.of(item) : Optional.empty();
    }

    /** 지급 격리 해제(관리자, D54). 주문을 닫지 않는다 */
    public OrderItem releasePayoutQuarantine(Long orderItemId) {
        OrderItem item = findItem(orderItemId);
        item.releasePayoutQuarantine();
        return item;
    }

    /** 구매 취소. 끝나지 않은 항목이 남지 않으면 주문도 취소한다. 보류 HP 반환은 호출한 쪽이 같은 트랜잭션에서 한다 */
    public OrderItem cancelItem(Long orderItemId, LocalDateTime now) {
        OrderItem item = findItem(orderItemId);
        item.cancel(now);
        closeIfAllItemsEnded(now);
        return item;
    }

    /** 내용 다름 이의 제기 접수. 접수된 항목은 지급·구매 취소되지 않는다 (설계 6.2) */
    public OrderItem fileObjection(Long orderItemId, Long buyerUserId, Long inquiryId, String reason,
                                   LocalDateTime now) {
        OrderItem item = findItem(orderItemId);
        item.fileObjection(buyerUserId, inquiryId, reason, now);
        return item;
    }

    /**
     * 이의 제기 판단. 인정해서 끝나지 않은 항목이 남지 않으면 주문도 취소한다.
     * 보류 HP 반환은 호출한 쪽이 같은 트랜잭션에서 한다
     */
    public OrderItem resolveObjection(Long orderItemId, Long inquiryId, boolean accepted, Long adminUserId,
                                      LocalDateTime now) {
        OrderItem item = findItem(orderItemId);
        item.resolveObjection(inquiryId, accepted, adminUserId, now);
        if (accepted) {
            closeIfAllItemsEnded(now);
        }
        return item;
    }

    /** 판단 전 이의 제기 철회. 항목은 접수 전 상태로 돌아가고 주문은 닫히지 않는다 (D52) */
    public OrderItem withdrawObjection(Long orderItemId, Long buyerUserId, Long inquiryId) {
        OrderItem item = findItem(orderItemId);
        item.withdrawObjection(buyerUserId, inquiryId);
        return item;
    }

    /** PAID인 주문의 진행 상황. 취소된 주문은 {@code null} */
    public OrderProgress progress() {
        if (status == LectureOrderStatus.CANCELED) {
            return null;
        }
        return items.stream().anyMatch(OrderItem::isInProgress) ? OrderProgress.IN_PROGRESS : OrderProgress.COMPLETED;
    }

    private OrderItem findItem(Long orderItemId) {
        return items.stream()
                .filter(item -> item.getId().equals(orderItemId))
                .findFirst()
                .orElseThrow(() -> new LectureException(LectureErrorCode.ORDER_ITEM_NOT_FOUND));
    }

    // 구매 취소·이의 제기 인정이 모두 이 메서드로 주문을 닫는다 (설계 4.6)
    private void closeIfAllItemsEnded(LocalDateTime now) {
        if (items.stream().allMatch(OrderItem::isWithdrawn)) {
            this.status = LectureOrderStatus.CANCELED;
            this.canceledAt = now;
        }
    }

    private static void validateLectures(Long buyerUserId, List<Lecture> lectures, Set<Long> ownedLectureIds) {
        if (buyerUserId == null || lectures.isEmpty()) {
            throw new LectureException(LectureErrorCode.INVALID_REQUEST);
        }
        if (lectures.stream().map(Lecture::getId).distinct().count() != lectures.size()) {
            throw new LectureException(LectureErrorCode.INVALID_REQUEST, "한 주문에 같은 강의를 두 번 넣을 수 없습니다.");
        }
        if (lectures.stream().anyMatch(lecture -> lecture.isSeller(buyerUserId))) {
            throw new LectureException(LectureErrorCode.OWN_LECTURE_NOT_PURCHASABLE);
        }
        // 판매 중이 아닌 강의는 상세 조회·장바구니처럼 없는 강의로 본다
        if (!lectures.stream().allMatch(Lecture::isOnSale)) {
            throw new LectureException(LectureErrorCode.LECTURE_NOT_FOUND);
        }
        if (lectures.stream().anyMatch(lecture -> ownedLectureIds.contains(lecture.getId()))) {
            throw new LectureException(LectureErrorCode.ALREADY_OWNED_LECTURE);
        }
    }
}
