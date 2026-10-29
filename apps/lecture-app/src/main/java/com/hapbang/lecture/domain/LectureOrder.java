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
 *     <li>주문은 HP 보류보다 먼저 결제 처리 중({@code HOLD_PENDING})으로 저장되고, 모든 항목의 보류가 성공해야
 *     {@code PAID}가 된다. 거절되면 결제 취소 처리 중을 거쳐 결제 실패({@code HOLD_FAILED})다 (D58).</li>
 *     <li>HP 보류는 주문 항목마다 한다 (D36). 항목 상태는 결제 단계를 그대로 복제한다.</li>
 *     <li>주문 상태는 저장값이고, PAID인 주문의 진행 상황은 항목에서 파생한다.</li>
 *     <li>상태로 관리하고 삭제하지 않는다. 결제 실패한 주문도 남긴다.</li>
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

    /** 주문을 시도한 시각 (D58 (5)) */
    @Column(nullable = false)
    private LocalDateTime orderedAt;

    /** 모든 보류가 성공해 {@code PAID}가 된 시각. 그 전에는 {@code null}이다 */
    private LocalDateTime paidAt;

    private LocalDateTime canceledAt;

    // 저장만 전파한다. 주문 항목은 삭제하지 않는다.
    // 주문 목록은 페이지의 주문 항목을 한 번에 읽는다(주문마다 따로 조회하지 않음). 페이지 크기가 이보다 크면 나눠 읽는다.
    @OneToMany(mappedBy = "order", cascade = CascadeType.PERSIST)
    @OrderBy("id")
    @BatchSize(size = 100)
    private List<OrderItem> items = new ArrayList<>();

    private LectureOrder(Long buyerUserId, LocalDateTime now) {
        this.buyerUserId = buyerUserId;
        this.status = LectureOrderStatus.HOLD_PENDING;
        this.orderedAt = now;
    }

    /**
     * 주문을 만든다. 강의 하나라도 주문할 수 없으면 주문 전체를 거부한다 (D31).
     * 검사 순서는 장바구니 담기와 같이 본인 강의 → 판매 중 → 보유 중이다.
     *
     * @param ownedLectureIds 구매자가 이미 보유한(결제 처리 중부터 지급까지 항목이 있는) 강의 ID
     */
    public static LectureOrder place(Long buyerUserId, List<Lecture> lectures, Set<Long> ownedLectureIds,
                                     LocalDateTime now) {
        validateLectures(buyerUserId, lectures, ownedLectureIds);
        LectureOrder order = new LectureOrder(buyerUserId, now);
        for (Lecture lecture : lectures) {
            order.items.add(new OrderItem(order, lecture));
            order.totalAmountHp += lecture.getPriceHp();
        }
        return order;
    }

    public List<OrderItem> getItems() {
        return List.copyOf(items);
    }

    /**
     * 모든 항목의 HP 보류가 성공했다. 이 시각으로 결제 시각과 항목의 구매 시각·기한을 정한다 (D35, D69 (7)).
     * 주문 시각은 주문을 시도한 시각 그대로다
     */
    public void markPaid(LocalDateTime now) {
        requireStatus(LectureOrderStatus.HOLD_PENDING);
        this.status = LectureOrderStatus.PAID;
        this.paidAt = now;
        items.forEach(item -> item.markPurchased(now));
    }

    /** 보류 하나 이상이 확정 거절되어, 이미 성공한 보류를 반환한다 (D58 (3)) */
    public void startHoldCompensation() {
        requireStatus(LectureOrderStatus.HOLD_PENDING);
        this.status = LectureOrderStatus.HOLD_COMPENSATING;
        items.forEach(item -> item.changeHoldStage(OrderItemStatus.HOLD_COMPENSATING));
    }

    /** 결제 실패. 묶여 남은 보류 HP가 없음을 호출한 쪽이 확인했다 (D58 (3)) */
    public void markHoldFailed() {
        if (!status.isHoldInProgress()) {
            throw new IllegalStateException("결제 단계가 아닌 주문은 결제 실패로 바꿀 수 없습니다. orderId=" + id
                    + ", status=" + status);
        }
        this.status = LectureOrderStatus.HOLD_FAILED;
        items.forEach(item -> item.changeHoldStage(OrderItemStatus.HOLD_FAILED));
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

    /** PAID인 주문의 진행 상황. 결제 단계·취소된 주문은 {@code null} */
    public OrderProgress progress() {
        if (status != LectureOrderStatus.PAID) {
            return null;
        }
        return items.stream().anyMatch(OrderItem::isInProgress) ? OrderProgress.IN_PROGRESS : OrderProgress.COMPLETED;
    }

    private void requireStatus(LectureOrderStatus expected) {
        if (status != expected) {
            throw new IllegalStateException("주문 상태가 " + expected + "가 아닙니다. orderId=" + id + ", status=" + status);
        }
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
