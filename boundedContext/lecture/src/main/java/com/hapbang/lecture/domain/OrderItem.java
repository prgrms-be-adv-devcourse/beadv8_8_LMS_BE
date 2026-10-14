package com.hapbang.lecture.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Objects;

import com.hapbang.shared.lecture.exception.LectureObjectionRefusedException;
import com.hapbang.shared.lecture.exception.LectureObjectionRefusedException.Reason;

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
@Table(name = "order_item", schema = "lectures",
        // 판매 내역 조회용 (D51)
        indexes = @Index(name = "idx_order_item_seller_purchased_at",
                columnList = "seller_user_id, purchased_at DESC, id DESC"))
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

    /** 이의 제기를 접수한 고객센터 문의 ID. 판단할 때 같은 문의인지 대조한다 */
    private Long objectionInquiryId;

    private LocalDateTime objectionResolvedAt;

    /** 이의 제기를 판단한 관리자 */
    private Long objectionResolvedByUserId;

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

    /** 구매자가 보유 중인지(구매, 구매 확정, 지급). 보유 중이면 시청할 수 있고 다시 살 수 없다 (D5) */
    public boolean isHeld() {
        return status == OrderItemStatus.PURCHASED || status == OrderItemStatus.CONFIRMED
                || status == OrderItemStatus.PAID_OUT;
    }

    /** 영상·자료를 보기 전에 재생 동의가 필요한지 (D7·D8) */
    public boolean requiresPlayConsent() {
        return status == OrderItemStatus.PURCHASED;
    }

    /** 리뷰를 쓸 수 있는지. 구매 확정 후(구매 확정, 지급)에만 쓴다 (D5) */
    public boolean isReviewable() {
        return status == OrderItemStatus.CONFIRMED || status == OrderItemStatus.PAID_OUT;
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
        confirmFirstOf(ConfirmationType.DIRECT, now);
    }

    /**
     * 재생 동의(첫 재생 확정, D7). 직접 확정과 같이 자동 구매 확정 시각이 이미 지났으면 자동 확정으로 기록한다 (D34, D38).
     * 이미 구매 확정·지급된 항목은 그대로 둔다(멱등).
     */
    void consentToPlay(LocalDateTime now) {
        if (status == OrderItemStatus.CONFIRMED || status == OrderItemStatus.PAID_OUT) {
            return;
        }
        requirePurchased();
        confirmFirstOf(ConfirmationType.FIRST_PLAY, now);
    }

    /**
     * 자동 구매 확정(배치, #149). 직접 확정과 같이 {@code AUTO}, {@code confirmedAt = autoConfirmAt}으로 기록한다 (D34).
     * 구매 상태가 아니거나(배치가 조회한 뒤 직접 확정·재생 동의·구매 취소가 먼저 된 경우) 기한 전이면 그대로 둔다(D41).
     */
    void autoConfirm(LocalDateTime now) {
        if (status != OrderItemStatus.PURCHASED || now.isBefore(autoConfirmAt)) {
            return;
        }
        markConfirmed(ConfirmationType.AUTO, autoConfirmAt);
    }

    /**
     * 지급 대상인지 (설계 4.6 불변식). 구매 확정 상태이고 지급 예정 시각이 지났으며, 이의 제기가 없거나 기각된 항목이다.
     * 이의 제기 접수 중({@code FILED})이면 지급을 멈추고, 인정({@code ACCEPTED})되면 반환 대상이다.
     */
    public boolean isPayable(LocalDateTime now) {
        return status == OrderItemStatus.CONFIRMED
                && !now.isBefore(payoutScheduledAt)
                && (objectionStatus == ObjectionStatus.NONE || objectionStatus == ObjectionStatus.REJECTED);
    }

    /** 지급(배치, #150). 판매자에게 보류 HP를 옮기는 billing 호출은 호출한 쪽이 같은 트랜잭션에서 한다 (D50) */
    void payOut(LocalDateTime now) {
        if (!isPayable(now)) {
            throw new IllegalStateException("지급 대상이 아닌 주문 항목입니다: " + id);
        }
        this.status = OrderItemStatus.PAID_OUT;
        this.paidOutAt = now;
    }

    /** 구매 취소. 자동 구매 확정 시각 전까지, 이의 제기가 없는(접수 후 철회 포함) 항목만 된다 (D44, D52) */
    void cancel(LocalDateTime now) {
        requirePurchased();
        if (!now.isBefore(autoConfirmAt)) {
            throw new LectureException(LectureErrorCode.CANCEL_PERIOD_EXPIRED);
        }
        if (objectionStatus != ObjectionStatus.NONE) {
            throw new LectureException(LectureErrorCode.OBJECTION_FILED);
        }
        this.status = OrderItemStatus.CANCELED;
        this.canceledAt = now;
    }

    /**
     * 내용 다름 이의 제기 접수(고객센터 호출, 설계 6.2). 접수되면 지급과 구매 취소가 멈춘다.
     * 검사 순서는 상태 → 기한이다(D49): 구매자 → 취소·반환 → 지급 → 이의 제기 이력 → 지급 예정 시각.
     */
    void fileObjection(Long buyerUserId, Long inquiryId, String reason, LocalDateTime now) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("이의 제기 사유는 필수입니다.");
        }
        if (!this.buyerUserId.equals(buyerUserId)) {
            throw new LectureObjectionRefusedException(Reason.NOT_BUYER);
        }
        if (isWithdrawn()) {
            throw new LectureObjectionRefusedException(Reason.NOT_OBJECTIONABLE);
        }
        if (status == OrderItemStatus.PAID_OUT) {
            throw new LectureObjectionRefusedException(Reason.ALREADY_PAID_OUT);
        }
        if (objectionStatus != ObjectionStatus.NONE) {
            throw new LectureObjectionRefusedException(Reason.ALREADY_FILED);
        }
        if (!now.isBefore(payoutScheduledAt)) {
            throw new LectureObjectionRefusedException(Reason.PERIOD_EXPIRED);
        }
        this.objectionStatus = ObjectionStatus.FILED;
        this.objectionInquiryId = Objects.requireNonNull(inquiryId, "문의 ID는 필수입니다.");
        this.objectionReason = reason;
        this.objectionFiledAt = now;
    }

    /**
     * 이의 제기 판단(고객센터 호출, 설계 6.2). 접수된 항목이고 접수한 문의일 때만 처리한다.
     * 인정하면 항목을 반환하고, 보류 HP 반환은 호출한 쪽이 같은 트랜잭션에서 한다 (D3).
     * 기각하면 항목 상태는 그대로이고 지급 예정 시각이 되면 지급 배치가 지급한다.
     */
    void resolveObjection(Long inquiryId, boolean accepted, Long adminUserId, LocalDateTime now) {
        Objects.requireNonNull(adminUserId, "판단한 관리자 ID는 필수입니다.");
        if (objectionStatus != ObjectionStatus.FILED) {
            throw new LectureObjectionRefusedException(Reason.NOT_FILED);
        }
        if (!objectionInquiryId.equals(inquiryId)) {
            throw new LectureObjectionRefusedException(Reason.INQUIRY_MISMATCH);
        }
        if (accepted) {
            // 접수 중에는 구매 취소·지급이 멈추므로 끝난 거래일 수 없다
            if (!isInProgress()) {
                throw new IllegalStateException("이의 제기가 접수된 항목이 진행 중이 아닙니다. orderItemId=" + id
                        + ", status=" + status);
            }
            this.objectionStatus = ObjectionStatus.ACCEPTED;
            this.status = OrderItemStatus.RETURNED;
            this.returnedAt = now;
        } else {
            this.objectionStatus = ObjectionStatus.REJECTED;
        }
        this.objectionResolvedAt = now;
        this.objectionResolvedByUserId = adminUserId;
    }

    /**
     * 판단 전 이의 제기 철회(고객센터 문의 삭제, #203). 접수 전 상태({@code NONE})로 되돌려 지급·구매 취소·재접수가
     * 원래 기한 그대로 다시 가능해진다 (D52). 항목의 {@link #isWithdrawn()}(구매 취소·반환)과는 다른 말이다.
     * 검사 순서: 구매자 → 접수 중인지 → 접수한 문의인지. HP는 움직이지 않는다(보류 유지).
     */
    void withdrawObjection(Long buyerUserId, Long inquiryId) {
        if (!this.buyerUserId.equals(buyerUserId)) {
            throw new LectureObjectionRefusedException(Reason.NOT_BUYER);
        }
        if (objectionStatus != ObjectionStatus.FILED) {
            throw new LectureObjectionRefusedException(Reason.NOT_FILED);
        }
        if (!objectionInquiryId.equals(inquiryId)) {
            throw new LectureObjectionRefusedException(Reason.INQUIRY_MISMATCH);
        }
        this.objectionStatus = ObjectionStatus.NONE;
        this.objectionInquiryId = null;
        this.objectionReason = null;
        this.objectionFiledAt = null;
    }

    private void requirePurchased() {
        if (status != OrderItemStatus.PURCHASED) {
            throw new LectureException(LectureErrorCode.ORDER_ITEM_NOT_PURCHASED);
        }
    }

    /**
     * 구매 확정은 직접 확정·첫 재생·자동 확정 중 먼저 온 것이다. 자동 구매 확정 시각이 지났으면(배치가 아직 처리하지 않은 경우)
     * 먼저 온 사건인 자동 확정으로 기록해, 배치 실행 시점과 관계없이 같은 결과가 되게 한다.
     */
    private void confirmFirstOf(ConfirmationType type, LocalDateTime now) {
        if (now.isBefore(autoConfirmAt)) {
            markConfirmed(type, now);
        } else {
            markConfirmed(ConfirmationType.AUTO, autoConfirmAt);
        }
    }

    private void markConfirmed(ConfirmationType type, LocalDateTime confirmedAt) {
        this.status = OrderItemStatus.CONFIRMED;
        this.confirmationType = type;
        this.confirmedAt = confirmedAt;
    }

    /** 구매 시각과 그로부터 계산하는 기한을 정한다 (설계 5장) */
    void stampPurchasedAt(LocalDateTime now) {
        LocalDate purchaseDate = now.toLocalDate();
        this.purchasedAt = now;
        this.autoConfirmAt = purchaseDate.plusDays(AUTO_CONFIRM_DAYS).atStartOfDay();
        this.payoutScheduledAt = purchaseDate.plusDays(PAYOUT_DAYS).atStartOfDay();
    }
}
