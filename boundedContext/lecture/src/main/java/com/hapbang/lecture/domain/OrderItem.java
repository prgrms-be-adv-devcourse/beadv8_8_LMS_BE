package com.hapbang.lecture.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 주문 항목(강의 하나). 구매 확정·구매 취소·지급·이의 제기는 항목 단위로 일어난다 (설계 4.6).
 * <ul>
 *     <li>주문 시점의 강의 제목·가격을 남긴다. 이후 강의 가격이 바뀌어도 반환·지급 금액은 이 가격이다.</li>
 *     <li>기한은 구매일(KST 날짜) 기준이다. 구매일 다음 날부터 7일이 지나면 자동 구매 확정 시각,
 *     30일이 지나면 지급 예정 시각이다 (설계 5장).</li>
 *     <li>기한 판정은 배치가 아니라 이 엔티티가 시각으로 한다.</li>
 * </ul>
 */
@Entity
@Table(name = "order_item", schema = "lectures")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderItem {

    /** 구매일 다음 날부터 7일이 지난 날의 00:00 = 구매일 + 8일 */
    private static final int AUTO_CONFIRM_DAYS = 8;
    /** 구매일 다음 날부터 30일이 지난 날의 00:00 = 구매일 + 31일 */
    private static final int PAYOUT_DAYS = 31;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private LectureOrder order;

    @Column(nullable = false)
    private Long lectureId;

    @Column(nullable = false)
    private Long sellerUserId;

    /** 보유 여부 부분 유일 인덱스용 (주문의 구매자와 같다) */
    @Column(nullable = false)
    private Long buyerUserId;

    @Column(nullable = false)
    private String lectureTitleSnapshot;

    @Column(nullable = false)
    private long priceHp;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderItemStatus status;

    @Column(nullable = false)
    private LocalDateTime purchasedAt;

    private LocalDateTime confirmedAt;

    @Enumerated(EnumType.STRING)
    private ConfirmationType confirmationType;

    @Column(nullable = false)
    private LocalDateTime autoConfirmAt;

    @Column(nullable = false)
    private LocalDateTime payoutScheduledAt;

    private LocalDateTime paidOutAt;

    private LocalDateTime canceledAt;

    private LocalDateTime returnedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ObjectionStatus objectionStatus;

    @Column(columnDefinition = "text")
    private String objectionReason;

    private LocalDateTime objectionFiledAt;

    private LocalDateTime objectionResolvedAt;

    @Version
    private Long version;

    OrderItem(LectureOrder order, Lecture lecture, LocalDateTime now) {
        this.order = order;
        this.lectureId = lecture.getId();
        this.sellerUserId = lecture.getSellerUserId();
        this.buyerUserId = order.getBuyerUserId();
        this.lectureTitleSnapshot = lecture.getTitle();
        this.priceHp = lecture.getPriceHp();
        this.status = OrderItemStatus.PURCHASED;
        this.objectionStatus = ObjectionStatus.NONE;
        stampPurchasedAt(now);
    }

    /** 아직 끝나지 않은 거래(구매, 구매 확정)인지 */
    public boolean isInProgress() {
        return status == OrderItemStatus.PURCHASED || status == OrderItemStatus.CONFIRMED;
    }

    /** 구매 취소·반환되어 HP가 구매자에게 돌아갔는지 */
    public boolean isWithdrawn() {
        return status == OrderItemStatus.CANCELED || status == OrderItemStatus.RETURNED;
    }

    /**
     * 직접 구매 확정. 자동 구매 확정 시각이 이미 지났으면(배치가 아직 처리하지 않은 경우)
     * 먼저 온 사건인 자동 확정으로 기록한다 (D34).
     */
    void confirm(LocalDateTime now) {
        requirePurchased();
        if (now.isBefore(autoConfirmAt)) {
            markConfirmed(ConfirmationType.DIRECT, now);
        } else {
            markConfirmed(ConfirmationType.AUTO, autoConfirmAt);
        }
    }

    /** 구매 취소. 자동 구매 확정 시각 전까지만 된다 */
    void cancel(LocalDateTime now) {
        requirePurchased();
        if (!now.isBefore(autoConfirmAt)) {
            throw new LectureException(LectureErrorCode.CANCEL_PERIOD_EXPIRED);
        }
        this.status = OrderItemStatus.CANCELED;
        this.canceledAt = now;
    }

    private void requirePurchased() {
        if (status != OrderItemStatus.PURCHASED) {
            throw new LectureException(LectureErrorCode.ORDER_ITEM_NOT_PURCHASED);
        }
    }

    private void markConfirmed(ConfirmationType type, LocalDateTime confirmedAt) {
        this.status = OrderItemStatus.CONFIRMED;
        this.confirmationType = type;
        this.confirmedAt = confirmedAt;
    }

    private void stampPurchasedAt(LocalDateTime now) {
        LocalDate purchaseDate = now.toLocalDate();
        this.purchasedAt = now;
        this.autoConfirmAt = purchaseDate.plusDays(AUTO_CONFIRM_DAYS).atStartOfDay();
        this.payoutScheduledAt = purchaseDate.plusDays(PAYOUT_DAYS).atStartOfDay();
    }
}
